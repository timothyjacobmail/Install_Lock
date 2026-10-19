package com.example.installlock

import org.junit.Assert.*
import org.junit.Test

class TimingConfigTest {
    @Test
    fun defaultsKeepTheExistingDurations() {
        val config = TimingConfig()
        assertNull(config.validationError())
        assertEquals(15 * 60_000L, config.milliseconds(TimingOption.INSTALL_WAIT))
        assertEquals(15 * 60_000L, config.milliseconds(TimingOption.SITE_WAIT))
        assertEquals(30, config[TimingOption.DEFAULT_UNLOCK])
        assertEquals(120, config[TimingOption.MAX_UNLOCK])
        assertEquals(0L, config.passwordLockoutMs(4))
        assertEquals(60_000L, config.passwordLockoutMs(5))
        assertEquals(120_000L, config.passwordLockoutMs(6))
        assertEquals(60 * 60_000L, config.passwordLockoutMs(11))
    }

    @Test
    fun changedSiteWaitRecalculatesExistingEntriesFromTheirAddedTime() {
        val addedAt = 1_000_000L
        assertEquals(addedAt + 15 * 60_000L, TimingConfig().siteActiveAt(addedAt))
        assertEquals(addedAt + 3 * 60_000L,
            TimingConfig(mapOf(TimingOption.SITE_WAIT to 3)).siteActiveAt(addedAt))
        assertEquals(addedAt, TimingConfig(mapOf(TimingOption.SITE_WAIT to 0)).siteActiveAt(addedAt))
    }

    @Test
    fun invalidAndOverflowingInputsAreRejected() {
        for (option in TimingOption.entries) {
            assertNotNull(TimingConfig(mapOf(option to option.minimum - 1)).validationError())
            assertNotNull(TimingConfig(mapOf(option to option.maximum + 1)).validationError())
            assertNotNull(TimingConfig(mapOf(option to Int.MAX_VALUE)).validationError())
        }
    }

    @Test
    fun relatedLimitsAndPresetsMustAgree() {
        assertNotNull(TimingConfig(mapOf(TimingOption.DEFAULT_UNLOCK to 121)).validationError())
        assertNotNull(TimingConfig(mapOf(TimingOption.LOCKOUT_INITIAL to 61)).validationError())
        for (presets in listOf(emptyList(), listOf(0), listOf(121), listOf(15, 15), (1..7).toList())) {
            assertNotNull(TimingConfig(unlockPresets = presets).validationError())
        }
        assertNull(TimingConfig(mapOf(TimingOption.MAX_UNLOCK to 10, TimingOption.DEFAULT_UNLOCK to 5),
            listOf(1, 5, 10)).validationError())
    }

    @Test
    fun customPasswordLockoutsDoubleAndStopAtTheConfiguredMaximum() {
        val config = TimingConfig(mapOf(TimingOption.PASSWORD_FAILURES to 3,
            TimingOption.LOCKOUT_INITIAL to 2, TimingOption.LOCKOUT_MAX to 5))
        assertEquals(0L, config.passwordLockoutMs(2))
        assertEquals(2 * 60_000L, config.passwordLockoutMs(3))
        assertEquals(4 * 60_000L, config.passwordLockoutMs(4))
        assertEquals(5 * 60_000L, config.passwordLockoutMs(5))
        assertEquals(5 * 60_000L, config.passwordLockoutMs(Int.MAX_VALUE))
    }

    @Test
    fun loweredUnlockLimitCapsRemainingTimeButNeverExtendsAnUnlock() {
        val config = TimingConfig(mapOf(TimingOption.MAX_UNLOCK to 10))
        val now = 1_000_000L
        assertEquals(now + 10 * 60_000L, config.capUnlockUntil(now + 120 * 60_000L, now))
        assertEquals(now + 2 * 60_000L, config.capUnlockUntil(now + 2 * 60_000L, now))
        assertEquals(0L, config.capUnlockUntil(0, now))
        assertEquals(now - 1, config.capUnlockUntil(now - 1, now))
    }
}
