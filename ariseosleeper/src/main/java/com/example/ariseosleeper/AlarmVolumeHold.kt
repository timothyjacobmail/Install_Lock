package com.example.ariseosleeper

internal interface AlarmVolumeAccess {
    fun savedLevel(): Int?
    fun saveLevel(level: Int?)
    fun currentLevel(): Int
    fun minimumLevel(): Int
    fun maximumLevel(): Int
    fun setLevel(level: Int)
}

/** The persisted level belongs to the whole routine, including quiet and follow-up waits. */
internal class AlarmVolumeHold(private val audio: AlarmVolumeAccess) {
    fun reconcile(active: Boolean) {
        val saved = audio.savedLevel()
        if (!active) {
            if (saved != null) audio.saveLevel(null)
            return
        }
        val current = audio.currentLevel()
        val max = audio.maximumLevel()
        val min = audio.minimumLevel().coerceAtLeast(1).coerceAtMost(max)
        val target = (saved ?: current).coerceIn(min, max)
        // Save before changing sound, so process death cannot adopt a lowered volume.
        if (target != saved) audio.saveLevel(target)
        if (current != target) audio.setLevel(target)
    }
}
