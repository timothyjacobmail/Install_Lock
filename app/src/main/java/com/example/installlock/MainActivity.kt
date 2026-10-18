package com.example.installlock

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val owner = Policy.isOwner(this)
        if (owner) LockService.start(this)
        setContentView(TextView(this).apply {
            setPadding(48, 96, 48, 48)
            textSize = 18f
            text = if (owner)
                "Install Lock is active.\nNew apps are suspended for 15 minutes after install."
            else
                "Not set up as device owner yet.\nProvision via QR code (or adb when testing)."
        })
    }
}