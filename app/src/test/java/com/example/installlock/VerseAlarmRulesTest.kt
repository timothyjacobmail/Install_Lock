package com.example.installlock

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
        assertEquals(300_000L, c.remaining(WatchdogClock(9_000_000, 10_000, 4)))
        assertFalse(c.ringing(WatchdogClock(0, 309_999, 4)))
        assertTrue(c.ringing(WatchdogClock(0, 310_000, 4)))
        assertEquals(100_000L, c.remaining(WatchdogClock(300_000, 1000, 5)))
        assertTrue(c.ringing(WatchdogClock(500_000, 1000, 5)))
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
}
