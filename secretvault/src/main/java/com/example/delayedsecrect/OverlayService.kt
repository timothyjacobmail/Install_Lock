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

class OverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private lateinit var windowManager: WindowManager
    private var overlayView: ComposeView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var layoutParams: WindowManager.LayoutParams? = null

    companion object {
        const val EXTRA_SECRET_NAME = "secret_name"
        const val EXTRA_SECRET_VALUE = "secret_value"
        const val EXTRA_SECRET_ID = "secret_id"
        const val EXTRA_HIDE_AT_ELAPSED_MS = "hide_at_elapsed_ms"
        const val ACTION_STOP = "action_stop"
    }

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            removeOverlay()
            stopSelf()
            return START_NOT_STICKY
        }

        // IDs 1 and 2 belong to Install Lock's policy and VPN foreground services.
        startForeground(7001, buildForegroundNotification())

        val name = intent?.getStringExtra(EXTRA_SECRET_NAME) ?: ""
        val value = intent?.getStringExtra(EXTRA_SECRET_VALUE) ?: ""
        val secretId = intent?.getStringExtra(EXTRA_SECRET_ID) ?: ""
        val hideAtElapsedMs = intent?.getLongExtra(EXTRA_HIDE_AT_ELAPSED_MS, 0L) ?: 0L

        showOverlay(name, value, secretId, hideAtElapsedMs)
        return START_NOT_STICKY
    }

    private fun buildForegroundNotification(): Notification {
        return NotificationCompatBuilderHelper.build(this)
    }

    private fun showOverlay(name: String, value: String, secretId: String, hideAtElapsedMs: Long) {
        removeOverlay() // in case one is already showing

        lifecycleRegistry.currentState = Lifecycle.State.STARTED

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 100
        }
        layoutParams = params


        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@OverlayService)
            setViewTreeSavedStateRegistryOwner(this@OverlayService)
            setContent {
                OverlayContent(
                    name = name,
                    value = value,
                    hideAtElapsedMs = hideAtElapsedMs,
                    onClose = {
                        RevealTimer(secretId, DEFAULT_DELAY_MS, DEFAULT_VISIBLE_MS, TimerStore(this@OverlayService)).closeEarly()
                        removeOverlay()
                        stopSelf()
                    },
                    onExpired = {
                        removeOverlay()
                        stopSelf()
                    },
                    onDrag = ::onOverlayDragged,
                    onCopy = {
                        copySecretToClipboard(this@OverlayService, value)
                        handler.postDelayed({
                            clearClipboardIfMatches(this@OverlayService, value)
                        }, CLIPBOARD_CLEAR_DELAY_MS)
                    },
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
    value: String,
    hideAtElapsedMs: Long,
    onClose: () -> Unit,
    onExpired: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onCopy: () -> Unit
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
            .widthIn(min = 260.dp)
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
            Text(name, color = Color.White, style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                value,
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier
                    .background(Color(0xFF000000))
                    .padding(8.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text("Hides in ${formatDuration(remainingMs)}", color = Color.LightGray)
            Spacer(modifier = Modifier.height(8.dp))
            Row {
                Button(onClick = onCopy) {
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
