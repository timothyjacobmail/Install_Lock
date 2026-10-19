package com.example.installlock

internal enum class PlayInstallCategory(val storeId: String, val title: String) {
    ART_AND_DESIGN("ART_AND_DESIGN", "Art & Design"),
    AUTO_AND_VEHICLES("AUTO_AND_VEHICLES", "Auto & Vehicles"),
    BEAUTY("BEAUTY", "Beauty"),
    BOOKS_AND_REFERENCE("BOOKS_AND_REFERENCE", "Books & Reference"),
    BUSINESS("BUSINESS", "Business"),
    COMICS("COMICS", "Comics"),
    COMMUNICATION("COMMUNICATION", "Communication"),
    DATING("DATING", "Dating"),
    EDUCATION("EDUCATION", "Education"),
    ENTERTAINMENT("ENTERTAINMENT", "Entertainment"),
    EVENTS("EVENTS", "Events"),
    FINANCE("FINANCE", "Finance"),
    FOOD_AND_DRINK("FOOD_AND_DRINK", "Food & Drink"),
    HEALTH_AND_FITNESS("HEALTH_AND_FITNESS", "Health & Fitness"),
    HOUSE_AND_HOME("HOUSE_AND_HOME", "House & Home"),
    LIBRARIES_AND_DEMO("LIBRARIES_AND_DEMO", "Libraries & Demo"),
    LIFESTYLE("LIFESTYLE", "Lifestyle"),
    MAPS_AND_NAVIGATION("MAPS_AND_NAVIGATION", "Maps & Navigation"),
    MEDICAL("MEDICAL", "Medical"),
    MUSIC_AND_AUDIO("MUSIC_AND_AUDIO", "Music & Audio"),
    NEWS_AND_MAGAZINES("NEWS_AND_MAGAZINES", "News & Magazines"),
    PARENTING("PARENTING", "Parenting"),
    PERSONALIZATION("PERSONALIZATION", "Personalization"),
    PHOTOGRAPHY("PHOTOGRAPHY", "Photography"),
    PRODUCTIVITY("PRODUCTIVITY", "Productivity"),
    SHOPPING("SHOPPING", "Shopping"),
    SOCIAL("SOCIAL", "Social"),
    SPORTS("SPORTS", "Sports"),
    TOOLS("TOOLS", "Tools"),
    TRAVEL_AND_LOCAL("TRAVEL_AND_LOCAL", "Travel & Local"),
    VIDEO_PLAYERS("VIDEO_PLAYERS", "Video Players & Editors"),
    WEATHER("WEATHER", "Weather"),
    GAME_ACTION("GAME_ACTION", "Action"),
    GAME_ADVENTURE("GAME_ADVENTURE", "Adventure"),
    GAME_ARCADE("GAME_ARCADE", "Arcade"),
    GAME_BOARD("GAME_BOARD", "Board"),
    GAME_CARD("GAME_CARD", "Card"),
    GAME_CASINO("GAME_CASINO", "Casino"),
    GAME_CASUAL("GAME_CASUAL", "Casual"),
    GAME_EDUCATIONAL("GAME_EDUCATIONAL", "Educational"),
    GAME_MUSIC("GAME_MUSIC", "Music"),
    GAME_PUZZLE("GAME_PUZZLE", "Puzzle"),
    GAME_RACING("GAME_RACING", "Racing"),
    GAME_ROLE_PLAYING("GAME_ROLE_PLAYING", "Role Playing"),
    GAME_SIMULATION("GAME_SIMULATION", "Simulation"),
    GAME_SPORTS("GAME_SPORTS", "Sports"),
    GAME_STRATEGY("GAME_STRATEGY", "Strategy"),
    GAME_TRIVIA("GAME_TRIVIA", "Trivia"),
    GAME_WORD("GAME_WORD", "Word");

    val isGame: Boolean get() = storeId.startsWith("GAME_")
    val displayName: String get() = if (isGame) "Games: $title" else title
}

internal object PlayCategoryRules {
    const val PLAY_STORE = "com.android.vending"
    val defaultCategories = setOf("MUSIC_AND_AUDIO", "FINANCE", "SHOPPING")
    private val knownCategories = PlayInstallCategory.entries.map { it.storeId }.toSet()

    fun validCategories(categories: Set<String>) = categories.intersect(knownCategories)

    fun exemption(installer: String?, storeCategory: String?, enabled: Set<String> = defaultCategories): PlayInstallCategory? =
        if (installer == PLAY_STORE && storeCategory in enabled)
            PlayInstallCategory.entries.firstOrNull { it.storeId == storeCategory } else null

    fun installWaitUntil(isSystem: Boolean, installedAt: Long, now: Long, waitMs: Long,
                         exemption: PlayInstallCategory? = null): Long? =
        if (!isSystem && exemption == null && now - installedAt in 0 until waitMs) installedAt + waitMs else null
}

/** A result applies only to the exact installation/version that was checked. */
internal data class PlayCategoryRecord(
    val installedAt: Long, val updatedAt: Long, val category: String, val retryAt: Long
) {
    fun matches(firstInstallTime: Long, lastUpdateTime: Long) =
        installedAt == firstInstallTime && updatedAt == lastUpdateTime

    fun encode() = "$installedAt|$updatedAt|$category|$retryAt"

    companion object {
        fun decode(raw: String?): PlayCategoryRecord? {
            val parts = raw?.split('|') ?: return null
            if (parts.size != 4) return null
            return PlayCategoryRecord(parts[0].toLongOrNull() ?: return null,
                parts[1].toLongOrNull() ?: return null, parts[2], parts[3].toLongOrNull() ?: return null)
        }
    }
}
