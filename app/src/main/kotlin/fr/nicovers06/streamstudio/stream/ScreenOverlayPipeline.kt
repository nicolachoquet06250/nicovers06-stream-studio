package fr.nicovers06.streamstudio.stream

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MediaProjection → ImageReader (ratio capture 16:9 stream) → center-crop
 * vers la surface du filtre GL (ratio du cadre widget).
 * Le flux n’est jamais déformé : il est cropé pour remplir le cadre.
 *
 * Un token MediaProjection ne peut créer qu’un VirtualDisplay sur Android 14+ :
 * le display est conservé entre les changements de surface de filtre.
 */
class ScreenOverlayPipeline(
    context: Context,
    private val mediaProjection: MediaProjection,
    @Volatile private var outputSurface: Surface,
    private val captureWidth: Int,
    private val captureHeight: Int,
    private val onStopped: () -> Unit,
    private val onError: (String) -> Unit,
) {
    private val densityDpi = context.applicationContext.resources.displayMetrics.densityDpi
    private val released = AtomicBoolean(false)
    private val stopNotified = AtomicBoolean(false)
    private val callbackRegistered = AtomicBoolean(false)
    private val handlerThread = HandlerThread("screen-overlay").apply { start() }
    private val handler = Handler(handlerThread.looper)
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            notifyStopped()
            releaseInternal()
        }
    }

    fun start(): Boolean {
        if (released.get()) return false
        if (callbackRegistered.compareAndSet(false, true)) {
            mediaProjection.registerCallback(projectionCallback, handler)
        }
        return ensureCapture()
    }

    fun attachSurface(surface: Surface): Boolean {
        if (released.get() || stopNotified.get() || !surface.isValid) return false
        outputSurface = surface
        return if (virtualDisplay == null) ensureCapture() else true
    }

    fun detachSurface() {
        // Conserve le VirtualDisplay ; les frames sont ignorées si la surface est invalide.
    }

    fun release() {
        releaseInternal()
    }

    private fun ensureCapture(): Boolean {
        if (virtualDisplay != null && imageReader != null) return true
        return runCatching {
            val reader = ImageReader.newInstance(
                captureWidth,
                captureHeight,
                PixelFormat.RGBA_8888,
                2,
            )
            reader.setOnImageAvailableListener({ r -> onImage(r) }, handler)
            imageReader = reader
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "stream-studio-screen",
                captureWidth,
                captureHeight,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                handler,
            )
            true
        }.onFailure {
            onError("VirtualDisplay indisponible : ${it.message.orEmpty()}")
            releaseInternal()
        }.getOrDefault(false)
    }

    private fun onImage(reader: ImageReader) {
        if (released.get() || stopNotified.get()) return
        val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return
        try {
            val bitmap = imageToBitmap(image) ?: return
            try {
                drawCenterCrop(bitmap)
            } finally {
                bitmap.recycle()
            }
        } finally {
            image.close()
        }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        buffer.rewind()
        val pixelStride = plane.pixelStride.coerceAtLeast(1)
        val rowStride = plane.rowStride
        val rowPadding = (rowStride - pixelStride * image.width).coerceAtLeast(0)
        val fullWidth = image.width + rowPadding / pixelStride
        val bitmap = Bitmap.createBitmap(fullWidth, image.height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)
        return if (fullWidth == image.width) {
            bitmap
        } else {
            Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height).also { cropped ->
                if (cropped !== bitmap) bitmap.recycle()
            }
        }
    }

    private fun drawCenterCrop(source: Bitmap) {
        val surface = outputSurface
        if (!surface.isValid) return
        val canvas = runCatching { surface.lockCanvas(null) }.getOrNull() ?: return
        try {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            val srcRatio = source.width.toFloat() / source.height.coerceAtLeast(1)
            val dstRatio = canvas.width.toFloat() / canvas.height.coerceAtLeast(1)
            val dest = if (srcRatio > dstRatio) {
                val scaledW = canvas.height * srcRatio
                val left = (canvas.width - scaledW) / 2f
                RectF(left, 0f, left + scaledW, canvas.height.toFloat())
            } else {
                val scaledH = canvas.width / srcRatio
                val top = (canvas.height - scaledH) / 2f
                RectF(0f, top, canvas.width.toFloat(), top + scaledH)
            }
            canvas.drawBitmap(source, null, dest, paint)
        } catch (_: RuntimeException) {
            // La surface de destination peut changer pendant un redimensionnement DeX.
        } finally {
            runCatching { surface.unlockCanvasAndPost(canvas) }
        }
    }

    private fun notifyStopped() {
        if (stopNotified.compareAndSet(false, true)) {
            onStopped()
        }
    }

    private fun releaseInternal() {
        if (!released.compareAndSet(false, true)) {
            // Déjà libéré : s'assurer que le thread est arrêté.
            return
        }
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { imageReader?.setOnImageAvailableListener(null, null) }
        runCatching { imageReader?.close() }
        imageReader = null
        if (callbackRegistered.compareAndSet(true, false)) {
            runCatching { mediaProjection.unregisterCallback(projectionCallback) }
        }
        handlerThread.quitSafely()
    }
}
