package com.example.delayedsecrect

data class PasswordEntryResult(val success: Boolean, val message: String)

/** In-process connection to Install Lock's accessibility service. Never buffers a password. */
object PasswordFieldEntry {
    private var writer: ((String) -> PasswordEntryResult)? = null
    val available: Boolean get() = writer != null

    fun attach(write: (String) -> PasswordEntryResult) { writer = write }
    fun detach() { writer = null }

    fun enter(value: String): PasswordEntryResult {
        val current = writer ?: return PasswordEntryResult(false, "Turn on Install Lock accessibility to enter a password.")
        return try { current(value) } catch (_: RuntimeException) {
            PasswordEntryResult(false, "The password field changed. Tap the password field and try again.")
        }
    }
}
