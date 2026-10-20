package com.example.installlock

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.window.OnBackInvokedDispatcher
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.color.DynamicColors
import java.net.URI

/** Keeps blocked content covered while the user requests the site through the Share flow. */
class BlockedSiteActivity : AppCompatActivity() {
    companion object {
        private const val SITE = "blocked_site"

        private fun requestSite(address: String): String =
            SharedSite.whitelistOptions(address).firstOrNull()
                ?: SharedSite.shortLinkFromText(address)?.let { link ->
                    val uri = URI(link)
                    "${uri.scheme}://${uri.host}" + (if (uri.port == -1) "" else ":${uri.port}") + uri.rawPath.orEmpty()
                }.orEmpty()

        internal fun openIntent(context: Context, address: String): Intent =
            Intent(context, BlockedSiteActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                // Never carry temporary sign-in codes, URL credentials or fragments into activity state.
                .putExtra(SITE, requestSite(address))

        internal fun whitelistIntent(context: Context, address: String): Intent =
            Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, requestSite(address))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) { goHome() }
        }
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        render()
    }

    private fun render() {
        val site = requestSite(intent.getStringExtra(SITE).orEmpty())
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = UiStyle.dp(this@BlockedSiteActivity, 24)
            setPadding(padding, padding, padding, padding)
            addView(UiStyle.eyebrow(this@BlockedSiteActivity, "CHROME WHITELIST"))
            addView(UiStyle.label(this@BlockedSiteActivity, "Website blocked", 26f))
            addView(UiStyle.card(this@BlockedSiteActivity,
                UiStyle.label(this@BlockedSiteActivity, "This page is outside your active Chrome whitelist."),
                UiStyle.label(this@BlockedSiteActivity, site.ifEmpty { "Website address unavailable" }, 20f),
                UiStyle.label(this@BlockedSiteActivity,
                    "You can request this site here without opening Chrome's Share menu. " +
                        "Choose a path or the whole domain on the next screen. " +
                        "The usual whitelist password and waiting period still apply. Other locks also remain active."),
                UiStyle.button(this@BlockedSiteActivity, "Whitelist this site", primary = true) {
                    startActivity(whitelistIntent(this@BlockedSiteActivity, site))
                    finish()
                }
            ), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = padding })
            addView(UiStyle.button(this@BlockedSiteActivity, "Home") { goHome() }, LinearLayout.LayoutParams(-1, -2))
        }
        val scroll = ScrollView(this).apply {
            addView(column)
            isVerticalScrollBarEnabled = false
            setBackgroundColor(UiStyle.color(this@BlockedSiteActivity, com.google.android.material.R.attr.colorSurface))
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                WindowInsets.CONSUMED
            }
        }
        setContentView(scroll)
        scroll.requestApplyInsets()
    }

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    // Returning to Chrome would expose the same blocked page and immediately trigger the guard again.
    @android.annotation.SuppressLint("MissingSuperCall")
    @Suppress("OVERRIDE_DEPRECATION", "GestureBackNavigation")
    override fun onBackPressed() { goHome() }
}
