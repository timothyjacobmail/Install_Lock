package com.example.installlock

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognizerIntent
import android.text.InputType
import android.view.View
import android.view.KeyEvent
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.net.toUri
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

class VerseAlarmActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var rendered = ""
    private var status: TextView? = null
    private var answer: EditText? = null
    private var dictatedText: String? = null
    private var speechId: String? = null
    private var practice: AlarmVerse? = null
    private var previewSound: android.media.Ringtone? = null
    private val speech = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        val current = VerseAlarmStore.read(this).challenge?.id ?: practice?.id
        if (result.resultCode == RESULT_OK && text != null && speechId == current) {
            dictatedText = text
            answer?.setText(text)
        } else if (result.resultCode == RESULT_OK) toast("The challenge changed. Please dictate again.")
        speechId = null
    }
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_SECURE)
        speechId = savedInstanceState?.getString("speechId")
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("speechId", speechId)
        super.onSaveInstanceState(outState)
    }
    override fun onResume() {
        super.onResume()
        VerseAlarmScheduler.sync(this)
        if (VerseAlarmStore.read(this).challenge == null && practice == null) rendered = ""
        handler.removeCallbacks(tick)
        handler.post(tick)
    }
    override fun onPause() {
        handler.removeCallbacks(tick)
        previewSound?.stop()
        super.onPause()
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode in listOf(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_UP,
                KeyEvent.KEYCODE_VOLUME_MUTE) && VerseAlarmStore.read(this).challenge != null) {
            VerseAlarmVolume.sync(this)
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0)
                toast("Alarm volume stays fixed until all three checks are complete.")
            return true
        }
        return super.dispatchKeyEvent(event)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        practice = null
    }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun label(text: String, size: Float = 16f) = UiStyle.label(this, text, size)
    private fun button(text: String, action: () -> Unit) = UiStyle.button(this, text, onClick = action)
    private fun field(hint: String, value: String = "", number: Boolean = false) = EditText(this).apply {
        this.hint = hint
        setText(value)
        inputType = if (number) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
    }
    private fun show(views: List<View>) {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = UiStyle.dp(this@VerseAlarmActivity, 20)
            setPadding(pad, pad, pad, pad)
            views.forEach { addView(it, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 12 }) }
        }
        UiStyle.styleInputs(column)
        val scroll = ScrollView(this).apply { addView(column) }
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }
        setContentView(scroll)
        scroll.requestApplyInsets()
    }
    private fun attempt(action: () -> Unit) {
        try { action(); VerseAlarmScheduler.sync(this); refresh() }
        catch (e: Exception) { toast(e.message ?: "Could not save this change.") }
    }

    private fun refresh() {
        val now = WatchdogStore.clock(this)
        val s = VerseAlarmStore.reconcile(this, now)
        val ch = s.challenge
        if (ch != null) practice = null
        val waiting = ch != null && ch.waiting && !ch.ringing(now)
        val key = if (ch != null) "${ch.id}|$waiting|${ch.quietUsed}" else
            "${s.config}|${s.pending}|${s.verses.hashCode()}|${practice?.id}"
        if (key != rendered) {
            val previousAnswer = if (ch != null && rendered.substringBefore('|') == ch.id) answer?.text?.toString() else null
            rendered = key
            dictatedText = null
            when {
                waiting -> showWaiting(ch!!)
                ch != null -> showChallenge(s, ch)
                practice != null -> showPractice(practice!!)
                else -> showSettings(s)
            }
            if (previousAnswer != null) answer?.setText(previousAnswer)
        }
        status?.text = when {
            ch != null && waiting -> "Next check in ${(ch.remaining(now) + 999) / 1000} seconds. You have completed ${ch.round} of ${VerseAlarmRules.ROUNDS} checks."
            ch != null && !ch.ringing(now) -> "Quiet time: ${(ch.remaining(now) + 999) / 1000} seconds. Finish the verse before the sound resumes."
            ch != null -> "Check ${ch.round + 1} of ${VerseAlarmRules.ROUNDS}. Leaving this screen does not finish the alarm."
            practice != null -> "Practice only: this does not increase your wake-up completion count."
            else -> buildString {
                append(if (s.config.enabled) "Next alarm: ${DateFormat.getDateTimeInstance().format(Date(s.nextAlarm))}" else "Alarm is off.")
                s.pending?.let { pending ->
                    append("\nPending: ${if (pending.enabled) "%02d:%02d".format(pending.hour, pending.minute) else "turn off"}, memory after ${pending.memoryAfter} completions.")
                    append("\nApplies in ${((s.countdown?.remaining(now) ?: 0L) + 59_999) / 60_000} minutes, after any active routine finishes.")
                }
            }
        }
    }

    private fun showWaiting(ch: VerseChallenge) {
        answer = null
        status = label("")
        show(listOf(label("Keep awake", 26f), status!!,
            label("Your next verse will appear when the five-minute interval ends. The alarm will ring even if you leave this screen. Alarm volume stays fixed until all three checks are complete."),
            button("Leave this screen") { finish() }))
    }

    private fun responseField() = object : AppCompatEditText(this) {
        override fun onTextContextMenuItem(id: Int): Boolean =
            if (id == android.R.id.paste || id == android.R.id.pasteAsPlainText) false else super.onTextContextMenuItem(id)
    }.apply {
        hint = "Type the passage, or use Dictate"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        minLines = 4
        isSaveEnabled = false
        filters = arrayOf(android.text.InputFilter.LengthFilter(12_000))
    }

    private fun dictate(id: String, realAlarm: Boolean) {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Recite the passage without its reference")
        try {
            if (realAlarm) {
                VerseAlarmStore.quiet(this, id)
                VerseAlarmScheduler.sync(this)
                refresh()
            }
            speechId = id
            speech.launch(intent)
        } catch (_: ActivityNotFoundException) { toast("Speech recognition is unavailable. Type the passage or use your keyboard microphone.") }
        catch (_: SecurityException) { toast("Unlock your phone to use speech recognition, or type the passage.") }
    }

    private fun showChallenge(s: VerseAlarmState, ch: VerseChallenge) {
        val v = s.verses.first { it.id == ch.verseId }
        status = label("")
        answer = responseField()
        val result = label("")
        show(listOf(label("Verse wake-up", 26f), status!!, label("${v.reference} · ESV", 22f),
            label(if (ch.fromMemory) "From memory — you have completed this passage ${v.successes} times."
                else "Learning — ${v.successes}/${s.config.memoryAfter} completions before memory mode."),
            label(if (ch.fromMemory) "Write or recite the passage from memory. The text stays hidden during this challenge." else v.text),
            answer!!, label("Capitalization and punctuation do not matter. Include the passage only, without verse numbers."),
            button("Dictate") { dictate(ch.id, true) },
            button("Check my verse") {
                val value = answer!!.text.toString()
                attempt {
                    if (VerseAlarmStore.answer(this, ch.id, value, dictatedText == value)) {
                        toast(if (ch.round + 1 == VerseAlarmRules.ROUNDS) "Wake-up routine complete." else "Verse complete. Your next check is in five minutes.")
                    } else {
                        result.text = "That does not match yet. Check the words and their order, then try again."
                        return@attempt
                    }
                }
            },
            button("Quiet for one minute") {
                attempt { VerseAlarmStore.quiet(this, ch.id) }
            }.apply { isEnabled = !ch.quietUsed }, result,
            label("One quiet minute per check, shared with dictation. Alarm volume stays fixed until all three checks are complete.")))
    }

    private fun showPractice(v: AlarmVerse) {
        status = label("")
        answer = responseField()
        val result = label("")
        show(listOf(label("Practice · ${v.reference}", 26f), status!!, label(v.text), answer!!,
            button("Dictate") { dictate(v.id, false) },
            button("Check practice") {
                val value = answer!!.text.toString()
                result.text = if (VerseAlarmRules.matches(v.text, value, dictatedText == value)) "Correct." else "Check the words and their order."
            }, result, button("Back to alarm settings") { practice = null; rendered = ""; refresh() }))
    }

    private fun showSettings(s: VerseAlarmState) {
        answer = null
        status = label("")
        val enabled = CheckBox(this).apply { text = "Daily alarm enabled"; isChecked = s.config.enabled }
        var selectedHour = s.config.hour
        var selectedMinute = s.config.minute
        fun alarmTime(): String {
            val time = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, selectedHour)
                set(Calendar.MINUTE, selectedMinute)
            }
            return "Alarm time: ${android.text.format.DateFormat.getTimeFormat(this).format(time.time)}"
        }
        val timeButton = button(alarmTime()) { }
        timeButton.setOnClickListener {
            val picker = MaterialTimePicker.Builder()
                .setTitleText("Daily alarm time")
                .setTimeFormat(if (android.text.format.DateFormat.is24HourFormat(this)) TimeFormat.CLOCK_24H else TimeFormat.CLOCK_12H)
                .setHour(selectedHour).setMinute(selectedMinute).build()
            picker.addOnPositiveButtonClickListener {
                selectedHour = picker.hour
                selectedMinute = picker.minute
                timeButton.text = alarmTime()
            }
            picker.show(supportFragmentManager, "verse_alarm_time")
        }
        val threshold = field("Successful completions (2–20)", s.config.memoryAfter.toString(), true).apply {
            id = View.generateViewId()
        }
        val memoryLabel = label("Successful completions before memory mode").apply { labelFor = threshold.id }
        val needs = VerseAlarmScheduler.readiness(this)
        val views = mutableListOf<View>(label("Verse wake-up", 26f), status!!,
            label("Learn a passage with its text visible, then write or recite it from memory. Each verse has its own count. Three checks, five minutes apart."),
            label("Daily time follows your phone's time zone. Settings changes wait 15 minutes after the alarm has first been enabled. Rebooting restarts that wait. An active routine must be completed."),
            label(if (needs.isEmpty()) "Alarm permissions are ready. Test the sound and dictation on your phone before relying on this alarm."
                else "Setup needed:\n${needs.joinToString("\n")}"),
            button("Alarms & reminders access") {
                if (Build.VERSION.SDK_INT >= 31) openSetting(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:$packageName".toUri()))
            },
            button("Alarm notifications") { openSetting(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName).putExtra(Settings.EXTRA_CHANNEL_ID, VerseAlarmScheduler.CHANNEL)) },
            button("Full-screen alarm access") {
                if (Build.VERSION.SDK_INT >= 34) openSetting(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, "package:$packageName".toUri()))
            },
            button("Sound settings") { openSetting(Intent(Settings.ACTION_SOUND_SETTINGS)) },
            button("Test alarm sound · 5 seconds") {
                previewSound?.stop()
                previewSound = android.media.RingtoneManager.getRingtone(this,
                    android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM))?.apply {
                    audioAttributes = android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM).build()
                    play()
                }
                val sound = previewSound
                handler.postDelayed({ sound?.stop() }, 5000)
            },
            enabled, timeButton,
            memoryLabel, threshold,
            label("For example, 3 means each verse stays visible for its first 3 successful alarm completions. After that, only its reference is shown and you recall the words from memory. Practice does not count toward this number."),
            button(if (s.everArmed) "Request settings change · 15 minutes" else "Save alarm settings") {
                attempt {
                    val config = VerseAlarmConfig(enabled.isChecked, selectedHour,
                        selectedMinute, threshold.text.toString().toIntOrNull() ?: -1)
                    require(config.valid()) { "Use hour 0–23, minute 0–59, and memory threshold 2–20." }
                    if (config.enabled) require(VerseAlarmScheduler.readiness(this).isEmpty()) { "Complete the alarm setup above first." }
                    VerseAlarmStore.saveConfig(this, config)
                }
            })
        if (s.pending != null) views.add(button("Cancel pending settings change") { attempt { VerseAlarmStore.cancelChange(this) } })
        views.add(label("Your ESV passages", 22f))
        views.add(label("Paste passages you want to learn, with the reference separate. No passages are downloaded or bundled. Edit the library while the alarm is off. Dictation uses your phone's speech provider and may require internet."))
        s.verses.forEach { v ->
            views.add(label("${v.reference} · ${v.successes} completions · ${if (v.successes >= s.config.memoryAfter) "Memory" else "Learning"}"))
            views.add(button("Practice ${v.reference}") { practice = v; rendered = ""; refresh() })
            if (!s.config.enabled) views.add(button("Remove ${v.reference}") { attempt { VerseAlarmStore.removeVerse(this, v.id) } })
        }
        if (!s.config.enabled) {
            val reference = field("Reference, e.g. Psalm 23:1")
            val passage = field("Paste ESV passage text without verse numbers").apply { minLines = 4 }
            views.add(reference)
            views.add(passage)
            views.add(button("Add passage") { attempt { VerseAlarmStore.addVerse(this, reference.text.toString(), passage.text.toString()) } })
        }
        views.add(button("Back") { finish() })
        show(views)
    }
    private fun openSetting(intent: Intent) {
        try { startActivity(intent) } catch (_: ActivityNotFoundException) { toast("This setting is unavailable on this phone.") }
    }
}
