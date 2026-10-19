package com.example.installlock

import org.junit.Assert.*
import org.junit.Test

class PlayCategoryRulesTest {
    @Test fun onlyTheOriginalThreeCategoriesAreEnabledByDefault() {
        for (category in PlayInstallCategory.entries) {
            assertEquals(if (category.storeId in PlayCategoryRules.defaultCategories) category else null,
                PlayCategoryRules.exemption("com.android.vending", category.storeId))
        }
        for (category in listOf("ENTERTAINMENT", "VIDEO_PLAYERS", "GAME_MUSIC", "PRODUCTIVITY", "", "Shopping")) {
            assertNull(PlayCategoryRules.exemption("com.android.vending", category))
        }
        assertNull(PlayCategoryRules.exemption("com.android.vending", null))
    }

    @Test fun selectedAppAndGameCategoriesAreEligible() {
        val selected = setOf("EDUCATION", "TOOLS", "GAME_PUZZLE")
        for (category in PlayInstallCategory.entries) {
            assertEquals(if (category.storeId in selected) category else null,
                PlayCategoryRules.exemption("com.android.vending", category.storeId, selected))
        }
    }

    @Test fun changingSelectionImmediatelyChangesEligibilityForACachedCategory() {
        val record = PlayCategoryRecord(1_000, 2_000, "FINANCE", Long.MAX_VALUE)
        assertEquals(PlayInstallCategory.FINANCE, PlayCategoryRules.exemption("com.android.vending", record.category, setOf("FINANCE")))
        assertNull(PlayCategoryRules.exemption("com.android.vending", record.category, emptySet()))
        assertNull(PlayCategoryRules.exemption("com.android.vending", record.category, setOf("SHOPPING")))
    }

    @Test fun unknownCategoriesAndUntrustedInstallersStayIneligibleEvenWhenSelected() {
        assertNull(PlayCategoryRules.exemption("com.android.vending", "FAKE_CATEGORY", setOf("FAKE_CATEGORY")))
        assertNull(PlayCategoryRules.exemption("com.android.shell", "TOOLS", setOf("TOOLS")))
        assertEquals(setOf("TOOLS", "GAME_PUZZLE"), PlayCategoryRules.validCategories(setOf("TOOLS", "GAME_PUZZLE", "FAKE_CATEGORY")))
    }

    @Test fun catalogDistinguishesMusicAndSportsAppsFromGames() {
        assertFalse(PlayInstallCategory.MUSIC_AND_AUDIO.isGame)
        assertTrue(PlayInstallCategory.GAME_MUSIC.isGame)
        assertEquals("Games: Sports", PlayInstallCategory.GAME_SPORTS.displayName)
        assertEquals("Sports", PlayInstallCategory.SPORTS.displayName)
        val ids = PlayInstallCategory.entries.map { it.storeId }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun sideloadedAndOtherStoreAppsDoNotGetCategoryExemptions() {
        for (installer in listOf(null, "", "com.android.shell", "com.amazon.venezia", "fake.com.android.vending")) {
            for (category in PlayInstallCategory.entries) {
                assertNull(PlayCategoryRules.exemption(installer, category.storeId))
            }
        }
    }

    @Test fun unverifiedNewAppsKeepTheirOriginalWaitDeadline() {
        assertEquals(901_000L, PlayCategoryRules.installWaitUntil(false, 1_000, 2_000, 900_000))
        assertEquals(901_000L, PlayCategoryRules.installWaitUntil(false, 1_000, 900_999, 900_000))
    }

    @Test fun aVerifiedCategoryRemovesOnlyTheInstallWait() {
        for (category in PlayInstallCategory.entries) {
            assertNull(PlayCategoryRules.installWaitUntil(false, 1_000, 2_000, 900_000, category))
            val otherLocks = AppLockReasons(appLock = true, keywordServiceMissing = true,
                installWaitUntil = PlayCategoryRules.installWaitUntil(false, 1_000, 2_000, 900_000, category))
            assertTrue(otherLocks.shouldLock)
            assertEquals(2, otherLocks.descriptions().size)
        }
    }

    @Test fun elapsedWaitAndZeroWaitAndSystemAppsStillNeedNoInstallLock() {
        assertNull(PlayCategoryRules.installWaitUntil(false, 1_000, 901_000, 900_000))
        assertNull(PlayCategoryRules.installWaitUntil(false, 1_000, 2_000, 0))
        assertNull(PlayCategoryRules.installWaitUntil(true, 1_000, 2_000, 900_000))
    }

    @Test fun futureInstallTimesKeepExistingTimerBehavior() {
        assertNull(PlayCategoryRules.installWaitUntil(false, 2_000, 1_000, 900_000))
    }

    @Test fun cachedCategoryRoundTripsAndBelongsToOneInstalledVersion() {
        val record = PlayCategoryRecord(1_000, 2_000, "FINANCE", 10_000)
        assertEquals(record, PlayCategoryRecord.decode(record.encode()))
        assertTrue(record.matches(1_000, 2_000))
        assertFalse(record.matches(3_000, 3_000))
        assertFalse(record.matches(1_000, 4_000))
    }

    @Test fun failedLookupRecordsRoundTripWithoutGrantingAnExemption() {
        val record = PlayCategoryRecord(1_000, 2_000, "", 10_000)
        val restored = PlayCategoryRecord.decode(record.encode())!!
        assertEquals(record, restored)
        assertNull(PlayCategoryRules.exemption("com.android.vending", restored.category))
    }

    @Test fun malformedCacheCannotGrantAnExemption() {
        for (raw in listOf(null, "", "FINANCE", "1|2|FINANCE", "x|2|FINANCE|10", "1|x|FINANCE|10", "1|2|FINANCE|x")) {
            assertNull(PlayCategoryRecord.decode(raw))
        }
    }
}
