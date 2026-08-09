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
import android.os.Handler
import android.os.HandlerThread
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
import fr.nicovers06.streamstudio.data.SceneImageStore
import fr.nicovers06.streamstudio.model.ImageComponent
import fr.nicovers06.streamstudio.model.LayerRef
import fr.nicovers06.streamstudio.model.ChatMessage
import fr.nicovers06.streamstudio.model.NormalizedRect
import fr.nicovers06.streamstudio.model.NativeWidgetComponent
import fr.nicovers06.streamstudio.model.StreamScene
import fr.nicovers06.streamstudio.model.WidgetModules
import fr.nicovers06.streamstudio.model.WidgetType
import fr.nicovers06.streamstudio.platform.AndroidCapabilities
import fr.nicovers06.streamstudio.stream.chat.LiveChatConfig
import fr.nicovers06.streamstudio.stream.chat.LiveChatCoordinator
import fr.nicovers06.streamstudio.stream.chat.LiveChatPlatform
import java.util.concurrent.ConcurrentHashMap

class StreamService : LifecycleService(), ConnectChecker {
    interface Listener {
        fun onStateChanged(isStreaming: Boolean, status: String)
        fun onScreenCaptureChanged(isReady: Boolean)
        fun onBitrateChanged(bitsPerSecond: Long)
        fun onWarning(message: String)
        fun onLiveChatMessages(messages: List<ChatMessage>) {}
    }

