package com.example.installlock

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

internal object BrowserPackages {
    const val GOOGLE_APP = "com.google.android.googlequicksearchbox"

    /** Scanning, suspension and release must cover the same apps, including native Google search. */
    fun keywordTargets(context: Context): Set<String> = discover(context) + GOOGLE_APP

    /** Installed browsers that can handle arbitrary web links. */
    fun discover(context: Context): Set<String> {
        val pm = context.packageManager
        val flags = PackageManager.MATCH_ALL or PackageManager.MATCH_DISABLED_COMPONENTS
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        val handlers = pm.queryIntentActivities(probe, flags or PackageManager.GET_RESOLVED_FILTER)
            .filter { it.filter?.let { filter ->
                filter.hasDataScheme("https") && filter.countDataAuthorities() == 0
            } == true }.map { it.activityInfo.packageName }
        val browserApps = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER), flags
        ).map { it.activityInfo.packageName }
        return (handlers + browserApps + Policy.CHROME).filter { it != context.packageName }.toSet()
    }
}
