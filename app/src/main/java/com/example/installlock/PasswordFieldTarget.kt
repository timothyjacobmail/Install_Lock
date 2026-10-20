package com.example.installlock

import android.text.InputType

/** Field metadata only; never reads the existing password or falls back to a search/text box. */
internal object PasswordFieldTarget {
    fun eligible(focused: Boolean, visible: Boolean, enabled: Boolean, editable: Boolean,
                 canSetText: Boolean, password: Boolean, inputType: Int): Boolean {
        if (!focused || !visible || !enabled || !editable || !canSetText) return false
        if (password) return true
        val type = inputType and (InputType.TYPE_MASK_CLASS or InputType.TYPE_MASK_VARIATION)
        return type in setOf(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        )
    }
}
