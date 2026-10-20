package com.example.delayedsecrect

enum class ClipboardLifetime(val title: String, val durationMs: Long) {
    UNTIL_PASTED("Until pasted", 10 * 60_000L),
    FIVE_MINUTES("5 min", 5 * 60_000L),
    TEN_MINUTES("10 min", 10 * 60_000L)
}

/** Only a bulk insertion of the copied value counts; normal typing and unrelated text do not. */
internal object SecretPasteSignal {
    fun matches(value: String, from: Int, added: Int, text: List<CharSequence>, password: Boolean): Boolean {
        if (value.length < 2 || from < 0 || added != value.length) return false
        // Password controls hide their text. A whole-password insertion also covers autofill;
        // either way, the clipboard is no longer needed. Never inspect the hidden field.
        if (password) return true
        return text.any { content ->
            from <= content.length - value.length &&
                content.subSequence(from, from + value.length).toString() == value
        }
    }
}

internal data class ClipboardLease(val token: String, val copiedAt: Long, val deadline: Long, val boot: Int) {
    fun expired(now: Long, currentBoot: Int) = currentBoot != boot || now < copiedAt || now >= deadline
    fun selected(lifetime: ClipboardLifetime) = copy(deadline = copiedAt + lifetime.durationMs)
    fun owns(clipboardToken: String?) = clipboardToken == token
}
