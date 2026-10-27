package com.example.ariseosleeper

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class VerseAlarmRulesTest {
    // Original fixture prose, not bundled Scripture.
    private val text = "Wake up, open the window, and begin the morning with a clear mind."

    @Test fun typingIgnoresCaseWhitespaceAndPunctuationButRequiresTheWords() {
        assertTrue(VerseAlarmRules.matches(text, "WAKE UP open the window\nand begin the morning with a clear mind"))
        assertFalse(VerseAlarmRules.matches(text, "Wake up open the window"))
        assertFalse(VerseAlarmRules.matches(text, text + " extra words"))
        assertFalse(VerseAlarmRules.matches("", ""))
        assertTrue(VerseAlarmRules.matches("Don't give up.", "Don’t give up"))
    }

    @Test fun dictationErrorsAreLimitedAndCannotAcceptShortcuts() {
        val passage = (1..40).joinToString(" ") { "word$it" }
        val oneError = passage.replace("word15", "heard")
        assertTrue(VerseAlarmRules.matches(passage, oneError, dictated = true))
        assertFalse(VerseAlarmRules.matches(passage, oneError, dictated = false))
        assertFalse(VerseAlarmRules.matches(passage, "word1 word2", dictated = true))
        assertFalse(VerseAlarmRules.matches(text, text.replace("window", "door"), dictated = true))
        assertFalse(VerseAlarmRules.matches(passage, passage.split(' ').reversed().joinToString(" "), true))
    }

    @Test fun learningAndRecallAlternateAndOldestPassageIsRevisited() {
        val new = AlarmVerse("new", "New", text, successes = 2, lastUsed = 20)
        val old = AlarmVerse("old", "Old", text, successes = 3, lastUsed = 10)
        val older = AlarmVerse("older", "Older", text, successes = 3, lastUsed = 0)
        assertEquals(new, VerseAlarmRules.choose(listOf(new, old, older), 3, 0))
        assertEquals(older, VerseAlarmRules.choose(listOf(new, old, older), 3, 1))
        assertEquals(old, VerseAlarmRules.choose(listOf(old), 3, 0))
    }

    @Test fun followUpCannotBeSkippedByChangingWallClock() {
        val c = VerseChallenge("id", "verse", 1, false, 400_000, 310_000, 4, waiting = true)
        assertEquals(300_000L, c.remaining(AlarmClockSnapshot(9_000_000, 10_000, 4)))
        assertFalse(c.ringing(AlarmClockSnapshot(0, 309_999, 4)))
        assertTrue(c.ringing(AlarmClockSnapshot(0, 310_000, 4)))
        assertEquals(100_000L, c.remaining(AlarmClockSnapshot(300_000, 1000, 5)))
        assertTrue(c.ringing(AlarmClockSnapshot(500_000, 1000, 5)))
    }

    @Test fun dailyTimeUsesLocalZoneAndRollsToTomorrowAtBoundary() {
        val config = VerseAlarmConfig(true, 7, 30)
        val now = Instant.parse("2026-10-23T03:29:00Z").toEpochMilli()
        val zone = ZoneId.of("Asia/Dubai")
        val next = VerseAlarmRules.nextDaily(config, now, zone)
        assertEquals(Instant.parse("2026-10-23T03:30:00Z").toEpochMilli(), next)
        assertEquals(next + 86_400_000, VerseAlarmRules.nextDaily(config, next, zone))
    }

    @Test fun daylightSavingGapSchedulesARealLocalInstant() {
        val config = VerseAlarmConfig(true, 2, 30)
        val next = VerseAlarmRules.nextDaily(config, Instant.parse("2026-03-08T05:00:00Z").toEpochMilli(), ZoneId.of("America/New_York"))
        assertEquals(Instant.parse("2026-03-08T07:30:00Z").toEpochMilli(), next)
    }

    @Test fun multipleAlarmsSelectTheNextTimeEvenWhenUnsorted() {
        val config = VerseAlarmConfig(true, 20, 0, sessions = 7,
            additionalTimes = listOf(VerseAlarmTime(7, 30), VerseAlarmTime(12, 0)))
        val zone = ZoneId.of("UTC")
        fun at(time: String) = Instant.parse("2026-10-26T${time}:00Z").toEpochMilli()
        assertEquals(at("07:30"), VerseAlarmRules.nextDaily(config, at("06:00"), zone))
        assertEquals(at("12:00"), VerseAlarmRules.nextDaily(config, at("07:30"), zone))
        assertEquals(at("20:00"), VerseAlarmRules.nextDaily(config, at("12:00"), zone))
        assertEquals(at("07:30") + 86_400_000, VerseAlarmRules.nextDaily(config, at("20:00"), zone))
    }

    @Test fun validatesSessionCountsAndRejectsDuplicateOrInvalidTimes() {
        assertTrue(VerseAlarmConfig(sessions = 1).valid())
        assertTrue(VerseAlarmConfig(sessions = 20).valid())
        assertFalse(VerseAlarmConfig(sessions = 0).valid())
        assertFalse(VerseAlarmConfig(sessions = 21).valid())
        assertFalse(VerseAlarmConfig(additionalTimes = listOf(VerseAlarmTime(7, 0))).valid())
        assertFalse(VerseAlarmConfig(additionalTimes = listOf(VerseAlarmTime(24, 0))).valid())
        assertFalse(VerseAlarmConfig(additionalTimes = listOf(VerseAlarmTime(8, 60))).valid())
        assertFalse(VerseAlarmConfig(additionalTimes = (1..24).map { VerseAlarmTime(0, it) }).valid())
    }

    @Test fun longQuietTimesAreNotClampedToTheFiveMinuteFollowUp() {
        val now = AlarmClockSnapshot(1_000_000, 10_000, 8)
        val quiet = VerseChallenge("c", "v", 0, false,
            now.wall + 900_000, now.elapsed + 900_000, now.boot, quietUsed = true, quietDurationMs = 900_000)
        assertEquals(900_000L, quiet.remaining(now))
        assertEquals(840_000L, quiet.remaining(now.copy(wall = now.wall + 60_000, elapsed = 1000, boot = 9)))
        assertEquals(900_000L, quiet.remaining(now.copy(wall = now.wall + 9_000_000)))
        assertEquals(900_000L, quiet.remaining(now.copy(wall = now.wall - 9_000_000, boot = 9)))
        assertFalse(quiet.ringing(now.copy(elapsed = now.elapsed + 899_999)))
        assertTrue(quiet.ringing(now.copy(elapsed = now.elapsed + 900_000)))
    }
}
