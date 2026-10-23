package com.example.installlock

import org.junit.Assert.*
import org.junit.Test

class TaskVaultTest {
    @Test fun matchingSavedSecretsDoesNotConsumeAttemptsOrGrantTaskAccess() {
        val data = Memory()
        val vault = TaskVault(data)
        val scope = TaskScope(TaskKind.WHITELIST_ADD)
        assertNull(vault.install(listOf(NewTaskPassword(scope, "SavedPassword1")), false))
        val before = data.values.toMap()
        repeat(10) { assertFalse(vault.matchesStoredSecret(scope, "UnrelatedSecret1")) }
        assertTrue(vault.matchesStoredSecret(scope, "SavedPassword1"))
        assertFalse(vault.matchesStoredSecret(TaskScope(TaskKind.CATEGORIES), "SavedPassword1"))
        assertEquals(before, data.values)
        assertFalse(vault.check(scope, ""))
    }
    @Test fun statusReadsNeedOnlyTheirOwnKeysAndSeeNewValuesImmediately() {
        val values = mutableMapOf("task.ready" to "1", "task.APP_UNLOCK|com.example.app.until" to "2000",
            "task.APP_UNLOCK|com.example.app.limit" to "15", "task.APP_UNLOCK|com.example.app.hash" to "present")
        val vault = TaskVault(object : TaskData {
            override fun read(): Map<String, String> = error("Status reads must not copy all credentials")
            override fun get(key: String) = values[key]
            override fun write(changes: Map<String, String>) = false
        }, { 1000L })
        assertEquals(2000L, vault.until("com.example.app"))
        assertEquals(15, vault.limit("com.example.app"))
        assertTrue(vault.has(TaskScope(TaskKind.APP_UNLOCK, "com.example.app")))
        values["task.APP_UNLOCK|com.example.app.until"] = "0"
        assertEquals(0L, vault.until("com.example.app"))
        values["task.ready"] = "0"
        values["task.APP_UNLOCK|com.example.app.until"] = "5000"
        assertEquals(0L, vault.until("com.example.app"))
    }

    @Test fun lockAllEndsEveryAppSessionInOneWriteWithoutChangingCredentialsOrLimits() {
        val values = mutableMapOf("task.ready" to "1",
            "task.scopes" to "APP_UNLOCK|com.example.one\nAPP_UNLOCK|com.example.two\nWHITELIST_OFF|",
            "task.APP_UNLOCK|com.example.one.until" to "9000", "task.APP_UNLOCK|com.example.two.until" to "8000",
            "task.APP_UNLOCK|com.example.one.limit" to "15", "task.WHITELIST_OFF|.hash" to "secret")
        val before = values.toMap()
        var writes = 0
        val vault = TaskVault(object : TaskData {
            override fun read() = values.toMap()
            override fun write(changes: Map<String, String>): Boolean { writes++; values.putAll(changes); return true }
        })
        assertTrue(vault.lockAll())
        assertEquals(1, writes)
        assertEquals(0L, vault.until("com.example.one"))
        assertEquals(0L, vault.until("com.example.two"))
        assertEquals(before.filterKeys { !it.endsWith(".until") }, values.filterKeys { !it.endsWith(".until") })
    }
    @Test fun changingAPasswordCannotProbeAnotherTasksSecret() {
        val vault = TaskVault(Memory())
        install(vault, item(whitelist, "ChromeOnly1"), item(youtubeAccess, "YoutubePass1"))
        val matchingOtherTask = vault.change(whitelist, "ChromeOnly1", "YoutubePass1", false)
        val notMatchingOtherTask = vault.change(whitelist, "ChromeOnly1", "UnrelatedPass1", false)
        assertNotNull(matchingOtherTask)
        assertEquals(matchingOtherTask, notMatchingOtherTask)
        assertEquals(0L, vault.remaining(youtubeAccess))
        assertTrue(vault.check(youtubeAccess, "YoutubePass1"))
    }

