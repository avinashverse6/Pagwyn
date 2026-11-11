package io.pagwyn.reader.ui.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import io.pagwyn.reader.R
import kotlin.math.min

/**
 * Draws a single PDF page.
 *
 * A page is either *fit* to the view (aspect preserved, whole page visible) or
 * *zoomed* by the parent [ZoomableLayout]. Scale and translation are applied in
 * the view's own canvas, which avoids re-layout during pinch gestures — the
 * single most important optimisation for smooth zooming on low-end devices.
 */
class PageImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val pagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#22000000")
        style = Paint.Style.FILL
    }

    /** Intrinsic page size in points; used for the placeholder while rendering. */
    var pageAspect: Float = A4_ASPECT
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
                invalidate()
            }
        }

    var rotation: Int = 0
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
                invalidate()
            }
        }

    /** True once a bitmap has been painted at least once. */
    var hasContent: Boolean = false
        private set

    private var bitmap: Bitmap? = null

    /** Extra vertical padding drawn around the page, in pixels. */
    var pageGap: Int = 0
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    private val bitmapRect = RectF()
    private val matrix = Matrix()

    /** The rect the bitmap occupies in view-local coordinates. */
    val contentRect: RectF get() = RectF(bitmapRect)

    fun setPageBitmap(next: Bitmap?) {
        if (bitmap === next) return
        bitmap = next
        hasContent = next != null
        invalidate()
    }

    fun currentBitmap(): Bitmap? = bitmap

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec)
        val parentHeight = MeasureSpec.getSize(heightMeasureSpec)
        val horizontalPadding = paddingLeft + paddingRight + pageGap * 2
        val usableWidth = (availableWidth - horizontalPadding).coerceAtLeast(1)

        val ratio = effectiveRatio()
        var pageWidth = usableWidth
        val mode = MeasureSpec.getMode(heightMeasureSpec)
        if (mode == MeasureSpec.EXACTLY || mode == MeasureSpec.AT_MOST) {
            val maxPageHeight = (parentHeight - paddingTop - paddingBottom - pageGap * 2).coerceAtLeast(1)
            // Never grow taller than the viewport: the page letterboxes instead.
            val heightLimitedWidth = (maxPageHeight * ratio).toInt().coerceAtLeast(1)
            if (heightLimitedWidth < pageWidth) pageWidth = heightLimitedWidth
        }
        val pageHeight = (pageWidth / ratio).toInt().coerceAtLeast(1)

        setMeasuredDimension(
            availableWidth,
            pageHeight + pageGap * 2 + paddingTop + paddingBottom,
        )
    }

    private fun effectiveRatio(): Float {
        val base = if (pageAspect > 0f) pageAspect else A4_ASPECT
        return if (rotation % 180 == 0) base else 1f / base
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = bitmap
        val left = paddingLeft + pageGap
        val top = paddingTop + pageGap
        val availableWidth = (width - paddingLeft - paddingRight - pageGap * 2).coerceAtLeast(1)
        val availableHeight = (height - paddingTop - paddingBottom - pageGap * 2).coerceAtLeast(1)

        // The slot is the area the (possibly rotated) page may occupy.
        val slotRatio = effectiveRatio()
        var slotWidth = availableWidth.toFloat()
        var slotHeight = slotWidth / slotRatio
        if (slotHeight > availableHeight) {
            slotHeight = availableHeight.toFloat()
            slotWidth = slotHeight * slotRatio
        }
        val slotLeft = left + (availableWidth - slotWidth) / 2f
        val slotTop = top + (availableHeight - slotHeight) / 2f
        val centerX = slotLeft + slotWidth / 2f
        val centerY = slotTop + slotHeight / 2f

        // Fit the unrotated page inside the slot, then rotate about its centre.
        val intrinsic = if (pageAspect > 0f) pageAspect else A4_ASPECT
        var pageWidth = slotWidth
        var pageHeight = pageWidth / intrinsic
        if (pageHeight > slotHeight) {
            pageHeight = slotHeight
            pageWidth = pageHeight * intrinsic
        }
        bitmapRect.set(
            centerX - pageWidth / 2f,
            centerY - pageHeight / 2f,
            centerX + pageWidth / 2f,
            centerY + pageHeight / 2f,
        )

        // A hairline edge keeps white pages visually separated from a white theme.
        canvas.drawRect(
            bitmapRect.left - dp(0.6f),
            bitmapRect.top - dp(0.6f),
            bitmapRect.right + dp(0.6f),
            bitmapRect.bottom + dp(0.6f),
            shadowPaint,
        )

        if (bmp == null || bmp.isRecycled) {
            canvas.drawRect(bitmapRect, pagePaint)
            return
        }

        val needsRotation = rotation % 360 != 0
        val save = if (needsRotation) canvas.save() else 0
        if (needsRotation) canvas.rotate(rotation.toFloat(), centerX, centerY)

        matrix.reset()
        matrix.setRectToRect(
            RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat()),
            bitmapRect,
            Matrix.ScaleToFit.FILL,
        )
        canvas.drawBitmap(bmp, matrix, bitmapPaint)

        if (needsRotation) canvas.restoreToCount(save)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        setWillNotDraw(false)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    companion object {
        /** Default A4 portrait ratio, used until the real page size is known. */
        const val A4_ASPECT = 0.707f

        fun aspectFor(widthPoints: Int, heightPoints: Int): Float =
            if (widthPoints > 0 && heightPoints > 0) widthPoints.toFloat() / heightPoints else A4_ASPECT

        fun scaledHeight(width: Int, ratio: Float): Int = (width / ratio).toInt().coerceAtLeast(1)

        fun minOf(a: Int, b: Int, c: Int): Int = min(a, min(b, c))
    }
}
