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
import android.widget.Toast
import android.app.AlertDialog
import android.view.WindowInsets
import android.widget.FrameLayout
class MainActivity : Activity() {

    companion object {
        // Survives rotation; cleared when the app leaves the screen
        private var authed = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)   // consistent edge-to-edge; show() adds the padding
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
    /** Shows [content] padded so it stays clear of the status bar, navigation bar,
     *  camera cutout and keyboard. */
    private fun show(content: View) {
        val root = FrameLayout(this)
        root.addView(
            content,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsets.CONSUMED
        }
        setContentView(root)
        root.requestApplyInsets()
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
        val shotsBlocked = Policy.isScreenCaptureBlocked(this)
        val hasFilterPw = Store.hasPassword(this, Store.Pw.FILTER)
        screen(
            label("Install Lock is active", 22f),
            label("New apps are suspended for 15 minutes after install."),
            label(status),
            label("Chrome lock: " + (if (Store.chromeLock(this)) "ON (whitelist only)" else "off")),
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
            button("Chrome whitelist") { showChromeSites() },
            button("Google app filter") { showGoogleFilter() },
            button(if (shotsBlocked) "Allow screenshots (all apps)" else "Block screenshots (all apps)") {
                toast(Policy.setScreenCaptureBlocked(this, !shotsBlocked))
                render()
            },
            button(if (hasFilterPw) "Change filter password" else "Set filter password") {
                if (hasFilterPw) showChangeFilterPassword() else showSetFilterPassword()
            },
            button("Change password") { showChangePassword() }
        )
    }

    // ---------- filter password ----------

    /** Asks for the filter password, then runs [action]. Weakening actions go through here. */
    private fun withFilterPassword(title: String, action: () -> Unit) {
        if (!Store.hasPassword(this, Store.Pw.FILTER)) {
            showSetFilterPassword()
            return
        }
        val f = Store.Pw.FILTER
        val pw = passwordField("Filter password")
        val msg = label("")
        screen(
            label(title, 22f),
            label("Enter the filter password."),
            pw, msg,
            button("Confirm") {
                val wait = Store.lockoutRemainingMs(this, f)
                if (wait > 0) {
                    msg.text = "Too many attempts. Try again in ${(wait + 59_999) / 60_000} min."
                } else if (Store.checkPassword(this, pw.text.toString(), f)) {
                    action()
                } else {
                    val w = Store.lockoutRemainingMs(this, f)
                    msg.text = if (w > 0) "Too many attempts. Try again in ${(w + 59_999) / 60_000} min."
                    else "Wrong filter password."
                }
            },
            button("Cancel") { render() }
        )
    }

    private fun showSetFilterPassword() {
        val p1 = passwordField("New filter password")
        val p2 = passwordField("Confirm filter password")
        val msg = label("")
        screen(
            label("Set the filter password", 22f),
            label("A second, separate password. It's needed to turn filters off, unblock domains " +
                    "or add Chrome whitelist sites. It works best if someone else types it in, " +
                    "so only they know it. 8+ characters with a letter and a number."),
            p1, p2, msg,
            button("Save") {
                val a = p1.text.toString()
                when {
                    !Store.isValidNewPassword(a) -> msg.text = "Needs 8+ characters with a letter and a number."
                    a != p2.text.toString() -> msg.text = "Passwords don't match."
                    else -> {
                        Store.setPassword(this, a, Store.Pw.FILTER)
                        render()
                    }
                }
            },
            button("Cancel") { render() }
        )
    }

    private fun showChangeFilterPassword() {
        val f = Store.Pw.FILTER
        val old = passwordField("Current filter password")
        val p1 = passwordField("New filter password")
        val p2 = passwordField("Confirm new filter password")
        val msg = label("")
        screen(
            label("Change filter password", 22f),
            old, p1, p2, msg,
            button("Save") {
                val n = p1.text.toString()
                when {
                    Store.lockoutRemainingMs(this, f) > 0 -> msg.text = "Too many attempts. Try again later."
                    !Store.checkPassword(this, old.text.toString(), f) -> msg.text = "Current filter password is wrong."
                    !Store.isValidNewPassword(n) -> msg.text = "Needs 8+ characters with a letter and a number."
                    n != p2.text.toString() -> msg.text = "Passwords don't match."
                    else -> {
                        Store.setPassword(this, n, f)
                        render()
                    }
                }
            },
            button("Cancel") { render() }
        )
    }

    // ---------- Chrome whitelist ----------

