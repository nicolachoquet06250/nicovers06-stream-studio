package fr.nicovers06.streamstudio.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
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
        color = 0xFF8B5CF6.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x198B5CF6 }
    private val labelBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE66D3FDB.toInt() }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 11f * resources.displayMetrics.scaledDensity
        isFakeBoldText = true
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val drawingBounds = RectF()

    private var normalizedRect = NormalizedRect(0f, 0f, 0.3f, 0.3f)
    private var componentLabel = "Composant"
    private var onBoundsChanged: ((NormalizedRect) -> Unit)? = null
    private var resizeFromTopRight = false
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var resizing = false

    fun bind(
        rect: NormalizedRect,
        visible: Boolean,
        label: String,
        resizeFromTopRight: Boolean = false,
        listener: (NormalizedRect) -> Unit,
    ) {
        normalizedRect = rect.constrained()
        componentLabel = label
        this.resizeFromTopRight = resizeFromTopRight
        onBoundsChanged = listener
        visibility = if (visible) VISIBLE else GONE
        post(::applyLayout)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawingBounds.set(1f, 1f, width - 1f, height - 1f)
        canvas.drawRoundRect(drawingBounds, 8f * density, 8f * density, fillPaint)
        canvas.drawRoundRect(drawingBounds, 8f * density, 8f * density, borderPaint)

        val textWidth = labelPaint.measureText(componentLabel)
        val labelHeight = 24f * density
        canvas.drawRoundRect(0f, 0f, textWidth + 16f * density, labelHeight, 8f * density, 8f * density, labelBackgroundPaint)
        canvas.drawText(componentLabel, 8f * density, 16.5f * density, labelPaint)

        val handleSize = 14f * density
        val handleY = if (resizeFromTopRight) handleSize else height - handleSize
        canvas.drawCircle(width - handleSize, handleY, 5f * density, handlePaint)
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
                    if (resizeFromTopRight) {
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

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun applyLayout() {
        val parentView = parent as? FrameLayout ?: return
        if (parentView.width == 0 || parentView.height == 0) return
        layoutParams = (layoutParams as FrameLayout.LayoutParams).apply {
            width = (normalizedRect.width * parentView.width).roundToInt()
            height = (normalizedRect.height * parentView.height).roundToInt()
        }
        x = normalizedRect.x * parentView.width
        y = normalizedRect.y * parentView.height
    }
}
