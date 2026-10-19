package com.example.installlock

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

object CurfewStore {
    private fun prefs(c: Context) = c.getSharedPreferences("curfew", Context.MODE_PRIVATE)
    fun zone(): ZoneId = ZoneId.systemDefault()
    fun appealedNight(c: Context): String? = prefs(c).getString("appealedNight", null)
    fun targets(c: Context): Set<String> = prefs(c).getStringSet("targets", emptySet()).orEmpty().toSet()

    @Synchronized
    fun enroll(c: Context, packages: List<PackageInfo>, defaults: Set<String>, home: String?, now: Long): Set<String> {
        val p = prefs(c)
        val zone = zone()
        val activation = p.getString("activationDay", null)?.let(LocalDate::parse)
            ?: Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val enrolled = packages.filter { pkg ->
            CurfewRules.enrollInstall(pkg.firstInstallTime,
                (pkg.applicationInfo?.flags ?: ApplicationInfo.FLAG_SYSTEM) and ApplicationInfo.FLAG_SYSTEM != 0,
                activation, now, zone)
        }.map { it.packageName }
        val before = targets(c)
        val after = (before + defaults + enrolled) - setOfNotNull(c.packageName, home)
        if (!p.contains("activationDay") || before != after) {
            check(p.edit().putString("activationDay", activation.toString()).putStringSet("targets", after).commit())
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
