package com.example.installlock

import java.text.DateFormat
import java.util.Date

/** The same reasons drive suspension and its explanation. No browsing history is recorded. */
data class AppLockReasons(
    val keywordServiceMissing: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val appLock: Boolean = false,
    val installWaitUntil: Long? = null,
    val otherBrowser: Boolean = false,
    val curboxMissing: Boolean = false,
    val neverInstallRule: String? = null
) {
    val shouldLock: Boolean get() = keywordServiceMissing || appLock || installWaitUntil != null ||
            otherBrowser || curboxMissing || neverInstallRule != null

    fun descriptions(): List<String> = buildList {
        if (keywordServiceMissing) add(
            if (accessibilityEnabled)
                "The browser keyword accessibility service is enabled but has not connected. " +
                        "Wait for it to connect, or re-enable it in Android Accessibility settings."
            else
                "The browser keyword accessibility setting is disabled. " +
                        "Enable Install Lock's browser keyword service in Android Accessibility settings."
        )
        if (appLock) add("This app is selected in App locks and has no active temporary unlock. " +
                "Use the app-lock password to unlock it temporarily or remove its app lock.")
        installWaitUntil?.let {
            val end = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))
            add("The new-app install waiting period ends at $end.")
        }
        if (otherBrowser) add("Chrome whitelist protection blocks other browsers. " +
                "Use Chrome, or use the filter password to turn off Chrome whitelist protection.")
        if (curboxMissing) add("Curbox's accessibility service is not running and its grace period has ended. " +
                "Re-enable Curbox, or use the filter password to turn off the Curbox guard.")
        neverInstallRule?.let {
            add("Its app name or package matches the never-install rule \"$it\". " +
                    "Install Lock also attempts to uninstall it; removing this rule requires the filter password.")
        }
    }
}

data class AppLockStatus(
    val packageName: String,
    val appName: String,
    val reasons: AppLockReasons,
    val suspended: Boolean?
) {
    val needsAttention: Boolean get() = reasons.shouldLock || suspended != false

    fun explanation(): String {
        val heading = when (suspended) {
            true -> if (reasons.shouldLock) "$appName is locked because:" else
                "$appName is still locked, but no current Install Lock rule requires it. " +
                        "Tap Refresh to retry releasing it. Android may also have another restriction."
            false -> if (reasons.shouldLock) "$appName has a lock pending. Android has not applied it yet. Reasons:" else
                "$appName has no active app lock."
            null -> "$appName's Android lock status could not be checked." +
                    if (reasons.shouldLock) " Current lock reasons:" else " Tap Refresh to retry."
        }
        return (listOf(heading) + reasons.descriptions().map { "\u2022 $it" }).joinToString("\n")
    }
}
