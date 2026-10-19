package com.example.installlock

import android.os.Bundle
import org.json.JSONArray

internal object ChromeRestrictions {
    private const val BLOCKLIST = "URLBlocklist"
    private const val ALLOWLIST = "URLAllowlist"

    /** Null means Android already holds the requested policy. Preserve unrelated restrictions. */
    @Suppress("DEPRECATION")
    fun update(current: Bundle, lockOn: Boolean, activePatterns: List<String>): Bundle? {
        if (!lockOn) {
            if (!current.containsKey(BLOCKLIST) && !current.containsKey(ALLOWLIST)) return null
            return Bundle(current).apply {
                remove(BLOCKLIST)
                remove(ALLOWLIST)
            }
        }

        // Chrome's Android managed configuration declares these lists as JSON strings.
        val blocked = JSONArray(listOf("*")).toString()
        val allowed = JSONArray(activePatterns.sorted()).toString()
        if (current[BLOCKLIST] == blocked && current[ALLOWLIST] == allowed) return null
        return Bundle(current).apply {
            putString(BLOCKLIST, blocked)
            putString(ALLOWLIST, allowed)
        }
    }
}
