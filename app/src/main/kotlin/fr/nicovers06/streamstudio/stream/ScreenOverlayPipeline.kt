package fr.nicovers06.streamstudio.stream

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sends MediaProjection frames into a SurfaceFilterRender input surface.
 *
 * A MediaProjection token may create only one VirtualDisplay on Android 14+.
 * The display therefore survives preview surface recreation and is detached or
 * attached to the current OpenGL filter surface instead of being recreated.
 */
class ScreenOverlayPipeline(
    context: Context,
    private val mediaProjection: MediaProjection,
    outputSurface: Surface,
    private val width: Int,
    private val height: Int,
    private val onProjectionStopped: () -> Unit,
    private val onError: (String) -> Unit,
) {
    private val densityDpi = context.resources.displayMetrics.densityDpi
    private val worker = HandlerThread("screen-overlay")
    private val released = AtomicBoolean(false)
    private val stopNotified = AtomicBoolean(false)
    private var handler: Handler? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var outputSurface: Surface? = outputSurface

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            virtualDisplay?.release()
            virtualDisplay = null
            if (stopNotified.compareAndSet(false, true)) onProjectionStopped()
        }
    }

    fun start(): Boolean {
        if (virtualDisplay != null) return true
        val surface = outputSurface ?: return false
        if (released.get() || stopNotified.get() || !surface.isValid) return false

        worker.start()
        val workerHandler = Handler(worker.looper)
        handler = workerHandler
        mediaProjection.registerCallback(projectionCallback, workerHandler)

        return runCatching {
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "StreamStudioScreen",
                width,
                height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                workerHandler,
            ) ?: error("VirtualDisplay indisponible")
        }.onFailure {
            onError("Partage d’écran indisponible : ${it.message.orEmpty()}")
            release()
        }.isSuccess
    }

    fun attachSurface(surface: Surface): Boolean {
        if (released.get() || stopNotified.get() || !surface.isValid) return false
        if (outputSurface === surface && virtualDisplay != null) return true
        outputSurface = surface
        val display = virtualDisplay ?: return start()
        return runCatching { display.setSurface(surface) }
            .onFailure { onError("Surface de partage d’écran indisponible : ${it.message.orEmpty()}") }
            .isSuccess
    }

    fun detachSurface() {
        if (released.get()) return
        runCatching { virtualDisplay?.setSurface(null) }
        outputSurface = null
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        virtualDisplay?.release()
        virtualDisplay = null
        outputSurface = null
        runCatching { mediaProjection.unregisterCallback(projectionCallback) }
        handler = null
        worker.quitSafely()
    }
}
