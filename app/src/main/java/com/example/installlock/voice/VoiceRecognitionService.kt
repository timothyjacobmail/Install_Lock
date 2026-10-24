package com.example.installlock.voice

import android.content.Intent
import android.media.*
import android.os.*
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.util.concurrent.atomic.AtomicBoolean

/** Android assistant speech-provider adapter. Results are returned only; no commands execute here. */
class VoiceRecognitionService : RecognitionService() {
    private val running = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var cancel = false
    @Volatile private var stop = false
    @Volatile private var audio: AudioRecord? = null
    @Suppress("MissingPermission")
    override fun onStartListening(intent: Intent?, callback: Callback) {
        if (VoiceListenerService.active || !running.compareAndSet(false, true)) { callback.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY); return }
        cancel = false; stop = false
        Thread {
            try {
                Model(VoiceModel.prepare(this)).use { model ->
                    Recognizer(model, 16000f).use { decoder ->
                        val size = maxOf(6400, AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT))
                        val mic = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
                        audio = mic
                        check(mic.state == AudioRecord.STATE_INITIALIZED)
                        mic.startRecording()
                        main.post { if (!cancel) callback.readyForSpeech(Bundle()) }
                        val data = ShortArray(1600)
                        val until = SystemClock.elapsedRealtime() + 15000
                        var result = ""
                        while (!cancel && !stop && SystemClock.elapsedRealtime() < until) {
                            val n = mic.read(data, 0, data.size); if (n <= 0) break
                            val final = decoder.acceptWaveForm(data, n)
                            val text = JSONObject(if (final) decoder.result else decoder.partialResult).optString(if (final) "text" else "partial")
                            if (final && text.isNotBlank()) { result = text; break }
                            if (text.isNotBlank()) main.post { if (!cancel) callback.partialResults(Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text)) }) }
                        }
                        if (result.isBlank()) result = JSONObject(decoder.finalResult).optString("text")
                        val text = result
                        main.post {
                            if (!cancel) {
                                callback.endOfSpeech()
                                if (text.isBlank()) callback.error(SpeechRecognizer.ERROR_NO_MATCH)
                                else callback.results(Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text)) })
                            }
                        }
                    }
                }
            } catch (_: Exception) { main.post { if (!cancel) callback.error(SpeechRecognizer.ERROR_AUDIO) } }
            finally { try { audio?.stop() } catch (_: Exception) {}; audio?.release(); audio = null; running.set(false) }
        }.start()
    }
    override fun onStopListening(callback: Callback) { stop = true }
    override fun onCancel(callback: Callback) { cancel = true; stop = true; try { audio?.stop() } catch (_: Exception) {} }
    override fun onDestroy() { cancel = true; stop = true; try { audio?.stop() } catch (_: Exception) {}; super.onDestroy() }
}
