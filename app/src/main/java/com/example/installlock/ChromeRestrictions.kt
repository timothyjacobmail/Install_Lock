package com.example.installlock

import android.os.Bundle
import org.json.JSONArray

internal object ChromeRestrictions {
    private const val BLOCKLIST = "URLBlocklist"
    private const val ALLOWLIST = "URLAllowlist"

    /** Null means Android already holds the requested policy. Preserve unrelated restrictions. */
    @Suppress("DEPRECATION")
    fun update(current: Bundle, lockOn: Boolean, activePatterns: List<String>,
               extraLockedDomains: List<String> = emptyList()): Bundle? {
        if (!lockOn && extraLockedDomains.isEmpty()) {
            if (!current.containsKey(BLOCKLIST) && !current.containsKey(ALLOWLIST)) return null
            return Bundle(current).apply {
                remove(BLOCKLIST)
                remove(ALLOWLIST)
            }
        }

        // Chrome's Android managed configuration declares these lists as JSON strings.
        val extras = ExtraSiteLocks(extraLockedDomains.toSet())
        val blocked = JSONArray((if (lockOn) listOf("*") else emptyList()) + extras.domains.sorted()).toString()
        val allowed = if (lockOn) JSONArray(extras.allowedPatterns(activePatterns).sorted()).toString() else null
        if (current[BLOCKLIST] == blocked && current[ALLOWLIST] == allowed &&
            (lockOn || !current.containsKey(ALLOWLIST))) return null
        return Bundle(current).apply {
            putString(BLOCKLIST, blocked)
            if (allowed != null) putString(ALLOWLIST, allowed) else remove(ALLOWLIST)
        }
    }
}
