package com.example.installlock

import android.content.Context
import java.util.UUID

internal object DelayedSecrets {
    const val REQUEST = "com.example.delayedsecrect.REQUEST_INSTALL_LOCK"
    const val RESULT = "com.example.installlock.DELAYED_PASSWORD"
    private fun prefs(c: Context) = c.getSharedPreferences("delayed_secret_requests", Context.MODE_PRIVATE)

    @Synchronized fun prepare(c: Context, task: DelayedTask): String {
        val id = UUID.randomUUID().toString()
        // Replace only previous requests for the exact same task. Other tasks remain independent.
        val p = prefs(c)
        p.all.entries.firstOrNull { it.value == task.encode() }?.let { existing ->
            return existing.key
        }
        val edit = p.edit()
        p.all.forEach { (key, value) ->
            if (value is String && DelayedTask.decode(value)?.scope == task.scope) edit.remove(key)
        }
        check(edit.putString(id, task.encode()).commit()) { "Could not save the request. Try again." }
        return id
    }

    fun peek(c: Context, id: String): DelayedTask? = prefs(c).getString(id, null)?.let(DelayedTask::decode)

    @Synchronized fun take(c: Context, id: String): DelayedTask? {
        val p = prefs(c)
        val raw = p.getString(id, null) ?: return null
        // Consume before authentication. A replay cannot repeat the operation, even with a wrong password.
        return if (p.edit().remove(id).commit()) DelayedTask.decode(raw) else null
    }
}
