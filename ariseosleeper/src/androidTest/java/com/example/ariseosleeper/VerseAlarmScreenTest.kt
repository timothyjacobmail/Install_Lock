package com.example.ariseosleeper

import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Run only on an isolated emulator: this exercises the real ringing screen and service. */
class VerseAlarmScreenTest {
    private fun descendants(v: View): List<View> = listOf(v) +
        if (v is ViewGroup) (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else emptyList()

    @Test fun wrongAnswerStaysVisibleAndQuietPreservesInput() {
        val c = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = c.createDeviceProtectedStorageContext().getSharedPreferences("verse_alarm", Context.MODE_PRIVATE)
        val original = prefs.getString("state", null)
        try {
            prefs.edit().clear().commit()
            VerseAlarmStore.addVerse(c, "Original test prose", "Wake up and open the window to begin a new day.", 900)
            val now = AlarmClock.now(c)
            VerseAlarmStore.saveConfig(c, VerseAlarmConfig(true), now.copy(wall = now.wall - 86_400_000))
            VerseAlarmStore.reconcile(c, now)
            ActivityScenario.launch<VerseAlarmActivity>(Intent(c, VerseAlarmActivity::class.java)).use { scenario ->
                scenario.onActivity { activity ->
                    fun views() = descendants(activity.findViewById(android.R.id.content))
                    fun click(text: String) = views().filterIsInstance<TextView>().first { it.text.toString() == text }.performClick()
                    val entry = views().filterIsInstance<EditText>().single()
                    entry.requestFocus()
                    val editor = EditorInfo()
                    assertNotNull(entry.onCreateInputConnection(editor))
                    assertEquals(InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, editor.inputType and InputType.TYPE_MASK_VARIATION)
                    assertTrue(editor.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0)
                    assertTrue(editor.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
                    assertNull(entry.transformationMethod)
                    entry.setText("wrong answer")
                    click("Check my verse")
                    assertTrue(views().filterIsInstance<TextView>().any { it.text.startsWith("That does not match yet") })
                    assertEquals(0, VerseAlarmStore.read(c).verses.single().successes)
                    click("Quiet for 900 seconds")
                    assertEquals("wrong answer", views().filterIsInstance<EditText>().single().text.toString())
                    assertTrue(VerseAlarmStore.read(c).challenge!!.quietUsed)
                }
            }
        } finally {
            c.stopService(Intent(c, VerseAlarmService::class.java))
            prefs.edit().clear().putString("state", original).commit()
            VerseAlarmScheduler.reschedule(c)
        }
    }

    @Test fun settingsSaveCustomSessionsQuietTimeAndMultipleAlarmEdits() {
        val c = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = c.createDeviceProtectedStorageContext().getSharedPreferences("verse_alarm", Context.MODE_PRIVATE)
        val original = prefs.getString("state", null)
        try {
            prefs.edit().clear().commit()
            VerseAlarmStore.addVerse(c, "Test passage", "Begin today with a clear mind.")
            VerseAlarmStore.saveConfig(c, VerseAlarmConfig(additionalTimes = listOf(VerseAlarmTime(18, 0))))
            ActivityScenario.launch<VerseAlarmActivity>(Intent(c, VerseAlarmActivity::class.java)).use { scenario ->
                scenario.onActivity { activity ->
                    fun views() = descendants(activity.findViewById(android.R.id.content))
                    fun click(text: String) = views().filterIsInstance<TextView>().first { it.text.toString() == text }.performClick()
                    fun field(label: String): EditText {
                        val id = views().filterIsInstance<TextView>().first { it.text.toString() == label }.labelFor
                        return activity.findViewById(id)
                    }
                    field("Number of sessions per alarm (1-20)").setText("5")
                    click("Remove alarm 1")
                    click("Save alarm settings")
                    assertEquals(5, VerseAlarmStore.read(c).config.sessions)
                    assertEquals(listOf(VerseAlarmTime(18, 0)), VerseAlarmStore.read(c).config.times)
                    field("Quiet time for Test passage (seconds)").setText("125")
                    click("Save quiet time for Test passage")
                    assertEquals(125, VerseAlarmStore.read(c).verses.single().quietSeconds)
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    val fields = descendants(activity.findViewById(android.R.id.content)).filterIsInstance<EditText>()
                    assertEquals("5", fields.first { it.hint == "Sessions per alarm (1-20)" }.text.toString())
                    assertTrue(fields.any { it.text.toString() == "125" })
                }
            }
        } finally {
            prefs.edit().clear().putString("state", original).commit()
            VerseAlarmScheduler.reschedule(c)
        }
    }
}
