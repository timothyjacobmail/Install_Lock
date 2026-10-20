package com.example.delayedsecrect

import org.junit.Assert.*
import org.junit.Test

class SecretClipboardPolicyTest {
    private val value = "SyntheticPass123"

    @Test fun selectedDurationsStartWhenCopiedAndUntilPastedHasBoundedFallback() {
        val pending = ClipboardLease("copy-1", 1000L, 61_000L, 7)
        assertEquals(301_000L, pending.selected(ClipboardLifetime.FIVE_MINUTES).deadline)
        assertEquals(601_000L, pending.selected(ClipboardLifetime.TEN_MINUTES).deadline)
        assertEquals(601_000L, pending.selected(ClipboardLifetime.UNTIL_PASTED).deadline)
    }
    @Test fun deadlineIsInclusiveAndRebootOrResetClockExpiresLease() {
        val lease = ClipboardLease("copy-1", 1000L, 301_000L, 7)
        assertFalse(lease.expired(300_999L, 7))
        assertTrue(lease.expired(301_000L, 7))
        assertTrue(lease.expired(2000L, 8))
        assertTrue(lease.expired(0L, 7))
    }
    @Test fun recopyingSameValueOrCopyingOtherTextDoesNotBelongToOldTimer() {
        val lease = ClipboardLease("copy-1", 1000L, 301_000L, 7)
        assertTrue(lease.owns("copy-1"))
        assertFalse(lease.owns("copy-2"))
        assertFalse(lease.owns(null))
    }
    @Test fun wholePasswordInsertionIsRecognizedIncludingWithinExistingText() {
        assertTrue(SecretPasteSignal.matches(value, 0, value.length, listOf(value), false))
        assertTrue(SecretPasteSignal.matches(value, 7, value.length, listOf("prefix:$value:suffix"), false))
    }
    @Test fun normalTypingUnrelatedTextAndNonInsertionEventsDoNotClearClipboard() {
        assertFalse(SecretPasteSignal.matches(value, 0, 1, listOf(value), false))
        assertFalse(SecretPasteSignal.matches(value, 0, value.length, listOf("SomethingElse!!"), false))
        assertFalse(SecretPasteSignal.matches(value, -1, value.length, listOf(value), false))
        assertFalse(SecretPasteSignal.matches(value, 100, value.length, listOf(value), false))
        assertFalse(SecretPasteSignal.matches(value, 0, 0, listOf(value), false))
    }
    @Test fun hiddenPasswordBulkInsertionDoesNotRequireReadingItsContents() {
        assertTrue(SecretPasteSignal.matches(value, 0, value.length, emptyList(), true))
        assertFalse(SecretPasteSignal.matches(value, 0, 1, emptyList(), true))
    }
}
