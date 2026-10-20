package com.example.installlock

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object Store {
    private val domainRuleCache = ParsedValueCache<String, List<DomainRule>> { raw -> raw
        .lineSequence().mapNotNull { DomainRule.decode(it) }.distinctBy { it.id }.toList()
    }
    internal fun domainRules(c: Context): List<DomainRule> = domainRuleCache.get(p(c).getString("domainRules", "").orEmpty())

    private fun saveDomainRules(c: Context, rules: List<DomainRule>) {
        p(c).edit().putString("domainRules", rules.joinToString("\n") { it.encode() }).apply()
    }

    @Synchronized
    internal fun addDomainRule(c: Context, action: DomainAction, type: DomainMatchType, input: String,
                               position: Int, password: String? = null): Boolean {
        val value = DomainRules.normalizeValue(type, input) ?: return false
        val rules = domainRules(c).toMutableList()
        if (position !in 1..rules.size + 1 || rules.any { it.action == action && it.type == type && it.value == value }) return false
        if (action == DomainAction.ALLOW && (password == null ||
                    !TaskPasswords.check(c, TaskScope(TaskKind.DOMAIN_ALLOW), password))) return false
        rules.add(position - 1, DomainRule(java.util.UUID.randomUUID().toString(), action, type, value))
        saveDomainRules(c, rules)
        return true
    }

    @Synchronized
    internal fun moveDomainRule(c: Context, id: String, position: Int, password: String): Boolean {
        val rules = domainRules(c).toMutableList()
        val index = rules.indexOfFirst { it.id == id }
        if (index < 0 || position !in 1..rules.size) return false
        if (!TaskPasswords.check(c, TaskScope(TaskKind.DOMAIN_MOVE), password)) return false
        rules.add(position - 1, rules.removeAt(index))
        saveDomainRules(c, rules)
        return true
    }

    @Synchronized
    internal fun removeDomainRule(c: Context, id: String, password: String? = null): Boolean {
        val rules = domainRules(c)
        val rule = rules.firstOrNull { it.id == id } ?: return false
        if (rule.action == DomainAction.BLOCK && (password == null ||
                    !TaskPasswords.check(c, TaskScope(TaskKind.DOMAIN_REMOVE), password))) return false
        saveDomainRules(c, rules.filterNot { it.id == id })
        return true
    }

    fun immediatePlayCategories(c: Context): Set<String> = PlayCategoryRules.validCategories(
        p(c).getStringSet("immediatePlayCategories", PlayCategoryRules.defaultCategories)!!.toSet())

    /** Saved only from the settings-password-protected editor, like timing settings. */
    fun saveImmediatePlayCategories(c: Context, categories: Set<String>) {
        p(c).edit().putStringSet("immediatePlayCategories", PlayCategoryRules.validCategories(categories)).apply()
    }

    fun timingConfig(c: Context): TimingConfig {
        val prefs = p(c)
        val values = TimingOption.entries.associateWith { prefs.getInt("timing_${it.name}", it.default) }
        val presets = prefs.getString("unlockPresets", "15,30,60,120")!!
            .split(',').mapNotNull { it.toIntOrNull() }
        return TimingConfig(values, presets)
    }

    fun saveTimingConfig(c: Context, config: TimingConfig) {
        require(config.validationError() == null) { config.validationError().orEmpty() }
        val previous = timingConfig(c)
        val prefs = p(c)
        val edit = prefs.edit()
        // Write every field, including defaults, so reset works with partial maps too.
        TimingOption.entries.forEach { edit.putInt("timing_${it.name}", config[it]) }
        edit.putString("unlockPresets", config.unlockPresets.joinToString(","))
        val last = if (previous[TimingOption.DEFAULT_UNLOCK] != config[TimingOption.DEFAULT_UNLOCK])
            config[TimingOption.DEFAULT_UNLOCK] else lastUnlockMinutes(c)
        edit.putInt("lastUnlockMin", last.coerceIn(1, config[TimingOption.MAX_UNLOCK]))
        edit.apply()
        TaskPasswords.vault(c).capAll(config[TimingOption.MAX_UNLOCK])
    }

    fun lastUnlockMinutes(c: Context): Int {
        val config = timingConfig(c)
        return p(c).getInt("lastUnlockMin", config[TimingOption.DEFAULT_UNLOCK])
            .coerceIn(1, config[TimingOption.MAX_UNLOCK])
    }

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

    /** Each password has its own salt, hash, attempt counter and lockout deadline. */
    enum class Pw(val prefix: String) { MAIN(""), FILTER("f_"), SETTINGS("s_"), APP_LOCK("a_") }

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
        // Missing input is not a password guess, and some Android crypto providers reject empty keys.
        if (pw.isBlank() || !hasPassword(c, which)) return false
        val k = which.prefix
        val prefs = p(c)
        if (lockoutRemainingMs(c, which) > 0) return false
        val salt = Base64.decode(prefs.getString(k + "salt", "")!!, Base64.NO_WRAP)
        val expected = Base64.decode(prefs.getString(k + "hash", "")!!, Base64.NO_WRAP)
        if (MessageDigest.isEqual(expected, hash(pw, salt))) {
            prefs.edit().putInt(k + "fails", 0).apply()
            return true
        }
        val fails = (prefs.getInt(k + "fails", 0).toLong() + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val e = prefs.edit().putInt(k + "fails", fails)
        val lockout = timingConfig(c).passwordLockoutMs(fails)
        if (lockout > 0) {
            e.putLong(k + "lockoutUntil", System.currentTimeMillis() + lockout)
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

    /** Strengthening protection is always allowed and immediately ends any unlock session. */
    fun addAppLock(c: Context, pkg: String) = synchronized(TaskPasswords) {
        p(c).edit().putStringSet("protected", protectedApps(c) + pkg)
            .putLong("unlockedUntil", 0L).apply()
        TaskPasswords.vault(c).lock(pkg)
    }

    fun removeAppLock(c: Context, pkg: String, password: String): Boolean = synchronized(TaskPasswords) {
        if (!TaskPasswords.check(c, TaskScope(TaskKind.APP_REMOVE, pkg), password)) return@synchronized false
        p(c).edit().putStringSet("protected", protectedApps(c) - pkg).apply()
        TaskPasswords.vault(c).lock(pkg)
        true
    }

    fun unlockedUntil(c: Context, pkg: String = "") = if (pkg.isEmpty()) 0L else TaskPasswords.vault(c).until(pkg)

    fun lockProtectedApps(c: Context) = synchronized(TaskPasswords) {
        p(c).edit().putLong("unlockedUntil", 0L).apply()
        TaskPasswords.vault(c).lockAll()
    }

    fun unlockApp(c: Context, pkg: String, minutes: Int, password: String): Boolean = synchronized(TaskPasswords) {
        pkg in protectedApps(c) && TaskPasswords.vault(c).unlock(pkg, minutes, timingConfig(c)[TimingOption.MAX_UNLOCK], password)
    }

    // ---------- Google app DNS blocklist ----------

    fun blockedDomains(c: Context): Set<String> =
        p(c).getStringSet("blockedDomains", DEFAULT_BLOCKED)!!.toSet()

    fun setBlockedDomains(c: Context, s: Set<String>) =
        p(c).edit().putStringSet("blockedDomains", s).apply()

    fun isBlocked(c: Context, name: String): Boolean =
        blockedDomains(c).any { name == it || name.endsWith(".$it") }

    // ---------- Chrome whitelist ----------

    data class Site(val pattern: String, val addedAt: Long, val activeAt: Long)

    fun sites(c: Context): List<Site> {
        val config = timingConfig(c)
        return p(c).getStringSet("sites", emptySet())!!.mapNotNull { raw ->
            val i = raw.indexOf('|')
            val t = if (i > 0) raw.substring(0, i).toLongOrNull() else null
            if (t == null) null else Site(raw.substring(i + 1), t, config.siteActiveAt(t))
        }.sortedBy { it.addedAt }
    }

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

    /** Only sites whose configured wait is over. */
    fun activePatterns(c: Context): List<String> {
        val now = System.currentTimeMillis()
        return sites(c).filter { now >= it.activeAt }.map { it.pattern }
    }

    /** "https://www.Example.com/" -> "example.com". Null if it isn't a specific, safe site. */
    fun normalizeSite(input: String): String? = SitePattern.normalizeForEntry(input)

    fun chromeLock(c: Context) = p(c).getBoolean("chromeLock", false)

    fun setChromeLock(c: Context, on: Boolean) =
        p(c).edit().putBoolean("chromeLock", on).apply()

    // ---------- extra locked sites (instant addition, fixed 45-minute removal wait) ----------

    data class ExtraLockedSite(val domain: String, val removeAt: Long?)

    private fun readExtraSiteLocks(c: Context): ExtraSiteLocks {
        val prefs = p(c)
        val removals = prefs.getStringSet("extraSiteRemovalRequests", emptySet())!!.mapNotNull { raw ->
            val split = raw.indexOf('|')
            val deadline = if (split > 0) raw.substring(0, split).toLongOrNull() else null
            if (deadline == null) null else raw.substring(split + 1) to deadline
        }.toMap()
        return ExtraSiteLocks(prefs.getStringSet("extraLockedSites", emptySet())!!.toSet(), removals)
    }

    private fun saveExtraSiteLocks(c: Context, locks: ExtraSiteLocks) {
        p(c).edit().putStringSet("extraLockedSites", locks.domains)
            .putStringSet("extraSiteRemovalRequests", locks.removals.map { (domain, time) -> "$time|$domain" }.toSet())
            .apply()
    }

    @Synchronized
    fun extraLockedSites(c: Context): List<ExtraLockedSite> {
        val saved = readExtraSiteLocks(c)
        val current = saved.expire(System.currentTimeMillis())
        if (current != saved) saveExtraSiteLocks(c, current)
        return current.domains.sorted().map { ExtraLockedSite(it, current.removals[it]) }
    }

    /** Full URLs are accepted, but the lock always covers the whole domain and its subdomains. */
    fun normalizeExtraSite(input: String): String? =
        SharedSite.domainFromText(input)?.let { normalizeSite(it) }

    @Synchronized
    fun addExtraLockedSite(c: Context, input: String): Boolean {
        val domain = normalizeExtraSite(input) ?: return false
        val saved = readExtraSiteLocks(c)
        val current = saved.expire(System.currentTimeMillis())
        val updated = current.add(domain, System.currentTimeMillis())
        if (updated != saved) saveExtraSiteLocks(c, updated)
        return updated != current
    }

    @Synchronized
    fun requestExtraSiteRemoval(c: Context, domain: String): Long? {
        val saved = readExtraSiteLocks(c)
        val updated = saved.requestRemoval(domain, System.currentTimeMillis())
        if (updated != saved) saveExtraSiteLocks(c, updated)
        return updated.removals[domain]
    }

    // ---------- browser keyword filter ----------

    data class BrowserKeyword(val word: String, val removeAt: Long?)

    fun browserKeywordFilterEnabled(c: Context): Boolean = p(c).getBoolean("keywordFilterEnabled", true)

    fun enableBrowserKeywordFilter(c: Context) = p(c).edit().putBoolean("keywordFilterEnabled", true).apply()

    /** There is no unauthenticated setter for disabling this protection. */
    fun disableBrowserKeywordFilter(c: Context, password: String): Boolean {
        if (!TaskPasswords.check(c, TaskScope(TaskKind.KEYWORD_OFF), password)) return false
        p(c).edit().putBoolean("keywordFilterEnabled", false).apply()
        return true
    }

    fun keywordGuardPackages(c: Context): Set<String> =
        p(c).getStringSet("keywordGuardPackages", emptySet())!!.toSet()

    fun setKeywordGuardPackages(c: Context, packages: Set<String>) {
        if (packages != keywordGuardPackages(c)) p(c).edit().putStringSet("keywordGuardPackages", packages).apply()
    }

    private val DEFAULT_BROWSER_KEYWORDS = setOf("anime", "manga", "webtoon")

    private fun keywordRemovals(c: Context): Map<String, Long> =
        p(c).getStringSet("keywordRemovalRequests", emptySet())!!.mapNotNull { raw ->
            val split = raw.indexOf('|')
            val deadline = if (split > 0) raw.substring(0, split).toLongOrNull() else null
            if (deadline == null) null else raw.substring(split + 1) to deadline
        }.toMap()

    @Synchronized
    fun applyDueKeywordRemovals(c: Context, now: Long = System.currentTimeMillis()) {
        val removals = keywordRemovals(c)
        val due = removals.filterValues { now >= it }.keys
        if (due.isEmpty()) return
        val words = p(c).getStringSet("browserKeywords", DEFAULT_BROWSER_KEYWORDS)!!.toSet()
        p(c).edit().putStringSet("browserKeywords", words - due)
            .putStringSet("keywordExceptions", p(c).getStringSet("keywordExceptions", emptySet())!!
                .filterNot { it.substringBefore('|') in due }.toSet())
            .putStringSet("keywordRemovalRequests", removals.filterKeys { it !in due }
                .map { (word, deadline) -> "$deadline|$word" }.toSet()).apply()
    }

    @Synchronized
    fun browserKeywordEntries(c: Context): List<BrowserKeyword> {
        applyDueKeywordRemovals(c)
        val removals = keywordRemovals(c)
        return p(c).getStringSet("browserKeywords", DEFAULT_BROWSER_KEYWORDS)!!
            .sorted().map { BrowserKeyword(it, removals[it]) }
    }

    @Synchronized
    fun browserKeywords(c: Context): Set<String> {
        applyDueKeywordRemovals(c)
        return p(c).getStringSet("browserKeywords", DEFAULT_BROWSER_KEYWORDS)!!.toSet()
    }

    fun genericKeywordExceptions(c: Context): Set<String> =
        p(c).getStringSet("genericKeywordExceptions", emptySet())!!
            .mapNotNull { KeywordMatcher.normalizeWord(it) }.toSet()

    @Synchronized
    fun addGenericKeywordException(c: Context, input: String, password: String): Boolean {
        val word = KeywordMatcher.normalizeWord(input) ?: return false
        if (!TaskPasswords.check(c, TaskScope(TaskKind.PAGE_EXCEPTION), password)) return false
        p(c).edit().putStringSet("genericKeywordExceptions", genericKeywordExceptions(c) + word).apply()
        return true
    }

    @Synchronized
    fun removeGenericKeywordException(c: Context, word: String) {
        val normalized = KeywordMatcher.normalizeWord(word) ?: return
        p(c).edit().putStringSet("genericKeywordExceptions", genericKeywordExceptions(c) - normalized).apply()
    }

    fun keywordExceptions(c: Context): Map<String, Set<String>> {
        val active = browserKeywords(c)
        return p(c).getStringSet("keywordExceptions", emptySet())!!.mapNotNull { raw ->
            val parts = raw.split('|')
            if (parts.size != 2) return@mapNotNull null
            val blocked = KeywordMatcher.normalizeWord(parts[0]) ?: return@mapNotNull null
            val allowed = KeywordMatcher.normalizeWord(parts[1]) ?: return@mapNotNull null
            if (blocked in active && blocked != allowed) blocked to allowed else null
        }.groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }
    }

    /** Adding an exception weakens protection and needs the existing filter password. */
    @Synchronized
    fun addKeywordException(c: Context, blockedInput: String, allowedInput: String, password: String): Boolean {
        val blocked = KeywordMatcher.normalizeWord(blockedInput) ?: return false
        val allowed = KeywordMatcher.normalizeWord(allowedInput) ?: return false
        if (blocked == allowed || blocked !in browserKeywords(c)) return false
        if (!TaskPasswords.check(c, TaskScope(TaskKind.WORD_EXCEPTION), password)) return false
        val saved = p(c).getStringSet("keywordExceptions", emptySet())!!.toSet()
        p(c).edit().putStringSet("keywordExceptions", saved + "$blocked|$allowed").apply()
        return true
    }

    /** Removing an exception strengthens protection, so it is immediate and password-free. */
    @Synchronized
    fun removeKeywordException(c: Context, blocked: String, allowed: String) {
        val saved = p(c).getStringSet("keywordExceptions", emptySet())!!.toSet()
        p(c).edit().putStringSet("keywordExceptions", saved - "$blocked|$allowed").apply()
    }

    @Synchronized
    fun addBrowserKeyword(c: Context, input: String): Boolean {
        val word = KeywordMatcher.normalizeWord(input) ?: return false
        applyDueKeywordRemovals(c)
        val words = p(c).getStringSet("browserKeywords", DEFAULT_BROWSER_KEYWORDS)!!.toSet()
        val removals = keywordRemovals(c)
        if (word in words && word !in removals) return false
        p(c).edit().putStringSet("browserKeywords", words + word)
            .putStringSet("keywordRemovalRequests", removals.filterKeys { it != word }
                .map { (w, deadline) -> "$deadline|$w" }.toSet()).apply()
        return true
    }

    @Synchronized
    fun requestKeywordRemoval(c: Context, word: String): Long? {
        if (word !in browserKeywords(c)) return null
        val removals = keywordRemovals(c)
        removals[word]?.let { return it }
        val deadline = System.currentTimeMillis() + timingConfig(c).milliseconds(TimingOption.KEYWORD_REMOVAL_WAIT)
        p(c).edit().putStringSet("keywordRemovalRequests", (removals + (word to deadline))
            .map { (w, time) -> "$time|$w" }.toSet()).apply()
        return deadline
    }

    @Synchronized
    fun cancelKeywordRemoval(c: Context, word: String) {
        applyDueKeywordRemovals(c)
        p(c).edit().putStringSet("keywordRemovalRequests", keywordRemovals(c).filterKeys { it != word }
            .map { (w, deadline) -> "$deadline|$w" }.toSet()).apply()
    }

    // ---------- unused now, kept so older code still compiles ----------

    fun filterHost(c: Context): String = p(c).getString("filterHost", "") ?: ""

    fun setFilterHost(c: Context, host: String) =
        p(c).edit().putString("filterHost", host.trim()).apply()
}
