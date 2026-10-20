package com.example.installlock

import android.text.InputType
import org.junit.Assert.*
import org.junit.Test

class PasswordFieldTargetTest {
    private fun allowed(type: Int, password: Boolean = false, focused: Boolean = true,
                        visible: Boolean = true, enabled: Boolean = true, editable: Boolean = true,
                        setText: Boolean = true) = PasswordFieldTarget.eligible(focused, visible, enabled, editable, setText, password, type)

    @Test fun nativeWebVisibleAndNumericPasswordFieldsAreSupported() {
        for (type in listOf(0x81, 0x91, 0xE1, 0x12)) assertTrue(allowed(type))
        assertTrue(allowed(InputType.TYPE_CLASS_TEXT, password = true))
        assertTrue(allowed(0x81 or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS))
    }
    @Test fun searchAddressEmailAndOrdinaryTextFieldsNeverReceiveThePassword() {
        for (type in listOf(InputType.TYPE_CLASS_TEXT, InputType.TYPE_CLASS_NUMBER,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)) assertFalse(allowed(type))
    }
    @Test fun aPasswordFieldMustBeFocusedVisibleEnabledEditableAndSupportDirectEntry() {
        assertFalse(allowed(0x81, focused = false))
        assertFalse(allowed(0x81, visible = false))
        assertFalse(allowed(0x81, enabled = false))
        assertFalse(allowed(0x81, editable = false))
        assertFalse(allowed(0x81, setText = false))
    }
}