    private fun showChromeSites() {
        val sites = Store.sites(this)
        val now = System.currentTimeMillis()
        val lockOn = Store.chromeLock(this)

        val rows = sites.map {
            if (now >= it.activeAt) "✓  ${it.pattern}"
            else "⏳  ${it.pattern}  (active in ${(it.activeAt - now + 59_999) / 60_000} min)"
        }
        val list = ListView(this)
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, rows)
        list.setOnItemClickListener { _, _, i, _ ->
            val site = sites[i]
            AlertDialog.Builder(this)
                .setMessage("Remove ${site.pattern} from the whitelist?")
                .setPositiveButton("Remove") { _, _ ->
                    Store.removeSite(this, site.pattern)
                    Policy.syncChrome(this)
                    showChromeSites()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        val info = if (lockOn) {
            val b = Policy.otherBrowsers(this)
            "Chrome only opens ✓ sites. " +
                    (if (b.isEmpty()) "No other browsers found." else "Other browsers suspended: ${b.joinToString()}")
        } else {
            "Chrome lock is off, so Chrome is unrestricted. Sites you add still start their 15-minute wait."
        }

        val lockBtn = button(if (lockOn) "Turn Chrome lock OFF (filter password)" else "Turn Chrome lock ON") {
            if (lockOn) {
                withFilterPassword("Turn Chrome lock off") {
                    Policy.setChromeLock(this, false)
                    showChromeSites()
                }
            } else if (!Store.hasPassword(this, Store.Pw.FILTER)) {
                showSetFilterPassword()
            } else {
                if (Store.activePatterns(this).isEmpty()) {
                    toast("No active sites yet: Chrome will block everything until one is active.")
                }
                Policy.setChromeLock(this, true)
                showChromeSites()
            }
        }
        val add = button("Add site (filter password, 15 min wait)") {
            withFilterPassword("Add a site") { showAddSite() }
        }
        val refresh = button("Refresh") { showChromeSites() }
        val back = button("Back") { render() }

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(16), dp(16), dp(16))
        col.addView(label("Chrome whitelist", 22f))
        col.addView(label(info))
        col.addView(lockBtn)
        col.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        col.addView(add)
        col.addView(refresh)
        col.addView(back)
        show(col)
    }

    private fun showAddSite() {
        val site = EditText(this)
        site.hint = "example.com or example.com/path"
        site.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        val msg = label("")
        screen(
            label("Add a site", 22f),
            label("It becomes usable in Chrome ${Store.SITE_DELAY_MS / 60_000} minutes after you add it. " +
                    "A domain also allows its subdomains, and you can add a path to allow only part of a site."),
            site, msg,
            button("Add") {
                val s = Store.normalizeSite(site.text.toString())
                when {
                    s == null -> msg.text = "That doesn't look like a specific site. Use something like example.com."
                    !Store.addSite(this, s) -> msg.text = "$s is already on the list."
                    else -> {
                        msg.text = "Added $s. It will be active in ${Store.SITE_DELAY_MS / 60_000} min."
                        site.setText("")
                    }
                }
            },
            button("Done") { showChromeSites() }
        )
    }    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    private fun showGoogleFilter() {
        val msg = label("")
        screen(
            label("Google app: block images & videos", 22f),
            label("Routes only the Google app's DNS lookups through Install Lock and refuses " +
                    "the servers that serve images and videos. Other apps aren't affected."),
            msg,
            button("Turn on") { msg.text = Policy.enableGoogleFilter(this) },
            button("Turn off (filter password)") {
                withFilterPassword("Turn off the Google app filter") {
                    toast(Policy.disableGoogleFilter(this))
                    showGoogleFilter()
                }
            },
            button("DNS log / tune blocklist") { showDnsLog() },
            button("Back") { render() }
        )
    }

    private fun showDnsLog() {
        val entries = DnsLog.recent()
        val list = ListView(this)
        list.adapter = ArrayAdapter(
            this, android.R.layout.simple_list_item_1,
            entries.map { (if (it.second) "✗  " else "✓  ") + it.first }
        )
        list.setOnItemClickListener { _, _, i, _ ->
            val name = entries[i].first
            val rules = Store.blockedDomains(this).toMutableSet()
            val hits = rules.filter { name == it || name.endsWith(".$it") }
            if (hits.isEmpty()) {
                rules.add(name)
                Store.setBlockedDomains(this, rules)
                toast("Blocking $name")
                showDnsLog()
            } else {
                withFilterPassword("Unblock $name") {
                    rules.removeAll(hits.toSet())
                    Store.setBlockedDomains(this, rules)
                    toast("Unblocked. Removed rule: ${hits.joinToString()}")
                    showDnsLog()
                }
            }
        }
        val refresh = button("Refresh") { showDnsLog() }
        val back = button("Back") { showGoogleFilter() }

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(16), dp(16), dp(16))
        col.addView(label("Google app DNS log", 22f))
        col.addView(label("✗ blocked, ✓ allowed. Tap a name to block or unblock it."))
        col.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        col.addView(refresh)
        col.addView(back)
        show(col)
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
        col.setPadding(dp(16), dp(16), dp(16), dp(16))
        col.addView(label("Choose protected apps", 22f))
        col.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        col.addView(save)
        col.addView(cancel)
        show(col)
    }

    // ---------- tiny UI helpers ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun screen(vararg views: View) {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(16), dp(16), dp(16))
        views.forEach { col.addView(it) }
        val scroll = ScrollView(this)
        scroll.addView(col)
        show(scroll)
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