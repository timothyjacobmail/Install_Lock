package com.example.installlock

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import java.util.concurrent.Executors

/** Internal vault handoff. No password is saved or shown. */
class DelayedUnlockActivity : AppCompatActivity() {
    companion object { private val executor = Executors.newSingleThreadExecutor() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val incoming = intent
        val id = incoming.getStringExtra("request_id").orEmpty()
        val password = incoming.getStringExtra("password").orEmpty()
        val expires = incoming.getLongExtra("expires_elapsed", 0)
        val boot = incoming.getIntExtra("boot_count", -1)
        intent = Intent() // Do not retain the secret in the activity intent or saved state.
        val message = TextView(this).apply { text = "Checking the requested task…"; textSize = 18f }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            addView(message)
        }
        layout.addView(MaterialButton(this).apply {
            text = "Return to Install Lock"
            setOnClickListener {
                startActivity(Intent(this@DelayedUnlockActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                finish()
            }
        })
        val scroll = ScrollView(this).apply { addView(layout) }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(scroll)
        if (savedInstanceState != null || incoming.action != DelayedSecrets.RESULT || password.isBlank()) {
            message.text = "This request is no longer available. Request the password again from Install Lock."
            return
        }
        val task = DelayedSecrets.take(this, id)
        fun valid() = SecretDeliveryWindow.valid(expires, boot, SystemClock.elapsedRealtime(),
            Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -2))
        if (task == null || !valid()) {
            message.text = "This unlock request expired or was already used. Request it again from Install Lock."
            return
        }
        executor.execute {
            val success = try { valid() && Policy.isOwner(applicationContext) && task.execute(applicationContext, password) }
                catch (_: Exception) { false }
            if (success) Policy.sweep(applicationContext)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (success && task.scope == DelayedTask.DASHBOARD) {
                    startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                    finish()
                } else {
                    message.text = if (success) "Completed: ${task.title}\nOnly the requested task was authorized."
                        else "Could not complete: ${task.title}\nCheck that Install Lock is still device owner, the secret matches this task, the task is not locked out, and the requested change is still valid."
                }
            }
        }
    }
}
