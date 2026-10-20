package com.example.delayedsecrect

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(settingsStore: SettingsStore, onDone: () -> Unit) {
    var delayMinutesText by remember { mutableStateOf(settingsStore.getDelayMinutes().toString()) }
    var visibleSecondsText by remember { mutableStateOf(settingsStore.getVisibleSeconds().toString()) }

    var activeDelay by remember { mutableStateOf(settingsStore.getDelayMinutes()) }
    var activeVisible by remember { mutableStateOf(settingsStore.getVisibleSeconds()) }
    var pendingDelay by remember { mutableStateOf(settingsStore.getPendingDelay()) }
    var pendingDelayRemaining by remember { mutableStateOf(settingsStore.pendingDelayRemainingMs()) }
    var pendingVisible by remember { mutableStateOf(settingsStore.getPendingVisible()) }
    var pendingVisibleRemaining by remember { mutableStateOf(settingsStore.pendingVisibleRemainingMs()) }

    LaunchedEffect(Unit) {
        while (true) {
            settingsStore.reconcilePending()
            activeDelay = settingsStore.getDelayMinutes()
            activeVisible = settingsStore.getVisibleSeconds()
            pendingDelay = settingsStore.getPendingDelay()
            pendingDelayRemaining = settingsStore.pendingDelayRemainingMs()
            pendingVisible = settingsStore.getPendingVisible()
            pendingVisibleRemaining = settingsStore.pendingVisibleRemainingMs()
            delay(1000)
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
        ) {
            Text("Currently active: $activeDelay min delay, ${activeVisible}s visible")
            Spacer(modifier = Modifier.height(16.dp))

            if (pendingDelay != null && pendingDelayRemaining != null) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Pending decrease: ${pendingDelay!!.delayMinutes} min delay")
                        Text("Applies in ${formatDuration(pendingDelayRemaining!!)}")
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(onClick = {
                            settingsStore.cancelPendingDelay()
                            pendingDelay = null
                            pendingDelayRemaining = null
                        }) {
                            Text("Cancel this change")
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (pendingVisible != null && pendingVisibleRemaining != null) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Pending: ${pendingVisible!!.visibleSeconds}s visible")
                        Text("Applies in ${formatDuration(pendingVisibleRemaining!!)}")
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(onClick = {
                            settingsStore.cancelPendingVisible()
                            pendingVisible = null
                            pendingVisibleRemaining = null
                        }) {
                            Text("Cancel this change")
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            Text("New delay before reveal (minutes)")
            OutlinedTextField(
                value = delayMinutesText,
                onValueChange = { delayMinutesText = it.filter { c -> c.isDigit() } },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(16.dp))

            Text("New visible duration (seconds)")
            OutlinedTextField(
                value = visibleSecondsText,
                onValueChange = { visibleSecondsText = it.filter { c -> c.isDigit() } },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(24.dp))

            Text(
                "Increasing the delay applies immediately. Decreasing the delay, or changing " +
                        "the visible duration in either direction, takes effect 30 minutes after saving. " +
                        "None of this affects a countdown or reveal already in progress.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(16.dp))

            Button(onClick = {
                val minutes = delayMinutesText.toIntOrNull()?.coerceAtLeast(1) ?: 15
                val seconds = visibleSecondsText.toIntOrNull()?.coerceAtLeast(1) ?: 60
                settingsStore.requestChange(minutes, seconds)
                activeDelay = settingsStore.getDelayMinutes()
                activeVisible = settingsStore.getVisibleSeconds()
                pendingDelay = settingsStore.getPendingDelay()
                pendingDelayRemaining = settingsStore.pendingDelayRemainingMs()
                pendingVisible = settingsStore.getPendingVisible()
                pendingVisibleRemaining = settingsStore.pendingVisibleRemainingMs()
            }) {
                Text("Save")
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(onClick = onDone) {
                Text("Back")
            }
        }
    }
}