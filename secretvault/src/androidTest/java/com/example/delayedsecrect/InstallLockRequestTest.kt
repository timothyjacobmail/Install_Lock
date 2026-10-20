package com.example.delayedsecrect

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InstallLockRequestTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val names = mutableSetOf<String>()
    private val prefix = "install_lock_link_test_${System.nanoTime()}_"
    private val c = object : ContextWrapper(base) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            names.add(name)
            return base.getSharedPreferences(prefix + name, mode)
        }
    }
    @After fun clean() { names.forEach { base.getSharedPreferences(prefix + it, 0).edit().clear().commit() } }

    private fun readySecret(): Pair<Secret, TimerStore> {
        val secrets = SecretStore(c)
        secrets.add("Test task password", "TestValue123", null)
        val secret = secrets.getAll().single()
        val elapsed = SystemClock.elapsedRealtime()
        val wall = System.currentTimeMillis()
        val store = TimerStore(c)
        store.save(secret.id, TimerRecord(true, elapsed - 10, 0, wall - 10, 0,
            wall - elapsed, cycleId = "test-cycle"))
        return secret to store
    }

    @Test fun readinessDoesNotClaimAndRestartCannotReviveAnOldRequest() {
        val (secret, timers) = readySecret()
        val requests = InstallLockRequests(c)
        val request = InstallLockRequest("test", "KEYWORD_OFF|", "Test task", secret.id, "test-cycle")
        val ready = requests.availability(request)!!.second
        assertEquals(RevealState.REVEALED, ready.state)
        assertEquals(0L, timers.get(secret.id)!!.hideAtElapsedMs)
        requests.timer(secret).startCountdown()
        assertNull(requests.availability(request))
    }

    @Test fun expiredAndHardLockedSecretsCannotBeDelivered() {
        val (secret, timers) = readySecret()
        val requests = InstallLockRequests(c)
        val request = InstallLockRequest("test", "KEYWORD_OFF|", "Test task", secret.id, "test-cycle")
        val record = timers.get(secret.id)!!
        timers.save(secret.id, record.copy(revealAtElapsedMs = SystemClock.elapsedRealtime() - CLAIM_WINDOW_MS - 1,
            revealAtWallClockMs = System.currentTimeMillis() - CLAIM_WINDOW_MS - 1))
        assertNull(requests.availability(request))
        timers.save(secret.id, record)
        requests.timer(secret).hardLock(HARD_LOCK_MS)
        assertNull(requests.availability(request))
    }

    @Test fun inlineShowCopyAndUnlockShareOneDeadlineAndCompletionConsumesTheReveal() {
        val (secret, timers) = readySecret()
        val requests = InstallLockRequests(c)
        val id = java.util.UUID.randomUUID().toString()
        requests.receive(id, "KEYWORD_OFF|", "Test task")
        try {
            assertTrue(requests.select(id, secret.id))
            assertEquals(0L, timers.get(secret.id)!!.hideAtElapsedMs)
            val shown = requests.claimInline(id)!!
            assertEquals(secret.value, shown.value)
            assertTrue(shown.expiresElapsed > SystemClock.elapsedRealtime())
            assertEquals(shown, requests.claimInline(id)) // Copy/show must never refresh expiry.
            assertEquals(shown.expiresElapsed, timers.get(secret.id)!!.hideAtElapsedMs)
            requests.completeInline(id)
            assertNull(requests.claimInline(id))
            assertEquals(RevealState.LOCKED, requests.timer(secret).currentState(false).state)
        } finally { requests.cancel(id) }
    }

    @Test fun inlineClaimCannotReleaseAWaitingOrExpiredPassword() {
        val (secret, timers) = readySecret()
        val requests = InstallLockRequests(c)
        val id = java.util.UUID.randomUUID().toString()
        requests.receive(id, "KEYWORD_OFF|", "Test task")
        val record = timers.get(secret.id)!!
        timers.save(secret.id, record.copy(revealAtElapsedMs = SystemClock.elapsedRealtime() + 60_000,
            revealAtWallClockMs = System.currentTimeMillis() + 60_000))
        try {
            assertTrue(requests.select(id, secret.id))
            assertNull(requests.claimInline(id))
            timers.save(secret.id, record.copy(hideAtElapsedMs = SystemClock.elapsedRealtime() - 1,
                hideAtWallClockMs = System.currentTimeMillis() - 1))
            assertNull(requests.claimInline(id))
        } finally { requests.cancel(id) }
    }

    @Test fun readinessOpensInstallLockInsteadOfTheSeparateVaultPage() {
        val intent = InstallLockRequests(c).openIntent(c, "saved-request")
        assertEquals("com.example.installlock.MainActivity", intent.component!!.className)
        assertEquals("saved-request", intent.getStringExtra("request_id"))
        assertFalse(intent.hasExtra("password"))
    }
}
