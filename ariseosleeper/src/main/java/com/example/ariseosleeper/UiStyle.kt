package com.example.ariseosleeper

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
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
    fun color(context: Context, attr: Int) = MaterialColors.getColor(context, attr, "Arise O Sleeper UI")
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
            setOnClickListener { onClick() }
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

}
