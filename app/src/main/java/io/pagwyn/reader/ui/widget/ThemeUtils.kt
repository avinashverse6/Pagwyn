package io.pagwyn.reader.ui.widget

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.util.TypedValue

/** Resolves a semantic theme attribute to a concrete colour. */
fun Context.colorFromAttr(attrRes: Int, fallback: Int = 0): Int {
    val value = TypedValue()
    return if (theme.resolveAttribute(attrRes, value, true)) {
        if (value.resourceId != 0) androidx.core.content.ContextCompat.getColor(this, value.resourceId) else value.data
    } else {
        fallback
    }
}

fun Context.colorStateListFromAttr(attrRes: Int, fallback: Int = 0): ColorStateList =
    ColorStateList.valueOf(colorFromAttr(attrRes, fallback))

fun Context.drawableFromAttr(attrRes: Int): Drawable? {
    val value = TypedValue()
    return if (theme.resolveAttribute(attrRes, value, true)) {
        androidx.core.content.ContextCompat.getDrawable(this, value.resourceId)
    } else {
        null
    }
}

/** Sets a click-safe scale feedback without relying on animations in hot paths. */
fun android.view.View.setAlphaEnabled(enabled: Boolean) {
    alpha = if (enabled) 1f else 0.38f
    isEnabled = enabled
}

/** Converts dp to pixels for views created in code. */
fun android.content.Context.dp(value: Float): Float = value * resources.displayMetrics.density

/**
 * A fully rounded (pill) background in a single colour.
 *
 * Used by code-created buttons so they match the themed pill shapes used in
 * layouts, without a drawable per colour.
 */
fun pillBackground(context: android.content.Context, color: Int): android.graphics.drawable.GradientDrawable =
    android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
        cornerRadius = context.dp(999f)
        setColor(color)
    }
