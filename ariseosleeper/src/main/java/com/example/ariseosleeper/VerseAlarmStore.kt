package com.example.ariseosleeper

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class VerseAlarmState(val config: VerseAlarmConfig = VerseAlarmConfig(),
    val verses: List<AlarmVerse> = emptyList(), val challenge: VerseChallenge? = null,
    val nextAlarm: Long = 0, val pending: VerseAlarmConfig? = null,
    val countdown: AlarmChangeCountdown? = null, val everArmed: Boolean = false) {
    fun requestConfig(config: VerseAlarmConfig, now: AlarmClockSnapshot): VerseAlarmState =
        if (challenge == null) copy(config = config, everArmed = everArmed || config.enabled,
            pending = null, countdown = null,
            nextAlarm = if (config.enabled) VerseAlarmRules.nextDaily(config, now.wall) else 0)
        else copy(pending = config,
            countdown = AlarmChangeCountdown.start(VerseAlarmRules.CHANGE_DELAY_MINUTES, now))

    /** The alteration wait is relevant only while a routine is still active. */
    fun applyPendingIfIdle(now: AlarmClockSnapshot): VerseAlarmState {
        val requested = pending ?: return this
        if (challenge != null || (requested.enabled && verses.isEmpty())) return this
        return requestConfig(requested, now)
    }

    fun nextWarningAt(now: AlarmClockSnapshot): Long? {
        val ch = challenge
        val due = if (ch == null) {
            if (!config.enabled || nextAlarm <= now.wall) return null
            nextAlarm
        } else {
            if (!ch.waiting || ch.warningSent || ch.ringing(now)) return null
            now.wall + ch.remaining(now)
        }
        return (due - VerseAlarmRules.WARNING_MS).coerceAtLeast(now.wall + 1000)
    }
}

/** One durable snapshot makes answer credit and advancement atomic, including after a reboot.
 * Device-protected storage lets an alarm ring before the first unlock after restarting. */
internal object VerseAlarmStore {
    private fun prefs(c: Context) = c.createDeviceProtectedStorageContext()
        .getSharedPreferences("verse_alarm", Context.MODE_PRIVATE)
    private fun config(o: JSONObject) = VerseAlarmConfig(o.getBoolean("enabled"),
        o.getInt("hour"), o.getInt("minute"), o.getInt("memoryAfter"), o.optInt("sessions", 3),
        o.optJSONArray("additionalTimes")?.let { a -> (0 until a.length()).map { i ->
            a.getJSONObject(i).let { VerseAlarmTime(it.getInt("hour"), it.getInt("minute")) }
        } } ?: emptyList())
    private fun encode(v: VerseAlarmConfig) = JSONObject().put("enabled", v.enabled)
        .put("hour", v.hour).put("minute", v.minute).put("memoryAfter", v.memoryAfter)
        .put("sessions", v.sessions).put("additionalTimes", JSONArray().apply {
            v.additionalTimes.forEach { put(JSONObject().put("hour", it.hour).put("minute", it.minute)) }
        })

    @Synchronized fun read(c: Context): VerseAlarmState {
        val raw = prefs(c).getString("state", null) ?: return VerseAlarmState()
        val o = JSONObject(raw)
        val verses = o.getJSONArray("verses").let { a -> (0 until a.length()).map { i ->
            a.getJSONObject(i).let { AlarmVerse(it.getString("id"), it.getString("reference"),
                it.getString("text"), it.getInt("successes"), it.getLong("lastUsed"), it.optInt("quietSeconds", 60)) }
        } }
        val challenge = o.optJSONObject("challenge")?.let { VerseChallenge(it.getString("id"),
            it.getString("verse"), it.getInt("round"), it.getBoolean("memory"),
            it.getLong("wall"), it.getLong("elapsed"), it.getInt("boot"),
            it.getBoolean("waiting"), it.getBoolean("quietUsed"), it.optBoolean("warningSent"),
            it.optLong("quietDurationMs", 60_000)) }
        val countdown = o.optJSONObject("countdown")?.let {
            AlarmChangeCountdown(it.getLong("due"), it.getInt("boot"), it.getLong("duration")) }
        return VerseAlarmState(config(o.getJSONObject("config")), verses, challenge,
            o.getLong("nextAlarm"), o.optJSONObject("pending")?.let(::config), countdown,
            o.optBoolean("everArmed"))
    }

    private fun write(c: Context, s: VerseAlarmState) {
        val o = JSONObject().put("config", encode(s.config)).put("nextAlarm", s.nextAlarm)
            .put("everArmed", s.everArmed).put("verses", JSONArray().apply {
                s.verses.forEach { put(JSONObject().put("id", it.id).put("reference", it.reference)
                    .put("text", it.text).put("successes", it.successes).put("lastUsed", it.lastUsed)
                    .put("quietSeconds", it.quietSeconds)) }
            })
        s.challenge?.let { o.put("challenge", JSONObject().put("id", it.id).put("verse", it.verseId)
            .put("round", it.round).put("memory", it.fromMemory).put("wall", it.dueWall)
            .put("elapsed", it.dueElapsed).put("boot", it.boot).put("waiting", it.waiting)
            .put("quietUsed", it.quietUsed).put("warningSent", it.warningSent)
            .put("quietDurationMs", it.quietDurationMs)) }
        s.pending?.let { o.put("pending", encode(it)) }
        s.countdown?.let { o.put("countdown", JSONObject().put("due", it.dueElapsed)
            .put("boot", it.boot).put("duration", it.duration)) }
        check(prefs(c).edit().putString("state", o.toString()).commit()) { "Could not save the alarm." }
    }

