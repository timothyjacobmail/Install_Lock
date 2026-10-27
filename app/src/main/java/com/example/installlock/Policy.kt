package com.example.installlock

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.UserManager
import android.os.Bundle
import android.util.Log
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.app.PendingIntent
import android.content.pm.PackageInfo
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityManager


object Policy {
    fun dpm(c: Context): DevicePolicyManager =
        c.getSystemService(DevicePolicyManager::class.java)

    fun admin(c: Context) = ComponentName(c, AdminReceiver::class.java)

    fun isOwner(c: Context) = dpm(c).isDeviceOwnerApp(c.packageName)

    /** One-time hardening, safe to call repeatedly. */
    fun apply(c: Context) {
        if (!isOwner(c)) return
        updateSupportMessages(c)
        val dpm = dpm(c)
        val admin = admin(c)
        // Stop the clock being changed to cheat the timer
        dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_DATE_TIME)
        dpm.setAutoTimeEnabled(admin, true)
        // Preserve all configured protected apps when refreshing our own process protection.
        enforceNoUninstall(c)
        if (Build.VERSION.SDK_INT >= 33) {
            dpm.setPermissionGrantState(
                admin, c.packageName, Manifest.permission.POST_NOTIFICATIONS,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
            )
        }
    }

    /** Android owns the restriction dialog. Supply its supported admin explanation text. */
    internal fun updateSupportMessages(c: Context) {
        if (!isOwner(c)) return
        try {
            val manager = dpm(c)
            val receiver = admin(c)
            val shortMessage = c.getString(R.string.policy_support_short)
            val longMessage = c.getString(R.string.policy_support_long)
            if (manager.getShortSupportMessage(receiver)?.toString() != shortMessage)
                manager.setShortSupportMessage(receiver, shortMessage)
            if (manager.getLongSupportMessage(receiver)?.toString() != longMessage)
                manager.setLongSupportMessage(receiver, longMessage)
        } catch (e: RuntimeException) {
            // Explanation text is optional; a failed update must never interrupt enforcement.
            Log.w("InstallLock", "Could not update Android's restriction explanation", e)
        }
    }

    /** Suspend new installs and protected apps whose unlock window has ended. */
    const val CHROME = "com.android.chrome"

    /** Set to true if a site that has just become active still shows "blocked" in an
     *  already-open Chrome until you swipe Chrome away. Chrome is then bounced automatically. */
    const val RESTART_CHROME_ON_CHANGE = false

    private val KEEP = setOf(CHROME, "com.google.android.googlequicksearchbox", "com.android.vending")

    /** Package names of browsers you want to keep while the Chrome lock is on. */
    private val ALLOWED_BROWSERS = emptySet<String>()

    /** Refresh enforcement first so the UI reports the same rules and actual Android state. */
    fun lockStatuses(c: Context): List<AppLockStatus> = reconcile(c, collectStatus = true)

    fun sweep(c: Context) {
        reconcile(c, collectStatus = false)
    }

    private fun reconcile(c: Context, collectStatus: Boolean, releaseTargets: Set<String> = emptySet()): List<AppLockStatus> {
        if (!isOwner(c)) return emptyList()
        enforceNoUninstall(c)
        val dpm = dpm(c)
        val admin = admin(c)
        val pm = c.packageManager
        val now = System.currentTimeMillis()
        val cooldownRemaining = WatchdogStore.remaining(c)
        val focusRemaining = FocusLockStore.remaining(c)
        val focusTargets = FocusLockStore.targets(c)
        val focusManaged = FocusLockStore.managed(c)
        val discoveredBrowsers = BrowserPackages.discover(c)
        val browserTargets = discoveredBrowsers + BrowserPackages.GOOGLE_APP
        val monitoredTargets = browserTargets + ShortFormFeeds.nativePackages
        val cooldownTargets = monitoredTargets + "com.android.vending"
        val rapidCooldown = WatchdogStore.rapidCooldown(c)
        try { WatchdogStore.enforceInstallLock(c, cooldownRemaining > 0 || focusRemaining > 0) }
        catch (e: Exception) { Log.w("InstallLock", "Could not reconcile cooldown install restriction; will retry", e) }
        val timings = Store.timingConfig(c)
        val homePkg = pm.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
        )?.activityInfo?.packageName
        val protectedApps = Store.protectedApps(c)
        val installed = pm.getInstalledPackages(0)
        val curfewTargets = CurfewStore.enroll(c, installed, homePkg, now)
        val curfewManaged = CurfewStore.managed(c)
        val curfewUntil = CurfewRules.blockedUntil(now, CurfewStore.zone(), CurfewStore.appealedNight(c))
        val browsers = if (Store.chromeLock(c)) otherBrowsers(c) else emptySet()
        val previousKeywordGuard = Store.keywordGuardPackages(c)
        val accessibilityEnabled = BrowserKeywordService.isEnabled(c)
        val keywordFilterOn = Store.browserKeywordFilterEnabled(c)
        val domainRulesOn = Store.domainRules(c).isNotEmpty()
        val keywordBrowsers = if (!BrowserKeywordService.isReady(c))
            monitoredTargets + previousKeywordGuard else emptySet()
        val keywordReleaseRetries = mutableSetOf<String>()
        val googleVpnMissing = !DnsFilterService.isReady(c)
        val neverRules = Store.neverInstall(c)
        val selectedCategories = Store.immediatePlayCategories(c)
        val installWaitMs = timings.milliseconds(TimingOption.INSTALL_WAIT)
        val statuses = mutableListOf<AppLockStatus>()

        fun record(pkg: PackageInfo, info: ApplicationInfo, reasons: AppLockReasons, categoryExemption: String? = null) {
            if (!collectStatus) return
            val suspended = try { dpm.isPackageSuspended(admin, pkg.packageName) }
                catch (_: Exception) { null }
            // Keep managed apps visible when unlocked, and include any unexpected suspension.
            if (reasons.shouldLock || suspended != false || pkg.packageName in protectedApps ||
                pkg.packageName == CHROME || pkg.packageName == BrowserPackages.GOOGLE_APP || categoryExemption != null) {
                statuses.add(AppLockStatus(pkg.packageName, info.loadLabel(pm).toString(), reasons, suspended, categoryExemption))
            }
        }

        // Allow the configured grace period after Curbox stops, including during updates and reboots.
        val guardOn = Store.curboxGuard(c)
        var curboxDown = false
        if (guardOn && !curboxRunning(c)) {
            if (curboxDownSince == 0L) curboxDownSince = now
            curboxDown = now - curboxDownSince >= timings.milliseconds(TimingOption.CURBOX_GRACE)
        } else {
            curboxDownSince = 0L
        }

        for (pkg in installed) {
            val info = pkg.applicationInfo ?: continue
            val name = pkg.packageName
            if (name == c.packageName || name == homePkg) continue
            val isSystem = info.flags and ApplicationInfo.FLAG_SYSTEM != 0

            // Apps that must never stay installed: suspend at once, then uninstall.
            val neverRule = if (!isSystem && neverRules.isNotEmpty())
                blockedRule(neverRules, name, labelOf(pm, pkg, info)) else null

            val isProtected = name in protectedApps
            val isBrowser = name in browsers
            val isGuarded = guardOn && name in CURBOX_GUARDED
            val keywordPaused = name in keywordBrowsers
            val isGoogle = name == BrowserPackages.GOOGLE_APP
            // Previously guarded system apps must also be processed so they can be resumed.
            if (isSystem && !isProtected && !isBrowser && !isGuarded &&
                !keywordPaused && !isGoogle && name !in previousKeywordGuard && name !in releaseTargets && name !in curfewManaged &&
                name !in cooldownTargets && name !in focusManaged) {
                // Read status without changing policy for unrelated system apps.
                if (name == CHROME) record(pkg, info, AppLockReasons())
                continue
            }

            val newInstall = PlayCategoryRules.installWaitUntil(isSystem, pkg.firstInstallTime, now, installWaitMs) != null
            val categoryExemption = if (newInstall && neverRule == null) PlayCategoryLookup.exemption(c, pkg, selectedCategories) else null
            val reasons = AppLockReasons(
                keywordServiceMissing = keywordPaused && keywordFilterOn && name !in ShortFormFeeds.nativePackages,
                domainServiceMissing = keywordPaused && domainRulesOn && !keywordFilterOn && name !in ShortFormFeeds.nativePackages,
                accessibilityEnabled = accessibilityEnabled,
                appLock = isProtected && now >= Store.unlockedUntil(c, name),
                installWaitUntil = PlayCategoryRules.installWaitUntil(isSystem, pkg.firstInstallTime, now,
                    installWaitMs, categoryExemption),
                otherBrowser = isBrowser,
                curboxMissing = isGuarded && curboxDown,
                neverInstallRule = neverRule,
                googleVpnMissing = isGoogle && googleVpnMissing,
                curfewUntil = curfewUntil.takeIf { name in curfewTargets },
                focusUntil = (now + focusRemaining).takeIf { focusRemaining > 0 && name in focusTargets },
                categoryCooldownUntil = (now + cooldownRemaining).takeIf { cooldownRemaining > 0 && name in cooldownTargets },
                categoryServiceMissing = keywordPaused,
                rapidScrollCooldown = rapidCooldown
            )
            val shouldLock = reasons.shouldLock
            try {
                if (dpm.isPackageSuspended(admin, name) != shouldLock) {
                    val failed = dpm.setPackagesSuspended(admin, arrayOf(name), shouldLock)
                    if (name in failed && name in previousKeywordGuard) keywordReleaseRetries.add(name)
                }
            } catch (_: Exception) {
                if (name in previousKeywordGuard) keywordReleaseRetries.add(name)
            }
            record(pkg, info, reasons, categoryExemption?.displayName)
            if (neverRule != null) requestUninstall(c, name)
        }
        Store.setKeywordGuardPackages(c, keywordBrowsers + keywordReleaseRetries)
        syncChrome(c)
        return statuses.sortedWith(
            compareByDescending<AppLockStatus> { it.packageName == CHROME }
                .thenByDescending { it.needsAttention }.thenBy { it.appName.lowercase() })
    }

    private val labelCache = HashMap<String, String>()
    private val uninstallAsked = HashMap<String, Long>()

    /** Lowercase app name, cached until the app is updated. */
    private fun labelOf(pm: PackageManager, pkg: PackageInfo, info: ApplicationInfo): String =
        labelCache.getOrPut(pkg.packageName + "|" + pkg.lastUpdateTime) {
            info.loadLabel(pm).toString().lowercase()
        }

    /** The rule that makes this app unwelcome: its package name or app name contains it. */
    private fun blockedRule(rules: Set<String>, pkgName: String, label: String): String? =
        rules.firstOrNull { pkgName.lowercase().contains(it) || label.contains(it) }

    /** Silent uninstall, throttled per app by the configured retry interval. */
    private fun requestUninstall(c: Context, pkg: String) {
        val now = System.currentTimeMillis()
        if (now - (uninstallAsked[pkg] ?: 0L) <
            Store.timingConfig(c).milliseconds(TimingOption.UNINSTALL_RETRY)) return
        uninstallAsked[pkg] = now
        try {
            val intent = Intent("com.example.installlock.UNINSTALL_RESULT").setPackage(c.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(c, 0, intent, flags)
            c.packageManager.packageInstaller.uninstall(pkg, pi.intentSender)
        } catch (_: Exception) { }
    }

    /** Names of installed, non-system apps that a new rule would remove. */
    fun matchesFor(c: Context, rule: String): List<String> {
        val pm = c.packageManager
        return pm.getInstalledPackages(0).mapNotNull { pkg ->
            val info = pkg.applicationInfo ?: return@mapNotNull null
            val isSystem = info.flags and ApplicationInfo.FLAG_SYSTEM != 0
            if (isSystem || pkg.packageName == c.packageName) return@mapNotNull null
            if (blockedRule(setOf(rule), pkg.packageName, labelOf(pm, pkg, info)) == null) null
            else info.loadLabel(pm).toString()
        }
    }
    /** Every app that can open a normal web link, except Chrome and a few essentials. */
    fun otherBrowsers(c: Context): Set<String> {
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        return c.packageManager.queryIntentActivities(probe, PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName }
            .filter { it != c.packageName && it !in KEEP && it !in ALLOWED_BROWSERS }
            .toSet()
    }

    /** Reconcile with Android's actual restrictions; a saved "sent" flag can become stale. */
    fun syncChrome(c: Context, force: Boolean = false) {
        if (!isOwner(c)) return
        try {
            val manager = dpm(c)
            val owner = admin(c)
            val current = manager.getApplicationRestrictions(owner, CHROME)
            val updated = ChromeRestrictions.update(current, Store.chromeLock(c), Store.activePatterns(c),
                Store.extraLockedSites(c).map { it.domain })
            if (updated == null && !force) return
            manager.setApplicationRestrictions(owner, CHROME, updated ?: Bundle(current))
            if (RESTART_CHROME_ON_CHANGE) bounceChrome(c)
        } catch (e: Exception) {
            // Leave reconciliation pending: the next service tick will try again.
            Log.w("InstallLock", "Could not apply Chrome whitelist", e)
        }
    }

    private fun bounceChrome(c: Context) {
        val dpm = dpm(c)
        val admin = admin(c)
        try {
            if (dpm.isPackageSuspended(admin, CHROME)) return   // not running; reads policy on next start
            dpm.setPackagesSuspended(admin, arrayOf(CHROME), true)
            Handler(Looper.getMainLooper()).postDelayed({
                release(c, listOf(CHROME))
            }, Store.timingConfig(c).milliseconds(TimingOption.CHROME_RESTART))
        } catch (_: Exception) { }
    }

    fun setChromeLock(c: Context, on: Boolean, password: String? = null): Boolean {
        if (!on && !TaskPasswords.check(c, TaskScope(TaskKind.WHITELIST_OFF), password.orEmpty())) return false
        Store.setChromeLock(c, on)
        if (!on) release(c, otherBrowsers(c))   // system browsers aren't un-suspended by sweep
        sweep(c)
        return true
    }

    fun enableGoogleFilter(c: Context): String {
        if (!isOwner(c)) return "Not device owner."
        return try {
            // Device-wide VPN lockdown would also block unrelated apps. Google itself
            // stays suspended until our DNS tunnel is ready instead.
            dpm(c).setAlwaysOnVpnPackage(admin(c), c.packageName, false)
            c.startForegroundService(Intent(c, DnsFilterService::class.java))
            sweep(c)
            "Starting Install Lock's VPN. Google remains locked until it is running; other protections still apply."
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    fun disableGoogleFilter(c: Context, password: String): String? {
        if (!TaskPasswords.check(c, TaskScope(TaskKind.VPN_OFF), password)) return null
        if (!isOwner(c)) return "Not device owner."
        return try {
            dpm(c).setAlwaysOnVpnPackage(admin(c), null, false)
            c.stopService(Intent(c, DnsFilterService::class.java))
            DnsFilterService.disconnect()
            sweep(c)
            "VPN is off. Google is locked until Install Lock's VPN is running again."
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
    /** Immediately unsuspend packages (used when an app is removed from the protected list). */
    fun release(c: Context, packages: Collection<String>) {
        if (!isOwner(c) || packages.isEmpty()) return
        reconcile(c, collectStatus = false, releaseTargets = packages.toSet())
    }
    fun isScreenCaptureBlocked(c: Context): Boolean =
        dpm(c).getScreenCaptureDisabled(admin(c))

    fun setScreenCaptureBlocked(c: Context, blocked: Boolean, password: String? = null): String? {
        if (!blocked && !TaskPasswords.check(c, TaskScope(TaskKind.SCREENSHOTS_ALLOW), password.orEmpty())) return null
        if (!isOwner(c)) return "Not device owner."
        return try {
            dpm(c).setScreenCaptureDisabled(admin(c), blocked)
            if (blocked) "Screenshots and screen recording are blocked in all apps."
            else "Screenshots are allowed again."
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
    const val CURBOX = "neth.iecal.curbox"

    /** Paused whenever the Curbox guard is on and Curbox's accessibility service isn't running. */
    val CURBOX_GUARDED = setOf("com.google.android.youtube", "com.instagram.android")

    private var curboxDownSince = 0L

    fun isInstalled(c: Context, pkg: String): Boolean =
        try { c.packageManager.getPackageInfo(pkg, 0); true } catch (_: Exception) { false }

    /** True if Curbox's accessibility service is currently enabled. */
    fun curboxRunning(c: Context): Boolean {
        val am = c.getSystemService(AccessibilityManager::class.java)
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo?.serviceInfo?.packageName == CURBOX }
    }

    /** Chosen apps (plus Install Lock itself) can't be uninstalled, force-stopped or have data cleared. */
    fun enforceNoUninstall(c: Context) {
        if (!isOwner(c)) return
        val dpm = dpm(c)
        val admin = admin(c)
        val wanted = (Store.noUninstall(c) + c.packageName).filter { isInstalled(c, it) }
        for (pkg in wanted) {
            try {
                if (!dpm.isUninstallBlocked(admin, pkg)) dpm.setUninstallBlocked(admin, pkg, true)
            } catch (e: RuntimeException) {
                Log.w("InstallLock", "Could not protect $pkg from uninstall; will retry", e)
            }
        }
        // Force-stop/data-clear protection must still apply if one uninstall policy fails.
        try {
            if (dpm.getUserControlDisabledPackages(admin).toSet() != wanted.toSet()) {
                dpm.setUserControlDisabledPackages(admin, wanted)
            }
        } catch (e: RuntimeException) {
            Log.w("InstallLock", "Could not protect selected apps from force stop/data clearing; will retry", e)
        }
    }

    /** Lifts both protections for removed apps and reapplies them for the remaining selection. */
    fun allowUninstall(c: Context, pkgs: Collection<String>) {
        if (!isOwner(c)) return
        for (pkg in pkgs.filter { it != c.packageName }) {
            try { dpm(c).setUninstallBlocked(admin(c), pkg, false) } catch (_: Exception) { }
        }
        enforceNoUninstall(c)
    }
}
