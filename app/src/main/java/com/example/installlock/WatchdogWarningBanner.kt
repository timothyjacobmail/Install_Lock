package com.example.installlock

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast

/** Shared, touch-through banner. The more urgent warning wins when detectors overlap. */
internal class WatchdogWarningBanner(private val service: AccessibilityService) {
    private val warnings = linkedMapOf<String, Pair<Int, String>>()
    private var shown: Pair<Int, String>? = null
    private var banner: TextView? = null
    private val windows = service.getSystemService(WindowManager::class.java)

    fun update(source: String, stage: Int, text: String = "") {
        if (stage == 0) warnings.remove(source) else warnings[source] = stage to text
        val next = warnings.values.maxByOrNull { it.first }
        if (next == shown) return
        banner?.let { try { windows.removeViewImmediate(it) } catch (_: RuntimeException) { } }
        banner = null
        shown = next
        if (next == null) return
        val density = service.resources.displayMetrics.density
        val view = TextView(service).apply {
            this.text = next.second
            textSize = 17f
            setTextColor(Color.WHITE)
            setBackgroundColor(if (next.first == 1) Color.rgb(125, 65, 0) else Color.rgb(145, 20, 25))
            val p = (16 * density).toInt()
            setPadding(p, p, p, p)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.BOTTOM; y = (56 * density).toInt() }
        try { windows.addView(view, params); banner = view }
        catch (_: RuntimeException) { Toast.makeText(service, next.second, Toast.LENGTH_LONG).show() }
    }
}
