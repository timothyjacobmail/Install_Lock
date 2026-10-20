package com.example.installlock

import java.net.URI

/** Local heuristics, not a claim to recognize every short-form player or physical gesture. */
internal object ShortFormFeeds {
    val nativePackages = setOf("com.google.android.youtube", "com.instagram.android", "com.reddit.frontpage")
    internal val navigationLabels = setOf("shorts", "reels", "home", "popular", "watch")
    internal val reelsTabs = setOf("clips_tab", "clips_tab_icon", "reels_tab")
    internal val homeTabs = setOf("feed_tab", "home_tab", "home_tab_icon")
    private val redditSorts = setOf("hot", "new", "top", "rising", "best")
    private val redditTabs = setOf("home", "popular", "watch")
    private val reelPaths = setOf("reel", "reels")
    private val youtubePlayers = setOf("reel_recycler", "reel_recycler_view", "reel_player_page_container",
        "reel_watch_player", "shorts_player", "shorts_recycler_view")
    private val excludedControls = listOf("comment", "reply", "message", "inbox", "chat", "composer")

    fun website(address: String?): String? {
        val text = address?.trim() ?: return null
        val host = DomainRules.hostFromAddress(text) ?: return null
        val uri = try { URI(if (text.contains("://")) text else "https://$text") }
            catch (_: Exception) { return null }
        val parts = uri.path.orEmpty().lowercase().split('/').filter { it.isNotEmpty() }
        fun domain(name: String) = host == name || host.endsWith(".$name")
        return when {
            domain("youtube.com") && parts.firstOrNull() == "shorts" -> "YouTube Shorts"
            domain("instagram.com") && parts.firstOrNull() in reelPaths -> "Instagram Reels"
            domain("instagram.com") && parts.isEmpty() -> "Instagram home feed"
            domain("reddit.com") && (parts.isEmpty() ||
                parts.size == 1 && parts[0] in redditSorts ||
                parts.size in 2..3 && parts[0] == "r" &&
                (parts.size == 2 || parts[2] in redditSorts)) -> "Reddit feed"
            else -> null
        }
    }

    /** Only native resource IDs and selected navigation controls enter this classifier. */
    fun nativeFeed(pkg: String, ids: Set<String>, selected: Set<String>): String? {
        if (ids.any { excludedControl(it) }) return null
        return when (pkg) {
            "com.google.android.youtube" -> if ("shorts" in selected || ids.any {
                it in youtubePlayers
            }) "YouTube Shorts" else null
            "com.instagram.android" -> when {
                "reels" in selected || ids.any { it.startsWith("clips_viewer_") } -> "Instagram Reels"
                "home" in selected && ids.any { it.startsWith("row_feed_") } -> "Instagram home feed"
                else -> null
            }
            "com.reddit.frontpage" -> if (selected.any { it in redditTabs } &&
                ids.any { it.contains("feed") || it.startsWith("post_") }) "Reddit feed" else null
            else -> null
        }
    }

    fun excludedControl(id: String) = excludedControls
        .any { id.contains(it) } && !id.contains("button") && !id.endsWith("_count") &&
        !id.endsWith("_icon") && !id.endsWith("_label")
}

/** Tracks one scrolling container at a time; a visible item range is not itself movement. */
internal class ScrollMovement {
    private var source: Int? = null
    private var x = -1
    private var y = -1
    private var index = -1
    fun reset() { source = null }
    fun vertical(identity: Int, nextX: Int, nextY: Int, nextIndex: Int,
                 reportedX: Int, reportedY: Int, horizontalOnly: Boolean = false): Boolean {
        val same = source == identity
        val dy = if (same && nextY >= 0 && y >= 0) nextY.toLong() - y else 0L
        val dx = if (same && nextX >= 0 && x >= 0) nextX.toLong() - x else 0L
        val indexed = same && nextIndex >= 0 && index >= 0 && nextIndex != index
        source = identity; x = nextX; y = nextY; index = nextIndex
        // Widgets use either 0 or -1 for deltas they did not populate.
        val vertical = if (reportedY != -1 && reportedY != 0) reportedY.toLong() else dy
        val horizontal = if (reportedX != -1 && reportedX != 0) reportedX.toLong() else dx
        return !horizontalOnly && (kotlin.math.abs(vertical) > 0 &&
            kotlin.math.abs(vertical) >= kotlin.math.abs(horizontal) || indexed && horizontal == 0L)
    }
}

/** Animation events separated by less than 600ms form one burst, including one long fling. */
internal class RapidScrollBursts {
    companion object {
        const val WINDOW_MS = 15_000L
        const val BURSTS = 6
        const val QUIET_GAP_MS = 600L
        const val IDLE_MS = 3_000L
    }
    private val bursts = ArrayDeque<Long>()
    private var lastEvent = Long.MIN_VALUE
    private var lastBurst = Long.MIN_VALUE
    private var armed = false

    fun scroll(now: Long, verticalMovement: Boolean) {
        if (!verticalMovement) return
        if (lastEvent != Long.MIN_VALUE && now < lastEvent) reset()
        if (lastEvent == Long.MIN_VALUE || now - lastEvent >= QUIET_GAP_MS) {
            // A gap after a qualified pattern requires a fresh pattern before resuming.
            if (lastBurst != Long.MIN_VALUE && now - lastBurst >= IDLE_MS) armed = false
            while (bursts.isNotEmpty() && now - bursts.first() > WINDOW_MS) bursts.removeFirst()
            bursts.addLast(now)
            lastBurst = now
            if (bursts.size >= BURSTS) armed = true
        }
        lastEvent = now
    }

    // A stream of fling animation events cannot keep the warning timer running indefinitely.
    fun active(now: Long) = armed && lastBurst != Long.MIN_VALUE && now - lastBurst in 0 until IDLE_MS
    fun reset() { bursts.clear(); lastEvent = Long.MIN_VALUE; lastBurst = Long.MIN_VALUE; armed = false }
}
