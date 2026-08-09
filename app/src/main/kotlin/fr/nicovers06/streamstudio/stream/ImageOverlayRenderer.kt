package fr.nicovers06.streamstudio.stream

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Dessine une bitmap sur la surface d’un [com.pedro.encoder.input.gl.render.filters.object.SurfaceFilterRender].
 * Le contenu est toujours en crop/cover (remplit le cadre sans déformation) ;
 * le switch « Garder le ratio » ne concerne que le verrouillage du cadre à l’édition.
 */
class ImageOverlayRenderer(
    surfaceTexture: SurfaceTexture,
    private var bufferWidth: Int,
    private var bufferHeight: Int,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val surface = Surface(surfaceTexture)
    private val released = AtomicBoolean(false)
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FFFFFF
        textAlign = Paint.Align.CENTER
        textSize = 28f
    }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x332C3442
        style = Paint.Style.FILL
    }

    private var bitmap: Bitmap? = null
    private var enabled = false
    private var placeholderLabel = "Image"

    init {
        surfaceTexture.setDefaultBufferSize(bufferWidth, bufferHeight)
    }

    fun setBitmap(value: Bitmap?, label: String = "Image") {
        if (released.get()) return
        mainHandler.post {
            if (released.get()) return@post
            // Ne pas recycler : les bitmaps peuvent être partagées via le cache service.
            bitmap = value
            placeholderLabel = label
            redraw()
        }
    }

    fun update(enabled: Boolean, keepAspectRatio: Boolean = true) {
        // keepAspectRatio ignoré pour le dessin : toujours cover/crop (pas d’étirement).
        if (released.get()) return
        mainHandler.post {
            if (released.get()) return@post
            this.enabled = enabled
            redraw()
        }
    }

    fun resizeBuffer(width: Int, height: Int) {
        if (released.get()) return
        mainHandler.post {
            if (released.get()) return@post
            if (width == bufferWidth && height == bufferHeight) return@post
            bufferWidth = width.coerceAtLeast(2)
            bufferHeight = height.coerceAtLeast(2)
            runCatching {
                // SurfaceTexture buffer size is owned by the filter; caller updates ST too.
            }
            redraw()
        }
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        mainHandler.post {
            mainHandler.removeCallbacksAndMessages(null)
            bitmap = null
            runCatching { surface.release() }
        }
    }

    private fun redraw() {
        if (released.get() || !surface.isValid) return
        val canvas = runCatching { surface.lockCanvas(null) }.getOrNull() ?: return
        try {
            if (released.get()) return
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            if (!enabled) return
            val w = canvas.width.takeIf { it > 0 } ?: bufferWidth
            val h = canvas.height.takeIf { it > 0 } ?: bufferHeight
            val bmp = bitmap
            if (bmp == null || bmp.isRecycled) {
                canvas.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), 16f, 16f, framePaint)
                canvas.drawText(placeholderLabel, w / 2f, h / 2f, placeholderPaint)
                return
            }
            // Cover/crop : scale max pour remplir le cadre, centrage, débordement rogné (jamais d’étirement).
            val matrix = Matrix()
            val scale = maxOf(w.toFloat() / bmp.width, h.toFloat() / bmp.height)
            val dx = (w - bmp.width * scale) / 2f
            val dy = (h - bmp.height * scale) / 2f
            matrix.setScale(scale, scale)
            matrix.postTranslate(dx, dy)
            canvas.drawBitmap(bmp, matrix, bitmapPaint)
        } catch (_: RuntimeException) {
            // Le filtre GL peut remplacer sa SurfaceTexture pendant ce dessin.
        } finally {
            runCatching { surface.unlockCanvasAndPost(canvas) }
        }
    }
}
