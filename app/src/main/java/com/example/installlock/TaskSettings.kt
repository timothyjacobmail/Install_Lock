package com.example.installlock

import android.content.Context

internal object TaskSettings {
    val security = setOf(TimingOption.PASSWORD_FAILURES, TimingOption.LOCKOUT_INITIAL, TimingOption.LOCKOUT_MAX)
    fun save(c: Context, changes: Map<TimingOption, Int>, presets: List<Int>?, securityOnly: Boolean, password: String): Boolean {
        if (changes.keys.any { (it in security) != securityOnly } || (securityOnly && presets != null)) return false
        return TaskPasswords.run(c, TaskScope(if (securityOnly) TaskKind.SECURITY else TaskKind.TIMING), password) {
            val current = Store.timingConfig(c)
            val updated = TimingConfig(TimingOption.entries.associateWith { changes[it] ?: current[it] }, presets ?: current.unlockPresets)
            if (updated.validationError() != null) false else { Store.saveTimingConfig(c, updated); true }
        }
    }
    fun categories(c: Context, categories: Set<String>, password: String) =
        TaskPasswords.run(c, TaskScope(TaskKind.CATEGORIES), password) { Store.saveImmediatePlayCategories(c, categories); true }
}
