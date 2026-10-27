package com.example.ariseosleeper

/** Wall time schedules daily alarms; elapsed time protects waits within a boot. */
internal data class AlarmClockSnapshot(val wall: Long, val elapsed: Long, val boot: Int)
