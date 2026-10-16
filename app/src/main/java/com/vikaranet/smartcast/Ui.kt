package com.vikaranet.smartcast

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Small helpers so the app needs no extra libraries. */
object Ui {
    const val BG = 0xFF0E1116.toInt()
    const val CARD = 0xFF1A1F27.toInt()
    const val CARD_BORDER = 0xFF2A313C.toInt()
    const val ACCENT = 0xFF00B3A4.toInt()
    const val ACCENT_DARK = 0xFF00897D.toInt()
    const val DANGER = 0xFFE5534B.toInt()
    const val TEXT = 0xFFF2F4F7.toInt()
    const val MUTED = 0xFF9AA4B2.toInt()

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    fun rounded(ctx: Context, color: Int, radiusDp: Int = 14, stroke: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(ctx, radiusDp).toFloat()
            setColor(color)
            if (stroke != 0) setStroke(dp(ctx, 1), stroke)
        }

    fun text(ctx: Context, s: String, sizeSp: Float = 15f, color: Int = TEXT, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            text = s
            textSize = sizeSp
            setTextColor(color)
            if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setLineSpacing(0f, 1.15f)
        }

    fun card(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(ctx, CARD, 18, CARD_BORDER)
        val p = dp(ctx, 16)
        setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(ctx, 14) }
    }

    fun button(ctx: Context, label: String, color: Int = ACCENT, onClick: (View) -> Unit): Button =
        Button(ctx).apply {
            text = label
            isAllCaps = false
            textSize = 16f
            setTextColor(Color.WHITE)
            background = pressable(ctx, color)
            stateListAnimator = null
            minHeight = dp(ctx, 50)
            setOnClickListener(onClick)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(ctx, 10) }
        }

    /** Background that also shows D-pad focus on TVs. */
    fun pressable(ctx: Context, color: Int): StateListDrawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), rounded(ctx, darken(color)))
        addState(intArrayOf(android.R.attr.state_focused), rounded(ctx, color, 14, Color.WHITE).apply { setStroke(dp(ctx, 3), Color.WHITE) })
        addState(intArrayOf(), rounded(ctx, color))
    }

    fun darken(c: Int): Int = Color.rgb((Color.red(c) * 0.8).toInt(), (Color.green(c) * 0.8).toInt(), (Color.blue(c) * 0.8).toInt())

    /** A row of options where exactly one is selected. */
    class Segmented(ctx: Context, labels: List<String>, private val onSelect: (Int) -> Unit) : LinearLayout(ctx) {
        private val buttons = ArrayList<TextView>()
        var selected = 0
            private set

        init {
            orientation = HORIZONTAL
            background = rounded(ctx, BG, 12, CARD_BORDER)
            val p = dp(ctx, 3)
            setPadding(p, p, p, p)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(ctx, 8) }
            labels.forEachIndexed { i, label ->
                val b = TextView(ctx).apply {
                    text = label
                    gravity = Gravity.CENTER
                    textSize = 14f
                    isFocusable = true
                    isClickable = true
                    val v = dp(ctx, 10)
                    setPadding(0, v, 0, v)
                    setOnClickListener { select(i); onSelect(i) }
                }
                addView(b, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                buttons.add(b)
            }
            select(0)
        }

        fun select(i: Int) {
            selected = i
            buttons.forEachIndexed { idx, b ->
                val on = idx == i
                b.background = if (on) rounded(context, ACCENT, 10) else null
                b.setTextColor(if (on) Color.WHITE else MUTED)
                b.typeface = Typeface.create(Typeface.DEFAULT, if (on) Typeface.BOLD else Typeface.NORMAL)
            }
        }
    }
}
