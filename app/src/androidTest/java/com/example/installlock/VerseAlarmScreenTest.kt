package com.example.installlock

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
            VerseAlarmStore.addVerse(c, "Original test prose", "Wake up and open the window to begin a new day.")
            val now = WatchdogStore.clock(c)
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
                    click("Quiet for one minute")
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
}
