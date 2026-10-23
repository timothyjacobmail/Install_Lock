package com.example.installlock

/** Custom tabs expose an origin, not the current path, in their read-only URL control. */
internal object ChromeTabRecovery {
    // Chrome versions use both labels for the same custom-tab action. Keep this
    // exact: a generic "Open in browser" can launch a different default browser.
    private val openInChromeLabels = setOf("Open in Chrome", "Open in Chrome browser")

    fun needsFullTab(resourceId: String?, editable: Boolean, focused: Boolean, address: String?): Boolean =
        resourceId == "com.android.chrome:id/url_bar" && !editable && !focused &&
            SharedSite.whitelistOptions(address).size == 1

    // The generic "Open in browser" item can target a different default browser. Only
    // choose Chrome explicitly; unsupported/localized menus fall back to manual entry.
    fun isOpenInChrome(packageName: String?, resourceId: String?, text: String?): Boolean =
        packageName == "com.android.chrome" &&
            resourceId in setOf("com.android.chrome:id/open_in_browser_id", "com.android.chrome:id/menu_item_text") &&
            openInChromeLabels.any { it.equals(text?.trim(), ignoreCase = true) }
}