    inner class LocalBinder : Binder() {
        val service: StreamService get() = this@StreamService
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var overlayRenderThread: HandlerThread
    private lateinit var overlayRenderHandler: Handler
    private val mediaProjectionManager by lazy {
        getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }
    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    private lateinit var stream: GenericStream
    private var prepared = false
    @Volatile private var listener: Listener? = null
    private var mediaProjection: MediaProjection? = null
    private var currentScene = StreamScene()
    private var filtersAdded = false
    private var installedLayerOrder: List<LayerRef> = emptyList()
    private var screenFilter: SurfaceFilterRender? = null
    private var cameraFilter: SurfaceFilterRender? = null
    private var chatFilter: SurfaceFilterRender? = null
    private val imageFilters = linkedMapOf<String, SurfaceFilterRender>()
    private val imageRenderers = linkedMapOf<String, ImageOverlayRenderer>()
    private val nativeWidgetFilters = linkedMapOf<String, SurfaceFilterRender>()
    private val nativeWidgetRenderers = linkedMapOf<String, NativeWidgetOverlayRenderer>()
    private val mediaPipelines = linkedMapOf<String, MediaOverlayPipeline>()
    private val imageBitmapCache = ConcurrentHashMap<String, android.graphics.Bitmap>()
    private var screenSurface: Surface? = null
    private var screenPipeline: ScreenOverlayPipeline? = null
    private var cameraPipeline: CameraOverlayPipeline? = null
    private var chatRenderer: ChatOverlayRenderer? = null
    private var currentStatus = "PRÊT"
    private val liveChatCoordinator = LiveChatCoordinator()
    private var liveChatConfig = LiveChatConfig()
    private var liveChatMessages: List<ChatMessage> = emptyList()
    private var liveChatActive = false
    private val previewLease = IdentityLease<TextureView>()
    private val overlayGeneration = GenerationGate()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        overlayRenderThread = HandlerThread("native-widget-renderer").also { it.start() }
        overlayRenderHandler = Handler(overlayRenderThread.looper)
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
            ACTION_PREPARE_SCREEN -> handlePrepareScreen(intent)
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> {
                stopBroadcast()
                stopSelf()
            }
        }
        return Service.START_NOT_STICKY
    }

    override fun onDestroy() {
        previewLease.clear()
        listener = null
        liveChatCoordinator.stop()
        liveChatActive = false
        stopScreenCapture()
        releaseOverlayPipelines()
        imageBitmapCache.values.forEach { bitmap ->
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        imageBitmapCache.clear()
        if (::stream.isInitialized) runCatching { stream.release() }
        if (::overlayRenderThread.isInitialized) overlayRenderThread.quitSafely()
        super.onDestroy()
    }

    fun setListener(listener: Listener?) {
        this.listener = listener
        listener?.onStateChanged(isStreaming(), currentStatus)
        listener?.onScreenCaptureChanged(mediaProjection != null)
    }

    fun clearListener(expected: Listener) {
        if (listener === expected) listener = null
    }

    fun isStreaming(): Boolean = ::stream.isInitialized && stream.isStreaming

    fun hasScreenCapture(): Boolean = mediaProjection != null

    fun stopScreenPreview() {
        if (isStreaming()) return
        stopScreenCapture()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun applyScene(scene: StreamScene) {
        currentScene = scene
        val layerOrder = scene.normalizedLayerOrder()
        val imageIds = scene.images.map { it.id }.toSet()
        val installedImageIds = imageFilters.keys.toSet()
        val nativeWidgetIds = scene.nativeWidgets.map { it.id }.toSet()
        val installedNativeWidgetIds = nativeWidgetFilters.keys.toSet()
        val needReinstall = filtersAdded && (
            installedLayerOrder != layerOrder ||
                installedImageIds != imageIds ||
                installedNativeWidgetIds != nativeWidgetIds
        )
        if (needReinstall) {
            reinstallFilters(layerOrder)
        }
        if (scene.screen.enabled && mediaProjection != null) {
            ensureScreenPipeline()
        }
        syncOverlaySurfaceSizes(scene)
        screenFilter?.let {
            applyTransform(it, scene.screen.bounds)
            val hasScreenOutput = screenPipeline != null && screenSurface?.isValid == true
            it.setAlpha(if (scene.screen.enabled && hasScreenOutput) 1f else 0f)
        }
        cameraFilter?.let {
            applyTransform(it, scene.camera.bounds)
            it.setAlpha(if (scene.camera.enabled) 1f else 0f)
        }
        chatFilter?.let {
            applyTransform(it, scene.chat.bounds)
            it.setAlpha(if (scene.chat.enabled) 1f else 0f)
        }
        scene.images.forEach { image ->
            imageFilters[image.id]?.let { filter ->
                applyTransform(filter, image.bounds)
                filter.setAlpha(if (image.enabled) 1f else 0f)
            }
        }
        scene.nativeWidgets.forEach { widget ->
            nativeWidgetFilters[widget.id]?.let { filter ->
                applyTransform(filter, widget.bounds)
                filter.setAlpha(if (widget.enabled) 1f else 0f)
            }
        }

        cameraPipeline?.let { pipeline ->
            if (scene.camera.enabled) {
                pipeline.start(scene.camera.backgroundBlur, scene.camera.facing)
                pipeline.update(scene.camera.backgroundBlur, scene.camera.facing)
            } else {
                pipeline.stop()
            }
        }
        refreshChatOverlay()
        refreshImageOverlays()
        refreshNativeWidgetOverlays()
        syncLiveChat()
    }

    fun configureLiveChat(config: LiveChatConfig) {
        liveChatConfig = config
        syncLiveChat()
    }

    fun attachPreview(textureView: TextureView) {
        if (!prepared) {
            listener?.onWarning("L’encodeur vidéo n’a pas pu être préparé sur cet appareil")
            return
        }
        val ownerChanged = previewLease.claim(textureView)
        if (!ownerChanged && stream.isOnPreview) return
        if (stream.isOnPreview && !stopPreviewSession()) {
            previewLease.release(textureView)
            listener?.onWarning("Impossible de rattacher l’aperçu à cette fenêtre")
            return
        }
        ensureFilters()
        runCatching { stream.startPreview(textureView, true) }
            .onFailure {
                previewLease.release(textureView)
                listener?.onWarning("Aperçu indisponible : ${it.message.orEmpty()}")
            }
    }

    fun detachPreview(textureView: TextureView) {
        if (!previewLease.release(textureView)) return
        stopPreviewSession()
    }

    private fun stopPreviewSession(): Boolean {
        if (!stream.isOnPreview) return true
        val keepScreenCapture = !stream.isStreaming && mediaProjection != null
        if (keepScreenCapture) screenPipeline?.detachSurface()
        val stopped = runCatching { stream.stopPreview(true) }
            .onFailure { listener?.onWarning("L’aperçu n’a pas pu être détaché proprement") }
            .isSuccess
        if (!stopped && stream.isOnPreview) return false
        if (!stream.isStreaming) {
            filtersAdded = false
            installedLayerOrder = emptyList()
            releaseOverlayPipelines(keepScreenCapture = keepScreenCapture)
            screenFilter = null
            cameraFilter = null
            chatFilter = null
            imageFilters.clear()
            nativeWidgetFilters.clear()
        }
        return true
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
        // Keep live chat running for preview if chat stays enabled.
        syncLiveChat()
        if (!stream.isOnPreview) {
            filtersAdded = false
            installedLayerOrder = emptyList()
            releaseOverlayPipelines()
            screenFilter = null
            cameraFilter = null
            chatFilter = null
            imageFilters.clear()
            nativeWidgetFilters.clear()
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        currentStatus = "PRÊT"
        listener?.onStateChanged(false, currentStatus)
    }

    private fun handlePrepareScreen(intent: Intent) {
        if (isStreaming()) {
            listener?.onWarning("Arrêtez le stream avant de modifier la source partagée")
            return
        }
        val scene = runCatching {
            StreamScene.fromJson(org.json.JSONObject(intent.getStringExtra(EXTRA_SCENE_JSON).orEmpty()))
        }.getOrElse { currentScene }
        startForegroundForScreenPreview("Aperçu du partage d’écran")

        if (!prepared || !scene.screen.enabled) {
            failScreenPreview("Impossible de préparer l’aperçu du partage d’écran")
            return
        }
        @Suppress("DEPRECATION")
        val projectionData = intent.getParcelableExtra<Intent>(EXTRA_PROJECTION_DATA)
        val resultCode = intent.getIntExtra(EXTRA_PROJECTION_RESULT, Activity.RESULT_CANCELED)
        if (projectionData == null || resultCode != Activity.RESULT_OK) {
            failScreenPreview("Autorisation de capture d’écran manquante")
            return
        }

        stopScreenCapture(notifyListener = false)
        ensureFilters()
        applyScene(scene)
        runCatching {
            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, projectionData)
                ?: error("MediaProjection indisponible")
            check(ensureScreenPipeline()) { "Surface de partage d’écran indisponible" }
        }.onSuccess {
            listener?.onScreenCaptureChanged(true)
        }.onFailure {
            failScreenPreview("Aperçu du partage d’écran indisponible : ${it.message.orEmpty()}")
        }
    }

    private fun handleStart(intent: Intent) {
        val scene = runCatching {
            StreamScene.fromJson(org.json.JSONObject(intent.getStringExtra(EXTRA_SCENE_JSON).orEmpty()))
        }.getOrElse { currentScene }
        val endpoint = intent.getStringExtra(EXTRA_ENDPOINT).orEmpty()
        liveChatConfig = liveChatConfigFromIntent(intent)
        startForegroundFor(scene, "Connexion en cours…")

        if (!prepared || endpoint.isBlank()) {
            failStart("Paramètres d’encodage ou destination invalides")
            return
        }
        ensureFilters()
        applyScene(scene)

        if (!configureAudio(scene)) return
        if (!configureVideo(scene)) return

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

    private fun configureVideo(scene: StreamScene): Boolean {
        val baseReady = runCatching {
            if (stream.videoSource !is NoVideoSource) stream.changeVideoSource(NoVideoSource())
        }.onFailure { failStart("Source vidéo indisponible") }.isSuccess
        if (!baseReady) return false

        if (!scene.screen.enabled) {
            stopScreenCapture()
            return true
        }
        if (mediaProjection == null) {
            failStart("Choisissez l’écran ou l’application à partager avant de démarrer")
            return false
        }
        return if (ensureScreenPipeline()) {
            true
        } else {
            failStart("La surface de partage d’écran n’a pas pu démarrer")
            false
        }
    }

    private fun ensureFilters() {
        if (filtersAdded) return
        releaseOverlayPipelines(keepScreenCapture = mediaProjection != null)
        val layerOrder = currentScene.normalizedLayerOrder()
        createOverlayFilters()
        installFiltersInOrder(layerOrder)
        filtersAdded = true
        installedLayerOrder = layerOrder
    }

    /**
     * Réinstalle les filtres GL dans l’ordre back→front dérivé de [layerOrderFrontFirst].
     * removeFilter libère les ressources GL : de nouvelles instances SurfaceFilterRender sont créées.
     */
    private fun reinstallFilters(layerOrderFrontFirst: List<LayerRef>) {
        if (!prepared || !::stream.isInitialized) return
        val gl = stream.getGlInterface()
        screenFilter?.let { runCatching { gl.removeFilter(it) } }
        cameraFilter?.let { runCatching { gl.removeFilter(it) } }
        chatFilter?.let { runCatching { gl.removeFilter(it) } }
        imageFilters.values.forEach { runCatching { gl.removeFilter(it) } }
        nativeWidgetFilters.values.forEach { runCatching { gl.removeFilter(it) } }
        screenFilter = null
        cameraFilter = null
        chatFilter = null
        imageFilters.clear()
        nativeWidgetFilters.clear()
        // Pipelines rattach?s via SurfaceReadyCallback des nouveaux filtres.
        releaseOverlayPipelines(keepScreenCapture = mediaProjection != null)
        createOverlayFilters()
        installFiltersInOrder(layerOrderFrontFirst)
        installedLayerOrder = layerOrderFrontFirst
    }

    private fun createOverlayFilters() {
        val generation = overlayGeneration.open()
        screenFilter = SurfaceFilterRender(SurfaceFilterRender.SurfaceReadyCallback { texture: SurfaceTexture ->
            mainHandler.post surfaceReady@{
                if (!overlayGeneration.isCurrent(generation)) return@surfaceReady
                val (sw, sh) = surfaceSizeForBounds(currentScene.screen.bounds)
                texture.setDefaultBufferSize(sw, sh)
                screenPipeline?.detachSurface()
                runCatching { screenSurface?.release() }
                screenSurface = Surface(texture)
                if (!ensureScreenPipeline()) {
                    if (stream.isStreaming) {
                        failStart("La surface de partage d’écran n’a pas pu démarrer")
                    } else {
                        failScreenPreview("La surface d’aperçu du partage d’écran n’a pas pu démarrer")
                    }
                }
                applyScene(currentScene)
            }
        })
        cameraFilter = SurfaceFilterRender(SurfaceFilterRender.SurfaceReadyCallback { texture ->
            mainHandler.post surfaceReady@{
                if (!overlayGeneration.isCurrent(generation)) return@surfaceReady
                val (cw, ch) = surfaceSizeForBounds(currentScene.camera.bounds)
                texture.setDefaultBufferSize(cw, ch)
                cameraPipeline?.release()
                cameraPipeline = CameraOverlayPipeline(
                    context = applicationContext,
                    lifecycleOwner = this,
                    outputSurface = Surface(texture),
                    onError = { listener?.onWarning(it) },
                )
                applyScene(currentScene)
            }
        })
        chatFilter = SurfaceFilterRender(SurfaceFilterRender.SurfaceReadyCallback { texture: SurfaceTexture ->
            mainHandler.post surfaceReady@{
                if (!overlayGeneration.isCurrent(generation)) return@surfaceReady
                chatRenderer?.release()
                chatRenderer = ChatOverlayRenderer(texture)
                applyScene(currentScene)
            }
        })
        currentScene.images.forEach { image ->
            imageFilters[image.id] = createImageFilter(image, generation)
        }
        currentScene.nativeWidgets.forEach { widget ->
            nativeWidgetFilters[widget.id] = createNativeWidgetFilter(widget, generation)
        }
    }

    private fun createImageFilter(image: ImageComponent, generation: Long): SurfaceFilterRender {
        val imageId = image.id
        return SurfaceFilterRender(SurfaceFilterRender.SurfaceReadyCallback { texture: SurfaceTexture ->
            mainHandler.post surfaceReady@{
                if (!overlayGeneration.isCurrent(generation)) return@surfaceReady
                val component = currentScene.image(imageId) ?: image
                val (iw, ih) = surfaceSizeForBounds(component.bounds)
                texture.setDefaultBufferSize(iw, ih)
                imageRenderers.remove(imageId)?.release()
                val renderer = ImageOverlayRenderer(texture, iw, ih)
                imageRenderers[imageId] = renderer
                bindImageBitmap(component, renderer)
                renderer.update(component.enabled, component.keepAspectRatio)
                applyScene(currentScene)
            }
        })
    }

    private fun createNativeWidgetFilter(
        initial: NativeWidgetComponent,
        generation: Long,
    ): SurfaceFilterRender {
        val widgetId = initial.id
        return SurfaceFilterRender(SurfaceFilterRender.SurfaceReadyCallback { texture: SurfaceTexture ->
            mainHandler.post surfaceReady@{
                if (!overlayGeneration.isCurrent(generation)) return@surfaceReady
                val component = currentScene.nativeWidget(widgetId) ?: initial
                val (width, height) = surfaceSizeForBounds(component.bounds)
                texture.setDefaultBufferSize(width, height)
                nativeWidgetRenderers.remove(widgetId)?.release()
                mediaPipelines.remove(widgetId)?.release()
                if (component.type == WidgetType.MEDIA) {
                    mediaPipelines[widgetId] = MediaOverlayPipeline(
                        context = applicationContext,
                        surfaceTexture = texture,
                        bufferWidth = width,
                        bufferHeight = height,
                        mainHandler = mainHandler,
                        onError = { message -> listener?.onWarning(message) },
                    ).also { it.update(component) }
                } else {
                    nativeWidgetRenderers[widgetId] = NativeWidgetOverlayRenderer(
                        surfaceTexture = texture,
                        bufferWidth = width,
                        bufferHeight = height,
                        renderHandler = overlayRenderHandler,
                    ).also { it.update(component) }
                }
                applyScene(currentScene)
            }
        })
    }

    private fun installFiltersInOrder(layerOrderFrontFirst: List<LayerRef>) {
        val gl = stream.getGlInterface()
        val order = WidgetModules.visualLayerOrder(layerOrderFrontFirst, currentScene)
        // GL : premier ajout? = fond ; dernier = devant ? inverser la liste UI (front?back).
        order.asReversed().forEach { ref ->
            val filter = filterFor(ref) ?: return@forEach
            when (ref.type) {
                WidgetType.SCREEN -> {
                    applyTransform(filter, currentScene.screen.bounds)
                    filter.setAlpha(0f)
                }
                WidgetType.CAMERA -> {
                    applyTransform(filter, currentScene.camera.bounds)
                    filter.setAlpha(if (currentScene.camera.enabled) 1f else 0f)
                }
                WidgetType.CHAT -> {
                    applyTransform(filter, currentScene.chat.bounds)
                    filter.setAlpha(if (currentScene.chat.enabled) 1f else 0f)
                }
                WidgetType.IMAGE -> {
                    val image = currentScene.image(ref.instanceId.orEmpty())
                    if (image != null) {
                        applyTransform(filter, image.bounds)
                        filter.setAlpha(if (image.enabled) 1f else 0f)
                    }
                }
                WidgetType.TIMER,
                WidgetType.SHAPE,
                WidgetType.BACKGROUND,
                WidgetType.TICKER,
                WidgetType.MEDIA,
                WidgetType.ALERT,
                WidgetType.POLL,
                WidgetType.TEXT,
                -> {
                    val widget = currentScene.nativeWidget(ref.instanceId.orEmpty())
                    if (widget != null) {
                        applyTransform(filter, widget.bounds)
                        filter.setAlpha(if (widget.enabled) 1f else 0f)
                    }
                }
                WidgetType.MICROPHONE -> Unit
            }
            gl.addFilter(filter)
        }
    }

    private fun filterFor(ref: LayerRef): SurfaceFilterRender? = when (ref.type) {
        WidgetType.SCREEN -> screenFilter
        WidgetType.CAMERA -> cameraFilter
        WidgetType.CHAT -> chatFilter
        WidgetType.IMAGE -> imageFilters[ref.instanceId]
        WidgetType.TIMER,
        WidgetType.SHAPE,
        WidgetType.BACKGROUND,
        WidgetType.TICKER,
        WidgetType.MEDIA,
        WidgetType.ALERT,
        WidgetType.POLL,
        WidgetType.TEXT,
        -> nativeWidgetFilters[ref.instanceId]
        WidgetType.MICROPHONE -> null
    }

    private fun releaseOverlayPipelines(keepScreenCapture: Boolean = false) {
        overlayGeneration.invalidate()
        if (keepScreenCapture) {
            screenPipeline?.detachSurface()
        } else {
            screenPipeline?.release()
            screenPipeline = null
        }
        runCatching { screenSurface?.release() }
        screenSurface = null
        cameraPipeline?.release()
        cameraPipeline = null
        chatRenderer?.release()
        chatRenderer = null
        imageRenderers.values.forEach { it.release() }
        imageRenderers.clear()
        nativeWidgetRenderers.values.forEach { it.release() }
        nativeWidgetRenderers.clear()
        mediaPipelines.values.forEach { it.release() }
        mediaPipelines.clear()
    }

    private fun refreshImageOverlays() {
        val liveFiles = currentScene.images.map { it.fileName }.filter { it.isNotBlank() }.toSet()
        imageBitmapCache.keys.filter { it !in liveFiles }.forEach { key ->
            imageBitmapCache.remove(key)?.recycle()
        }
        currentScene.images.forEach { image ->
            val renderer = imageRenderers[image.id] ?: return@forEach
            bindImageBitmap(image, renderer)
            renderer.update(image.enabled, image.keepAspectRatio)
        }
    }

    private fun bindImageBitmap(image: ImageComponent, renderer: ImageOverlayRenderer) {
        if (image.fileName.isBlank()) {
            renderer.setBitmap(null, image.displayName)
            return
        }
        val cached = imageBitmapCache[image.fileName]
        if (cached != null && !cached.isRecycled) {
            renderer.setBitmap(cached, image.displayName)
            return
        }
        val file = SceneImageStore.fileFor(applicationContext, image.fileName)
        val decoded = SceneImageStore.decodeFile(file)
        if (decoded != null) {
            imageBitmapCache[image.fileName] = decoded
            renderer.setBitmap(decoded, image.displayName)
        } else {
            renderer.setBitmap(null, image.displayName)
        }
    }

    private fun refreshNativeWidgetOverlays() {
        currentScene.nativeWidgets.forEach { widget ->
            nativeWidgetRenderers[widget.id]?.update(widget)
            mediaPipelines[widget.id]?.update(widget)
        }
    }

    private fun refreshChatOverlay() {
        val messages = if (liveChatActive) liveChatMessages else currentScene.chat.messages
        chatRenderer?.update(messages, currentScene.chat.enabled)
    }

    private fun syncLiveChat() {
        val wantLive = currentScene.chat.enabled && liveChatConfig.isActionable()
        if (!wantLive) {
            if (liveChatActive) {
                liveChatCoordinator.stop()
                liveChatActive = false
                liveChatMessages = emptyList()
                refreshChatOverlay()
            }
            return
        }
        liveChatCoordinator.start(
            config = liveChatConfig,
            onMessages = { messages ->
                mainHandler.post {
                    liveChatMessages = messages
                    liveChatActive = true
                    refreshChatOverlay()
                    listener?.onLiveChatMessages(messages)
                }
            },
            onStatus = { status ->
                mainHandler.post { listener?.onWarning(status) }
            },
        )
        liveChatActive = true
    }

    private fun ensureScreenPipeline(): Boolean {
        screenFilter?.setAlpha(0f)

        if (!currentScene.screen.enabled) return true
        val projection = mediaProjection ?: return true
        val surface = screenSurface ?: return true
        if (!surface.isValid) return false

        screenPipeline?.let { pipeline ->
            val attached = pipeline.attachSurface(surface)
            if (attached) screenFilter?.setAlpha(1f)
            return attached
        }

        val pipeline = ScreenOverlayPipeline(
            context = applicationContext,
            mediaProjection = projection,
            outputSurface = surface,
            captureWidth = OUTPUT_WIDTH,
            captureHeight = OUTPUT_HEIGHT,
            onStopped = {
                mainHandler.post {
                    if (mediaProjection === projection) {
                        mediaProjection = null
                        screenPipeline?.release()
                        screenPipeline = null
                        screenFilter?.setAlpha(0f)
                        listener?.onScreenCaptureChanged(false)
                        if (stream.isStreaming) {
                            failStart("Le partage d’écran a été arrêté")
                        } else {
                            failScreenPreview("Le partage d’écran a été arrêté")
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

    private fun stopScreenCapture(notifyListener: Boolean = true) {
        val projection = mediaProjection
        mediaProjection = null
        screenPipeline?.release()
        screenPipeline = null
        screenFilter?.setAlpha(0f)
        projection?.stop()
        if (notifyListener) listener?.onScreenCaptureChanged(false)
    }

    private fun applyTransform(filter: SurfaceFilterRender, bounds: NormalizedRect) {
        val safe = bounds.constrained()
        filter.setScale(safe.width * 100f, safe.height * 100f)
        filter.setPosition(safe.x * 100f, safe.y * 100f)
    }

    /**
     * Aligne le buffer des surfaces filtre sur le ratio du cadre widget.
     * Cam?ra et ?cran center-cropent ensuite leur source dans ce buffer :
     * le flux n?est jamais ?tir?.
     */
    private fun syncOverlaySurfaceSizes(scene: StreamScene) {
        val (sw, sh) = surfaceSizeForBounds(scene.screen.bounds)
        screenFilter?.let { filter ->
            runCatching { filter.surfaceTexture.setDefaultBufferSize(sw, sh) }
        }
        val (cw, ch) = surfaceSizeForBounds(scene.camera.bounds)
        cameraFilter?.let { filter ->
            runCatching { filter.surfaceTexture.setDefaultBufferSize(cw, ch) }
        }
        scene.images.forEach { image ->
            val (iw, ih) = surfaceSizeForBounds(image.bounds)
            imageFilters[image.id]?.let { filter ->
                runCatching { filter.surfaceTexture.setDefaultBufferSize(iw, ih) }
            }
            imageRenderers[image.id]?.resizeBuffer(iw, ih)
        }
        scene.nativeWidgets.forEach { widget ->
            val (width, height) = surfaceSizeForBounds(widget.bounds)
            nativeWidgetFilters[widget.id]?.let { filter ->
                runCatching { filter.surfaceTexture.setDefaultBufferSize(width, height) }
            }
            nativeWidgetRenderers[widget.id]?.resizeBuffer(width, height)
            mediaPipelines[widget.id]?.resizeBuffer(width, height)
        }
    }

    private fun surfaceSizeForBounds(bounds: NormalizedRect): Pair<Int, Int> {
        val aspect = bounds.pixelAspect(OUTPUT_WIDTH, OUTPUT_HEIGHT).coerceAtLeast(0.05f)
        val maxSide = OVERLAY_SURFACE_MAX_SIDE
        return if (aspect >= 1f) {
            val w = maxSide
            val h = (maxSide / aspect).toInt().coerceAtLeast(2)
            w to h
        } else {
            val h = maxSide
            val w = (maxSide * aspect).toInt().coerceAtLeast(2)
            w to h
        }
    }

    private fun startForegroundFor(scene: StreamScene, text: String) {
        val serviceTypes = if (AndroidCapabilities.supportsMediaProjectionForegroundServiceType()) {
            var result = 0
            if (scene.screen.enabled) result = result or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (AndroidCapabilities.supportsCameraAndMicrophoneForegroundServiceTypes()) {
                if (scene.camera.enabled) result = result or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                if (scene.microphoneEnabled) result = result or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            result
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, createNotification(text), serviceTypes)
    }

    private fun startForegroundForScreenPreview(text: String) {
        val serviceTypes = if (AndroidCapabilities.supportsMediaProjectionForegroundServiceType()) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
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
        if (AndroidCapabilities.supportsNotificationChannels()) {
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
            imageFilters.clear()
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        currentStatus = "ERREUR"
        listener?.onWarning(message)
        listener?.onStateChanged(false, currentStatus)
    }

    private fun failScreenPreview(message: String) {
        stopScreenCapture()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        listener?.onWarning(message)
        stopSelf()
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
        const val ACTION_PREPARE_SCREEN = "fr.nicovers06.streamstudio.action.PREPARE_SCREEN"
        const val ACTION_START = "fr.nicovers06.streamstudio.action.START_STREAM"
        const val ACTION_STOP = "fr.nicovers06.streamstudio.action.STOP_STREAM"
        const val EXTRA_ENDPOINT = "endpoint"
        const val EXTRA_SCENE_JSON = "scene_json"
        const val EXTRA_PROJECTION_RESULT = "projection_result"
        const val EXTRA_PROJECTION_DATA = "projection_data"
        const val EXTRA_CHAT_PLATFORM = "chat_platform"
        const val EXTRA_TWITCH_CHANNEL = "twitch_channel"
        const val EXTRA_TWITCH_LOGIN = "twitch_login"
        const val EXTRA_TWITCH_OAUTH = "twitch_oauth"
        const val EXTRA_YOUTUBE_VIDEO_ID = "youtube_video_id"
        const val EXTRA_YOUTUBE_ACCESS_TOKEN = "youtube_access_token"

        fun liveChatConfigFromIntent(intent: Intent): LiveChatConfig {
            val platform = runCatching {
                LiveChatPlatform.valueOf(intent.getStringExtra(EXTRA_CHAT_PLATFORM).orEmpty())
            }.getOrDefault(LiveChatPlatform.NONE)
            return LiveChatConfig(
                platform = platform,
                twitchChannel = intent.getStringExtra(EXTRA_TWITCH_CHANNEL).orEmpty(),
                twitchLogin = intent.getStringExtra(EXTRA_TWITCH_LOGIN).orEmpty(),
                twitchOAuthToken = intent.getStringExtra(EXTRA_TWITCH_OAUTH).orEmpty(),
                youtubeVideoId = intent.getStringExtra(EXTRA_YOUTUBE_VIDEO_ID).orEmpty(),
                youtubeAccessToken = intent.getStringExtra(EXTRA_YOUTUBE_ACCESS_TOKEN).orEmpty(),
            )
        }

        private const val NOTIFICATION_CHANNEL_ID = "stream_broadcast"
        private const val NOTIFICATION_ID = 6106
        private const val OUTPUT_WIDTH = 1280
        private const val OUTPUT_HEIGHT = 720
        private const val OUTPUT_FPS = 30
        private const val VIDEO_BITRATE = 4_500_000
        private const val AUDIO_SAMPLE_RATE = 44_100
        private const val AUDIO_BITRATE = 128_000
        private const val OVERLAY_SURFACE_MAX_SIDE = 720
        private const val CAMERA_SURFACE_WIDTH = 640
        private const val CAMERA_SURFACE_HEIGHT = 360
    }
}
