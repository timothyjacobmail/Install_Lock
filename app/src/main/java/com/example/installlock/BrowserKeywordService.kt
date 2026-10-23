package com.example.installlock

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.app.KeyguardManager
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import com.example.delayedsecrect.SecretClipboard
import com.example.delayedsecrect.ClipboardCleanupActivity
import com.example.delayedsecrect.PasswordFieldEntry
import com.example.delayedsecrect.PasswordEntryResult

/** Reads browser pages and visible Google app content locally; never saves the inspected text. */
@Suppress("DEPRECATION")
class BrowserKeywordService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var targets = setOf(Policy.CHROME, BrowserPackages.GOOGLE_APP)
    private var scanScheduled = false
    private var nextScanAt = 0L
    private var handlingMatch = false
    private var registered = false
    private var lastWords = emptySet<String>()
    private var lastExceptions = emptyMap<String, Set<String>>()
    private var lastGenericExceptions = emptySet<String>()
    private var pageFilter = KeywordPageFilter(emptySet())
    private val queryTextCache = ParsedValueCache<List<String>, GoogleResultText>(::GoogleResultText)
    private val chromeNavigationCache = ParsedValueCache<ChromeNavigationConfig, ChromeNavigationFilter>(::ChromeNavigationFilter)
    private val prefs by lazy { getSharedPreferences("install_lock", Context.MODE_PRIVATE) }
    private val watchdogWarnings by lazy { WatchdogWarningBanner(this) }
    private val categoryWatchdog by lazy {
        WebsiteWatchdogMonitor(this, handler, { targets }, watchdogWarnings) { root, browser -> browserHost(root, browser, visibleOnly = false) }
    }
    private val rapidScrollWatchdog by lazy {
        RapidScrollMonitor(this, handler, { targets }, watchdogWarnings) { root, browser -> browserAddress(root, browser, visibleOnly = false) }
    }

    private fun filteringRequired() = Store.browserKeywordFilterEnabled(this) || Store.domainRules(this).isNotEmpty() ||
        Store.chromeLock(this) || Store.extraLockedSites(this).isNotEmpty()

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
        if (key == "chromeLock" || key == "sites" || key == "extraLockedSites" || key == "extraSiteRemovalRequests") {
            if (filteringRequired()) scheduleScan(0)
            else { handler.removeCallbacks(scan); scanScheduled = false }
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
        PasswordFieldEntry.attach(::enterFocusedPassword)
        SecretClipboard.attachAccessibility(this, ::updateEventPackages) {
            startActivity(ClipboardCleanupActivity.openIntent(this))
        }
        categoryWatchdog.start()
        rapidScrollWatchdog.start()
        Policy.sweep(this)
        scheduleScan(0)
    }

    private fun refreshTargets() {
        // Some Android versions hide suspended handlers from intent queries. Keep their targets
        // registered before the guard releases them as this service reconnects.
        targets = BrowserPackages.keywordTargets(this) + (Store.keywordGuardPackages(this) - ShortFormFeeds.nativePackages)
        updateEventPackages()
    }

    private fun updateEventPackages() {
        // Observe other apps only for the one-copy "until pasted" option. Their page
        // content is never scanned; normal browser filtering still uses targets below.
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            packageNames = if (SecretClipboard.watchingPaste) null else (targets + ShortFormFeeds.nativePackages).toTypedArray()
        }
    }

    private fun enterFocusedPassword(value: String): PasswordEntryResult {
        if (getSystemService(KeyguardManager::class.java).isDeviceLocked)
            return PasswordEntryResult(false, "Unlock your phone, then tap the app's password field.")
        // A touch on a floating window can make it the active accessibility window.
        // The input-focused application window remains the intended destination.
        val focusedWindow = windows.firstOrNull { it.isFocused && it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val root = focusedWindow?.root ?: rootInActiveWindow
            ?: return PasswordEntryResult(false, "Open the target app and tap its password field first.")
        try {
            if (root.packageName?.toString() == "com.android.systemui")
                return PasswordEntryResult(false, "Open the target app's password field first.")
            val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: return PasswordEntryResult(false, "Tap the password field, then press Enter password here.")
            try {
                if (!PasswordFieldTarget.eligible(field.isFocused, field.isVisibleToUser, field.isEnabled,
                        field.isEditable, field.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT },
                        field.isPassword, field.inputType))
                    return PasswordEntryResult(false, "Select an editable password field. Search boxes and ordinary text fields are not filled.")
                val entered = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
                })
                return if (entered) PasswordEntryResult(true, "Password entered. Use the app's own confirm button when ready.")
                else PasswordEntryResult(false, "This app did not allow direct password entry. Nothing was copied.")
            } finally { field.recycle() }
        } finally { root.recycle() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try { SecretClipboard.onAccessibilityEvent(event) } catch (_: RuntimeException) {
            // A transient clipboard or accessibility error must not interrupt browser enforcement.
        }
        if (event != null) try { rapidScrollWatchdog.onScroll(event) } catch (_: RuntimeException) {
            // Navigation can invalidate a scroll event's source while it is being inspected.
        }
        if (event?.packageName?.toString() in targets) {
            val navigation = event?.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
                event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            // Taps and window changes preempt the poll. Continuous video, text and scroll
            // updates keep the normal rate instead of repeatedly scanning the whole page.
            scheduleScan(if (navigation) 0 else 500)
        }
    }

    private fun scheduleScan(delay: Long) {
        if (handlingMatch || !filteringRequired()) return
        val due = SystemClock.uptimeMillis() + delay
        if (scanScheduled) {
            if (nextScanAt <= due) return
            // A navigation event must not wait behind the next periodic content scan.
            handler.removeCallbacks(scan)
        }
        nextScanAt = due
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
            if (browser == Policy.CHROME) {
                val address = browserAddress(root, browser, visibleOnly = false)
                val config = ChromeNavigationConfig(Store.chromeLock(this), Store.activePatterns(this),
                    Store.extraLockedSites(this).map { it.domain }.toSet())
                val blocked = address?.let { chromeNavigationCache.get(config).block(it) }
                if (address != null && blocked != null) {
                    if (blocked == ChromeNavigationBlock.WHITELIST) {
                        if (!expandChromeCustomTab(root, address)) recoverBlockedSite(address)
                    }
                    else closeTabOrLeave(root, browser, "This domain is in your extra locked sites")
                    return
                }
            }
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
    private fun findNative(root: AccessibilityNodeInfo, includeEditable: Boolean = false,
                           predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        var visited = 0
        fun walk(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
            if (depth > 64 || ++visited > 2000) return null
            if (node.className?.toString() == "android.webkit.WebView" ||
                node.className?.toString()?.endsWith("GeckoView") == true) return null
            if (node.isPassword || node.isEditable && !includeEditable) return null
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

    /** Promote Google's embedded Chrome tab so its native URL includes the requested path.
     * Never infer that path from a page title, search-result breadcrumb, or page link. */
    private fun expandChromeCustomTab(root: AccessibilityNodeInfo, address: String): Boolean {
        val url = findNative(root) {
            it.packageName?.toString() == Policy.CHROME &&
                ChromeTabRecovery.needsFullTab(it.viewIdResourceName, it.isEditable, it.isFocused, it.text?.toString())
        } ?: return false
        url.recycle()
        val menu = findNative(root) {
            it.packageName?.toString() == Policy.CHROME &&
                it.viewIdResourceName == "${Policy.CHROME}:id/menu_button"
        } ?: return false
        val opened = try { menu.performAction(AccessibilityNodeInfo.ACTION_CLICK) } finally { menu.recycle() }
        if (!opened) return false
        handlingMatch = true
        handler.postDelayed({ finishChromeTabHandoff(address, openedTab = false, attempt = 0) }, 150)
        return true
    }

    private fun finishChromeTabHandoff(address: String, openedTab: Boolean, attempt: Int) {
        val root = rootInActiveWindow
        try {
            // Do not act on another app if the user switches away during the handoff.
            if (root == null || root.packageName?.toString() != Policy.CHROME) {
                handlingMatch = false
                scheduleScan(500)
                return
            }
            if (!openedTab) {
                val open = findNative(root) {
                    ChromeTabRecovery.isOpenInChrome(it.packageName?.toString(), it.viewIdResourceName, it.text?.toString())
                }
                val clicked = try { open != null && clickMenuItem(open) } finally { open?.recycle() }
                if (clicked) {
                    handler.postDelayed({ finishChromeTabHandoff(address, openedTab = true, attempt = 0) }, 150)
                    return
                }
            } else {
                // A normal tab has an editable native URL bar even while it is unfocused.
                // Re-run every guard against that committed URL, including extra site locks.
                val fullTab = findNative(root, includeEditable = true) {
                    it.packageName?.toString() == Policy.CHROME && it.isEditable && !it.isFocused &&
                        it.viewIdResourceName == "${Policy.CHROME}:id/url_bar" &&
                        DomainRules.hostFromAddress(it.text?.toString().orEmpty()) != null
                }
                if (fullTab != null) {
                    fullTab.recycle()
                    handlingMatch = false
                    scheduleScan(0)
                    return
                }
            }
            if (attempt < 5) {
                handler.postDelayed({ finishChromeTabHandoff(address, openedTab, attempt + 1) }, 150)
                return
            }
            // Never keep the guard suspended when Chrome changes its menu or fails to open.
            recoverBlockedSite(browserAddress(root, Policy.CHROME, visibleOnly = false) ?: address)
        } catch (_: RuntimeException) {
            recoverBlockedSite(address)
        } finally { root?.recycle() }
    }

    private fun recoverBlockedSite(address: String) {
        handlingMatch = true
        val root = rootInActiveWindow
        try {
            if (root?.packageName?.toString() == Policy.CHROME) {
                // Cover the blocked page without closing the tab before its URL can be
                // requested through the normal password-protected whitelist form.
                startActivity(BlockedSiteActivity.openIntent(this, address))
            }
        } catch (_: RuntimeException) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            Toast.makeText(this, "Site blocked. Open Chrome's whitelist in Install Lock to add a site.", Toast.LENGTH_LONG).show()
        } finally {
            root?.recycle()
            handlingMatch = false
            scheduleScan(500)
        }
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
        PasswordFieldEntry.detach()
        SecretClipboard.detachAccessibility()
        onInterrupt()
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        if (registered) unregisterReceiver(packageReceiver)
        Policy.sweep(this)
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        connected = false
        PasswordFieldEntry.detach()
        SecretClipboard.detachAccessibility()
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
