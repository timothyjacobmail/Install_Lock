package com.example.delayedsecrect

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle

internal const val SECRET_CLIP_TOKEN = "com.example.installlock.SECRET_CLIP_TOKEN"

fun copySecretToClipboard(context: Context, value: String, token: String? = null) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("secret", value)

    clip.description.extras = PersistableBundle().apply {
        // The literal key also flags sensitive clips on Android versions before API 33.
        putBoolean("android.content.extra.IS_SENSITIVE", true)
        if (token != null) putString(SECRET_CLIP_TOKEN, token)
    }

    clipboard.setPrimaryClip(clip)
}

fun clearClipboardIfMatches(context: Context, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val current = clipboard.primaryClip
    val currentText = current?.getItemAt(0)?.text?.toString()
    if (currentText == value) {
        clipboard.clearPrimaryClip()
    }
}
