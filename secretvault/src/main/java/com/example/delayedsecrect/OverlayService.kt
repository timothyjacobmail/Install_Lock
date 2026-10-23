package com.example.delayedsecrect

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.*
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import android.widget.Toast
import androidx.compose.ui.text.style.TextOverflow

class OverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private lateinit var windowManager: WindowManager
    private var overlayView: ComposeView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    companion object {
        const val EXTRA_SECRET_ID = "secret_id"
        private const val EXTRA_REQUEST_ID = "inline_request_id"
        private const val EXTRA_ENTRY_ONLY = "entry_only"
        const val ACTION_STOP = "action_stop"

        fun secretIntent(context: Context, secretId: String, entryOnly: Boolean = true) =
            Intent(context, OverlayService::class.java).putExtra(EXTRA_SECRET_ID, secretId)
                .putExtra(EXTRA_ENTRY_ONLY, entryOnly)

        fun openForRequest(context: Context, requestId: String): String = open(context,
            Intent(context, OverlayService::class.java).putExtra(EXTRA_REQUEST_ID, requestId))

        fun openForSecret(context: Context, secretId: String): String = open(context, secretIntent(context, secretId))

        private fun open(context: Context, intent: Intent): String {
            if (!PasswordFieldEntry.available) return "Turn on Install Lock accessibility, then try Floating password again."
            if (!hasOverlayPermission(context)) {
                try { requestOverlayPermission(context) } catch (_: RuntimeException) {
                    return "Open Android settings and allow Install Lock to display over other apps, then try again."
                }
                return "Allow Install Lock to display over other apps, then tap Floating password again."
            }
            return try {
                context.startForegroundService(intent)
                "Open the target app, tap its password field, then tap Enter password in the floating window."
            } catch (_: RuntimeException) { "Could not open the floating password. Check display-over-other-apps permission." }
        }
    }

    private data class Session(val name: String, val expiresAt: Long, val displayValue: String?,
                               val read: () -> InlineSecretClaim?, val close: () -> Unit)

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            removeOverlay()
            stopSelf()
            return START_NOT_STICKY
        }

        // IDs 1 and 2 belong to Install Lock's policy and VPN foreground services.
        startForeground(7001, buildForegroundNotification())

        try {
            val requestId = intent?.getStringExtra(EXTRA_REQUEST_ID)
            val session = if (requestId != null) requestSession(requestId)
                else secretSession(intent?.getStringExtra(EXTRA_SECRET_ID).orEmpty(),
                    intent?.getBooleanExtra(EXTRA_ENTRY_ONLY, true) != false)
            if (session == null || !hasOverlayPermission(this)) {
                Toast.makeText(this, "Password unavailable or expired. Request it again in Install Lock.", Toast.LENGTH_LONG).show()
                removeOverlay()
                stopSelf()
            } else showOverlay(session)
        } catch (_: RuntimeException) {
            removeOverlay()
            stopSelf()
            Toast.makeText(this, "Could not open the floating password. Check display-over-other-apps permission.", Toast.LENGTH_LONG).show()
        }
        return START_NOT_STICKY
    }

    private fun requestSession(id: String): Session? {
        val requests = InstallLockRequests(this)
        val request = requests.get(id) ?: return null
        val (secret, _) = requests.availability(request) ?: return null
        val expiresAt = requests.claimInline(id)?.expiresElapsed ?: return null
        fun sameRequest() = requests.get(id)?.let { it.cycleId == request.cycleId && it.secretId == request.secretId } == true
        return Session(secret.name, expiresAt, null, read = {
            if (SystemClock.elapsedRealtime() >= expiresAt || !sameRequest()) null else requests.claimInline(id)
        }, close = { if (sameRequest()) requests.completeInline(id) })
    }

    private fun secretSession(id: String, entryOnly: Boolean): Session? {
        val secrets = SecretStore(this)
        val timers = TimerStore(this)
        val settings = SettingsStore(this)
        fun timer(secret: Secret) = RevealTimer(id,
            (secret.delayMinutes ?: settings.getDelayMinutes()) * 60_000L,
            settings.getVisibleSeconds() * 1000L, timers)
        val secret = secrets.getAll().firstOrNull { it.id == id } ?: return null
        val initial = timer(secret).currentState()
        if (initial.state != RevealState.REVEALED || initial.hideAtElapsedMs <= SystemClock.elapsedRealtime()) return null
        val cycle = timers.get(id)?.cycleId ?: return null
        return Session(secret.name, initial.hideAtElapsedMs, if (entryOnly) null else secret.value, read = {
            val currentSecret = secrets.getAll().firstOrNull { it.id == id }
            if (currentSecret == null || timers.get(id)?.cycleId != cycle || SystemClock.elapsedRealtime() >= initial.hideAtElapsedMs) null
            else {
                val current = timer(currentSecret).currentState(claim = false)
                if (current.state != RevealState.REVEALED || current.hideAtElapsedMs <= SystemClock.elapsedRealtime()) null
                else InlineSecretClaim(currentSecret.value, minOf(initial.hideAtElapsedMs, current.hideAtElapsedMs))
            }
        }, close = { if (timers.get(id)?.cycleId == cycle) timer(secret).closeEarly() })
    }

    private fun buildForegroundNotification(): Notification {
        return NotificationCompatBuilderHelper.build(this)
    }

    private fun showOverlay(session: Session) {
        removeOverlay() // in case one is already showing

        lifecycleRegistry.currentState = Lifecycle.State.STARTED

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 100
        }
        layoutParams = params


        val feedback = mutableStateOf("")
        fun finishSession() { removeOverlay(); stopSelf() }
        fun read(): InlineSecretClaim? {
            val grant = try { session.read() } catch (_: Exception) { null }
            if (grant == null || grant.expiresElapsed <= SystemClock.elapsedRealtime()) {
                Toast.makeText(this, "This password expired or was locked. Request it again.", Toast.LENGTH_LONG).show()
                finishSession()
                return null
            }
            return grant
        }
        val view = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setViewTreeLifecycleOwner(this@OverlayService)
            setViewTreeSavedStateRegistryOwner(this@OverlayService)
            setContent {
                OverlayContent(
                    name = session.name,
                    value = session.displayValue,
                    hideAtElapsedMs = session.expiresAt,
                    feedback = feedback.value,
                    onClose = {
                        session.close()
                        finishSession()
                    },
                    onExpired = {
                        finishSession()
                    },
                    onDrag = ::onOverlayDragged,
                    onCopy = {
                        read()?.let { SecretClipboard.copyWithOptions(this@OverlayService, it.value) }
                    },
                    onEnter = {
                        read()?.let { grant ->
                            val result = PasswordFieldEntry.enter(grant.value)
                            if (result.success) {
                                session.close()
                                finishSession()
                                Toast.makeText(this@OverlayService, result.message, Toast.LENGTH_LONG).show()
                            } else feedback.value = result.message
                        }
                    }
                )
            }
        }

        overlayView = view
        windowManager.addView(view, params)
    }
    private fun onOverlayDragged(dx: Float, dy: Float) {
        val view = overlayView ?: return
        val params = layoutParams ?: return
        params.x += dx.toInt()
        params.y += dy.toInt()
        windowManager.updateViewLayout(view, params)
    }

    private fun removeOverlay() {
        overlayView?.let {
            try { windowManager.removeView(it) } catch (e: Exception) { /* already removed */ }
            it.disposeComposition()
        }
        overlayView = null
    }

    override fun onDestroy() {
        removeOverlay()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

@Composable
fun OverlayContent(
    name: String,
    value: String?,
    hideAtElapsedMs: Long,
    onClose: () -> Unit,
    onExpired: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onCopy: () -> Unit,
    onEnter: () -> Unit,
    feedback: String
) {
    var remainingMs by remember { mutableStateOf(hideAtElapsedMs - SystemClock.elapsedRealtime()) }

    LaunchedEffect(Unit) {
        while (true) {
            remainingMs = hideAtElapsedMs - SystemClock.elapsedRealtime()
            if (remainingMs <= 0) {
                onExpired()
                break
            }
            kotlinx.coroutines.delay(500)
        }
    }

    Surface(
        modifier = Modifier
            .padding(16.dp)
            .widthIn(min = 260.dp, max = 320.dp)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.x, dragAmount.y)
                }
            },
        color = Color(0xFF222222),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        shadowElevation = 8.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(name, color = Color.White, style = MaterialTheme.typography.labelLarge,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(modifier = Modifier.height(4.dp))
            if (value != null) Text(
                value,
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier
                    .background(Color(0xFF000000))
                    .padding(8.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text("Hides in ${formatDuration(remainingMs)}", color = Color.LightGray)
            Text("Tap the password field in the other app, then Enter password. Nothing is copied or submitted.",
                color = Color.White, style = MaterialTheme.typography.bodySmall)
            if (feedback.isNotEmpty()) Text(feedback, color = Color(0xFFFFB4AB), style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onEnter, modifier = Modifier.fillMaxWidth()) { Text("Enter password") }
            Row {
                if (value != null) Button(onClick = onCopy) {
                    Text("Copy")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = onClose) {
                    Text("Close")
                }
            }
        }
    }
}