    private class Memory : TaskData {
        val values = mutableMapOf<String, String>()
        var writable = true
        override fun read() = values.toMap()
        override fun write(changes: Map<String, String>): Boolean {
            if (!writable) return false
            values.putAll(changes)
            return true
        }
    }
    private val youtube = "com.google.android.youtube"
    private val chrome = "com.android.chrome"
    private val youtubeAccess = TaskScope(TaskKind.APP_UNLOCK, youtube)
    private val chromeAccess = TaskScope(TaskKind.APP_UNLOCK, chrome)
    private val removal = TaskScope(TaskKind.APP_REMOVE, youtube)
    private val limit = TaskScope(TaskKind.APP_LIMIT, youtube)
    private val whitelist = TaskScope(TaskKind.WHITELIST_OFF)
    private fun item(scope: TaskScope, password: String = "${scope.kind.name}${scope.app}Pass1", cap: Int = 15) =
        NewTaskPassword(scope, password, if (scope.kind == TaskKind.APP_UNLOCK) cap else null)
    private fun install(vault: TaskVault, vararg items: NewTaskPassword) { assertNull(vault.install(items.toList(), false)) }

    @Test fun youtubePasswordCannotUnlockChromeOrChangeAnyOtherProtection() {
        val vault = TaskVault(Memory())
        install(vault, item(youtubeAccess, "YoutubePass1"), item(chromeAccess, "ChromePass2"),
            item(removal), item(limit), item(whitelist))
        assertTrue(vault.unlock(youtube, 10, 120, "YoutubePass1"))
        assertFalse(vault.unlock(chrome, 10, 120, "YoutubePass1"))
        assertEquals(0L, vault.until(chrome))
        assertFalse(vault.check(removal, "YoutubePass1"))
        assertFalse(vault.check(whitelist, "YoutubePass1"))
        assertFalse(vault.setLimit(youtube, 60, "YoutubePass1"))
        assertEquals(15, vault.limit(youtube))
    }

    @Test fun successfulVerificationDoesNotCreateASessionForLaterOperations() {
        val vault = TaskVault(Memory())
        install(vault, item(whitelist, "ChromeOnly1"))
        assertTrue(vault.check(whitelist, "ChromeOnly1"))
        assertFalse(vault.check(whitelist, ""))
        assertFalse(vault.check(whitelist, "wrong"))
        assertFalse(vault.check(TaskScope(TaskKind.KEYWORD_OFF), "ChromeOnly1"))
    }

    @Test fun appLimitAndGlobalLimitAreBothEnforced() {
        var now = 1000L
        val vault = TaskVault(Memory(), { now })
        install(vault, item(youtubeAccess, "YoutubePass1", 15))
        assertFalse(vault.unlock(youtube, 16, 120, "YoutubePass1"))
        assertFalse(vault.unlock(youtube, 11, 10, "YoutubePass1"))
        assertFalse(vault.unlock(youtube, 0, 120, "YoutubePass1"))
        assertTrue(vault.unlock(youtube, 10, 10, "YoutubePass1"))
        assertEquals(now + 600_000, vault.until(youtube))
        now += 600_001
        assertTrue(vault.until(youtube) < now)
    }

    @Test fun onlyTheAppLimitCredentialCanChangeItsCapAndCannotExtendAnExistingTimer() {
        val vault = TaskVault(Memory(), { 1000L })
        install(vault, item(youtubeAccess, "YoutubePass1"), item(limit, "LimitPass2"), item(chromeAccess))
        assertTrue(vault.unlock(youtube, 15, 120, "YoutubePass1"))
        assertTrue(vault.setLimit(youtube, 5, "LimitPass2"))
        assertEquals(301_000L, vault.until(youtube))
        assertTrue(vault.setLimit(youtube, 60, "LimitPass2"))
        assertEquals(301_000L, vault.until(youtube))
        assertFalse(vault.setLimit(chrome, 60, "LimitPass2"))
    }

    @Test fun globalDurationChangesCannotRaiseAnAppsOwnCapOrExtendItsAccess() {
        val vault = TaskVault(Memory(), { 1000L })
        install(vault, item(youtubeAccess, "YoutubePass1", 15))
        assertTrue(vault.unlock(youtube, 15, 120, "YoutubePass1"))
        vault.capAll(5)
        assertEquals(301_000L, vault.until(youtube))
        vault.capAll(120)
        assertEquals(301_000L, vault.until(youtube))
        assertFalse(vault.unlock(youtube, 16, 120, "YoutubePass1"))
    }

