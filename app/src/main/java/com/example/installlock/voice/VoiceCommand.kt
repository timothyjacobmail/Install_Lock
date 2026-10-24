package com.example.installlock.voice

import java.time.*
import java.time.temporal.TemporalAdjusters
import java.util.Locale

data class VoiceDraft(val title: String, val due: Long? = null, val repeat: String = "NONE")
data class VoiceInterpretation(val draft: VoiceDraft, val correction: String? = null)

/** An explicit English grammar: unknown/ambiguous schedules are rejected, never guessed. */
object VoiceCommand {
    fun reminderDelayMillis(input: String): Long {
        val text = input.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trimEnd('.', '!', '?')
        val m = Regex("^(?:remind me )?(?:(?:in|after) )?(.+?) (seconds?|secs?|minutes?|mins?|hours?|hrs?|days?)(?: from now)?$").matchEntire(text)
            ?: throw IllegalArgumentException("Say a duration such as five minutes or five hours.")
        val n = number(m.groupValues[1]) ?: throw IllegalArgumentException("Say a number such as five or twenty five.")
        require(n in 1..10000) { "Choose a positive duration up to 10,000 units." }
        val unit = m.groupValues[2]
        val multiplier = when { unit.startsWith("sec") -> 1000L; unit.startsWith("min") -> 60000L; unit.startsWith("h") -> 3600000L; else -> 86400000L }
        return n * multiplier
    }
    /** A narrowly scoped ASR repair. Any correction MUST be reviewed, even in quick-save mode. */
    fun parseSpoken(input: String, now: ZonedDateTime = ZonedDateTime.now()): VoiceInterpretation {
        try { return VoiceInterpretation(parse(input, now)) }
        catch (original: IllegalArgumentException) {
            val text = input.trim().replace(Regex("\\s+"), " ").trimEnd('.', '!', '?')
            val m = Regex("^remind me to (.+) and (.+?) (seconds?|minutes?|mins?|hours?|days?)$", RegexOption.IGNORE_CASE).matchEntire(text) ?: throw original
            val corrected = "Remind me to ${m.groupValues[1]} in ${m.groupValues[2]} ${m.groupValues[3]}"
            return VoiceInterpretation(parse(corrected, now), corrected)
        }
    }
    private val words = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
    fun number(s: String): Int? {
        val text = s.trim().lowercase(Locale.ROOT).replace('-', ' ')
        text.toIntOrNull()?.let { return it }
        if (text == "a" || text == "an") return 1
        words.indexOf(text).takeIf { it >= 0 }?.let { return it }
        val parts = text.split(' ')
        val tens = mapOf("twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90)
        val base = tens[parts[0]] ?: return null
        return if (parts.size == 1) base else if (parts.size == 2 && words.indexOf(parts[1]) in 1..9) base + words.indexOf(parts[1]) else null
    }
    fun time(s: String): LocalTime {
        val t = s.lowercase(Locale.ROOT).replace("a.m.", "am").replace("p.m.", "pm").replace("a m", "am").replace("p m", "pm").trim()
        if (t == "noon") return LocalTime.NOON
        if (t == "midnight") return LocalTime.MIDNIGHT
        val m = Regex("^(.+?)\\s*(am|pm)$").matchEntire(t)
        val body = m?.groupValues?.get(1)?.trim() ?: t
        val spoken = Regex("^(one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve) (.+)$").matchEntire(body)
        val parts = if (spoken != null) listOf(spoken.groupValues[1], (number(spoken.groupValues[2]) ?: -1).toString()) else body.split(':')
        val hour = number(parts[0]) ?: throw IllegalArgumentException("Use a time such as nine AM or 21:30.")
        val minute = if (parts.size == 2) parts[1].toIntOrNull() ?: -1 else 0
        require(parts.size <= 2 && minute in 0..59) { "Check the minutes." }
        if (m == null) {
            require(body.contains(':') && parts.size == 2 && hour in 0..23) { "Please specify AM/PM, or use a 24-hour time such as 21:00." }
            return LocalTime.of(hour, minute)
        }
        require(hour in 1..12) { "AM/PM hours must be between 1 and 12." }
        return LocalTime.of(hour % 12 + if (m.groupValues[2] == "pm") 12 else 0, minute)
    }
    fun parse(input: String, now: ZonedDateTime = ZonedDateTime.now()): VoiceDraft {
        val s = input.trim().replace(Regex("\\s+"), " ").trimEnd('.', '!', '?')
        require(s.length in 1..1000) { "Enter a command under 1,000 characters." }
        fun match(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE).matchEntire(s)?.groupValues
        match("add (.+?) to my tasks")?.let { return VoiceDraft(it[1].trim(' ', '\"', '\'')) }
        var m = match("remind me to (.+) in (.+?) (seconds?|minutes?|mins?|hours?|days?)")
        if (m == null) match("remind me in (.+?) (seconds?|minutes?|mins?|hours?|days?) to (.+)")?.let { m = listOf(it[0], it[3], it[1], it[2]) }
        m?.let {
            val n = number(it[2]) ?: throw IllegalArgumentException("Use a number such as five or 25.")
            require(n in 1..10000) { "Choose a positive duration up to 10,000 units." }
            val unit = it[3].lowercase(Locale.ROOT)
            val due = when { unit.startsWith("day") -> now.plusDays(n.toLong()); unit.startsWith("hour") -> now.plusHours(n.toLong()); unit.startsWith("sec") -> now.plusSeconds(n.toLong()); else -> now.plusMinutes(n.toLong()) }
            return VoiceDraft(it[1], due.toInstant().toEpochMilli())
        }
        match("remind me every (day|week|monday|tuesday|wednesday|thursday|friday|saturday|sunday) at (.+?) to (.+)")?.let {
            val repeat = it[1].lowercase(Locale.ROOT)
            var date = now.toLocalDate()
            if (repeat != "day" && repeat != "week") date = date.with(TemporalAdjusters.nextOrSame(DayOfWeek.valueOf(repeat.uppercase(Locale.ROOT))))
            var due = date.atTime(time(it[2])).atZone(now.zone)
            if (!due.isAfter(now)) due = if (repeat == "day") due.plusDays(1) else due.plusWeeks(1)
            return VoiceDraft(it[3], due.toInstant().toEpochMilli(), if (repeat == "day") "DAILY" else "WEEKLY")
        }
        m = match("remind me (today|tomorrow|\\d{4}-\\d{2}-\\d{2}) at (.+?) to (.+)")
        if (m == null) match("remind me to (.+) (today|tomorrow|\\d{4}-\\d{2}-\\d{2}) at (.+)")?.let { m = listOf(it[0], it[2], it[3], it[1]) }
        m?.let {
            val date = when (it[1].lowercase(Locale.ROOT)) { "today" -> now.toLocalDate(); "tomorrow" -> now.toLocalDate().plusDays(1); else -> LocalDate.parse(it[1]) }
            val due = date.atTime(time(it[2])).atZone(now.zone)
            require(due.isAfter(now)) { "That time has passed. Choose a future time." }
            return VoiceDraft(it[3], due.toInstant().toEpochMilli())
        }
        match("remind me at (.+?) to (.+)")?.let {
            var due = now.toLocalDate().atTime(time(it[1])).atZone(now.zone)
            if (!due.isAfter(now)) due = due.plusDays(1)
            return VoiceDraft(it[2], due.toInstant().toEpochMilli())
        }
        throw IllegalArgumentException("Try: Remind me to take out the trash in five minutes; Remind me tomorrow at nine AM to call John; or Add study CATIA to my tasks.")
    }
    fun next(due: Long, repeat: String, now: Long, zone: ZoneId): Long? {
        if (repeat == "NONE") return null
        var d = Instant.ofEpochMilli(due).atZone(zone)
        val current = Instant.ofEpochMilli(now).atZone(zone)
        // Calendar arithmetic retains the local time through daylight-saving changes.
        while (!d.isAfter(current)) d = when (repeat) { "DAILY" -> d.plusDays(1); "WEEKLY" -> d.plusWeeks(1); "MONTHLY" -> d.plusMonths(1); else -> return null }
        return d.toInstant().toEpochMilli()
    }
}
