package com.example.installlock

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object Store {
    const val UNLOCK_MS = 30 * 60 * 1000L
    private val DEFAULT_PROTECTED = setOf("com.google.android.youtube", "com.android.chrome")

    private fun p(c: Context) = c.getSharedPreferences("install_lock", Context.MODE_PRIVATE)

    fun hasPassword(c: Context) = p(c).contains("hash")

    fun protectedApps(c: Context): Set<String> =
        p(c).getStringSet("protected", DEFAULT_PROTECTED)!!.toSet()

    fun setProtectedApps(c: Context, s: Set<String>) =
        p(c).edit().putStringSet("protected", s).apply()

    fun unlockedUntil(c: Context) = p(c).getLong("unlockedUntil", 0L)

    fun setUnlockedUntil(c: Context, t: Long) =
        p(c).edit().putLong("unlockedUntil", t).apply()

    fun lockoutRemainingMs(c: Context) =
        (p(c).getLong("lockoutUntil", 0L) - System.currentTimeMillis()).coerceAtLeast(0L)

    /** At least 8 characters, with at least one letter and one digit. */
    fun isValidNewPassword(pw: String) =
        pw.length >= 8 && pw.any { it.isLetter() } && pw.any { it.isDigit() }

    fun setPassword(c: Context, pw: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        p(c).edit()
            .putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("hash", Base64.encodeToString(hash(pw, salt), Base64.NO_WRAP))
            .putInt("fails", 0)
            .putLong("lockoutUntil", 0L)
            .apply()
    }

    /** True if correct. Wrong guesses trigger escalating lockouts. */
    fun checkPassword(c: Context, pw: String): Boolean {
        val prefs = p(c)
        if (lockoutRemainingMs(c) > 0) return false
        val salt = Base64.decode(prefs.getString("salt", "")!!, Base64.NO_WRAP)
        val expected = Base64.decode(prefs.getString("hash", "")!!, Base64.NO_WRAP)
        if (MessageDigest.isEqual(expected, hash(pw, salt))) {
            prefs.edit().putInt("fails", 0).apply()
            return true
        }
        val fails = prefs.getInt("fails", 0) + 1
        val e = prefs.edit().putInt("fails", fails)
        if (fails >= 5) {
            val minutes = minOf(60L, 1L shl (fails - 5).coerceAtMost(6))
            e.putLong("lockoutUntil", System.currentTimeMillis() + minutes * 60_000L)
        }
        e.apply()
        return false
    }

    private fun hash(pw: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pw.toCharArray(), salt, 120_000, 256))
            .encoded
}