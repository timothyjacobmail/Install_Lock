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
    const val LOCK_MS = 15 * 60 * 1000L

    fun dpm(c: Context): DevicePolicyManager =
        c.getSystemService(DevicePolicyManager::class.java)

    fun admin(c: Context) = ComponentName(c, AdminReceiver::class.java)

    fun isOwner(c: Context) = dpm(c).isDeviceOwnerApp(c.packageName)

    /** One-time hardening, safe to call repeatedly. */
    fun apply(c: Context) {
        if (!isOwner(c)) return
        val dpm = dpm(c)
        val admin = admin(c)
        // Stop the clock being changed to cheat the timer
        dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_DATE_TIME)
        dpm.setAutoTimeEnabled(admin, true)
        // Stop this app being force-stopped / data-cleared
        dpm.setUserControlDisabledPackages(admin, listOf(c.packageName))
        if (Build.VERSION.SDK_INT >= 33) {
            dpm.setPermissionGrantState(
                admin, c.packageName, Manifest.permission.POST_NOTIFICATIONS,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
            )
        }
    }

    /** Suspend anything installed < 15 min ago, unsuspend everything else. */
    /** Suspend new installs (<15 min) and protected apps whose unlock window has ended. */
    const val CHROME = "com.android.chrome"

    /** Set to true if a site that has just become active still shows "blocked" in an
     *  already-open Chrome until you swipe Chrome away. Chrome is then bounced automatically. */
    const val RESTART_CHROME_ON_CHANGE = false

    private val KEEP = setOf(CHROME, "com.google.android.googlequicksearchbox", "com.android.vending")

    /** Package names of browsers you want to keep while the Chrome lock is on. */
    private val ALLOWED_BROWSERS = emptySet<String>()

    fun sweep(c: Context) {
        if (!isOwner(c)) return
        enforceNoUninstall(c)
        val dpm = dpm(c)
        val admin = admin(c)
        val pm = c.packageManager
        val now = System.currentTimeMillis()
        val homePkg = pm.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
        )?.activityInfo?.packageName
        val protectedApps = Store.protectedApps(c)
        val gateClosed = now >= Store.unlockedUntil(c)
        val browsers = if (Store.chromeLock(c)) otherBrowsers(c) else emptySet()
        val neverRules = Store.neverInstall(c)

        // Curbox guard: "down" only after 15 s of its service being off, so updates and reboots don't trip it.
        val guardOn = Store.curboxGuard(c)
        var curboxDown = false
        if (guardOn && !curboxRunning(c)) {
            if (curboxDownSince == 0L) curboxDownSince = now
            curboxDown = now - curboxDownSince >= 15_000L
        } else {
            curboxDownSince = 0L
        }

        for (pkg in pm.getInstalledPackages(0)) {
            val info = pkg.applicationInfo ?: continue
            val name = pkg.packageName
            if (name == c.packageName || name == homePkg) continue
            val isSystem = info.flags and ApplicationInfo.FLAG_SYSTEM != 0

            // Apps that must never stay installed: suspend at once, then uninstall.
            if (!isSystem && neverRules.isNotEmpty() &&
                blockedRule(neverRules, name, labelOf(pm, pkg, info)) != null
            ) {
                try {
                    if (!dpm.isPackageSuspended(admin, name)) {
                        dpm.setPackagesSuspended(admin, arrayOf(name), true)
                    }
                } catch (_: Exception) { }
                requestUninstall(c, name)
                continue
            }

            val isProtected = name in protectedApps
            val isBrowser = name in browsers
            val isGuarded = guardOn && name in CURBOX_GUARDED
            if (isSystem && !isProtected && !isBrowser && !isGuarded) continue

            val newInstall = !isSystem && ((now - pkg.firstInstallTime) in 0 until LOCK_MS)
            val shouldLock = newInstall || (isProtected && gateClosed) || isBrowser ||
                    (isGuarded && curboxDown)
            try {
                if (dpm.isPackageSuspended(admin, name) != shouldLock) {
                    dpm.setPackagesSuspended(admin, arrayOf(name), shouldLock)
                }
            } catch (_: Exception) { /* package vanished mid-sweep */ }
        }
        syncChrome(c)
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

    /** Silent uninstall (a device owner may do this). Asks at most once per 30 seconds per app. */
    private fun requestUninstall(c: Context, pkg: String) {
        val now = System.currentTimeMillis()
        if (now - (uninstallAsked[pkg] ?: 0L) < 30_000L) return
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
            val updated = ChromeRestrictions.update(current, Store.chromeLock(c), Store.activePatterns(c))
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
                try { dpm.setPackagesSuspended(admin, arrayOf(CHROME), false) } catch (_: Exception) { }
            }, 1500)
        } catch (_: Exception) { }
    }

    fun setChromeLock(c: Context, on: Boolean) {
        Store.setChromeLock(c, on)
        if (!on) release(c, otherBrowsers(c))   // system browsers aren't un-suspended by sweep
        sweep(c)
    }

    fun enableGoogleFilter(c: Context): String {
        if (!isOwner(c)) return "Not device owner."
        return try {
            dpm(c).setAlwaysOnVpnPackage(admin(c), c.packageName, false)  // false = fail open
            c.startService(Intent(c, DnsFilterService::class.java))
            "Filter is on. Check Settings → VPN: you should see Install Lock DNS filter."
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    fun disableGoogleFilter(c: Context): String {
        if (!isOwner(c)) return "Not device owner."
        return try {
            dpm(c).setAlwaysOnVpnPackage(admin(c), null, false)
            c.stopService(Intent(c, DnsFilterService::class.java))
            "Filter is off."
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
    /** Immediately unsuspend packages (used when an app is removed from the protected list). */
    fun release(c: Context, packages: Collection<String>) {
        if (!isOwner(c) || packages.isEmpty()) return
        try {
            dpm(c).setPackagesSuspended(admin(c), packages.toTypedArray(), false)
        } catch (_: Exception) { }
    }
    fun isScreenCaptureBlocked(c: Context): Boolean =
        dpm(c).getScreenCaptureDisabled(admin(c))

    fun setScreenCaptureBlocked(c: Context, blocked: Boolean): String {
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
        val dpm = dpm(c)
        val admin = admin(c)
        val wanted = (Store.noUninstall(c) + c.packageName).filter { isInstalled(c, it) }
        try {
            for (pkg in wanted) {
                if (!dpm.isUninstallBlocked(admin, pkg)) dpm.setUninstallBlocked(admin, pkg, true)
            }
            if (dpm.getUserControlDisabledPackages(admin).toSet() != wanted.toSet()) {
                dpm.setUserControlDisabledPackages(admin, wanted)
            }
        } catch (_: Exception) { }
    }

    /** Lifts the uninstall block for apps removed from the list. */
    fun allowUninstall(c: Context, pkgs: Collection<String>) {
        if (!isOwner(c)) return
        for (pkg in pkgs) {
            try { dpm(c).setUninstallBlocked(admin(c), pkg, false) } catch (_: Exception) { }
        }
    }
}
