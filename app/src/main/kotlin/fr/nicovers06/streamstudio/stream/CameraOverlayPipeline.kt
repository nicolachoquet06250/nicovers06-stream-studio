package fr.nicovers06.streamstudio.stream

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import android.util.Size
import android.view.Display
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.SegmentationMask
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import fr.nicovers06.streamstudio.model.CameraFacing
import fr.nicovers06.streamstudio.platform.AndroidCapabilities
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/**
 * Captures CameraX analysis frames, optionally separates the person with ML Kit and
 * draws the recomposed image into the SurfaceFilterRender input surface.
 */
class CameraOverlayPipeline(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val outputSurface: Surface,
    private val outputSurfaceSize: Size = Size(640, 360),
    private val onError: (String) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val analyzerExecutor = Executors.newSingleThreadExecutor()
    private val processing = AtomicBoolean(false)
    private val blurFallbackNotified = AtomicBoolean(false)
    private val released = AtomicBoolean(false)
    private val analyzerResourcesReleased = AtomicBoolean(false)
    @Suppress("DEPRECATION")
    private val renderScript = runCatching { RenderScript.create(context.applicationContext) }.getOrNull()
    @Suppress("DEPRECATION")
    private val blurScript = renderScript?.let { scriptContext ->
        runCatching {
            ScriptIntrinsicBlur.create(scriptContext, Element.U8_4(scriptContext)).apply {
                setRadius(14f)
            }
        }.getOrNull()
    }
    private val segmenter = runCatching {
        Segmentation.getClient(
            SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.STREAM_MODE)
                .build(),
        )
    }.getOrNull()

    @Volatile private var running = false
    @Volatile private var blurEnabled = true
    @Volatile private var facing = CameraFacing.FRONT
    @Volatile private var targetRotation = defaultDisplayRotation()
    private var provider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private val orientationListener = object : OrientationEventListener(context.applicationContext) {
        override fun onOrientationChanged(orientation: Int) {
            if (released.get() || orientation == ORIENTATION_UNKNOWN) return
            val rotation = when (orientation) {
                in 45 until 135 -> Surface.ROTATION_270
                in 135 until 225 -> Surface.ROTATION_180
                in 225 until 315 -> Surface.ROTATION_90
                else -> Surface.ROTATION_0
            }
            if (rotation == targetRotation) return
            targetRotation = rotation
            mainHandler.post {
                if (!released.get()) imageAnalysis?.targetRotation = rotation
            }
        }
    }

    fun start(backgroundBlur: Boolean, cameraFacing: CameraFacing) {
        if (released.get()) return
        if (running) {
            update(backgroundBlur, cameraFacing)
            return
        }
        blurEnabled = backgroundBlur
        facing = cameraFacing
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            onError("Autorisation caméra manquante")
            return
        }
        running = true
        mainHandler.post {
            if (!released.get() && orientationListener.canDetectOrientation()) orientationListener.enable()
        }
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            runCatching { future.get() }
                .onSuccess {
                    if (released.get() || !running) return@onSuccess
                    provider = it
                    bindCamera()
                }
                .onFailure {
                    if (released.get()) return@onFailure
                    running = false
                    mainHandler.post { orientationListener.disable() }
                    onError("Caméra indisponible : ${it.message.orEmpty()}")
                }
        }, ContextCompat.getMainExecutor(context))
    }

    fun update(backgroundBlur: Boolean, cameraFacing: CameraFacing) {
        if (released.get()) return
        val facingChanged = facing != cameraFacing
        blurEnabled = backgroundBlur
        facing = cameraFacing
        if (running && facingChanged) bindCamera()
    }

    fun stop() {
        if (released.get()) return
        running = false
        mainHandler.post {
            orientationListener.disable()
            imageAnalysis?.let { analysis ->
                analysis.clearAnalyzer()
                runCatching { provider?.unbind(analysis) }
            }
            imageAnalysis = null
        }
        clearSurface()
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        running = false
        mainHandler.post {
            orientationListener.disable()
            imageAnalysis?.let { analysis ->
                analysis.clearAnalyzer()
                runCatching { provider?.unbind(analysis) }
            }
            imageAnalysis = null
            provider = null
            runCatching { outputSurface.release() }
        }
        releaseAnalyzerResourcesWhenIdle()
    }

    private fun releaseAnalyzerResourcesWhenIdle(attempt: Int = 0) {
        if (analyzerResourcesReleased.get()) return
        runCatching {
            analyzerExecutor.execute {
                if (processing.get()) {
                    if (attempt < MAX_RELEASE_DRAIN_ATTEMPTS) {
                        mainHandler.postDelayed(
                            { releaseAnalyzerResourcesWhenIdle(attempt + 1) },
                            RELEASE_DRAIN_DELAY_MS,
                        )
                    }
                    return@execute
                }
                if (analyzerResourcesReleased.compareAndSet(false, true)) {
                    runCatching { segmenter?.close() }
                    @Suppress("DEPRECATION")
                    runCatching { blurScript?.destroy() }
                    @Suppress("DEPRECATION")
                    runCatching { renderScript?.destroy() }
                    analyzerExecutor.shutdown()
                }
            }
        }
    }

    private fun bindCamera() {
        if (!running || released.get()) return
        val cameraProvider = provider ?: return
        val selector = CameraSelector.Builder()
            .requireLensFacing(
                if (facing == CameraFacing.FRONT) {
                    CameraSelector.LENS_FACING_FRONT
                } else {
                    CameraSelector.LENS_FACING_BACK
                },
            )
            .build()
        val analysis = ImageAnalysis.Builder()
            .setTargetResolution(outputSurfaceSize)
            .setTargetRotation(targetRotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { it.setAnalyzer(analyzerExecutor, ::analyze) }
        imageAnalysis = analysis

        runCatching {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(lifecycleOwner, selector, analysis)
        }.onFailure {
            if (imageAnalysis === analysis) imageAnalysis = null
            if (!released.get()) onError("Impossible d’ouvrir cette caméra : ${it.message.orEmpty()}")
        }
    }

    private fun analyze(imageProxy: ImageProxy) {
        if (released.get() || !running || !processing.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }

        val frame = runCatching {
            imageProxy.toBitmap().transformFrame(
                rotationDegrees = imageProxy.imageInfo.rotationDegrees,
                mirror = facing == CameraFacing.FRONT,
            )
        }
            .getOrElse {
                imageProxy.close()
                processing.set(false)
                return
            }
        imageProxy.close()

        val activeSegmenter = segmenter
        if (!blurEnabled || activeSegmenter == null || renderScript == null || blurScript == null) {
            if (blurEnabled && blurFallbackNotified.compareAndSet(false, true)) {
                onError("Flou indisponible sur cet appareil : la caméra reste active sans flou")
            }
            render(frame)
            return
        }

        var handedToRenderer = false
        activeSegmenter.process(InputImage.fromBitmap(frame, 0))
            .addOnSuccessListener(analyzerExecutor) { mask ->
                handedToRenderer = true
                if (running && !released.get()) {
                    val output = runCatching { compositeBlur(frame, mask) }.getOrElse { frame }
                    render(output)
                } else {
                    frame.recycle()
                    processing.set(false)
                }
            }
            .addOnFailureListener(analyzerExecutor) {
                handedToRenderer = true
                if (running && !released.get()) render(frame) else {
                    frame.recycle()
                    processing.set(false)
                }
            }
            .addOnCompleteListener(analyzerExecutor) {
                if (!handedToRenderer) {
                    frame.recycle()
                    processing.set(false)
                }
            }
    }

    private fun Bitmap.transformFrame(rotationDegrees: Int, mirror: Boolean): Bitmap {
        if (rotationDegrees == 0 && !mirror && config == Bitmap.Config.ARGB_8888) return this
        val transformed = if (rotationDegrees != 0 || mirror) {
            val matrix = Matrix().apply {
                postRotate(rotationDegrees.toFloat())
                if (mirror) postScale(-1f, 1f)
            }
            Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
                .also { if (it !== this) recycle() }
        } else {
            this
        }
        if (transformed.config == Bitmap.Config.ARGB_8888) return transformed
        return transformed.copy(Bitmap.Config.ARGB_8888, false).also { transformed.recycle() }
    }

    private fun defaultDisplayRotation(): Int {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        return displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
    }

    private fun compositeBlur(foreground: Bitmap, mask: SegmentationMask): Bitmap {
        val width = foreground.width
        val height = foreground.height
        val blurred = createBlurredBitmap(foreground)
        val sharpPixels = IntArray(width * height)
        val blurredPixels = IntArray(width * height)
        val outputPixels = IntArray(width * height)
        foreground.getPixels(sharpPixels, 0, width, 0, 0, width, height)
        blurred.getPixels(blurredPixels, 0, width, 0, 0, width, height)

        val maskWidth = mask.width
        val maskHeight = mask.height
        val confidences = FloatArray(maskWidth * maskHeight)
        val buffer = mask.buffer.asFloatBuffer()
        buffer.rewind()
        buffer.get(confidences, 0, min(confidences.size, buffer.remaining()))

        for (y in 0 until height) {
            val maskY = min(maskHeight - 1, y * maskHeight / height)
            for (x in 0 until width) {
                val index = y * width + x
                val maskX = min(maskWidth - 1, x * maskWidth / width)
                var mix = ((confidences[maskY * maskWidth + maskX] - 0.25f) / 0.5f).coerceIn(0f, 1f)
                mix = mix * mix * (3f - 2f * mix)
                outputPixels[index] = blend(blurredPixels[index], sharpPixels[index], mix)
            }
        }

        return Bitmap.createBitmap(outputPixels, width, height, Bitmap.Config.ARGB_8888).also {
            foreground.recycle()
            blurred.recycle()
        }
    }

    @Suppress("DEPRECATION")
    private fun createBlurredBitmap(source: Bitmap): Bitmap {
        val scriptContext = renderScript ?: return source.copy(Bitmap.Config.ARGB_8888, false)
        val script = blurScript ?: return source.copy(Bitmap.Config.ARGB_8888, false)
        // Blur at half resolution, then upscale. This reduces GPU/CPU work while
        // producing the softer background expected from a portrait-mode blur.
        val smallWidth = max(1, source.width / 2)
        val smallHeight = max(1, source.height / 2)
        val small = Bitmap.createScaledBitmap(source, smallWidth, smallHeight, true)
            .copy(Bitmap.Config.ARGB_8888, true)
        val blurredSmall = Bitmap.createBitmap(smallWidth, smallHeight, Bitmap.Config.ARGB_8888)
        val input = Allocation.createFromBitmap(scriptContext, small)
        val output = Allocation.createFromBitmap(scriptContext, blurredSmall)
        return try {
            script.setInput(input)
            script.forEach(output)
            output.copyTo(blurredSmall)
            val scaled = Bitmap.createScaledBitmap(blurredSmall, source.width, source.height, true)
            if (scaled === blurredSmall) blurredSmall.copy(Bitmap.Config.ARGB_8888, false) else scaled
        } finally {
            input.destroy()
            output.destroy()
            small.recycle()
            blurredSmall.recycle()
        }
    }

    private fun blend(background: Int, foreground: Int, amount: Float): Int {
        val inverse = 1f - amount
        return Color.rgb(
            (Color.red(background) * inverse + Color.red(foreground) * amount).toInt(),
            (Color.green(background) * inverse + Color.green(foreground) * amount).toInt(),
            (Color.blue(background) * inverse + Color.blue(foreground) * amount).toInt(),
        )
    }

    private fun render(bitmap: Bitmap) {
        mainHandler.post {
            if (released.get() || !running || !outputSurface.isValid) {
                bitmap.recycle()
                processing.set(false)
                return@post
            }
            val canvas = runCatching { outputSurface.lockCanvas(null) }.getOrNull()
            if (canvas == null) {
                bitmap.recycle()
                processing.set(false)
                return@post
            }
            try {
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                val sourceRatio = bitmap.width.toFloat() / bitmap.height
                val targetRatio = canvas.width.toFloat() / canvas.height
                val destination = if (sourceRatio > targetRatio) {
                    val scaledWidth = canvas.height * sourceRatio
                    val left = (canvas.width - scaledWidth) / 2f
                    android.graphics.RectF(left, 0f, left + scaledWidth, canvas.height.toFloat())
                } else {
                    val scaledHeight = canvas.width / sourceRatio
                    val top = (canvas.height - scaledHeight) / 2f
                    android.graphics.RectF(0f, top, canvas.width.toFloat(), top + scaledHeight)
                }
                canvas.drawBitmap(bitmap, null, destination, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            } catch (_: RuntimeException) {
                // La SurfaceTexture peut être invalidée par le thread GL pendant un resize.
            } finally {
                runCatching { outputSurface.unlockCanvasAndPost(canvas) }
                bitmap.recycle()
                processing.set(false)
            }
        }
    }

    private fun clearSurface() {
        mainHandler.post {
            if (released.get() || !outputSurface.isValid) return@post
            val canvas = runCatching { outputSurface.lockCanvas(null) }.getOrNull() ?: return@post
            try {
                if (AndroidCapabilities.supportsModernCanvasClear()) {
                    canvas.drawColor(Color.TRANSPARENT, BlendMode.CLEAR)
                } else {
                    @Suppress("DEPRECATION")
                    canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                }
            } catch (_: RuntimeException) {
                // La surface est en cours de remplacement ; la prochaine frame la redessinera.
            } finally {
                runCatching { outputSurface.unlockCanvasAndPost(canvas) }
            }
        }
    }

    companion object {
        private const val RELEASE_DRAIN_DELAY_MS = 25L
        private const val MAX_RELEASE_DRAIN_ATTEMPTS = 200
    }
}
