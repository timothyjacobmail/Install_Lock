package com.example.installlock

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object Store {
    const val UNLOCK_MS = 30 * 60 * 1000L
    const val SITE_DELAY_MS = 15 * 60 * 1000L
    /** Longest unlock you can request. Raise or lower this to taste. */
    const val MAX_UNLOCK_MIN = 120

    fun lastUnlockMinutes(c: Context): Int = p(c).getInt("lastUnlockMin", 30)

    fun setLastUnlockMinutes(c: Context, m: Int) =
        p(c).edit().putInt("lastUnlockMin", m).apply()

    // ---------- apps that must never stay installed ----------

    private val DEFAULT_NEVER_INSTALL = setOf("grok", "webtoon")

    fun neverInstall(c: Context): Set<String> =
        p(c).getStringSet("neverInstall", DEFAULT_NEVER_INSTALL)!!.toSet()

    fun setNeverInstall(c: Context, s: Set<String>) =
        p(c).edit().putStringSet("neverInstall", s).apply()

    /** Turns a name, package name or Play Store link into a lowercase rule. Null if unusable. */
    fun normalizeRule(input: String): String? {
        var s = input.trim().lowercase()
        Regex("[?&]id=([a-z0-9._]+)").find(s)?.let { s = it.groupValues[1] }
        if (s.length < 3 || !Regex("^[a-z0-9._ -]+$").matches(s)) return null
        return s
    }

    /** The main password, or the separate filter password. */
    enum class Pw(val prefix: String) { MAIN(""), FILTER("f_") }

    private val DEFAULT_PROTECTED = setOf("com.google.android.youtube", "com.android.chrome","com.vivo.browser")
    private val DEFAULT_BLOCKED = setOf(
        "encrypted-tbn0.gstatic.com", "encrypted-tbn1.gstatic.com",
        "encrypted-tbn2.gstatic.com", "encrypted-tbn3.gstatic.com",
        "ytimg.com", "googlevideo.com", "ggpht.com", "googleusercontent.com"
    )

    private fun p(c: Context) = c.getSharedPreferences("install_lock", Context.MODE_PRIVATE)

    // ---------- passwords ----------

    fun hasPassword(c: Context, which: Pw = Pw.MAIN) = p(c).contains(which.prefix + "hash")

    fun lockoutRemainingMs(c: Context, which: Pw = Pw.MAIN) =
        (p(c).getLong(which.prefix + "lockoutUntil", 0L) - System.currentTimeMillis()).coerceAtLeast(0L)

    /** At least 8 characters, with at least one letter and one digit. */
    fun isValidNewPassword(pw: String) =
        pw.length >= 8 && pw.any { it.isLetter() } && pw.any { it.isDigit() }

    fun setPassword(c: Context, pw: String, which: Pw = Pw.MAIN) {
        val k = which.prefix
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        p(c).edit()
            .putString(k + "salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(k + "hash", Base64.encodeToString(hash(pw, salt), Base64.NO_WRAP))
            .putInt(k + "fails", 0)
            .putLong(k + "lockoutUntil", 0L)
            .apply()
    }

    /** True if correct. Wrong guesses trigger escalating lockouts. */
    fun checkPassword(c: Context, pw: String, which: Pw = Pw.MAIN): Boolean {
        val k = which.prefix
        val prefs = p(c)
        if (lockoutRemainingMs(c, which) > 0) return false
        val salt = Base64.decode(prefs.getString(k + "salt", "")!!, Base64.NO_WRAP)
        val expected = Base64.decode(prefs.getString(k + "hash", "")!!, Base64.NO_WRAP)
        if (MessageDigest.isEqual(expected, hash(pw, salt))) {
            prefs.edit().putInt(k + "fails", 0).apply()
            return true
        }
        val fails = prefs.getInt(k + "fails", 0) + 1
        val e = prefs.edit().putInt(k + "fails", fails)
        if (fails >= 5) {
            val minutes = minOf(60L, 1L shl (fails - 5).coerceAtMost(6))
            e.putLong(k + "lockoutUntil", System.currentTimeMillis() + minutes * 60_000L)
        }
        e.apply()
        return false
    }
    // ---------- apps that can't be uninstalled ----------

    fun noUninstall(c: Context): Set<String> =
        p(c).getStringSet("noUninstall", emptySet())!!.toSet()

    fun setNoUninstall(c: Context, s: Set<String>) =
        p(c).edit().putStringSet("noUninstall", s).apply()

    // ---------- Curbox guard ----------

    fun curboxGuard(c: Context) = p(c).getBoolean("curboxGuard", false)

    fun setCurboxGuard(c: Context, on: Boolean) =
        p(c).edit().putBoolean("curboxGuard", on).apply()
    private fun hash(pw: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pw.toCharArray(), salt, 120_000, 256))
            .encoded

    // ---------- protected apps (password-gated unlock) ----------

    fun protectedApps(c: Context): Set<String> =
        p(c).getStringSet("protected", DEFAULT_PROTECTED)!!.toSet()

    fun setProtectedApps(c: Context, s: Set<String>) =
        p(c).edit().putStringSet("protected", s).apply()

    fun unlockedUntil(c: Context) = p(c).getLong("unlockedUntil", 0L)

    fun setUnlockedUntil(c: Context, t: Long) =
        p(c).edit().putLong("unlockedUntil", t).apply()

    // ---------- Google app DNS blocklist ----------

    fun blockedDomains(c: Context): Set<String> =
        p(c).getStringSet("blockedDomains", DEFAULT_BLOCKED)!!.toSet()

    fun setBlockedDomains(c: Context, s: Set<String>) =
        p(c).edit().putStringSet("blockedDomains", s).apply()

    fun isBlocked(c: Context, name: String): Boolean =
        blockedDomains(c).any { name == it || name.endsWith(".$it") }

    // ---------- Chrome whitelist ----------

    data class Site(val pattern: String, val addedAt: Long) {
        val activeAt: Long get() = addedAt + Store.SITE_DELAY_MS
    }

    fun sites(c: Context): List<Site> =
        p(c).getStringSet("sites", emptySet())!!.mapNotNull { raw ->
            val i = raw.indexOf('|')
            val t = if (i > 0) raw.substring(0, i).toLongOrNull() else null
            if (t == null) null else Site(raw.substring(i + 1), t)
        }.sortedBy { it.addedAt }

    fun addSite(c: Context, pattern: String): Boolean {
        val set = p(c).getStringSet("sites", emptySet())!!.toMutableSet()
        if (set.any { it.substringAfter('|') == pattern }) return false
        set.add("${System.currentTimeMillis()}|$pattern")
        p(c).edit().putStringSet("sites", set).apply()
        return true
    }

    fun removeSite(c: Context, pattern: String) {
        val set = p(c).getStringSet("sites", emptySet())!!
            .filterNot { it.substringAfter('|') == pattern }.toSet()
        p(c).edit().putStringSet("sites", set).apply()
    }

    /** Only sites whose 15-minute wait is over. */
    fun activePatterns(c: Context): List<String> {
        val now = System.currentTimeMillis()
        return sites(c).filter { now >= it.activeAt }.map { it.pattern }
    }

    private val SITE_RE = Regex(
        "^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+(:\\d+)?(/[^\\s|*]*)?$"
    )
    private val SUFFIX_LIKE = setOf("co", "com", "org", "net", "gov", "edu", "ac", "or", "ne", "go")

    /** "https://www.Example.com/" -> "example.com". Null if it isn't a specific, safe site. */
    fun normalizeSite(input: String): String? {
        val s = input.trim().lowercase()
            .removePrefix("https://").removePrefix("http://").removePrefix("www.")
            .trimEnd('/')
        if (!SITE_RE.matches(s)) return null
        val labels = s.substringBefore('/').substringBefore(':').split('.')
        if (labels.size == 2 && labels[0] in SUFFIX_LIKE && labels[1].length == 2) return null // e.g. co.uk
        return s
    }

    fun chromeLock(c: Context) = p(c).getBoolean("chromeLock", false)

    fun setChromeLock(c: Context, on: Boolean) =
        p(c).edit().putBoolean("chromeLock", on).apply()

    // ---------- unused now, kept so older code still compiles ----------

    fun filterHost(c: Context): String = p(c).getString("filterHost", "") ?: ""

    fun setFilterHost(c: Context, host: String) =
        p(c).edit().putString("filterHost", host.trim()).apply()
}
