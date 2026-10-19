package com.example.installlock

enum class TimingOption(
    val title: String, val unit: String, val default: Int,
    val minimum: Int, val maximum: Int, val millisecondsPerUnit: Long
) {
    INSTALL_WAIT("New-app install delay", "minutes", 15, 0, 10080, 60_000),
    SITE_WAIT("Chrome whitelist wait", "minutes", 15, 0, 10080, 60_000),
    KEYWORD_REMOVAL_WAIT("Browser keyword removal wait (new requests)", "minutes", 15, 1, 10080, 60_000),
    DEFAULT_UNLOCK("Default unlock duration", "minutes", 30, 1, 10080, 60_000),
    MAX_UNLOCK("Maximum unlock duration", "minutes", 120, 1, 10080, 60_000),
    CURBOX_GRACE("Curbox guard grace period", "seconds", 15, 0, 3600, 1000),
    POLICY_INTERVAL("Protection check interval", "seconds", 5, 1, 60, 1000),
    UNINSTALL_RETRY("Uninstall retry interval", "seconds", 30, 1, 3600, 1000),
    DNS_TIMEOUT("DNS request timeout", "seconds", 4, 1, 60, 1000),
    CHROME_RESTART("Chrome restart pause (when automatic restart is enabled)", "milliseconds", 1500, 100, 10000, 1),
    PASSWORD_FAILURES("Failed attempts before password lockout", "attempts", 5, 1, 100, 1),
    LOCKOUT_INITIAL("Initial password lockout", "minutes", 1, 1, 1440, 60_000),
    LOCKOUT_MAX("Maximum password lockout", "minutes", 60, 1, 10080, 60_000)
}

data class TimingConfig(
    val values: Map<TimingOption, Int> = emptyMap(),
    val unlockPresets: List<Int> = listOf(15, 30, 60, 120)
) {
    operator fun get(option: TimingOption): Int = values[option] ?: option.default
    fun milliseconds(option: TimingOption): Long = get(option).toLong() * option.millisecondsPerUnit

    fun validationError(): String? {
        for (option in TimingOption.entries) {
            if (get(option) !in option.minimum..option.maximum) {
                return "${option.title}: enter ${option.minimum} to ${option.maximum} ${option.unit}."
            }
        }
        if (get(TimingOption.DEFAULT_UNLOCK) > get(TimingOption.MAX_UNLOCK))
            return "Default unlock duration cannot exceed the maximum unlock duration."
        if (get(TimingOption.LOCKOUT_INITIAL) > get(TimingOption.LOCKOUT_MAX))
            return "Initial password lockout cannot exceed the maximum password lockout."
        if (unlockPresets.size !in 1..6 || unlockPresets.distinct().size != unlockPresets.size ||
            unlockPresets.any { it !in 1..get(TimingOption.MAX_UNLOCK) })
            return "Enter 1 to 6 different unlock presets, each between 1 and ${get(TimingOption.MAX_UNLOCK)} minutes."
        return null
    }

    /** Doubles each subsequent lockout, capped before the arithmetic can overflow. */
    fun passwordLockoutMs(failedAttempts: Int): Long {
        val threshold = get(TimingOption.PASSWORD_FAILURES)
        if (failedAttempts < threshold) return 0L
        val exponent = (failedAttempts.toLong() - threshold).coerceIn(0, 30).toInt()
        return minOf(milliseconds(TimingOption.LOCKOUT_MAX),
            milliseconds(TimingOption.LOCKOUT_INITIAL) * (1L shl exponent))
    }

    fun siteActiveAt(addedAt: Long): Long = addedAt + milliseconds(TimingOption.SITE_WAIT)

    fun capUnlockUntil(requestedUntil: Long, now: Long): Long =
        minOf(requestedUntil, now + milliseconds(TimingOption.MAX_UNLOCK))
}
