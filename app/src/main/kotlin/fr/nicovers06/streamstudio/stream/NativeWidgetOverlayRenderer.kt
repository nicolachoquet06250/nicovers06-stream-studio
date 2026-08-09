package fr.nicovers06.streamstudio.stream

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SurfaceTexture
import android.os.Handler
import android.text.TextPaint
import android.text.TextUtils
import android.view.Surface
import fr.nicovers06.streamstudio.model.NativeWidgetComponent
import fr.nicovers06.streamstudio.model.PollKind
import fr.nicovers06.streamstudio.model.ShapeKind
import fr.nicovers06.streamstudio.model.TextKind
import fr.nicovers06.streamstudio.model.TimerMode
import fr.nicovers06.streamstudio.model.WidgetType
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/** Rend les widgets graphiques avec Android Canvas, sans HTML ni WebView. */
class NativeWidgetOverlayRenderer(
    private val surfaceTexture: SurfaceTexture,
    private var bufferWidth: Int,
    private var bufferHeight: Int,
    private val renderHandler: Handler,
) {
    private val surface = Surface(surfaceTexture)
    private val released = AtomicBoolean(false)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private var component: NativeWidgetComponent? = null
    private var frameScheduled = false

    private val animationFrame = object : Runnable {
        override fun run() {
            frameScheduled = false
            if (released.get()) return
            redraw()
            scheduleAnimationIfNeeded()
        }
    }

    init {
        surfaceTexture.setDefaultBufferSize(bufferWidth.coerceAtLeast(2), bufferHeight.coerceAtLeast(2))
    }

    fun update(value: NativeWidgetComponent) {
        if (released.get()) return
        renderHandler.post {
            if (released.get()) return@post
            component = value
            redraw()
            scheduleAnimationIfNeeded()
        }
    }

    fun resizeBuffer(width: Int, height: Int) {
        if (released.get()) return
        renderHandler.post {
            if (released.get()) return@post
            val nextWidth = width.coerceAtLeast(2)
            val nextHeight = height.coerceAtLeast(2)
            if (nextWidth == bufferWidth && nextHeight == bufferHeight) return@post
            bufferWidth = nextWidth
            bufferHeight = nextHeight
            runCatching { surfaceTexture.setDefaultBufferSize(nextWidth, nextHeight) }
            redraw()
            scheduleAnimationIfNeeded()
        }
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        renderHandler.post {
            renderHandler.removeCallbacks(animationFrame)
            frameScheduled = false
            component = null
            runCatching { surface.release() }
        }
    }

    private fun scheduleAnimationIfNeeded() {
        if (frameScheduled || !needsAnimation(component, System.currentTimeMillis())) return
        frameScheduled = true
        renderHandler.postDelayed(animationFrame, FRAME_DELAY_MS)
    }

    private fun needsAnimation(widget: NativeWidgetComponent?, now: Long): Boolean {
        if (widget == null || !widget.enabled) return false
        return when (widget.type) {
            WidgetType.TICKER -> true
            WidgetType.TIMER -> widget.timerRunning &&
                (widget.timerMode == TimerMode.STOPWATCH || widget.displayedTimerMs(now) > 0L)
            WidgetType.ALERT -> widget.alertTriggeredAtEpochMs > 0L &&
                now - widget.alertTriggeredAtEpochMs <= widget.alertDurationSeconds * 1_000L
            else -> false
        }
    }

    private fun redraw() {
        if (released.get() || !surface.isValid) return
        val canvas = runCatching { surface.lockCanvas(null) }.getOrNull() ?: return
        try {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            val widget = component ?: return
            if (!widget.enabled) return
            when (widget.type) {
                WidgetType.TIMER -> drawTimer(canvas, widget)
                WidgetType.SHAPE -> drawShape(canvas, widget)
                WidgetType.BACKGROUND -> drawBackground(canvas, widget)
                WidgetType.TICKER -> drawTicker(canvas, widget)
                WidgetType.ALERT -> drawAlert(canvas, widget)
                WidgetType.POLL -> drawPoll(canvas, widget)
                WidgetType.TEXT -> drawTextWidget(canvas, widget)
                else -> Unit
            }
        } catch (_: RuntimeException) {
            // Le filtre GL peut remplacer sa SurfaceTexture pendant le dessin.
        } finally {
            runCatching { surface.unlockCanvasAndPost(canvas) }
        }
    }

    private fun drawBackground(canvas: Canvas, widget: NativeWidgetComponent) {
        fillPaint.shader = if (widget.gradientEnabled) {
            LinearGradient(
                0f,
                0f,
                canvas.width.toFloat(),
                canvas.height.toFloat(),
                widget.backgroundColor,
                widget.accentColor,
                Shader.TileMode.CLAMP,
            )
        } else {
            null
        }
        fillPaint.color = widget.backgroundColor
        canvas.drawRect(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(), fillPaint)
        fillPaint.shader = null
    }

    private fun drawShape(canvas: Canvas, widget: NativeWidgetComponent) {
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()
        val inset = minOf(w, h) * 0.04f
        val rect = RectF(inset, inset, w - inset, h - inset)
        fillPaint.color = widget.backgroundColor
        fillPaint.style = Paint.Style.FILL
        when (widget.shapeKind) {
            ShapeKind.RECTANGLE -> canvas.drawRoundRect(rect, minOf(w, h) * 0.12f, minOf(w, h) * 0.12f, fillPaint)
            ShapeKind.ELLIPSE -> canvas.drawOval(rect, fillPaint)
            ShapeKind.LINE -> {
                strokePaint.color = widget.backgroundColor
                strokePaint.strokeWidth = (minOf(w, h) * 0.14f).coerceAtLeast(3f)
                strokePaint.strokeCap = Paint.Cap.ROUND
                canvas.drawLine(inset, h / 2f, w - inset, h / 2f, strokePaint)
            }
        }
    }

    private fun drawTimer(canvas: Canvas, widget: NativeWidgetComponent) {
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()
        val radius = minOf(w, h) * 0.10f
        fillPaint.color = widget.backgroundColor
        canvas.drawRoundRect(RectF(0f, 0f, w, h), radius, radius, fillPaint)
        strokePaint.color = widget.accentColor
        strokePaint.strokeWidth = (h * 0.035f).coerceAtLeast(2f)
        canvas.drawRoundRect(
            RectF(strokePaint.strokeWidth, strokePaint.strokeWidth, w - strokePaint.strokeWidth, h - strokePaint.strokeWidth),
            radius,
            radius,
            strokePaint,
        )
        textPaint.color = widget.foregroundColor
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.isFakeBoldText = true
        textPaint.textSize = (h * 0.50f).coerceAtMost(w * 0.25f)
        val baseline = centeredBaseline(h, textPaint)
        canvas.drawText(widget.formattedTimer(), w / 2f, baseline, textPaint)
    }

    private fun drawTicker(canvas: Canvas, widget: NativeWidgetComponent) {
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()
        fillPaint.color = widget.backgroundColor
        canvas.drawRect(0f, 0f, w, h, fillPaint)
        fillPaint.color = widget.accentColor
        canvas.drawRect(0f, 0f, (h * 0.10f).coerceAtLeast(4f), h, fillPaint)
        textPaint.color = widget.foregroundColor
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.isFakeBoldText = true
        textPaint.textSize = h * 0.44f
        val text = widget.title.ifBlank { "Bandeau défilant" }
        val textWidth = textPaint.measureText(text)
        val distance = w + textWidth + h
        val travelled = ((System.currentTimeMillis() / 1_000.0) * w * widget.tickerSpeed).toFloat()
        val x = w - (travelled % distance)
        canvas.save()
        canvas.clipRect(h * 0.10f, 0f, w, h)
        canvas.drawText(text, x, centeredBaseline(h, textPaint), textPaint)
        canvas.restore()
    }

    private fun drawAlert(canvas: Canvas, widget: NativeWidgetComponent) {
        val now = System.currentTimeMillis()
        val elapsed = now - widget.alertTriggeredAtEpochMs
        val durationMs = widget.alertDurationSeconds.coerceIn(1, 60) * 1_000L
        if (widget.alertTriggeredAtEpochMs <= 0L || elapsed !in 0..durationMs) return
        val enter = (elapsed / 260f).coerceIn(0f, 1f)
        val remaining = durationMs - elapsed
        val exitAlpha = (remaining / 420f).coerceIn(0f, 1f)
        val alpha = (255 * minOf(enter, exitAlpha)).roundToInt().coerceIn(0, 255)
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()
        canvas.saveLayerAlpha(RectF(0f, 0f, w, h), alpha)
        canvas.translate(0f, (1f - enter) * -h * 0.55f)
        fillPaint.color = widget.backgroundColor
        canvas.drawRoundRect(RectF(2f, 2f, w - 2f, h - 2f), h * 0.16f, h * 0.16f, fillPaint)
        fillPaint.color = widget.accentColor
        canvas.drawCircle(h * 0.28f, h * 0.50f, h * 0.15f, fillPaint)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = widget.foregroundColor
        textPaint.isFakeBoldText = true
        textPaint.textSize = h * 0.17f
        canvas.drawText("★", h * 0.28f, centeredBaseline(h, textPaint), textPaint)
        val textLeft = h * 0.52f
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = h * 0.24f
        textPaint.isFakeBoldText = true
        val title = ellipsize(widget.title, textPaint, w - textLeft - h * 0.15f)
        canvas.drawText(title, textLeft, h * 0.46f, textPaint)
        textPaint.textSize = h * 0.17f
        textPaint.isFakeBoldText = false
        textPaint.color = withAlpha(widget.foregroundColor, 205)
        val subtitle = ellipsize(widget.subtitle, textPaint, w - textLeft - h * 0.15f)
        canvas.drawText(subtitle, textLeft, h * 0.72f, textPaint)
        canvas.restore()
    }

    private fun drawPoll(canvas: Canvas, widget: NativeWidgetComponent) {
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()
        fillPaint.color = widget.backgroundColor
        canvas.drawRoundRect(RectF(0f, 0f, w, h), h * 0.055f, h * 0.055f, fillPaint)
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.color = widget.accentColor
        textPaint.textSize = h * 0.075f
        textPaint.isFakeBoldText = true
        val kind = if (widget.pollKind == PollKind.POLL) "SONDAGE" else "QUESTION"
        canvas.drawText(kind, w * 0.07f, h * 0.13f, textPaint)
        textPaint.color = widget.foregroundColor
        textPaint.textSize = h * 0.105f
        val title = ellipsize(widget.title, textPaint, w * 0.86f)
        canvas.drawText(title, w * 0.07f, h * 0.27f, textPaint)

        val options = widget.pollOptions.take(4)
        val total = options.sumOf { it.value }.coerceAtLeast(1)
        val startY = h * 0.36f
        val rowHeight = (h * 0.55f / options.size.coerceAtLeast(1)).coerceAtMost(h * 0.16f)
        options.forEachIndexed { index, option ->
            val ratio = option.value.toFloat() / total
            val top = startY + index * rowHeight
            val bar = RectF(w * 0.07f, top, w * 0.93f, top + rowHeight * 0.62f)
            fillPaint.color = withAlpha(widget.foregroundColor, 28)
            canvas.drawRoundRect(bar, rowHeight * 0.20f, rowHeight * 0.20f, fillPaint)
            fillPaint.color = widget.accentColor
            canvas.drawRoundRect(
                RectF(bar.left, bar.top, bar.left + bar.width() * ratio.coerceIn(0f, 1f), bar.bottom),
                rowHeight * 0.20f,
                rowHeight * 0.20f,
                fillPaint,
            )
            textPaint.color = widget.foregroundColor
            textPaint.textSize = rowHeight * 0.30f
            textPaint.isFakeBoldText = false
            val label = ellipsize(option.label, textPaint, bar.width() * 0.68f)
            canvas.drawText(label, bar.left + rowHeight * 0.16f, bar.centerY() + textPaint.textSize * 0.35f, textPaint)
            textPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(
                String.format(Locale.ROOT, "%d %%", (ratio * 100).roundToInt()),
                bar.right - rowHeight * 0.16f,
                bar.centerY() + textPaint.textSize * 0.35f,
                textPaint,
            )
            textPaint.textAlign = Paint.Align.LEFT
        }
    }

    private fun drawTextWidget(canvas: Canvas, widget: NativeWidgetComponent) {
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()
        val left = w * 0.055f
        if (widget.textKind == TextKind.LOWER_THIRD) {
            fillPaint.color = widget.backgroundColor
            canvas.drawRoundRect(RectF(0f, h * 0.08f, w, h * 0.92f), h * 0.10f, h * 0.10f, fillPaint)
            fillPaint.color = widget.accentColor
            canvas.drawRoundRect(RectF(0f, h * 0.08f, w * 0.025f, h * 0.92f), h * 0.02f, h * 0.02f, fillPaint)
        }
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.color = widget.foregroundColor
        textPaint.isFakeBoldText = true
        textPaint.setShadowLayer(h * 0.035f, 0f, h * 0.02f, 0x99000000.toInt())
        textPaint.textSize = if (widget.subtitle.isBlank()) h * 0.42f else h * 0.31f
        val title = ellipsize(widget.title, textPaint, w - left * 2f)
        val titleY = if (widget.subtitle.isBlank()) centeredBaseline(h, textPaint) else h * 0.48f
        canvas.drawText(title, left, titleY, textPaint)
        if (widget.subtitle.isNotBlank()) {
            textPaint.clearShadowLayer()
            textPaint.isFakeBoldText = false
            textPaint.color = withAlpha(widget.foregroundColor, 210)
            textPaint.textSize = h * 0.20f
            val subtitle = ellipsize(widget.subtitle, textPaint, w - left * 2f)
            canvas.drawText(subtitle, left, h * 0.76f, textPaint)
        }
        textPaint.clearShadowLayer()
    }

    private fun centeredBaseline(height: Float, paint: Paint): Float {
        val metrics = paint.fontMetrics
        return height / 2f - (metrics.ascent + metrics.descent) / 2f
    }

    private fun ellipsize(value: String, paint: TextPaint, maxWidth: Float): String =
        TextUtils.ellipsize(value.ifBlank { " " }, paint, maxWidth.coerceAtLeast(1f), TextUtils.TruncateAt.END)
            .toString()

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    companion object {
        private const val FRAME_DELAY_MS = 33L
    }
}
