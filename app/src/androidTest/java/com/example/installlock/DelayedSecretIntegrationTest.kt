package com.example.installlock

import android.content.ComponentName
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
class DelayedSecretIntegrationTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val names = mutableSetOf<String>()
    private val prefix = "delayed_link_test_${System.nanoTime()}_"
    private val c = object : ContextWrapper(base) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            names.add(name)
            return base.getSharedPreferences(prefix + name, mode)
        }
    }
    @After fun clean() { names.forEach { base.getSharedPreferences(prefix + it, 0).edit().clear().commit() } }

    @Test fun savedActionSurvivesReloadAndCanBeTakenOnlyOnce() {
        val task = DelayedTask(TaskScope(TaskKind.APP_UNLOCK, "com.google.android.youtube"), "YouTube for 5 minutes", mapOf("minutes" to 5))
        c.getSharedPreferences("delayed_secret_requests", 0).edit().putString("request", task.encode()).commit()
        assertEquals(task, DelayedSecrets.take(c, "request"))
        assertNull(DelayedSecrets.take(c, "request"))
        assertNull(DelayedSecrets.take(c, "another-request"))
    }

    @Test fun deliveryStillRequiresTheExactAppsCredentialAndAccessLimit() {
        val youtube = "com.google.android.youtube"
        val chrome = "com.android.chrome"
        c.getSharedPreferences("install_lock", 0).edit().putStringSet("protected", setOf(youtube, chrome)).commit()
        val items = listOf(
            NewTaskPassword(TaskScope(TaskKind.APP_UNLOCK, youtube), "YouTubePass123", 10),
            NewTaskPassword(TaskScope(TaskKind.APP_UNLOCK, chrome), "ChromePass123", 10),
            NewTaskPassword(TaskScope(TaskKind.KEYWORD_OFF), "KeywordPass123"))
        assertNull(TaskPasswords.vault(c).install(items, false))
        val task = DelayedTask(TaskScope(TaskKind.APP_UNLOCK, youtube), "YouTube", mapOf("minutes" to 5))
        assertFalse(task.execute(c, "ChromePass123"))
        assertFalse(task.execute(c, "KeywordPass123"))
        assertFalse(task.copy(details = "{\"minutes\":11}").execute(c, "YouTubePass123"))
        assertTrue(DelayedTask.decode(task.encode())!!.execute(c, "YouTubePass123"))
        assertTrue(Store.unlockedUntil(c, youtube) > System.currentTimeMillis())
        assertEquals(0L, Store.unlockedUntil(c, chrome))
        assertTrue(Store.browserKeywordFilterEnabled(c))
    }

    @Test fun callbackIsPrivateToInstallLock() {
        val info = base.packageManager.getActivityInfo(ComponentName(base, DelayedUnlockActivity::class.java), 0)
        assertFalse(info.exported)
    }

    @Test fun inlinePreparationNeverNavigatesAndPreservesTheExactAction() {
        val inlineContext = object : ContextWrapper(c) {
            override fun startActivity(intent: android.content.Intent) { fail("Inline requests must not open another activity") }
        }
        val task = DelayedTask(TaskScope(TaskKind.APP_UNLOCK, "com.google.android.youtube"), "YouTube for 5 minutes", mapOf("minutes" to 5))
        val id = DelayedSecrets.prepare(inlineContext, task)
        assertEquals(task, DelayedSecrets.peek(c, id))
        assertEquals(id, DelayedSecrets.prepare(inlineContext, task))
        val replacement = task.copy(title = "YouTube for 3 minutes", details = "{\"minutes\":3}")
        val next = DelayedSecrets.prepare(inlineContext, replacement)
        assertNotEquals(id, next)
        assertNull(DelayedSecrets.peek(c, id))
        assertEquals(replacement, DelayedSecrets.take(c, next))
        assertNull(DelayedSecrets.take(c, next))
    }
}
