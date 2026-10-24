package com.example.installlock.voice

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneId
import java.util.UUID

data class VoiceTask(
    val id: String = UUID.randomUUID().toString(), val title: String, val due: Long? = null,
    val repeat: String = "NONE", val notes: String = "", val list: String = "Personal",
    val priority: Boolean = false, val done: Boolean = false, val archived: Boolean = false,
    val alertedAt: Long? = null, val snoozeUntil: Long? = null, val zone: String = ZoneId.systemDefault().id,
    val speak: Boolean = false, val recording: String = "", val followUps: Int = 0
) {
    val trigger: Long? get() = snoozeUntil ?: due
    fun json() = JSONObject().put("id", id).put("title", title).put("due", due ?: JSONObject.NULL)
        .put("repeat", repeat).put("notes", notes).put("list", list).put("priority", priority).put("done", done)
        .put("archived", archived).put("alertedAt", alertedAt ?: JSONObject.NULL).put("snoozeUntil", snoozeUntil ?: JSONObject.NULL)
        .put("zone", zone).put("speak", speak).put("recording", recording).put("followUps", followUps)
    companion object {
        fun from(j: JSONObject): VoiceTask {
            fun optional(key: String): Long? = if (j.isNull(key)) null else j.getLong(key).also { require(it in 0..7_258_118_400_000L) { "Invalid reminder timestamp" } }
            val title = j.getString("title").trim()
            require(title.isNotBlank() && title.length <= 1000)
            val id = j.getString("id"); UUID.fromString(id)
            val repeat = j.optString("repeat", "NONE"); require(repeat in listOf("NONE", "DAILY", "WEEKLY", "MONTHLY"))
            val zone = j.optString("zone", ZoneId.systemDefault().id); ZoneId.of(zone)
            return VoiceTask(id, title, optional("due"), repeat, j.optString("notes").take(10000), j.optString("list", "Personal").take(80),
                j.optBoolean("priority"), j.optBoolean("done"), j.optBoolean("archived"), optional("alertedAt"), optional("snoozeUntil"), zone,
                j.optBoolean("speak"), j.optString("recording").takeIf { it.matches(Regex("[a-f0-9-]+\\.m4a")) }.orEmpty(), j.optInt("followUps").coerceIn(0, 3))
        }
    }
}

object VoiceStore {
    private val settingKeys = listOf("wake", "shake", "shake_memo", "volume", "fast", "quiet", "private", "repeat_alert")
    fun prefs(c: Context) = c.getSharedPreferences("voice_reminders", Context.MODE_PRIVATE)
    @Synchronized fun all(c: Context): List<VoiceTask> {
        val a = JSONArray(prefs(c).getString("tasks", "[]"))
        return (0 until a.length()).map { VoiceTask.from(a.getJSONObject(it)) }
    }
    private fun write(c: Context, tasks: List<VoiceTask>) {
        require(tasks.size <= 5000) { "Task limit reached. Export and remove old tasks." }
        val oldRecordings = all(c).map { it.recording }.filter { it.isNotEmpty() }.toSet()
        check(prefs(c).edit().putString("tasks", JSONArray(tasks.map { it.json() }).toString()).commit()) { "Could not save tasks." }
        val retained = tasks.map { it.recording }.toSet()
        (oldRecordings - retained).forEach { java.io.File(c.filesDir, "voice-recordings/$it").delete() }
        VoiceWidget.refresh(c)
    }
    @Synchronized fun save(c: Context, task: VoiceTask) {
        require(task.title.isNotBlank() && task.title.length <= 1000) { "Keep task titles between 1 and 1,000 characters." }
        require(task.notes.length <= 10000 && task.list.length <= 80) { "Notes must be under 10,000 characters and list names under 80." }
        VoiceTask.from(task.json()) // Validate dates, recurrence and identifiers before changing alarms or storage.
        val old = all(c).find { it.id == task.id }
        old?.let { VoiceAlarms.cancel(c, it) }
        write(c, all(c).filterNot { it.id == task.id } + task)
        VoiceAlarms.schedule(c, task)
    }
    @Synchronized fun remove(c: Context, id: String) {
        all(c).find { it.id == id }?.let { VoiceAlarms.cancel(c, it) }
        write(c, all(c).filterNot { it.id == id })
    }
    @Synchronized fun complete(c: Context, id: String) {
        val t = all(c).find { it.id == id } ?: return
        val next = t.due?.let { VoiceCommand.next(it, t.repeat, maxOf(System.currentTimeMillis(), it), ZoneId.of(t.zone)) }
        save(c, if (next == null) t.copy(done = true, snoozeUntil = null) else t.copy(due = next, alertedAt = null, snoozeUntil = null, followUps = 0))
    }
    @Synchronized fun snooze(c: Context, id: String, minutes: Int) {
        require(minutes in 1..10080)
        val t = all(c).find { it.id == id && !it.done } ?: return
        save(c, t.copy(snoozeUntil = System.currentTimeMillis() + minutes * 60000L, alertedAt = null))
    }
    @Synchronized fun claim(c: Context, id: String, scheduled: Long): VoiceTask? {
        val t = all(c).find { it.id == id } ?: return null
        if (ignoreAlarm(t, scheduled)) return null
        val updated = t.copy(alertedAt = scheduled)
        write(c, all(c).map { if (it.id == id) updated else it })
        return updated
    }
    fun export(c: Context): String {
        val settings = JSONObject()
        settingKeys.forEach { settings.put(it, prefs(c).getBoolean(it, it == "private" || it == "shake_memo")) }
        settings.put("shake_threshold", prefs(c).getFloat("shake_threshold", 2.7f).toDouble())
        return JSONObject().put("version", 1).put("tasks", JSONArray(all(c).map { it.json() })).put("settings", settings).toString(2)
    }
    @Synchronized fun import(c: Context, text: String): Int {
        require(text.length <= 5_000_000) { "Backup is too large." }
        val root = JSONObject(text); require(root.getInt("version") == 1)
        val arr = root.getJSONArray("tasks"); require(arr.length() <= 5000)
        val settings = root.optJSONObject("settings")
        settings?.let { s -> settingKeys.forEach { if (s.has(it)) s.getBoolean(it) }; if (s.has("shake_threshold")) require(s.getDouble("shake_threshold") in 1.5..4.0) }
        // Validate the entire backup before modifying anything. Merge by stable ID.
        val incoming = (0 until arr.length()).map { VoiceTask.from(arr.getJSONObject(it)) }
        val existing = all(c); val incomingIds = incoming.map { it.id }.toSet()
        require(incomingIds.size == incoming.size) { "Duplicate task IDs in backup." }
        val merged = existing.filterNot { it.id in incomingIds } + incoming
        require(merged.size <= 5000)
        existing.filter { it.id in incomingIds }.forEach { VoiceAlarms.cancel(c, it) }
        write(c, merged)
        settings?.let { s ->
            val edit = prefs(c).edit()
            settingKeys.forEach { if (s.has(it)) edit.putBoolean(it, s.getBoolean(it)) }
            if (s.has("shake_threshold")) edit.putFloat("shake_threshold", s.getDouble("shake_threshold").toFloat())
            edit.apply()
        }
        VoiceAlarms.restore(c)
        return incoming.size
    }
}

// Adapted from SpeakAlert's ReminderAlarmValidity.kt (Apache-2.0).
// Copyright SpeakAlert contributors. See assets/voice/NOTICE.txt and Apache-2.0.txt.
internal fun ignoreAlarm(task: VoiceTask, scheduled: Long): Boolean =
    task.done || task.archived || task.trigger != scheduled || task.alertedAt == scheduled
