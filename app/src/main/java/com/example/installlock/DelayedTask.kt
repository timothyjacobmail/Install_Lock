package com.example.installlock

import android.content.Context
import org.json.JSONObject

/** A snapshot of one requested action. Contains parameters, never a password or authorization. */
internal data class DelayedTask(val scope: String, val title: String, val details: String = "{}") {
    constructor(scope: TaskScope, title: String, details: Map<String, Any> = emptyMap()) :
        this(scope.id, title, JSONObject(details).toString())

    fun encode(): String = JSONObject().put("scope", scope).put("title", title).put("details", JSONObject(details)).toString()

    fun execute(c: Context, password: String): Boolean {
        if (scope == DASHBOARD) return Store.checkPassword(c, password)
        val task = TaskScope.decode(scope) ?: return false
        val d = JSONObject(details)
        fun strings(key: String): Set<String> = d.getJSONArray(key).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
        fun run(action: () -> Boolean) = TaskPasswords.run(c, task, password, action)
        return when (task.kind) {
            TaskKind.APP_UNLOCK -> Store.unlockApp(c, task.app, d.getInt("minutes"), password)
            TaskKind.APP_LIMIT -> TaskPasswords.setLimit(c, task.app, d.getInt("minutes"), password)
            TaskKind.APP_REMOVE -> Store.removeAppLock(c, task.app, password).also { if (it) Policy.release(c, listOf(task.app)) }
            TaskKind.WHITELIST_OFF -> Policy.setChromeLock(c, false, password)
            // This path has already waited for its saved password; never start a second site timer.
            TaskKind.WHITELIST_ADD -> run { Store.addSite(c, d.getString("site"), waitMs = 0).also { if (it) Policy.syncChrome(c) } }
            TaskKind.KEYWORD_OFF -> Store.disableBrowserKeywordFilter(c, password)
            TaskKind.VPN_OFF -> Policy.disableGoogleFilter(c, password)?.startsWith("Error:") == false
            TaskKind.SCREENSHOTS_ALLOW -> Policy.setScreenCaptureBlocked(c, false, password)?.startsWith("Error:") == false
            TaskKind.CURBOX_OFF -> run { Store.setCurboxGuard(c, false); Policy.release(c, Policy.CURBOX_GUARDED); true }
            TaskKind.NEVER_REMOVE -> run { Store.setNeverInstall(c, Store.neverInstall(c) - d.getString("rule")); true }
            TaskKind.UNINSTALL_REMOVE -> run {
                val remove = strings("remove")
                Store.setNoUninstall(c, (Store.noUninstall(c) + strings("add")) - remove)
                Policy.allowUninstall(c, remove)
                true
            }
            TaskKind.DNS_UNBLOCK -> run { Store.setBlockedDomains(c, Store.blockedDomains(c) - strings("remove")); true }
            TaskKind.DOMAIN_ALLOW -> Store.addDomainRule(c, DomainAction.ALLOW, DomainMatchType.valueOf(d.getString("type")),
                d.getString("value"), d.getInt("position"), password)
            TaskKind.DOMAIN_MOVE -> Store.moveDomainRule(c, d.getString("id"), d.getInt("position"), password)
            TaskKind.DOMAIN_REMOVE -> {
                // A removed/replaced rule must never authorize a different rule.
                if (Store.domainRules(c).none { it.id == d.getString("id") && it.action == DomainAction.BLOCK }) false
                else Store.removeDomainRule(c, d.getString("id"), password)
            }
            TaskKind.PAGE_EXCEPTION -> Store.addGenericKeywordException(c, d.getString("word"), password)
            TaskKind.WORD_EXCEPTION -> Store.addKeywordException(c, d.getString("blocked"), d.getString("word"), password)
            TaskKind.CATEGORIES -> TaskSettings.categories(c, strings("categories"), password)
            TaskKind.TIMING, TaskKind.SECURITY -> {
                val changes = d.getJSONObject("changes")
                val values = changes.keys().asSequence().associate { TimingOption.valueOf(it) to changes.getInt(it) }
                val presets = d.optJSONArray("presets")?.let { a -> (0 until a.length()).map { a.getInt(it) } }
                TaskSettings.save(c, values, presets, task.kind == TaskKind.SECURITY, password)
            }
            TaskKind.ENROLL_APP -> false // Creating/changing credentials still uses its dedicated form.
        }
    }

    companion object {
        const val DASHBOARD = "dashboard"
        fun decode(raw: String): DelayedTask? = try {
            if (raw.length > 65_536) null else JSONObject(raw).let {
                val scope = it.getString("scope")
                if (scope != DASHBOARD && TaskScope.decode(scope) == null) null
                else DelayedTask(scope, it.getString("title"), it.getJSONObject("details").toString())
            }
        } catch (_: Exception) { null }
    }
}
