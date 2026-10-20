package com.example.delayedsecrect

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class PasswordFieldEntryTest {
    @After fun disconnect() { PasswordFieldEntry.detach() }

    @Test fun aMissingAccessibilityConnectionRejectsEntryWithoutKeepingItForLater() {
        PasswordFieldEntry.detach()
        assertFalse(PasswordFieldEntry.enter("SyntheticPass123").success)
        var writes = 0
        PasswordFieldEntry.attach { writes++; PasswordEntryResult(true, "Entered") }
        assertEquals(0, writes)
    }
    @Test fun onlyAnExplicitEnterRequestDeliversTheSelectedValue() {
        var written: String? = null
        PasswordFieldEntry.attach { written = it; PasswordEntryResult(true, "Entered") }
        assertTrue(PasswordFieldEntry.available)
        assertNull(written)
        assertTrue(PasswordFieldEntry.enter("SyntheticPass123").success)
        assertEquals("SyntheticPass123", written)
    }
    @Test fun disconnectingPreventsFurtherEntry() {
        var writes = 0
        PasswordFieldEntry.attach { writes++; PasswordEntryResult(true, "Entered") }
        PasswordFieldEntry.detach()
        assertFalse(PasswordFieldEntry.available)
        assertFalse(PasswordFieldEntry.enter("SyntheticPass123").success)
        assertEquals(0, writes)
    }
    @Test fun rejectedFieldsAndChangedWindowsDoNotReportSuccessOrLeakTheValue() {
        PasswordFieldEntry.attach { PasswordEntryResult(false, "Select a password field") }
        assertFalse(PasswordFieldEntry.enter("SyntheticPass123").success)
        PasswordFieldEntry.attach { throw IllegalStateException("SyntheticPass123") }
        val result = PasswordFieldEntry.enter("SyntheticPass123")
        assertFalse(result.success)
        assertFalse(result.message.contains("SyntheticPass123"))
    }
}
