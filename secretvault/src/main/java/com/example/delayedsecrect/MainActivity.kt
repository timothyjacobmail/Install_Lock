package com.example.delayedsecrect

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import androidx.activity.result.contract.ActivityResultContracts
import android.os.Build
import android.content.Intent
import androidx.compose.ui.res.painterResource

// Defaults per your spec — will become user-configurable in the Settings step
const val DEFAULT_DELAY_MS = 1 * 60 * 1000L
const val DEFAULT_VISIBLE_MS = 60 * 1000L
const val EXTEND_MS = 5 * 60 * 1000L
const val HARD_LOCK_MS = 60 * 60 * 1000L // 1 hour
enum class Screen { LIST, SETTINGS }
class MainActivity : ComponentActivity() {

    private var refreshGeneration by mutableIntStateOf(0)
    override fun onResume() {
        super.onResume()
        refreshGeneration++
    }

    private lateinit var secretStore: SecretStore
    private lateinit var timerStore: TimerStore

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op either way */ }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createNotificationChannel(applicationContext)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        secretStore = SecretStore(applicationContext)
        timerStore = TimerStore(applicationContext)

        val settingsStore = SettingsStore(applicationContext)
        setContent {
            MaterialTheme {
                var screen by remember { mutableStateOf(Screen.LIST) }
                when (screen) {
                    Screen.LIST -> SecretListScreen(
                        secretStore, timerStore, settingsStore,
                        refreshGeneration = refreshGeneration,
                        onOpenSettings = { screen = Screen.SETTINGS }
                    )
                    Screen.SETTINGS -> SettingsScreen(
                        settingsStore,
                        onDone = { screen = Screen.LIST }
                    )
                }
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SecretListScreen(
    secretStore: SecretStore,
    timerStore: TimerStore,
    settingsStore: SettingsStore,
    refreshGeneration: Int = 0,
    onOpenSettings: () -> Unit
) {
    var secretPendingDelete by remember { mutableStateOf<Secret?>(null) }
    var secretEditingDelay by remember { mutableStateOf<Secret?>(null) }
    var secrets by remember { mutableStateOf(secretStore.getAll()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val searchTerms = searchQuery.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val visibleSecrets = secrets.filter { secret -> searchTerms.all { secret.name.contains(it, ignoreCase = true) } }

    // One RevealTimer per secret, created once and reused
    var delayMs by remember { mutableStateOf(settingsStore.getDelayMinutes() * 60_000L) }
    var visibleMs by remember { mutableStateOf(settingsStore.getVisibleSeconds() * 1000L) }

    // Re-read settings each time this screen becomes visible again (e.g. returning from Settings)
    LaunchedEffect(Unit) {
        delayMs = settingsStore.getDelayMinutes() * 60_000L
        visibleMs = settingsStore.getVisibleSeconds() * 1000L
    }

    // An external unlock may close/change a timer while this screen is in the background.
    val timers = remember(refreshGeneration, TimerStore.revision) { mutableStateMapOf<String, RevealTimer>() }
    secrets.forEach { secret ->
        if (!timers.containsKey(secret.id)) {
            timers[secret.id] = RevealTimer(secret.id, delayMs, visibleMs, timerStore)
        }
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val linkedRequests = remember { InstallLockRequests(context) }
    var overlayGranted by remember { mutableStateOf(hasOverlayPermission(context)) }

    val lastKnownState = remember(refreshGeneration) { mutableStateMapOf<String, RevealState>() }

    // Global 1-second tick so every card's countdown updates live
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            tick++
        }
    }
    LaunchedEffect(tick) {
        overlayGranted = hasOverlayPermission(context)
        settingsStore.reconcilePending()
        secrets = secretStore.getAll()
        delayMs = settingsStore.getDelayMinutes() * 60_000L
        visibleMs = settingsStore.getVisibleSeconds() * 1000L
    }

    val activeLinks = remember(tick, refreshGeneration) {
        linkedRequests.all().filter { it.secretId.isNotEmpty() && linkedRequests.availability(it) != null }.associateBy { it.secretId }
    }



    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Delayed Secrets") },
                actions = {
                    ImportSecretListButton(secretStore, settingsStore) { secrets = secretStore.getAll() }
                    IconButton(onClick = onOpenSettings) {
                        Icon(painterResource(R.drawable.ic_settings), contentDescription = "Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Text("+")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Search secrets") },
                placeholder = { Text("Search by name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) TextButton(onClick = { searchQuery = "" }) { Text("Clear") }
                }
            )
            if (secrets.isNotEmpty() && visibleSecrets.isEmpty()) {
                Text("No secrets match your search.", Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            if (secrets.isEmpty()) {
                Card(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("Add a secret with +, or import your existing secret-list file. " +
                        "This vault is stored inside Install Lock; the separate Delayed Secret app keeps its own data.",
                        Modifier.padding(16.dp))
                }
            }
            if (!overlayGranted) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Overlay permission needed to show secrets as a floating window.")
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = { requestOverlayPermission(context) }) {
                            Text("Grant permission")
                        }
                    }
                }
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
                    .padding(16.dp)
            ) {
                items(visibleSecrets, key = { it.id }) { secret ->
                val linkedRequest = activeLinks[secret.id]
                val timer = if (linkedRequest != null) linkedRequests.timer(secret) else timers[secret.id]!!
                    timer.delayMillis = secret.delayMinutes?.let { it * 60_000L } ?: delayMs
                    timer.visibleMillis = visibleMs
                @Suppress("UNUSED_EXPRESSION") tick
                    val previousState = lastKnownState[secret.id]
                    val info = timer.currentState(claim = linkedRequest == null)
                    if (linkedRequest == null && previousState == RevealState.COUNTING_DOWN && info.state == RevealState.REVEALED) {
                        showSecretReadyNotification(context, secret.name, secret.id.hashCode())

                        if (hasOverlayPermission(context)) {
                            val serviceIntent = OverlayService.secretIntent(context, secret.id, entryOnly = false)
                            context.startForegroundService(serviceIntent)
                        }
                    }
                    lastKnownState[secret.id] = info.state

                Card(modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                secret.name,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = { secretPendingDelete = secret },
                                modifier = Modifier.widthIn(min = 72.dp)
                            ) {
                                Text("Delete", maxLines = 1, softWrap = false)
                            }
                        }
                        val effectiveDelayMin = secret.delayMinutes ?: (delayMs / 60_000L).toInt()
                        Text(
                            "Delay: $effectiveDelayMin min" + if (secret.delayMinutes == null) " (default)" else "",
                            style = MaterialTheme.typography.bodySmall
                        )
                        TextButton(onClick = { secretEditingDelay = secret }) {
                            Text("Change delay")
                        }
                        secret.pendingDelay?.let { pending ->
                            val target = pending.delayMinutes?.let { "$it min" } ?: "default delay"
                            Text("Pending: $target")
                            val remaining = pending.effectiveAtElapsedMs - android.os.SystemClock.elapsedRealtime()
                            Text("Applies in ${formatDuration(remaining)}", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = {
                                secretStore.cancelPendingDelay(secret.id)
                                secrets = secretStore.getAll()
                            }) { Text("Cancel delay change") }
                        }
                        Spacer(modifier = Modifier.height(8.dp))

                        when (info.state) {
                            RevealState.HARD_LOCKED -> {
                                val remainingMs = info.hardLockUntilElapsedMs - android.os.SystemClock.elapsedRealtime()
                                Text("Locked — unavailable for ${formatDuration(remainingMs)}")
                            }
                            RevealState.LOCKED -> {
                                Row {
                                    Button(onClick = { timer.startCountdown() }) {
                                        Text("Reveal")
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    OutlinedButton(onClick = { timer.hardLock(HARD_LOCK_MS) }) {
                                        Text("Lock 1h")
                                    }
                                }
                            }
                            RevealState.COUNTING_DOWN -> {
                                val remainingMs = info.revealAtElapsedMs - android.os.SystemClock.elapsedRealtime()
                                Text("Unlocking in ${formatDuration(remainingMs)}")
                                Spacer(modifier = Modifier.height(4.dp))
                                Row {
                                    OutlinedButton(onClick = { timer.cancel() }) {
                                        Text("Cancel")
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    OutlinedButton(onClick = { timer.extendCountdown(EXTEND_MS) }) {
                                        Text("+5 min")
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    OutlinedButton(onClick = { timer.hardLock(HARD_LOCK_MS) }) {
                                        Text("Lock 1h")
                                    }
                                }
                            }
                            RevealState.REVEALED -> {
                                if (linkedRequest != null) {
                                    Text("Ready for Install Lock. Confirmation expires automatically.")
                                    Button(onClick = { context.startActivity(linkedRequests.openIntent(context, linkedRequest.id)) }) {
                                        Text("Unlock in Install Lock?")
                                    }
                                    OutlinedButton(onClick = {
                                        android.widget.Toast.makeText(context, OverlayService.openForRequest(context, linkedRequest.id), android.widget.Toast.LENGTH_LONG).show()
                                    }) { Text("Floating password") }
                                } else {
                                val remainingMs = info.hideAtElapsedMs - android.os.SystemClock.elapsedRealtime()
                                Text("SECRET: ${secret.value}", style = MaterialTheme.typography.bodyLarge)
                                Text("Hides in ${formatDuration(remainingMs)}")
                                Spacer(modifier = Modifier.height(4.dp))
                                OutlinedButton(onClick = {
                                    android.widget.Toast.makeText(context, OverlayService.openForSecret(context, secret.id), android.widget.Toast.LENGTH_LONG).show()
                                }) { Text("Floating password") }
                                Row {
                                    OutlinedButton(onClick = {
                                        SecretClipboard.copyWithOptions(context, secret.value)
                                    }) {
                                        Text("Copy")
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    OutlinedButton(onClick = { timer.closeEarly() }) {
                                        Text("Close")
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    OutlinedButton(onClick = { timer.hardLock(HARD_LOCK_MS) }) {
                                        Text("Lock 1h")
                                    }
                                }
                                }
                            }
                        }
                    }
                }
            }
        }
    }}

    secretEditingDelay?.let { secret ->
        EditSecretDelayDialog(
            secret = secret,
            defaultMinutes = (delayMs / 60_000L).toInt(),
            onDismiss = { secretEditingDelay = null },
            onSave = { minutes ->
                settingsStore.reconcilePending()
                secretStore.requestDelayChange(secret.id, minutes, settingsStore.getDelayMinutes())
                secrets = secretStore.getAll()
                secretEditingDelay = null
            }
        )
    }

    if (showAddDialog) {
        AddSecretDialog(
            onDismiss = { showAddDialog = false },
            onSave = { name, value, delayMinutes ->
                secretStore.add(name, value, delayMinutes)
                secrets = secretStore.getAll()
                showAddDialog = false
            }
        )
    }

    secretPendingDelete?.let { secret ->
        AlertDialog(
            onDismissRequest = { secretPendingDelete = null },
            title = { Text("Delete \"${secret.name}\"?") },
            text = { Text("Are you sure? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    timers[secret.id]?.cancel()   // clears any active countdown/reveal state too
                    timerStore.clear(secret.id)
                    secretStore.delete(secret.id)
                    timers.remove(secret.id)
                    lastKnownState.remove(secret.id)
                    secrets = secretStore.getAll()
                    secretPendingDelete = null
                }) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { secretPendingDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes > 0) "%d:%02d".format(minutes, seconds) else "${seconds}s"
}

@Composable
fun EditSecretDelayDialog(
    secret: Secret,
    defaultMinutes: Int,
    onDismiss: () -> Unit,
    onSave: (Int?) -> Unit
) {
    var delayText by remember(secret.id) {
        mutableStateOf((if (secret.pendingDelay != null) secret.pendingDelay.delayMinutes else secret.delayMinutes)?.toString() ?: "")
    }
    val valid = delayText.isBlank() || (delayText.toIntOrNull()?.let { it >= 1 } == true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change delay for ${secret.name}") },
        text = {
            Column {
                OutlinedTextField(
                    value = delayText,
                    onValueChange = { delayText = it.filter { c -> c.isDigit() } },
                    label = { Text("Delay in minutes") },
                    singleLine = true,
                    isError = !valid,
                    supportingText = {
                        Text(if (valid) "Leave blank to use the default ($defaultMinutes min)." else "Enter a whole number of at least 1 minute.")
                    }
                )
                Spacer(Modifier.height(8.dp))
                Text("Increases apply immediately. Decreases take at least 25 minutes after saving. Saving another decrease restarts the wait; restarting the device also restarts it.")
                Spacer(Modifier.height(8.dp))
                Text("Changes apply to future reveals. A countdown already running keeps its scheduled unlock time.")
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onSave(delayText.toIntOrNull()) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun AddSecretDialog(onDismiss: () -> Unit, onSave: (String, String, Int?) -> Unit) {
    var name by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var delayText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Secret") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") })
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text("Secret text") })
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = delayText,
                    onValueChange = { delayText = it.filter { c -> c.isDigit() } },
                    label = { Text("Delay in minutes (blank = default)") }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank() && value.isNotBlank()) {
                    val delay = delayText.toIntOrNull()?.coerceAtLeast(1)
                    onSave(name, value, delay)
                }
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
