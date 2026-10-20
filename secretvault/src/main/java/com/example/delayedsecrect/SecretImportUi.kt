package com.example.delayedsecrect

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class ImportPreview(val entries: List<SecretImportEntry>, val plan: SecretImportPlan)

@Composable
fun ImportSecretListButton(secretStore: SecretStore, settingsStore: SettingsStore, onImported: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf<ImportPreview?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                try {
                    preview = withContext(Dispatchers.IO) {
                        val entries = context.contentResolver.openInputStream(uri)?.use { SecretImport.read(it) }
                            ?: throw IllegalArgumentException("Could not open this file.")
                        ImportPreview(entries, SecretImport.plan(secretStore.getAll(), entries))
                    }
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    message = if (error is IllegalArgumentException) error.message else "Could not read the secret list. Try another file."
                } finally {
                    busy = false
                }
            }
        }
    }

    TextButton(enabled = !busy, onClick = { picker.launch(arrayOf("text/*", "application/octet-stream")) }) {
        Text(if (busy) "Importing…" else "Import")
    }

    preview?.let { selected ->
        AlertDialog(
            onDismissRequest = { if (!busy) preview = null },
            title = { Text("Import secret list") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Text("Add ${selected.plan.additions.size} secrets using the current default delay (${settingsStore.getDelayMinutes()} min).")
                    if (selected.plan.skipped > 0) {
                        Spacer(Modifier.height(8.dp))
                        Text("${selected.plan.skipped} identical secrets will be skipped.")
                    }
                    Spacer(Modifier.height(12.dp))
                    selected.plan.additions.forEach { Text("• ${it.name}", Modifier.padding(vertical = 3.dp)) }
                    Spacer(Modifier.height(12.dp))
                    Text("Existing secrets, delays and timers stay unchanged.")
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                }
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val result = withContext(Dispatchers.IO) { secretStore.importAll(selected.entries) }
                            preview = null
                            onImported()
                            message = "Imported ${result.imported} secrets with the default delay." +
                                if (result.skipped > 0) " Skipped ${result.skipped} identical secrets." else ""
                        } catch (error: Exception) {
                            if (error is kotlinx.coroutines.CancellationException) throw error
                            message = if (error is IllegalArgumentException) error.message else "Could not save the secret list. Try again."
                        } finally {
                            busy = false
                        }
                    }
                }) { Text(if (busy) "Importing…" else "Import ${selected.plan.additions.size}") }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { preview = null }) { Text("Cancel") } }
        )
    }

    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            title = { Text("Secret list import") },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } }
        )
    }
}
