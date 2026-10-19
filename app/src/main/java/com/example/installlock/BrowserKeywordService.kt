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

/** Reads browser page text locally. Never captures screenshots, typed fields, or browsing logs. */
@Suppress("DEPRECATION")
class BrowserKeywordService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var browsers = setOf(Policy.CHROME)
    private var scanScheduled = false
    private var handlingMatch = false
    private var registered = false
    private var lastWords = emptySet<String>()
    private var matcher = KeywordMatcher(emptySet())
    private val prefs by lazy { getSharedPreferences("install_lock", Context.MODE_PRIVATE) }

    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "browserKeywords" || key == "keywordRemovalRequests") scheduleScan(0)
        if (key == "keywordFilterEnabled") {
            if (Store.browserKeywordFilterEnabled(this)) scheduleScan(0) else onInterrupt()
        }
    }
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { refreshBrowsers() }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        refreshBrowsers()
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
        Policy.sweep(this)
        scheduleScan(0)
    }

    private fun refreshBrowsers() {
        // Some Android versions hide suspended handlers from intent queries. Keep their targets
        // registered before the guard releases them as this service reconnects.
        browsers = BrowserPackages.discover(this) + Store.keywordGuardPackages(this)
        serviceInfo = serviceInfo.apply { packageNames = browsers.toTypedArray() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() in browsers) scheduleScan(0)
    }

    private fun scheduleScan(delay: Long) {
        if (scanScheduled || handlingMatch || !Store.browserKeywordFilterEnabled(this)) return
        scanScheduled = true
        handler.postDelayed(scan, delay)
    }

    private val scan = Runnable {
        scanScheduled = false
        if (!handlingMatch) scanPage()
    }

    private fun scanPage() {
        if (!Store.browserKeywordFilterEnabled(this)) return
        val root = rootInActiveWindow ?: return
        try {
            val browser = root.packageName?.toString() ?: return
            if (browser !in browsers) return
            val words = Store.browserKeywords(this)
            if (words != lastWords) {
                lastWords = words
                matcher = KeywordMatcher(words)
            }
            val text = visiblePageText(root)
            val match = matcher.findMatch(text)
            if (match != null) closeTabOrLeave(root, browser, match)
            else scheduleScan(500)
        } catch (_: RuntimeException) {
            // A browser may replace its accessibility tree during navigation.
            scheduleScan(500)
        } finally { root.recycle() }
    }

    private fun visiblePageText(root: AccessibilityNodeInfo): String {
        val text = StringBuilder()
        var visited = 0
        fun walk(node: AccessibilityNodeInfo, insidePage: Boolean, depth: Int) {
            if (depth > 64 || ++visited > 4000 || text.length >= 131072) return
            if (node.isPassword || node.isEditable) return
            val className = node.className?.toString().orEmpty()
            val page = insidePage || className == "android.webkit.WebView" || className.endsWith("GeckoView")
            if (page && node.isVisibleToUser) {
                for (value in listOfNotNull(node.text, node.contentDescription)) {
                    val available = 131072 - text.length
                    if (available > 1) text.append(value.take(available - 1)).append(' ')
                }
            }
            for (index in 0 until node.childCount) {
                if (visited >= 4000 || text.length >= 131072) break
                val child = node.getChild(index) ?: continue
                try { walk(child, page, depth + 1) } finally { child.recycle() }
            }
        }
        walk(root, false, 0)
        return text.toString()
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
            else leaveBrowser(browser, word)
        } catch (_: RuntimeException) {
            leaveBrowser(browser, word)
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
            leaveBrowser(browser, word)
        }
    }

    private fun clickMenuItem(node: AccessibilityNodeInfo, depth: Int = 0): Boolean {
        if (node.isClickable) return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (depth >= 2) return false
        val parent = node.parent ?: return false
        return try { clickMenuItem(parent, depth + 1) } finally { parent.recycle() }
    }

    private fun leaveBrowser(browser: String, word: String) {
        val root = rootInActiveWindow
        try {
            if (root?.packageName?.toString() == browser) {
                performGlobalAction(GLOBAL_ACTION_HOME)
                Toast.makeText(this, "Browser keyword blocked: $word", Toast.LENGTH_SHORT).show()
            }
        } finally {
            root?.recycle()
            handlingMatch = false
            scheduleScan(500)
        }
    }

    override fun onInterrupt() {
        handler.removeCallbacksAndMessages(null)
        scanScheduled = false
        handlingMatch = false
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

        fun isReady(context: Context): Boolean = connected && isEnabled(context)

        fun isEnabled(context: Context): Boolean =
            context.getSystemService(AccessibilityManager::class.java)
                .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { it.resolveInfo.serviceInfo.name == BrowserKeywordService::class.java.name &&
                    it.resolveInfo.serviceInfo.packageName == context.packageName }
    }
}
