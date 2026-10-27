package com.example.ariseosleeper

import android.content.ActivityNotFoundException
import android.content.Intent
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
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatEditText
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
    private var editingSettings = false
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
                toast("Alarm volume stays fixed until all checks are complete.")
            return true
        }
        return super.dispatchKeyEvent(event)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        practice = null
        editingSettings = false
    }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun label(text: String, size: Float = 16f) = UiStyle.label(this, text, size)
    private fun button(text: String, action: () -> Unit) = UiStyle.button(this, text, onClick = action)
    private fun field(hint: String, value: String = "", number: Boolean = false) = EditText(this).apply {
        this.hint = hint
        setText(value)
        if (number) inputType = InputType.TYPE_CLASS_NUMBER else VerseTextInput.configure(this)
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
        val now = AlarmClock.now(this)
        val s = VerseAlarmStore.reconcile(this, now)
        val ch = s.challenge
        if (ch != null) practice = null
        val waiting = ch != null && !ch.canAnswer(now)
        val preview = ch?.warning(now) == true
        val key = if (ch != null) "${ch.id}|$waiting|$preview|${ch.quietUsed}|$editingSettings|${s.pending}" else
            "${s.config}|${s.pending}|${s.verses.hashCode()}|${practice?.id}"
        if (key != rendered) {
            val previousAnswer = if (ch != null && rendered.substringBefore('|') == ch.id) answer?.text?.toString() else null
            val previousDictation = dictatedText?.takeIf { it == previousAnswer }
            rendered = key
            dictatedText = null
            when {
                ch != null && editingSettings -> showSettings(s)
                waiting -> showWaiting(ch!!)
                ch != null -> showChallenge(s, ch)
                practice != null -> showPractice(practice!!)
                else -> showSettings(s)
            }
            if (previousAnswer != null) {
                answer?.setText(previousAnswer)
                dictatedText = previousDictation
            }
        }
        status?.text = if (ch != null && editingSettings) buildString {
            append("A wake-up routine is active. Settings changes wait 15 minutes and cannot end the current routine.")
            if (s.pending != null) append("\nChange requested: ${((s.countdown?.remaining(now) ?: 0L) + 59_999) / 60_000} minutes remaining. It applies after the current routine finishes.")
        } else when {
            ch != null && waiting -> "Next check in ${(ch.remaining(now) + 999) / 1000} seconds. You have completed ${ch.round} of ${s.config.sessions} checks."
            ch != null && preview -> "Alarm rings in ${(ch.remaining(now) + 999) / 1000} seconds. Complete this verse now to prevent it ringing. Check ${ch.round + 1} of ${s.config.sessions}."
            ch != null && !ch.ringing(now) -> "Quiet time: ${(ch.remaining(now) + 999) / 1000} seconds. Finish the verse before the sound resumes."
            ch != null -> "Check ${ch.round + 1} of ${s.config.sessions}. Leaving this screen does not finish the alarm."
            practice != null -> "Practice only: this does not increase your wake-up completion count."
            else -> buildString {
                append(if (s.config.enabled) "Next alarm: ${DateFormat.getDateTimeInstance().format(Date(s.nextAlarm))}" else "Alarm is off.")
                s.pending?.let { pending ->
                    append("\nPending: ${if (pending.enabled) pending.times.joinToString { "%02d:%02d".format(it.hour, it.minute) } else "turn off"}, ${pending.sessions} sessions per alarm, memory after ${pending.memoryAfter} completions.")
                    append("\nApplies in ${((s.countdown?.remaining(now) ?: 0L) + 59_999) / 60_000} minutes, after any active routine finishes.")
                }
            }
        }
    }

    private fun showWaiting(ch: VerseChallenge) {
        answer = null
        status = label("")
        show(listOf(label("Keep awake", 26f), status!!,
            label("A notification chime and verse-entry page appear one minute before the next alarm. Complete the verse during that minute to prevent it ringing. Alarm volume stays fixed until all checks are complete."),
            button("Alarm settings") { editingSettings = true; rendered = ""; refresh() },
            button("Leave this screen") { finish() }))
    }

    private fun responseField() = object : AppCompatEditText(this) {
        override fun onTextContextMenuItem(id: Int): Boolean =
            if (id == android.R.id.paste || id == android.R.id.pasteAsPlainText) false else super.onTextContextMenuItem(id)
    }.apply {
        hint = "Type the passage, or use Dictate"
        VerseTextInput.configure(this)
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
        show(listOf(label(if (ch.warning(AlarmClock.now(this))) "One-minute alarm warning" else "Verse wake-up", 26f), status!!, label("${v.reference} · ESV", 22f),
            label(if (ch.fromMemory) "From memory — you have completed this passage ${v.successes} times."
                else "Learning — ${v.successes}/${s.config.memoryAfter} completions before memory mode."),
            label(if (ch.fromMemory) "Write or recite the passage from memory. The text stays hidden during this challenge." else v.text),
            answer!!, label("Capitalization and punctuation do not matter. Include the passage only, without verse numbers."),
            button("Dictate") { dictate(ch.id, true) },
            button("Check my verse") {
                val value = answer!!.text.toString()
                attempt {
                    if (VerseAlarmStore.answer(this, ch.id, value, dictatedText == value)) {
                        toast(if (ch.round + 1 >= s.config.sessions) "Wake-up routine complete." else "Verse complete. Your next check is in five minutes.")
                    } else {
                        result.text = "That does not match yet. Check the words and their order, then try again."
                        return@attempt
                    }
                }
            },
            button("Quiet for ${v.quietSeconds} seconds") {
                attempt { VerseAlarmStore.quiet(this, ch.id) }
            }.apply { isEnabled = !ch.quietUsed && ch.ringing(AlarmClock.now(this@VerseAlarmActivity)) }, result,
            button("Alarm settings") { editingSettings = true; rendered = ""; refresh() },
            label("One quiet period of ${v.quietSeconds} seconds per check, shared with dictation. Alarm volume stays fixed until all checks are complete.")))
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
        val draft = s.pending ?: s.config
        val enabled = CheckBox(this).apply { text = "Daily alarms enabled"; isChecked = draft.enabled }
        val times = draft.times.toMutableList()
        val timeRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun pickTime(time: VerseAlarmTime, onPicked: (VerseAlarmTime) -> Unit) {
            val picker = MaterialTimePicker.Builder()
                .setTitleText("Daily alarm time")
                .setTimeFormat(if (android.text.format.DateFormat.is24HourFormat(this)) TimeFormat.CLOCK_24H else TimeFormat.CLOCK_12H)
                .setHour(time.hour).setMinute(time.minute).build()
            picker.addOnPositiveButtonClickListener { onPicked(VerseAlarmTime(picker.hour, picker.minute)) }
            picker.show(supportFragmentManager, "verse_alarm_time")
        }
        fun renderTimes() {
            timeRows.removeAllViews()
            times.forEachIndexed { index, time ->
                val formatted = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, time.hour)
                    set(Calendar.MINUTE, time.minute)
                }.let { android.text.format.DateFormat.getTimeFormat(this).format(it.time) }
                timeRows.addView(button("Alarm ${index + 1}: $formatted") {
                    pickTime(time) { selected ->
                        if (times.withIndex().any { it.index != index && it.value == selected })
                            toast("An alarm already uses that time.")
                        else { times[index] = selected; renderTimes() }
                    }
                })
                if (times.size > 1) timeRows.addView(button("Remove alarm ${index + 1}") {
                    times.removeAt(index)
                    renderTimes()
                })
            }
        }
        renderTimes()
        val addAlarm = button("Add alarm") {
            if (times.size >= 24) toast("You can set up to 24 daily alarms.")
            else pickTime(VerseAlarmTime(7, 0)) { selected ->
                if (selected in times) toast("An alarm already uses that time.")
                else { times.add(selected); renderTimes() }
            }
        }
        val sessions = field("Sessions per alarm (1-20)", draft.sessions.toString(), true).apply {
            id = View.generateViewId()
        }
        val sessionsLabel = label("Number of sessions per alarm (1-20)").apply { labelFor = sessions.id }
        val threshold = field("Successful completions (2–20)", draft.memoryAfter.toString(), true).apply {
            id = View.generateViewId()
        }
        val memoryLabel = label("Successful completions before memory mode").apply { labelFor = threshold.id }
        val needs = VerseAlarmScheduler.readiness(this)
        val views = mutableListOf<View>(label(getString(R.string.app_name), 26f), status!!,
            label("Learn a passage with its text visible, then write or recite it from memory. Each verse has its own count. Choose the number of sessions (verse checks) per alarm, five minutes apart. A one-minute warning lets you finish each verse before the alarm rings."),
            label("Daily times follow your phone's time zone. Settings changes save immediately when no wake-up routine is running. During a routine, changes have a 15-minute wait and cannot end the current routine. The wait ends when all checks are complete."),
            label(if (needs.isEmpty()) "Alarm permissions are ready. Test the sound and dictation on your phone before relying on this alarm."
                else "Setup needed in Android Settings for Arise O Sleeper!:\n${needs.joinToString("\n")}"),
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
            enabled, label("Daily alarm times", 22f), timeRows, addAlarm,
            label("Each time repeats daily. All alarms use the session count below and your shared verse library. If a routine is still active, later alarms wait and run in order after it finishes. Tap Save to apply added, edited, or removed times."),
            sessionsLabel, sessions,
            memoryLabel, threshold,
            label("For example, 3 means each verse stays visible for its first 3 successful alarm completions. After that, only its reference is shown and you recall the words from memory. Practice does not count toward this number."),
            button(if (s.challenge != null) "Request settings change · 15 minutes" else "Save alarm settings") {
                attempt {
                    val config = VerseAlarmConfig(enabled.isChecked, times.first().hour,
                        times.first().minute, threshold.text.toString().toIntOrNull() ?: -1,
                        sessions.text.toString().toIntOrNull() ?: -1, times.drop(1))
                    require(config.valid()) { "Use 1-20 sessions, a memory threshold of 2-20, and up to 24 different alarm times." }
                    if (config.enabled) require(VerseAlarmScheduler.readiness(this).isEmpty()) { "Complete the alarm setup above first." }
                    VerseAlarmStore.saveConfig(this, config)
                }
            })
        if (s.pending != null) views.add(button("Cancel pending settings change") { attempt { VerseAlarmStore.cancelChange(this) } })
        views.add(label("Your ESV passages", 22f))
        views.add(label("Add passages below, with the reference separate. You can add verses while the daily alarm is enabled. No passages are downloaded or bundled. Dictation uses your phone's speech provider and may require internet."))
        val canAddPassage = s.challenge == null
        val reference = field("Reference, e.g. Psalm 23:1").apply { isEnabled = canAddPassage }
        val passage = field("Paste ESV passage text without verse numbers").apply {
            minLines = 4
            isEnabled = canAddPassage
        }
        val quiet = field("Quiet time in seconds (1-3600)", "60", true).apply {
            id = View.generateViewId()
            isEnabled = canAddPassage
        }
        views.add(reference)
        views.add(passage)
        views.add(label("Quiet time for this passage (seconds)").apply { labelFor = quiet.id })
        views.add(quiet)
        views.add(button("Add passage") {
            attempt { VerseAlarmStore.addVerse(this, reference.text.toString(), passage.text.toString(),
                quiet.text.toString().toIntOrNull() ?: -1) }
        }.apply { isEnabled = canAddPassage })
        if (!canAddPassage) views.add(label("Finish all checks in the current wake-up routine to add new passages."))
        views.add(label("Each passage has its own quiet time, used by both Quiet and Dictate. Set 1-3600 seconds (up to 60 minutes). Quiet times can be edited when no routine is active."))
        s.verses.forEach { v ->
            views.add(label("${v.reference} · ${v.successes} completions · ${if (v.successes >= s.config.memoryAfter) "Memory" else "Learning"}"))
            val verseQuiet = field("Quiet time in seconds (1-3600)", v.quietSeconds.toString(), true).apply {
                id = View.generateViewId()
                isEnabled = canAddPassage
            }
            views.add(label("Quiet time for ${v.reference} (seconds)").apply { labelFor = verseQuiet.id })
            views.add(verseQuiet)
            views.add(button("Save quiet time for ${v.reference}") {
                attempt { VerseAlarmStore.setQuietTime(this, v.id, verseQuiet.text.toString().toIntOrNull() ?: -1) }
            }.apply { isEnabled = canAddPassage })
            views.add(button("Practice ${v.reference}") { practice = v; rendered = ""; refresh() })
            if (!s.config.enabled) views.add(button("Remove ${v.reference}") { attempt { VerseAlarmStore.removeVerse(this, v.id) } })
        }
        views.add(button(if (s.challenge != null) "Back to wake-up check" else "Close app") {
            if (s.challenge != null) { editingSettings = false; rendered = ""; refresh() } else finish()
        })
        show(views)
    }
    private fun openSetting(intent: Intent) {
        try { startActivity(intent) } catch (_: ActivityNotFoundException) { toast("This setting is unavailable on this phone.") }
    }
}