    @Test fun passwordLockoutsAreIndependentForEachAppAndEachAction() {
        var now = 1000L
        val vault = TaskVault(Memory(), { now })
        install(vault, item(youtubeAccess), item(chromeAccess), item(removal))
        repeat(5) { assertFalse(vault.check(youtubeAccess, "wrong")) }
        assertEquals(60_000L, vault.remaining(youtubeAccess))
        assertFalse(vault.check(youtubeAccess, item(youtubeAccess).password))
        assertTrue(vault.check(chromeAccess, item(chromeAccess).password))
        assertTrue(vault.check(removal, item(removal).password))
        now += 60_001
        assertTrue(vault.check(youtubeAccess, item(youtubeAccess).password))
    }

    @Test fun blankOrMissingCredentialsDoNotConsumeAttemptsOrCreatePasswords() {
        val memory = Memory()
        val vault = TaskVault(memory)
        install(vault, item(whitelist))
        val before = memory.values.toMap()
        repeat(6) { assertFalse(vault.check(whitelist, " \n")) }
        assertFalse(vault.check(youtubeAccess, "AbsentPass1"))
        assertEquals(before, memory.values)
    }

    @Test fun duplicatePasswordsNeedExplicitSharingAndNeverStorePlaintext() {
        val memory = Memory()
        val vault = TaskVault(memory)
        val items = listOf(item(youtubeAccess, "SharedPass1"), item(chromeAccess, "SharedPass1"))
        assertNotNull(vault.install(items, false))
        assertFalse(vault.ready())
        assertNull(vault.install(items, true))
        assertTrue(vault.check(youtubeAccess, "SharedPass1"))
        assertTrue(vault.check(chromeAccess, "SharedPass1"))
        assertFalse(memory.values.values.any { "SharedPass1" in it })
        assertEquals(2, memory.values.filterKeys { it.endsWith("salt") }.values.distinct().size)
    }

    @Test fun changingOnePasswordRequiresThatExactCredentialAndPreservesOtherPasswordsAndTimers() {
        val vault = TaskVault(Memory())
        install(vault, item(youtubeAccess, "YoutubePass1"), item(chromeAccess, "ChromePass2"))
        assertTrue(vault.unlock(youtube, 5, 120, "YoutubePass1"))
        val until = vault.until(youtube)
        val next = TaskVault.generatePassword(youtubeAccess)
        assertNotNull(vault.change(youtubeAccess, "ChromePass2", next, false))
        assertNull(vault.change(youtubeAccess, "YoutubePass1", next, false))
        assertFalse(vault.check(youtubeAccess, "YoutubePass1"))
        assertTrue(vault.check(youtubeAccess, next))
        assertTrue(vault.check(chromeAccess, "ChromePass2"))
        assertEquals(15, vault.limit(youtube))
        assertEquals(until, vault.until(youtube))
    }

    @Test fun passwordChangesNeedExplicitConsentToShareWithAnotherTask() {
        val vault = TaskVault(Memory())
        install(vault, item(youtubeAccess, "YoutubePass1"), item(chromeAccess, "ChromePass2"))
        assertNotNull(vault.change(youtubeAccess, "YoutubePass1", "ChromePass2", false))
        assertTrue(vault.check(youtubeAccess, "YoutubePass1"))
        assertNull(vault.change(youtubeAccess, "YoutubePass1", "ChromePass2", true))
        assertTrue(vault.check(youtubeAccess, "ChromePass2"))
    }

    @Test fun setupCannotBeRerunToOverwriteExistingCredentials() {
        val vault = TaskVault(Memory())
        install(vault, item(youtubeAccess, "YoutubePass1"))
        assertNotNull(vault.install(listOf(item(youtubeAccess, "OtherPass2")), true))
        assertTrue(vault.check(youtubeAccess, "YoutubePass1"))
    }

