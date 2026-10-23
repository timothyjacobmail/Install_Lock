package com.example.installlock

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.delayedsecrect.SecretStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScheduledTasksTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val names = mutableSetOf<String>()
    private val prefix = "scheduled_test_${System.nanoTime()}_"
    private val c = object : ContextWrapper(base) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            names.add(name)
            return base.getSharedPreferences(prefix + name, mode)
        }
    }
    private val scope = TaskScope(TaskKind.CATEGORIES)
    private val password = "SavedCategory123"
    private fun setup(): String {
        assertNull(TaskPasswords.vault(c).install(listOf(NewTaskPassword(scope, password)), false))
        SecretStore(c).add("Category delay", password, 2)
        return SecretStore(c).getAll().single().id
    }
    private fun task() = DelayedTask(scope, "Clear immediate-access categories", mapOf("categories" to emptyList<String>()))
    @After fun clean() {
        ScheduledTasks.all(c).forEach { ScheduledTasks.cancel(c, it.id) }
        names.forEach { base.getSharedPreferences(prefix + it, 0).edit().clear().commit() }
    }

    @Test fun confirmationWaitsThenAppliesTheExactTaskOnceWithoutASecondConfirmation() {
        val id = setup()
        val entry = ScheduledTasks.confirm(c, task(), id)
        val before = Store.immediatePlayCategories(c)
        assertEquals(120_000L, entry.countdown.duration)
        assertEquals(entry.id, ScheduledTasks.confirm(c, task(), id).id)
        assertEquals(before, Store.immediatePlayCategories(c))
        assertFalse(entry.encode().contains(password))
        var calls = 0
        val execute = { task: DelayedTask, value: String -> calls++; task.execute(c, value) }
        val due = WatchdogClock(0, entry.countdown.dueElapsed, entry.countdown.boot)
        ScheduledTasks.applyDue(c, due.copy(elapsed = due.elapsed - 1), execute)
        assertEquals(0, calls)
        ScheduledTasks.applyDue(c, due, execute)
        assertEquals(1, calls)
        assertTrue(Store.immediatePlayCategories(c).isEmpty())
        assertEquals("Applied", ScheduledTasks.all(c).single().result)
        ScheduledTasks.applyDue(c, due, execute)
        assertEquals(1, calls)
    }

    @Test fun cancellationAndDeletedSecretsNeverApplyTheTask() {
        val id = setup()
        val entry = ScheduledTasks.confirm(c, task(), id)
        val due = WatchdogClock(0, entry.countdown.dueElapsed, entry.countdown.boot)
        ScheduledTasks.cancel(c, entry.id)
        ScheduledTasks.applyDue(c, due) { _, _ -> fail("Cancelled task ran"); false }
        val next = ScheduledTasks.confirm(c, task(), id)
        SecretStore(c).delete(id)
        ScheduledTasks.applyDue(c, due.copy(elapsed = next.countdown.dueElapsed)) { _, _ -> fail("Deleted password was used"); false }
        assertTrue(ScheduledTasks.all(c).single().result.startsWith("Not applied"))
    }

    @Test fun changedCredentialsAreCheckedAgainAtExecution() {
        val id = setup()
        val entry = ScheduledTasks.confirm(c, task(), id)
        assertNull(TaskPasswords.change(c, scope, password, "ChangedPassword123", true))
        val before = Store.immediatePlayCategories(c)
        ScheduledTasks.applyDue(c, WatchdogClock(0, entry.countdown.dueElapsed, entry.countdown.boot)) { task, value ->
            task.execute(c, value)
        }
        assertEquals(before, Store.immediatePlayCategories(c))
        assertTrue(ScheduledTasks.all(c).single().result.startsWith("Not applied"))
    }

    @Test fun missingOrWrongSavedSecretsCannotScheduleATask() {
        setup()
        assertEquals(1, ScheduledTasks.choices(c, scope).size)
        assertTrue(ScheduledTasks.choices(c, TaskScope(TaskKind.WHITELIST_ADD)).isEmpty())
        try { ScheduledTasks.confirm(c, task(), "missing"); fail("Missing secret accepted") }
        catch (_: IllegalStateException) { }
        assertTrue(ScheduledTasks.all(c).isEmpty())
    }

    @Test fun completedWhitelistWaitDoesNotStartTheOldWhitelistTimer() {
        assertNull(TaskPasswords.vault(c).install(listOf(NewTaskPassword(TaskScope(TaskKind.WHITELIST_ADD), password)), false))
        Store.saveTimingConfig(c, TimingConfig(mapOf(TimingOption.SITE_WAIT to 15)))
        val whitelist = DelayedTask(TaskScope(TaskKind.WHITELIST_ADD), "Allow example.com", mapOf("site" to "example.com"))
        assertTrue(whitelist.execute(c, password))
        assertTrue("example.com" in Store.activePatterns(c))
        Store.saveTimingConfig(c, TimingConfig(mapOf(TimingOption.SITE_WAIT to 60)))
        assertTrue("example.com" in Store.activePatterns(c))
    }
}
