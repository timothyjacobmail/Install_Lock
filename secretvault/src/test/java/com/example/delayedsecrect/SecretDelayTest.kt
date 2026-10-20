package com.example.delayedsecrect

import org.junit.Assert.*
import org.junit.Test

class SecretDelayTest {
    private val secret = Secret("id", "Name", "value", delayMinutes = 60)
    private val start = 100_000L
    private val boot = 7

    @Test fun decreaseWaitsTheFull25Minutes() {
        val queued = SecretDelay.request(secret, 5, 15, start, boot)
        assertEquals(60, queued.delayMinutes)
        assertEquals(5, queued.pendingDelay?.delayMinutes)
        assertEquals(queued, SecretDelay.reconcile(queued, start + SecretDelay.DECREASE_WAIT_MS - 1, boot))
        val applied = SecretDelay.reconcile(queued, start + SecretDelay.DECREASE_WAIT_MS, boot)
        assertEquals(5, applied.delayMinutes)
        assertNull(applied.pendingDelay)
    }

    @Test fun increaseAppliesImmediatelyAndClearsPendingDecrease() {
        val queued = SecretDelay.request(secret, 5, 15, start, boot)
        val increased = SecretDelay.request(queued, 90, 15, start + 1, boot)
        assertEquals(90, increased.delayMinutes)
        assertNull(increased.pendingDelay)
        assertNull(SecretDelay.request(queued, 60, 15, start + 1, boot).pendingDelay)
    }

    @Test fun switchingToLowerDefaultAlsoWaits() {
        val queued = SecretDelay.request(secret, null, 15, start, boot)
        assertEquals(60, queued.delayMinutes)
        assertNotNull(queued.pendingDelay)
        val applied = SecretDelay.reconcile(queued, start + SecretDelay.DECREASE_WAIT_MS, boot)
        assertNull(applied.delayMinutes)
        assertNull(applied.pendingDelay)
    }

    @Test fun defaultBasedSecretUsesCurrentDefaultForComparison() {
        val defaultSecret = secret.copy(delayMinutes = null)
        assertNotNull(SecretDelay.request(defaultSecret, 10, 15, start, boot).pendingDelay)
        assertEquals(20, SecretDelay.request(defaultSecret, 20, 15, start, boot).delayMinutes)
        assertNull(SecretDelay.request(secret, null, 90, start, boot).pendingDelay)
    }

    @Test fun replacingDecreaseRestartsWait() {
        val queued = SecretDelay.request(secret, 5, 15, start, boot)
        val replaced = SecretDelay.request(queued, 10, 15, start + 60_000, boot)
        assertEquals(start + 60_000 + SecretDelay.DECREASE_WAIT_MS, replaced.pendingDelay!!.effectiveAtElapsedMs)
        assertEquals(60, SecretDelay.reconcile(replaced, start + SecretDelay.DECREASE_WAIT_MS, boot).delayMinutes)
    }

    @Test fun rebootRestartsWaitWithoutRelyingOnWallClock() {
        val queued = SecretDelay.request(secret, 5, 15, start, boot)
        val rebased = SecretDelay.reconcile(queued, 500, boot + 1)
        assertEquals(60, rebased.delayMinutes)
        assertEquals(500 + SecretDelay.DECREASE_WAIT_MS, rebased.pendingDelay!!.effectiveAtElapsedMs)
        assertEquals(rebased, SecretDelay.reconcile(rebased, 501, boot + 1))
        assertEquals(5, SecretDelay.reconcile(rebased, 500 + SecretDelay.DECREASE_WAIT_MS, boot + 1).delayMinutes)
    }

    @Test fun expiredPendingChangeIsAppliedBeforeComparingNewRequest() {
        val queued = SecretDelay.request(secret, 5, 15, start, boot)
        val changed = SecretDelay.request(queued, 10, 15, start + SecretDelay.DECREASE_WAIT_MS, boot)
        assertEquals(10, changed.delayMinutes)
        assertNull(changed.pendingDelay)
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroDelayIsRejected() {
        SecretDelay.request(secret, 0, 15, start, boot)
    }
}
