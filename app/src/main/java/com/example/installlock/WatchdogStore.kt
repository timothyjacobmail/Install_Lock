package com.example.installlock

import android.content.Context
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings

internal object WatchdogStore {
    fun monitoredPackages(c: Context) = BrowserPackages.keywordTargets(c) + ShortFormFeeds.nativePackages
    fun cooldownPackages(c: Context) = monitoredPackages(c) + "com.android.vending"
    private fun prefs(c: Context) = c.getSharedPreferences("category_watchdog", Context.MODE_PRIVATE)
    @Volatile private var processBoot: Int? = null
    // A process cannot survive a device reboot. Avoid a settings-provider call every 250ms.
    private fun boot(c: Context): Int = processBoot ?: synchronized(this) {
        processBoot ?: Settings.Global.getInt(c.contentResolver, Settings.Global.BOOT_COUNT, 0).also { processBoot = it }
    }
    fun clock(c: Context) = WatchdogClock(System.currentTimeMillis(), SystemClock.elapsedRealtime(),
        boot(c))
    @Volatile private var bundled: Set<String>? = null
    private val domainCache = ParsedValueCache<Pair<Set<String>, Set<String>>, Set<String>> {
        (catalog, added) -> catalog + WebsiteCategories.additions + added
    }
    fun domains(c: Context): Set<String> {
        val catalog = bundled ?: synchronized(this) {
            bundled ?: c.assets.open("categories/manga-domains.txt").bufferedReader().use {
                WebsiteCategories.parse(it.readText())
            }.also { bundled = it }
        }
        return domainCache.get(catalog to prefs(c).getStringSet("addedDomains", emptySet()).orEmpty())
    }
    @Synchronized fun addDomain(c: Context, input: String): Boolean {
        val host = DomainRules.hostFromAddress(input) ?: return false
        if ('.' !in host) return false
        return prefs(c).edit().putStringSet("addedDomains",
            prefs(c).getStringSet("addedDomains", emptySet()).orEmpty() + host).commit()
    }
    private val categoryStateCache = ParsedValueCache<String, WatchdogState>(::decode)
    private val rapidStateCache = ParsedValueCache<String, WatchdogState>(::decode)
    private fun read(c: Context, key: String = "state"): WatchdogState =
        (if (key == "state") categoryStateCache else rapidStateCache).get(prefs(c).getString(key, "").orEmpty())

    private fun decode(raw: String): WatchdogState {
        val a = raw.split('|')
        return try {
            if (a.size != 10) WatchdogState() else WatchdogState(a[0].toLong(), a[1].toLong(), a[2].toLong(),
                a[3].toInt(), a[4].toLong(), a[5].toInt(), a[6] == "1", a[7].toLong(), a[8].toLong(), a[9].toInt())
        } catch (_: NumberFormatException) { WatchdogState() }
    }
    private fun write(c: Context, s: WatchdogState, durable: Boolean, key: String = "state") {
        val value = listOf(s.spent, s.seenWall, s.seenElapsed, s.seenBoot, s.sampleElapsed, s.sampleBoot,
            if (s.visible) 1 else 0, s.untilWall, s.untilElapsed, s.cooldownBoot).joinToString("|")
        val edit = prefs(c).edit().putString(key, value)
        if (durable) edit.commit() else edit.apply()
    }
    @Synchronized fun observe(c: Context, categoryVisible: Boolean) = observeKey(c, categoryVisible, "state")
    @Synchronized fun observeRapid(c: Context, scrolling: Boolean) = observeKey(c, scrolling, "rapidState")

    private fun observeKey(c: Context, visible: Boolean, key: String): WatchdogState {
        val now = clock(c)
        // A second detector cannot start or extend a cooldown already imposed by the first.
        if (remaining(c, now) > 0) return read(c, key)
        val old = read(c, key)
        val next = CategoryWatchdog.observe(old, visible, now)
        if (next != old) {
            write(c, next, next.untilWall != old.untilWall, key)
            if (next.untilWall != 0L) write(c, WatchdogState(), true,
                if (key == "state") "rapidState" else "state")
        }
        return next
    }
    @Synchronized fun remaining(c: Context): Long = remaining(c, clock(c))
    private fun remaining(c: Context, now: WatchdogClock): Long {
        // Rebase once after reboot and clear expired state; never extend a running cooldown.
        return listOf("state", "rapidState").maxOf { key ->
            val old = read(c, key)
            val next = if (old.untilWall != 0L) CategoryWatchdog.observe(old, false, now) else old
            if (next != old) write(c, next, true, key)
            CategoryWatchdog.remaining(next, now)
        }
    }
    @Synchronized fun rapidCooldown(c: Context) = CategoryWatchdog.remaining(read(c, "rapidState"), clock(c)) > 0

    /** Only remove the install restriction this feature added, and retry Android failures on each sweep. */
    @Synchronized fun enforceInstallLock(c: Context, active: Boolean) {
        val p = prefs(c)
        val dpm = Policy.dpm(c)
        val admin = Policy.admin(c)
        val key = UserManager.DISALLOW_INSTALL_APPS
        if (active) {
            if (!dpm.getUserRestrictions(admin).getBoolean(key)) {
                check(p.edit().putBoolean("ownsInstallLock", true).commit())
                dpm.addUserRestriction(admin, key)
            }
        } else if (p.getBoolean("ownsInstallLock", false)) {
            dpm.clearUserRestriction(admin, key)
            p.edit().remove("ownsInstallLock").commit()
        }
    }
}
