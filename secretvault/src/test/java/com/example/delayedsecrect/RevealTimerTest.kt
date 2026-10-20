package com.example.delayedsecrect

import org.junit.Assert.*
import org.junit.Test

class RevealTimerTest {
    private var now = 10_000L
    private val records = mutableMapOf<String, TimerRecord>()
    private val store = object : TimerPersistence {
        override fun get(secretId: String) = records[secretId]
        override fun save(secretId: String, record: TimerRecord) { records[secretId] = record }
    }
    private fun timer() = RevealTimer("task", 60_000, 20_000, store, { now }, { 1_000_000 + now })

    @Test fun backgroundReadinessNeverClaimsOrExtendsTheExpiryWindow() {
        val timer = timer()
        timer.startCountdown()
        now += 59_999
        assertEquals(RevealState.COUNTING_DOWN, timer.currentState(false).state)
        now++
        val ready = timer.currentState(false)
        assertEquals(RevealState.REVEALED, ready.state)
        assertEquals(0L, ready.hideAtElapsedMs)
        assertEquals(0L, records.getValue("task").hideAtElapsedMs)
        now += CLAIM_WINDOW_MS - 1
        assertEquals(RevealState.REVEALED, timer().currentState(false).state)
        now++
        assertEquals(RevealState.LOCKED, timer().currentState(false).state)
    }

    @Test fun explicitClaimUsesTheExistingVisiblePeriodAndNeverRefreshesIt() {
        val timer = timer()
        timer.startCountdown()
        now += 60_000
        val expiry = timer.currentState().hideAtElapsedMs
        assertEquals(now + 20_000, expiry)
        now += 19_999
        assertEquals(expiry, timer().currentState(false).hideAtElapsedMs)
        assertEquals(expiry, timer().currentState().hideAtElapsedMs)
        now++
        assertEquals(RevealState.LOCKED, timer().currentState().state)
    }

    @Test fun hardLockCancelsTheRequestCycleAndCannotBeBypassedByClaimingOrRestarting() {
        val timer = timer()
        timer.startCountdown()
        timer.hardLock(3_600_000)
        timer.startCountdown()
        timer.cancel()
        timer.closeEarly()
        assertEquals(RevealState.HARD_LOCKED, timer.currentState(false).state)
        assertEquals(RevealState.HARD_LOCKED, timer().currentState().state)
        assertFalse(records.getValue("task").started)
    }

    @Test fun closingAfterDeliveryRequiresAnotherFullCountdown() {
        val timer = timer()
        timer.startCountdown()
        now += 60_000
        assertEquals(RevealState.REVEALED, timer.currentState().state)
        timer.closeEarly()
        val next = timer()
        assertEquals(RevealState.LOCKED, next.currentState(false).state)
        next.startCountdown()
        assertEquals(now + 60_000, next.currentState(false).revealAtElapsedMs)
    }

    @Test fun manualAndLinkedViewsObserveTheSamePersistedClaimDeadline() {
        timer().startCountdown()
        now += 60_000
        assertEquals(0L, timer().currentState(false).hideAtElapsedMs)
        val manualDeadline = timer().currentState().hideAtElapsedMs
        now += 1000
        assertEquals(manualDeadline, timer().currentState(false).hideAtElapsedMs)
    }

    @Test fun extendingTheWaitKeepsTheCycleButRestartingCreatesADifferentCycle() {
        val timer = timer()
        timer.startCountdown()
        val cycle = records.getValue("task").cycleId
        assertTrue(cycle.isNotBlank())
        timer.extendCountdown(300_000)
        assertEquals(cycle, records.getValue("task").cycleId)
        timer.cancel()
        assertEquals("", records.getValue("task").cycleId)
        timer.startCountdown()
        assertNotEquals(cycle, records.getValue("task").cycleId)
    }
}
