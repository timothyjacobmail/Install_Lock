package com.example.installlock

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.os.Handler
import android.os.PowerManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/** The overlay never takes focus or intercepts touches; leaving the site remains possible. */
@Suppress("DEPRECATION")
internal class WebsiteWatchdogMonitor(
    private val service: AccessibilityService, private val handler: Handler,
    private val targets: () -> Set<String>,
    private val warnings: WatchdogWarningBanner,
    private val hostReader: (AccessibilityNodeInfo, String) -> String?
) {
    private var running = false
    private var catalog: Set<String>? = null
    private var categories = WebsiteCategories(emptySet())
    private var lastBrowser: String? = null
    private var lastHost: String? = null
    private var lastWindow = -1
    private var lastTitle: String? = null

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            try { sample() } catch (_: RuntimeException) {
                WatchdogStore.observe(service, false)
                clearPage()
                hideBanner()
            }
            if (running) handler.postDelayed(this, 250)
        }
    }

    fun start() {
        if (running) return
        WatchdogStore.observe(service, false)
        running = true
        handler.post(tick)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
        WatchdogStore.observe(service, false)
        clearPage()
        hideBanner()
    }

    private fun clearPage() { lastBrowser = null; lastHost = null; lastWindow = -1; lastTitle = null }

    private fun sample() {
        val awake = service.getSystemService(PowerManager::class.java).isInteractive &&
            !service.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val root = if (awake) service.rootInActiveWindow else null
        try {
            val pkg = root?.packageName?.toString()
            if (WatchdogStore.remaining(service) > 0) {
                hideBanner()
                clearPage()
                if (pkg in targets() || pkg in ShortFormFeeds.nativePackages || pkg == "com.android.vending")
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                return
            }
            var classified = false
            if (root != null && pkg != null && pkg in targets()) {
                val page = pageInfo(root)
                val host = hostReader(root, pkg)
                // A disappearing toolbar must not reset exposure while the same document scrolls.
                // A new title/window or leaving the app invalidates that cache. Focusing the
                // address editor does not pause time while the same categorized document is visible.
                if (host != null && page.hasPage && !page.editing) {
                    lastHost = host; lastBrowser = pkg; lastWindow = root.windowId; lastTitle = page.title
                } else if (!page.hasPage || lastBrowser != pkg || lastWindow != root.windowId ||
                    page.title.isNullOrEmpty() || page.title != lastTitle) clearPage()
                val domains = WatchdogStore.domains(service)
                if (catalog !== domains) { catalog = domains; categories = WebsiteCategories(domains) }
                classified = page.hasPage && categories.mangaDomain(lastHost) != null
            } else clearPage()
            val state = WatchdogStore.observe(service, classified)
            if (state.untilWall != 0L) {
                hideBanner()
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                Policy.sweep(service)
                LockService.start(service)
                Toast.makeText(service, "30-minute manga/comics cooldown. Browsers, Google, YouTube, Instagram, Reddit and app installs are locked. " +
                    "Open Install Lock for details.", Toast.LENGTH_LONG).show()
            } else {
                val stage = CategoryWatchdog.warning(state)
                if (stage == 0) hideBanner() else showBanner(stage)
            }
        } finally { root?.recycle() }
    }

    private data class PageInfo(var hasPage: Boolean = false, var title: String? = null, var editing: Boolean = false)
    private fun pageInfo(root: AccessibilityNodeInfo): PageInfo {
        val info = PageInfo()
        var visited = 0
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (++visited > 2000 || depth > 50 || n.isPassword || !n.isVisibleToUser) return
            val type = n.className?.toString().orEmpty()
            if (type == "android.webkit.WebView" || type.endsWith("GeckoView")) {
                info.hasPage = true
                info.title = n.text?.toString()?.take(256)
                return // Content links, snippets and editable page fields cannot impersonate an address.
            }
            if (n.isFocused && BrowserAddress.isAddressField(n.packageName?.toString().orEmpty(), n.viewIdResourceName))
                info.editing = true
            for (i in 0 until n.childCount) {
                if (visited >= 2000) break
                val child = n.getChild(i) ?: continue
                try { walk(child, depth + 1) } finally { child.recycle() }
            }
        }
        walk(root, 0)
        return info
    }

    private fun showBanner(stage: Int) {
        val text = if (stage == 1) "Manga/comics site detected. Leave now. Final warning at 5 seconds; 30-minute cooldown at 10 seconds total."
            else "FINAL WARNING: leave now. At 10 seconds total, browsers, Google, YouTube, Instagram, Reddit and app installs lock for 30 minutes."
        warnings.update("category", stage, text)
    }

    private fun hideBanner() {
        warnings.update("category", 0)
    }
}
