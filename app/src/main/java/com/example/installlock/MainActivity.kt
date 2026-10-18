package com.example.installlock

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    companion object {
        // Survives rotation; cleared when the app leaves the screen
        private var authed = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Policy.isOwner(this)) LockService.start(this)
    }

    override fun onStart() {
        super.onStart()
        render()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) authed = false
    }

    private fun render() {
        when {
            !Policy.isOwner(this) -> screen(
                label("Not set up as device owner yet.\nProvision via QR code (or adb when testing).")
            )
            !Store.hasPassword(this) -> showSetPassword()
            !authed -> showLogin()
            else -> showDashboard()
        }
    }

    // ---------- screens ----------

    private fun showSetPassword() {
        val p1 = passwordField("New password")
        val p2 = passwordField("Confirm password")
        val msg = label("")
        screen(
            label("Set a password", 22f),
            label("At least 8 characters, with at least one letter and one number. " +
                    "You'll need it to unlock YouTube, Chrome and any other protected app."),
            p1, p2, msg,
            button("Save password") {
                val a = p1.text.toString()
                when {
                    !Store.isValidNewPassword(a) -> msg.text = "Needs 8+ characters with a letter and a number."
                    a != p2.text.toString() -> msg.text = "Passwords don't match."
                    else -> {
                        Store.setPassword(this, a)
                        authed = true
                        Policy.sweep(this)
                        render()
                    }
                }
            }
        )
    }

    private fun showLogin() {
        val pw = passwordField("Password")
        val msg = label("")
        fun waitText(ms: Long) = "Too many attempts. Try again in ${(ms + 59_999) / 60_000} min."
        screen(
            label("Install Lock", 22f),
            label("Enter your password."),
            pw, msg,
            button("Unlock") {
                val wait = Store.lockoutRemainingMs(this)
                if (wait > 0) {
                    msg.text = waitText(wait)
                } else if (Store.checkPassword(this, pw.text.toString())) {
                    authed = true
                    render()
                } else {
                    val w = Store.lockoutRemainingMs(this)
                    msg.text = if (w > 0) waitText(w) else "Wrong password."
                }
            }
        )
    }

    private fun showDashboard() {
        val remaining = Store.unlockedUntil(this) - System.currentTimeMillis()
        val status = if (remaining > 0)
            "Protected apps are unlocked for another ${(remaining + 59_999) / 60_000} min."
        else
            "Protected apps are locked."
        screen(
            label("Install Lock is active", 22f),
            label("New apps are suspended for 15 minutes after install."),
            label(status),
            button("Unlock protected apps for ${Store.UNLOCK_MS / 60_000} min") {
                Store.setUnlockedUntil(this, System.currentTimeMillis() + Store.UNLOCK_MS)
                Policy.sweep(this)
                render()
            },
            button("Lock protected apps now") {
                Store.setUnlockedUntil(this, 0L)
                Policy.sweep(this)
                render()
            },
            button("Choose protected apps") { showAppPicker() },
            button("Change password") { showChangePassword() }
        )
    }

    private fun showChangePassword() {
        val old = passwordField("Current password")
        val p1 = passwordField("New password")
        val p2 = passwordField("Confirm new password")
        val msg = label("")
        screen(
            label("Change password", 22f),
            old, p1, p2, msg,
            button("Save") {
                val n = p1.text.toString()
                when {
                    Store.lockoutRemainingMs(this) > 0 -> msg.text = "Too many attempts. Try again later."
                    !Store.checkPassword(this, old.text.toString()) -> msg.text = "Current password is wrong."
                    !Store.isValidNewPassword(n) -> msg.text = "Needs 8+ characters with a letter and a number."
                    n != p2.text.toString() -> msg.text = "Passwords don't match."
                    else -> {
                        Store.setPassword(this, n)
                        render()
                    }
                }
            },
            button("Cancel") { render() }
        )
    }

    private fun showAppPicker() {
        val pm = packageManager
        val home = pm.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
        )?.activityInfo?.packageName
        val apps = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        )
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != packageName && it.first != home }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }

        val current = Store.protectedApps(this)
        val list = ListView(this)
        list.choiceMode = ListView.CHOICE_MODE_MULTIPLE
        list.adapter = ArrayAdapter(
            this, android.R.layout.simple_list_item_multiple_choice, apps.map { it.second }
        )
        apps.forEachIndexed { i, a -> list.setItemChecked(i, a.first in current) }

        val save = button("Save") {
            val chosen = apps.indices.filter { list.isItemChecked(it) }.map { apps[it].first }.toSet()
            Store.setProtectedApps(this, chosen)
            Policy.release(this, current - chosen)
            Policy.sweep(this)
            render()
        }
        val cancel = button("Cancel") { render() }

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(48), dp(16), dp(16))
        col.addView(label("Choose protected apps", 22f))
        col.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        col.addView(save)
        col.addView(cancel)
        setContentView(col)
    }

    // ---------- tiny UI helpers ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun screen(vararg views: View) {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(24), dp(48), dp(24), dp(24))
        views.forEach { col.addView(it) }
        val scroll = ScrollView(this)
        scroll.addView(col)
        setContentView(scroll)
    }

    private fun label(t: String, size: Float = 16f): TextView {
        val v = TextView(this)
        v.text = t
        v.textSize = size
        v.setPadding(0, dp(8), 0, dp(8))
        return v
    }

    private fun passwordField(hintText: String): EditText {
        val v = EditText(this)
        v.hint = hintText
        v.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        return v
    }

    private fun button(t: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = t
        b.setOnClickListener { onClick() }
        return b
    }
}