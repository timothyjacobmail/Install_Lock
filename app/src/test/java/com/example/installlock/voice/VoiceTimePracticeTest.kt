package com.example.installlock.voice

import org.junit.Assert.*
import org.junit.Test

class VoiceTimePracticeTest {
    @Test fun confirmedMisheardPhrasesResolveToTheIntendedDuration() {
        val samples = listOf(VoiceTimeSample("five men", "five minutes"), VoiceTimeSample("ten ours", "ten hours"), VoiceTimeSample("six minutez", "six minutes"))
        samples.forEach(VoiceTimePractice::validate)
        assertEquals(300000L, VoiceTimePractice.resolve("Five men.", samples).delay)
        assertEquals(36000000L, VoiceTimePractice.resolve("ten ours", samples).delay)
        assertEquals(360000L, VoiceTimePractice.resolve("six minutez", samples).delay)
        assertNotNull("Personal corrections require an explicit review", VoiceTimePractice.resolve("five men", samples).correction)
    }
    @Test fun correctRecognitionNeedsNoCorrection() {
        VoiceTimePractice.validate(VoiceTimeSample("five minutes", "five minutes"))
        assertNull(VoiceTimePractice.resolve("5 mins", emptyList()).correction)
        assertEquals(300000L, VoiceTimePractice.resolve("5 mins", emptyList()).delay)
    }
    @Test fun practiceCannotOverwriteAValidDifferentTime() {
        assertThrows(IllegalArgumentException::class.java) { VoiceTimePractice.validate(VoiceTimeSample("six minutes", "five minutes")) }
        assertEquals(360000L, VoiceTimePractice.resolve("six minutes", listOf(VoiceTimeSample("six minutes", "five minutes"))).delay)
    }
    @Test fun incompleteOrContradictorySamplesAreRejected() {
        listOf("five", "[unk] minutes", "six men", "fire hours", "-5 mins", "0 minutes", "random noise").forEach {
            assertThrows(it, IllegalArgumentException::class.java) { VoiceTimePractice.validate(VoiceTimeSample(it, "five minutes")) }
        }
    }
    @Test fun conflictingCorrectionsAndUnseenSpeechAreRejected() {
        val samples = listOf(VoiceTimeSample("five men", "five minutes"), VoiceTimeSample("five men", "five hours"))
        assertThrows(IllegalArgumentException::class.java) { VoiceTimePractice.resolve("five men", samples) }
        assertThrows(IllegalArgumentException::class.java) { VoiceTimePractice.resolve("unseen phrase", emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { VoiceTimePractice.resolve("-5 mins", listOf(VoiceTimeSample("5 mins", "five minutes"))) }
    }
}
