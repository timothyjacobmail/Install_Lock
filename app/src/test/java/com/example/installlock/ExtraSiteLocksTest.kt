package com.example.installlock

import org.junit.Assert.*
import org.junit.Test

class ExtraSiteLocksTest {
    private val now = 1_800_000_000_000L

    @Test fun additionsAreImmediateAndRemovalsWaitExactly45Minutes() {
        val added = ExtraSiteLocks().add("example.com", now)
        assertEquals(setOf("example.com"), added.domains)
        val requested = added.requestRemoval("example.com", now)
        val deadline = requested.removals.getValue("example.com")
        assertEquals(now + 45 * 60_000L, deadline)
        assertEquals(setOf("example.com"), requested.expire(deadline - 1).domains)
        assertTrue(requested.expire(deadline).domains.isEmpty())
        assertTrue(requested.expire(deadline).removals.isEmpty())
    }

    @Test fun repeatRequestsCannotShortenOrExtendTheWait() {
        val requested = ExtraSiteLocks().add("example.com", now).requestRemoval("example.com", now)
        assertEquals(requested, requested.requestRemoval("example.com", now + 60_000))
        assertEquals(requested, requested.requestRemoval("missing.com", now + 60_000))
    }

    @Test fun readdingCancelsPendingRemovalOrRestoresAnExpiredLock() {
        val requested = ExtraSiteLocks().add("example.com", now).requestRemoval("example.com", now)
        val kept = requested.add("example.com", now + 1)
        assertTrue(kept.expire(now + 46 * 60_000).domains.contains("example.com"))
        assertTrue(kept.removals.isEmpty())
        val restored = requested.add("example.com", now + 46 * 60_000)
        assertTrue(restored.domains.contains("example.com"))
        assertTrue(restored.removals.isEmpty())
    }

    @Test fun independentRemovalsSurviveReconstructionAndDoNotResurrect() {
        val requested = ExtraSiteLocks().add("a.example", now).add("b.example", now)
            .requestRemoval("a.example", now).requestRemoval("b.example", now + 60_000)
        val restored = ExtraSiteLocks(requested.domains.toSet(), requested.removals.toMap())
        val firstExpired = restored.expire(now + 45 * 60_000)
        assertEquals(setOf("b.example"), firstExpired.domains)
        assertEquals(setOf("b.example"), firstExpired.removals.keys)
        assertTrue(firstExpired.expire(now + 46 * 60_000).domains.isEmpty())
        assertFalse(firstExpired.expire(now).domains.contains("a.example"))
    }

    @Test fun domainLocksOverrideSameDomainPathsPortsAndSubdomainsInWhitelist() {
        val locks = ExtraSiteLocks(setOf("example.com"))
        assertEquals(listOf("other.com", "notexample.com", "example.com.evil.org"),
            locks.allowedPatterns(listOf("example.com", "example.com/path", "example.com:443/path",
                "sub.example.com", "deep.sub.example.com/path", "other.com", "notexample.com", "example.com.evil.org")))
    }

    @Test fun blockingASubdomainDoesNotRemoveParentOrSiblingAllowEntries() {
        val locks = ExtraSiteLocks(setOf("blocked.example.com"))
        assertEquals(listOf("example.com", "example.com/path", "mail.example.com"),
            locks.allowedPatterns(listOf("example.com", "example.com/path", "mail.example.com",
                "blocked.example.com", "sub.blocked.example.com")))
    }

    @Test fun expiredLockRestoresWhitelistExceptionsWithoutChangingSavedWhitelist() {
        val whitelist = listOf("example.com/path", "other.com")
        val pending = ExtraSiteLocks().add("example.com", now).requestRemoval("example.com", now)
        assertEquals(listOf("other.com"), pending.allowedPatterns(whitelist))
        assertEquals(whitelist, pending.expire(now + 45 * 60_000).allowedPatterns(whitelist))
    }
}
