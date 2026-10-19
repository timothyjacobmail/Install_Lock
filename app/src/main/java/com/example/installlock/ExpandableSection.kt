package com.example.installlock

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** A native, keyboard-accessible dropdown; collapsed controls stay out of focus navigation. */
internal class ExpandableSection(
    context: Context,
    private val title: String,
    summary: String,
    initiallyExpanded: Boolean,
    private val onExpanded: (Boolean) -> Unit,
    vararg children: View
) : LinearLayout(context) {
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private val header = Button(context).apply {
        isAllCaps = false
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        minHeight = dp(52)
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
    }
    private val body = LinearLayout(context).apply { orientation = VERTICAL }
    private var expanded = initiallyExpanded

    init {
        orientation = VERTICAL
        setPadding(dp(12), dp(6), dp(12), dp(10))
        val colors = context.obtainStyledAttributes(intArrayOf(android.R.attr.colorBackground, android.R.attr.textColorPrimary))
        val surface = colors.getColor(0, 0xFFFFFFFF.toInt())
        val foreground = colors.getColor(1, 0xFF000000.toInt())
        colors.recycle()
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(surface)
            setStroke(dp(1), (foreground and 0x00FFFFFF) or 0x33000000)
        }
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        if (summary.isNotBlank()) addView(TextView(context).apply {
            text = summary
            textSize = 14f
            setPadding(dp(4), 0, dp(4), dp(8))
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        children.forEach { body.addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(4)
        }) }
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        header.setOnClickListener { changeExpansion(!expanded) }
        update()
    }

    fun expand() = changeExpansion(true)

    private fun changeExpansion(value: Boolean) {
        expanded = value
        update()
        onExpanded(value)
    }

    private fun update() {
        header.text = (if (expanded) "▾ " else "▸ ") + title
        header.stateDescription = if (expanded) "Expanded" else "Collapsed"
        body.visibility = if (expanded) View.VISIBLE else View.GONE
    }
}
