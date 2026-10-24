package com.example.installlock.voice

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.UUID

class VoiceTimePracticeActivity : AppCompatActivity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var progress: TextView
    private lateinit var phrase: TextView
    private lateinit var state: TextView
    private lateinit var record: Button
    private lateinit var confirm: Button
    private lateinit var skip: Button
    private var step = 0
    private var confirmed = 0
    private var heard = ""
    private var captureId = ""
    private var listening = false
    private var closing = false
    private var resumeActivation = false
    private val events = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (!listening || i.getStringExtra("capture_id") != captureId || step >= VoiceTimePractice.session.size) return
            val text = i.getStringExtra("text").orEmpty()
            when (i.getStringExtra("kind")) {
                "status" -> state.text = text
                "partial" -> state.text = "Hearing: $text"
                "error" -> { listening = false; record.isEnabled = true; state.text = "$text Tap Record phrase to try again." }
                "practice" -> {
                    listening = false; heard = text; record.isEnabled = true
                    val sample = VoiceTimeSample(heard, VoiceTimePractice.session[step])
                    try {
                        VoiceTimePractice.validate(sample)
                        confirm.isEnabled = true
                        val correct = runCatching { VoiceCommand.reminderDelayMillis(heard) }.getOrNull() == VoiceCommand.reminderDelayMillis(sample.intended)
                        state.text = "Heard: ‘$heard’\n" + if (correct) "Recognized correctly. Confirm this sample to continue." else "Confirm only if you said ‘${sample.intended}’. This will save a local phrase correction."
                    } catch (e: IllegalArgumentException) {
                        confirm.isEnabled = false; state.text = "Heard: ‘$heard’\n${e.message}"
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        resumeActivation = VoiceStore.prefs(this).getBoolean("running", false) && VoiceListenerService.active
        VoiceListenerService.pause(this)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 20, 28, 20) }
        val scroll = ScrollView(this).apply { addView(column) }
        setContentView(scroll)
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        fun label(text: String, size: Float = 17f) = TextView(this).apply { this.text = text; textSize = size; setPadding(0, 12, 0, 12); column.addView(this) }
        fun button(text: String, action: () -> Unit) = Button(this).apply { this.text = text; isAllCaps = false; setOnClickListener { action() }; column.addView(this) }
        label("Practice my time phrases", 26f)
        label("Six short attempts in your normal voice. Tap Record phrase, wait for the vibration, then say the displayed time. Confirm each result before it is learned.\n\nEverything stays on this phone. Practice saves text corrections, not recordings; it does not retrain the speech model.")
        progress = label("")
        phrase = label("", 30f)
        state = label("")
        record = button("Record phrase") { recordPhrase() }
        confirm = button("Confirm sample") { confirmSample() }
        skip = button("Skip phrase") { next() }
        button("Finish session") { finishSession() }
        button("Reset learned time phrases") {
            MaterialAlertDialogBuilder(this).setTitle("Reset time-phrase practice?")
                .setMessage("Remove your confirmed time samples and phrase corrections?")
                .setPositiveButton("Reset") { _, _ -> VoiceTimePractice.clear(this); state.text = "Learned time phrases reset." }
                .setNegativeButton("Cancel", null).show()
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() { finishSession() } })
        render()
    }

    override fun onStart() {
        super.onStart()
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(events, IntentFilter(VoiceListenerService.EVENT), Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(events, IntentFilter(VoiceListenerService.EVENT))
    }

    override fun onStop() {
        if (!closing) {
            main.removeCallbacksAndMessages(null); VoiceListenerService.pause(this)
            captureId = ""; listening = false; record.isEnabled = true
            state.text = "Practice paused. Tap Record phrase to continue."
        }
        unregisterReceiver(events); super.onStop()
    }

    private fun render() {
        confirm.isEnabled = false; heard = ""; captureId = ""; listening = false
        val done = step >= VoiceTimePractice.session.size
        record.visibility = if (done) View.GONE else View.VISIBLE
        confirm.visibility = record.visibility; skip.visibility = record.visibility
        record.isEnabled = true
        progress.text = if (done) "Session complete · $confirmed samples confirmed" else "Phrase ${step + 1} of ${VoiceTimePractice.session.size} · $confirmed confirmed"
        phrase.text = if (done) "Ready to try your reminders" else "Say: ${VoiceTimePractice.session[step]}"
        state.text = if (done) "Confirmed samples are saved. Tap Finish session to return to your memos." else "Tap Record phrase. No reminder will be created during practice."
    }

    private fun recordPhrase() {
        if (closing || listening || step >= VoiceTimePractice.session.size) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 51); return
        }
        VoiceListenerService.pause(this)
        listening = true; record.isEnabled = false; confirm.isEnabled = false; heard = ""
        captureId = UUID.randomUUID().toString()
        val id = captureId
        state.text = "Preparing offline practice…"
        val deadline = SystemClock.elapsedRealtime() + 8000
        fun startWhenReleased() {
            if (closing || !listening || captureId != id || isFinishing || isDestroyed) return
            if (VoiceListenerService.active || VoiceListenerService.workerRunning) {
                if (SystemClock.elapsedRealtime() < deadline) main.postDelayed({ startWhenReleased() }, 100)
                else { listening = false; record.isEnabled = true; state.text = "Microphone busy. Tap Record phrase to retry." }
                return
            }
            try { startForegroundService(Intent(this, VoiceListenerService::class.java).setAction(VoiceListenerService.CAPTURE_PRACTICE).putExtra("capture_id", id)) }
            catch (e: RuntimeException) { listening = false; record.isEnabled = true; state.text = "Could not start practice: ${e.message}" }
        }
        main.post { startWhenReleased() }
    }

    private fun confirmSample() {
        if (heard.isBlank() || step >= VoiceTimePractice.session.size) return
        try { VoiceTimePractice.save(this, VoiceTimeSample(heard, VoiceTimePractice.session[step])); confirmed++; next() }
        catch (e: Exception) { state.text = e.message ?: "Could not save this sample." }
    }

    private fun next() {
        VoiceListenerService.pause(this); step++; render()
    }

    private fun finishSession() {
        if (closing) return
        closing = true; captureId = ""; listening = false
        main.removeCallbacksAndMessages(null); VoiceListenerService.pause(this)
        state.text = "Finishing practice…"
        val deadline = SystemClock.elapsedRealtime() + 8000
        fun finishWhenReleased() {
            if (isDestroyed) return
            if ((VoiceListenerService.active || VoiceListenerService.workerRunning) && SystemClock.elapsedRealtime() < deadline) {
                main.postDelayed({ finishWhenReleased() }, 100); return
            }
            val p = VoiceStore.prefs(this)
            if (resumeActivation && !VoiceListenerService.workerRunning && (p.getBoolean("shake", false) || p.getBoolean("wake", false))) {
                try { p.edit().putBoolean("running", true).apply(); VoiceListenerService.start(this) }
                catch (_: RuntimeException) { p.edit().putBoolean("running", false).apply() }
            }
            finish()
        }
        main.post { finishWhenReleased() }
    }

    @Deprecated("platform callback")
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == 51 && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) recordPhrase()
    }

    override fun onDestroy() { main.removeCallbacksAndMessages(null); if (!closing) VoiceListenerService.pause(this); super.onDestroy() }
}
