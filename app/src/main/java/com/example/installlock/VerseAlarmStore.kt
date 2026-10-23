package com.example.installlock

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class VerseAlarmState(val config: VerseAlarmConfig = VerseAlarmConfig(),
    val verses: List<AlarmVerse> = emptyList(), val challenge: VerseChallenge? = null,
    val nextAlarm: Long = 0, val pending: VerseAlarmConfig? = null,
    val countdown: TaskCountdown? = null, val everArmed: Boolean = false)

/** One durable snapshot makes answer credit and advancement atomic, including after a reboot.
 * Device-protected storage lets an alarm ring before the first unlock after restarting. */
internal object VerseAlarmStore {
    private fun prefs(c: Context) = c.createDeviceProtectedStorageContext()
        .getSharedPreferences("verse_alarm", Context.MODE_PRIVATE)
    private fun config(o: JSONObject) = VerseAlarmConfig(o.getBoolean("enabled"),
        o.getInt("hour"), o.getInt("minute"), o.getInt("memoryAfter"))
    private fun encode(v: VerseAlarmConfig) = JSONObject().put("enabled", v.enabled)
        .put("hour", v.hour).put("minute", v.minute).put("memoryAfter", v.memoryAfter)

    @Synchronized fun read(c: Context): VerseAlarmState {
        val raw = prefs(c).getString("state", null) ?: return VerseAlarmState()
        val o = JSONObject(raw)
        val verses = o.getJSONArray("verses").let { a -> (0 until a.length()).map { i ->
            a.getJSONObject(i).let { AlarmVerse(it.getString("id"), it.getString("reference"),
                it.getString("text"), it.getInt("successes"), it.getLong("lastUsed")) }
        } }
        val challenge = o.optJSONObject("challenge")?.let { VerseChallenge(it.getString("id"),
            it.getString("verse"), it.getInt("round"), it.getBoolean("memory"),
            it.getLong("wall"), it.getLong("elapsed"), it.getInt("boot"),
            it.getBoolean("waiting"), it.getBoolean("quietUsed")) }
        val countdown = o.optJSONObject("countdown")?.let {
            TaskCountdown(it.getLong("due"), it.getInt("boot"), it.getLong("duration")) }
        return VerseAlarmState(config(o.getJSONObject("config")), verses, challenge,
            o.getLong("nextAlarm"), o.optJSONObject("pending")?.let(::config), countdown,
            o.optBoolean("everArmed"))
    }

    private fun write(c: Context, s: VerseAlarmState) {
        val o = JSONObject().put("config", encode(s.config)).put("nextAlarm", s.nextAlarm)
            .put("everArmed", s.everArmed).put("verses", JSONArray().apply {
                s.verses.forEach { put(JSONObject().put("id", it.id).put("reference", it.reference)
                    .put("text", it.text).put("successes", it.successes).put("lastUsed", it.lastUsed)) }
            })
        s.challenge?.let { o.put("challenge", JSONObject().put("id", it.id).put("verse", it.verseId)
            .put("round", it.round).put("memory", it.fromMemory).put("wall", it.dueWall)
            .put("elapsed", it.dueElapsed).put("boot", it.boot).put("waiting", it.waiting)
            .put("quietUsed", it.quietUsed)) }
        s.pending?.let { o.put("pending", encode(it)) }
        s.countdown?.let { o.put("countdown", JSONObject().put("due", it.dueElapsed)
            .put("boot", it.boot).put("duration", it.duration)) }
        check(prefs(c).edit().putString("state", o.toString()).commit()) { "Could not save the alarm." }
    }

    @Synchronized fun saveConfig(c: Context, config: VerseAlarmConfig, now: WatchdogClock = WatchdogStore.clock(c)) {
        require(config.valid())
        val s = reconcile(c, now)
        require(!config.enabled || s.verses.isNotEmpty()) { "Add an ESV passage first." }
        if (!s.everArmed) write(c, s.copy(config = config, everArmed = config.enabled,
            nextAlarm = if (config.enabled) VerseAlarmRules.nextDaily(config, now.wall) else 0))
        else write(c, s.copy(pending = config,
            countdown = TaskCountdown.start(VerseAlarmRules.CHANGE_DELAY_MINUTES, now)))
    }

    @Synchronized fun cancelChange(c: Context) {
        val s = read(c)
        write(c, s.copy(pending = null, countdown = null))
    }

