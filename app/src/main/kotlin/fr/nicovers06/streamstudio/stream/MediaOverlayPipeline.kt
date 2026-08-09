package fr.nicovers06.streamstudio.stream

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.os.Handler
import android.view.Surface
import fr.nicovers06.streamstudio.data.SceneMediaStore
import fr.nicovers06.streamstudio.model.NativeWidgetComponent
import java.util.concurrent.atomic.AtomicBoolean

/** Lit une vidéo locale directement dans la surface du filtre OpenGL. */
class MediaOverlayPipeline(
    private val context: Context,
    private val surfaceTexture: SurfaceTexture,
    private var bufferWidth: Int,
    private var bufferHeight: Int,
    private val mainHandler: Handler,
    private val onError: (String) -> Unit,
) {
    private val surface = Surface(surfaceTexture)
    private val released = AtomicBoolean(false)
    private var player: MediaPlayer? = null
    private var currentFileName = ""
    private var loadGeneration = 0L
    private var component: NativeWidgetComponent? = null

    init {
        surfaceTexture.setDefaultBufferSize(bufferWidth.coerceAtLeast(2), bufferHeight.coerceAtLeast(2))
    }

    fun update(value: NativeWidgetComponent) {
        if (released.get()) return
        mainHandler.post {
            if (released.get()) return@post
            component = value
            if (value.mediaFileName != currentFileName) {
                load(value)
            } else {
                player?.isLooping = value.mediaLoop
                if (value.enabled) startPlayer() else pausePlayer()
                if (player == null) drawPlaceholder(value.mediaDisplayName)
            }
        }
    }

    fun resizeBuffer(width: Int, height: Int) {
        if (released.get()) return
        mainHandler.post {
            if (released.get()) return@post
            bufferWidth = width.coerceAtLeast(2)
            bufferHeight = height.coerceAtLeast(2)
            runCatching { surfaceTexture.setDefaultBufferSize(bufferWidth, bufferHeight) }
            if (player == null) drawPlaceholder(component?.mediaDisplayName.orEmpty())
        }
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        mainHandler.post {
            loadGeneration++
            releasePlayer()
            runCatching { surface.release() }
        }
    }

    private fun load(value: NativeWidgetComponent) {
        loadGeneration++
        val generation = loadGeneration
        releasePlayer()
        currentFileName = value.mediaFileName
        if (value.mediaFileName.isBlank()) {
            drawPlaceholder(value.mediaDisplayName)
            return
        }
        val file = SceneMediaStore.fileFor(context, value.mediaFileName)
        if (!file.isFile || file.length() <= 0L) {
            drawPlaceholder("Média introuvable")
            return
        }
        drawPlaceholder("Chargement de ${value.mediaDisplayName}…")
        val created = MediaPlayer()
        player = created
        runCatching {
            created.setDataSource(file.absolutePath)
            created.setSurface(surface)
            created.setVolume(0f, 0f)
            created.isLooping = value.mediaLoop
            created.setOnPreparedListener { prepared ->
                if (released.get() || generation != loadGeneration || player !== prepared) return@setOnPreparedListener
                runCatching {
                    prepared.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
                }
                if (component?.enabled == true) runCatching { prepared.start() }
            }
            created.setOnCompletionListener { completed ->
                if (component?.mediaLoop == true) runCatching {
                    completed.seekTo(0)
                    completed.start()
                }
            }
            created.setOnErrorListener { broken, _, _ ->
                if (player === broken && generation == loadGeneration) {
                    drawPlaceholder("Média illisible")
                    onError("Le média « ${value.mediaDisplayName} » ne peut pas être lu")
                }
                true
            }
            created.prepareAsync()
        }.onFailure {
            if (player === created) player = null
            runCatching { created.release() }
            drawPlaceholder("Média illisible")
            onError("Le média « ${value.mediaDisplayName} » ne peut pas être préparé")
        }
    }

    private fun startPlayer() {
        val current = player ?: return
        runCatching { if (!current.isPlaying) current.start() }
    }

    private fun pausePlayer() {
        val current = player ?: return
        runCatching { if (current.isPlaying) current.pause() }
    }

    private fun releasePlayer() {
        val old = player
        player = null
        if (old != null) {
            runCatching { old.setSurface(null) }
            runCatching { old.stop() }
            runCatching { old.release() }
        }
    }

    private fun drawPlaceholder(label: String) {
        if (released.get() || !surface.isValid) return
        val canvas: Canvas = runCatching { surface.lockCanvas(null) }.getOrNull() ?: return
        try {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            val w = canvas.width.toFloat().takeIf { it > 0f } ?: bufferWidth.toFloat()
            val h = canvas.height.toFloat().takeIf { it > 0f } ?: bufferHeight.toFloat()
            val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xDD111827.toInt() }
            val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF8B5CF6.toInt()
                style = Paint.Style.STROKE
                strokeWidth = (minOf(w, h) * 0.025f).coerceAtLeast(2f)
            }
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textAlign = Paint.Align.CENTER
                textSize = (minOf(w, h) * 0.12f).coerceAtLeast(18f)
            }
            canvas.drawRoundRect(RectF(0f, 0f, w, h), minOf(w, h) * 0.07f, minOf(w, h) * 0.07f, panel)
            canvas.drawRoundRect(
                RectF(border.strokeWidth, border.strokeWidth, w - border.strokeWidth, h - border.strokeWidth),
                minOf(w, h) * 0.07f,
                minOf(w, h) * 0.07f,
                border,
            )
            val metrics = text.fontMetrics
            val baseline = h / 2f - (metrics.ascent + metrics.descent) / 2f
            canvas.drawText(label.ifBlank { "Choisissez une vidéo" }, w / 2f, baseline, text)
        } catch (_: RuntimeException) {
            Unit
        } finally {
            runCatching { surface.unlockCanvasAndPost(canvas) }
        }
    }
}
