package com.example.delayedsecrect

data class Secret(
    val id: String,
    val name: String,
    val value: String,
    val delayMinutes: Int? = null,
    val pendingDelay: PendingSecretDelay? = null
)

data class PendingSecretDelay(
    val delayMinutes: Int?, // null switches back to the default
    val effectiveAtElapsedMs: Long,
    val bootCount: Int
)

object SecretDelay {
    const val DECREASE_WAIT_MS = 25 * 60_000L

    fun request(secret: Secret, minutes: Int?, defaultMinutes: Int, now: Long, bootCount: Int): Secret {
        require(minutes == null || minutes >= 1)
        val current = reconcile(secret, now, bootCount)
        return if ((minutes ?: defaultMinutes) >= (current.delayMinutes ?: defaultMinutes)) {
            current.copy(delayMinutes = minutes, pendingDelay = null)
        } else {
            current.copy(pendingDelay = PendingSecretDelay(minutes, now + DECREASE_WAIT_MS, bootCount))
        }
    }

    fun reconcile(secret: Secret, now: Long, bootCount: Int): Secret {
        val pending = secret.pendingDelay ?: return secret
        // A reboot restarts the wait conservatively. Wall-clock changes cannot shorten it.
        if (pending.bootCount != bootCount) {
            return secret.copy(pendingDelay = pending.copy(
                effectiveAtElapsedMs = now + DECREASE_WAIT_MS, bootCount = bootCount
            ))
        }
        return if (now >= pending.effectiveAtElapsedMs) {
            secret.copy(delayMinutes = pending.delayMinutes, pendingDelay = null)
        } else secret
    }
}
