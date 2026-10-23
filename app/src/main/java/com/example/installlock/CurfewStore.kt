package com.example.installlock

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import java.time.ZoneId

object CurfewStore {
    private fun prefs(c: Context) = c.getSharedPreferences("curfew", Context.MODE_PRIVATE)
    fun zone(): ZoneId = ZoneId.systemDefault()
    fun appealedNight(c: Context): String? = prefs(c).getString("appealedNight", null)
    fun targets(c: Context): Set<String> = prefs(c).getStringSet("targets", emptySet()).orEmpty().toSet()
    /** Release bookkeeping only; old entries here are not curfew targets. */
    fun managed(c: Context): Set<String> = prefs(c).getStringSet("managed", emptySet()).orEmpty().toSet()

    @Synchronized
    fun enroll(c: Context, packages: List<PackageInfo>, home: String?, now: Long): Set<String> {
        val p = prefs(c)
        val zone = zone()
        val enrolled = packages.filter { pkg ->
            CurfewRules.enrollInstall(pkg.firstInstallTime,
                (pkg.applicationInfo?.flags ?: ApplicationInfo.FLAG_SYSTEM) and ApplicationInfo.FLAG_SYSTEM != 0,
                now, zone)
        }.map { it.packageName }
        val before = targets(c)
        val excluded = setOfNotNull(c.packageName, home)
        val after = (CurfewRules.permanentPackages + enrolled) - excluded
        // Rebuild instead of accumulating. Keep former system targets visible to policy so
        // migration and expiry can release them, including retries after Android rejects a release.
        val previousManaged = managed(c)
        val nextManaged = (previousManaged + before + after) - excluded
        if (before != after || previousManaged != nextManaged || p.contains("activationDay")) {
            check(p.edit().remove("activationDay").putStringSet("targets", after)
                .putStringSet("managed", nextManaged).commit())
        }
        return after
    }

    /** The deadline is checked again here; a screen left open before 9 pm cannot bypass it. */
    @Synchronized
    fun appeal(c: Context, now: Long = System.currentTimeMillis()): Boolean {
        if (!CurfewRules.canAppeal(now, zone(), appealedNight(c))) return false
        return prefs(c).edit().putString("appealedNight", CurfewRules.night(now, zone()).toString()).commit()
    }
}
