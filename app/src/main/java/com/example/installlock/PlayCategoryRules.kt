package com.example.installlock

internal enum class PlayInstallCategory(val storeId: String, val title: String) {
    MUSIC_AND_AUDIO("MUSIC_AND_AUDIO", "Music & Audio"),
    FINANCE("FINANCE", "Finance"),
    SHOPPING("SHOPPING", "Shopping")
}

internal object PlayCategoryRules {
    const val PLAY_STORE = "com.android.vending"

    fun exemption(installer: String?, storeCategory: String?): PlayInstallCategory? =
        if (installer == PLAY_STORE) PlayInstallCategory.entries.firstOrNull { it.storeId == storeCategory } else null

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
