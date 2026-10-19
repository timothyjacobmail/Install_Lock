package com.example.installlock

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Calendar nights, rather than a duration since launch: survives midnight, restarts and DST. */
object CurfewRules {
    fun night(now: Long, zone: ZoneId): LocalDate {
        val local = Instant.ofEpochMilli(now).atZone(zone)
        return if (local.hour < 4) local.toLocalDate().minusDays(1) else local.toLocalDate()
    }

    fun start(night: LocalDate, zone: ZoneId, appealed: Boolean = false): Long =
        (if (appealed) night.plusDays(1).atStartOfDay(zone) else night.atTime(23, 0).atZone(zone))
            .toInstant().toEpochMilli()

    fun end(night: LocalDate, zone: ZoneId): Long =
        night.plusDays(1).atTime(4, 0).atZone(zone).toInstant().toEpochMilli()

    fun canAppeal(now: Long, zone: ZoneId, appealedNight: String?): Boolean {
        val date = night(now, zone)
        val earliest = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val cutoff = date.atTime(21, 0).atZone(zone).toInstant().toEpochMilli()
        return now >= earliest && now < cutoff && appealedNight != date.toString()
    }

    fun blockedUntil(now: Long, zone: ZoneId, appealedNight: String?): Long? {
        val date = night(now, zone)
        return end(date, zone).takeIf { now >= start(date, zone, appealedNight == date.toString()) && now < it }
    }

    fun nextBoundary(now: Long, zone: ZoneId, appealedNight: String?): Long {
        val date = night(now, zone)
        return listOf(start(date, zone, appealedNight == date.toString()), end(date, zone),
            start(date.plusDays(1), zone)).filter { it > now }.min()
    }

    fun enrollInstall(firstInstall: Long, system: Boolean, activatedOn: LocalDate, now: Long, zone: ZoneId): Boolean =
        !system && firstInstall in 1..now && !Instant.ofEpochMilli(firstInstall).atZone(zone).toLocalDate().isBefore(activatedOn)
}
