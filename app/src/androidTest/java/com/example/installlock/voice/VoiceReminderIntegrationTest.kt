package com.example.installlock.voice

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.widget.Button
import android.widget.EditText
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.io.File
import java.util.zip.ZipInputStream
import org.vosk.Model
import org.vosk.Recognizer
import org.json.JSONObject
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.matcher.ViewMatchers.withHint
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.matcher.RootMatchers.isDialog

/** Only creates uniquely named test reminders; preserves all existing protection and task settings. */
class VoiceReminderIntegrationTest {
    private val c get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun practiceConfirmsSamplesAndUsesProfileWithNativeDurationGrammar() {
        val profile = c.getSharedPreferences("voice_time_practice", Context.MODE_PRIVATE)
        val before = profile.getString("samples", null)
        val capture = java.util.concurrent.atomic.AtomicReference<String>()
        val ready = java.util.concurrent.CountDownLatch(1)
        val listener = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getStringExtra("kind") == "status" && intent.getStringExtra("text")?.startsWith("Listening") == true) {
                    val id = intent.getStringExtra("capture_id")
                    if (!id.isNullOrBlank()) { capture.set(id); ready.countDown() }
                }
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) c.registerReceiver(listener, android.content.IntentFilter(VoiceListenerService.EVENT), Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") c.registerReceiver(listener, android.content.IntentFilter(VoiceListenerService.EVENT))
        try {
            profile.edit().remove("samples").commit()
            ActivityScenario.launch<VoiceTimePracticeActivity>(Intent(c, VoiceTimePracticeActivity::class.java)).use {
                onView(withText("Say: five minutes")).check(matches(isDisplayed()))
                onView(withText("Record phrase")).perform(scrollTo(), click())
                assertTrue("Practice starts offline capture", ready.await(12, java.util.concurrent.TimeUnit.SECONDS))
                c.sendBroadcast(Intent(VoiceListenerService.EVENT).setPackage(c.packageName).putExtra("kind", "practice").putExtra("text", "five men").putExtra("capture_id", capture.get()))
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                assertTrue("Recognition alone must not teach a correction", VoiceTimePractice.samples(c).isEmpty())
                onView(withText("Confirm sample")).perform(scrollTo(), click())
                onView(withText("Say: ten hours")).check(matches(isDisplayed()))
                assertEquals(300000L, VoiceTimePractice.resolve(c, "five men").delay)
                assertThrows(IllegalArgumentException::class.java) { VoiceTimePractice.save(c, VoiceTimeSample("five men", "five hours")) }
                Model(VoiceModel.prepare(c)).use { model ->
                    Recognizer(model, 16000f, VoiceTimePractice.grammar(c)).use { decoder ->
                        val pcm = fixturePcm("reminder-duration.wav")
                        decoder.acceptWaveForm(pcm, pcm.size)
                        val text = JSONObject(decoder.finalResult).getString("text")
                        assertEquals("Focused duration recognition: $text", 18000000L, VoiceTimePractice.resolve(c, text).delay)
                    }
                }
                onView(withText("Finish session")).perform(scrollTo(), click())
            }
        } finally {
            c.unregisterReceiver(listener); VoiceListenerService.pause(c)
            profile.edit().apply { if (before == null) remove("samples") else putString("samples", before) }.commit()
        }
    }
    private fun fixturePcm(name: String): ByteArray {
        val wave = InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { it.readBytes() }
        var offset = 12
        while (offset + 8 <= wave.size) {
            val chunk = String(wave, offset, 4, Charsets.US_ASCII)
            val size = java.nio.ByteBuffer.wrap(wave, offset + 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
            if (chunk == "data") return wave.copyOfRange(offset + 8, offset + 8 + size)
            offset += 8 + size + size % 2
        }
        error("Fixture has no PCM data")
    }
    @Test fun shakeDefaultsToMemoAndSavesAudioThenResumesActivation() {
        val p = VoiceStore.prefs(c)
        val previousMemo = if (p.contains("shake_memo")) p.getBoolean("shake_memo", true) else null
        val title = "Shake memo test ${UUID.randomUUID()}"
        try {
            p.edit().remove("shake_memo").commit()
            assertTrue(VoiceShakeAction.intent(c).getBooleanExtra("memo", false))
            assertFalse(VoiceShakeAction.intent(c).getBooleanExtra("capture", false))
            p.edit().putBoolean("shake_memo", false).commit()
            assertTrue("Dictation remains selectable", VoiceShakeAction.intent(c).getBooleanExtra("capture", false))
            p.edit().remove("shake_memo").commit()
            ActivityScenario.launch<VoiceTasksActivity>(Intent(c, VoiceTasksActivity::class.java)).use { scenario ->
                scenario.onActivity { activity ->
                    p.edit().putBoolean("running", true).commit()
                    VoiceListenerService.start(activity)
                }
                val readyBy = System.currentTimeMillis() + 10000
                while (!VoiceListenerService.ready && System.currentTimeMillis() < readyBy) Thread.sleep(100)
                assertTrue("Activation starts before microphone handoff", VoiceListenerService.ready)
                scenario.onActivity { it.startActivity(VoiceShakeAction.intent(c)) }
                val recordingBy = System.currentTimeMillis() + 7000
                while (true) {
                    try { onView(withText("Recording voice memo")).check(matches(isDisplayed())); break }
                    catch (e: androidx.test.espresso.NoMatchingViewException) {
                        if (System.currentTimeMillis() > recordingBy) throw e
                        Thread.sleep(150)
                    }
                }
                Thread.sleep(1200)
                onView(withText("Finish")).perform(click())
                onView(withHint("When? e.g. 5 mins or 5 hours")).perform(replaceText("5 mins"))
                closeSoftKeyboard()
                onView(withText("Use time")).perform(click())
                onView(withHint("Task title")).perform(replaceText(title))
                closeSoftKeyboard()
                onView(withText("Save task")).perform(click())
                val saved = VoiceStore.all(c).single { it.title == title }
                assertTrue(saved.recording.isNotEmpty())
                assertTrue("Saved memo contains audio", File(c.filesDir, "voice-recordings/${saved.recording}").length() > 100)
                assertTrue("Memo uses the chosen five-minute delay", saved.due!! in (System.currentTimeMillis() + 240000)..(System.currentTimeMillis() + 360000))
                val resumedBy = System.currentTimeMillis() + 10000
                while (!VoiceListenerService.ready && System.currentTimeMillis() < resumedBy) Thread.sleep(100)
                assertTrue("Activation resumes after saving", VoiceListenerService.ready && p.getBoolean("running", false))
            }
        } finally {
            VoiceListenerService.pause(c)
            VoiceStore.all(c).filter { it.title == title }.forEach { VoiceStore.remove(c, it.id) }
            if (previousMemo == null) p.edit().remove("shake_memo").commit() else p.edit().putBoolean("shake_memo", previousMemo).commit()
        }
    }
    @Test fun memoSpokenTimeResultSchedulesAudioAndRejectsAmbiguity() {
        val title = "Spoken memo test ${UUID.randomUUID()}"
        try {
            ActivityScenario.launch<VoiceTasksActivity>(Intent(c, VoiceTasksActivity::class.java).putExtra("memo", true)).use {
                val recordingBy = System.currentTimeMillis() + 7000
                while (true) {
                    try { onView(withText("Recording voice memo")).check(matches(isDisplayed())); break }
                    catch (e: androidx.test.espresso.NoMatchingViewException) {
                        if (System.currentTimeMillis() > recordingBy) throw e
                        Thread.sleep(150)
                    }
                }
                Thread.sleep(1200)
                onView(withText("Finish")).perform(click())
                // Exercise the actual duration-result receiver, without needing someone to speak during tests.
                val listeningBy = System.currentTimeMillis() + 18000
                while (true) {
                    try { onView(withText("Listening… say five minutes or five hours")).inRoot(isDialog()).check(matches(isDisplayed())); break }
                    catch (e: androidx.test.espresso.NoMatchingViewException) {
                        if (System.currentTimeMillis() > listeningBy) throw e
                        Thread.sleep(150)
                    }
                }
                c.sendBroadcast(Intent(VoiceListenerService.EVENT).setPackage(c.packageName).putExtra("kind", "duration").putExtra("text", "five"))
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                onView(withText("When should I remind you?")).check(matches(isDisplayed()))
                assertFalse(VoiceStore.all(c).any { it.title == title })
                val spokenTime = Model(VoiceModel.prepare(c)).use { model ->
                    Recognizer(model, 16000f).use { recognizer ->
                        val pcm = fixturePcm("reminder-duration.wav")
                        recognizer.acceptWaveForm(pcm, pcm.size)
                        JSONObject(recognizer.finalResult).getString("text")
                    }
                }
                assertEquals("Recognized duration: $spokenTime", 18000000L, VoiceCommand.reminderDelayMillis(spokenTime))
                c.sendBroadcast(Intent(VoiceListenerService.EVENT).setPackage(c.packageName).putExtra("kind", "duration").putExtra("text", spokenTime))
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                onView(withHint("Task title")).inRoot(isDialog()).perform(replaceText(title))
                closeSoftKeyboard()
                onView(withText("Save task")).perform(click())
                val saved = VoiceStore.all(c).single { it.title == title }
                assertTrue(saved.speak && saved.recording.isNotEmpty())
                assertTrue(saved.due!! in (System.currentTimeMillis() + 17900000)..(System.currentTimeMillis() + 18100000))
            }
        } finally {
            VoiceListenerService.pause(c)
            VoiceStore.all(c).filter { it.title == title }.forEach { VoiceStore.remove(c, it.id) }
        }
    }
    @Test fun dictatedFragmentCanBeReviewedAndSavedWithReminder() {
        val title = "Voice save test ${UUID.randomUUID()}"
        try {
            ActivityScenario.launch<VoiceTasksActivity>(Intent(c, VoiceTasksActivity::class.java).putExtra("transcript", "$title in five minutes")).use {
                assertFalse(VoiceStore.all(c).any { it.title == title })
                closeSoftKeyboard()
                onView(withText("Save / review task")).perform(scrollTo(), click())
                assertFalse("Opening review must not save", VoiceStore.all(c).any { it.title == title })
                onView(withText("Save task")).perform(click())
                val saved = VoiceStore.all(c).single { it.title == title }
                assertTrue("The reviewed reminder must retain its time", saved.due!! in (System.currentTimeMillis() + 240000)..(System.currentTimeMillis() + 360000))
            }
        } finally { VoiceStore.all(c).filter { it.title == title }.forEach { VoiceStore.remove(c, it.id) } }
    }
    @Test fun unrecognizedDictationCanBeSavedAsTaskWithoutInventingReminder() {
        val title = "Plain voice test ${UUID.randomUUID()} sometime"
        try {
            ActivityScenario.launch<VoiceTasksActivity>(Intent(c, VoiceTasksActivity::class.java).putExtra("transcript", title)).use {
                closeSoftKeyboard()
                onView(withText("Save / review task")).perform(scrollTo(), click())
                assertFalse(VoiceStore.all(c).any { it.title == title })
                onView(withText("Save task")).perform(click())
                assertNull(VoiceStore.all(c).single { it.title == title }.due)
            }
        } finally { VoiceStore.all(c).filter { it.title == title }.forEach { VoiceStore.remove(c, it.id) } }
    }
    @Test fun bundledModelRecognizesAudioWithoutSpeechProviderOrNetwork() {
        val root = File(c.cacheDir, "voice-model-test-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            ZipInputStream(c.assets.open("voice/model-en-us.zip")).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val path = entry.name.substringAfter('/', "")
                    if (path.isEmpty()) continue
                    val file = File(root, path)
                    require(file.canonicalPath.startsWith(root.canonicalPath + File.separator))
                    if (entry.isDirectory) file.mkdirs() else { file.parentFile?.mkdirs(); file.outputStream().use { zip.copyTo(it) } }
                }
            }
            Model(root.path).use { model ->
                Recognizer(model, 16000f).use { recognizer ->
                    val wave = InstrumentationRegistry.getInstrumentation().context.assets.open("reminder-command.wav").use { it.readBytes() }
                    var offset = 12
                    var pcm = byteArrayOf()
                    while (offset + 8 <= wave.size) {
                        val name = String(wave, offset, 4, Charsets.US_ASCII)
                        val count = java.nio.ByteBuffer.wrap(wave, offset + 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
                        if (name == "data") { pcm = wave.copyOfRange(offset + 8, offset + 8 + count); break }
                        offset += 8 + count + count % 2
                    }
                    assertTrue(pcm.isNotEmpty())
                    recognizer.acceptWaveForm(pcm, pcm.size)
                    val text = JSONObject(recognizer.finalResult).getString("text")
                    android.util.Log.i("VoiceRecognitionTest", "Recognized fixture: $text")
                    assertTrue("Recognized: $text", text.contains("trash"))
                    assertTrue("Recognized: $text", text.contains("five minutes"))
                    val result = try { VoiceCommand.parseSpoken(text) } catch (e: Exception) { throw AssertionError("Could not interpret recognized fixture: $text", e) }
                    assertEquals("take out the trash", result.draft.title)
                    if (text.contains("and five minutes")) assertNotNull("Uncertain speech must require review", result.correction)
                }
                Recognizer(model, 16000f, "[\"hey reminder\", \"[unk]\"]").use { assertNotNull(it) }
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun reminderDeliveredOnceSnoozedAndCompleted() {
        val t = VoiceTask(title = "InstallLock test ${UUID.randomUUID()}", due = System.currentTimeMillis() + 2500)
        try {
            VoiceStore.save(c, t)
            val manager = c.getSystemService(NotificationManager::class.java)
            val deadline = System.currentTimeMillis() + 15000
            while (System.currentTimeMillis() < deadline && manager.activeNotifications.none { it.tag == t.id }) Thread.sleep(250)
            assertTrue("Scheduled alarm must produce a notification", manager.activeNotifications.any { it.tag == t.id })
            assertNull("Duplicate delivery is ignored", VoiceStore.claim(c, t.id, t.due!!))
            VoiceStore.snooze(c, t.id, 10)
            assertTrue(VoiceStore.all(c).first { it.id == t.id }.snoozeUntil!! > System.currentTimeMillis())
            assertNull("Original delivery cannot bypass snooze", VoiceStore.claim(c, t.id, t.due))
            VoiceStore.complete(c, t.id)
            assertTrue(VoiceStore.all(c).first { it.id == t.id }.done)
            assertFalse(manager.activeNotifications.any { it.tag == t.id })
        } finally { VoiceStore.remove(c, t.id) }
    }
    @Test fun backupValidationDoesNotEraseExistingTasks() {
        val task = VoiceTask(title = "Backup test ${UUID.randomUUID()}", notes = "Line 1\nLine 2", list = "Testing")
        try {
            VoiceStore.save(c, task)
            val before = VoiceStore.all(c)
            assertThrows(Exception::class.java) { VoiceStore.import(c, "{\"version\":1,\"tasks\":[{\"id\":\"bad\"}]}") }
            assertEquals(before, VoiceStore.all(c))
            VoiceStore.import(c, VoiceStore.export(c))
            assertEquals(task, VoiceStore.all(c).first { it.id == task.id })
        } finally { VoiceStore.remove(c, task.id) }
    }
    @Test fun screenProvidesCaptureAndTaskControls() {
        ActivityScenario.launch<VoiceTasksActivity>(Intent(c, VoiceTasksActivity::class.java)).use { scenario ->
            scenario.onActivity { activity ->
                fun all(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
                val views = all(activity.findViewById(android.R.id.content))
                assertTrue(views.filterIsInstance<Button>().any { it.text.toString() == "Dictate offline" })
                assertTrue(views.filterIsInstance<EditText>().any { it.hint.toString().startsWith("Remind me") })
            }
        }
    }
}
