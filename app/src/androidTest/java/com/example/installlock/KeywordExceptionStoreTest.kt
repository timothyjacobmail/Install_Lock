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
class KeywordExceptionStoreTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs = base.getSharedPreferences("keyword_exception_test_${System.nanoTime()}", Context.MODE_PRIVATE)
    private val context = object : ContextWrapper(base) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
    }

    @After
    fun cleanUp() { prefs.edit().clear().commit() }

    @Test
    fun addingRequiresTheTaskPasswordAndAnActiveDifferentBlockedWord() {
        Store.addBrowserKeyword(context, "movie")
        assertFalse(Store.addKeywordException(context, "movie", "christian", "FilterPass4"))
        Store.setPassword(context, "MainPass1", Store.Pw.MAIN)
        installTaskTestCredentials(context, "FilterPass4", TaskKind.WORD_EXCEPTION, TaskKind.PAGE_EXCEPTION)
        assertFalse(Store.addKeywordException(context, "movie", "christian", "MainPass1"))
        assertFalse(Store.addKeywordException(context, "missing", "christian", "FilterPass4"))
        assertFalse(Store.addKeywordException(context, "movie", "MOVIE", "FilterPass4"))
        assertFalse(Store.addKeywordException(context, "movie", "invalid|word", "FilterPass4"))
        assertTrue(Store.keywordExceptions(context).isEmpty())
        assertTrue(Store.addKeywordException(context, " MOVIE ", " Christian ", "FilterPass4"))
        assertEquals(mapOf("movie" to setOf("christian")), Store.keywordExceptions(context))
        assertEquals(setOf("movie|christian"), prefs.getStringSet("keywordExceptions", emptySet()))
    }

    @Test
    fun lockoutPreventsAddingButRemovingIsImmediateWithoutAPassword() {
        installTaskTestCredentials(context, "FilterPass4", TaskKind.WORD_EXCEPTION, TaskKind.PAGE_EXCEPTION)
        assertTrue(Store.addKeywordException(context, "anime", "education", "FilterPass4"))
        repeat(5) { TaskPasswords.check(context, TaskScope(TaskKind.WORD_EXCEPTION), "wrong"); TaskPasswords.check(context, TaskScope(TaskKind.PAGE_EXCEPTION), "wrong") }
        assertFalse(Store.addKeywordException(context, "anime", "documentary", "FilterPass4"))
        Store.removeKeywordException(context, "anime", "education")
        assertTrue(Store.keywordExceptions(context).isEmpty())
        assertTrue("anime" in Store.browserKeywords(context))
    }

    @Test
    fun exceptionEditsPreserveTheBlockedWordsRemovalDeadline() {
        installTaskTestCredentials(context, "FilterPass4", TaskKind.WORD_EXCEPTION, TaskKind.PAGE_EXCEPTION)
        val deadline = Store.requestKeywordRemoval(context, "anime")!!
        assertTrue(Store.addKeywordException(context, "anime", "education", "FilterPass4"))
        assertEquals(deadline, Store.browserKeywordEntries(context).first { it.word == "anime" }.removeAt)
        Store.removeKeywordException(context, "anime", "education")
        assertEquals(deadline, Store.browserKeywordEntries(context).first { it.word == "anime" }.removeAt)
    }

    @Test
    fun expiredWordRemovalPrunesItsExceptionsWithoutResurrectingThemOnReadd() {
        installTaskTestCredentials(context, "FilterPass4", TaskKind.WORD_EXCEPTION, TaskKind.PAGE_EXCEPTION)
        assertTrue(Store.addKeywordException(context, "anime", "education", "FilterPass4"))
        assertTrue(Store.addKeywordException(context, "manga", "bible study", "FilterPass4"))
        val deadline = Store.requestKeywordRemoval(context, "anime")!!
        Store.applyDueKeywordRemovals(context, deadline - 1)
        assertEquals(setOf("education"), Store.keywordExceptions(context)["anime"])
        Store.applyDueKeywordRemovals(context, deadline)
        assertEquals(mapOf("manga" to setOf("bible study")), Store.keywordExceptions(context))
        assertEquals(setOf("manga|bible study"), prefs.getStringSet("keywordExceptions", emptySet()))
        assertTrue(Store.addBrowserKeyword(context, "anime"))
        assertNull(Store.keywordExceptions(context)["anime"])
    }

    @Test
    fun genericExceptionsRequireTheTaskPasswordAndPersistNormalizedWords() {
        assertTrue(Store.genericKeywordExceptions(context).isEmpty())
        assertFalse(Store.addGenericKeywordException(context, "christian", "FilterPass4"))
        Store.setPassword(context, "MainPass1", Store.Pw.MAIN)
        installTaskTestCredentials(context, "FilterPass4", TaskKind.WORD_EXCEPTION, TaskKind.PAGE_EXCEPTION)
        assertFalse(Store.addGenericKeywordException(context, "christian", "MainPass1"))
        assertFalse(Store.addGenericKeywordException(context, "bad|word", "FilterPass4"))
        assertTrue(Store.addGenericKeywordException(context, " CHRISTIAN ", "FilterPass4"))
        assertTrue(Store.addGenericKeywordException(context, " Bible   Study ", "FilterPass4"))
        assertEquals(setOf("christian", "bible study"), Store.genericKeywordExceptions(context))
        assertEquals(setOf("christian", "bible study"), prefs.getStringSet("genericKeywordExceptions", emptySet()))
    }

    @Test
    fun genericExceptionsCanBeRemovedDuringPasswordLockoutButCannotBeAdded() {
        installTaskTestCredentials(context, "FilterPass4", TaskKind.WORD_EXCEPTION, TaskKind.PAGE_EXCEPTION)
        assertTrue(Store.addGenericKeywordException(context, "christian", "FilterPass4"))
        repeat(5) { TaskPasswords.check(context, TaskScope(TaskKind.WORD_EXCEPTION), "wrong"); TaskPasswords.check(context, TaskScope(TaskKind.PAGE_EXCEPTION), "wrong") }
        assertFalse(Store.addGenericKeywordException(context, "education", "FilterPass4"))
        Store.removeGenericKeywordException(context, " CHRISTIAN ")
        assertTrue(Store.genericKeywordExceptions(context).isEmpty())
    }

    @Test
    fun genericRulesPreservePerWordRulesAndDeadlinesAndSurviveWordRemoval() {
        installTaskTestCredentials(context, "FilterPass4", TaskKind.WORD_EXCEPTION, TaskKind.PAGE_EXCEPTION)
        assertTrue(Store.addKeywordException(context, "manga", "bible study", "FilterPass4"))
        val deadline = Store.requestKeywordRemoval(context, "anime")!!
        assertTrue(Store.addGenericKeywordException(context, "christian", "FilterPass4"))
        Store.removeGenericKeywordException(context, "christian")
        assertEquals(deadline, Store.browserKeywordEntries(context).first { it.word == "anime" }.removeAt)
        assertTrue(Store.addGenericKeywordException(context, "christian", "FilterPass4"))
        Store.applyDueKeywordRemovals(context, deadline)
        assertEquals(setOf("christian"), Store.genericKeywordExceptions(context))
        assertEquals(mapOf("manga" to setOf("bible study")), Store.keywordExceptions(context))
        assertFalse("anime" in Store.browserKeywords(context))
    }
}
