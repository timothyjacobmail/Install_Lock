package com.example.installlock.voice

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class VoiceCommandTest {
    private val now = ZonedDateTime.of(2026, 10, 23, 14, 0, 0, 0, ZoneId.of("Asia/Dubai"))
    @Test fun memoReminderDurationsSupportSpeechAndAbbreviations() {
        listOf("5 mins", "five minutes", "in five minutes", "after 5 minutes", "remind me in five minutes", "five minutes from now.").forEach {
            assertEquals(it, 300000L, VoiceCommand.reminderDelayMillis(it))
        }
        assertEquals(18000000L, VoiceCommand.reminderDelayMillis("5 hours"))
        assertEquals(18000000L, VoiceCommand.reminderDelayMillis("five hrs"))
        assertEquals(1500000L, VoiceCommand.reminderDelayMillis("twenty-five minutes"))
        assertEquals(86400000L, VoiceCommand.reminderDelayMillis("one day"))
    }
    @Test fun memoReminderDurationsRejectMissingOrAmbiguousUnits() {
        listOf("five", "five minutes or five hours", "0 mins", "-5 hours", "tomorrow", "five bananas", "10001 days", "delete all tasks").forEach {
            assertThrows(it, IllegalArgumentException::class.java) { VoiceCommand.reminderDelayMillis(it) }
        }
    }
    @Test fun capturesUndatedTaskWithoutInventingTime() {
        assertEquals(VoiceDraft("study CATIA"), VoiceCommand.parse("Add study CATIA to my tasks", now))
    }
    @Test fun speechRepairIsExplicitAndStillValidatesDuration() {
        val result = VoiceCommand.parseSpoken("remind me to take out the trash and five minutes", now)
        assertEquals("take out the trash", result.draft.title)
        assertEquals(now.plusMinutes(5).toInstant().toEpochMilli(), result.draft.due)
        assertNotNull("Repairs must require review", result.correction)
        assertNull(VoiceCommand.parseSpoken("remind me to call in five minutes", now).correction)
        assertThrows(IllegalArgumentException::class.java) { VoiceCommand.parse("remind me to call and five minutes", now) }
        assertThrows(IllegalArgumentException::class.java) { VoiceCommand.parseSpoken("remind me to call and zero minutes", now) }
    }
    @Test fun relativeOrdersAndNumberWords() {
        val expected = VoiceDraft("take out the trash", now.plusMinutes(5).toInstant().toEpochMilli())
        assertEquals(expected, VoiceCommand.parse("Remind me to take out the trash in five minutes.", now))
        assertEquals(expected, VoiceCommand.parse("Remind me in five minutes to take out the trash", now))
        assertEquals(25, VoiceCommand.number("twenty-five"))
        assertNull(VoiceCommand.number("twenty banana"))
    }
    @Test fun tomorrowAndWeekday() {
        assertEquals(now.plusDays(1).withHour(9).toInstant().toEpochMilli(), VoiceCommand.parse("Remind me tomorrow at nine AM to call John", now).due)
        val monday = VoiceCommand.parse("Remind me every Monday at seven PM to take out the bins", now)
        assertEquals("WEEKLY", monday.repeat)
        assertEquals(now.plusDays(3).withHour(19).toInstant().toEpochMilli(), monday.due)
    }
    @Test fun ambiguityAndUnsupportedCommandsNeverBecomeTasks() {
        listOf("remind me at nine to call John", "remind me at nine thirty to call John", "remind me sometime to call John", "remind me today at nine AM to call John", "remind me to call in zero minutes", "disable install lock", "remind me tomorrow at 25:00 to call").forEach {
            assertThrows(it, IllegalArgumentException::class.java) { VoiceCommand.parse(it, now) }
        }
    }
    @Test fun explicitDatesAndNoon() {
        assertEquals(12, VoiceCommand.time("noon").hour)
        assertEquals(0, VoiceCommand.time("12 AM").hour)
        assertEquals(21, VoiceCommand.time("21:30").hour)
        assertEquals(LocalTime.of(21, 30), VoiceCommand.time("nine thirty p m"))
        assertEquals("call John", VoiceCommand.parse("Remind me to call John 2026-10-25 at 21:30", now).title)
    }
    @Test fun recurringCompletionSkipsMissedOccurrencesAndHandlesDst() {
        val zone = ZoneId.of("America/New_York")
        val before = ZonedDateTime.of(2026, 3, 7, 9, 0, 0, 0, zone)
        val next = VoiceCommand.next(before.toInstant().toEpochMilli(), "DAILY", before.toInstant().toEpochMilli(), zone)!!
        assertEquals(23 * 3600000L, next - before.toInstant().toEpochMilli())
        assertEquals(9, Instant.ofEpochMilli(next).atZone(zone).hour)
        val now = before.plusDays(20).plusHours(1)
        val later = VoiceCommand.next(before.toInstant().toEpochMilli(), "WEEKLY", now.toInstant().toEpochMilli(), zone)!!
        assertTrue(later > now.toInstant().toEpochMilli())
    }
    @Test fun lateOrCancelledAlarmCannotResurrectTask() {
        val t = VoiceTask(title = "test", due = 1000)
        assertFalse(ignoreAlarm(t, 1000))
        assertTrue(ignoreAlarm(t.copy(done = true), 1000))
        assertTrue(ignoreAlarm(t.copy(archived = true), 1000))
        assertTrue(ignoreAlarm(t.copy(due = 2000), 1000))
        assertTrue(ignoreAlarm(t.copy(alertedAt = 1000), 1000))
        assertTrue(ignoreAlarm(t.copy(snoozeUntil = 3000), 1000))
        assertFalse(ignoreAlarm(t.copy(snoozeUntil = 3000, alertedAt = 1000), 3000))
    }
}
