package fr.nicovers06.streamstudio.stream

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
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
    private val onSourceAspectRatioChanged: (Float) -> Unit,
    previewAudioEnabled: Boolean,
) {
    private val released = AtomicBoolean(false)
    private var player: MediaPlayer? = null
    private var playbackSurface: Surface? = null
    private var playerPrepared = false
    private var currentFileName = ""
    private var loadGeneration = 0L
    private var component: NativeWidgetComponent? = null
    private var previewAudioEnabled = previewAudioEnabled

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
            } else if (playerPrepared) {
                player?.let { current -> runCatching { current.isLooping = value.mediaLoop } }
                if (value.enabled) startPlayer() else pausePlayer()
            } else if (player == null) {
                drawPlaceholder(value.mediaDisplayName)
            }
        }
    }

    fun resizeBuffer(width: Int, height: Int) {
        if (released.get()) return
        mainHandler.post {
            if (released.get()) return@post
            bufferWidth = width.coerceAtLeast(2)
            bufferHeight = height.coerceAtLeast(2)
            if (player == null) drawPlaceholder(component?.mediaDisplayName.orEmpty())
        }
    }

    fun setPreviewAudioEnabled(enabled: Boolean) {
        runOnPlayerThread {
            previewAudioEnabled = enabled
            applyPlayerVolume()
        }
    }

    fun currentPositionMs(): Long = if (Looper.myLooper() == mainHandler.looper && playerPrepared) {
        runCatching { player?.currentPosition?.toLong() }.getOrNull() ?: 0L
    } else {
        0L
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        mainHandler.post {
            loadGeneration++
            releasePlayer()
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
        val sourceSize = SceneMediaStore.displaySize(context, value.mediaFileName)
        if (sourceSize != null) {
            val (sourceWidth, sourceHeight) = sourceSize
            onSourceAspectRatioChanged(sourceWidth.toFloat() / sourceHeight.toFloat())
            runCatching { surfaceTexture.setDefaultBufferSize(sourceWidth, sourceHeight) }
        }
        val createdSurface = runCatching { Surface(surfaceTexture) }.getOrNull()
        if (createdSurface == null || !createdSurface.isValid) {
            runCatching { createdSurface?.release() }
            drawPlaceholder("Surface vidéo indisponible")
            onError("Le média « ${value.mediaDisplayName} » ne peut pas être affiché")
            return
        }
        val created = runCatching { MediaPlayer() }.getOrNull()
        if (created == null) {
            runCatching { createdSurface.release() }
            drawPlaceholder("Lecteur vidéo indisponible")
            onError("Le lecteur vidéo ne peut pas être initialisé")
            return
        }
        player = created
        playbackSurface = createdSurface
        playerPrepared = false
        runCatching {
            created.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build(),
            )
            created.setDataSource(file.absolutePath)
            created.setSurface(createdSurface)
            applyPlayerVolume(created)
            created.isLooping = value.mediaLoop
            created.setOnPreparedListener { prepared ->
                if (released.get() || generation != loadGeneration || player !== prepared) return@setOnPreparedListener
                playerPrepared = true
                if (sourceSize == null && prepared.videoWidth > 0 && prepared.videoHeight > 0) {
                    onSourceAspectRatioChanged(prepared.videoWidth.toFloat() / prepared.videoHeight.toFloat())
                }
                runCatching { prepared.isLooping = component?.mediaLoop ?: value.mediaLoop }
                if (component?.enabled == true) startPlayer()
            }
            created.setOnErrorListener { broken, _, _ ->
                if (player === broken && generation == loadGeneration) {
                    releasePlayer()
                    drawPlaceholder("Média illisible")
                    onError("Le média « ${value.mediaDisplayName} » ne peut pas être lu")
                }
                true
            }
            created.prepareAsync()
        }.onFailure {
            if (player === created) {
                releasePlayer()
            } else {
                runCatching { created.setSurface(null) }
                runCatching { created.release() }
                runCatching { createdSurface.release() }
            }
            drawPlaceholder("Média illisible")
            onError("Le média « ${value.mediaDisplayName} » ne peut pas être préparé")
        }
    }

    private fun startPlayer() {
        if (!playerPrepared) return
        val current = player ?: return
        runCatching { if (!current.isPlaying) current.start() }
    }

    private fun pausePlayer() {
        if (!playerPrepared) return
        val current = player ?: return
        runCatching { if (current.isPlaying) current.pause() }
    }

    private fun applyPlayerVolume(target: MediaPlayer? = player) {
        val volume = if (previewAudioEnabled) 1f else 0f
        target?.let { current -> runCatching { current.setVolume(volume, volume) } }
    }

    private fun runOnPlayerThread(action: () -> Unit) {
        if (Looper.myLooper() == mainHandler.looper) {
            action()
        } else {
            mainHandler.post {
                if (!released.get()) action()
            }
        }
    }

    private fun releasePlayer() {
        val old = player
        val oldSurface = playbackSurface
        player = null
        playbackSurface = null
        playerPrepared = false
        if (old != null) {
            runCatching { old.setOnPreparedListener(null) }
            runCatching { old.setOnCompletionListener(null) }
            runCatching { old.setOnErrorListener(null) }
            runCatching { old.setSurface(null) }
            runCatching { old.release() }
        }
        runCatching { oldSurface?.release() }
    }

    private fun drawPlaceholder(label: String) {
        if (released.get() || player != null || playbackSurface != null) return
        runCatching { surfaceTexture.setDefaultBufferSize(bufferWidth, bufferHeight) }
        onSourceAspectRatioChanged(bufferWidth.toFloat() / bufferHeight.toFloat())
        // Une SurfaceTexture n'accepte qu'un producteur : le Canvas utilise donc une Surface éphémère.
        val canvasSurface = runCatching { Surface(surfaceTexture) }.getOrNull() ?: return
        if (!canvasSurface.isValid) {
            runCatching { canvasSurface.release() }
            return
        }
        val canvas: Canvas = runCatching { canvasSurface.lockCanvas(null) }.getOrNull()
            ?: run {
                runCatching { canvasSurface.release() }
                return
            }
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
            runCatching { canvasSurface.unlockCanvasAndPost(canvas) }
            runCatching { canvasSurface.release() }
        }
    }
}