    @Synchronized fun saveConfig(c: Context, config: VerseAlarmConfig, now: AlarmClockSnapshot = AlarmClock.now(c)) {
        require(config.valid())
        val s = reconcile(c, now)
        require(!config.enabled || s.verses.isNotEmpty()) { "Add an ESV passage first." }
        write(c, s.requestConfig(config, now))
    }

    @Synchronized fun cancelChange(c: Context) {
        val s = read(c)
        write(c, s.copy(pending = null, countdown = null))
    }

    @Synchronized fun addVerse(c: Context, reference: String, text: String, quietSeconds: Int = 60) {
        val s = reconcile(c)
        check(s.challenge == null) { "Finish the current wake-up routine before adding passages." }
        require(reference.trim().length in 1..100 && text.trim().length in 1..6000 &&
            VerseAlarmRules.words(text).size in 3..500) { "Enter a reference and a passage of 3–500 words." }
        require(s.verses.size < 200) { "The library holds up to 200 passages." }
        require(s.verses.none { VerseAlarmRules.words(it.text) == VerseAlarmRules.words(text) }) { "That passage is already saved." }
        require(quietSeconds in 1..VerseAlarmRules.MAX_QUIET_SECONDS) { "Use a quiet time of 1–3600 seconds." }
        write(c, s.copy(verses = s.verses + AlarmVerse(UUID.randomUUID().toString(), reference.trim(), text.trim(),
            quietSeconds = quietSeconds)))
    }

    @Synchronized fun setQuietTime(c: Context, id: String, seconds: Int) {
        val s = reconcile(c)
        check(s.challenge == null) { "Finish the current wake-up routine before changing quiet times." }
        require(seconds in 1..VerseAlarmRules.MAX_QUIET_SECONDS) { "Use a quiet time of 1–3600 seconds." }
        require(s.verses.any { it.id == id }) { "Passage no longer exists." }
        write(c, s.copy(verses = s.verses.map { if (it.id == id) it.copy(quietSeconds = seconds) else it }))
    }

    @Synchronized fun removeVerse(c: Context, id: String) {
        val s = read(c)
        check(!s.config.enabled && s.challenge == null) { "Turn the alarm off before editing passages." }
        check(s.pending?.enabled != true) { "Cancel the pending activation before deleting passages." }
        write(c, s.copy(verses = s.verses.filterNot { it.id == id }))
    }

    private fun challenge(s: VerseAlarmState, round: Int, now: AlarmClockSnapshot, delay: Long =
        if (round == 0) 0L else VerseAlarmRules.FOLLOW_UP_MS): VerseChallenge {
        val v = VerseAlarmRules.choose(s.verses, s.config.memoryAfter, round)
        return VerseChallenge(UUID.randomUUID().toString(), v.id, round,
            v.successes >= s.config.memoryAfter, now.wall + delay, now.elapsed + delay,
            now.boot, waiting = delay > 0)
    }

    @Synchronized fun reconcile(c: Context, now: AlarmClockSnapshot = AlarmClock.now(c),
        timeChanged: Boolean = false): VerseAlarmState {
        val old = read(c)
        var s = old
        val timer = s.countdown?.reconcile(now)
        if (timer != s.countdown) s = s.copy(countdown = timer)
        // A due alarm wins over a pending settings change. Settings never end an active routine.
        // Advance from the scheduled time so alarms overlapping a routine are not skipped.
        if (s.config.enabled && s.challenge == null && s.nextAlarm > 0 &&
            s.nextAlarm - VerseAlarmRules.WARNING_MS <= now.wall && !timeChanged)
            s = s.copy(challenge = challenge(s, 0, now, (s.nextAlarm - now.wall).coerceAtLeast(0)),
                nextAlarm = VerseAlarmRules.nextDaily(s.config, s.nextAlarm))
        s = s.applyPendingIfIdle(now)
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
        now: AlarmClockSnapshot = AlarmClock.now(c)): Boolean {
        val s = reconcile(c, now)
        val ch = s.challenge ?: return false
        if (ch.id != id || !ch.canAnswer(now)) return false
        val verse = s.verses.first { it.id == ch.verseId }
        if (!VerseAlarmRules.matches(verse.text, answer, dictated)) return false
        val updated = s.copy(verses = s.verses.map { if (it.id != verse.id) it else
            it.copy(successes = (it.successes + 1).coerceAtMost(1_000_000), lastUsed = now.wall) })
        write(c, updated.copy(challenge = if (ch.round + 1 >= s.config.sessions) null
            else challenge(updated, ch.round + 1, now)).applyPendingIfIdle(now))
        return true
    }

    /** Commit once per check so duplicate broadcasts and restarts do not repeat the chime. */
    @Synchronized fun takeWarning(c: Context, now: AlarmClockSnapshot = AlarmClock.now(c)): VerseChallenge? {
        val s = reconcile(c, now)
        val ch = s.challenge ?: return null
        if (!ch.warning(now) || ch.warningSent) return null
        write(c, s.copy(challenge = ch.copy(warningSent = true)))
        return ch
    }

    @Synchronized fun quiet(c: Context, id: String, now: AlarmClockSnapshot = AlarmClock.now(c)): Boolean {
        val s = reconcile(c, now)
        val ch = s.challenge ?: return false
        if (ch.id != id || ch.quietUsed || !ch.ringing(now)) return false
        val duration = s.verses.first { it.id == ch.verseId }.quietSeconds * 1000L
        write(c, s.copy(challenge = ch.copy(dueWall = now.wall + duration,
            dueElapsed = now.elapsed + duration, boot = now.boot, quietUsed = true, waiting = false,
            quietDurationMs = duration)))
        return true
    }
}
