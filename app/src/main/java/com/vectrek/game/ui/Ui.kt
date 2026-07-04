package com.vectrek.game.ui

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Tiny programmatic-UI toolkit so the whole app can skip XML layouts and
 * share one retro-terminal style.
 */
object Ui {
    const val BG = Color.BLACK
    const val ACCENT = Palette.SELF
    const val DIM = Palette.TEXT_DIM

    fun dp(context: Context, v: Float): Int = (v * context.resources.displayMetrics.density).toInt()

    fun goFullscreen(activity: Activity) {
        val window = activity.window
        window.decorView.setBackgroundColor(BG)
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    fun column(context: Context, padding: Float = 24f): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            val p = dp(context, padding)
            setPadding(p, p, p, p)
        }

    fun row(context: Context): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

    fun scroller(context: Context, content: View): ScrollView =
        ScrollView(context).apply {
            setBackgroundColor(BG)
            isFillViewport = true
            addView(content)
        }

    fun title(context: Context, text: String, size: Float = 34f): TextView =
        TextView(context).apply {
            this.text = text
            textSize = size
            setTextColor(ACCENT)
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.28f
            gravity = Gravity.CENTER_HORIZONTAL
        }

    fun label(context: Context, text: String, size: Float = 12f, color: Int = DIM): TextView =
        TextView(context).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            typeface = Typeface.MONOSPACE
        }

    fun button(context: Context, text: String, accent: Int = ACCENT, onClick: () -> Unit): Button =
        Button(context).apply {
            this.text = text
            textSize = 14f
            setTextColor(accent)
            typeface = Typeface.MONOSPACE
            isAllCaps = true
            letterSpacing = 0.12f
            background = outline(context, accent)
            setPadding(dp(context, 16f), dp(context, 10f), dp(context, 16f), dp(context, 10f))
            setOnClickListener { onClick() }
        }

    fun smallButton(context: Context, text: String, accent: Int = ACCENT, onClick: () -> Unit): Button =
        button(context, text, accent, onClick).apply {
            textSize = 13f
            minWidth = dp(context, 44f)
            minimumWidth = dp(context, 44f)
            setPadding(dp(context, 8f), dp(context, 4f), dp(context, 8f), dp(context, 4f))
        }

    fun field(context: Context, hint: String, initial: String): EditText =
        EditText(context).apply {
            this.hint = hint
            setText(initial)
            textSize = 15f
            setTextColor(Color.WHITE)
            setHintTextColor(DIM)
            typeface = Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_TEXT
            background = outline(context, DIM)
            setPadding(dp(context, 12f), dp(context, 10f), dp(context, 12f), dp(context, 10f))
        }

    fun outline(context: Context, color: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(0xFF0A1218.toInt())
            setStroke(dp(context, 1.5f), color)
            cornerRadius = dp(context, 6f).toFloat()
        }

    fun space(context: Context, h: Float): View =
        View(context).apply {
            layoutParams = LinearLayout.LayoutParams(1, dp(context, h))
        }

    fun match(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
}
