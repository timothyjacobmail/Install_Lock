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
class DomainRuleStoreTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs = base.getSharedPreferences("domain_rules_test_${System.nanoTime()}", Context.MODE_PRIVATE)
    private val context = object : ContextWrapper(base) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
    }
    private val password = "FilterPass123"
    private fun addBlock(value: String = "manga", position: Int = 1) =
        Store.addDomainRule(context, DomainAction.BLOCK, DomainMatchType.CONTAINS, value, position)

    @After fun cleanUp() { prefs.edit().clear().commit() }

    @Test fun parsedRuleCacheTracksPreferenceChangesWithoutWaitingForListeners() {
        assertTrue(addBlock())
        val first = Store.domainRules(context)
        assertSame(first, Store.domainRules(context))
        prefs.edit().putString("domainRules", "replacement|BLOCK|SUFFIX|example.org").commit()
        val replacement = Store.domainRules(context)
        assertEquals("example.org", replacement.single().value)
        assertEquals("manga", first.single().value)
        prefs.edit().remove("domainRules").commit()
        assertTrue(Store.domainRules(context).isEmpty())
    }

    @Test fun blockRulesAreImmediateAndInvalidOrDuplicateRulesDoNotMutateTheList() {
        assertTrue(Store.domainRules(context).isEmpty())
        assertTrue(addBlock())
        assertFalse(addBlock())
        assertFalse(addBlock("anime", 3))
        assertFalse(addBlock("anime", 0))
        assertFalse(addBlock("https://invalid.com"))
        assertEquals(listOf("manga"), Store.domainRules(context).map { it.value })
        assertEquals(DomainAction.BLOCK, DomainRules(Store.domainRules(context)).match("manga.org")!!.rule.action)
    }

    @Test fun allowRulesRequireTheTaskPasswordAndPersistTheirRequestedPosition() {
        installTaskTestCredentials(context, password, TaskKind.DOMAIN_ALLOW, TaskKind.DOMAIN_MOVE, TaskKind.DOMAIN_REMOVE)
        Store.setPassword(context, "MainPass123", Store.Pw.MAIN)
        Store.setPassword(context, "SettingsPass123", Store.Pw.SETTINGS)
        assertTrue(addBlock())
        fun addAllow(pw: String?) = Store.addDomainRule(context, DomainAction.ALLOW, DomainMatchType.SUFFIX, ".org", 2, pw)
        assertFalse(addAllow(null))
        assertFalse(addAllow("MainPass123"))
        assertFalse(addAllow("SettingsPass123"))
        assertTrue(addAllow(password))
        assertEquals(listOf("manga", "org"), Store.domainRules(context).map { it.value })
        assertEquals(Store.domainRules(context), prefs.getString("domainRules", "")!!.lines().mapNotNull { DomainRule.decode(it) })
    }

    @Test fun reorderingRequiresAuthenticationAndChangesFirstMatchInBothDirections() {
        installTaskTestCredentials(context, password, TaskKind.DOMAIN_ALLOW, TaskKind.DOMAIN_MOVE, TaskKind.DOMAIN_REMOVE)
        assertTrue(addBlock())
        assertTrue(Store.addDomainRule(context, DomainAction.ALLOW, DomainMatchType.SUFFIX, "org", 2, password))
        val allow = Store.domainRules(context)[1]
        assertFalse(Store.moveDomainRule(context, allow.id, 1, "wrong"))
        assertFalse(Store.moveDomainRule(context, allow.id, 0, password))
        assertTrue(Store.moveDomainRule(context, allow.id, 1, password))
        assertEquals(allow, Store.domainRules(context).first())
        assertEquals(DomainAction.ALLOW, DomainRules(Store.domainRules(context)).match("manga.org")!!.rule.action)
        assertTrue(Store.moveDomainRule(context, allow.id, 2, password))
        assertEquals(DomainAction.BLOCK, DomainRules(Store.domainRules(context)).match("manga.org")!!.rule.action)
    }

    @Test fun removingBlockRequiresPasswordButRemovingAllowIsFree() {
        assertTrue(addBlock())
        val block = Store.domainRules(context).single()
        assertFalse(Store.removeDomainRule(context, block.id))
        installTaskTestCredentials(context, password, TaskKind.DOMAIN_ALLOW, TaskKind.DOMAIN_MOVE, TaskKind.DOMAIN_REMOVE)
        assertFalse(Store.removeDomainRule(context, block.id, "wrong"))
        assertTrue(Store.addDomainRule(context, DomainAction.ALLOW, DomainMatchType.SUFFIX, "org", 2, password))
        assertTrue(Store.removeDomainRule(context, Store.domainRules(context)[1].id))
        assertTrue(Store.removeDomainRule(context, block.id, password))
        assertTrue(Store.domainRules(context).isEmpty())
    }

    @Test fun passwordLockoutPreventsReorderingAndRemovalEvenWithCorrectPassword() {
        installTaskTestCredentials(context, password, TaskKind.DOMAIN_ALLOW, TaskKind.DOMAIN_MOVE, TaskKind.DOMAIN_REMOVE)
        assertTrue(addBlock())
        assertTrue(addBlock("anime", 2))
        val before = Store.domainRules(context)
        repeat(5) { listOf(TaskKind.DOMAIN_ALLOW, TaskKind.DOMAIN_MOVE, TaskKind.DOMAIN_REMOVE).forEach { TaskPasswords.check(context, TaskScope(it), "wrong") } }
        assertTrue(TaskPasswords.remaining(context, TaskScope(TaskKind.DOMAIN_MOVE)) > 0)
        assertFalse(Store.moveDomainRule(context, before[1].id, 1, password))
        assertFalse(Store.removeDomainRule(context, before[0].id, password))
        assertFalse(Store.addDomainRule(context, DomainAction.ALLOW, DomainMatchType.SUFFIX, "org", 1, password))
        assertEquals(before, Store.domainRules(context))
    }
}
