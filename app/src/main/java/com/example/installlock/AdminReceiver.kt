package com.example.installlock

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

class AdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) = start(context)
    override fun onProfileProvisioningComplete(context: Context, intent: Intent) = start(context)

    private fun start(context: Context) {
        Policy.apply(context)
        LockService.start(context)
    }
}