package com.example.installlock.voice

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale

/** Prompts with an installed offline voice, then captures only a reminder duration in Vosk. */
class VoiceMemoTimingDialog(
    private val activity: Activity,
    private val onSelected: (Long?) -> Unit,
    private val onCancelled: () -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var finished = false
    private var listening = false
    private val answer = EditText(activity).apply {
        hint = "When? e.g. 5 mins or 5 hours"
        setSingleLine(true)
    }
    private val state = TextView(activity).apply { text = "Preparing the spoken question…" }
    private val dialog = MaterialAlertDialogBuilder(activity)
        .setTitle("When should I remind you?")
        .setView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(32, 12, 32, 12)
            addView(TextView(activity).apply { text = "Say five minutes or five hours after the vibration. You can also type a duration." })
            addView(state); addView(answer)
            addView(Button(activity).apply {
                text = "Speak again"; isAllCaps = false
                setOnClickListener { listen() }
            })
        })
        .setPositiveButton("Use time", null)
        .setNeutralButton("No reminder") { _, _ -> select(null) }
        .setNegativeButton("Cancel memo") { _, _ -> cancel() }
        .create()

    fun show() {
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener { accept(answer.text.toString()) }
        }
        dialog.setOnDismissListener { if (!finished) cancel() }
        dialog.show()
        // Missing or unresponsive TTS must still leave the visual question and offline capture usable.
        main.postDelayed({ if (!finished && !listening) listen() }, 8000)
        try {
            tts = TextToSpeech(activity) { status -> main.post {
                if (finished || listening) return@post
                val engine = tts
                val voice = if (status == TextToSpeech.SUCCESS) engine?.voices?.firstOrNull {
                    !it.isNetworkConnectionRequired && it.locale.language == Locale.ENGLISH.language &&
                        TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty()
                } else null
                if (voice == null || engine == null) {
                    state.text = "No offline speaking voice installed. Read the question, then answer."
                    main.postDelayed({ listen() }, 500)
                } else {
                    engine.voice = voice
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?) {}
                        override fun onDone(id: String?) { main.postDelayed({ listen() }, 300) }
                        @Deprecated("platform callback") override fun onError(id: String?) { main.post { listen() } }
                    })
                    state.text = "Asking the question…"
                    android.util.Log.i("VoiceMemoTiming", "Offline prompt voice: ${voice.name}")
                    if (engine.speak("When should I remind you? Say five minutes or five hours.", TextToSpeech.QUEUE_FLUSH, Bundle(), "memo-time") == TextToSpeech.ERROR) listen()
                }
            } }
        } catch (_: RuntimeException) { listen() }
    }

    private fun stopPrompt() { tts?.stop(); tts?.shutdown(); tts = null }

    private fun listen() {
        if (finished || listening || activity.isFinishing || activity.isDestroyed) return
        if (VoiceListenerService.active || VoiceListenerService.workerRunning) {
            state.text = "Waiting for the previous capture to finish…"
            main.postDelayed({ listen() }, 100)
            return
        }
        main.removeCallbacksAndMessages(null)
        stopPrompt()
        try {
            listening = true
            state.text = "Starting offline listening…"
            VoiceListenerService.start(activity, duration = true)
        } catch (e: RuntimeException) {
            listening = false
            state.text = "Could not start listening. Type a duration or tap Speak again."
        }
    }

    fun onSpeech(kind: String, text: String) {
        if (finished) return
        when (kind) {
            "partial" -> answer.setText(text)
            "duration" -> { listening = false; answer.setText(text); accept(text) }
            "error" -> { listening = false; state.text = "$text Tap Speak again or type a duration." }
            "status" -> state.text = text
        }
    }

    private fun accept(text: String) {
        if (finished) return
        try {
            val resolved = VoiceTimePractice.resolve(activity, text)
            val due = System.currentTimeMillis() + resolved.delay
            if (resolved.correction != null) {
                listening = false; VoiceListenerService.pause(activity)
                MaterialAlertDialogBuilder(activity).setTitle("Use learned time correction?")
                    .setMessage(resolved.correction).setPositiveButton("Use this time") { _, _ -> select(System.currentTimeMillis() + resolved.delay) }
                    .setNegativeButton("Try again") { _, _ -> listen() }.show()
            } else select(due)
        } catch (e: IllegalArgumentException) {
            state.text = "${e.message} No reminder has been saved."
        }
    }

    private fun select(due: Long?) {
        if (finished) return
        finished = true; cleanup(); dialog.dismiss()
        // Let the timing window finish dismissing before the review window takes focus.
        main.post { if (!activity.isFinishing && !activity.isDestroyed) onSelected(due) else onCancelled() }
    }

    fun cancel() {
        if (finished) return
        finished = true; cleanup(); dialog.dismiss(); onCancelled()
    }

    fun pause() {
        if (finished) return
        main.removeCallbacksAndMessages(null); stopPrompt()
        VoiceListenerService.pause(activity); listening = false
        state.text = "Listening paused. Tap Speak again or type a duration."
    }

    private fun cleanup() {
        main.removeCallbacksAndMessages(null); stopPrompt()
        VoiceListenerService.pause(activity)
    }
}
