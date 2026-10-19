package com.example.installlock

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.os.Handler
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

@Suppress("DEPRECATION")
internal class RapidScrollMonitor(
    private val service: AccessibilityService, private val handler: Handler,
    private val browsers: () -> Set<String>, private val warnings: WatchdogWarningBanner,
    private val addressReader: (AccessibilityNodeInfo, String) -> String?
) {
    private val bursts = RapidScrollBursts()
    private var running = false
    private data class Feed(val pkg: String, val window: Int, val name: String)
    private var feed: Feed? = null
    private var sampledAt = 0L
    private var scrolledFeed: Feed? = null
    private val movement = ScrollMovement()
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            try { sample() } catch (_: RuntimeException) { pause() }
            if (running) handler.postDelayed(this, 250)
        }
    }

    fun start() {
        if (running) return
        WatchdogStore.observeRapid(service, false)
        running = true
        handler.post(tick)
    }
    fun stop() {
        running = false
        handler.removeCallbacks(tick)
        bursts.reset()
        pause()
    }
    private fun pause() {
        feed = null
        scrolledFeed = null
        movement.reset()
        WatchdogStore.observeRapid(service, false)
        warnings.update("rapid", 0)
    }

    /** Only scrolling from the current foreground feed contributes; never page text or background events. */
    fun onScroll(event: AccessibilityEvent) {
        if (!running || event.eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED) return
        val current = feed ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - sampledAt > 1000 || event.packageName?.toString() != current.pkg ||
            event.windowId != current.window || SystemClock.uptimeMillis() - event.eventTime !in 0..2000) return
        val source = event.source ?: return
        try {
            if (!source.isVisibleToUser || source.isEditable || source.isPassword ||
                source.packageName?.toString() != current.pkg || excludedAncestors(source)) return
            val vertical = movement.vertical(source.hashCode(), event.scrollX, event.scrollY, event.fromIndex,
                event.scrollDeltaX, event.scrollDeltaY,
                source.className?.toString()?.contains("HorizontalScrollView") == true ||
                    event.maxScrollX > 0 && event.maxScrollY == 0)
            if (vertical) {
                bursts.scroll(now, true)
                scrolledFeed = current
            }
        } finally { source.recycle() }
    }

    private fun excludedAncestors(source: AccessibilityNodeInfo): Boolean {
        var node: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(source)
        try {
            repeat(20) {
                val current = node ?: return false
                val id = current.viewIdResourceName?.substringAfter(":id/").orEmpty().lowercase()
                if (ShortFormFeeds.excludedControl(id)) return true
                val parent = current.parent
                current.recycle()
                node = parent
            }
            return false
        } finally { node?.recycle() }
    }

    private fun sample() {
        val awake = service.getSystemService(PowerManager::class.java).isInteractive &&
            !service.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        if (!awake || WatchdogStore.remaining(service) > 0) { bursts.reset(); pause(); return }
        val root = service.rootInActiveWindow
        if (root == null) { pause(); return }
        try {
            val pkg = root.packageName?.toString().orEmpty()
            val name = when {
                pkg in ShortFormFeeds.nativePackages -> nativeFeed(root, pkg)
                pkg in browsers() -> if (hasVisiblePage(root)) ShortFormFeeds.website(addressReader(root, pkg)) else null
                else -> null
            }
            val next = name?.let { Feed(pkg, root.windowId, it) }
            if (next != feed) { movement.reset(); scrolledFeed = null }
            feed = next
            sampledAt = SystemClock.elapsedRealtime()
            val active = next != null && next == scrolledFeed && bursts.active(sampledAt)
            val state = WatchdogStore.observeRapid(service, active)
            if (state.untilWall != 0L) {
                warnings.update("rapid", 0)
                bursts.reset()
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                Policy.sweep(service)
                LockService.start(service)
                Toast.makeText(service, "30-minute rapid-scrolling cooldown. Browsers, Google, YouTube, Instagram, Reddit and app installs are locked.",
                    Toast.LENGTH_LONG).show()
            } else {
                val stage = CategoryWatchdog.warning(state)
                val text = if (stage == 1) "Rapid scrolling detected. Stop scrolling or leave this feed. Final warning at 5 seconds; cooldown at 10 seconds of continued activity."
                    else "FINAL WARNING: stop scrolling or leave. At 10 seconds of continued activity, browsing, social apps and app installs lock for 30 minutes."
                warnings.update("rapid", stage, text)
            }
        } finally { root.recycle() }
    }

    private fun hasVisiblePage(root: AccessibilityNodeInfo): Boolean {
        var visited = 0
        fun walk(n: AccessibilityNodeInfo, depth: Int): Boolean {
            if (++visited > 1000 || depth > 40 || !n.isVisibleToUser) return false
            val type = n.className?.toString().orEmpty()
            if (type == "android.webkit.WebView" || type.endsWith("GeckoView")) return true
            for (i in 0 until n.childCount) {
                if (visited >= 1000) break
                val child = n.getChild(i) ?: continue
                try { if (walk(child, depth + 1)) return true } finally { child.recycle() }
            }
            return false
        }
        return walk(root, 0)
    }

    private fun nativeFeed(root: AccessibilityNodeInfo, pkg: String): String? {
        val ids = mutableSetOf<String>()
        val selected = mutableSetOf<String>()
        var editor = false
        var truncated = false
        var visited = 0
        fun walk(n: AccessibilityNodeInfo, depth: Int, selectedTab: Boolean = false) {
            if (++visited > 1500 || depth > 45) { truncated = true; return }
            if (!n.isVisibleToUser || n.isPassword) return
            if (n.className?.toString() == "android.webkit.WebView") return
            if (n.packageName?.toString() != pkg) return
            if (n.isEditable) { editor = true; return }
            val id = n.viewIdResourceName?.takeIf { it.startsWith("$pkg:id/") }
                ?.substringAfter(":id/").orEmpty().lowercase()
            if (id.isNotEmpty()) ids.add(id)
            if (n.isScrollable && n.className?.toString()?.contains("Horizontal") != true) ids.add("feed_scroll")
            val nav = id.contains("tab") || id.contains("pivot_bar") || id.contains("navigation") ||
                n.className?.toString()?.endsWith("Tab") == true || n.isClickable &&
                listOfNotNull(n.text, n.contentDescription).any {
                    it.toString().substringBefore(',').trim().lowercase() in setOf("shorts", "reels", "home", "popular", "watch")
                }
            val chosen = selectedTab || nav && (n.isSelected || n.isChecked)
            if (chosen) {
                listOfNotNull(n.text, n.contentDescription).forEach { value ->
                    val label = value.toString().substringBefore(',').trim().lowercase()
                    if (label in setOf("shorts", "reels", "home", "popular", "watch")) selected.add(label)
                }
                if (id in setOf("clips_tab", "clips_tab_icon", "reels_tab")) selected.add("reels")
                if (id in setOf("feed_tab", "home_tab", "home_tab_icon")) selected.add("home")
            }
            for (i in 0 until n.childCount) {
                if (visited >= 1500) { truncated = true; break }
                val child = n.getChild(i) ?: continue
                try { walk(child, depth + 1, chosen) } finally { child.recycle() }
            }
        }
        walk(root, 0)
        return if (editor || truncated) null else ShortFormFeeds.nativeFeed(pkg, ids, selected)
    }
}
