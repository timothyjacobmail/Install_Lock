package com.example.installlock

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/** Reads browser pages and visible Google app content locally; never saves the inspected text. */
@Suppress("DEPRECATION")
class BrowserKeywordService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var targets = setOf(Policy.CHROME, BrowserPackages.GOOGLE_APP)
    private var scanScheduled = false
    private var handlingMatch = false
    private var registered = false
    private var lastWords = emptySet<String>()
    private var lastExceptions = emptyMap<String, Set<String>>()
    private var lastGenericExceptions = emptySet<String>()
    private var pageFilter = KeywordPageFilter(emptySet())
    private val queryTextCache = ParsedValueCache<List<String>, GoogleResultText>(::GoogleResultText)
    private val prefs by lazy { getSharedPreferences("install_lock", Context.MODE_PRIVATE) }
    private val watchdogWarnings by lazy { WatchdogWarningBanner(this) }
    private val categoryWatchdog by lazy {
        WebsiteWatchdogMonitor(this, handler, { targets }, watchdogWarnings) { root, browser -> browserHost(root, browser, visibleOnly = false) }
    }
    private val rapidScrollWatchdog by lazy {
        RapidScrollMonitor(this, handler, { targets }, watchdogWarnings) { root, browser -> browserAddress(root, browser, visibleOnly = false) }
    }

    private fun filteringRequired() = Store.browserKeywordFilterEnabled(this) || Store.domainRules(this).isNotEmpty()

    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "browserKeywords" || key == "keywordRemovalRequests" ||
            key == "keywordExceptions" || key == "genericKeywordExceptions") scheduleScan(0)
        if (key == "keywordFilterEnabled") {
            if (filteringRequired()) scheduleScan(0) else onInterrupt()
        }
        if (key == "domainRules") {
            if (filteringRequired()) scheduleScan(0) else onInterrupt()
            Policy.sweep(this)
        }
    }
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { refreshTargets() }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        refreshTargets()
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        if (!registered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addDataScheme("package")
            }
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(packageReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else registerReceiver(packageReceiver, filter)
            registered = true
        }
        connected = true
        categoryWatchdog.start()
        rapidScrollWatchdog.start()
        Policy.sweep(this)
        scheduleScan(0)
    }

    private fun refreshTargets() {
        // Some Android versions hide suspended handlers from intent queries. Keep their targets
        // registered before the guard releases them as this service reconnects.
        targets = BrowserPackages.keywordTargets(this) + (Store.keywordGuardPackages(this) - ShortFormFeeds.nativePackages)
        serviceInfo = serviceInfo.apply { packageNames = (targets + ShortFormFeeds.nativePackages).toTypedArray() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event != null) try { rapidScrollWatchdog.onScroll(event) } catch (_: RuntimeException) {
            // Navigation can invalidate a scroll event's source while it is being inspected.
        }
        if (event?.packageName?.toString() in targets) scheduleScan(0)
    }

    private fun scheduleScan(delay: Long) {
        if (scanScheduled || handlingMatch || !filteringRequired()) return
        scanScheduled = true
        handler.postDelayed(scan, delay)
    }

    private val scan = Runnable {
        scanScheduled = false
        if (!handlingMatch) scanPage()
    }

    private fun scanPage() {
        if (!filteringRequired()) return
        val root = rootInActiveWindow ?: return
        try {
            val browser = root.packageName?.toString() ?: return
            if (browser !in targets) return
            val domainRules = Store.domainRules(this)
            if (browser != BrowserPackages.GOOGLE_APP && domainRules.isNotEmpty()) {
                val host = browserHost(root, browser)
                val decision = host?.let { DomainRules(domainRules).match(it) }
                if (decision?.rule?.action == DomainAction.BLOCK) {
                    closeTabOrLeave(root, browser, "$host (domain rule #${decision.position})")
                    return
                }
            }
            val words = if (Store.browserKeywordFilterEnabled(this)) Store.browserKeywords(this) else emptySet()
            // Domain checks still run above. An empty keyword list cannot match page content.
            if (words.isEmpty()) { scheduleScan(500); return }
            val exceptions = Store.keywordExceptions(this)
            val genericExceptions = Store.genericKeywordExceptions(this)
            if (words != lastWords || exceptions != lastExceptions || genericExceptions != lastGenericExceptions) {
                lastWords = words
                lastExceptions = exceptions
                lastGenericExceptions = genericExceptions
                pageFilter = KeywordPageFilter(words, exceptions, genericExceptions)
            }
            val google = browser == BrowserPackages.GOOGLE_APP
            val query = if (google) googleSearchQuery(root) else null
            val queryText = if (google) queryTextCache.get(listOfNotNull(query, lastSubmittedGoogleQuery)) else null
            val text = visiblePageText(root, queryText)
            val match = pageFilter.findBlock(text)
            if (match != null) {
                // Home leaves Google's blocked results open, causing a loop on re-entry.
                // Offer a separate search editor that can replace those results instead.
                if (google) recoverGoogleSearch(match, query ?: lastSubmittedGoogleQuery)
                else closeTabOrLeave(root, browser, match)
            }
            else scheduleScan(500)
        } catch (_: RuntimeException) {
            // A browser may replace its accessibility tree during navigation.
            scheduleScan(500)
        } finally { root.recycle() }
    }

    private fun browserHost(root: AccessibilityNodeInfo, browser: String, visibleOnly: Boolean = true): String? =
        browserAddress(root, browser, visibleOnly)?.let { DomainRules.hostFromAddress(it) }

    private fun browserAddress(root: AccessibilityNodeInfo, browser: String, visibleOnly: Boolean = true): String? {
        var visited = 0
        fun walk(node: AccessibilityNodeInfo, depth: Int): String? {
            if (depth > 64 || ++visited > 2000 || node.isPassword) return null
            val type = node.className?.toString().orEmpty()
            // Website content and search-result links cannot impersonate the address bar.
            if (type == "android.webkit.WebView" || type.endsWith("GeckoView")) return null
            // A browser can slide its genuine address control offscreen while its document stays
            // visible. The watchdog can read that native control without trusting page text.
            if ((!visibleOnly || node.isVisibleToUser) && !node.isFocused &&
                node.packageName?.toString() == browser && BrowserAddress.isAddressField(browser, node.viewIdResourceName)) {
                val address = node.text?.toString().orEmpty()
                if (DomainRules.hostFromAddress(address) != null) return address
            }
            for (index in 0 until node.childCount) {
                if (visited >= 2000) break
                val child = node.getChild(index) ?: continue
                try { walk(child, depth + 1)?.let { return it } } finally { child.recycle() }
            }
            return null
        }
        return walk(root, 0)
    }

    private fun visiblePageText(root: AccessibilityNodeInfo, queryText: GoogleResultText?): String {
        val text = StringBuilder()
        val google = root.packageName?.toString() == BrowserPackages.GOOGLE_APP
        var visited = 0
        fun walk(node: AccessibilityNodeInfo, insidePage: Boolean, depth: Int) {
            if (depth > 64 || ++visited > 4000 || text.length >= 131072) return
            if (node.isPassword || node.isEditable || node.className?.toString() == "android.widget.EditText") return
            val className = node.className?.toString().orEmpty()
            val webPage = insidePage || className == "android.webkit.WebView" || className.endsWith("GeckoView")
            if (google && !webPage && GoogleQueryField.matches(className, node.viewIdResourceName, node.isEditable)) return
            if ((google || webPage) && node.isVisibleToUser) {
                for (value in listOfNotNull(node.text, node.contentDescription)) {
                    val available = 131072 - text.length
                    val content = queryText?.forMatching(value.toString()) ?: value.toString()
                    if (available > 1) text.append(content.take(available - 1)).append(' ')
                }
            }
            for (index in 0 until node.childCount) {
                if (visited >= 4000 || text.length >= 131072) break
                val child = node.getChild(index) ?: continue
                try { walk(child, webPage, depth + 1) } finally { child.recycle() }
            }
        }
        // Google also renders results and Discover cards as native views. Browser chrome
        // remains excluded for other packages, so address bars and menus aren't scanned.
        walk(root, false, 0)
        // A query can also be split across multiple nodes in Google's accessibility tree.
        return queryText?.forMatching(text.toString()) ?: text.toString()
    }

    /** Read only the current native query control, never guess the query from result snippets. */
    private fun googleSearchQuery(root: AccessibilityNodeInfo): String? {
        var visited = 0
        fun walk(node: AccessibilityNodeInfo, insideQuery: Boolean, depth: Int): String? {
            if (depth > 64 || ++visited > 2000 || node.isPassword) return null
            val className = node.className?.toString().orEmpty()
            if (className == "android.webkit.WebView" || className.endsWith("GeckoView")) return null
            val queryField = insideQuery || GoogleQueryField.matches(className, node.viewIdResourceName, node.isEditable)
            if (queryField && node.isVisibleToUser && !node.isShowingHintText) {
                val value = node.text?.toString()?.trim()
                if (!value.isNullOrEmpty() && value.length <= 2048 &&
                    value.lowercase() !in setOf("search", "search...", "search…")) return value
            }
            for (index in 0 until node.childCount) {
                if (visited >= 2000) break
                val child = node.getChild(index) ?: continue
                try { walk(child, queryField, depth + 1)?.let { return it } } finally { child.recycle() }
            }
            return null
        }
        return walk(root, false, 0)
    }

    /** Native browser controls only: a page cannot impersonate a close-tab menu item. */
    private fun findNative(root: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        var visited = 0
        fun walk(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
            if (depth > 64 || ++visited > 2000) return null
            if (node.className?.toString() == "android.webkit.WebView" ||
                node.className?.toString()?.endsWith("GeckoView") == true) return null
            if (node.isEditable || node.isPassword) return null
            if (node.isVisibleToUser && node.isEnabled && predicate(node)) return AccessibilityNodeInfo.obtain(node)
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try { walk(child, depth + 1)?.let { return it } } finally { child.recycle() }
            }
            return null
        }
        return walk(root, 0)
    }

    private fun closeTabOrLeave(root: AccessibilityNodeInfo, browser: String, word: String) {
        handlingMatch = true
        try {
            // Chrome/Chromium expose Close tab through a long press on the native tab button.
            val tabs = findNative(root) { it.viewIdResourceName == "$browser:id/tab_switcher_button" }
            val opened = try { tabs?.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) == true }
                finally { tabs?.recycle() }
            if (opened) handler.postDelayed({ tryCloseTab(browser, word, 0) }, 200)
            else leaveApp(browser, word)
        } catch (_: RuntimeException) {
            leaveApp(browser, word)
        }
    }

    private fun tryCloseTab(browser: String, word: String, attempt: Int) {
        val root = rootInActiveWindow
        var clicked = false
        var stillBrowser = false
        try {
            stillBrowser = root?.packageName?.toString() == browser
            if (root != null && stillBrowser) {
                val close = findNative(root) {
                    it.packageName?.toString() == browser &&
                        (it.viewIdResourceName == "$browser:id/close_tab" ||
                            it.text?.toString()?.trim()?.equals("Close tab", ignoreCase = true) == true)
                }
                try { if (close != null) clicked = clickMenuItem(close) }
                finally { close?.recycle() }
            }
        } catch (_: RuntimeException) { /* retry while the menu is animating */ }
        finally { root?.recycle() }
        if (clicked) {
            // Let the tab-close animation finish, then check whichever page is now visible.
            handler.postDelayed({
                handlingMatch = false
                scheduleScan(0)
            }, 500)
        } else if (stillBrowser && attempt < 4) {
            handler.postDelayed({ tryCloseTab(browser, word, attempt + 1) }, 200)
        } else {
            leaveApp(browser, word)
        }
    }

    private fun clickMenuItem(node: AccessibilityNodeInfo, depth: Int = 0): Boolean {
        if (node.isClickable) return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (depth >= 2) return false
        val parent = node.parent ?: return false
        return try { clickMenuItem(parent, depth + 1) } finally { parent.recycle() }
    }

    private fun recoverGoogleSearch(word: String, query: String?) {
        handlingMatch = true
        val root = rootInActiveWindow
        try {
            if (root?.packageName?.toString() == BrowserPackages.GOOGLE_APP) {
                startActivity(GoogleSearchRecoveryActivity.openIntent(this, word, query))
            }
        } catch (_: RuntimeException) {
            // The same search editor is also available from Install Lock if Android
            // prevents an automatic launch. Never leave the blocked results readable.
            performGlobalAction(GLOBAL_ACTION_HOME)
            Toast.makeText(this, "Keyword blocked. Open Install Lock to start a new Google search.", Toast.LENGTH_LONG).show()
        } finally {
            root?.recycle()
            handlingMatch = false
            scheduleScan(500)
        }
    }

    private fun leaveApp(browser: String, word: String) {
        val root = rootInActiveWindow
        try {
            if (root?.packageName?.toString() == browser) {
                performGlobalAction(GLOBAL_ACTION_HOME)
                val app = if (browser == BrowserPackages.GOOGLE_APP) "Google app" else "Browser"
                Toast.makeText(this, "$app blocked: $word", Toast.LENGTH_SHORT).show()
            }
        } finally {
            root?.recycle()
            handlingMatch = false
            scheduleScan(500)
        }
    }

    override fun onInterrupt() {
        categoryWatchdog.stop()
        rapidScrollWatchdog.stop()
        handler.removeCallbacksAndMessages(null)
        scanScheduled = false
        handlingMatch = false
        if (connected && isEnabled(this)) {
            categoryWatchdog.start()
            rapidScrollWatchdog.start()
        }
    }

    override fun onDestroy() {
        connected = false
        onInterrupt()
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        if (registered) unregisterReceiver(packageReceiver)
        Policy.sweep(this)
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        connected = false
        onInterrupt()
        Policy.sweep(this)
        return super.onUnbind(intent)
    }

    companion object {
        @Volatile private var connected = false
        // One query in memory only, so query echoes can be recognized even when Google's
        // current version doesn't expose a native query control. No search history is saved.
        @Volatile private var lastSubmittedGoogleQuery: String? = null

        internal fun rememberGoogleSearch(query: String) { lastSubmittedGoogleQuery = query }

        fun isReady(context: Context): Boolean = connected && isEnabled(context)

        fun isEnabled(context: Context): Boolean =
            context.getSystemService(AccessibilityManager::class.java)
                .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { it.resolveInfo.serviceInfo.name == BrowserKeywordService::class.java.name &&
                    it.resolveInfo.serviceInfo.packageName == context.packageName }
    }
}
