package com.example.delayedsecrect // <-- match your actual package name

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

class SecretStore(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    private fun bootCount(): Int = Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT, 0)

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "secret_store_encrypted",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    @Synchronized
    fun getAll(): List<Secret> {
        val raw = prefs.getString("secrets_json", "[]") ?: "[]"
        val array = JSONArray(raw)
        val result = mutableListOf<Secret>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            result.add(
                Secret(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    value = obj.getString("value"),
                    delayMinutes = if (obj.has("delayMinutes")) obj.getInt("delayMinutes") else null,
                    pendingDelay = obj.optJSONObject("pendingDelay")?.let { pending ->
                        PendingSecretDelay(
                            delayMinutes = if (pending.has("delayMinutes")) pending.getInt("delayMinutes") else null,
                            effectiveAtElapsedMs = pending.getLong("effectiveAtElapsedMs"),
                            bootCount = pending.getInt("bootCount")
                        )
                    }
                )
            )
        }
        val now = SystemClock.elapsedRealtime()
        val boot = bootCount()
        val reconciled = result.map { SecretDelay.reconcile(it, now, boot) }
        if (reconciled != result) saveAll(reconciled)
        return reconciled
    }

    @Synchronized
    fun requestDelayChange(id: String, delayMinutes: Int?, defaultMinutes: Int) {
        val now = SystemClock.elapsedRealtime()
        val boot = bootCount()
        saveAll(getAll().map {
            if (it.id == id) SecretDelay.request(it, delayMinutes, defaultMinutes, now, boot) else it
        })
    }

    @Synchronized
    fun cancelPendingDelay(id: String) {
        saveAll(getAll().map { if (it.id == id) it.copy(pendingDelay = null) else it })
    }

    @Synchronized
    fun add(name: String, value: String, delayMinutes: Int?) {
        val current = getAll().toMutableList()
        current.add(
            Secret(
                id = System.currentTimeMillis().toString(),
                name = name,
                value = value,
                delayMinutes = delayMinutes
            )
        )
        saveAll(current)
    }

    @Synchronized
    fun delete(id: String) {
        val current = getAll().filter { it.id != id }
        saveAll(current)
    }

    @Synchronized
    fun importAll(entries: List<SecretImportEntry>): SecretImportResult {
        val current = getAll()
        val plan = SecretImport.plan(current, entries)
        if (plan.additions.isEmpty()) return SecretImportResult(0, plan.skipped)
        val additions = plan.additions.map { Secret(java.util.UUID.randomUUID().toString(), it.name, it.value) }
        check(prefs.edit().putString("secrets_json", serialize(current + additions)).commit()) {
            "Could not confirm that the secrets were saved."
        }
        return SecretImportResult(additions.size, plan.skipped)
    }

    private fun saveAll(list: List<Secret>) {
        prefs.edit().putString("secrets_json", serialize(list)).apply()
    }

    private fun serialize(list: List<Secret>): String {
        val array = JSONArray()
        list.forEach {
            val obj = JSONObject()
            obj.put("id", it.id)
            obj.put("name", it.name)
            obj.put("value", it.value)
            it.delayMinutes?.let { d -> obj.put("delayMinutes", d) }
            it.pendingDelay?.let { pending ->
                val change = JSONObject()
                pending.delayMinutes?.let { d -> change.put("delayMinutes", d) }
                change.put("effectiveAtElapsedMs", pending.effectiveAtElapsedMs)
                change.put("bootCount", pending.bootCount)
                obj.put("pendingDelay", change)
            }
            array.put(obj)
        }
        return array.toString()
    }
}