    @Synchronized fun addVerse(c: Context, reference: String, text: String) {
        val s = read(c)
        check(!s.config.enabled && s.challenge == null) { "Turn the alarm off before editing passages." }
        require(reference.trim().length in 1..100 && text.trim().length in 1..6000 &&
            VerseAlarmRules.words(text).size in 3..500) { "Enter a reference and a passage of 3–500 words." }
        require(s.verses.size < 200) { "The library holds up to 200 passages." }
        require(s.verses.none { VerseAlarmRules.words(it.text) == VerseAlarmRules.words(text) }) { "That passage is already saved." }
        write(c, s.copy(verses = s.verses + AlarmVerse(UUID.randomUUID().toString(), reference.trim(), text.trim())))
    }

    @Synchronized fun removeVerse(c: Context, id: String) {
        val s = read(c)
        check(!s.config.enabled && s.challenge == null) { "Turn the alarm off before editing passages." }
        check(s.pending?.enabled != true) { "Cancel the pending activation before deleting passages." }
        write(c, s.copy(verses = s.verses.filterNot { it.id == id }))
    }

    private fun challenge(s: VerseAlarmState, round: Int, now: WatchdogClock): VerseChallenge {
        val v = VerseAlarmRules.choose(s.verses, s.config.memoryAfter, round)
        val delay = if (round == 0) 0L else VerseAlarmRules.FOLLOW_UP_MS
        return VerseChallenge(UUID.randomUUID().toString(), v.id, round,
            v.successes >= s.config.memoryAfter, now.wall + delay, now.elapsed + delay,
            now.boot, waiting = round > 0)
    }

    @Synchronized fun reconcile(c: Context, now: WatchdogClock = WatchdogStore.clock(c),
        timeChanged: Boolean = false): VerseAlarmState {
        val old = read(c)
        var s = old
        val timer = s.countdown?.reconcile(now)
        if (timer != s.countdown) s = s.copy(countdown = timer)
        // A due alarm wins over a pending settings change. Settings never end an active routine.
        if (s.config.enabled && s.challenge == null && s.nextAlarm > 0 && s.nextAlarm <= now.wall && !timeChanged)
            s = s.copy(challenge = challenge(s, 0, now), nextAlarm = VerseAlarmRules.nextDaily(s.config, now.wall))
        if (s.challenge == null && s.pending != null && timer?.remaining(now) == 0L) {
            val config = s.pending
            if (!config.enabled || s.verses.isNotEmpty()) s = s.copy(config = config,
                pending = null, countdown = null, everArmed = s.everArmed || config.enabled,
                nextAlarm = if (config.enabled) VerseAlarmRules.nextDaily(config, now.wall) else 0)
        }
        if (timeChanged && s.config.enabled) s = s.copy(nextAlarm = VerseAlarmRules.nextDaily(s.config, now.wall))
        // Rebase a follow-up once after reboot; wall-clock changes during a boot cannot skip it.
        s.challenge?.takeIf { it.boot != now.boot }?.let {
            val left = it.remaining(now)
            s = s.copy(challenge = it.copy(dueWall = now.wall + left, dueElapsed = now.elapsed + left, boot = now.boot))
        }
        if (s != old) write(c, s)
        return s
    }

    @Synchronized fun answer(c: Context, id: String, answer: String, dictated: Boolean,
        now: WatchdogClock = WatchdogStore.clock(c)): Boolean {
        val s = reconcile(c, now)
        val ch = s.challenge ?: return false
        if (ch.id != id || (ch.waiting && !ch.ringing(now))) return false
        val verse = s.verses.first { it.id == ch.verseId }
        if (!VerseAlarmRules.matches(verse.text, answer, dictated)) return false
        val updated = s.copy(verses = s.verses.map { if (it.id != verse.id) it else
            it.copy(successes = (it.successes + 1).coerceAtMost(1_000_000), lastUsed = now.wall) })
        write(c, updated.copy(challenge = if (ch.round + 1 == VerseAlarmRules.ROUNDS) null
            else challenge(updated, ch.round + 1, now)))
        return true
    }

    @Synchronized fun quiet(c: Context, id: String, now: WatchdogClock = WatchdogStore.clock(c)): Boolean {
        val s = reconcile(c, now)
        val ch = s.challenge ?: return false
        if (ch.id != id || ch.quietUsed || !ch.ringing(now)) return false
        write(c, s.copy(challenge = ch.copy(dueWall = now.wall + 60_000,
            dueElapsed = now.elapsed + 60_000, boot = now.boot, quietUsed = true, waiting = false)))
        return true
    }
}
