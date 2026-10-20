package com.example.delayedsecrect

import android.content.Context

/** Metadata is safe to display before readiness. Values are released only by an explicit claim. */
data class InlineSecretChoice(val id: String, val name: String, val delayMinutes: Int)
data class InlineSecretStatus(val name: String, val info: TimerInfo)
data class InlineSecretClaim(val value: String, val expiresElapsed: Long)

class InlineSecrets(context: Context) {
    private val c = context.applicationContext
    private val requests = InstallLockRequests(c)
    private val secrets = SecretStore(c)

    fun prepare(id: String, scope: String, title: String) { requests.receive(id, scope, title) }
    fun choices(scope: String): List<InlineSecretChoice> {
        val suggested = requests.suggestedSecret(scope)
        val defaultDelay = SettingsStore(c).getDelayMinutes()
        return secrets.getAll().sortedWith(compareBy<Secret> { it.id != suggested }.thenBy { it.name.lowercase() })
            .map { InlineSecretChoice(it.id, it.name, it.delayMinutes ?: defaultDelay) }
    }
    fun select(id: String, secretId: String) = requests.select(id, secretId)
    fun status(id: String): InlineSecretStatus? {
        val request = requests.get(id) ?: return null
        val (secret, info) = requests.availability(request) ?: return null
        return InlineSecretStatus(secret.name, info)
    }
    fun claim(id: String): InlineSecretClaim? = requests.claimInline(id)
    fun complete(id: String) { requests.completeInline(id) }
    fun cancel(id: String) { requests.cancel(id) }
}
