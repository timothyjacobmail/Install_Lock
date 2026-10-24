package com.example.installlock.voice

import android.app.PendingIntent
import android.appwidget.*
import android.content.*
import android.os.*
import android.media.AudioAttributes
import android.service.quicksettings.TileService
import android.service.voice.*
import android.view.KeyEvent
import android.widget.RemoteViews
import com.example.installlock.R

object VoiceShakeAction {
    fun intent(c: Context): Intent = Intent(c, VoiceTasksActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .putExtra(if (VoiceStore.prefs(c).getBoolean("shake_memo", true)) "memo" else "capture", true)
}

object VoiceFeedback {
    fun ready(c: Context) {
        val vibrator = c.getSystemService(Vibrator::class.java) ?: return
        if (!vibrator.hasVibrator()) return
        try {
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 160, 80, 160), -1),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        } catch (e: RuntimeException) { android.util.Log.w("VoiceReminders", "Readiness vibration unavailable", e) }
    }
}

class VoiceTile : TileService() {
    override fun onClick() {
        super.onClick()
        val intent = Intent(this, VoiceTasksActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("capture", true)
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 8103, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        else @Suppress("DEPRECATION") startActivityAndCollapse(intent)
    }
}

class VoiceWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) { ids.forEach { manager.updateAppWidget(it, views(context)) } }
    companion object {
        private fun views(c: Context): RemoteViews {
            val next = VoiceStore.all(c).filter { !it.done && !it.archived }.minByOrNull { it.trigger ?: Long.MAX_VALUE }
            return RemoteViews(c.packageName, R.layout.voice_widget).apply {
                setTextViewText(R.id.voice_widget_title, "Voice Tasks · Dictate")
                setTextViewText(R.id.voice_widget_next, if (VoiceStore.prefs(c).getBoolean("private", true)) "Tap to capture a task offline" else next?.title ?: "No upcoming tasks")
                setOnClickPendingIntent(R.id.voice_widget_root, PendingIntent.getActivity(c, 8104, Intent(c, VoiceTasksActivity::class.java).putExtra("capture", true), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }
        }
        fun refresh(c: Context) {
            val manager = AppWidgetManager.getInstance(c)
            val ids = manager.getAppWidgetIds(ComponentName(c, VoiceWidget::class.java))
            if (ids.isNotEmpty()) manager.updateAppWidget(ids, views(c))
        }
    }
}

class VoiceAssistant : VoiceInteractionService()
class VoiceSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = object : VoiceInteractionSession(this) {
        override fun onShow(args: Bundle?, showFlags: Int) {
            super.onShow(args, showFlags)
            startAssistantActivity(Intent(this@VoiceSessionService, VoiceTasksActivity::class.java).putExtra("capture", true))
            hide()
        }
    }
}

/** Pass through the complete key stream; the long hold also changes volume normally. */
class VoiceVolumeTrigger(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private var held = false
    private val fire = Runnable {
        if (held && VoiceStore.prefs(context).getBoolean("volume", false)) {
            try { context.startActivity(Intent(context, VoiceTasksActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("capture", true)) }
            catch (_: RuntimeException) {}
        }
    }
    fun key(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && VoiceStore.prefs(context).getBoolean("volume", false)) { held = true; handler.postDelayed(fire, 800) }
        if (event.action == KeyEvent.ACTION_UP) { held = false; handler.removeCallbacks(fire) }
        return false
    }
    fun close() { held = false; handler.removeCallbacksAndMessages(null) }
}
