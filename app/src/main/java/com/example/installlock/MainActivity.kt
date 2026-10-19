package com.example.installlock

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Switch
import android.widget.Spinner
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ProgressBar
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.Future
class MainActivity : Activity() {

    companion object {
        // Cleared when the app leaves the screen
        private var authed = false
        private val shareLinkExecutor = Executors.newSingleThreadExecutor()
    }

    // ---------- navigation ----------

    private val history = ArrayDeque<() -> Unit>()
    private var current: (() -> Unit)? = null
    private var receivedSiteShare = false
    private var sharedSiteOptions = emptyList<String>()
    private var sharedSiteText: String? = null
    private var shareLinkTask: Future<*>? = null
    private var settingsAuthed = false
    private val sectionStates = mutableMapOf<String, Boolean>()

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
            "App-lock timer: temporarily unlocked until " +
                    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(until)) +
                    " (${(left + 59_999) / 60_000} min left). Other protections can still lock an app."
        else
            "App-lock timer: no temporary unlock is active."
    }

    private fun chromeLockExplanation(statuses: List<AppLockStatus>): String =
        statuses.firstOrNull { it.packageName == Policy.CHROME }?.explanation()
            ?: "Chrome is not installed."

    private fun playAccessSummary(): String {
        val selected = Store.immediatePlayCategories(this)
        val titles = PlayInstallCategory.entries.filter { it.storeId in selected }.map { it.displayName }
        return if (titles.isEmpty()) "No Play Store categories skip the new-app waiting period."
        else "Immediate Play Store access: ${titles.joinToString()}. Category verification needs internet; other locks still apply."
    }

    private fun browserActions() = section("Browser & Google", "Website rules, keyword filtering and Google VPN",
        button("Ordered domain rules") { goTo { showDomainRules() } },
        button("Chrome whitelist") { goTo { showChromeSites() } },
        button("Extra locked sites") { goTo { showExtraLockedSites() } },
        button("Browser & Google keyword filter") { goTo { showBrowserKeywords() } },
        button("Google VPN filter") { goTo { showGoogleFilter() } },
        button("Start a new Google search") { startActivity(GoogleSearchRecoveryActivity.openIntent(this)) })

    /** Read-only and available before login, so a blocked browser always has a recovery explanation. */
    private fun showLockReasons() {
        val statuses = Policy.lockStatuses(this)
        val views = mutableListOf<View>(
            label("Why apps are locked", 22f),
            label("Expand an app to see its lock reasons and recovery steps."),
            label("Checked at " + DateFormat.getTimeInstance().format(Date())),
            button("Refresh") { reopen() },
            section("Recovery options", "Settings and filters that can keep an app locked",
            button("Android accessibility settings") {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
            button("App locks") { goTo { showAppPicker() } },
            button("Ordered domain rules") { goTo { showDomainRules() } },
            button("Browser & Google keyword filter") { goTo { showBrowserKeywords() } },
            button("Google VPN filter") { goTo { showGoogleFilter() } },
            button("Chrome whitelist") { goTo { showChromeSites() } },
            button("Extra locked sites") { goTo { showExtraLockedSites() } }),
            section("How lock rules work", "Install waits and website restrictions",
                label("Each active lock reason must be cleared before an app can open. Tap Refresh after changing a setting or waiting."),
                label(playAccessSummary()),
                label("Category checks may take a few seconds. Unverified apps keep the normal install wait."))
        )
        val websiteRules = mutableListOf<View>()
        if (Store.chromeLock(this)) websiteRules.add(label(
            "Chrome website restriction: only active whitelist entries can open. " +
                    "A site that is missing or still waiting will be blocked even when Chrome itself is unlocked. " +
                    "Open Chrome whitelist to see each site's activation time."
        ))
        val extraSites = Store.extraLockedSites(this)
        if (extraSites.isNotEmpty()) websiteRules.add(label(
            "Chrome extra site locks: ${extraSites.joinToString { it.domain }}. " +
                    "These override whitelist entries and remain active until their removal wait ends. " +
                    "Open Extra locked sites to see removal deadlines."
        ))
        if (websiteRules.isNotEmpty()) views.add(section("Website rules", "Chrome whitelist and extra site locks", *websiteRules.toTypedArray()))
        if (statuses.none { it.needsAttention }) views.add(label("No app is currently locked by Install Lock."))
        for (status in statuses) {
            val summary = when {
                status.reasons.shouldLock && status.suspended == true -> "Locked"
                status.reasons.shouldLock -> "Lock pending"
                status.suspended == true -> "Release pending"
                status.suspended == null -> "Status unavailable"
                else -> "Accessible"
            }
            views.add(section(status.appName, summary, label(status.explanation()), label(status.packageName, 12f)))
        }
        views.add(button("Back") { back() })
        screen(*views.toTypedArray())
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
        if (!goBack()) {
            if (receivedSiteShare) finish() else render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readSharedSite(intent)
        window.setDecorFitsSystemWindows(false)   // consistent edge-to-edge; show() adds the padding
        if (Policy.isOwner(this)) LockService.start(this)
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) { if (!goBack()) finish() }
        }
    }

    private fun readSharedSite(incoming: Intent) {
        shareLinkTask?.cancel(true)
        receivedSiteShare = incoming.action == Intent.ACTION_SEND && incoming.type == "text/plain"
        sharedSiteText = if (receivedSiteShare) listOfNotNull(
            incoming.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(),
            incoming.getStringExtra(Intent.EXTRA_SUBJECT)
        ).joinToString("\n") else null
        sharedSiteOptions = SharedSite.whitelistOptions(sharedSiteText)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readSharedSite(intent)
        render()
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
        shareLinkTask?.cancel(true)
        shareLinkTask = null
        settingsAuthed = false
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
                "Curbox guard: ON. Curbox is not running. YouTube and Instagram lock after the grace period. " +
                        "See Why apps are locked for their current status."
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
            receivedSiteShare -> goTo { showSharedSite() }
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
                    "You'll need it to open the dashboard and set up the separate protection passwords."),
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
        val msg = label("").apply { setTextColor(Color.RED) }
        val status = label(unlockStatus())
        val locks = Policy.lockStatuses(this)
        fun waitText(ms: Long) = "Too many attempts. Try again in ${(ms + 59_999) / 60_000} min."
        screen(
            label("Install Lock", 26f),
            label("Enter your main password to open the dashboard."),
            pw, msg,
            button("Unlock") {
                val password = pw.text.toString()
                if (password.isBlank()) {
                    msg.text = "Enter password"
                    return@button
                }
                val wait = Store.lockoutRemainingMs(this)
                if (wait > 0) {
                    msg.text = waitText(wait)
                } else if (Store.checkPassword(this, password)) {
                    authed = true
                    render()
                } else {
                    val w = Store.lockoutRemainingMs(this)
                    msg.text = if (w > 0) waitText(w) else "Wrong password."
                }
            },
            button("Why apps are locked") { goTo { showLockReasons() } },
            browserActions(),
            section("Apps", "Choose apps to lock or end an unlock session",
                button("App locks") { goTo { showAppPicker() } },
                button("Lock protected apps now") {
                    Store.lockProtectedApps(this)
                    Policy.sweep(this)
                    reopen()
                }),
            section("Settings & passwords", "Separate password required for settings",
                button("Settings") { openSettings() }),
            section("Protection status", "Current app and browser protection",
                status, label(chromeLockExplanation(locks)), label(curboxStatus()), label(playAccessSummary()))
        )
    }

    private fun showDashboard() {
        val locks = Policy.lockStatuses(this)
        val shotsBlocked = Policy.isScreenCaptureBlocked(this)
        val hasFilterPw = Store.hasPassword(this, Store.Pw.FILTER)
        val timings = Store.timingConfig(this)
        val maxUnlock = timings[TimingOption.MAX_UNLOCK]

        val minutes = EditText(this)
        minutes.inputType = InputType.TYPE_CLASS_NUMBER
        minutes.hint = "Minutes"
        minutes.setText(Store.lastUnlockMinutes(this).toString())

        val presetRow = LinearLayout(this)
        presetRow.orientation = LinearLayout.HORIZONTAL
        for (m in timings.unlockPresets) {
            presetRow.addView(
                button("$m") { minutes.setText(m.toString()) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            )
        }
        val presets = HorizontalScrollView(this).apply { addView(presetRow) }

        screen(
            label("Install Lock", 26f),
            label(unlockStatus()),
            button("Why apps are locked") { goTo { showLockReasons() } },
            section("Unlock protected apps", "Choose a temporary unlock duration",
            label("Unlock for how many minutes? (max $maxUnlock)"),
            minutes,
            presets,
            button("Unlock protected apps (app-lock password)") {
                val m = minutes.text.toString().toIntOrNull()
                val currentMax = Store.timingConfig(this)[TimingOption.MAX_UNLOCK]
                if (m == null || m < 1 || m > currentMax) {
                    toast("Enter 1 to $currentMax minutes.")
                } else {
                    withAppLockPassword("Unlock protected apps for $m minutes") { password ->
                        Store.unlockProtectedApps(this, m, password)
                    }
                }
            }, initiallyExpanded = true),
            section("Apps", "App locks, install rules and uninstall protection",
            button("Lock protected apps now") {
                Store.lockProtectedApps(this)
                Policy.sweep(this)
                reopen()
            },
            button("App locks") { goTo { showAppPicker() } },
            button("Never-install list") { goTo { showNeverInstall() } },
            button("Prevent-uninstall apps") { goTo { showNoUninstallPicker() } }),
            browserActions(),
            section("Additional protections", "Screenshots and the Curbox guard",
            label(curboxStatus()),
            button(if (shotsBlocked) "Allow screenshots (all apps)" else "Block screenshots (all apps)") {
                toast(Policy.setScreenCaptureBlocked(this, !shotsBlocked))
                reopen()
            },
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
            }),
            section("Settings & passwords", "Durations, Play Store access and separate passwords",
                button("Settings") { openSettings() },
                button("Change main password") { goTo { showChangePassword() } },
                button(if (hasFilterPw) "Change filter password" else "Set filter password") {
                    if (hasFilterPw) goTo { showChangeFilterPassword() } else goTo { showFilterPasswordSetup() }
                },
                button(if (Store.hasPassword(this, Store.Pw.APP_LOCK)) "Change app-lock password" else "Set app-lock password") {
                    configureAppLockPassword()
                }),
            section("Protection status", "Current app and browser protection",
                label(chromeLockExplanation(locks)),
                label("Chrome whitelist: " + if (Store.chromeLock(this)) "ON" else "OFF"),
                label("New-app waiting period: ${timings[TimingOption.INSTALL_WAIT]} minutes."),
                label(playAccessSummary()))
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
        val selected = Store.protectedApps(this)
        val launchable = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        )
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
        // Keep existing selections visible even if their launcher icon has disappeared.
        val existing = selected.map { pkg ->
            val name = try { pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString() }
                catch (_: Exception) { "$pkg (not installed)" }
            pkg to name
        }
        val apps = (launchable + existing)
            .filter { it.first != packageName && it.first != home }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }

        val list = ListView(this)
        list.adapter = object : ArrayAdapter<Pair<String, String>>(this, android.R.layout.simple_list_item_1, apps) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val (pkg, name) = getItem(position)!!
                return Switch(this@MainActivity).apply {
                    text = name
                    textSize = 16f
                    setPadding(dp(8), dp(16), dp(8), dp(16))
                    isChecked = pkg in selected
                    setOnCheckedChangeListener { _, enabled ->
                        if (enabled) {
                            Store.addAppLock(this@MainActivity, pkg)
                            Policy.sweep(this@MainActivity)
                            reopen()
                        } else {
                            // Stay visibly locked until the password has actually been verified.
                            setOnCheckedChangeListener(null)
                            isChecked = true
                            withAppLockPassword("Remove lock from $name") { password ->
                                val removed = Store.removeAppLock(this@MainActivity, pkg, password)
                                if (removed) Policy.release(this@MainActivity, listOf(pkg))
                                removed
                            }
                        }
                    }
                }
            }
        }

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(16), dp(16), dp(16))
        col.addView(label("App locks", 22f))
        col.addView(label("Switch on to lock an app immediately, without a password. " +
                "This ends any temporary unlock session. Switching off requires the separate app-lock password. " +
                "Other install delays and filters can still block an app."))
        col.addView(label(unlockStatus()))
        col.addView(button("Why apps are locked") { goTo { showLockReasons() } })
        col.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        col.addView(button(if (Store.hasPassword(this, Store.Pw.APP_LOCK))
            "Change app-lock password" else "Set app-lock password") { configureAppLockPassword() })
        col.addView(button("Back") { back() })
        show(col)
    }

    private fun withAppLockPassword(title: String, operation: (String) -> Boolean) {
        if (!Store.hasPassword(this, Store.Pw.APP_LOCK)) {
            toast("Set the app-lock password first, then retry this action.")
            configureAppLockPassword()
            return
        }
        goTo {
            showCredentialGate(title, Store.Pw.APP_LOCK, operation) {
                Policy.sweep(this)
                back()
            }
        }
    }

    private fun configureAppLockPassword() {
        if (Store.hasPassword(this, Store.Pw.APP_LOCK) || authed) {
            goTo { showAppLockPasswordEditor() }
        } else {
            goTo {
                showPasswordGate("Set up app-lock password", Store.Pw.MAIN) {
                    swapTo { showAppLockPasswordEditor() }
                }
            }
        }
    }

    private fun showAppLockPasswordEditor() {
        val changing = Store.hasPassword(this, Store.Pw.APP_LOCK)
        val old = passwordField("Current app-lock password")
        val p1 = passwordField("New app-lock password")
        val p2 = passwordField("Confirm app-lock password")
        val msg = label("")
        val views = mutableListOf<View>(
            label(if (changing) "Change app-lock password" else "Set app-lock password", 22f),
            label("This separate password is needed to remove app locks or temporarily unlock apps. " +
                    "Adding a lock is always allowed. Use 8+ characters with a letter and a number.")
        )
        if (changing) views.add(old)
        views.addAll(listOf(p1, p2, msg, button("Save password") {
            val value = p1.text.toString()
            val which = Store.Pw.APP_LOCK
            when {
                !changing && Store.hasPassword(this, which) -> {
                    toast("An app-lock password has already been set.")
                    back()
                }
                !Store.isValidNewPassword(value) -> msg.text = "Needs 8+ characters with a letter and a number."
                value != p2.text.toString() -> msg.text = "Passwords don't match."
                changing && Store.lockoutRemainingMs(this, which) > 0 -> msg.text = "Too many attempts. Try again later."
                changing && !Store.checkPassword(this, old.text.toString(), which) -> msg.text = "Current app-lock password is wrong."
                else -> {
                    Store.setPassword(this, value, which)
                    if (!changing) {
                        Store.lockProtectedApps(this)
                        Policy.sweep(this)
                    }
                    toast("App-lock password saved.")
                    back()
                }
            }
        }, button("Cancel") { back() }))
        screen(*views.toTypedArray())
    }

    // ---------- Google app filter ----------

    private fun showGoogleFilter() {
        val msg = label("")
        val status = label("")
        fun updateStatus() {
            if (!status.isAttachedToWindow) return
            status.text = if (DnsFilterService.isReady(this))
                "Install Lock VPN: running. Other app locks and keyword protection still apply."
            else "Install Lock VPN: not running. Google remains locked until this VPN is running."
            status.postDelayed({ updateStatus() }, 1000)
        }
        screen(
            label("Google VPN filter", 22f),
            label("Routes only the Google app's DNS lookups through Install Lock and refuses " +
                    "the servers that serve images and videos, plus domains blocked by your ordered domain rules. Other apps aren't affected. " +
                    "Google requires this VPN to be running, even during temporary app unlocks and when keyword filtering is off. " +
                    "Turning the VPN off keeps Google locked until you turn it on again."),
            status,
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
        status.post { updateStatus() }
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
            goTo { showFilterPasswordSetup() }
        } else {
            goTo { showPasswordGate(title, Store.Pw.FILTER, action) }
        }
    }

    private fun showPasswordGate(title: String, which: Store.Pw, action: () -> Unit) {
        showCredentialGate(title, which, { Store.checkPassword(this, it, which) }, action)
    }

    private fun showCredentialGate(title: String, which: Store.Pw, verify: (String) -> Boolean, action: () -> Unit) {
        val passwordName = when (which) {
            Store.Pw.MAIN -> "main password"
            Store.Pw.FILTER -> "filter password"
            Store.Pw.SETTINGS -> "settings password"
            Store.Pw.APP_LOCK -> "app-lock password"
        }
        val pw = passwordField(passwordName.replaceFirstChar { it.uppercase() })
        val msg = label("").apply { setTextColor(Color.RED) }
        screen(
            label(title, 22f),
            label("Enter the $passwordName."),
            pw, msg,
            button("Confirm") {
                val password = pw.text.toString()
                if (password.isBlank()) {
                    msg.text = "Enter password"
                    return@button
                }
                val wait = Store.lockoutRemainingMs(this, which)
                if (wait > 0) {
                    msg.text = "Too many attempts. Try again in ${(wait + 59_999) / 60_000} min."
                } else if (verify(password)) {
                    action()
                } else {
                    val w = Store.lockoutRemainingMs(this, which)
                    msg.text = if (w > 0) "Too many attempts. Try again in ${(w + 59_999) / 60_000} min."
                    else "Wrong $passwordName."
                }
            },
            button("Cancel") { back() }
        )
    }

    private fun showFilterPasswordSetup(onSaved: (() -> Unit)? = null) {
        if (authed) {
            showSetFilterPassword(onSaved)
        } else {
            showPasswordGate("Set up filter password", Store.Pw.MAIN) {
                swapTo { showSetFilterPassword(onSaved) }
            }
        }
    }

    private fun showSetFilterPassword(onSaved: (() -> Unit)? = null) {
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
                    Store.hasPassword(this, Store.Pw.FILTER) -> msg.text = "A filter password is already set. Go back and use it."
                    !Store.isValidNewPassword(a) -> msg.text = "Needs 8+ characters with a letter and a number."
                    a != p2.text.toString() -> msg.text = "Passwords don't match."
                    else -> {
                        Store.setPassword(this, a, Store.Pw.FILTER)
                        if (onSaved != null) onSaved() else back()
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

    private fun showSharedSite() {
        val openForm = { swapTo {
            val shortLink = SharedSite.shortLinkFromText(sharedSiteText)
            if (shortLink != null) showResolveSharedSite(shortLink)
            else showAddSite(sharedSiteOptions.firstOrNull().orEmpty(), sharedSiteOptions)
        } }
        if (!Store.hasPassword(this, Store.Pw.FILTER)) {
            showFilterPasswordSetup(onSaved = openForm)
        } else {
            showPasswordGate("Add to Chrome whitelist", Store.Pw.FILTER, openForm)
        }
    }

    private fun showResolveSharedSite(shortLink: String) {
        val status = label("Finding the original website behind Google's shortened link…")
        val fallback = sharedSiteOptions.toList()
        screen(
            label("Add to Chrome whitelist", 22f), status, ProgressBar(this),
            label("This checks the Google sharing link online. The destination page is not opened and nothing is added automatically."),
            button(if (fallback.isEmpty()) "Enter the original address manually" else "Use the website shown in the shared text") {
                shareLinkTask?.cancel(true)
                swapTo { showAddSite(fallback.firstOrNull().orEmpty(), fallback,
                    "Using the website shown in the shared text. Review or edit the address before adding it.") }
            },
            button("Cancel") { shareLinkTask?.cancel(true); back() }
        )
        shareLinkTask = shareLinkExecutor.submit {
            val resolved = GoogleShareResolver().resolve(shortLink)
            val resolvedOptions = resolved?.let { SharedSite.whitelistOptions(it) }.orEmpty()
            runOnUiThread {
                // Don't replace a newer share, an edited form, or a password screen after backgrounding.
                if (!isFinishing && !isDestroyed && status.isAttachedToWindow && hasWindowFocus()) {
                    val options = resolvedOptions.ifEmpty { fallback }
                    val notice = when {
                        resolvedOptions.isNotEmpty() -> "Resolved Google's short link to the original website. Review the address below."
                        fallback.isNotEmpty() -> "Could not resolve the short link. Using the website shown in the shared text; review the address below."
                        else -> "Could not resolve Google's short link. Enter the original website, or turn off “Shorten links to web pages” " +
                                "in Google app → Settings → General and share again."
                    }
                    sharedSiteOptions = options
                    swapTo { showAddSite(options.firstOrNull().orEmpty(), options, notice) }
                }
            }
        }
    }

    private fun showChromeSites() {
        val sites = Store.sites(this)
        val now = System.currentTimeMillis()
        val lockOn = Store.chromeLock(this)
        val siteWait = Store.timingConfig(this)[TimingOption.SITE_WAIT]

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
            "Chrome only opens ✓ sites, except those covered by Extra locked sites. " +
                    (if (b.isEmpty()) "No other browsers found." else "Other browsers suspended: ${b.joinToString()}")
        } else {
            "Chrome whitelist is off. Extra locked sites still apply. " +
                    "Whitelist sites you add still start their $siteWait-minute wait."
        }

        val lockBtn = button(if (lockOn) "Turn Chrome lock OFF (filter password)" else "Turn Chrome lock ON") {
            if (lockOn) {
                withFilterPassword("Turn Chrome lock off") {
                    Policy.setChromeLock(this, false)
                    back()
                }
            } else if (!Store.hasPassword(this, Store.Pw.FILTER)) {
                goTo { showFilterPasswordSetup() }
            } else {
                if (Store.activePatterns(this).isEmpty()) {
                    toast("No active sites yet: Chrome will block everything until one is active.")
                }
                Policy.setChromeLock(this, true)
                reopen()
            }
        }
        val add = button("Add site (filter password, $siteWait min wait)") {
            withFilterPassword("Add a site") { swapTo { showAddSite() } }
        }
        val refresh = button("Refresh") { reopen() }
        val backBtn = button("Back") { back() }

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(16), dp(16), dp(16))
        col.addView(label("Chrome whitelist", 22f))
        col.addView(label(info))
        col.addView(button("Extra locked sites") { goTo { showExtraLockedSites() } })
        col.addView(lockBtn)
        col.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        col.addView(add)
        col.addView(refresh)
        col.addView(backBtn)
        show(col)
    }

    private fun showAddSite(initialSite: String = "", suggestedSites: List<String> = emptyList(), shareNotice: String = "") {
        val siteWait = Store.timingConfig(this)[TimingOption.SITE_WAIT]
        val site = EditText(this)
        site.hint = "example.com or example.com/path"
        site.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        site.setText(initialSite)
        site.setSelection(site.text.length)
        val choices = if (suggestedSites.isNotEmpty()) RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            suggestedSites.forEach { pattern ->
                addView(RadioButton(this@MainActivity).apply {
                    id = View.generateViewId()
                    text = pattern
                    textSize = 16f
                    minHeight = dp(48)
                }, RadioGroup.LayoutParams(RadioGroup.LayoutParams.MATCH_PARENT, RadioGroup.LayoutParams.WRAP_CONTENT))
            }
            setOnCheckedChangeListener { group, checkedId ->
                if (checkedId != -1) {
                    site.setText(group.findViewById<RadioButton>(checkedId).text)
                    site.setSelection(site.text.length)
                }
            }
            check(getChildAt(suggestedSites.indexOf(initialSite).coerceAtLeast(0)).id)
        } else null
        val msg = label(shareNotice.ifEmpty { if (receivedSiteShare && initialSite.isEmpty())
            "No valid website found in the shared text. Enter a domain or path below." else "" })
        val views = mutableListOf<View>(
            label("Add a site", 22f),
            label("It becomes usable in Chrome $siteWait minutes after you add it. " +
                    "A domain also allows its subdomains. Path entries allow URLs that start with that path.")
        )
        if (choices != null) views.addAll(listOf(
            label("Choose how much of this site to allow", 20f),
            label("Listed from the shared path to the whole domain. Select an option, or edit the address below. " +
                    "Query strings and page fragments are omitted."), choices
        ))
        views.addAll(listOf(
            label(if (choices != null) "Selected address (editable)" else "Website address"),
            site, msg,
            button(if (choices != null) "Add selected address" else "Add") {
                val s = Store.normalizeSite(site.text.toString())
                when {
                    s == null -> msg.text = "That doesn't look like a specific site. Use something like example.com."
                    !Store.addSite(this, s) -> msg.text = "$s is already on the list."
                    else -> {
                        Policy.syncChrome(this)
                        msg.text = "Added $s. It will be active in $siteWait min."
                        site.setText("")
                        choices?.clearCheck()
                    }
                }
            },
            button("Done") { back() }
        ))
        screen(*views.toTypedArray())
    }

    // ---------- extra locked sites ----------

    private fun showExtraLockedSites() {
        Policy.syncChrome(this)
        val sites = Store.extraLockedSites(this)
        fun timeLabel(time: Long) = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(time))
        val field = EditText(this).apply {
            hint = "example.com or a website URL"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val msg = label("")
        val views = mutableListOf<View>(
            label("Extra locked sites", 22f),
            label("Adding a site blocks its whole domain and subdomains in Chrome immediately, without a password. " +
                    "These locks override the whitelist and stay on even when the whitelist is off. " +
                    "Removing a site takes a fixed 45 minutes; this wait cannot be changed in Settings. " +
                    "Whitelist rules, keyword filtering and app locks can still restrict access afterward."),
            field, msg,
            button("Lock site immediately") {
                val domain = Store.normalizeExtraSite(field.text.toString())
                when {
                    domain == null -> msg.text = "Enter a specific site, such as example.com."
                    !Store.addExtraLockedSite(this, domain) -> msg.text = "$domain is already locked."
                    else -> {
                        Policy.syncChrome(this)
                        toast("Locked $domain. Any pending removal was cancelled.")
                        reopen()
                    }
                }
            },
            button("Refresh") { reopen() }
        )
        if (sites.isEmpty()) views.add(label("No extra sites are locked."))
        for (site in sites) {
            val title = site.domain + if (site.removeAt != null)
                " — removal at ${timeLabel(site.removeAt)}" else " — locked"
            views.add(button(title) {
                if (site.removeAt != null) {
                    AlertDialog.Builder(this)
                        .setTitle(site.domain)
                        .setMessage("This site stays locked until ${timeLabel(site.removeAt)}. Cancel its removal to keep it locked?")
                        .setPositiveButton("Keep locked") { _, _ ->
                            Store.addExtraLockedSite(this, site.domain)
                            Policy.syncChrome(this)
                            reopen()
                        }
                        .setNegativeButton("Back", null).show()
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("Remove ${site.domain}?")
                        .setMessage("It stays locked for 45 minutes after you request removal, then is removed automatically. " +
                                "You can cancel the removal while you wait.")
                        .setPositiveButton("Start 45-minute wait") { _, _ ->
                            Store.requestExtraSiteRemoval(this, site.domain)
                            Policy.syncChrome(this)
                            reopen()
                        }
                        .setNegativeButton("Cancel", null).show()
                }
            })
        }
        views.add(button("Back") { back() })
        screen(*views.toTypedArray())
    }

    // ---------- ordered domain rules ----------

    private fun withDomainRulePassword(title: String, operation: (String) -> Boolean) {
        if (!Store.hasPassword(this, Store.Pw.FILTER)) {
            toast("Set the filter password first, then retry this action.")
            goTo { showFilterPasswordSetup() }
            return
        }
        goTo {
            showCredentialGate(title, Store.Pw.FILTER, operation) {
                Policy.sweep(this)
                back()
            }
        }
    }

    private fun showDomainRules() {
        val rules = Store.domainRules(this)
        fun choices(titles: List<String>) = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, titles).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
        }
        fun positionField(value: Int) = EditText(this).apply {
            hint = "Position (1 is highest)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(value.toString())
        }
        val action = choices(DomainAction.entries.map { it.title })
        val type = choices(DomainMatchType.entries.map { it.title })
        val value = EditText(this).apply {
            hint = "manga, .org, or example.org"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
        }
        val position = positionField(1)
        val msg = label("").apply { setTextColor(Color.RED) }
        val tester = EditText(this).apply {
            hint = "Domain or URL to test, e.g. manga.org"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
        }
        val result = label("")
        val views = mutableListOf<View>(
            label("Ordered domain rules", 22f),
            label("Rules run from top to bottom. The FIRST matching rule decides: Block or Allow. " +
                    "If no rule matches, this filter allows the domain. Higher position means a smaller number."),
            label("Example: #1 Block domains containing manga, #2 Allow suffix .org: manga.org is blocked. " +
                    "Move Allow .org to #1 and manga.org passes this filter."),
            section("Add a domain rule", "Block instantly; Allow requires the filter password",
                label("Action"), action, label("Match type"), type, value,
                label("Contains checks the hostname only. Suffix .org includes all .org domains; " +
                        "example.org includes that domain and its subdomains. Exact matches only the named domain."),
                label("Position (1 runs first; choose 1 to ${rules.size + 1})"), position, msg, button("Add rule") {
                    val selectedAction = DomainAction.entries[action.selectedItemPosition]
                    val selectedType = DomainMatchType.entries[type.selectedItemPosition]
                    val normalized = DomainRules.normalizeValue(selectedType, value.text.toString())
                    val target = position.text.toString().toIntOrNull()
                    when {
                        normalized == null -> msg.text = "Enter hostname text, a domain, or a suffix such as .org. Do not include a URL, path, or spaces."
                        target == null || target !in 1..rules.size + 1 -> msg.text = "Enter a position from 1 to ${rules.size + 1}."
                        rules.any { it.action == selectedAction && it.type == selectedType && it.value == normalized } -> msg.text = "This rule already exists. Change its position below."
                        selectedAction == DomainAction.ALLOW -> withDomainRulePassword("Add Allow rule") { password ->
                            Store.addDomainRule(this, selectedAction, selectedType, normalized, target, password)
                        }
                        else -> {
                            if (Store.addDomainRule(this, selectedAction, selectedType, normalized, target)) {
                                Policy.sweep(this)
                                reopen()
                            } else msg.text = "Rules changed. Refresh and try again."
                        }
                    }
                }, initiallyExpanded = rules.isEmpty()),
            label("Rule order (${rules.size})", 20f)
        )
        if (rules.isEmpty()) views.add(label("No domain rules yet."))
        rules.forEachIndexed { index, rule ->
            fun moveTo(target: Int) = withDomainRulePassword("Move rule to position $target") { password ->
                Store.moveDomainRule(this, rule.id, target, password)
            }
            val controls = mutableListOf<View>(label(rule.description),
                button("Change position (filter password)") {
                    val field = positionField(index + 1)
                    val dialog = AlertDialog.Builder(this).setTitle("Position for ${rule.value}")
                        .setMessage("Choose 1 to ${rules.size}. Position 1 runs first.").setView(field)
                        .setPositiveButton("Move", null).setNegativeButton("Cancel", null).create()
                    dialog.setOnShowListener {
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                            val target = field.text.toString().toIntOrNull()
                            if (target == null || target !in 1..rules.size) field.error = "Enter 1 to ${rules.size}"
                            else { dialog.dismiss(); if (target != index + 1) moveTo(target) }
                        }
                    }
                    dialog.show()
                })
            if (index > 0) controls.add(button("Move up (filter password)") { moveTo(index) })
            if (index < rules.lastIndex) controls.add(button("Move down (filter password)") { moveTo(index + 2) })
            controls.add(button(if (rule.action == DomainAction.BLOCK) "Remove rule (filter password)" else "Remove Allow rule") {
                if (rule.action == DomainAction.BLOCK) withDomainRulePassword("Remove Block rule") { password ->
                    Store.removeDomainRule(this, rule.id, password)
                } else {
                    Store.removeDomainRule(this, rule.id)
                    Policy.sweep(this)
                    reopen()
                }
            })
            views.add(section("#${index + 1} ${rule.action.title}: ${rule.value}", rule.type.title, *controls.toTypedArray()))
        }
        views.add(section("Test a domain", "Shows the first matching rule without opening a website", tester,
            button("Test rules") {
                val host = DomainRules.hostFromAddress(tester.text.toString())
                val match = host?.let { DomainRules(Store.domainRules(this)).match(it) }
                result.text = when {
                    host == null -> "Enter a valid domain or http(s) URL."
                    match == null -> "$host: no matching rule; passes this filter. Other protections still apply."
                    else -> "$host: ${match.rule.action.title.uppercase()} by rule #${match.position}: ${match.rule.description}"
                }
            }, result))
        views.add(section("Protection & scope", "Accessibility for browsers; VPN for Google",
            label("Browser domain checks use the visible native address bar. The accessibility service must be running. " +
                    "Google app domain checks use the built-in VPN's DNS filter. Cached connections may continue until the next DNS lookup. " +
                    "Browser address-bar support depends on the browser."),
            label("Allow passes only this domain filter. Chrome whitelist, extra site locks, Google DNS blocklist, " +
                    "keyword filtering and app locks still apply. Paths and search terms are not domain names."),
            label("Adding Block rules is immediate. Adding Allow rules, removing Block rules, and reordering require the filter password. " +
                    "While any rules exist, disabling accessibility locks browsers and Google."),
            label("Accessibility service: " + if (BrowserKeywordService.isReady(this)) "running" else "not running"),
            button("Android accessibility settings") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            button("Google VPN filter") { goTo { showGoogleFilter() } }))
        views.add(button("Refresh") { reopen() })
        views.add(button("Back") { back() })
        screen(*views.toTypedArray())
    }

    // ---------- browser keyword filter ----------

    private fun showBrowserKeywords() {
        val entries = Store.browserKeywordEntries(this)
        val wait = Store.timingConfig(this)[TimingOption.KEYWORD_REMOVAL_WAIT]
        val enabled = BrowserKeywordService.isEnabled(this)
        val filterOn = Store.browserKeywordFilterEnabled(this)
        val ready = BrowserKeywordService.isReady(this)
        fun timeLabel(time: Long) = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(time))
        val list = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, entries.map { entry ->
                entry.word + if (entry.removeAt != null) " — removal at ${timeLabel(entry.removeAt)}" else " — blocked"
            }).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            isEnabled = entries.isNotEmpty()
        }
        val manageWord = button("Manage selected word") {
            val entry = entries.getOrNull(list.selectedItemPosition) ?: return@button
            if (entry.removeAt != null) {
                AlertDialog.Builder(this)
                    .setTitle(entry.word)
                    .setMessage("This word remains blocked until ${timeLabel(entry.removeAt)}. Cancel its removal to keep it blocked?")
                    .setPositiveButton("Keep blocked") { _, _ ->
                        // Re-adding also restores a word if its removal expired while this dialog was open.
                        Store.addBrowserKeyword(this, entry.word)
                        reopen()
                    }
                    .setNegativeButton("Back", null).show()
            } else {
                AlertDialog.Builder(this)
                    .setTitle("Remove ${entry.word}?")
                    .setMessage("It will remain blocked for $wait minutes, then be removed automatically. " +
                            "You can cancel the removal while you wait.")
                    .setPositiveButton("Start removal wait") { _, _ ->
                        Store.requestKeywordRemoval(this, entry.word)
                        reopen()
                    }
                    .setNegativeButton("Cancel", null).show()
            }
        }.apply { isEnabled = entries.isNotEmpty() }
        val field = EditText(this).apply {
            hint = "Word or phrase to block"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val msg = label("")
        val add = button("Block word immediately") {
            val word = KeywordMatcher.normalizeWord(field.text.toString())
            if (word == null) {
                msg.text = "Use 1-80 characters: letters, numbers, spaces, apostrophes or hyphens."
            } else if (!Store.addBrowserKeyword(this, word)) {
                msg.text = "$word is already blocked."
            } else {
                toast("Blocked $word immediately. Any pending removal was cancelled.")
                reopen()
            }
        }
        val filterStatus = label(when {
            !filterOn -> "Keyword filter: OFF (disabled with filter password)."
            !ready -> "Keyword filter: ON. Browsers and the Google app are blocked until the accessibility service is running."
            else -> "Keyword filter: ON. Accessibility service is running."
        })
        val toggle = button(if (filterOn) "Turn keyword filter OFF (filter password)" else "Turn keyword filter ON") {
            if (Store.browserKeywordFilterEnabled(this)) {
                if (!Store.hasPassword(this, Store.Pw.FILTER)) {
                    toast("Set the filter password first, then retry turning the filter off.")
                    goTo { showFilterPasswordSetup() }
                } else {
                    goTo { showCredentialGate("Turn keyword filter off", Store.Pw.FILTER,
                        { password -> Store.disableBrowserKeywordFilter(this, password) }) {
                        Policy.sweep(this)
                        back()
                    } }
                }
            } else {
                Store.enableBrowserKeywordFilter(this)
                Policy.sweep(this)
                reopen()
            }
        }
        screen(
            label("Browser & Google keyword filter", 22f), filterStatus,
            section("Blocked words", "${entries.size} words and phrases; removals take $wait minutes",
                label(if (entries.isEmpty()) "No blocked words." else "Select a word to manage its removal."),
                list, manageWord, field, msg, add, initiallyExpanded = true),
            section("Page exceptions", "Allow specific topics or the whole page",
                button("Per-word page exceptions") {
                    val selected = entries.getOrNull(list.selectedItemPosition)?.word
                    goTo { showKeywordExceptions(selected) }
                },
                button("Generic page exceptions") { goTo { showGenericKeywordExceptions() } }),
            section("Filter controls & recovery", "Accessibility, Google VPN and a new search",
                label(if (Store.domainRules(this).isEmpty()) "No ordered domain rules configured."
                    else "Ordered domain rules remain active even when keyword filtering is off."),
                button("Ordered domain rules") { goTo { showDomainRules() } },
                label("Accessibility service: " + if (enabled) "enabled" else "OFF"), toggle,
                button("Open Android accessibility settings") {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                },
                button("Google VPN filter") { goTo { showGoogleFilter() } },
                button("Start a new Google search") { startActivity(GoogleSearchRecoveryActivity.openIntent(this)) }),
            section("How keyword filtering works", "Matching rules, exceptions and privacy",
                label("Checks visible browser pages and Google app content, including results and Discover. " +
                        "Matches whole words or phrases regardless of case, and ignores typed fields. " +
                        "Google opens a blocked-results screen so you can edit or exclude the triggering word. " +
                        "Browsers try to close the tab, otherwise return Home. " +
                        "Detection depends on readable accessibility content. Inspected text stays on this device and is not saved."),
                label("Enable “${getString(R.string.browser_keyword_service_name)}” in Android accessibility settings. " +
                        "When keyword protection is on, browsers and Google remain locked if the service is off. " +
                        "Turning protection off requires the filter password. Google also requires the built-in VPN."),
                label("Select a blocked word and tap Manage selected word to request or cancel removal. " +
                        "Per-word exceptions allow one blocked word. Generic exceptions allow all keyword matches on that page. " +
                        "Other app and site restrictions still apply.")),
            button("Refresh") { reopen() }, button("Back") { back() }
        )
        val nextRemoval = entries.mapNotNull { it.removeAt }.minOrNull()
        if (nextRemoval != null) list.postDelayed({
            if (list.isAttachedToWindow && hasWindowFocus() && field.text.isBlank() && !field.hasFocus()) reopen()
        }, (nextRemoval - System.currentTimeMillis()).coerceIn(100L, 60_000L))
    }

    // ---------- page keyword exceptions ----------

    private fun showGenericKeywordExceptions() {
        val allowed = EditText(this).apply { hint = "Generic exception word or phrase, e.g. christian" }
        val password = passwordField("Filter password")
        val msg = label("")
        val rules = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun drawRules() {
            rules.removeAllViews()
            val words = Store.genericKeywordExceptions(this).sorted()
            if (words.isEmpty()) rules.addView(label("No generic page exceptions."))
            for (word in words) {
                rules.addView(button("Remove exception: $word") {
                    Store.removeGenericKeywordException(this, word)
                    msg.text = "Removed the exception immediately."
                    drawRules()
                })
            }
        }
        val views = mutableListOf<View>(
            label("Generic page exceptions", 22f),
            label("These apply to browsers and the Google app. If any generic exception word or phrase " +
                    "appears on the same visible page, that page is allowed through the keyword filter, " +
                    "even if it contains several blocked words. For example, christian can allow a page " +
                    "containing movie and anime. Matching uses whole words or phrases, regardless of case. " +
                    "Adding requires the filter password; removing is immediate. " +
                    "App locks, accessibility, Google VPN and site restrictions still apply."),
            label("Current generic exceptions", 20f), rules, allowed, msg
        )
        if (Store.hasPassword(this, Store.Pw.FILTER)) {
            views.add(password)
            views.add(button("Add generic exception (filter password)") {
                val word = KeywordMatcher.normalizeWord(allowed.text.toString())
                when {
                    word == null -> msg.text = "Use 1-80 characters: letters, numbers, spaces, apostrophes or hyphens."
                    word in Store.genericKeywordExceptions(this) -> msg.text = "This exception already exists."
                    Store.lockoutRemainingMs(this, Store.Pw.FILTER) > 0 -> msg.text = "Too many attempts. Try again later."
                    !Store.addGenericKeywordException(this, word, password.text.toString()) -> msg.text = "Wrong filter password."
                    else -> {
                        allowed.setText("")
                        password.setText("")
                        msg.text = "Pages containing $word are allowed through the keyword filter."
                        drawRules()
                    }
                }
            })
        } else {
            views.add(button("Set filter password to add exceptions") { goTo { showFilterPasswordSetup() } })
        }
        views.add(button("Refresh") { reopen() })
        views.add(button("Back") { back() })
        screen(*views.toTypedArray())
        drawRules()
    }

    private fun showKeywordExceptions(initialWord: String? = null) {
        val words = Store.browserKeywords(this).sorted()
        if (words.isEmpty()) {
            screen(label("Page exceptions", 22f), label("Add a blocked word first for per-word exceptions."),
                button("Generic page exceptions") { goTo { showGenericKeywordExceptions() } }, button("Back") { back() })
            return
        }
        val selected = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, words).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(words.indexOf(initialWord).coerceAtLeast(0))
        }
        val allowed = EditText(this).apply { hint = "Exception word or phrase, e.g. christian" }
        val password = passwordField("Filter password")
        val msg = label("")
        val rules = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun drawRules() {
            rules.removeAllViews()
            val blocked = words.getOrNull(selected.selectedItemPosition) ?: return
            val exceptions = Store.keywordExceptions(this)[blocked].orEmpty().sorted()
            if (exceptions.isEmpty()) rules.addView(label("No page exceptions for $blocked."))
            for (word in exceptions) {
                rules.addView(button("Remove exception: $word") {
                    Store.removeKeywordException(this, blocked, word)
                    msg.text = "Removed the exception immediately."
                    drawRules()
                })
            }
        }
        selected.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { drawRules() }
            override fun onNothingSelected(parent: AdapterView<*>?) { drawRules() }
        }
        val views = mutableListOf<View>(
            label("Page exceptions", 22f),
            button("Generic page exceptions") { goTo { showGenericKeywordExceptions() } },
            label("Choose a blocked word, then a word that allows it on the same visible page. " +
                    "Example: movie → christian allows movie when christian also appears. " +
                    "Any one exception for that blocked word is enough. Other blocked words are still checked. " +
                    "Matching uses whole words or phrases, regardless of case. " +
                    "Adding an exception requires the filter password; removing one is immediate. " +
                    "App locks, accessibility requirements, the Google VPN requirement and site restrictions still apply."),
            label("Blocked word"), selected,
            label("Current exceptions", 20f), rules,
            allowed, msg
        )
        if (Store.hasPassword(this, Store.Pw.FILTER)) {
            views.add(password)
            views.add(button("Add exception (filter password)") {
                val blocked = words.getOrNull(selected.selectedItemPosition) ?: return@button
                val exception = KeywordMatcher.normalizeWord(allowed.text.toString())
                when {
                    exception == null -> msg.text = "Use 1–80 characters: letters, numbers, spaces, apostrophes or hyphens."
                    exception == blocked -> msg.text = "Choose a different word from the blocked word."
                    blocked !in Store.browserKeywords(this) -> msg.text = "This blocked word has been removed. Tap Refresh."
                    exception in Store.keywordExceptions(this)[blocked].orEmpty() -> msg.text = "This exception already exists."
                    Store.lockoutRemainingMs(this, Store.Pw.FILTER) > 0 -> msg.text = "Too many attempts. Try again later."
                    !Store.addKeywordException(this, blocked, exception, password.text.toString()) -> msg.text = "Wrong filter password."
                    else -> {
                        allowed.setText("")
                        password.setText("")
                        msg.text = "$blocked is allowed when $exception also appears on the page."
                        drawRules()
                    }
                }
            })
        } else {
            views.add(button("Set filter password to add exceptions") { goTo { showFilterPasswordSetup() } })
        }
        views.add(button("Refresh") { reopen() })
        views.add(button("Back") { back() })
        screen(*views.toTypedArray())
        drawRules()
    }

    // ---------- password-protected timing settings ----------

    private fun openSettings() {
        settingsAuthed = false
        goTo {
            if (Store.hasPassword(this, Store.Pw.SETTINGS)) {
                showSettingsGate()
            } else if (authed) {
                showSetSettingsPassword()
            } else {
                showPasswordGate("Set up settings password", Store.Pw.MAIN) {
                    swapTo { showSetSettingsPassword() }
                }
            }
        }
    }

    private fun showSettingsGate() {
        showPasswordGate("Settings", Store.Pw.SETTINGS) {
            settingsAuthed = true
            swapTo { showSettings() }
        }
    }

    private fun showSetSettingsPassword() {
        val p1 = passwordField("New settings password")
        val p2 = passwordField("Confirm settings password")
        val msg = label("")
        screen(
            label("Set settings password", 22f),
            label("This separate password protects durations and time limits. " +
                    "Use at least 8 characters, including a letter and a number."),
            p1, p2, msg,
            button("Save password") {
                val value = p1.text.toString()
                when {
                    Store.hasPassword(this, Store.Pw.SETTINGS) -> {
                        settingsAuthed = false
                        swapTo { showSettingsGate() }
                    }
                    !Store.isValidNewPassword(value) -> msg.text = "Needs 8+ characters with a letter and a number."
                    value != p2.text.toString() -> msg.text = "Passwords don't match."
                    else -> {
                        Store.setPassword(this, value, Store.Pw.SETTINGS)
                        settingsAuthed = true
                        swapTo { showSettings() }
                    }
                }
            },
            button("Cancel") { back() }
        )
    }

    private fun showSettings() {
        if (!settingsAuthed) {
            swapTo { showSettingsGate() }
            return
        }
        val config = Store.timingConfig(this)
        val fields = TimingOption.entries.associateWith { option ->
            EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                hint = option.title
                setText(config[option].toString())
            }
        }
        val presets = EditText(this).apply {
            hint = "For example: 15, 30, 60, 120"
            inputType = InputType.TYPE_CLASS_TEXT
            setText(config.unlockPresets.joinToString(", "))
        }
        val savedCategories = Store.immediatePlayCategories(this)
        val categoryBoxes = PlayInstallCategory.entries.associateWith { category ->
            CheckBox(this).apply {
                text = category.title
                textSize = 16f
                minHeight = dp(48)
                isChecked = category.storeId in savedCategories
            }
        }
        val categorySummary = label("")
        fun updateCategorySummary() {
            categorySummary.text = "${categoryBoxes.values.count { it.isChecked }} of ${categoryBoxes.size} categories selected. " +
                    "Changes apply when you tap Save settings."
        }
        categoryBoxes.values.forEach { it.setOnCheckedChangeListener { _, _ -> updateCategorySummary() } }
        updateCategorySummary()
        fun timingViews(vararg options: TimingOption): Array<View> = options.flatMap { option ->
            listOf(label("${option.title} (${option.unit}, ${option.minimum}-${option.maximum})"), fields.getValue(option))
        }.toTypedArray()
        val msg = label("")
        val views = mutableListOf<View>(
            label("Settings", 22f),
            label("Protected by your settings password. Expand a section, make changes, then Save settings."),
            section("Play Store immediate access", "Choose categories that skip the new-app wait",
                categorySummary,
                label("Only apps installed from Google Play qualify. The category must be verified online first. " +
                        "Unselected or unverified apps keep the normal wait. Other locks and filters still apply."),
                section("App categories", "Music, finance, shopping and other app types",
                    *categoryBoxes.filterKeys { !it.isGame }.values.toTypedArray()),
                section("Game categories", "Individual game genres",
                    *categoryBoxes.filterKeys { it.isGame }.values.toTypedArray()),
                button("Clear category selection") { categoryBoxes.values.forEach { it.isChecked = false } },
                button("Use default categories") {
                    categoryBoxes.forEach { (category, box) -> box.isChecked = category.storeId in PlayCategoryRules.defaultCategories }
                }),
            section("Install waiting period", "Default delay for newly installed apps",
                *timingViews(TimingOption.INSTALL_WAIT)),
            section("Websites & keywords", "Whitelist activation and blocked-word removal waits",
                *timingViews(TimingOption.SITE_WAIT, TimingOption.KEYWORD_REMOVAL_WAIT),
                label("Extra locked sites always use a fixed 45-minute removal wait.")),
            section("Unlock durations", "Temporary unlock limits and shortcut buttons",
                *timingViews(TimingOption.DEFAULT_UNLOCK, TimingOption.MAX_UNLOCK),
                label("Unlock button presets (1-6 different durations in minutes, separated by commas)"), presets),
            section("Protection timing", "Curbox, background checks and network timeouts",
                *timingViews(TimingOption.CURBOX_GRACE, TimingOption.POLICY_INTERVAL, TimingOption.UNINSTALL_RETRY,
                    TimingOption.DNS_TIMEOUT, TimingOption.CHROME_RESTART)),
            section("Password security", "Failed attempts and lockout durations",
                *timingViews(TimingOption.PASSWORD_FAILURES, TimingOption.LOCKOUT_INITIAL, TimingOption.LOCKOUT_MAX)),
            section("When changes take effect", "How saved settings affect existing timers",
                label("Install and whitelist waits are recalculated from the original install/add times. " +
                        "Play category changes apply to installed apps still within their install wait. " +
                        "An active unlock is capped to the new maximum. Existing password lockouts keep their end times. " +
                        "Keyword removal requests keep their scheduled deadlines; their delay setting applies to new requests."))
        )
        views.add(msg)
        views.add(button("Save settings") {
            if (settingsAuthed) {
                val values = fields.mapValues { it.value.text.toString().trim().toIntOrNull() }
                val invalid = values.entries.firstOrNull { (option, value) ->
                    value == null || value !in option.minimum..option.maximum
                }
                val presetValues = presets.text.toString().split(',').map { it.trim().toIntOrNull() }
                if (invalid != null) {
                    val option = invalid.key
                    fields.getValue(option).error = "Enter ${option.minimum} to ${option.maximum} ${option.unit}."
                    reveal(fields.getValue(option))
                    msg.text = "Check ${option.title.lowercase()}."
                } else if (presetValues.any { it == null }) {
                    presets.error = "Enter whole minutes separated by commas."
                    reveal(presets)
                    msg.text = "Check the unlock presets."
                } else {
                    val updated = TimingConfig(values.mapValues { it.value!! }, presetValues.filterNotNull())
                    val error = updated.validationError()
                    if (error != null) {
                        msg.text = error
                        reveal(when {
                            updated[TimingOption.DEFAULT_UNLOCK] > updated[TimingOption.MAX_UNLOCK] -> fields.getValue(TimingOption.DEFAULT_UNLOCK)
                            updated[TimingOption.LOCKOUT_INITIAL] > updated[TimingOption.LOCKOUT_MAX] -> fields.getValue(TimingOption.LOCKOUT_INITIAL)
                            else -> presets
                        })
                    } else {
                        Store.saveTimingConfig(this, updated)
                        Store.saveImmediatePlayCategories(this, categoryBoxes.filterValues { it.isChecked }.keys.map { it.storeId }.toSet())
                        Policy.sweep(this)
                        LockService.start(this)
                        msg.text = "Settings saved."
                    }
                }
            }
        })
        views.add(button("Use default values") {
            if (settingsAuthed) {
                val defaults = TimingConfig()
                fields.forEach { (option, field) -> field.setText(defaults[option].toString()); field.error = null }
                presets.setText(defaults.unlockPresets.joinToString(", "))
                categoryBoxes.forEach { (category, box) -> box.isChecked = category.storeId in PlayCategoryRules.defaultCategories }
                msg.text = "Defaults filled in. Tap Save settings to apply them."
            }
        })
        views.add(button("Change settings password") { goTo { showChangeSettingsPassword() } })
        views.add(button("Back") { settingsAuthed = false; back() })
        screen(*views.toTypedArray())
    }

    private fun showChangeSettingsPassword() {
        if (!settingsAuthed) { back(); return }
        val old = passwordField("Current settings password")
        val p1 = passwordField("New settings password")
        val p2 = passwordField("Confirm settings password")
        val msg = label("")
        screen(
            label("Change settings password", 22f), old, p1, p2, msg,
            button("Save password") {
                if (settingsAuthed) {
                    val which = Store.Pw.SETTINGS
                    val value = p1.text.toString()
                    when {
                        Store.lockoutRemainingMs(this, which) > 0 -> msg.text = "Too many attempts. Try again later."
                        !Store.checkPassword(this, old.text.toString(), which) -> msg.text = "Current settings password is wrong."
                        !Store.isValidNewPassword(value) -> msg.text = "Needs 8+ characters with a letter and a number."
                        value != p2.text.toString() -> msg.text = "Passwords don't match."
                        else -> { Store.setPassword(this, value, which); back() }
                    }
                }
            },
            button("Cancel") { back() }
        )
    }

    // ---------- UI helpers ----------

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun section(title: String, summary: String, vararg children: View,
                        initiallyExpanded: Boolean = false): ExpandableSection =
        ExpandableSection(this, title, summary, sectionStates[title] ?: initiallyExpanded,
            { sectionStates[title] = it }, *children)

    private fun reveal(view: View) {
        var ancestor = view.parent
        while (ancestor is View) {
            if (ancestor is ExpandableSection) ancestor.expand()
            ancestor = ancestor.parent
        }
        view.requestFocus()
        view.post { view.requestRectangleOnScreen(android.graphics.Rect(0, 0, view.width, view.height), false) }
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

    private fun screen(vararg views: View) {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(24), dp(16), dp(24), dp(24))
        views.forEach { col.addView(it, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }) }
        val scroll = ScrollView(this)
        scroll.isFillViewport = true
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
        b.isAllCaps = false
        b.minHeight = dp(48)
        b.setOnClickListener { onClick() }
        return b
    }
}
