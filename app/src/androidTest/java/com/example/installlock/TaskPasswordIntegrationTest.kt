package com.example.installlock

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TaskPasswordIntegrationTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs = base.getSharedPreferences("task_migration_${System.nanoTime()}", Context.MODE_PRIVATE)
    private val context = object : ContextWrapper(base) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
    }
    @After fun cleanUp() { prefs.edit().clear().commit() }

    @Test fun migrationVerifiesEveryExistingCredentialAndRetiresBroadPasswordsWithoutLosingRules() {
        val legacy = Store.Pw.entries.associateWith { "Old${it.name}Pass123" }
        legacy.forEach { (role, password) -> Store.setPassword(context, password, role) }
        prefs.edit().putStringSet("protected", setOf("com.google.android.youtube"))
            .putLong("unlockedUntil", System.currentTimeMillis() + 600_000).commit()
        Store.addExtraLockedSite(context, "example.com")
        val deadline = Store.requestKeywordRemoval(context, "anime")!!
        val entries = TaskPasswords.required(context).mapIndexed { index, scope ->
            NewTaskPassword(scope, "NewScoped${index}Pass123", if (scope.kind == TaskKind.APP_UNLOCK) 15 else null)
        }
        assertNotNull(TaskPasswords.migrate(context, legacy + (Store.Pw.FILTER to "wrong"), entries, false))
        assertFalse(TaskPasswords.ready(context))
        assertTrue(Store.hasPassword(context, Store.Pw.FILTER))
        assertEquals(0L, Store.unlockedUntil(context, "com.google.android.youtube"))
        assertNull(TaskPasswords.migrate(context, legacy, entries, false))
        assertTrue(TaskPasswords.ready(context))
        assertTrue(Store.hasPassword(context, Store.Pw.MAIN))
        assertTrue(listOf(Store.Pw.FILTER, Store.Pw.SETTINGS, Store.Pw.APP_LOCK).none { Store.hasPassword(context, it) })
        assertFalse(Store.disableBrowserKeywordFilter(context, legacy.getValue(Store.Pw.FILTER)))
        assertFalse(Store.unlockApp(context, "com.google.android.youtube", 5, legacy.getValue(Store.Pw.APP_LOCK)))
        assertEquals("example.com", Store.extraLockedSites(context).single().domain)
        assertEquals(deadline, Store.browserKeywordEntries(context).first { it.word == "anime" }.removeAt)
        assertNotNull(TaskPasswords.migrate(context, legacy, entries, false))
    }

    @Test fun categoryTimingAndSecurityPasswordsCannotBeUsedInterchangeably() {
        val credentials = listOf(TaskKind.TIMING, TaskKind.CATEGORIES, TaskKind.SECURITY).map {
            NewTaskPassword(TaskScope(it), "${it.name}Pass123")
        }
        assertNull(TaskPasswords.vault(context).install(credentials, false))
        val before = Store.timingConfig(context)
        assertFalse(TaskSettings.save(context, mapOf(TimingOption.INSTALL_WAIT to 1), null, false, "CATEGORIESPass123"))
        assertFalse(TaskSettings.categories(context, emptySet(), "TIMINGPass123"))
        assertFalse(TaskSettings.save(context, mapOf(TimingOption.PASSWORD_FAILURES to 10), null, false, "TIMINGPass123"))
        assertEquals(before, Store.timingConfig(context))
        assertTrue(TaskSettings.save(context, mapOf(TimingOption.INSTALL_WAIT to 1), null, false, "TIMINGPass123"))
        assertEquals(before[TimingOption.PASSWORD_FAILURES], Store.timingConfig(context)[TimingOption.PASSWORD_FAILURES])
        assertTrue(TaskSettings.categories(context, emptySet(), "CATEGORIESPass123"))
        assertEquals(1, Store.timingConfig(context)[TimingOption.INSTALL_WAIT])
    }

    @Test fun domainAllowReorderAndBlockRemovalRequireThreeDifferentCredentials() {
        val credentials = listOf(TaskKind.DOMAIN_ALLOW, TaskKind.DOMAIN_MOVE, TaskKind.DOMAIN_REMOVE).map {
            NewTaskPassword(TaskScope(it), "${it.name}Pass123")
        }
        assertNull(TaskPasswords.vault(context).install(credentials, false))
        assertTrue(Store.addDomainRule(context, DomainAction.BLOCK, DomainMatchType.CONTAINS, "manga", 1))
        assertFalse(Store.addDomainRule(context, DomainAction.ALLOW, DomainMatchType.SUFFIX, "org", 2, "DOMAIN_MOVEPass123"))
        assertTrue(Store.addDomainRule(context, DomainAction.ALLOW, DomainMatchType.SUFFIX, "org", 2, "DOMAIN_ALLOWPass123"))
        val rules = Store.domainRules(context)
        assertFalse(Store.moveDomainRule(context, rules[1].id, 1, "DOMAIN_ALLOWPass123"))
        assertTrue(Store.moveDomainRule(context, rules[1].id, 1, "DOMAIN_MOVEPass123"))
        assertFalse(Store.removeDomainRule(context, rules[0].id, "DOMAIN_MOVEPass123"))
        assertTrue(Store.removeDomainRule(context, rules[0].id, "DOMAIN_REMOVEPass123"))
    }

    @Test fun exceptionsDoNotAuthorizeDisablingTheFilterOrAddingOtherTypesOfExceptions() {
        val kinds = listOf(TaskKind.WORD_EXCEPTION, TaskKind.PAGE_EXCEPTION, TaskKind.KEYWORD_OFF)
        assertNull(TaskPasswords.vault(context).install(kinds.map { NewTaskPassword(TaskScope(it), "${it.name}Pass123") }, false))
        assertTrue(Store.addKeywordException(context, "anime", "christian", "WORD_EXCEPTIONPass123"))
        assertFalse(Store.addGenericKeywordException(context, "education", "WORD_EXCEPTIONPass123"))
        assertFalse(Store.disableBrowserKeywordFilter(context, "PAGE_EXCEPTIONPass123"))
        assertTrue(Store.browserKeywordFilterEnabled(context))
        assertTrue(Store.addGenericKeywordException(context, "education", "PAGE_EXCEPTIONPass123"))
        assertTrue(Store.disableBrowserKeywordFilter(context, "KEYWORD_OFFPass123"))
    }

    @Test fun anAuthorizedOperationDoesNotLeaveReusablePermissionBehind() {
        installTaskTestCredentials(context, "ScreenshotPass1", TaskKind.SCREENSHOTS_ALLOW)
        var actions = 0
        val scope = TaskScope(TaskKind.SCREENSHOTS_ALLOW)
        assertTrue(TaskPasswords.run(context, scope, "ScreenshotPass1") { actions++; true })
        assertFalse(TaskPasswords.run(context, scope, "") { actions++; true })
        assertFalse(TaskPasswords.run(context, TaskScope(TaskKind.CURBOX_OFF), "ScreenshotPass1") { actions++; true })
        assertEquals(1, actions)
    }
}
