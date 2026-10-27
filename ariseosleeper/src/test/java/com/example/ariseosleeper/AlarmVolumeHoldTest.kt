package com.example.ariseosleeper

import org.junit.Assert.*
import org.junit.Test

class AlarmVolumeHoldTest {
    private class Audio : AlarmVolumeAccess {
        var saved: Int? = null
        var volume = 6
        var writes = 0
        var adjustments = 0
        var failSave = false
        override fun savedLevel() = saved
        override fun saveLevel(level: Int?) {
            check(!failSave)
            saved = level
            writes++
        }
        override fun currentLevel() = volume
        override fun minimumLevel() = 0
        override fun maximumLevel() = 10
        override fun setLevel(level: Int) { volume = level; adjustments++ }
    }

    @Test fun loweringAndRaisingAreRevertedThroughoutAllThreeChecksAndPauses() {
        val audio = Audio()
        val hold = AlarmVolumeHold(audio)
        // Ringing, quiet minute, waiting, second check, waiting, third check.
        repeat(6) { stage ->
            hold.reconcile(true)
            audio.volume = if (stage % 2 == 0) 0 else 10
            hold.reconcile(true)
            assertEquals(6, audio.volume)
            assertEquals(6, audio.saved)
        }
        assertEquals(1, audio.writes)
        hold.reconcile(false)
        audio.volume = 2
        hold.reconcile(false)
        assertEquals(2, audio.volume)
        assertNull(audio.saved)
    }

    @Test fun processRestartReusesSavedLevelRatherThanAdoptingAnAttemptedMute() {
        val audio = Audio()
        AlarmVolumeHold(audio).reconcile(true)
        audio.volume = 0
        AlarmVolumeHold(audio).reconcile(true)
        assertEquals(6, audio.volume)
    }

    @Test fun nextRoutineCapturesTheNewLevelAndIdleDoesNotChangeVolume() {
        val audio = Audio()
        val hold = AlarmVolumeHold(audio)
        hold.reconcile(true)
        hold.reconcile(false)
        audio.volume = 3
        hold.reconcile(false)
        assertEquals(0, audio.adjustments)
        hold.reconcile(true)
        assertEquals(3, audio.saved)
    }

    @Test fun mutedStartUsesAnAudibleLevelWithoutForcingMaximumVolume() {
        val audio = Audio().apply { volume = 0 }
        AlarmVolumeHold(audio).reconcile(true)
        assertEquals(1, audio.volume)
        assertEquals(1, audio.saved)
    }

    @Test fun failedPersistenceDoesNotChangeVolume() {
        val audio = Audio().apply { volume = 0; failSave = true }
        assertThrows(IllegalStateException::class.java) { AlarmVolumeHold(audio).reconcile(true) }
        assertEquals(0, audio.adjustments)
    }
}
