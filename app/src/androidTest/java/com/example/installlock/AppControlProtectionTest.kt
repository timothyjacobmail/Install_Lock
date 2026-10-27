package com.example.installlock

import android.app.admin.DevicePolicyManager
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class AppControlProtectionTest {
    /** Changes a test package's protection only on a disposable device-owner emulator. */
    @Test fun selectingAndRemovingAnAppUpdatesUninstallAndForceStopTogether() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val c = instrumentation.targetContext
        assumeTrue(Build.MODEL.contains("sdk") && Policy.isOwner(c))
        val target = instrumentation.context.packageName
        val dpm = c.getSystemService(DevicePolicyManager::class.java)
        val admin = Policy.admin(c)
        val original = Store.noUninstall(c)
        try {
            Store.setNoUninstall(c, original + target)
            Policy.enforceNoUninstall(c)
            assertTrue(dpm.isUninstallBlocked(admin, target))
            assertTrue(target in dpm.getUserControlDisabledPackages(admin))
            assertTrue(c.packageName in dpm.getUserControlDisabledPackages(admin))

            Store.setNoUninstall(c, original - target)
            Policy.allowUninstall(c, listOf(target))
            assertFalse(dpm.isUninstallBlocked(admin, target))
            assertFalse(target in dpm.getUserControlDisabledPackages(admin))
            assertTrue(dpm.isUninstallBlocked(admin, c.packageName))
            assertTrue(c.packageName in dpm.getUserControlDisabledPackages(admin))
        } finally {
            Store.setNoUninstall(c, original)
            if (target !in original) Policy.allowUninstall(c, listOf(target))
            Policy.enforceNoUninstall(c)
        }
    }
}
