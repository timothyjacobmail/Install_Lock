package com.example.installlock.voice

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class VoiceTimeSample(val heard: String, val intended: String, val count: Int = 1)
data class VoiceTimeAnswer(val delay: Long, val correction: String? = null)

/** Confirmed phrase corrections, not acoustic-model training or speaker identification. */
object VoiceTimePractice {
    val session = listOf("five minutes", "ten hours", "six minutes", "five minutes", "ten hours", "six minutes")
    fun normalize(text: String) = text.trim().lowercase(Locale.ROOT).replace(Regex("(?<=[a-z])-(?=[a-z])"), " ")
        .replace(Regex("\\s+"), " ").trimEnd('.', '!', '?')

    fun validate(sample: VoiceTimeSample) {
        val heard = normalize(sample.heard)
        val expected = VoiceCommand.reminderDelayMillis(sample.intended)
        require(heard.length in 3..120 && heard.split(' ').size in 2..8 && !heard.contains("[unk]")) {
            "That sample is too incomplete. Please record the full number and unit again."
        }
        require(!Regex("(^|\\s)[−-]\\s*\\d").containsMatchIn(heard) && !heard.startsWith("minus ") && !heard.startsWith("negative ")) {
            "A reminder needs a positive duration. Please record the phrase again."
        }
        val parsed = runCatching { VoiceCommand.reminderDelayMillis(heard) }.getOrNull()
        require(parsed == null || parsed == expected) {
            "That was recognized as a different valid time. Please try again; it will not be remapped."
        }
        if (parsed == null) {
            val number = Regex("^(?:in |after )?(.+?) ").find(heard)?.groupValues?.get(1)?.let(VoiceCommand::number)
            val targetNumber = VoiceCommand.number(sample.intended.substringBeforeLast(' '))
            val unit = heard.substringAfterLast(' ')
            val targetUnit = sample.intended.substringAfterLast(' ').removeSuffix("s")
            val knownUnit = when (unit) { "minute", "minutes", "min", "mins" -> "minute"; "hour", "hours", "hr", "hrs" -> "hour"; "second", "seconds", "sec", "secs" -> "second"; "day", "days" -> "day"; else -> null }
            require(number == null || number == targetNumber) { "The number differs from the requested phrase. Please try again." }
            require(knownUnit == null || knownUnit == targetUnit) { "The unit differs from the requested phrase. Please try again." }
            require(number == targetNumber || knownUnit == targetUnit) { "Too little of the requested time was recognized. Please try again." }
        }
    }

    fun resolve(text: String, samples: List<VoiceTimeSample>): VoiceTimeAnswer {
        runCatching { VoiceCommand.reminderDelayMillis(text) }.getOrNull()?.let { return VoiceTimeAnswer(it) }
        val key = normalize(text)
        val matches = samples.filter { normalize(it.heard) == key }
        require(matches.isNotEmpty()) { "Say a duration such as five minutes or five hours." }
        matches.forEach(::validate)
        val delays = matches.map { VoiceCommand.reminderDelayMillis(it.intended) }.distinct()
        require(delays.size == 1) { "This phrase has conflicting practice samples. Please say the full time again." }
        return VoiceTimeAnswer(delays.single(), "Learned correction: ‘$text’ means ${matches.first().intended}.")
    }

    private fun prefs(c: Context) = c.getSharedPreferences("voice_time_practice", Context.MODE_PRIVATE)
    @Synchronized fun samples(c: Context): List<VoiceTimeSample> {
        val array = JSONArray(prefs(c).getString("samples", "[]"))
        return (0 until array.length()).map { i -> array.getJSONObject(i).let {
            VoiceTimeSample(it.getString("heard"), it.getString("intended"), it.optInt("count", 1).coerceIn(1, 1000))
        } }
    }
    @Synchronized fun save(c: Context, sample: VoiceTimeSample) {
        validate(sample)
        val existing = samples(c)
        val key = normalize(sample.heard)
        val old = existing.find { normalize(it.heard) == key }
        require(old == null || VoiceCommand.reminderDelayMillis(old.intended) == VoiceCommand.reminderDelayMillis(sample.intended)) {
            "This exact phrase was already confirmed for another time. Please record it again."
        }
        val updated = existing.filterNot { normalize(it.heard) == key } + sample.copy(heard = key, count = ((old?.count ?: 0) + 1).coerceAtMost(1000))
        require(updated.size <= 100) { "Practice profile is full. Reset it to start again." }
        check(prefs(c).edit().putString("samples", JSONArray(updated.map {
            JSONObject().put("heard", it.heard).put("intended", it.intended).put("count", it.count)
        }).toString()).commit()) { "Could not save this sample." }
    }
    fun clear(c: Context) { prefs(c).edit().remove("samples").apply() }
    fun resolve(c: Context, text: String) = resolve(text, samples(c))

    /** Constrain only duration answers; general dictation stays unrestricted. */
    val grammar: String by lazy {
        val small = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
        val tens = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
        val phrases = (1..99).flatMap { n ->
            val number = if (n < 20) small[n] else tens[n / 10] + if (n % 10 == 0) "" else " ${small[n % 10]}"
            listOf("second", "minute", "hour", "day").flatMap { unit ->
                listOf(unit, "${unit}s").flatMap { u -> listOf("", "in ", "after ", "remind me in ").map { "$it$number $u" } }
            }
        } + "[unk]"
        JSONArray(phrases).toString()
    }
    fun grammar(c: Context): String {
        val phrases = JSONArray(grammar)
        samples(c).forEach { phrases.put(it.heard) }
        return phrases.toString()
    }
}
