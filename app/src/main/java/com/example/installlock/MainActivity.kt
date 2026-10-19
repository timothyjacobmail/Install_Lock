package com.example.installlock

import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.color.DynamicColors
import com.google.android.material.switchmaterial.SwitchMaterial
import android.content.Intent
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
class MainActivity : AppCompatActivity() {

    companion object {
        // Cleared when the app leaves the screen
        private var authed = false
        private val shareLinkExecutor = Executors.newSingleThreadExecutor()
        private val credentialExecutor = Executors.newSingleThreadExecutor()
    }

    // ---------- navigation ----------

    private val history = ArrayDeque<() -> Unit>()
    private var current: (() -> Unit)? = null
    private var receivedSiteShare = false
    private var sharedSiteOptions = emptyList<String>()
    private var sharedSiteText: String? = null
    private var shareLinkTask: Future<*>? = null
    private var credentialJob: Future<*>? = null
    private var credentialGeneration = 0
    private val sectionStates = mutableMapOf<String, Boolean>()

    /** Go forward to a new screen. Back returns to the one we left. */
    private fun goTo(screen: () -> Unit) {
        current?.let { history.addLast(it) }
        current = screen
        screen()
    }

    private fun unlockStatus(): String {
        val active = Store.protectedApps(this).count { Store.unlockedUntil(this, it) > System.currentTimeMillis() }
        return "$active app(s) have temporary access. Each app has its own timer and credential. Other protections remain active."
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
        DynamicColors.applyToActivityIfAvailable(this)
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
        credentialGeneration++
        credentialJob?.cancel(true)
        credentialJob = null
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
                withTaskPassword(TaskScope(TaskKind.UNINSTALL_REMOVE), "Remove selected uninstall protection") { password ->
                    TaskPasswords.run(this, TaskScope(TaskKind.UNINSTALL_REMOVE), password) { commit(); true }
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
                "Install Lock is always protected. Unticking an app needs the task password."))
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
        val msg = errorLabel()
        val status = label(unlockStatus())
        val locks = Policy.lockStatuses(this)
        fun waitText(ms: Long) = "Too many attempts. Try again in ${(ms + 59_999) / 60_000} min."
        screen(
            UiStyle.eyebrow(this, "DEVICE PROTECTION"),
            label("Install Lock", 26f),
            UiStyle.card(this,
            label("Open your dashboard", 20f),
            label("Enter your main password to open the dashboard."),
            pw, msg,
            UiStyle.button(this, "Unlock", primary = true) {
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
            }),
            UiStyle.eyebrow(this, "PROTECTION TOOLS"),
            button("Why apps are locked") { goTo { showLockReasons() } },
            curfewActions(),
            watchdogActions(),
            browserActions(),
            section("Apps", "Choose apps to lock or end an unlock session",
                button("App locks") { goTo { showAppPicker() } },
                button("Lock protected apps now") {
                    Store.lockProtectedApps(this)
                    Policy.sweep(this)
                    reopen()
                }),
            section("Settings & passwords", "Separate password required for settings",
                button("Settings") { openSettings() },
                button("Task passwords") { goTo { showTaskPasswords() } },
                button("About & licenses") { goTo { showAbout() } }),
            section("Protection status", "Current app and browser protection",
                status, label(chromeLockExplanation(locks)), label(curboxStatus()), label(playAccessSummary()))
        )
    }

    private fun watchdogActions() = section("Browsing & scrolling watchdog", "Website categories and rapid feed scrolling",
        button("Watchdog status & website categories") { goTo { showCategoryWatchdog() } })

    private fun showCategoryWatchdog() {
        Policy.sweep(this)
        val status = label("")
        val site = EditText(this).apply { hint = "Website URL or domain"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }
        val result = label("")
        val catalog = WatchdogStore.domains(this)
        fun refreshStatus() {
            val remaining = WatchdogStore.remaining(this)
            val seconds = (remaining + 999) / 1000
            val reason = if (WatchdogStore.rapidCooldown(this)) "Rapid-scrolling" else "Manga/comics"
            status.text = if (remaining > 0) "$reason cooldown: ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')} remaining. " +
                "Browsers, Google, YouTube, Instagram, Reddit and app installations/updates are locked. App passwords cannot end this cooldown."
                else "No watchdog cooldown is active. Both detectors run while accessibility is connected."
            if (!Policy.isOwner(this)) status.append("\nDevice owner is required to suspend apps and block installations.")
            if (remaining > 0) {
                val installsBlocked = getSystemService(android.os.UserManager::class.java)
                    .hasUserRestriction(android.os.UserManager.DISALLOW_INSTALL_APPS)
                status.append(if (installsBlocked) "\nAndroid confirms the installation restriction is active."
                    else "\nAndroid installation restriction is pending; Install Lock will retry.")
            }
            if (!BrowserKeywordService.isReady(this)) status.append("\nAccessibility monitoring is disconnected. Browsers, Google, YouTube, Instagram and Reddit remain locked until it reconnects.")
        }
        refreshStatus()
        screen(label("Browsing & scrolling watchdog", 24f), status,
            label("Manga/comics sites: warning on entry, final warning at 5 seconds, 30-minute cooldown at 10 seconds total. " +
                "Only foreground website time counts. Leaving pauses the timer; returning to any categorized site within 10 minutes resumes it. " +
                "Reboots and app restarts do not clear an active cooldown. Curfew and other locks continue independently."),
            section("Rapid feed scrolling", "6 vertical scroll bursts within 15 seconds",
                label("Watches recognized YouTube Shorts, Instagram Reels/home feeds and Reddit feeds in their official apps, " +
                    "plus their supported website URLs in browsers that expose the full native address. " +
                    "One continuous fling counts as one burst. After detection, a warning appears, a final warning follows at 5 seconds, " +
                    "and 10 seconds of continued rapid activity starts the shared 30-minute cooldown."),
                label("Leaving the feed pauses the timer. Stopping scrolling pauses it after up to 3 seconds of inactivity; " +
                    "rapid activity within 10 minutes resumes accumulated warning time, including across supported apps. " +
                    "Watching one video without scrolling does not count. Identifiable comments, messages, search and settings screens are excluded."),
                label("Detection uses local accessibility signals, not saved browsing history. It is best effort: app updates, " +
                    "translated controls, hidden URL paths or missing scroll events can prevent detection. It does not recognize every short-form service. " +
                    "An existing cooldown survives restarts and cannot be cleared with an app password.")),
            section("Website classification", "${catalog.size} offline domains, including MangaDex and Webtoons",
                label("Uses the bundled UT1 manga/comics category plus your additions. It includes comics-related sites broadly. " +
                    "Unknown domains remain unclassified; word mentions, search-result links and background DNS requests do not count as a website visit. " +
                    "Detection requires a browser that exposes its native address. Existing keyword and domain blocks still act immediately."),
                site, button("Check category") {
                    val host = DomainRules.hostFromAddress(site.text.toString())
                    result.text = if (host == null) "Enter a website domain or http(s) URL."
                        else WebsiteCategories(WatchdogStore.domains(this)).mangaDomain(host)?.let { "Manga/comics category: $it" }
                            ?: "Unclassified: $host. You can add it to the category below."
                }, result,
                button("Add website to manga/comics category") {
                    result.text = if (WatchdogStore.addDomain(this, site.text.toString())) "Added immediately, including subdomains."
                        else "Enter a valid website domain or http(s) URL."
                }),
            section("Data source & license", "Universite Toulouse Capitole · CC BY-SA 4.0",
                label(assets.open("categories/README.txt").bufferedReader().use { it.readText() }, 13f)),
            button("Refresh") { refreshStatus(); Policy.sweep(this) }, button("Back") { back() })
        status.postDelayed(object : Runnable {
            override fun run() {
                if (status.isAttachedToWindow && !isFinishing && !isDestroyed) {
                    refreshStatus()
                    status.postDelayed(this, 1000)
                }
            }
        }, 1000)
    }

    private fun curfewActions() = section("Night curfew", "11 pm–4 am · takes priority over app unlocks",
        button("Curfew status, apps & appeal") { goTo { showCurfew() } })

    private fun showCurfew() {
        Policy.sweep(this)
        val now = System.currentTimeMillis()
        val zone = CurfewStore.zone()
        val night = CurfewRules.night(now, zone)
        val appealed = CurfewStore.appealedNight(this)
        val until = CurfewRules.blockedUntil(now, zone, appealed)
        val names = CurfewStore.targets(this).mapNotNull { pkg ->
            try { "${packageManager.getApplicationInfo(pkg, 0).loadLabel(packageManager)} ($pkg)" }
            catch (_: Exception) { null }
        }.sorted()
        screen(label("Night curfew", 24f),
            label("Every night: 11 pm–4 am. Time zone: $zone."),
            label(if (until != null) "Curfew is active. App passwords cannot bypass it."
                else if (appealed == night.toString()) "Appeal saved for $night: tonight's curfew starts at midnight and ends at 4 am."
                else "Curfew starts at 11 pm for the night of $night."),
            label("An appeal must be submitted strictly before 9 pm. It postpones only that night's curfew until midnight. " +
                "Other restrictions remain active. No late appeals, extensions or permanent off switch."),
            button("Appeal tonight: access until midnight") {
                if (CurfewStore.appeal(this)) toast("Appeal saved for tonight. Curfew starts at midnight.")
                else toast("Appeal unavailable: the 9 pm deadline has passed, or tonight was already appealed.")
                LockService.start(this)
                reopen()
            }.apply { isEnabled = Policy.isOwner(this@MainActivity) && CurfewRules.canAppeal(now, zone, appealed) },
            label("Browsers, Google, YouTube, Instagram and protected apps are included. Apps downloaded from the day this feature " +
                "is first run onward join automatically and remain included on future nights. Category exemptions do not bypass curfew."),
            section("Curfew apps (${names.size})", "Automatically enrolled; removing an app lock does not remove curfew",
                *names.map { label(it) }.toTypedArray()),
            button("Refresh") { reopen() }, button("Back") { back() })
    }

    private fun showDashboard() {
        screen(UiStyle.eyebrow(this, "YOUR DASHBOARD"), label("Install Lock", 26f),
            label("The main password opens this dashboard. Each sensitive operation needs its own task password."),
            button("Why apps are locked") { goTo { showLockReasons() } },
            curfewActions(),
            watchdogActions(),
            section("App access", "Separate access, removal and duration passwords for each app",
                button("Temporarily unlock one app") { goTo { showAppAccess() } },
                button("App locks") { goTo { showAppPicker() } },
                button("Lock protected apps now") { Store.lockProtectedApps(this); Policy.sweep(this); reopen() },
                button("Never-install list") { goTo { showNeverInstall() } },
                button("Prevent-uninstall apps") { goTo { showNoUninstallPicker() } }),
            browserActions(),
            section("Additional protections", "Separate credentials for each protection",
                label(curboxStatus()),
                button(if (Policy.isScreenCaptureBlocked(this)) "Allow screenshots (task password)" else "Block screenshots") {
                    if (Policy.isScreenCaptureBlocked(this)) withTaskPassword(TaskScope(TaskKind.SCREENSHOTS_ALLOW), "Allow screenshots") {
                        Policy.setScreenCaptureBlocked(this, false, it)?.startsWith("Error:") == false
                    } else { toast(Policy.setScreenCaptureBlocked(this, true).orEmpty()); reopen() }
                },
                button(if (Store.curboxGuard(this)) "Turn Curbox guard off (task password)" else "Turn Curbox guard on") {
                    if (Store.curboxGuard(this)) withTaskPassword(TaskScope(TaskKind.CURBOX_OFF), "Turn Curbox guard off") { password ->
                        TaskPasswords.run(this, TaskScope(TaskKind.CURBOX_OFF), password) {
                            Store.setCurboxGuard(this, false)
                            Policy.release(this, Policy.CURBOX_GUARDED)
                            true
                        }
                    } else if (!Policy.curboxRunning(this)) toast("Set up Curbox's accessibility service first.")
                    else { Store.setCurboxGuard(this, true); Policy.sweep(this); reopen() }
                }),
            section("Settings & passwords", "Each save and password change is scoped to its own task",
                button("Settings") { openSettings() },
                button("Task passwords") { goTo { showTaskPasswords() } },
                button("Change main dashboard password") { goTo { showChangePassword() } },
                button("About & licenses") { goTo { showAbout() } }),
            section("Protection status", "Other protections continue during temporary app access",
                label(unlockStatus()), label(chromeLockExplanation(Policy.lockStatuses(this))), label(playAccessSummary())))
    }

    private fun showNeverInstall() {
        val rules = Store.neverInstall(this).sorted()
        val list = ListView(this)
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, rules)
        list.setOnItemClickListener { _, _, i, _ ->
            val rule = rules[i]
            withTaskPassword(TaskScope(TaskKind.NEVER_REMOVE), "Remove never-install rule: $rule") { password ->
                TaskPasswords.run(this, TaskScope(TaskKind.NEVER_REMOVE), password) {
                    Store.setNeverInstall(this, Store.neverInstall(this) - rule)
                    true
                }
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
                    MaterialAlertDialogBuilder(this)
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
                "Tap an entry to remove it (task password)."))
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
                return SwitchMaterial(this@MainActivity).apply {
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
                            withTaskPassword(TaskScope(TaskKind.APP_REMOVE, pkg), "Remove lock only from $name") { password ->
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
                "This immediately ends temporary access for that app. Switching off requires the separate app-specific task password. " +
                "Other install delays and filters can still block an app."))
        col.addView(label(unlockStatus()))
        col.addView(button("Why apps are locked") { goTo { showLockReasons() } })
        col.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        col.addView(button("Temporary app access") { goTo { showAppAccess() } })
        col.addView(button("Task passwords") { goTo { showTaskPasswords() } })
        col.addView(button("Back") { back() })
        show(col)
    }

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
            button("Turn off (task password)") {
                withTaskPassword(TaskScope(TaskKind.VPN_OFF), "Turn off Google VPN") {
                    Policy.disableGoogleFilter(this, it)?.startsWith("Error:") == false
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
                withTaskPassword(TaskScope(TaskKind.DNS_UNBLOCK), "Unblock Google DNS domain: $name") { password ->
                    TaskPasswords.run(this, TaskScope(TaskKind.DNS_UNBLOCK), password) {
                        Store.setBlockedDomains(this, Store.blockedDomains(this) - hits.toSet())
                        true
                    }
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

    // ---------- task password ----------

    /** Asks for the task password, then runs [action]. Weakening actions go through here. */
    private fun showSharedSite() {
        val shortLink = SharedSite.shortLinkFromText(sharedSiteText)
        if (shortLink != null) showResolveSharedSite(shortLink)
        else showAddSite(sharedSiteOptions.firstOrNull().orEmpty(), sharedSiteOptions)
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
            MaterialAlertDialogBuilder(this)
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

        val lockBtn = button(if (lockOn) "Turn Chrome lock OFF (task password)" else "Turn Chrome lock ON") {
            if (lockOn) {
                withTaskPassword(TaskScope(TaskKind.WHITELIST_OFF), "Turn Chrome whitelist off") { Policy.setChromeLock(this, false, it) }
            } else {
                if (Store.activePatterns(this).isEmpty()) {
                    toast("No active sites yet: Chrome will block everything until one is active.")
                }
                Policy.setChromeLock(this, true)
                reopen()
            }
        }
        val add = button("Add site (task password, $siteWait min wait)") {
            goTo { showAddSite() }
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
                    Store.sites(this).any { it.pattern == s } -> msg.text = "$s is already on the list."
                    else -> {
                        withTaskPassword(TaskScope(TaskKind.WHITELIST_ADD), "Whitelist only $s") { password ->
                            TaskPasswords.run(this, TaskScope(TaskKind.WHITELIST_ADD), password) {
                                Store.addSite(this, s).also { if (it) Policy.syncChrome(this) }
                            }
                        }
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
                    MaterialAlertDialogBuilder(this)
                        .setTitle(site.domain)
                        .setMessage("This site stays locked until ${timeLabel(site.removeAt)}. Cancel its removal to keep it locked?")
                        .setPositiveButton("Keep locked") { _, _ ->
                            Store.addExtraLockedSite(this, site.domain)
                            Policy.syncChrome(this)
                            reopen()
                        }
                        .setNegativeButton("Back", null).show()
                } else {
                    MaterialAlertDialogBuilder(this)
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
        val msg = errorLabel()
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
            section("Add a domain rule", "Block instantly; Allow requires the task password",
                label("Action"), action, label("Match type"), type, value,
                label("Contains checks the hostname only. Suffix .org includes all .org domains; " +
                        "example.org includes that domain and its subdomains. Exact matches only the named domain."),
                label("Position (1 runs first; choose 1 to ${rules.size + 1})"), position, msg, primaryButton("Add rule") {
                    val selectedAction = DomainAction.entries[action.selectedItemPosition]
                    val selectedType = DomainMatchType.entries[type.selectedItemPosition]
                    val normalized = DomainRules.normalizeValue(selectedType, value.text.toString())
                    val target = position.text.toString().toIntOrNull()
                    when {
                        normalized == null -> msg.text = "Enter hostname text, a domain, or a suffix such as .org. Do not include a URL, path, or spaces."
                        target == null || target !in 1..rules.size + 1 -> msg.text = "Enter a position from 1 to ${rules.size + 1}."
                        rules.any { it.action == selectedAction && it.type == selectedType && it.value == normalized } -> msg.text = "This rule already exists. Change its position below."
                        selectedAction == DomainAction.ALLOW -> withTaskPassword(TaskScope(TaskKind.DOMAIN_ALLOW), "Add Allow rule") { password ->
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
            fun moveTo(target: Int) = withTaskPassword(TaskScope(TaskKind.DOMAIN_MOVE), "Move rule to position $target") { password ->
                Store.moveDomainRule(this, rule.id, target, password)
            }
            val controls = mutableListOf<View>(label(rule.description),
                button("Change position (task password)") {
                    val field = positionField(index + 1)
                    val dialog = MaterialAlertDialogBuilder(this).setTitle("Position for ${rule.value}")
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
            if (index > 0) controls.add(button("Move up (task password)") { moveTo(index) })
            if (index < rules.lastIndex) controls.add(button("Move down (task password)") { moveTo(index + 2) })
            controls.add(button(if (rule.action == DomainAction.BLOCK) "Remove rule (task password)" else "Remove Allow rule") {
                if (rule.action == DomainAction.BLOCK) withTaskPassword(TaskScope(TaskKind.DOMAIN_REMOVE), "Remove Block rule") { password ->
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
            label("Adding Block rules is immediate. Adding Allow rules, removing Block rules, and reordering require the task password. " +
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
                MaterialAlertDialogBuilder(this)
                    .setTitle(entry.word)
                    .setMessage("This word remains blocked until ${timeLabel(entry.removeAt)}. Cancel its removal to keep it blocked?")
                    .setPositiveButton("Keep blocked") { _, _ ->
                        // Re-adding also restores a word if its removal expired while this dialog was open.
                        Store.addBrowserKeyword(this, entry.word)
                        reopen()
                    }
                    .setNegativeButton("Back", null).show()
            } else {
                MaterialAlertDialogBuilder(this)
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
        val add = primaryButton("Block word immediately") {
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
            !filterOn -> "Keyword filter: OFF (disabled with task password)."
            !ready -> "Keyword filter: ON. Browsers and the Google app are blocked until the accessibility service is running."
            else -> "Keyword filter: ON. Accessibility service is running."
        })
        val toggle = button(if (filterOn) "Turn keyword filter OFF (task password)" else "Turn keyword filter ON") {
            if (Store.browserKeywordFilterEnabled(this)) {
                withTaskPassword(TaskScope(TaskKind.KEYWORD_OFF), "Turn keyword filter off") { Store.disableBrowserKeywordFilter(this, it) }
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
                        "Turning protection off requires the task password. Google also requires the built-in VPN."),
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
        val password = passwordField("Add generic page exceptions password")
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
                    "Adding requires the task password; removing is immediate. " +
                    "App locks, accessibility, Google VPN and site restrictions still apply."),
            label("Current generic exceptions", 20f), rules, allowed, msg
        )
        if (TaskPasswords.ready(this)) {
            views.add(password)
            views.add(button("Add generic exception (task password)") {
                val word = KeywordMatcher.normalizeWord(allowed.text.toString())
                when {
                    word == null -> msg.text = "Use 1-80 characters: letters, numbers, spaces, apostrophes or hyphens."
                    word in Store.genericKeywordExceptions(this) -> msg.text = "This exception already exists."
                    TaskPasswords.remaining(this, TaskScope(TaskKind.PAGE_EXCEPTION)) > 0 -> msg.text = "Too many attempts. Try again later."
                    !Store.addGenericKeywordException(this, word, password.text.toString()) -> msg.text = "Wrong task password."
                    else -> {
                        allowed.setText("")
                        password.setText("")
                        msg.text = "Pages containing $word are allowed through the keyword filter."
                        drawRules()
                    }
                }
            })
        } else {
            views.add(button("Set task password to add exceptions") { goTo { showTaskPasswords() } })
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
        val password = passwordField("Add per-word exceptions password")
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
                    "Adding an exception requires the task password; removing one is immediate. " +
                    "App locks, accessibility requirements, the Google VPN requirement and site restrictions still apply."),
            label("Blocked word"), selected,
            label("Current exceptions", 20f), rules,
            allowed, msg
        )
        if (TaskPasswords.ready(this)) {
            views.add(password)
            views.add(button("Add exception (task password)") {
                val blocked = words.getOrNull(selected.selectedItemPosition) ?: return@button
                val exception = KeywordMatcher.normalizeWord(allowed.text.toString())
                when {
                    exception == null -> msg.text = "Use 1–80 characters: letters, numbers, spaces, apostrophes or hyphens."
                    exception == blocked -> msg.text = "Choose a different word from the blocked word."
                    blocked !in Store.browserKeywords(this) -> msg.text = "This blocked word has been removed. Tap Refresh."
                    exception in Store.keywordExceptions(this)[blocked].orEmpty() -> msg.text = "This exception already exists."
                    TaskPasswords.remaining(this, TaskScope(TaskKind.WORD_EXCEPTION)) > 0 -> msg.text = "Too many attempts. Try again later."
                    !Store.addKeywordException(this, blocked, exception, password.text.toString()) -> msg.text = "Wrong task password."
                    else -> {
                        allowed.setText("")
                        password.setText("")
                        msg.text = "$blocked is allowed when $exception also appears on the page."
                        drawRules()
                    }
                }
            })
        } else {
            views.add(button("Set task password to add exceptions") { goTo { showTaskPasswords() } })
        }
        views.add(button("Refresh") { reopen() })
        views.add(button("Back") { back() })
        screen(*views.toTypedArray())
        drawRules()
    }

    // ---------- password-protected timing settings ----------

    private fun openSettings() { goTo { showSettings() } }

    private fun showSettings() {
        val config = Store.timingConfig(this)
        val fields = TimingOption.entries.associateWith { option -> EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER; setText(config[option].toString()); hint = option.title
        } }
        val presets = EditText(this).apply { setText(config.unlockPresets.joinToString(", ")); hint = "Unlock presets in minutes" }
        val boxes = PlayInstallCategory.entries.associateWith { category -> CheckBox(this).apply {
            text = category.title; minHeight = dp(48); isChecked = category.storeId in Store.immediatePlayCategories(this@MainActivity)
        } }
        val msg = errorLabel()
        fun timingViews(options: List<TimingOption>) = options.flatMap { listOf(label("${it.title} (${it.unit})"), fields.getValue(it)) }.toTypedArray()
        fun save(security: Boolean) {
            val chosen = TimingOption.entries.filter { (it in TaskSettings.security) == security }
            val invalid = chosen.firstOrNull { fields.getValue(it).text.toString().toIntOrNull() !in it.minimum..it.maximum }
            val shortcutValues = if (security) null else presets.text.toString().split(',').map { it.trim().toIntOrNull() }
            if (invalid != null) { fields.getValue(invalid).error = "Enter ${invalid.minimum} to ${invalid.maximum}."; reveal(fields.getValue(invalid)); return }
            if (shortcutValues?.any { it == null } == true) { presets.error = "Enter whole minutes separated by commas."; return }
            val changes = chosen.associateWith { fields.getValue(it).text.toString().toInt() }
            val shortcut = shortcutValues?.filterNotNull()
            val validation = TimingConfig(TimingOption.entries.associateWith { changes[it] ?: config[it] }, shortcut ?: config.unlockPresets).validationError()
            if (validation != null) { msg.text = validation; return }
            withTaskPassword(TaskScope(if (security) TaskKind.SECURITY else TaskKind.TIMING), if (security) "Save password security settings" else "Save timing settings") {
                TaskSettings.save(this, changes, shortcut, security, it)
            }
        }
        screen(label("Settings", 22f),
            label("Viewing settings is free. Each Save button asks for that task's password once. It does not unlock any other settings."),
            section("Play Store immediate access", "Separate category-settings password",
                label("Verified apps in selected categories skip the installation wait. Other locks still apply."),
                section("App categories", "Choose app types", *boxes.filterKeys { !it.isGame }.values.toTypedArray()),
                section("Game categories", "Choose game genres", *boxes.filterKeys { it.isGame }.values.toTypedArray()),
                primaryButton("Save category choices") {
                    val selected = boxes.filterValues { it.isChecked }.keys.map { it.storeId }.toSet()
                    withTaskPassword(TaskScope(TaskKind.CATEGORIES), "Save Play Store categories") { TaskSettings.categories(this, selected, it) }
                }),
            section("Durations and waiting periods", "Timing password only; app-specific limits also apply",
                *timingViews(TimingOption.entries.filterNot { it in TaskSettings.security }),
                label("Unlock presets"), presets,
                label("An app's own access limit still applies even if you increase the global maximum. Extra locked sites keep their fixed 45-minute removal wait."),
                button("Fill default timing values") {
                    TimingOption.entries.filterNot { it in TaskSettings.security }.forEach { fields.getValue(it).setText(it.default.toString()) }
                    presets.setText(TimingConfig().unlockPresets.joinToString(", "))
                }, primaryButton("Save timing settings") { save(false) }),
            section("Password security", "Separate security-settings password",
                *timingViews(TaskSettings.security.toList()),
                button("Fill default security values") { TaskSettings.security.forEach { fields.getValue(it).setText(it.default.toString()) } },
                primaryButton("Save password security settings") { save(true) }),
            msg, button("Task passwords") { goTo { showTaskPasswords() } }, button("Back") { back() })
    }

    private fun taskName(scope: TaskScope): String = if (scope.app.isEmpty()) scope.kind.title else {
        val name = try { packageManager.getApplicationInfo(scope.app, 0).loadLabel(packageManager).toString() }
            catch (_: Exception) { scope.app }
        "${scope.kind.title}: $name"
    }

    private fun taskJob(msg: TextView, work: () -> String?, success: () -> Unit) {
        if (credentialJob?.isDone == false) { msg.text = "Please wait for the current operation."; return }
        val generation = ++credentialGeneration
        msg.text = "Checking and saving. Please wait."
        credentialJob = credentialExecutor.submit {
            val error = try { work() } catch (_: Exception) { "Could not complete the operation. Please try again." }
            runOnUiThread {
                if (generation == credentialGeneration) {
                    credentialJob = null
                    if (msg.isAttachedToWindow && !isFinishing && !isDestroyed) {
                        if (error == null) success() else msg.text = error
                    }
                }
            }
        }
    }

    private fun withTaskPassword(scope: TaskScope, title: String, operation: (String) -> Boolean) {
        if (!TaskPasswords.ready(this)) { goTo { showTaskSetup() }; return }
        if (!TaskPasswords.has(this, scope)) {
            if (scope.kind.perApp) goTo { showNewAppPasswords(scope.app) }
            else toast("This task has no password. Open Task passwords to check setup.")
            return
        }
        goTo {
            val password = passwordField("Password for ${taskName(scope)}")
            val msg = errorLabel()
            screen(label(title, 22f), label("Required task: ${taskName(scope)}. This authorizes only this operation."),
                password, msg, primaryButton("Confirm this task") {
                    val entered = password.text.toString()
                    if (entered.isBlank()) msg.text = "Enter password"
                    else if (TaskPasswords.remaining(this, scope) > 0) msg.text = "This task is locked out. Try again in ${(TaskPasswords.remaining(this, scope) + 59_999) / 60_000} min."
                    else {
                        password.setText("")
                        taskJob(msg, { if (operation(entered)) null else "Wrong task password, invalid operation, or the task is locked out." }) {
                            Policy.sweep(this)
                            back()
                        }
                    }
                }, button("Cancel") { back() })
        }
    }

    private fun showTaskSetup() {
        if (TaskPasswords.ready(this)) { showTaskPasswords(); return }
        val oldFields = Store.Pw.entries.filter { Store.hasPassword(this, it) }.associateWith {
            passwordField("Current ${it.name.lowercase().replace('_', '-')} password")
        }
        val scopes = TaskPasswords.required(this)
        val fields = scopes.associateWith { passwordField("New password for this task") to passwordField("Confirm new password") }
        val limits = scopes.filter { it.kind == TaskKind.APP_UNLOCK }.associateWith {
            EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; setText("15"); hint = "Maximum minutes per unlock" }
        }
        val sharing = CheckBox(this).apply { text = "I deliberately want tasks with matching passwords to share that password" }
        val msg = errorLabel()
        val views = mutableListOf<View>(label("Set up task passwords", 22f),
            label("One-time upgrade. Existing restrictions stay active. Enter your old passwords, then choose a password for every task below. " +
                "Old filter, settings and app-lock passwords are retired together when setup completes. The main password will open only the dashboard. " +
                "Existing temporary unlocks end during this upgrade."),
            section("Verify existing passwords", "All currently configured passwords are required", *oldFields.values.toTypedArray(), initiallyExpanded = true))
        for (scope in scopes) {
            val pair = fields.getValue(scope)
            val children = mutableListOf<View>(pair.first, pair.second)
            limits[scope]?.let { children.add(label("Maximum access per operation (minutes)")); children.add(it) }
            views.add(section(taskName(scope), "Choose a new password for this task only", *children.toTypedArray()))
        }
        views.addAll(listOf(sharing, msg, primaryButton("Save all task passwords") {
            val mismatch = fields.entries.firstOrNull { it.value.first.text.toString() != it.value.second.text.toString() ||
                !Store.isValidNewPassword(it.value.first.text.toString()) }
            val invalidLimit = limits.entries.firstOrNull { it.value.text.toString().toIntOrNull() !in 1..10080 }
            when {
                oldFields.values.any { it.text.isBlank() } -> { msg.text = "Enter every current password."; reveal(oldFields.values.first { it.text.isBlank() }) }
                mismatch != null -> { msg.text = "Check ${taskName(mismatch.key)}: matching passwords need 8+ characters, a letter and a number."; reveal(mismatch.value.first) }
                invalidLimit != null -> { msg.text = "Choose an access limit from 1 to 10080 minutes."; reveal(invalidLimit.value) }
                else -> {
                    val legacy = oldFields.mapValues { it.value.text.toString() }
                    val items = scopes.map { NewTaskPassword(it, fields.getValue(it).first.text.toString(), limits[it]?.text?.toString()?.toIntOrNull()) }
                    val share = sharing.isChecked
                    taskJob(msg, { TaskPasswords.migrate(this, legacy, items, share) }) {
                        oldFields.values.forEach { it.setText("") }
                        fields.values.forEach { it.first.setText(""); it.second.setText("") }
                        Policy.sweep(this)
                        toast("Task passwords saved. Old broad task passwords are retired.")
                        swapTo { showTaskPasswords() }
                    }
                }
            }
        }, button("Back") { back() }))
        screen(*views.toTypedArray())
    }

    private fun showTaskPasswords() {
        if (!TaskPasswords.ready(this)) { showTaskSetup(); return }
        val views = mutableListOf<View>(label("Task passwords", 22f),
            label("Each password works for one task. There is no master override or password reset using the main password. " +
                "Changing a task password requires its current password. Sharing happens only if you explicitly allow matching passwords."),
            button("Temporary app access") { goTo { showAppAccess() } })
        val scopes = (TaskPasswords.scopes(this) + TaskPasswords.required(this)).sortedBy { taskName(it) }
        for (scope in scopes) {
            if (TaskPasswords.has(this, scope)) views.add(section(taskName(scope), "Password configured",
                button("Change only this password") { goTo { showTaskPasswordEditor(scope) } }))
        }
        Store.protectedApps(this).filter { !TaskPasswords.has(this, TaskScope(TaskKind.APP_UNLOCK, it)) }.forEach { app ->
            views.add(button("Set up app passwords: ${taskName(TaskScope(TaskKind.APP_UNLOCK, app))}") { goTo { showNewAppPasswords(app) } })
        }
        views.add(button("Back") { back() })
        screen(*views.toTypedArray())
    }

    private fun showTaskPasswordEditor(scope: TaskScope) {
        val old = passwordField("Current password for this task")
        val password = passwordField("New password")
        val confirm = passwordField("Confirm new password")
        val share = CheckBox(this).apply { text = "Use a chosen password. I explicitly accept sharing if it matches another credential" }
        val show = CheckBox(this).apply {
            text = "Show new password"
            setOnCheckedChangeListener { _, visible ->
                password.transformationMethod = if (visible) null else android.text.method.PasswordTransformationMethod.getInstance()
                confirm.transformationMethod = if (visible) null else android.text.method.PasswordTransformationMethod.getInstance()
            }
        }
        val msg = errorLabel()
        screen(label(taskName(scope), 22f), label("Change only this task's password. The main password cannot reset it."),
            old, label("Generate a separate password, or explicitly enable a chosen password. Save it somewhere safe before confirming."),
            button("Generate a separate password") { val value = TaskVault.generatePassword(scope); password.setText(value); confirm.setText(value) },
            password, confirm, show, share, msg, primaryButton("Change this password") {
                val before = old.text.toString()
                val next = password.text.toString()
                val shared = share.isChecked
                if (before.isBlank()) msg.text = "Enter current task password"
                else if (next != confirm.text.toString()) msg.text = "Passwords do not match."
                else taskJob(msg, { TaskPasswords.change(this, scope, before, next, shared) }) { back() }
            }, button("Back") { back() })
    }

    private fun showNewAppPasswords(app: String) {
        if (!TaskPasswords.ready(this)) { showTaskSetup(); return }
        val scopes = TaskScope.appTasks(app)
        if (scopes.any { TaskPasswords.has(this, it) }) { showTaskPasswords(); return }
        val fields = scopes.associateWith { passwordField("New task password") to passwordField("Confirm task password") }
        val limit = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; hint = "Maximum minutes per unlock"; setText("15") }
        val enrollment = passwordField("New app password-setup credential")
        val sharing = CheckBox(this).apply { text = "Use chosen passwords. I explicitly accept sharing if they match other credentials" }
        val show = CheckBox(this).apply {
            text = "Show new passwords"
            setOnCheckedChangeListener { _, visible -> fields.values.forEach {
                it.first.transformationMethod = if (visible) null else android.text.method.PasswordTransformationMethod.getInstance()
                it.second.transformationMethod = if (visible) null else android.text.method.PasswordTransformationMethod.getInstance()
            } }
        }
        val msg = errorLabel()
        val views = mutableListOf<View>(label("New app passwords", 22f),
            label("${taskName(scopes.first())}. Adding a lock was immediate. Setting its first passwords requires the new-app setup credential. " +
                "This cannot replace existing app passwords. Generate separate passwords, or explicitly enable chosen passwords. Save them before confirming."), enrollment,
            button("Generate separate app passwords") { fields.forEach { (scope, pair) ->
                val value = TaskVault.generatePassword(scope); pair.first.setText(value); pair.second.setText(value)
            } }, show)
        scopes.forEach { scope -> views.add(section(taskName(scope), "Separate task password", fields.getValue(scope).first, fields.getValue(scope).second)) }
        views.addAll(listOf(label("Maximum minutes for each temporary unlock"), limit, sharing, msg, primaryButton("Set up this app") {
            val minutes = limit.text.toString().toIntOrNull()
            if (fields.values.any { it.first.text.toString() != it.second.text.toString() } || minutes !in 1..10080) msg.text = "Check password confirmations and the access limit."
            else {
                val items = scopes.map { NewTaskPassword(it, fields.getValue(it).first.text.toString(), if (it.kind == TaskKind.APP_UNLOCK) minutes else null) }
                val credential = enrollment.text.toString()
                val shared = sharing.isChecked
                taskJob(msg, { TaskPasswords.enroll(this, app, credential, items, shared) }) { back() }
            }
        }, button("Back") { back() }))
        screen(*views.toTypedArray())
    }

    private fun showAppAccess() {
        val apps = Store.protectedApps(this).sorted()
        val selector = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                apps.map { taskName(TaskScope(TaskKind.APP_UNLOCK, it)).substringAfter(": ") })
        }
        val minutes = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; hint = "Minutes"; setText("15") }
        val shortcuts = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        Store.timingConfig(this).unlockPresets.forEach { duration ->
            shortcuts.addView(button("$duration min") { minutes.setText(duration.toString()) })
        }
        val shortcutScroll = HorizontalScrollView(this).apply { addView(shortcuts); isHorizontalScrollBarEnabled = false }
        val status = label("")
        fun selected() = apps.getOrNull(selector.selectedItemPosition)
        fun update() {
            val app = selected() ?: return
            val cap = minOf(TaskPasswords.vault(this).limit(app), Store.timingConfig(this)[TimingOption.MAX_UNLOCK])
            val until = Store.unlockedUntil(this, app)
            status.text = "Limit: $cap minutes per operation. " + if (until > System.currentTimeMillis())
                "Temporarily unlocked until ${DateFormat.getTimeInstance().format(Date(until))}. Other restrictions still apply."
                else "No temporary access is active. Other restrictions still apply."
            minutes.setText(minOf(Store.timingConfig(this)[TimingOption.DEFAULT_UNLOCK], cap.coerceAtLeast(1)).toString())
            Store.timingConfig(this).unlockPresets.forEachIndexed { index, duration -> shortcuts.getChildAt(index).isEnabled = duration <= cap }
        }
        selector.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = update()
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        screen(label("Temporary app access", 22f), label("Choose one app. Its access password cannot unlock any other app, remove its lock, or change its time limit."),
            selector, status, minutes, shortcutScroll,
            primaryButton("Unlock only the selected app") {
                val app = selected() ?: return@primaryButton
                val duration = minutes.text.toString().toIntOrNull()
                val cap = minOf(TaskPasswords.vault(this).limit(app), Store.timingConfig(this)[TimingOption.MAX_UNLOCK])
                if (!TaskPasswords.has(this, TaskScope(TaskKind.APP_UNLOCK, app))) goTo { showNewAppPasswords(app) }
                else if (duration == null || duration !in 1..cap) { minutes.error = "Enter 1 to $cap minutes." }
                else withTaskPassword(TaskScope(TaskKind.APP_UNLOCK, app), "Unlock only ${taskName(TaskScope(TaskKind.APP_UNLOCK, app)).substringAfter(": ")}") {
                    Store.unlockApp(this, app, duration, it)
                }
            }, button("Lock selected app now") { selected()?.let { TaskPasswords.lockApp(this, it); Policy.sweep(this); update() } },
            button("Set up this app's first passwords") { selected()?.let { app -> goTo { showNewAppPasswords(app) } } },
            button("Change selected app's access limit") { selected()?.let { app -> goTo { showAppLimit(app) } } },
            button("Task passwords") { goTo { showTaskPasswords() } }, button("Back") { back() })
        if (apps.isEmpty()) status.text = "No apps are manually locked. Add one in App locks."
    }

    private fun showAppLimit(app: String) {
        val field = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(TaskPasswords.vault(this@MainActivity).limit(app).toString())
        }
        screen(label(taskName(TaskScope(TaskKind.APP_LIMIT, app)), 22f),
            label("This app's separate limit password is required. Its temporary-access password cannot change this limit. Global maximum durations also apply."),
            field, primaryButton("Save limit for this app") {
                val minutes = field.text.toString().toIntOrNull()
                if (minutes !in 1..10080) field.error = "Enter 1 to 10080 minutes."
                else withTaskPassword(TaskScope(TaskKind.APP_LIMIT, app), "Change only this app's access limit") {
                    TaskPasswords.setLimit(this, app, minutes!!, it)
                }
            }, button("Back") { back() })
    }

    private fun showAbout() {
        screen(label("About Install Lock", 22f),
            label("Device protection, app locks and website rules in one place."),
            label("Visual design inspired by Curbox: Material 3 surfaces, rounded cards and Inter typography."),
            section("Website category data", "UT1 manga/comics catalog · CC BY-SA 4.0",
                label(assets.open("categories/README.txt").bufferedReader().use { it.readText() }, 13f),
                label(assets.open("licenses/CC-BY-SA-4.0.txt").bufferedReader().use { it.readText() }, 13f)),
            section("Inter font license", "The Inter Project Authors · SIL Open Font License 1.1",
                label(assets.open("licenses/Inter-OFL.txt").bufferedReader().use { it.readText() }, 13f)),
            button("Back") { back() })
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
        if (credentialJob?.isDone == false) {
            credentialGeneration++
            credentialJob?.cancel(true)
            credentialJob = null
        }
        UiStyle.styleInputs(content)
        val root = FrameLayout(this)
        root.setBackgroundColor(UiStyle.color(this, com.google.android.material.R.attr.colorSurface))
        val frame = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (history.isNotEmpty()) {
            val toolbar = LinearLayout(this).apply {
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(8), 0, dp(20), 0)
                addView(UiStyle.backButton(this@MainActivity) { back() }, LinearLayout.LayoutParams(dp(48), dp(48)))
                addView(UiStyle.label(this@MainActivity, "Install Lock", 14f))
            }
            frame.addView(toolbar, LinearLayout.LayoutParams(-1, dp(56)))
        }
        frame.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(
            frame,
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
        col.setPadding(dp(20), dp(20), dp(20), dp(32))
        views.forEach { col.addView(it, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(if (it is ExpandableSection) 16 else 8)
            }) }
        val scroll = ScrollView(this)
        scroll.isFillViewport = true
        scroll.addView(col)
        show(scroll)
    }

    private fun label(t: String, size: Float = 16f): TextView = UiStyle.label(this, t, size)

    private fun errorLabel() = label("").apply {
        setTextColor(UiStyle.color(this@MainActivity, androidx.appcompat.R.attr.colorError))
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }

    private fun primaryButton(t: String, onClick: () -> Unit): Button =
        UiStyle.button(this, t, primary = true, onClick = onClick)

    private fun passwordField(hintText: String): EditText {
        val v = EditText(this)
        v.hint = hintText
        v.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        v.isSaveEnabled = false
        return v
    }

    private fun button(t: String, onClick: () -> Unit): Button {
        return UiStyle.button(this, t, onClick = onClick)
    }
}
