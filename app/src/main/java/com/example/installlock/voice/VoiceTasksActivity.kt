package com.example.installlock.voice

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.color.DynamicColors
import java.io.File
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.zip.*

class VoiceTasksActivity : AppCompatActivity() {
    private lateinit var column: LinearLayout
    private lateinit var command: EditText
    private lateinit var status: TextView
    private lateinit var activationStatus: TextView
    private var activationDialogStatus: TextView? = null
    private lateinit var tasks: LinearLayout
    private var filter = "Open"
    private var query = ""
    private var pendingCapture = false
    private var pendingActivation = false
    private var pendingMemo = false
    private var pendingMemoPermission = false
    private var memoStarting = false
    private var resumeAfterMemo = false
    private var memoTiming: VoiceMemoTimingDialog? = null
    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var lastTranscript = ""
    private var lastTranscriptAt = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val refreshActivation = object : Runnable {
        override fun run() {
            val p = VoiceStore.prefs(this@VoiceTasksActivity)
            val modes = listOfNotNull(if (p.getBoolean("shake", false)) (if (p.getBoolean("shake_memo", true)) "Shake to record memo" else "Shake to dictate") else null, if (p.getBoolean("wake", false)) "Wake phrase" else null).joinToString(" + ")
            val message = when {
                recorder != null -> "Recording voice memo · tap Finish when done"
                memoStarting -> "Starting voice memo…"
                memoTiming != null -> "Choose when to play your memo reminder"
                modes.isEmpty() -> "Shake and wake activation are off"
                !VoiceListenerService.active || !p.getBoolean("running", false) -> "$modes paused · tap Resume activation"
                !VoiceListenerService.ready -> "$modes starting…"
                else -> "$modes ready${if (!p.getBoolean("wake", false)) " · microphone idle until triggered" else " · microphone listening locally"}"
            }
            activationStatus.text = message
            activationDialogStatus?.text = message
            handler.postDelayed(this, 500)
        }
    }
    private val speech = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val text = i.getStringExtra("text").orEmpty()
            val kind = i.getStringExtra("kind").orEmpty()
            memoTiming?.let { it.onSpeech(kind, text); return }
            when (kind) {
                "partial" -> { command.setText(text); status.text = "Listening locally…" }
                "final" -> transcript(text)
                "duration" -> Unit // Timing responses never become general task commands.
                "practice" -> Unit
                else -> status.text = text
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this); super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        scroll.addView(column); setContentView(scroll)
        scroll.setOnApplyWindowInsetsListener { v, insets -> val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars()); v.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets }
        label("VOICE TASKS & REMINDERS", 12f)
        label("Remember it, offline", 26f)
        label("Speak or type a command. Your tasks stay on this phone.")
        status = label("Ready · English speech model included")
        activationStatus = label("")
        command = field("Remind me to take out the trash in five minutes")
        button("Dictate offline") { dictate() }
        button("Save / review task") { process(command.text.toString(), fromSpeech = command.text.toString() == lastTranscript, allowDraft = true) }
        button("New task / reminder") { edit(VoiceTask(title = "")) }
        button("Record a voice memo") { recordMemo() }
        button("Activation & settings") { settings() }
        button("Practice my time phrases") { startActivity(Intent(this, VoiceTimePracticeActivity::class.java)) }
        button("Resume activation") { resumeActivation() }
        button("Pause listening now") { VoiceListenerService.pause(this); status.text = "Listening paused" }
        val search = field("Search tasks, notes or lists")
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { query = s.toString(); renderTasks() }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        val filters = Spinner(this).apply { adapter = ArrayAdapter(this@VoiceTasksActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Open", "Today", "Upcoming", "Overdue", "Completed", "Archived", "All")) }
        column.addView(filters)
        tasks = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; column.addView(tasks)
        filters.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { filter = filters.selectedItem.toString(); renderTasks() }
        }
        button("Export backup (including voice recordings)") { startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/zip").putExtra(Intent.EXTRA_TITLE, "installlock-reminders.zip"), 42) }
        button("Import backup") { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), 43) }
        button("Help & open-source credits") { help() }
        handle(intent)
    }
    override fun onStart() {
        super.onStart()
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(speech, IntentFilter(VoiceListenerService.EVENT), Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(speech, IntentFilter(VoiceListenerService.EVENT))
        VoiceAlarms.restore(this)
        handler.post(refreshActivation)
        renderTasks()
    }
    override fun onStop() { memoTiming?.pause(); handler.removeCallbacks(refreshActivation); unregisterReceiver(speech); super.onStop() }
    override fun onResume() {
        super.onResume()
        if (pendingMemo && !getSystemService(KeyguardManager::class.java).isDeviceLocked) {
            pendingMemo = false; handler.post { recordMemo() }
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handle(intent) }
    private fun handle(i: Intent) {
        if (i.getBooleanExtra("memo", false)) {
            i.removeExtra("memo")
            pendingMemo = true
            handler.post {
                if (pendingMemo && !getSystemService(KeyguardManager::class.java).isDeviceLocked) {
                    pendingMemo = false; recordMemo()
                } else if (pendingMemo) status.text = "Unlock your phone to record the voice memo."
            }
            return
        }
        i.getStringExtra("transcript")?.let { transcript(it) }
        if (i.getBooleanExtra("capture", false)) handler.post { dictate() }
        if (i.getBooleanExtra("listening", false)) status.text = "Listening locally…"
        i.removeExtra("capture"); i.removeExtra("transcript"); i.removeExtra("listening")
    }
    private fun transcript(text: String) {
        if (text == lastTranscript && SystemClock.elapsedRealtime() - lastTranscriptAt < 2500) return
        lastTranscript = text; lastTranscriptAt = SystemClock.elapsedRealtime()
        command.setText(text); status.text = "Captured offline · review before saving"
        process(text, fromSpeech = true)
    }
    private fun dictate() {
        if (recorder != null) { toast("Finish recording first."); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingCapture = true; requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 41); return
        }
        try { VoiceListenerService.start(this, true); status.text = "Starting offline dictation…" }
        catch (_: RuntimeException) { status.text = "Could not start listening. Check microphone permissions." }
    }
    @Deprecated("Android callback")
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == 41 && pendingMemoPermission) {
            pendingMemoPermission = false
            if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) recordMemo()
            else status.text = "Microphone permission is needed to record a memo."
        }
        if (code == 41 && pendingActivation) {
            pendingActivation = false
            if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) safely { resumeActivation() }
            else status.text = "Activation paused: microphone permission is needed for dictation."
        }
        if (code == 41 && pendingCapture) { pendingCapture = false; if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) dictate() else status.text = "Microphone denied. You can still type commands." }
    }
    private fun resumeActivation() {
        require(recorder == null) { "Finish recording first." }
        val p = VoiceStore.prefs(this)
        require(p.getBoolean("shake", false) || p.getBoolean("wake", false)) { "Enable shake or the wake phrase in Activation & settings first." }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingActivation = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 41)
            return
        }
        p.edit().putBoolean("running", true).remove("speech_error").apply()
        try { VoiceListenerService.start(this); status.text = "Starting offline activation…" }
        catch (e: RuntimeException) { p.edit().putBoolean("running", false).apply(); throw e }
    }
    private fun process(text: String, fromSpeech: Boolean = false, allowDraft: Boolean = false) {
        try {
            val lower = text.trim().lowercase()
            if (lower in listOf("cancel", "stop listening")) { VoiceListenerService.pause(this); status.text = "Listening paused"; return }
            if (lower in listOf("show my tasks", "show my tasks for today", "show today's tasks")) { filter = if (lower.contains("today")) "Today" else "Open"; renderTasks(); return }
            val done = Regex("mark (.+) (?:as )?done", RegexOption.IGNORE_CASE).matchEntire(text.trim())
            val snooze = Regex("snooze (.+) (?:for|in) (.+) minutes?", RegexOption.IGNORE_CASE).matchEntire(text.trim())
            if (done != null || snooze != null) {
                val name = (done ?: snooze)!!.groupValues[1]
                val matches = VoiceStore.all(this).filter { !it.done && !it.archived && it.title.equals(name, true) }
                require(matches.size == 1) { "Please use the exact title of one open task, or choose it in the list." }
                val t = matches.single()
                val mins = snooze?.let { VoiceCommand.number(it.groupValues[2]) ?: throw IllegalArgumentException("Specify snooze minutes.") }
                MaterialAlertDialogBuilder(this).setTitle(if (done != null) "Complete ${t.title}?" else "Snooze ${t.title} for $mins minutes?")
                    .setPositiveButton("Confirm") { _, _ -> safely { if (done != null) VoiceStore.complete(this, t.id) else VoiceStore.snooze(this, t.id, mins!!); renderTasks() } }
                    .setNegativeButton("Cancel", null).show(); return
            }
            val interpreted = try {
                if (fromSpeech) VoiceCommand.parseSpoken(text) else VoiceInterpretation(VoiceCommand.parse(text))
            } catch (e: IllegalArgumentException) {
                if (!allowDraft) throw e
                val title = text.trim()
                require(title.isNotEmpty()) { "Dictate or type a task first." }
                // A fragment can be reviewed explicitly, but never saved by quick-save.
                val fragment = runCatching { VoiceCommand.parseSpoken("Remind me to $title") }.getOrNull()
                if (fragment != null) {
                    edit(VoiceTask(title = fragment.draft.title, due = fragment.draft.due, repeat = fragment.draft.repeat),
                        "Check this suggested task and reminder time before saving.")
                } else {
                    edit(VoiceTask(title = title), "The reminder time was not understood. This will save as a task without a reminder unless you enter a date and time below.")
                }
                return
            }
            val parsed = interpreted.draft
            val task = VoiceTask(title = parsed.title, due = parsed.due, repeat = parsed.repeat)
            if (interpreted.correction != null) status.text = "Heard: $text\nSuggested: ${interpreted.correction}\nCheck the title and time before saving."
            if (interpreted.correction == null && VoiceStore.prefs(this).getBoolean("fast", false)) saveWithUndo(task) else edit(task)
        } catch (e: Exception) {
            status.text = if (fromSpeech && !allowDraft) "Captured, not saved. Tap Save / review task to correct the title and reminder time.\n${e.message.orEmpty()}"
                else e.message ?: "Please check the command."
        }
    }
    private fun renderTasks() {
        if (!::tasks.isInitialized) return
        tasks.removeAllViews()
        val now = System.currentTimeMillis(); val today = LocalDate.now()
        val items = VoiceStore.all(this).filter { t ->
            val dueDay = t.trigger?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() }
            val selected = when (filter) {
                "Completed" -> t.done && !t.archived; "Archived" -> t.archived; "All" -> true
                "Today" -> !t.done && !t.archived && dueDay == today
                "Upcoming" -> !t.done && !t.archived && (t.trigger ?: 0) > now
                "Overdue" -> !t.done && !t.archived && t.trigger != null && t.trigger!! < now
                else -> !t.done && !t.archived
            }
            selected && "${t.title} ${t.notes} ${t.list}".contains(query, true)
        }.sortedWith(compareBy<VoiceTask> { !it.priority }.thenBy { it.trigger ?: Long.MAX_VALUE })
        tasks.addView(TextView(this).apply { text = "$filter · ${items.size}"; textSize = 18f; setPadding(0, 20, 0, 12) })
        if (items.isEmpty()) tasks.addView(TextView(this).apply { text = "No tasks here yet." })
        items.forEach { t ->
            tasks.addView(Button(this).apply {
                isAllCaps = false; gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                text = "${if (t.priority) "★ " else ""}${t.title}\n${t.trigger?.let { format(it) } ?: "No reminder"} · ${t.list}${if (t.repeat != "NONE") " · ${t.repeat.lowercase()}" else ""}${if (t.done) " · Done" else ""}"
                setOnClickListener { actions(t) }
            })
        }
    }
    private fun actions(t: VoiceTask) {
        val choices = arrayOf(if (t.done) "Reopen" else "Done", "Edit", "Snooze", "Play aloud / recording", "Duplicate", if (t.archived) "Unarchive" else "Archive", "Delete")
        MaterialAlertDialogBuilder(this).setTitle(t.title).setItems(choices) { _, which -> safely {
            when (which) {
                0 -> if (t.done) VoiceStore.save(this, t.copy(done = false, alertedAt = t.trigger)) else VoiceStore.complete(this, t.id)
                1 -> edit(t)
                2 -> {
                    val input = EditText(this).apply { hint = "Minutes"; inputType = InputType.TYPE_CLASS_NUMBER; setText("10") }
                    MaterialAlertDialogBuilder(this).setTitle("Snooze").setView(input).setPositiveButton("Snooze") { _, _ -> safely { VoiceStore.snooze(this, t.id, input.text.toString().toInt()); renderTasks() } }.setNegativeButton("Cancel", null).show()
                }
                3 -> VoicePlaybackService.play(this, t)
                4 -> edit(t.copy(id = UUID.randomUUID().toString(), done = false, archived = false, alertedAt = null, snoozeUntil = null))
                5 -> VoiceStore.save(this, t.copy(archived = !t.archived))
                6 -> MaterialAlertDialogBuilder(this).setTitle("Delete this task?").setPositiveButton("Delete") { _, _ -> safely { VoiceStore.remove(this, t.id); renderTasks() } }.setNegativeButton("Cancel", null).show()
            }; renderTasks()
        } }.show()
    }
    private fun edit(original: VoiceTask, guidance: String? = null, afterDismiss: (() -> Unit)? = null) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(30, 12, 30, 12) }
        guidance?.let { box.addView(TextView(this).apply { text = it; setPadding(0, 0, 0, 16) }) }
        fun entry(hintText: String, value: String) = EditText(this).apply { hint = hintText; setText(value); box.addView(this) }
        val title = entry("Task title", original.title)
        val whenField = entry("Reminder: yyyy-MM-dd HH:mm[:ss] (blank = task)", original.due?.let { format(it) }.orEmpty())
        val notes = entry("Notes / subtasks (one per line)", original.notes)
        val list = entry("List", original.list)
        val repeats = listOf("NONE", "DAILY", "WEEKLY", "MONTHLY")
        val repeat = Spinner(this).apply { adapter = ArrayAdapter(this@VoiceTasksActivity, android.R.layout.simple_spinner_dropdown_item, repeats); setSelection(repeats.indexOf(original.repeat)); box.addView(this) }
        val priority = CheckBox(this).apply { text = "High priority"; isChecked = original.priority; box.addView(this) }
        val speak = CheckBox(this).apply { text = "Speak / play recording when due (offline voice required)"; isChecked = original.speak; box.addView(this) }
        val error = TextView(this); box.addView(error)
        val dialog = MaterialAlertDialogBuilder(this).setTitle("Review and save task").setView(ScrollView(this).apply { addView(box) }).setPositiveButton("Save task", null).setNegativeButton("Cancel", null).create()
        dialog.setOnDismissListener {
            if (original.recording.isNotEmpty() && VoiceStore.all(this).none { it.recording == original.recording })
                File(filesDir, "voice-recordings/${original.recording}").delete()
            afterDismiss?.invoke()
        }
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try {
                val titleText = title.text.toString().trim(); require(titleText.isNotEmpty()) { "Enter a title." }
                val due = whenField.text.toString().trim().takeIf { it.isNotEmpty() }?.let { LocalDateTime.parse(it, DATE).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }
                require(due == null || due > System.currentTimeMillis() || due == original.due) { "Choose a future reminder time." }
                require(due != null || repeat.selectedItem == "NONE") { "Recurring reminders need a date and time." }
                val t = original.copy(title = titleText, due = due, repeat = repeat.selectedItem.toString(), notes = notes.text.toString(), list = list.text.toString().ifBlank { "Personal" }, priority = priority.isChecked, speak = speak.isChecked,
                    alertedAt = if (due == original.due) original.alertedAt else null, snoozeUntil = if (due == original.due) original.snoozeUntil else null)
                saveWithUndo(t); dialog.dismiss()
            } catch (e: Exception) { error.text = e.message ?: "Check the date and time." }
        } }; dialog.show()
    }
    private fun saveWithUndo(task: VoiceTask) {
        val previous = VoiceStore.all(this).find { it.id == task.id }
        VoiceStore.save(this, task); renderTasks(); status.text = "Saved: ${task.title}${task.due?.let { " · ${format(it)}" }.orEmpty()}"
        com.google.android.material.snackbar.Snackbar.make(column, "Saved locally", 6000).setAction("Undo") { safely { if (previous == null) VoiceStore.remove(this, task.id) else VoiceStore.save(this, previous); renderTasks() } }.show()
    }
    private fun settings() {
        val p = VoiceStore.prefs(this)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(30, 10, 30, 10) }
        activationDialogStatus = TextView(this).also { it.text = activationStatus.text; box.addView(it) }
        box.addView(TextView(this).apply { text = "Turning on shake or the wake phrase starts detection. Pause stops both; Resume starts them again." })
        fun toggle(key: String, title: String, default: Boolean = false) { box.addView(CheckBox(this).apply {
            text = title; isChecked = p.getBoolean(key, default)
            setOnCheckedChangeListener { _, checked ->
                p.edit().putBoolean(key, checked).apply()
                if (key == "shake" || key == "wake") safely {
                    if (checked) resumeActivation()
                    else if (!p.getBoolean("shake", false) && !p.getBoolean("wake", false)) VoiceListenerService.pause(this@VoiceTasksActivity)
                }
            }
        }) }
        toggle("wake", "Listen for ‘Hey Reminder’ (uses battery)")
        toggle("shake", "Enable shake three times")
        toggle("shake_memo", "Shake records a voice memo (off = dictate)", true)
        toggle("volume", "Hold Volume Down to dictate (screen-on)")
        toggle("fast", "Save parsed commands immediately, with Undo")
        toggle("quiet", "Quiet hours: 10 PM–7 AM")
        toggle("private", "Hide reminder content on lock screen", true)
        toggle("repeat_alert", "Repeat unacknowledged alerts every 10 min (max 3)")
        box.addView(TextView(this).apply { text = "Shake sensitivity" })
        box.addView(SeekBar(this).apply { max = 25; progress = ((p.getFloat("shake_threshold", 2.7f) - 1.5f) * 10).toInt(); setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, user: Boolean) { if (user) p.edit().putFloat("shake_threshold", 1.5f + value / 10f).apply() }
            override fun onStartTrackingTouch(bar: SeekBar?) {}; override fun onStopTrackingTouch(bar: SeekBar?) {}
        }) })
        val alarms = getSystemService(AlarmManager::class.java)
        box.addView(TextView(this).apply { text = "Microphone permission: ${checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED}\nExact alarms: ${Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()}\nNotifications: ${getSystemService(NotificationManager::class.java).areNotificationsEnabled()}\nVolume shortcut uses InstallLock’s existing accessibility service. Screen-off keys are device-dependent.\nListening does not restart automatically after reboot.\n${p.getString("speech_error", "")}" })
        fun action(title: String, block: () -> Unit) { box.addView(Button(this).apply { text = title; isAllCaps = false; setOnClickListener { safely(block) } }) }
        action("Microphone permission") { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 41) }
        action("Alarms & reminders permission") { if (Build.VERSION.SDK_INT >= 31) startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))) }
        action("Notification settings") { startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)) }
        action("Accessibility settings") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        action("Default assistant settings") { startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
        action("Battery settings") { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        MaterialAlertDialogBuilder(this).setTitle("Offline voice settings").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Resume") { _, _ -> safely { resumeActivation() } }
            .setNeutralButton("Pause") { _, _ -> VoiceListenerService.pause(this); status.text = "Listening paused" }
            .setNegativeButton("Close", null).setOnDismissListener { activationDialogStatus = null }.show()
    }
    private fun recordMemo() {
        if (recorder != null || memoStarting || memoTiming != null) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingMemoPermission = true; requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 41); return
        }
        resumeAfterMemo = VoiceStore.prefs(this).getBoolean("running", false) && VoiceListenerService.active
        memoStarting = true
        VoiceListenerService.pause(this)
        status.text = "Starting voice memo…"
        val deadline = SystemClock.elapsedRealtime() + 5000
        // Wait until Vosk has released the microphone before opening MediaRecorder.
        fun startWhenReleased() {
            if (isFinishing || isDestroyed) { memoStarting = false; return }
            if (VoiceListenerService.active || VoiceListenerService.workerRunning) {
                if (SystemClock.elapsedRealtime() < deadline) { handler.postDelayed({ startWhenReleased() }, 50); return }
                memoStarting = false; status.text = "Microphone is still busy. Try recording again."; restoreAfterMemo(); return
            }
            try {
                val file = File(filesDir, "voice-recordings/${UUID.randomUUID()}.m4a"); file.parentFile?.mkdirs(); recordingFile = file
                @Suppress("DEPRECATION") val r = MediaRecorder()
                recorder = r; r.setAudioSource(MediaRecorder.AudioSource.MIC); r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4); r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC); r.setOutputFile(file.path); r.setMaxDuration(120000); r.prepare(); r.start()
                memoStarting = false; status.text = "Recording voice memo · tap Finish when done"; VoiceFeedback.ready(this)
                val dialog = MaterialAlertDialogBuilder(this).setTitle("Recording voice memo").setMessage("Recording locally, up to two minutes. Tap Finish; I will ask when to remind you.").setCancelable(false)
                    .setPositiveButton("Finish") { _, _ -> finishMemo(true) }.setNegativeButton("Cancel") { _, _ -> finishMemo(false) }.create()
                r.setOnInfoListener { _, what, _ -> if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) { dialog.dismiss(); finishMemo(true) } }
                r.setOnErrorListener { _, _, _ -> dialog.dismiss(); finishMemo(false); status.text = "Recording stopped because the microphone became unavailable." }
                dialog.show()
            } catch (e: Exception) {
                memoStarting = false; recorder?.release(); recorder = null; recordingFile?.delete(); recordingFile = null
                status.text = "Could not record memo: ${e.message}"; restoreAfterMemo()
            }
        }
        handler.post { startWhenReleased() }
    }
    private fun finishMemo(save: Boolean) {
        var good = save
        try { recorder?.stop() } catch (_: Exception) { good = false }
        recorder?.release(); recorder = null
        val f = recordingFile; recordingFile = null
        status.text = if (good) "Memo recorded · when should I remind you?" else "Recording cancelled"
        if (good && f != null) {
            memoTiming = VoiceMemoTimingDialog(this,
                onSelected = { due ->
                    memoTiming = null
                    edit(VoiceTask(title = "Voice memo", recording = f.name, speak = true, due = due),
                        if (due == null) "Save your recording without a reminder." else "I will play your recording at ${format(due)}. Check the time, then tap Save task.", ::restoreAfterMemo)
                },
                onCancelled = { memoTiming = null; f.delete(); status.text = "Memo cancelled"; restoreAfterMemo() }
            ).also { it.show() }
        }
        else { f?.delete(); restoreAfterMemo() }
    }
    private fun restoreAfterMemo() {
        val resume = resumeAfterMemo; resumeAfterMemo = false
        if (!resume || isFinishing || isDestroyed) return
        val deadline = SystemClock.elapsedRealtime() + 5000
        fun resumeWhenReleased() {
            if (isFinishing || isDestroyed) return
            if (VoiceListenerService.active || VoiceListenerService.workerRunning) {
                if (SystemClock.elapsedRealtime() < deadline) handler.postDelayed({ resumeWhenReleased() }, 100)
                else status.text = "Tap Resume activation when the microphone is free."
            } else safely { resumeActivation() }
        }
        handler.post { resumeWhenReleased() }
    }
    @Deprecated("Android callback")
    override fun onActivityResult(code: Int, result: Int, data: Intent?) {
        super.onActivityResult(code, result, data)
        if (result != RESULT_OK) return
        val uri = data?.data ?: return
        status.text = "Processing backup…"
        Thread {
            val outcome = runCatching {
                if (code == 42) {
                    ZipOutputStream(contentResolver.openOutputStream(uri)!!).use { zip ->
                        zip.putNextEntry(ZipEntry("tasks.json")); zip.write(VoiceStore.export(this).toByteArray()); zip.closeEntry()
                        VoiceStore.all(this).map { it.recording }.filter { it.isNotEmpty() }.distinct().forEach { name ->
                            val f = File(filesDir, "voice-recordings/$name")
                            if (f.isFile) { zip.putNextEntry(ZipEntry("recordings/$name")); f.inputStream().use { it.copyTo(zip) }; zip.closeEntry() }
                        }
                    }; "Backup exported"
                } else {
                    val staging = File(cacheDir, "voice-import-${UUID.randomUUID()}").apply { mkdirs() }
                    try {
                        var json: String? = null; var size = 0L
                        ZipInputStream(contentResolver.openInputStream(uri)!!).use { zip ->
                            while (true) {
                                val entry = zip.nextEntry ?: break
                                require(entry.name == "tasks.json" || entry.name.matches(Regex("recordings/[a-f0-9-]+\\.m4a"))) { "Invalid backup entry" }
                                val f = File(staging, entry.name); f.parentFile?.mkdirs()
                                f.outputStream().use { out -> val buf = ByteArray(8192); while (true) { val n = zip.read(buf); if (n < 0) break; size += n; require(size <= 100_000_000) { "Backup exceeds 100 MB" }; out.write(buf, 0, n) } }
                                if (entry.name == "tasks.json") { require(f.length() <= 5_000_000); json = f.readText() }
                            }
                        }
                        val count = VoiceStore.import(this, json ?: error("No tasks.json in backup"))
                        File(staging, "recordings").listFiles()?.forEach { f -> val target = File(filesDir, "voice-recordings/${f.name}"); target.parentFile?.mkdirs(); if (!target.exists()) f.copyTo(target) }
                        "Imported $count tasks"
                    } finally { staging.deleteRecursively() }
                }
            }
            runOnUiThread { status.text = outcome.getOrElse { "Backup failed: ${it.message}" }; renderTasks() }
        }.start()
    }
    private fun help() {
        val text = "Examples:\n• Add study CATIA to my tasks\n• Remind me to take out the trash in five minutes\n• Remind me tomorrow at nine AM to call John\n• Remind me every Monday at seven PM to take out the bins\n• Mark study CATIA as done\n• Snooze study CATIA for ten minutes\n\nSay ‘Hey Reminder’, pause for the vibration, then speak. Wake detection is experimental Vosk keyword recognition, not speaker verification or low-power hardware detection.\n\nRelative reminders use elapsed wall-clock timestamps. Daily/weekly repeats keep their saved timezone. Completing a recurring reminder advances it to the next occurrence. Missed reminders appear after reboot/unlock. Exact timing requires Alarms & reminders permission.\n\nVoice commands cannot change InstallLock protections. The feature has no network code; InstallLock’s other features still have internet access.\n\n" + assets.open("voice/NOTICE.txt").bufferedReader().use { it.readText() }
        MaterialAlertDialogBuilder(this).setTitle("Offline voice help").setMessage(text).setPositiveButton("Close", null).show()
    }
    private fun label(text: String, size: Float = 15f) = TextView(this).apply { this.text = text; textSize = size; setPadding(0, 8, 0, 8); column.addView(this) }
    private fun field(hintText: String) = EditText(this).apply { hint = hintText; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES; column.addView(this) }
    private fun button(title: String, action: () -> Unit) { column.addView(Button(this).apply { text = title; isAllCaps = false; setOnClickListener { safely(action) } }) }
    private fun safely(action: () -> Unit) { try { action() } catch (e: Exception) { toast(e.message ?: "Action failed") } }
    private fun toast(text: String) { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
    override fun onDestroy() { resumeAfterMemo = false; pendingMemo = false; memoTiming?.cancel(); handler.removeCallbacksAndMessages(null); if (recorder != null) finishMemo(false); super.onDestroy() }
    companion object {
        private val DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm[:ss]").withResolverStyle(java.time.format.ResolverStyle.STRICT)
        fun format(time: Long) = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).format(DATE)
    }
}
