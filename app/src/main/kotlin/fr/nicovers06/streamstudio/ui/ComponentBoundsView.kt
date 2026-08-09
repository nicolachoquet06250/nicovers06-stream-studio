package fr.nicovers06.streamstudio.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import fr.nicovers06.streamstudio.model.NormalizedRect
import kotlin.math.roundToInt

class ComponentBoundsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9B8CFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        pathEffect = DashPathEffect(floatArrayOf(4f * density, 3f * density), 0f)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x0C6C5CE7 }
    private val labelBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF01A1737.toInt() }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFB9AFFF.toInt()
        textSize = 11f * density * resources.configuration.fontScale
        isFakeBoldText = true
    }
    private val gripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF816EF2.toInt() }
    private val handleBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE8172231.toInt() }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val drawingBounds = RectF()

    private var normalizedRect = NormalizedRect(0f, 0f, 0.3f, 0.3f)
    private var componentLabel = "Composant"
    private var onBoundsChanged: ((NormalizedRect) -> Unit)? = null
    private var resizeFromTopRight = false
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var resizing = false
    private val applyLayoutRunnable = Runnable(::applyLayout)
    private val parentLayoutChangeListener = View.OnLayoutChangeListener {
            _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
        if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
            applyLayout()
        }
    }
    /**
     * Ratio pixels largeur/hauteur à conserver pendant le resize.
     * `0f` = redimensionnement libre (le flux sera cropé côté composition).
     */
    private var lockedPixelAspect: Float = 0f

    fun bind(
        rect: NormalizedRect,
        visible: Boolean,
        label: String,
        resizeFromTopRight: Boolean = false,
        keepAspectRatio: Boolean = false,
        listener: (NormalizedRect) -> Unit,
    ) {
        normalizedRect = rect.constrained()
        componentLabel = label
        this.resizeFromTopRight = resizeFromTopRight
        onBoundsChanged = listener
        visibility = if (visible) VISIBLE else GONE
        lockedPixelAspect = if (keepAspectRatio) {
            val parentView = parent as? View
            val pw = (parentView?.width ?: 0).coerceAtLeast(1)
            val ph = (parentView?.height ?: 0).coerceAtLeast(1)
            normalizedRect.pixelAspect(pw, ph)
        } else {
            0f
        }
        removeCallbacks(applyLayoutRunnable)
        post(applyLayoutRunnable)
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        (parent as? View)?.addOnLayoutChangeListener(parentLayoutChangeListener)
        removeCallbacks(applyLayoutRunnable)
        post(applyLayoutRunnable)
    }

    override fun onDetachedFromWindow() {
        (parent as? View)?.removeOnLayoutChangeListener(parentLayoutChangeListener)
        removeCallbacks(applyLayoutRunnable)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawingBounds.set(1f, 1f, width - 1f, height - 1f)
        canvas.drawRoundRect(drawingBounds, 9f * density, 9f * density, fillPaint)
        canvas.drawRoundRect(drawingBounds, 9f * density, 9f * density, borderPaint)

        val textWidth = labelPaint.measureText(componentLabel)
        val labelHeight = 28f * density
        val labelWidth = (textWidth + 34f * density).coerceAtMost(width - 36f * density)
        canvas.drawRoundRect(0f, 0f, labelWidth, labelHeight, 8f * density, 8f * density, labelBackgroundPaint)
        repeat(3) { row ->
            repeat(2) { column ->
                canvas.drawCircle(
                    (9f + column * 5f) * density,
                    (8.5f + row * 5f) * density,
                    1.35f * density,
                    gripPaint,
                )
            }
        }
        canvas.save()
        canvas.clipRect(24f * density, 0f, labelWidth - 5f * density, labelHeight)
        canvas.drawText(componentLabel, 24f * density, 19f * density, labelPaint)
        canvas.restore()

        val handleSize = 30f * density
        val handleLeft = width - handleSize - 2f * density
        val handleTop = if (resizeFromTopRight) 2f * density else height - handleSize - 2f * density
        canvas.drawRoundRect(
            handleLeft,
            handleTop,
            width - 2f * density,
            handleTop + handleSize,
            7f * density,
            7f * density,
            handleBackgroundPaint,
        )
        val inset = 8f * density
        canvas.drawLine(handleLeft + inset, handleTop + handleSize - inset, width - inset, handleTop + inset, handlePaint)
        canvas.drawLine(width - inset, handleTop + inset, width - 14f * density, handleTop + inset, handlePaint)
        canvas.drawLine(width - inset, handleTop + inset, width - inset, handleTop + 14f * density, handlePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val parentView = parent as? View ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastRawX = event.rawX
                lastRawY = event.rawY
                val handleZone = 42f * density
                val inHandleY = if (resizeFromTopRight) {
                    event.y <= handleZone
                } else {
                    event.y >= height - handleZone
                }
                resizing = event.x >= width - handleZone && inHandleY
                parent.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (parentView.width == 0 || parentView.height == 0) return true
                val dx = (event.rawX - lastRawX) / parentView.width.toFloat()
                val dy = (event.rawY - lastRawY) / parentView.height.toFloat()
                normalizedRect = if (resizing) {
                    resizeRect(dx, dy, parentView.width, parentView.height)
                } else {
                    normalizedRect.copy(
                        x = normalizedRect.x + dx,
                        y = normalizedRect.y + dy,
                    ).constrained()
                }
                lastRawX = event.rawX
                lastRawY = event.rawY
                applyLayout()
                onBoundsChanged?.invoke(normalizedRect)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent.requestDisallowInterceptTouchEvent(false)
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun resizeRect(dx: Float, dy: Float, parentW: Int, parentH: Int): NormalizedRect {
        val aspect = lockedPixelAspect
        if (aspect <= 0f) {
            return if (resizeFromTopRight) {
                normalizedRect.copy(
                    y = normalizedRect.y + dy,
                    width = normalizedRect.width + dx,
                    height = normalizedRect.height - dy,
                ).constrained()
            } else {
                normalizedRect.copy(
                    width = normalizedRect.width + dx,
                    height = normalizedRect.height + dy,
                ).constrained()
            }
        }

        // Conserves le ratio pixels : nh = nw * parentW / (aspect * parentH)
        val heightFromWidth: (Float) -> Float = { nw ->
            (nw * parentW.toFloat()) / (aspect * parentH.toFloat())
        }

        return if (resizeFromTopRight) {
            val newWidth = (normalizedRect.width + dx).coerceAtLeast(0.12f)
            val newHeight = heightFromWidth(newWidth).coerceAtLeast(0.12f)
            val bottom = normalizedRect.y + normalizedRect.height
            normalizedRect.copy(
                y = bottom - newHeight,
                width = newWidth,
                height = newHeight,
            ).constrained()
        } else {
            val newWidth = (normalizedRect.width + dx).coerceAtLeast(0.12f)
            val newHeight = heightFromWidth(newWidth).coerceAtLeast(0.12f)
            normalizedRect.copy(
                width = newWidth,
                height = newHeight,
            ).constrained()
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun applyLayout() {
        val parentView = parent as? FrameLayout ?: return
        if (parentView.width == 0 || parentView.height == 0) return
        val params = layoutParams as? FrameLayout.LayoutParams ?: return
        val targetWidth = (normalizedRect.width * parentView.width).roundToInt().coerceAtLeast(1)
        val targetHeight = (normalizedRect.height * parentView.height).roundToInt().coerceAtLeast(1)
        if (params.width != targetWidth || params.height != targetHeight) {
            params.width = targetWidth
            params.height = targetHeight
            layoutParams = params
        }
        val targetX = normalizedRect.x * parentView.width
        val targetY = normalizedRect.y * parentView.height
        if (x != targetX) x = targetX
        if (y != targetY) y = targetY
    }
}
