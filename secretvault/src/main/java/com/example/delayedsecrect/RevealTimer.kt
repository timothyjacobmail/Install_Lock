package com.example.delayedsecrect

import android.os.SystemClock

const val CLAIM_WINDOW_MS = 15 * 60 * 1000L // unclaimed reveals expire 15 min after the countdown ends
enum class RevealState { LOCKED, COUNTING_DOWN, REVEALED, HARD_LOCKED }

data class TimerInfo(
    val state: RevealState,
    val revealAtElapsedMs: Long = 0L,
    val hideAtElapsedMs: Long = 0L,
    val hardLockUntilElapsedMs: Long = 0L
)

class RevealTimer(
    private val secretId: String,
    var delayMillis: Long,
    var visibleMillis: Long,
    private val store: TimerPersistence,
    private val elapsed: () -> Long = { SystemClock.elapsedRealtime() },
    private val wallClock: () -> Long = System::currentTimeMillis
) {
    private var started = false
    private var cycleId = ""
    private var revealAtElapsedMs = 0L
    private var hideAtElapsedMs = 0L
    private var revealAtWallClockMs = 0L
    private var hideAtWallClockMs = 0L

    private var hardLockUntilElapsedMs = 0L
    private var hardLockUntilWallClockMs = 0L
    private var hardLockBootReferenceMs = 0L

    init {
        reconcileFromDisk()
    }

    private fun reconcileFromDisk() {
        val record = store.get(secretId) ?: return

        val nowElapsed = elapsed()
        val nowWallClock = wallClock()
        val currentBootReference = nowWallClock - nowElapsed

        // Reconcile the hard lock first, independently of the reveal timer
        if (record.hardLockUntilElapsedMs != 0L) {
            val rebooted = kotlin.math.abs(currentBootReference - record.hardLockBootReferenceMs) > 3000
            hardLockUntilElapsedMs = if (!rebooted) record.hardLockUntilElapsedMs
            else nowElapsed + (record.hardLockUntilWallClockMs - nowWallClock)
            hardLockUntilWallClockMs = record.hardLockUntilWallClockMs
            hardLockBootReferenceMs = record.hardLockBootReferenceMs
        }

        if (!record.started) return
        val rebooted = kotlin.math.abs(currentBootReference - record.bootReferenceMs) > 3000

        started = true
        cycleId = record.cycleId.ifEmpty { "legacy:${record.revealAtWallClockMs}" }
        if (!rebooted) {
            revealAtElapsedMs = record.revealAtElapsedMs
            hideAtElapsedMs = record.hideAtElapsedMs
        } else {
            revealAtElapsedMs = nowElapsed + (record.revealAtWallClockMs - nowWallClock)
            hideAtElapsedMs = if (record.hideAtElapsedMs == 0L) 0L
            else nowElapsed + (record.hideAtWallClockMs - nowWallClock)
        }
        revealAtWallClockMs = record.revealAtWallClockMs
        hideAtWallClockMs = record.hideAtWallClockMs
    }

    private fun persist() {
        val nowElapsed = elapsed()
        val nowWallClock = wallClock()
        store.save(
            secretId,
            TimerRecord(
                started = started,
                revealAtElapsedMs = revealAtElapsedMs,
                hideAtElapsedMs = hideAtElapsedMs,
                revealAtWallClockMs = revealAtWallClockMs,
                hideAtWallClockMs = hideAtWallClockMs,
                bootReferenceMs = nowWallClock - nowElapsed,
                hardLockUntilElapsedMs = hardLockUntilElapsedMs,
                hardLockUntilWallClockMs = hardLockUntilWallClockMs,
                hardLockBootReferenceMs = hardLockBootReferenceMs,
                cycleId = cycleId
            )
        )
    }

    private fun isHardLockedNow(): Boolean {
        if (hardLockUntilElapsedMs == 0L) return false
        if (elapsed() >= hardLockUntilElapsedMs) {
            // expired naturally -> clear it
            hardLockUntilElapsedMs = 0L
            hardLockUntilWallClockMs = 0L
            hardLockBootReferenceMs = 0L
            persist()
            return false
        }
        return true
    }

    /** Locks this one secret for the given duration, no matter what state it's
     *  currently in. Overrides/cancels any active countdown or open reveal.
     *  There is deliberately NO function anywhere that undoes this early. */
    fun hardLock(durationMs: Long) {
        val nowElapsed = elapsed()
        val nowWallClock = wallClock()

        // Forcibly cancel any in-progress countdown/reveal
        started = false
        cycleId = ""
        revealAtElapsedMs = 0L
        hideAtElapsedMs = 0L
        revealAtWallClockMs = 0L
        hideAtWallClockMs = 0L

        hardLockUntilElapsedMs = nowElapsed + durationMs
        hardLockUntilWallClockMs = nowWallClock + durationMs
        hardLockBootReferenceMs = nowWallClock - nowElapsed
        persist()
    }

    fun startCountdown() {
        if (isHardLockedNow()) return // hard lock blocks everything
        cycleId = java.util.UUID.randomUUID().toString()
        val nowElapsed = elapsed()
        val nowWallClock = wallClock()
        revealAtElapsedMs = nowElapsed + delayMillis
        revealAtWallClockMs = nowWallClock + delayMillis
        hideAtElapsedMs = 0L
        hideAtWallClockMs = 0L
        started = true
        persist()
    }

    fun cancel() {
        if (isHardLockedNow()) return
        reset()
    }

    fun closeEarly() {
        if (isHardLockedNow()) return
        reset()
    }

    fun extendCountdown(extraMillis: Long) {
        if (isHardLockedNow()) return
        if (!started || hideAtElapsedMs != 0L) return
        revealAtElapsedMs += extraMillis
        revealAtWallClockMs += extraMillis
        persist()
    }

    private fun reset() {
        started = false
        cycleId = ""
        revealAtElapsedMs = 0L
        hideAtElapsedMs = 0L
        revealAtWallClockMs = 0L
        hideAtWallClockMs = 0L
        persist()
    }

    /** Background notifications inspect readiness without starting the visible/claim period. */
    fun currentState(claim: Boolean = true): TimerInfo {
        if (isHardLockedNow()) {
            return TimerInfo(RevealState.HARD_LOCKED, hardLockUntilElapsedMs = hardLockUntilElapsedMs)
        }

        if (!started) return TimerInfo(RevealState.LOCKED)

        val nowElapsed = elapsed()

        if (hideAtElapsedMs == 0L && nowElapsed < revealAtElapsedMs) {
            return TimerInfo(RevealState.COUNTING_DOWN, revealAtElapsedMs, 0L)
        }

        if (hideAtElapsedMs == 0L) {
            // Countdown is over but the reveal hasn't been claimed yet.
            // If the claim window has passed, the secret expires back to locked.
            if (nowElapsed >= revealAtElapsedMs + CLAIM_WINDOW_MS) {
                reset()
                return TimerInfo(RevealState.LOCKED)
            }
            if (!claim) return TimerInfo(RevealState.REVEALED, revealAtElapsedMs, 0L)
            hideAtElapsedMs = nowElapsed + visibleMillis
            hideAtWallClockMs = wallClock() + visibleMillis
            persist()
        }
        if (nowElapsed < hideAtElapsedMs) {
            return TimerInfo(RevealState.REVEALED, revealAtElapsedMs, hideAtElapsedMs)
        }

        reset()
        return TimerInfo(RevealState.LOCKED)
    }
}