    @Test fun newAppEnrollmentCannotReplaceExistingCredentialsOrAuthorizeOtherTasks() {
        val vault = TaskVault(Memory())
        install(vault, item(TaskScope(TaskKind.ENROLL_APP), "EnrollPass1"), item(whitelist))
        val items = TaskScope.appTasks(youtube).map { item(it, TaskVault.generatePassword(it)) }
        assertNotNull(vault.enroll(items, "wrong", false))
        assertFalse(vault.has(youtubeAccess))
        assertNull(vault.enroll(items, "EnrollPass1", false))
        assertNotNull(vault.enroll(items.map { it.copy(password = "OverwritePass1") }, "EnrollPass1", true))
        assertTrue(vault.check(youtubeAccess, items.first { it.scope == youtubeAccess }.password))
        assertFalse(vault.check(whitelist, "EnrollPass1"))
    }

    @Test fun newAppEnrollmentRejectsMixedAppTasksOrMissingActions() {
        val vault = TaskVault(Memory())
        install(vault, item(TaskScope(TaskKind.ENROLL_APP), "EnrollPass1"))
        assertNotNull(vault.enroll(listOf(item(youtubeAccess), item(TaskScope(TaskKind.APP_REMOVE, chrome)), item(limit)), "EnrollPass1", false))
        assertNotNull(vault.enroll(listOf(item(youtubeAccess)), "EnrollPass1", false))
        assertFalse(vault.has(youtubeAccess))
    }

    @Test fun relockingOneAppDoesNotEndAnotherAppsAuthorizedSession() {
        val vault = TaskVault(Memory())
        install(vault, item(youtubeAccess), item(chromeAccess))
        assertTrue(vault.unlock(youtube, 5, 120, item(youtubeAccess).password))
        assertTrue(vault.unlock(chrome, 5, 120, item(chromeAccess).password))
        val other = vault.until(chrome)
        vault.lock(youtube)
        assertEquals(0L, vault.until(youtube))
        assertEquals(other, vault.until(chrome))
    }

    @Test fun credentialsLimitsAndTimersPersistAcrossVaultInstances() {
        val memory = Memory()
        val vault = TaskVault(memory)
        install(vault, item(youtubeAccess))
        assertTrue(vault.unlock(youtube, 5, 120, item(youtubeAccess).password))
        val loaded = TaskVault(memory)
        assertEquals(vault.scopes(), loaded.scopes())
        assertEquals(vault.until(youtube), loaded.until(youtube))
        assertEquals(15, loaded.limit(youtube))
        assertTrue(loaded.check(youtubeAccess, item(youtubeAccess).password))
    }

    @Test fun failedWritesDoNotCreateReadyStateOrGrantAuthorization() {
        val memory = Memory().apply { writable = false }
        val vault = TaskVault(memory)
        assertNotNull(vault.install(listOf(item(whitelist)), false))
        assertFalse(vault.ready())
        memory.writable = true
        install(vault, item(whitelist))
        memory.writable = false
        assertFalse(vault.check(whitelist, item(whitelist).password))
    }

    @Test fun scopesCannotCollideThroughMalformedAppIdentifiers() {
        assertNull(TaskScope.decode("APP_UNLOCK|com.android.chrome|extra"))
        assertNull(TaskScope.decode("WHITELIST_OFF|com.android.chrome"))
        assertNull(TaskScope.decode("APP_UNLOCK|"))
        assertNull(TaskScope.decode("APP_UNLOCK|com.foo\nuntil"))
        assertEquals(youtubeAccess, TaskScope.decode(youtubeAccess.id))
    }

    @Test fun generatedCredentialsAreDistinctAndBoundToTheirChosenTask() {
        val vault = TaskVault(Memory())
        install(vault, item(youtubeAccess, "YoutubePass1"), item(chromeAccess, "ChromePass2"))
        val password = TaskVault.generatePassword(youtubeAccess)
        assertTrue(StorePasswordRules.valid(password))
        assertNotEquals(password, TaskVault.generatePassword(youtubeAccess))
        assertNotNull(vault.change(chromeAccess, "ChromePass2", password, false))
        assertNull(vault.change(youtubeAccess, "YoutubePass1", password, false))
        assertTrue(vault.check(youtubeAccess, password))
        assertTrue(vault.check(chromeAccess, "ChromePass2"))
    }
}
