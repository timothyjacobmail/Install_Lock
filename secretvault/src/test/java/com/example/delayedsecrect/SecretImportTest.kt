package com.example.delayedsecrect

import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test

class SecretImportTest {
    private fun rejected(block: () -> Unit): String {
        try { block() } catch (error: IllegalArgumentException) { return error.message.orEmpty() }
        throw AssertionError("Expected the import to be rejected")
    }

    @Test fun exportedListSupportsBomCommentsAndWindowsLineEndings() {
        val text = "\uFEFF# Delayed Secret import v1\r\n# Default delay\r\n\r\nInstall Lock: YouTube\tA1b2C3d4E5f6G7h\r\nBrowser\tH8i9J0k1L2m3N4o\r\n"
        assertEquals(listOf(
            SecretImportEntry("Install Lock: YouTube", "A1b2C3d4E5f6G7h"),
            SecretImportEntry("Browser", "H8i9J0k1L2m3N4o")
        ), SecretImport.read(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))))
    }

    @Test fun secretWhitespaceAndSpecialCharactersArePreserved() {
        assertEquals(SecretImportEntry("My name", "  #x=é  "), SecretImport.parse("  My name  \t  #x=é  ").single())
    }

    @Test fun invalidRowRejectsTheWholeFileWithoutEchoingItsSecret() {
        val message = rejected { SecretImport.parse("Good\tvalue\nMalformed secret-do-not-print") }
        assertTrue(message.contains("Line 2"))
        assertFalse(message.contains("secret-do-not-print"))
    }

    @Test fun extraColumnsAreRejectedRatherThanBecomingADelay() {
        rejected { SecretImport.parse("Name\tvalue\t1") }
    }

    @Test fun blankNamesBlankValuesAndNulAreRejected() {
        listOf("\tvalue", "  \tvalue", "Name\t   ", "Name\tval\u0000ue").forEach { text ->
            rejected { SecretImport.parse(text) }
        }
    }

    @Test fun repeatedNamesAreRejectedEvenWhenValuesMatch() {
        rejected { SecretImport.parse("Name\tvalue\nName\tvalue") }
    }

    @Test fun emptyOrCommentOnlyFilesAreRejected() {
        rejected { SecretImport.parse("") }
        rejected { SecretImport.parse("# Nothing here\n\n") }
    }

    @Test fun malformedUtf8IsRejected() {
        rejected { SecretImport.read(ByteArrayInputStream(byteArrayOf(0xC3.toByte(), 0x28))) }
    }

    @Test fun filesOverTheSizeLimitAreRejectedDuringReading() {
        rejected { SecretImport.read(ByteArrayInputStream(ByteArray(SecretImport.MAX_BYTES + 1) { 'x'.code.toByte() })) }
    }

    @Test fun entryLimitIsEnforced() {
        val text = (1..SecretImport.MAX_ENTRIES + 1).joinToString("\n") { "Name $it\tvalue" }
        rejected { SecretImport.parse(text) }
    }

    @Test fun all24DistinctTaskPasswordsCanBePlannedForImport() {
        val text = (1..24).joinToString("\n") { "Install Lock: Task $it\tAbc123def456GH$it" }
        val entries = SecretImport.parse(text)
        val plan = SecretImport.plan(emptyList(), entries)
        assertEquals(24, plan.additions.size)
        assertEquals(0, plan.skipped)
        assertEquals(entries, plan.additions)
    }

    @Test fun repeatedImportSkipsIdenticalSecretsAndPreservesTheirDelayState() {
        val original = Secret("old-id", "Name", "value", 60, PendingSecretDelay(5, 2000, 1))
        val plan = SecretImport.plan(listOf(original), listOf(SecretImportEntry("Name", "value"), SecretImportEntry("New", "other")))
        assertEquals(listOf(SecretImportEntry("New", "other")), plan.additions)
        assertEquals(1, plan.skipped)
        assertEquals(60, original.delayMinutes)
        assertEquals(2000L, original.pendingDelay!!.effectiveAtElapsedMs)
    }

    @Test fun nameConflictRejectsTheWholeImportAndDoesNotExposeValues() {
        val message = rejected {
            SecretImport.plan(listOf(Secret("old", "Name", "old-secret")), listOf(
                SecretImportEntry("New", "new-secret"), SecretImportEntry("Name", "different-secret")
            ))
        }
        assertTrue(message.contains("Name"))
        assertFalse(message.contains("old-secret"))
        assertFalse(message.contains("different-secret"))
    }

    @Test fun duplicateExistingNamesMustAllMatchTheImportedValue() {
        rejected {
            SecretImport.plan(listOf(Secret("a", "Name", "one"), Secret("b", "Name", "two")), listOf(SecretImportEntry("Name", "one")))
        }
    }
}
