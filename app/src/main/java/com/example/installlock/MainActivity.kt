package com.example.installlock

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowInsets
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import java.text.DateFormat
import java.util.Date
class MainActivity : Activity() {

    companion object {
        // Cleared when the app leaves the screen
        private var authed = false
    }

    // ---------- navigation ----------

    private val history = ArrayDeque<() -> Unit>()
    private var current: (() -> Unit)? = null

    /** Go forward to a new screen. Back returns to the one we left. */
    private fun goTo(screen: () -> Unit) {
        current?.let { history.addLast(it) }
        current = screen
        screen()
    }

    private fun unlockStatus(): String {
        val until = Store.unlockedUntil(this)
        val left = until - System.currentTimeMillis()
        return if (left > 0)
            "Protected apps are unlocked until " +
                    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(until)) +
                    " (${(left + 59_999) / 60_000} min left)."
        else
            "Protected apps are locked."
    }
    /** Swap the current screen for another, leaving no history entry for it. */
    private fun swapTo(screen: () -> Unit) {
        current = screen
        screen()
    }

    /** Redraw the current screen in place. */
    private fun reopen() {
        current?.invoke()
    }

    /** Returns false if there is no previous screen. */
    private fun goBack(): Boolean {
        val prev = history.removeLastOrNull() ?: return false
        current = prev
        prev()
        return true
    }

    /** Back button on screen: previous screen, or the top screen if there isn't one. */
    private fun back() {
        if (!goBack()) render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)   // consistent edge-to-edge; show() adds the padding
        if (Policy.isOwner(this)) LockService.start(this)
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) { if (!goBack()) finish() }
        }
    }

    @Suppress("OVERRIDE_DEPRECATION", "GestureBackNavigation")
    override fun onBackPressed() {
        if (!goBack()) super.onBackPressed()
    }

    override fun onStart() {
        super.onStart()
        render()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            authed = false
            history.clear()
            current = null
        }
    }

    private fun curboxStatus(): String {
        val running = Policy.curboxRunning(this)
        val installed = Policy.isInstalled(this, Policy.CURBOX)
        return when {
            Store.curboxGuard(this) && running -> "Curbox guard: ON. Curbox is running."
            Store.curboxGuard(this) ->
                "Curbox guard: ON. Curbox is NOT running, so YouTube and Instagram are paused."
            !installed -> "Curbox guard: off. Curbox isn't installed."
            else -> "Curbox guard: off. Curbox is " + (if (running) "running." else "installed but not running.")
        }
    }

    private fun showNoUninstallPicker() {
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

        val selected = Store.noUninstall(this)
        val hidden = selected - apps.map { it.first }.toSet()   // entries without a launcher icon stay as they are
        val list = ListView(this)
        list.choiceMode = ListView.CHOICE_MODE_MULTIPLE
        list.adapter = ArrayAdapter(
            this, android.R.layout.simple_list_item_multiple_choice, apps.map { it.second }
        )
        apps.forEachIndexed { i, a -> list.setItemChecked(i, a.first in selected) }

        val save = button("Save") {
            val chosen = apps.indices.filter { list.isItemChecked(it) }.map { apps[it].first }.toSet() + hidden
            val removed = selected - chosen
            val commit = {
                Store.setNoUninstall(this, chosen)
                Policy.allowUninstall(this, removed)
                Policy.sweep(this)
            }
            if (removed.isNotEmpty()) {
                withFilterPassword("Allow uninstalling again") {
                    commit()
                    repeat(2) { back() }
                }
            } else {
                commit()
                back()
            }
        }
        val cancel = button("Cancel") { back() }

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(16), dp(16), dp(16))
        col.addView(label("Prevent-uninstall apps", 22f))
        col.addView(label("Ticked apps can't be uninstalled, force-stopped or have their data cleared. " +
                "Install Lock is always protected. Unticking an app needs the filter password."))
        col.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        col.addView(save)
        col.addView(cancel)
        show(col)
    }
    /** The top screen for the current state. Clears the history. */
    private fun render() {
        history.clear()
        current = null
        when {
            !Policy.isOwner(this) -> goTo {
                screen(label("Not set up as device owner yet.\nProvision via QR code (or adb when testing)."))
            }
            !Store.hasPassword(this) -> goTo { showSetPassword() }
            !authed -> goTo { showLogin() }
            else -> goTo { showDashboard() }
        }
    }

    // ---------- main screens ----------

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
        val status = label(unlockStatus())
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
            },
            status,
            button("Lock protected apps now") {
                Store.setUnlockedUntil(this, 0L)
                Policy.sweep(this)
                reopen()
            }
        )
    }

    private fun showDashboard() {
        val shotsBlocked = Policy.isScreenCaptureBlocked(this)
        val hasFilterPw = Store.hasPassword(this, Store.Pw.FILTER)

        val minutes = EditText(this)
        minutes.inputType = InputType.TYPE_CLASS_NUMBER
        minutes.hint = "Minutes"
        minutes.setText(Store.lastUnlockMinutes(this).toString())

        val presets = LinearLayout(this)
        presets.orientation = LinearLayout.HORIZONTAL
        for (m in listOf(15, 30, 60, 120)) {
            presets.addView(
                button("$m") { minutes.setText(m.toString()) },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
        }

        screen(
            label("Install Lock is active", 22f),
            label("New apps are suspended for 15 minutes after install."),
            label(unlockStatus()),
            label("Chrome lock: " + (if (Store.chromeLock(this)) "ON (whitelist only)" else "off")),
            label("Unlock for how many minutes? (max ${Store.MAX_UNLOCK_MIN})"),
            label(curboxStatus()),
            minutes,
            presets,
            button("Unlock protected apps") {
                val m = minutes.text.toString().toIntOrNull()
                if (m == null || m < 1 || m > Store.MAX_UNLOCK_MIN) {
                    toast("Enter 1 to ${Store.MAX_UNLOCK_MIN} minutes.")
                } else {
                    Store.setLastUnlockMinutes(this, m)
                    Store.setUnlockedUntil(this, System.currentTimeMillis() + m * 60_000L)
                    Policy.sweep(this)
                    reopen()
                }
            },
            button("Lock protected apps now") {
                Store.setUnlockedUntil(this, 0L)
                Policy.sweep(this)
                reopen()
            },
            button("Choose protected apps") { goTo { showAppPicker() } },
            button("Never-install list") { goTo { showNeverInstall() } },
            button("Chrome whitelist") { goTo { showChromeSites() } },
            button("Google app filter") { goTo { showGoogleFilter() } },
            button(if (shotsBlocked) "Allow screenshots (all apps)" else "Block screenshots (all apps)") {
                toast(Policy.setScreenCaptureBlocked(this, !shotsBlocked))
                reopen()
            },
            button(if (hasFilterPw) "Change filter password" else "Set filter password") {
                if (hasFilterPw) goTo { showChangeFilterPassword() } else goTo { showSetFilterPassword() }
            },
            button("Prevent-uninstall apps") { goTo { showNoUninstallPicker() } },
            button(if (Store.curboxGuard(this)) "Turn Curbox guard OFF (filter password)" else "Turn Curbox guard ON") {
                if (Store.curboxGuard(this)) {
                    withFilterPassword("Turn Curbox guard off") {
                        Store.setCurboxGuard(this, false)
                        Policy.release(this, Policy.CURBOX_GUARDED)
                        Policy.sweep(this)
                        back()
                    }
                } else if (!Policy.curboxRunning(this)) {
                    toast("Curbox's accessibility service isn't running yet. Set up Curbox first.")
                } else {
                    Store.setCurboxGuard(this, true)
                    Policy.sweep(this)
                    reopen()
                }
            },
            button("Change password") { goTo { showChangePassword() } }
        )
    }

    private fun showNeverInstall() {
        val rules = Store.neverInstall(this).sorted()
        val list = ListView(this)
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, rules)
        list.setOnItemClickListener { _, _, i, _ ->
            val rule = rules[i]
            withFilterPassword("Allow \"$rule\" again") {
                Store.setNeverInstall(this, Store.neverInstall(this) - rule)
                back()
            }
        }

        val field = EditText(this)
        field.hint = "App name, package or Play Store link"
        field.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        val msg = label("")
        val add = button("Block this app") {
            val rule = Store.normalizeRule(field.text.toString())
            if (rule == null) {
                msg.text = "Use at least 3 letters or digits: a name, package or Play Store link."
            } else if (rule in Store.neverInstall(this)) {
                msg.text = "Already blocked."
            } else {
                val hits = Policy.matchesFor(this, rule)
                val commit = {
                    Store.setNeverInstall(this, Store.neverInstall(this) + rule)
                    Policy.sweep(this)
                    reopen()
                }
                if (hits.isEmpty()) {
                    commit()
                } else {
                    AlertDialog.Builder(this)
                        .setMessage("These installed apps match and will be uninstalled, with their data:\n\n" +
                                hits.joinToString("\n"))
                        .setPositiveButton("Uninstall and block") { _, _ -> commit() }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
        }
        val backBtn = button("Back") { back() }

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(16), dp(16), dp(16))
        col.addView(label("Never-install list", 22f))
        col.addView(label("Any app whose name or package contains one of these words is suspended " +
                "and uninstalled within seconds of installing, from the Play Store or an APK. " +
                "Tap an entry to remove it (filter password)."))
        col.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        col.addView(field)
        col.addView(msg)
        col.addView(add)
        col.addView(backBtn)
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
                        back()
                    }
                }
            },
            button("Cancel") { back() }
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
            back()
        }
        val cancel = button("Cancel") { back() }

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(16), dp(16), dp(16))
        col.addView(label("Choose protected apps", 22f))
        col.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        col.addView(save)
        col.addView(cancel)
        show(col)
    }

    // ---------- Google app filter ----------

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
                    back()
                }
            },
            button("DNS log / tune blocklist") { goTo { showDnsLog() } },
            button("Back") { back() }
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
                reopen()
            } else {
                withFilterPassword("Unblock $name") {
                    rules.removeAll(hits.toSet())
                    Store.setBlockedDomains(this, rules)
                    toast("Unblocked. Removed rule: ${hits.joinToString()}")
                    back()
                }
            }
        }
        val refresh = button("Refresh") { reopen() }
        val backBtn = button("Back") { back() }

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(16), dp(16), dp(16))
        col.addView(label("Google app DNS log", 22f))
        col.addView(label("✗ blocked, ✓ allowed. Tap a name to block or unblock it."))
        col.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        col.addView(refresh)
        col.addView(backBtn)
        show(col)
    }

    // ---------- filter password ----------

    /** Asks for the filter password, then runs [action]. Weakening actions go through here. */
    private fun withFilterPassword(title: String, action: () -> Unit) {
        if (!Store.hasPassword(this, Store.Pw.FILTER)) {
            goTo { showSetFilterPassword() }
        } else {
            goTo { showFilterGate(title, action) }
        }
    }

    private fun showFilterGate(title: String, action: () -> Unit) {
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
            button("Cancel") { back() }
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
                        back()
                    }
                }
            },
            button("Cancel") { back() }
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
                        back()
                    }
                }
            },
            button("Cancel") { back() }
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
                    reopen()
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
                    back()
                }
            } else if (!Store.hasPassword(this, Store.Pw.FILTER)) {
                goTo { showSetFilterPassword() }
            } else {
                if (Store.activePatterns(this).isEmpty()) {
                    toast("No active sites yet: Chrome will block everything until one is active.")
                }
                Policy.setChromeLock(this, true)
                reopen()
            }
        }
        val add = button("Add site (filter password, 15 min wait)") {
            withFilterPassword("Add a site") { swapTo { showAddSite() } }
        }
        val refresh = button("Refresh") { reopen() }
        val backBtn = button("Back") { back() }

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(16), dp(16), dp(16))
        col.addView(label("Chrome whitelist", 22f))
        col.addView(label(info))
        col.addView(lockBtn)
        col.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        col.addView(add)
        col.addView(refresh)
        col.addView(backBtn)
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
            button("Done") { back() }
        )
    }

    // ---------- UI helpers ----------

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

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

    private fun screen(vararg views: View) {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(24), dp(16), dp(24), dp(24))
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