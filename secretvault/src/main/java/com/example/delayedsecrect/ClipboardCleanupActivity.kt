package com.example.delayedsecrect

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.TextView

/** Android requires focus to verify which clip will be removed. No password is displayed. */
class ClipboardCleanupActivity : Activity() {
    companion object {
        fun openIntent(context: Context) = Intent(context, ClipboardCleanupActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SecretClipboard.initialize(this)
        setContentView(TextView(this).apply {
            text = "Clearing copied password…"
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        })
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            SecretClipboard.clearFromFocusedActivity(this)
            finishAndRemoveTask()
        }
    }
}
