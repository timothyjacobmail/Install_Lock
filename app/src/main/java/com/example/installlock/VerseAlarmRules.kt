package com.example.installlock

import java.time.Instant
import java.time.ZoneId
import java.util.Locale

internal data class AlarmVerse(val id: String, val reference: String, val text: String,
    val successes: Int = 0, val lastUsed: Long = 0)

internal data class VerseAlarmConfig(val enabled: Boolean = false, val hour: Int = 7,
    val minute: Int = 0, val memoryAfter: Int = 3) {
    fun valid() = hour in 0..23 && minute in 0..59 && memoryAfter in 2..20
}

internal data class VerseChallenge(val id: String, val verseId: String, val round: Int,
    val fromMemory: Boolean, val dueWall: Long, val dueElapsed: Long, val boot: Int,
    val waiting: Boolean = false, val quietUsed: Boolean = false, val warningSent: Boolean = false) {
    fun remaining(now: WatchdogClock): Long =
        (if (now.boot == boot) dueElapsed - now.elapsed else dueWall - now.wall).coerceIn(0, 300_000)
    fun ringing(now: WatchdogClock) = remaining(now) == 0L
    fun warning(now: WatchdogClock) = waiting && remaining(now) in 1..VerseAlarmRules.WARNING_MS
    fun canAnswer(now: WatchdogClock) = !waiting || remaining(now) <= VerseAlarmRules.WARNING_MS
}

/** Pure rules shared by scheduling, the challenge screen and tests. */
internal object VerseAlarmRules {
    const val ROUNDS = 3
    const val WARNING_MS = 60_000L
    const val FOLLOW_UP_MS = 5 * 60_000L
    const val CHANGE_DELAY_MINUTES = 15

    fun words(text: String): List<String> = text.lowercase(Locale.ROOT)
        .replace(Regex("['’]"), "")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().split(Regex("\\s+"))
        .filter { it.isNotEmpty() }

    fun matches(expected: String, answer: String, dictated: Boolean = false): Boolean {
        if (answer.length > 12_000) return false
        val a = words(expected)
        val b = words(answer)
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        // Short verses require every word. Longer spoken passages tolerate a small
        // number of transcription errors, while preserving word order and length.
        val allowance = if (dictated) minOf(3, a.size / 20) else 0
        if (allowance == 0 || kotlin.math.abs(a.size - b.size) > allowance) return false
        var previous = IntArray(b.size + 1) { it }
        for (i in a.indices) {
            val row = IntArray(b.size + 1)
            row[0] = i + 1
            for (j in b.indices) row[j + 1] = minOf(row[j] + 1, previous[j + 1] + 1,
                previous[j] + if (a[i] == b[j]) 0 else 1)
            previous = row
        }
        return previous[b.size] <= allowance
    }

    fun choose(verses: List<AlarmVerse>, threshold: Int, round: Int): AlarmVerse {
        require(verses.isNotEmpty())
        // Alternate learning and recall where both are available; rotate within each group.
        val wantMemory = round % 2 == 1
        val pool = verses.filter { (it.successes >= threshold) == wantMemory }.ifEmpty { verses }
        return pool.minWith(compareBy<AlarmVerse> { it.lastUsed }.thenBy { it.id })
    }

    fun nextDaily(config: VerseAlarmConfig, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val local = Instant.ofEpochMilli(now).atZone(zone)
        var next = local.toLocalDate().atTime(config.hour, config.minute).atZone(zone)
        if (!next.toInstant().isAfter(local.toInstant()))
            next = local.toLocalDate().plusDays(1).atTime(config.hour, config.minute).atZone(zone)
        return next.toInstant().toEpochMilli()
    }
}
