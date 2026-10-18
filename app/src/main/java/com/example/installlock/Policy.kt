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
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
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

        for (pkg in pm.getInstalledPackages(0)) {
            val info = pkg.applicationInfo ?: continue
            val name = pkg.packageName
            val isSystem = info.flags and ApplicationInfo.FLAG_SYSTEM != 0
            val isProtected = name in protectedApps
            val isBrowser = name in browsers
            if (isSystem && !isProtected && !isBrowser) continue
            if (name == c.packageName || name == homePkg) continue

            val newInstall = !isSystem && ((now - pkg.firstInstallTime) in 0 until LOCK_MS)
            val shouldLock = newInstall || (isProtected && gateClosed) || isBrowser
            try {
                if (dpm.isPackageSuspended(admin, name) != shouldLock) {
                    dpm.setPackagesSuspended(admin, arrayOf(name), shouldLock)
                }
            } catch (_: Exception) { /* package vanished mid-sweep */ }
        }
        syncChrome(c)
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

    /** Pushes the active whitelist to Chrome, only when it has changed. */
    fun syncChrome(c: Context) {
        if (!isOwner(c)) return
        val want: String? =
            if (Store.chromeLock(c)) Store.activePatterns(c).sorted().joinToString("\n") else null
        if (want == Store.chromePushed(c)) return
        try {
            val b = Bundle()
            if (want != null) {
                b.putStringArray("URLBlocklist", arrayOf("*"))
                b.putStringArray(
                    "URLAllowlist",
                    want.split("\n").filter { it.isNotEmpty() }.toTypedArray()
                )
            }
            dpm(c).setApplicationRestrictions(admin(c), CHROME, b)
            Store.setChromePushed(c, want)
            if (RESTART_CHROME_ON_CHANGE) bounceChrome(c)
        } catch (_: Exception) { }
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
}