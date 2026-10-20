package com.example.delayedsecrect

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

data class SecretImportEntry(val name: String, val value: String)
data class SecretImportPlan(val additions: List<SecretImportEntry>, val skipped: Int)
data class SecretImportResult(val imported: Int, val skipped: Int)

/** UTF-8 text: one name, a tab, and one secret per line. No delay column: imports use the default. */
object SecretImport {
    const val MAX_BYTES = 1024 * 1024
    const val MAX_ENTRIES = 1000

    fun read(input: InputStream): List<SecretImportEntry> {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= MAX_BYTES) { "Choose a secret list smaller than 1 MB." }
            output.write(buffer, 0, count)
        }
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(output.toByteArray())).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            throw IllegalArgumentException("Save the secret list as UTF-8 text.")
        }
        return parse(text)
    }

    fun parse(text: String): List<SecretImportEntry> {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Choose a secret list smaller than 1 MB." }
        val result = mutableListOf<SecretImportEntry>()
        val names = mutableSetOf<String>()
        text.removePrefix("\uFEFF").lineSequence().forEachIndexed { index, line ->
            if (line.isBlank() || line.startsWith("#")) return@forEachIndexed
            val separator = line.indexOf('\t')
            require(separator > 0 && line.indexOf('\t', separator + 1) < 0) {
                "Line ${index + 1}: put a name, one tab, then the secret."
            }
            val name = line.substring(0, separator).trim()
            val value = line.substring(separator + 1)
            require(name.isNotBlank() && value.isNotBlank() && '\u0000' !in line) {
                "Line ${index + 1}: the name and secret must both contain text."
            }
            require(names.add(name)) { "Line ${index + 1}: this name appears twice in the file." }
            result.add(SecretImportEntry(name, value))
            require(result.size <= MAX_ENTRIES) { "Import at most $MAX_ENTRIES secrets at a time." }
        }
        require(result.isNotEmpty()) { "The file contains no secrets to import." }
        return result
    }

    fun plan(existing: List<Secret>, entries: List<SecretImportEntry>): SecretImportPlan {
        require(entries.isNotEmpty() && entries.size <= MAX_ENTRIES) { "Choose a list with 1 to $MAX_ENTRIES secrets." }
        require(entries.map { it.name }.distinct().size == entries.size) { "Each secret in the file needs a different name." }
        val byName = existing.groupBy { it.name }
        val additions = entries.filter { entry ->
            require(entry.name.isNotBlank() && entry.value.isNotBlank()) { "Every secret needs a name and value." }
            val matches = byName[entry.name].orEmpty()
            require(matches.all { it.value == entry.value }) {
                "A secret named \"${entry.name}\" already exists with a different value. Rename it in the file first."
            }
            matches.isEmpty()
        }
        return SecretImportPlan(additions, entries.size - additions.size)
    }
}
