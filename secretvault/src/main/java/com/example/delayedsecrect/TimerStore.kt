package com.example.delayedsecrect

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject

data class TimerRecord(
    val started: Boolean,
    val revealAtElapsedMs: Long,
    val hideAtElapsedMs: Long,
    val revealAtWallClockMs: Long,
    val hideAtWallClockMs: Long,
    val bootReferenceMs: Long,
    val hardLockUntilElapsedMs: Long = 0L,
    val hardLockUntilWallClockMs: Long = 0L,
    val hardLockBootReferenceMs: Long = 0L,
    val cycleId: String = ""
)

interface TimerPersistence {
    fun get(secretId: String): TimerRecord?
    fun save(secretId: String, record: TimerRecord)
}

class TimerStore(context: Context) : TimerPersistence {

    companion object {
        private val changes = java.util.concurrent.atomic.AtomicInteger()
        val revision: Int get() = changes.get()
    }

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "timer_store_encrypted",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    override fun get(secretId: String): TimerRecord? {
        val raw = prefs.getString("timer_$secretId", null) ?: return null
        val obj = JSONObject(raw)
        return TimerRecord(
            started = obj.getBoolean("started"),
            revealAtElapsedMs = obj.getLong("revealAtElapsedMs"),
            hideAtElapsedMs = obj.getLong("hideAtElapsedMs"),
            revealAtWallClockMs = obj.getLong("revealAtWallClockMs"),
            hideAtWallClockMs = obj.getLong("hideAtWallClockMs"),
            bootReferenceMs = obj.getLong("bootReferenceMs"),
            hardLockUntilElapsedMs = obj.optLong("hardLockUntilElapsedMs", 0L),
            hardLockUntilWallClockMs = obj.optLong("hardLockUntilWallClockMs", 0L),
            hardLockBootReferenceMs = obj.optLong("hardLockBootReferenceMs", 0L),
            cycleId = obj.optString("cycleId", "")
        )
    }

    override fun save(secretId: String, record: TimerRecord) {
        val obj = JSONObject()
        obj.put("started", record.started)
        obj.put("revealAtElapsedMs", record.revealAtElapsedMs)
        obj.put("hideAtElapsedMs", record.hideAtElapsedMs)
        obj.put("revealAtWallClockMs", record.revealAtWallClockMs)
        obj.put("hideAtWallClockMs", record.hideAtWallClockMs)
        obj.put("bootReferenceMs", record.bootReferenceMs)
        obj.put("hardLockUntilElapsedMs", record.hardLockUntilElapsedMs)
        obj.put("hardLockUntilWallClockMs", record.hardLockUntilWallClockMs)
        obj.put("hardLockBootReferenceMs", record.hardLockBootReferenceMs)
        obj.put("cycleId", record.cycleId)
        prefs.edit().putString("timer_$secretId", obj.toString()).apply()
        changes.incrementAndGet()
    }
    fun clear(secretId: String) {
        prefs.edit().remove("timer_$secretId").apply()
        changes.incrementAndGet()
    }
}
