package com.example.installlock

internal data class WatchdogClock(val wall: Long, val elapsed: Long, val boot: Int)
internal data class WatchdogState(
    val spent: Long = 0, val seenWall: Long = 0, val seenElapsed: Long = 0, val seenBoot: Int = -1,
    val sampleElapsed: Long = 0, val sampleBoot: Int = -1, val visible: Boolean = false,
    val untilWall: Long = 0, val untilElapsed: Long = 0, val cooldownBoot: Int = -1
)

/** Shared across websites and browsers. Only consecutive, fresh foreground observations accrue time. */
internal object CategoryWatchdog {
    const val FINAL_WARNING_MS = 5_000L
    const val TRIGGER_MS = 10_000L
    const val RETURN_WINDOW_MS = 10 * 60_000L
    const val COOLDOWN_MS = 30 * 60_000L

    fun remaining(s: WatchdogState, c: WatchdogClock): Long =
        (if (s.cooldownBoot == c.boot) s.untilElapsed - c.elapsed else s.untilWall - c.wall)
            .coerceIn(0, COOLDOWN_MS)

    fun observe(original: WatchdogState, categoryVisible: Boolean, c: WatchdogClock): WatchdogState {
        val remaining = remaining(original, c)
        if (remaining > 0) return if (original.cooldownBoot == c.boot) original else original.copy(
            untilWall = c.wall + remaining, untilElapsed = c.elapsed + remaining, cooldownBoot = c.boot, visible = false)
        val s = if (original.untilWall != 0L) WatchdogState() else original
        val gap = if (s.seenBoot == c.boot) c.elapsed - s.seenElapsed else c.wall - s.seenWall
        val retained = if (gap in 0 until RETURN_WINDOW_MS) s.spent else 0L
        if (!categoryVisible && !s.visible && retained == s.spent) return s
        if (!categoryVisible) return s.copy(spent = retained, visible = false,
            sampleElapsed = c.elapsed, sampleBoot = c.boot)
        val delta = c.elapsed - s.sampleElapsed
        val spent = retained + if (s.visible && s.sampleBoot == c.boot && delta in 0..1000) delta else 0
        if (spent >= TRIGGER_MS) return WatchdogState(untilWall = c.wall + COOLDOWN_MS,
            untilElapsed = c.elapsed + COOLDOWN_MS, cooldownBoot = c.boot)
        return s.copy(spent = spent, seenWall = c.wall, seenElapsed = c.elapsed, seenBoot = c.boot,
            sampleElapsed = c.elapsed, sampleBoot = c.boot, visible = true)
    }

    fun warning(s: WatchdogState): Int = when {
        !s.visible || s.untilWall != 0L -> 0
        s.spent >= FINAL_WARNING_MS -> 2
        else -> 1
    }
}

internal class WebsiteCategories(private val manga: Set<String>) {
    fun mangaDomain(host: String?): String? {
        var candidate = host?.let(DomainRules::normalizeHost) ?: return null
        while ('.' in candidate) {
            if (candidate in manga) return candidate
            candidate = candidate.substringAfter('.')
        }
        return null
    }

    companion object {
        // Additions to the attributed UT1 catalog; all descendants match at a dot boundary.
        val additions = setOf("mangadex.org", "webtoons.com", "mangaplus.shueisha.co.jp")
        fun parse(text: String): Set<String> = text.lineSequence().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith('#') }
            .mapNotNull(DomainRules::normalizeHost).filter { '.' in it }.toSet()
    }
}
