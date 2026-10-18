package com.example.installlock

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.UserManager

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
    fun sweep(c: Context) {
        if (!isOwner(c)) return
        val dpm = dpm(c)
        val admin = admin(c)
        val pm = c.packageManager
        val now = System.currentTimeMillis()
        val homePkg = pm.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
        )?.activityInfo?.packageName

        for (pkg in pm.getInstalledPackages(0)) {
            val info = pkg.applicationInfo ?: continue
            if (info.flags and ApplicationInfo.FLAG_SYSTEM != 0) continue
            val name = pkg.packageName
            if (name == c.packageName || name == homePkg) continue

            val shouldLock = (now - pkg.firstInstallTime) in 0 until LOCK_MS
            try {
                if (dpm.isPackageSuspended(admin, name) != shouldLock) {
                    dpm.setPackagesSuspended(admin, arrayOf(name), shouldLock)
                }
            } catch (_: Exception) { /* package vanished mid-sweep */ }
        }
    }
}