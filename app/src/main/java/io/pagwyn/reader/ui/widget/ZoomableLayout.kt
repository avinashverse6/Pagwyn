package io.pagwyn.reader.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Hosts the page content and owns zoom + pan.
 *
 * Why a custom container instead of a zoom library:
 *
 *  - **No re-layout.** Zoom is a canvas transform, so pinch never triggers
 *    `measure`/`layout` on the page views. This is what keeps zooming smooth on
 *    entry-level hardware.
 *  - **Single gesture owner.** Pinch, pan, double-tap, tap and long-press are
 *    resolved in one place, so parent scrolling (continuous mode) and child
 *    zooming never fight: at scale 1 horizontal/vertical drags are handed to the
 *    parent, above 1 they pan the page.
 *  - **Bounded.** Scale is clamped and translation is clamped to the content
 *    edges, so the user can never lose the page off-screen.
 */
class ZoomableLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    interface Listener {
        fun onTap()
        fun onDoubleTap()
        fun onLongPress(x: Float, y: Float)
        /** Called whenever the user zooms; the reader re-renders at the new scale. */
        fun onZoomChanged(scale: Float)
        /** Vertical drag that should scroll the surrounding reader (continuous mode). */
        fun onScrollBy(dy: Float): Boolean
    }

    var listener: Listener? = null

    /** Content scale, 1f = fit. */
    private var scaleFactor = 1f
    private var translateX = 0f
    private var translateY = 0f

    /** Rejects absurd pinches early (e.g. two fingers pinching in a pocket). */
    private var minScale = 1f
    private var maxScale = 8f

    val currentScale: Float get() = scaleFactor

    /** True while a pinch or pan gesture is in progress. */
    var isGestureActive: Boolean = false
        private set

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                isGestureActive = true
                scaleFactorAtGestureStart = scaleFactor
                focusX = detector.focusX
                focusY = detector.focusY
                return !isAnimating()
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val target = (scaleFactorAtGestureStart * detector.scaleFactor)
                    .coerceIn(minScale, maxScale)
                // Keep the focal point under the user's fingers while scaling.
                val ratio = target / scaleFactor
                translateX = focusX - (focusX - translateX) * ratio
                translateY = focusY - (focusY - translateY) * ratio
                scaleFactor = target
                clampTranslation()
                invalidate()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                isGestureActive = false
                listener?.onZoomChanged(scaleFactor)
            }
        },
    )

    private var scaleFactorAtGestureStart = 1f
    private var focusX = 0f
    private var focusY = 0f

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                listener?.onTap()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                toggleZoom(e.x, e.y)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                listener?.onLongPress(e.x, e.y)
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                if (scaleDetector.isInProgress) return false
                if (scaleFactor > 1.01f) {
                    translateX -= distanceX
                    translateY -= distanceY
                    val before = translateX to translateY
                    clampTranslation()
                    val consumed = before.first != translateX || before.second != translateY
                    invalidate()
                    return consumed
                }
                // Fully zoomed out: let the reader scroll vertically.
                return listener?.onScrollBy(distanceY) ?: false
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean = scaleFactor > 1.01f
        },
    )

    init {
        // Drawing children with our transform keeps scrolling allocation-free.
        setWillNotDraw(true)
        isChildrenDrawingOrderEnabled = false
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    // ------------------------------------------------------------------ transform

    override fun dispatchDraw(canvas: Canvas) {
        val save = canvas.save()
        canvas.translate(translateX, translateY)
        canvas.scale(scaleFactor, scaleFactor, 0f, 0f)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(save)
    }

    /** Resets zoom to fit. */
    fun resetZoom(notify: Boolean = true) {
        scaleFactor = 1f
        translateX = 0f
        translateY = 0f
        invalidate()
        if (notify) listener?.onZoomChanged(1f)
    }

    /**
     * Applies an absolute zoom. When [anchorX]/[anchorY] are supplied (0..1 of the
     * view) that point stays fixed, which is what users expect from buttons.
     */
    fun setZoom(scale: Float, anchorX: Float = 0.5f, anchorY: Float = 0.5f, notify: Boolean = true) {
        val target = scale.coerceIn(minScale, maxScale)
        if (abs(target - scaleFactor) < 0.001f) return
        val fx = width * anchorX
        val fy = height * anchorY
        val ratio = target / scaleFactor
        translateX = fx - (fx - translateX) * ratio
        translateY = fy - (fy - translateY) * ratio
        scaleFactor = target
        clampTranslation()
        invalidate()
        if (notify) listener?.onZoomChanged(scaleFactor)
    }

    fun zoomIn(notify: Boolean = true) = setZoom(nextStep(scaleFactor), notify = notify)

    fun zoomOut(notify: Boolean = true) = setZoom(previousStep(scaleFactor), notify = notify)

    private fun nextStep(current: Float): Float = when {
        current < 1f -> 1f
        current < 1.5f -> 1.5f
        current < 2f -> 2f
        current < 3f -> 3f
        current < 4f -> 4f
        else -> min(4f, maxScale)
    }

    private fun previousStep(current: Float): Float = when {
        current > 4f -> 4f
        current > 3f -> 3f
        current > 2f -> 2f
        current > 1.5f -> 1.5f
        current > 1.01f -> 1f
        else -> 1f
    }

    private fun toggleZoom(x: Float, y: Float) {
        if (scaleFactor > 1.01f) {
            animateTo(1f, 0f, 0f)
        } else {
            val target = 2.4f.coerceAtMost(maxScale)
            val ratio = target
            val tx = x - x * ratio
            val ty = y - y * ratio
            animateTo(target, tx, ty)
            listener?.onDoubleTap()
        }
    }

    private var animator: android.animation.ValueAnimator? = null

    private fun isAnimating(): Boolean = animator?.isRunning == true

    private fun animateTo(targetScale: Float, targetX: Float, targetY: Float) {
        animator?.cancel()
        val startScale = scaleFactor
        val startX = translateX
        val startY = translateY
        val clamped = clampTranslationFor(targetScale, targetX, targetY)
        val valueAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 180
            interpolator = android.view.animation.DecelerateInterpolator(1.6f)
            addUpdateListener { animation ->
                val t = animation.animatedFraction
                scaleFactor = startScale + (targetScale - startScale) * t
                translateX = startX + (clamped.first - startX) * t
                translateY = startY + (clamped.second - startY) * t
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    scaleFactor = targetScale
                    translateX = clamped.first
                    translateY = clamped.second
                    invalidate()
                    listener?.onZoomChanged(scaleFactor)
                }
            })
        }
        animator = valueAnimator
        valueAnimator.start()
    }

    // ------------------------------------------------------------------ clamping

    private fun clampTranslation() {
        val (x, y) = clampTranslationFor(scaleFactor, translateX, translateY)
        translateX = x
        translateY = y
    }

    /**
     * Keeps the scaled content covering the viewport.
     *
     * Scaling happens around the top-left corner, so the valid translation range
     * for a scale `s` is `[width - width*s, 0]` (the same for Y). When the content
     * is smaller than the viewport that range collapses to 0 and the page stays
     * centred by the layout instead.
     */
    private fun clampTranslationFor(scale: Float, tx: Float, ty: Float): Pair<Float, Float> {
        if (scale <= 1.001f) return 0f to 0f
        val minX = width - width * scale
        val minY = height - height * scale
        return tx.coerceIn(min(minX, 0f), 0f) to ty.coerceIn(min(minY, 0f), 0f)
    }

    // ------------------------------------------------------------------ touch

    override fun onTouchEvent(event: MotionEvent): Boolean {
        var handled = scaleDetector.onTouchEvent(event)
        handled = gestureDetector.onTouchEvent(event) || handled
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                animator?.cancel()
                isGestureActive = false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isGestureActive = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        if (scaleDetector.isInProgress || scaleFactor > 1.01f) {
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        return handled || super.onTouchEvent(event)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clampTranslation()
    }

    /** Pixel width the page is drawn at, used to pick the render resolution. */
    fun renderedPageWidthPx(): Int {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child is PageImageView) {
                val rect = child.contentRect
                if (rect.width() > 0) return (rect.width() * scaleFactor).roundToInt()
            }
        }
        return (width * scaleFactor).roundToInt()
    }
}
