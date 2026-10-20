package com.example.installlock

import org.junit.Assert.*
import org.junit.Test

class SecretDeliveryWindowTest {
    @Test fun expiryIsExclusiveAndCannotBeUsedAtOrAfterTheDeadline() {
        assertTrue(SecretDeliveryWindow.valid(10_000, 7, 9_999, 7))
        assertFalse(SecretDeliveryWindow.valid(10_000, 7, 10_000, 7))
        assertFalse(SecretDeliveryWindow.valid(10_000, 7, 10_001, 7))
        assertFalse(SecretDeliveryWindow.valid(0, 7, 0, 7))
    }
    @Test fun rebootedAndUnknownClockContextsCannotReuseADelivery() {
        assertFalse(SecretDeliveryWindow.valid(10_000, 7, 100, 8))
        assertFalse(SecretDeliveryWindow.valid(10_000, -1, 100, -1))
        assertFalse(SecretDeliveryWindow.valid(10_000, 7, -1, 7))
    }
}
