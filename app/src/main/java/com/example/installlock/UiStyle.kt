package com.example.installlock

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.appcompat.R as AppCompatR
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.R as MaterialR

/** Shared view styling for the screens built in code. Uses the active Material palette. */
internal object UiStyle {
    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()
    fun color(context: Context, attr: Int) = MaterialColors.getColor(context, attr, "InstallLock UI")
    fun shape(context: Context, color: Int, radius: Int = 16) = GradientDrawable().apply {
        cornerRadius = dp(context, radius).toFloat()
        setColor(color)
    }

    fun label(context: Context, text: String, size: Float = 16f) = TextView(context).apply {
        this.text = text
        textSize = if (size >= 22f) 28f else size
        setTextColor(color(context, if (size >= 20f) MaterialR.attr.colorOnSurface else MaterialR.attr.colorOnSurfaceVariant))
        if (size >= 20f) typeface = Typeface.create(resources.getFont(R.font.inter), Typeface.BOLD)
        setLineSpacing(dp(context, 3).toFloat(), 1.05f)
        setPadding(0, dp(context, 6), 0, dp(context, if (size >= 22f) 12 else 6))
        if (text.isEmpty()) {
            visibility = View.GONE
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                }
            })
        }
    }

    fun eyebrow(context: Context, text: String) = label(context, text, 12f).apply {
        letterSpacing = 0.1f
        setPadding(0, dp(context, 16), 0, dp(context, 12))
    }

    fun button(context: Context, text: String, primary: Boolean = false, onClick: () -> Unit): MaterialButton =
        MaterialButton(context, null, MaterialR.attr.materialButtonStyle).apply {
            this.text = text
            isAllCaps = false
            textSize = 14f
            minHeight = dp(context, 52)
            minimumWidth = dp(context, 48)
            setSingleLine(false)
            maxLines = Int.MAX_VALUE
            insetTop = dp(context, 3)
            insetBottom = dp(context, 3)
            cornerRadius = dp(context, 26)
            setPadding(dp(context, 20), dp(context, 10), dp(context, 20), dp(context, 10))
            val fill = color(context, if (primary) AppCompatR.attr.colorPrimary else MaterialR.attr.colorSecondaryContainer)
            val ink = color(context, if (primary) MaterialR.attr.colorOnPrimary else MaterialR.attr.colorOnSecondaryContainer)
            val states = arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf())
            backgroundTintList = ColorStateList(states, intArrayOf(ColorUtils.setAlphaComponent(fill, 90), fill))
            setTextColor(ColorStateList(states, intArrayOf(ColorUtils.setAlphaComponent(ink, 100), ink)))
            elevation = 0f
            stateListAnimator = null
            val buttonIcon = when {
                text.contains("delayed secrets", ignoreCase = true) -> R.drawable.ic_il_secret
                else -> null
            }
            if (buttonIcon != null) {
                setIconResource(buttonIcon)
                iconTint = textColors
                iconSize = dp(context, 24)
                iconPadding = dp(context, 10)
                iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            }
            setOnClickListener { onClick() }
        }

    fun card(context: Context, vararg children: View): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(context, 20), dp(context, 16), dp(context, 20), dp(context, 16))
        background = shape(context, color(context, MaterialR.attr.colorSurfaceContainer))
        children.forEach { addView(it, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(context, 8) }) }
    }

    fun badge(context: Context, drawable: Int) = ImageView(context).apply {
        setImageResource(drawable)
        imageTintList = ColorStateList.valueOf(color(context, MaterialR.attr.colorTertiary))
        background = shape(context, color(context, MaterialR.attr.colorTertiaryContainer), 14)
        setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun sectionIcon(title: String): Int = when {
        title.contains("curfew", true) -> R.drawable.ic_il_moon_stars
        title.contains("delayed secrets", true) -> R.drawable.ic_il_secret
        title.contains("Browser", true) || title.contains("website", true) || title.contains("domain", true) ||
                title.contains("keyword", true) || title.contains("Blocked words", true) || title.contains("page", true) -> R.drawable.ic_il_globe
        title.contains("app", true) || title.contains("categor", true) || title.contains("Play Store", true) -> R.drawable.ic_il_apps
        title.contains("setting", true) || title.contains("timing", true) || title.contains("duration", true) -> R.drawable.ic_il_tune
        title.contains("how", true) || title.contains("status", true) || title.contains("when", true) -> R.drawable.ic_il_info
        else -> R.drawable.ic_il_lock
    }

    /** Does not replace controls, input types, listeners or password error colors. */
    fun styleInputs(view: View) {
        val context = view.context
        when (view) {
            is EditText -> {
                val originalTypeface = view.typeface
                view.typeface = Typeface.create(context.resources.getFont(R.font.inter), originalTypeface?.style ?: Typeface.NORMAL)
                view.setTextColor(color(context, MaterialR.attr.colorOnSurface))
                view.setHintTextColor(color(context, MaterialR.attr.colorOnSurfaceVariant))
                view.textSize = 16f
                view.minHeight = dp(context, 56)
                view.setPadding(dp(context, 16), dp(context, 14), dp(context, 16), dp(context, 14))
                fun field(focused: Boolean) = shape(context, color(context, MaterialR.attr.colorSurfaceContainerLow), 12).apply {
                    setStroke(dp(context, if (focused) 2 else 1), color(context,
                        if (focused) AppCompatR.attr.colorPrimary else MaterialR.attr.colorOutline))
                }
                view.background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), field(true))
                    addState(intArrayOf(), field(false))
                }
            }
            is Spinner -> {
                view.minimumHeight = dp(context, 56)
                view.backgroundTintList = ColorStateList.valueOf(color(context, AppCompatR.attr.colorPrimary))
            }
            is ScrollView -> view.isVerticalScrollBarEnabled = false
        }
        if (view is ViewGroup) for (index in 0 until view.childCount) styleInputs(view.getChildAt(index))
    }

    fun backButton(context: Context, onClick: () -> Unit): View = android.widget.ImageButton(context).apply {
        setImageResource(R.drawable.ic_il_back)
        imageTintList = ColorStateList.valueOf(color(context, MaterialR.attr.colorOnSurface))
        contentDescription = context.getString(R.string.ui_back)
        background = RippleDrawable(ColorStateList.valueOf(ColorUtils.setAlphaComponent(
            color(context, AppCompatR.attr.colorPrimary), 35)), null, shape(context, Color.WHITE, 24))
        setOnClickListener { onClick() }
    }
}
