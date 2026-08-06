package fr.nicovers06.streamstudio.stream

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.SurfaceTexture
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.gl.render.filters.`object`.SurfaceFilterRender
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.audio.SilenceAudioSource
import com.pedro.encoder.input.sources.video.NoVideoSource
import com.pedro.library.generic.GenericStream
import fr.nicovers06.streamstudio.MainActivity
import fr.nicovers06.streamstudio.R
import fr.nicovers06.streamstudio.model.NormalizedRect
import fr.nicovers06.streamstudio.model.StreamScene

class StreamService : LifecycleService(), ConnectChecker {
    interface Listener {
        fun onStateChanged(isStreaming: Boolean, status: String)
        fun onBitrateChanged(bitsPerSecond: Long)
        fun onWarning(message: String)
    }

    inner class LocalBinder : Binder() {
        val service: StreamService get() = this@StreamService
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mediaProjectionManager by lazy {
        getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }
    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    private lateinit var stream: GenericStream
    private var prepared = false
    private var listener: Listener? = null
    private var mediaProjection: MediaProjection? = null
    private var currentScene = StreamScene()
    private var filtersAdded = false
    private var screenFilter: SurfaceFilterRender? = null
    private var cameraFilter: SurfaceFilterRender? = null
    private var chatFilter: SurfaceFilterRender? = null
    private var screenSurface: Surface? = null
    private var screenPipeline: ScreenOverlayPipeline? = null
    private var cameraPipeline: CameraOverlayPipeline? = null
    private var chatRenderer: ChatOverlayRenderer? = null
    private var currentStatus = "PRÊT"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        stream = GenericStream(applicationContext, this, NoVideoSource(), SilenceAudioSource()).apply {
            getGlInterface().setForceRender(true, OUTPUT_FPS)
        }
        prepared = runCatching {
            stream.prepareVideo(
                width = OUTPUT_WIDTH,
                height = OUTPUT_HEIGHT,
                bitrate = VIDEO_BITRATE,
                fps = OUTPUT_FPS,
                iFrameInterval = 2,
                rotation = 0,
            ) && stream.prepareAudio(
                sampleRate = AUDIO_SAMPLE_RATE,
                isStereo = true,
                bitrate = AUDIO_BITRATE,
                echoCanceler = true,
                noiseSuppressor = true,
            )
        }.getOrDefault(false)
        if (!prepared) currentStatus = "ENCODEUR INDISPONIBLE"
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> {
                stopBroadcast()
                stopSelf()
            }
        }
        return Service.START_NOT_STICKY
    }

    override fun onDestroy() {
        listener = null
        stopScreenCapture()
        releaseOverlayPipelines()
        if (::stream.isInitialized) stream.release()
        super.onDestroy()
    }

    fun setListener(listener: Listener?) {
        this.listener = listener
        listener?.onStateChanged(isStreaming(), currentStatus)
    }

    fun isStreaming(): Boolean = ::stream.isInitialized && stream.isStreaming

    fun applyScene(scene: StreamScene) {
        currentScene = scene
        if (!scene.screen.enabled) {
            screenPipeline?.release()
            screenPipeline = null
        } else if (mediaProjection != null && screenPipeline == null) {
            restartScreenPipeline()
        }
        screenFilter?.let {
            applyTransform(it, scene.screen.bounds)
            it.setAlpha(if (scene.screen.enabled && screenPipeline != null) 1f else 0f)
        }
        cameraFilter?.let {
            applyTransform(it, scene.camera.bounds)
            it.setAlpha(if (scene.camera.enabled) 1f else 0f)
        }
        chatFilter?.let {
            applyTransform(it, scene.chat.bounds)
            it.setAlpha(if (scene.chat.enabled) 1f else 0f)
        }

        cameraPipeline?.let { pipeline ->
            if (scene.camera.enabled) {
                pipeline.start(scene.camera.backgroundBlur, scene.camera.facing)
                pipeline.update(scene.camera.backgroundBlur, scene.camera.facing)
            } else {
                pipeline.stop()
            }
        }
        chatRenderer?.update(scene.chat.messages, scene.chat.enabled)
    }

    fun attachPreview(textureView: TextureView) {
        if (!prepared) {
            listener?.onWarning("L’encodeur vidéo n’a pas pu être préparé sur cet appareil")
            return
        }
        if (stream.isOnPreview) return
        ensureFilters()
        runCatching { stream.startPreview(textureView, true) }
            .onFailure { listener?.onWarning("Aperçu indisponible : ${it.message.orEmpty()}") }
    }

    fun detachPreview() {
        if (!stream.isOnPreview) return
        stream.stopPreview(true)
        if (!stream.isStreaming) {
            filtersAdded = false
            releaseOverlayPipelines()
            screenFilter = null
            cameraFilter = null
            chatFilter = null
        }
    }

    fun stopBroadcast() {
        if (stream.isStreaming) stream.stopStream()
        if (stream.videoSource !is NoVideoSource) {
            runCatching { stream.changeVideoSource(NoVideoSource()) }
        }
        if (stream.audioSource !is SilenceAudioSource) {
            runCatching { stream.changeAudioSource(SilenceAudioSource()) }
        }
        stopScreenCapture()
        if (!stream.isOnPreview) {
            filtersAdded = false
            releaseOverlayPipelines()
            screenFilter = null
            cameraFilter = null
            chatFilter = null
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        currentStatus = "PRÊT"
        listener?.onStateChanged(false, currentStatus)
    }

    private fun handleStart(intent: Intent) {
        val scene = runCatching {
            StreamScene.fromJson(org.json.JSONObject(intent.getStringExtra(EXTRA_SCENE_JSON).orEmpty()))
        }.getOrElse { currentScene }
        val endpoint = intent.getStringExtra(EXTRA_ENDPOINT).orEmpty()
        startForegroundFor(scene, "Connexion en cours…")

        if (!prepared || endpoint.isBlank()) {
            failStart("Paramètres d’encodage ou destination invalides")
            return
        }
        ensureFilters()
        applyScene(scene)

        if (!configureAudio(scene)) return
        if (!configureVideo(scene, intent)) return

        runCatching { stream.startStream(endpoint) }
            .onSuccess {
                currentStatus = "CONNEXION…"
                listener?.onStateChanged(true, currentStatus)
            }
            .onFailure { failStart("Impossible de démarrer l’encodage : ${it.message.orEmpty()}") }
    }

    private fun configureAudio(scene: StreamScene): Boolean {
        return if (scene.microphoneEnabled) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                failStart("Autorisation microphone manquante")
                false
            } else {
                runCatching {
                    if (stream.audioSource !is MicrophoneSource) stream.changeAudioSource(MicrophoneSource())
                    (stream.audioSource as MicrophoneSource).unMute()
                }.onFailure { failStart("Microphone indisponible : ${it.message.orEmpty()}") }.isSuccess
            }
        } else {
            runCatching {
                if (stream.audioSource !is SilenceAudioSource) stream.changeAudioSource(SilenceAudioSource())
            }.onFailure { failStart("Piste audio silencieuse indisponible") }.isSuccess
        }
    }

    private fun configureVideo(scene: StreamScene, intent: Intent): Boolean {
        val baseReady = runCatching {
            if (stream.videoSource !is NoVideoSource) stream.changeVideoSource(NoVideoSource())
        }.onFailure { failStart("Source vidéo indisponible") }.isSuccess
        if (!baseReady) return false

        stopScreenCapture()
        if (!scene.screen.enabled) return true

        @Suppress("DEPRECATION")
        val projectionData = intent.getParcelableExtra<Intent>(EXTRA_PROJECTION_DATA)
        val resultCode = intent.getIntExtra(EXTRA_PROJECTION_RESULT, Activity.RESULT_CANCELED)
        if (projectionData == null || resultCode != Activity.RESULT_OK) {
            failStart("Autorisation de capture d’écran manquante")
            return false
        }
        return runCatching {
            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, projectionData)
                ?: error("MediaProjection indisponible")
            check(restartScreenPipeline()) { "Surface de partage d’écran indisponible" }
        }.onFailure { failStart("Capture d’écran indisponible : ${it.message.orEmpty()}") }.isSuccess
    }

    private fun ensureFilters() {
        if (filtersAdded) return
        releaseOverlayPipelines()

        screenFilter = SurfaceFilterRender(SurfaceFilterRender.SurfaceReadyCallback { texture: SurfaceTexture ->
            texture.setDefaultBufferSize(OUTPUT_WIDTH, OUTPUT_HEIGHT)
            screenPipeline?.release()
            screenPipeline = null
            screenSurface?.release()
            screenSurface = Surface(texture)
            if (!restartScreenPipeline()) {
                mainHandler.post { failStart("La surface de partage d’écran n’a pas pu démarrer") }
            }
            applyScene(currentScene)
        })
        cameraFilter = SurfaceFilterRender(SurfaceFilterRender.SurfaceReadyCallback { texture ->
            texture.setDefaultBufferSize(CAMERA_SURFACE_WIDTH, CAMERA_SURFACE_HEIGHT)
            cameraPipeline?.release()
            cameraPipeline = CameraOverlayPipeline(
                context = applicationContext,
                lifecycleOwner = this,
                outputSurface = Surface(texture),
                onError = { listener?.onWarning(it) },
            )
            applyScene(currentScene)
        })
        chatFilter = SurfaceFilterRender(SurfaceFilterRender.SurfaceReadyCallback { texture: SurfaceTexture ->
            chatRenderer?.release()
            chatRenderer = ChatOverlayRenderer(texture)
            applyScene(currentScene)
        })

        screenFilter?.let {
            applyTransform(it, currentScene.screen.bounds)
            it.setAlpha(0f)
            stream.getGlInterface().addFilter(it)
        }
        cameraFilter?.let {
            applyTransform(it, currentScene.camera.bounds)
            it.setAlpha(if (currentScene.camera.enabled) 1f else 0f)
            stream.getGlInterface().addFilter(it)
        }
        chatFilter?.let {
            applyTransform(it, currentScene.chat.bounds)
            it.setAlpha(if (currentScene.chat.enabled) 1f else 0f)
            stream.getGlInterface().addFilter(it)
        }
        filtersAdded = true
    }

    private fun releaseOverlayPipelines() {
        screenPipeline?.release()
        screenPipeline = null
        screenSurface?.release()
        screenSurface = null
        cameraPipeline?.release()
        cameraPipeline = null
        chatRenderer?.release()
        chatRenderer = null
    }

    private fun restartScreenPipeline(): Boolean {
        screenPipeline?.release()
        screenPipeline = null
        screenFilter?.setAlpha(0f)

        if (!currentScene.screen.enabled) return true
        val projection = mediaProjection ?: return true
        val surface = screenSurface ?: return true
        if (!surface.isValid) return false

        val pipeline = ScreenOverlayPipeline(
            context = applicationContext,
            mediaProjection = projection,
            outputSurface = surface,
            width = OUTPUT_WIDTH,
            height = OUTPUT_HEIGHT,
            onProjectionStopped = {
                mainHandler.post {
                    if (mediaProjection === projection) {
                        mediaProjection = null
                        screenPipeline?.release()
                        screenPipeline = null
                        screenFilter?.setAlpha(0f)
                        if (stream.isStreaming) {
                            failStart("Le partage d’écran a été arrêté")
                        } else {
                            listener?.onWarning("Le partage d’écran a été arrêté")
                        }
                    }
                }
            },
            onError = { message -> mainHandler.post { listener?.onWarning(message) } },
        )
        screenPipeline = pipeline
        if (!pipeline.start()) {
            if (screenPipeline === pipeline) screenPipeline = null
            pipeline.release()
            return false
        }
        screenFilter?.setAlpha(1f)
        return true
    }

    private fun stopScreenCapture() {
        val projection = mediaProjection
        mediaProjection = null
        screenPipeline?.release()
        screenPipeline = null
        screenFilter?.setAlpha(0f)
        projection?.stop()
    }

    private fun applyTransform(filter: SurfaceFilterRender, bounds: NormalizedRect) {
        val safe = bounds.constrained()
        filter.setScale(safe.width * 100f, safe.height * 100f)
        filter.setPosition(safe.x * 100f, safe.y * 100f)
    }

    private fun startForegroundFor(scene: StreamScene, text: String) {
        val serviceTypes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var result = 0
            if (scene.screen.enabled) result = result or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (scene.camera.enabled) result = result or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                if (scene.microphoneEnabled) result = result or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            result
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, createNotification(text), serviceTypes)
    }

    private fun createNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, StreamService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, getString(R.string.notification_stop), stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    getString(R.string.notification_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    private fun failStart(message: String) {
        if (stream.isStreaming) stream.stopStream()
        if (stream.videoSource !is NoVideoSource) {
            runCatching { stream.changeVideoSource(NoVideoSource()) }
        }
        if (stream.audioSource !is SilenceAudioSource) {
            runCatching { stream.changeAudioSource(SilenceAudioSource()) }
        }
        stopScreenCapture()
        if (!stream.isOnPreview) {
            filtersAdded = false
            releaseOverlayPipelines()
            screenFilter = null
            cameraFilter = null
            chatFilter = null
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        currentStatus = "ERREUR"
        listener?.onWarning(message)
        listener?.onStateChanged(false, currentStatus)
    }

    override fun onConnectionStarted(url: String) {
        // Never expose or log `url`: it contains the private stream key.
    }

    override fun onConnectionSuccess() {
        currentStatus = "EN DIRECT"
        notificationManager.notify(NOTIFICATION_ID, createNotification("Diffusion active"))
        listener?.onStateChanged(true, currentStatus)
    }

    override fun onConnectionFailed(reason: String) {
        val safeReason = reason.replace(Regex("rtmps?://\\S+", RegexOption.IGNORE_CASE), "destination")
        failStart("Connexion refusée : $safeReason")
    }

    override fun onDisconnect() {
        currentStatus = "PRÊT"
        listener?.onStateChanged(false, currentStatus)
    }

    override fun onAuthError() {
        failStart("Clé de stream ou authentification refusée")
    }

    override fun onAuthSuccess() = Unit

    override fun onNewBitrate(bitrate: Long) {
        listener?.onBitrateChanged(bitrate)
    }

    companion object {
        const val ACTION_START = "fr.nicovers06.streamstudio.action.START_STREAM"
        const val ACTION_STOP = "fr.nicovers06.streamstudio.action.STOP_STREAM"
        const val EXTRA_ENDPOINT = "endpoint"
        const val EXTRA_SCENE_JSON = "scene_json"
        const val EXTRA_PROJECTION_RESULT = "projection_result"
        const val EXTRA_PROJECTION_DATA = "projection_data"

        private const val NOTIFICATION_CHANNEL_ID = "stream_broadcast"
        private const val NOTIFICATION_ID = 6106
        private const val OUTPUT_WIDTH = 1280
        private const val OUTPUT_HEIGHT = 720
        private const val OUTPUT_FPS = 30
        private const val VIDEO_BITRATE = 4_500_000
        private const val AUDIO_SAMPLE_RATE = 44_100
        private const val AUDIO_BITRATE = 128_000
        private const val CAMERA_SURFACE_WIDTH = 640
        private const val CAMERA_SURFACE_HEIGHT = 360
    }
}
