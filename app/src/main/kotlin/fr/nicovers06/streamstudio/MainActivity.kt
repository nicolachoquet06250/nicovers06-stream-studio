package fr.nicovers06.streamstudio

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.net.Uri
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.InputType
import android.view.DragEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import fr.nicovers06.streamstudio.data.SceneImageStore
import fr.nicovers06.streamstudio.data.SceneMediaStore
import fr.nicovers06.streamstudio.data.SceneRepository
import fr.nicovers06.streamstudio.databinding.ActivityMainBinding
import fr.nicovers06.streamstudio.model.CameraFacing
import fr.nicovers06.streamstudio.model.ImageComponent
import fr.nicovers06.streamstudio.model.LayerRef
import fr.nicovers06.streamstudio.model.NativeWidgetComponent
import fr.nicovers06.streamstudio.model.ChatComponent
import fr.nicovers06.streamstudio.model.ChatMessage
import fr.nicovers06.streamstudio.model.StreamScene
import fr.nicovers06.streamstudio.model.WidgetModule
import fr.nicovers06.streamstudio.model.WidgetModules
import fr.nicovers06.streamstudio.model.WidgetType
import fr.nicovers06.streamstudio.platform.AndroidCapabilities
import fr.nicovers06.streamstudio.stream.StreamService
import fr.nicovers06.streamstudio.stream.chat.LiveChatConfig
import fr.nicovers06.streamstudio.stream.chat.LiveChatPlatform
import fr.nicovers06.streamstudio.stream.chat.TwitchIrcChatClient
import fr.nicovers06.streamstudio.stream.chat.YouTubeLiveChatClient
import fr.nicovers06.streamstudio.ui.ComponentBoundsView
import fr.nicovers06.streamstudio.ui.NativeWidgetControlView
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private data class PlatformPreset(val label: String, val server: String)
    private data class PendingBroadcast(val endpoint: String, val scene: StreamScene)
    private enum class PermissionPurpose { CAMERA_PREVIEW, START_STREAM }

    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: SceneRepository
    private val mainHandler = Handler(Looper.getMainLooper())
    private val saveScenes = Runnable { repository.save(scenes) }
    private val mediaImportExecutor = Executors.newSingleThreadExecutor()
    private var scenes = mutableListOf<StreamScene>()
    private var selectedSceneIndex = 0
    private var rendering = false
    private var uiStarted = false
    private var serviceBindingRequested = false
    private var service: StreamService? = null
    private var pendingBroadcast: PendingBroadcast? = null
    private var screenCapturePrepared = false
    private var screenCapturePreparing = false
    private var permissionPurpose: PermissionPurpose? = null
    private var streaming = false
    private var addableWidgetModules: List<WidgetModule> = emptyList()
    private var reorderingLayers = false
    private val imageBoundsViews = linkedMapOf<String, ComponentBoundsView>()
    private val imageWidgetBlocks = linkedMapOf<String, LinearLayout>()
    private val nativeWidgetBoundsViews = linkedMapOf<String, ComponentBoundsView>()
    private val nativeWidgetBlocks = linkedMapOf<String, NativeWidgetControlView>()
    private var pendingImagePickId: String? = null
    private var pendingMediaPickId: String? = null

    private fun runWhenStarted(block: () -> Unit) {
        runOnUiThread callback@{
            if (!uiStarted || isFinishing || isDestroyed) return@callback
            block()
        }
    }

    private val streamListener = object : StreamService.Listener {
        override fun onStateChanged(isStreaming: Boolean, status: String) = runWhenStarted {
            streaming = isStreaming
            updateStreamingUi(status)
        }

        override fun onScreenCaptureChanged(isReady: Boolean) = runWhenStarted {
            screenCapturePrepared = isReady
            screenCapturePreparing = false
            updateScreenSelectionUi()
        }

        override fun onBitrateChanged(bitsPerSecond: Long) = runWhenStarted {
            if (streaming) {
                val megabits = bitsPerSecond / 1_000_000.0
                binding.statusText.text = String.format(Locale.FRANCE, "EN DIRECT · %.1f Mb/s", megabits)
            }
        }

        override fun onWarning(message: String) = runWhenStarted { showMessage(message) }

        override fun onLiveChatMessages(messages: List<ChatMessage>) = runWhenStarted {
            // Overlay is updated by the service; toast only on first batch if needed.
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val localService = (binder as StreamService.LocalBinder).service
            if (!uiStarted || !serviceBindingRequested) {
                localService.clearListener(streamListener)
                return
            }
            service = localService
            localService.setListener(streamListener)
            val scene = currentScene()
            val cameraPermissionMissing = scene.camera.enabled &&
                ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.CAMERA,
                ) != PackageManager.PERMISSION_GRANTED
            // La demande runtime appartient à l'activité : le service ne doit pas tenter
            // d'ouvrir la caméra pendant que son résultat est encore en attente.
            localService.applyScene(
                if (cameraPermissionMissing) {
                    scene.copy(camera = scene.camera.copy(enabled = false))
                } else {
                    scene
                },
            )
            localService.attachPreview(binding.streamPreview)
            binding.previewHint.visibility = View.GONE
            streaming = localService.isStreaming()
            updateStreamingUi(if (streaming) "EN DIRECT" else "PRÊT")
            if (cameraPermissionMissing) {
                ensureCameraPermissionForPreview()
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            screenCapturePrepared = false
            screenCapturePreparing = false
            if (uiStarted && !isFinishing && !isDestroyed) {
                binding.previewHint.visibility = View.VISIBLE
                updateScreenSelectionUi()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        configureImmersiveMode()

        repository = SceneRepository(this)
        scenes = repository.load()
        selectedSceneIndex = savedInstanceState?.getInt(KEY_SCENE_INDEX, 0)
            ?.coerceIn(0, scenes.lastIndex) ?: 0

        setupSceneSelector()
        setupPlatformSelector()
        setupActions()
        setupWidgetLayerDrag()
        refreshAddWidgetSpinner()
        renderScene()
        updateChatPlatformFields()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onStart() {
        super.onStart()
        uiStarted = true
        if (!serviceBindingRequested) {
            serviceBindingRequested = runCatching {
                bindService(Intent(this, StreamService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
            }.getOrDefault(false)
            if (!serviceBindingRequested) showMessage("Service d’aperçu indisponible")
        }
    }

    override fun onStop() {
        uiStarted = false
        mainHandler.removeCallbacks(saveScenes)
        repository.save(scenes)
        service?.detachPreview(binding.streamPreview)
        service?.clearListener(streamListener)
        if (serviceBindingRequested) {
            runCatching { unbindService(serviceConnection) }
        }
        serviceBindingRequested = false
        service = null
        super.onStop()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        mediaImportExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        hideSystemBars()
        binding.responsiveStudioLayout.requestLayout()
        binding.previewFrame.requestLayout()
    }

    private fun configureImmersiveMode() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, windowInsets ->
            val safeInsets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            val imeInsets = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(
                safeInsets.left,
                safeInsets.top,
                safeInsets.right,
                maxOf(safeInsets.bottom, imeInsets.bottom),
            )
            windowInsets
        }
        ViewCompat.requestApplyInsets(binding.root)
        hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(KEY_SCENE_INDEX, selectedSceneIndex)
        super.onSaveInstanceState(outState)
    }

    private fun setupSceneSelector() {
        refreshSceneAdapter()
        binding.sceneSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (rendering || position !in scenes.indices) return
                selectedSceneIndex = position
                renderScene()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun setupPlatformSelector() {
        binding.platformSpinner.adapter = spinnerAdapter(PLATFORMS.map { it.label })
        binding.platformSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val preset = PLATFORMS.getOrNull(position) ?: return
                if (preset.server.isNotBlank()) binding.serverInput.setText(preset.server)
                if (preset.label == "Personnalisé" && !rendering) binding.serverInput.requestFocus()
                updateChatPlatformFields()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun setupActions() = with(binding) {
        addSceneButton.setOnClickListener { showAddSceneDialog() }
        deleteSceneButton.setOnClickListener { deleteCurrentScene() }
        addWidgetButton.setOnClickListener { addSelectedWidget() }

        screenSwitch.setOnCheckedChangeListener { _, enabled ->
            if (!rendering) {
                if (!enabled) {
                    screenCapturePrepared = false
                    screenCapturePreparing = false
                }
                updateScene { it.copy(screen = it.screen.copy(enabled = enabled)) }
            }
        }
        selectScreenButton.setOnClickListener { requestScreenSelection() }
        microphoneSwitch.setOnCheckedChangeListener { _, enabled ->
            if (!rendering) updateScene { it.copy(microphoneEnabled = enabled) }
        }
        cameraSwitch.setOnCheckedChangeListener { _, enabled ->
            if (rendering) return@setOnCheckedChangeListener
            updateScene { it.copy(camera = it.camera.copy(enabled = enabled)) }
            if (enabled) ensureCameraPermissionForPreview()
        }
        blurSwitch.setOnCheckedChangeListener { _, enabled ->
            if (!rendering) updateScene { it.copy(camera = it.camera.copy(backgroundBlur = enabled)) }
        }
        screenKeepAspectSwitch.setOnCheckedChangeListener { _, enabled ->
            if (!rendering) updateScene { it.copy(screen = it.screen.copy(keepAspectRatio = enabled)) }
        }
        cameraKeepAspectSwitch.setOnCheckedChangeListener { _, enabled ->
            if (!rendering) updateScene { it.copy(camera = it.camera.copy(keepAspectRatio = enabled)) }
        }
        chatSwitch.setOnCheckedChangeListener { _, enabled ->
            if (!rendering) updateScene { it.copy(chat = it.chat.copy(enabled = enabled)) }
        }
        frontCameraButton.setOnClickListener { selectCameraFacing(CameraFacing.FRONT) }
        backCameraButton.setOnClickListener { selectCameraFacing(CameraFacing.BACK) }
        addChatMessageButton.setOnClickListener { addPreviewChatMessage() }
        connectLiveChatButton.setOnClickListener { connectLiveChat() }
        streamButton.setOnClickListener {
            if (streaming || service?.isStreaming() == true) {
                service?.stopBroadcast() ?: startService(
                    Intent(this@MainActivity, StreamService::class.java).setAction(StreamService.ACTION_STOP),
                )
                streaming = false
                updateStreamingUi("PRÊT")
            } else {
                prepareBroadcast()
            }
        }
    }

    private fun renderScene() {
        val scene = currentScene()
        rendering = true
        binding.sceneSpinner.setSelection(selectedSceneIndex, false)
        binding.screenSwitch.isChecked = scene.screen.enabled
        binding.microphoneSwitch.isChecked = scene.microphoneEnabled
        binding.cameraSwitch.isChecked = scene.camera.enabled
        binding.blurSwitch.isChecked = scene.camera.backgroundBlur
        binding.screenKeepAspectSwitch.isChecked = scene.screen.keepAspectRatio
        binding.cameraKeepAspectSwitch.isChecked = scene.camera.keepAspectRatio
        binding.chatSwitch.isChecked = scene.chat.enabled
        binding.cameraOptions.visibility = if (scene.camera.enabled) View.VISIBLE else View.GONE
        binding.chatOptions.visibility = if (scene.chat.enabled) View.VISIBLE else View.GONE
        binding.frontCameraButton.isSelected = scene.camera.facing == CameraFacing.FRONT
        binding.backCameraButton.isSelected = scene.camera.facing == CameraFacing.BACK
        binding.screenBounds.bind(
            scene.screen.bounds,
            scene.screen.enabled,
            "Partage d’écran",
            resizeFromTopRight = true,
            keepAspectRatio = scene.screen.keepAspectRatio,
        ) { bounds ->
            updateScene(debounceSave = true, render = false) {
                it.copy(screen = it.screen.copy(bounds = bounds))
            }
        }
        binding.cameraBounds.bind(
            scene.camera.bounds,
            scene.camera.enabled,
            "Caméra",
            keepAspectRatio = scene.camera.keepAspectRatio,
        ) { bounds ->
            updateScene(debounceSave = true, render = false) {
                it.copy(camera = it.camera.copy(bounds = bounds))
            }
        }
        binding.chatBounds.bind(scene.chat.bounds, scene.chat.enabled, "Chat du stream") { bounds ->
            updateScene(debounceSave = true, render = false) {
                it.copy(chat = it.chat.copy(bounds = bounds))
            }
        }
        syncImageWidgets(scene)
        syncNativeWidgets(scene)
        applyWidgetStackOrder(scene.normalizedLayerOrder())
        applyPreviewLayerOrder(scene.normalizedLayerOrder())
        rendering = false
        service?.applyScene(scene)
        if (!scene.screen.enabled && !streaming &&
            (screenCapturePrepared || screenCapturePreparing || service?.hasScreenCapture() == true)
        ) {
            screenCapturePrepared = false
            screenCapturePreparing = false
            service?.stopScreenPreview()
        }
        updateActiveWidgetCount(scene)
        refreshAddWidgetSpinner()
        updateStreamingUi(binding.statusText.text.toString())
    }

    private fun refreshAddWidgetSpinner() {
        val scene = currentScene()
        addableWidgetModules = WidgetModules.availableToAdd(scene)
        val labels = if (addableWidgetModules.isEmpty()) {
            listOf(getString(R.string.add_widget_none_available))
        } else {
            addableWidgetModules.map { module ->
                val limit = if (module.maxInstancesPerScene == Int.MAX_VALUE) {
                    getString(R.string.widget_unlimited_hint)
                } else {
                    getString(R.string.widget_max_hint, module.maxInstancesPerScene)
                }
                "${module.label} ($limit)"
            }
        }
        binding.addWidgetSpinner.adapter = spinnerAdapter(labels)
        binding.addWidgetSpinner.isEnabled = addableWidgetModules.isNotEmpty() && !streaming
        binding.addWidgetButton.isEnabled = addableWidgetModules.isNotEmpty() && !streaming
    }

    private fun addSelectedWidget() {
        if (streaming || rendering) return
        val module = addableWidgetModules.getOrNull(binding.addWidgetSpinner.selectedItemPosition)
        if (module == null) {
            showMessage(getString(R.string.add_widget_none_available))
            return
        }
        val scene = currentScene()
        if (!WidgetModules.canAdd(scene, module.type)) {
            val message = if (module.maxInstancesPerScene == Int.MAX_VALUE) {
                getString(R.string.add_widget_unlimited_reached, module.label)
            } else {
                getString(
                    R.string.add_widget_max_reached,
                    module.label,
                    module.maxInstancesPerScene,
                )
            }
            showMessage(message)
            refreshAddWidgetSpinner()
            return
        }
        when (module.type) {
            WidgetType.SCREEN -> updateScene {
                it.copy(
                    screen = it.screen.copy(enabled = true),
                    layerOrder = WidgetModules.bringToFront(it.layerOrder, WidgetType.SCREEN, it),
                )
            }
            WidgetType.CAMERA -> {
                updateScene {
                    it.copy(
                        camera = it.camera.copy(enabled = true),
                        layerOrder = WidgetModules.bringToFront(it.layerOrder, WidgetType.CAMERA, it),
                    )
                }
                ensureCameraPermissionForPreview()
            }
            WidgetType.CHAT -> updateScene {
                it.copy(
                    chat = it.chat.copy(enabled = true),
                    layerOrder = WidgetModules.bringToFront(it.layerOrder, WidgetType.CHAT, it),
                )
            }
            WidgetType.MICROPHONE -> updateScene {
                it.copy(
                    microphoneEnabled = true,
                    layerOrder = WidgetModules.bringToFront(it.layerOrder, WidgetType.MICROPHONE, it),
                )
            }
            WidgetType.IMAGE -> {
                val image = ImageComponent(
                    id = UUID.randomUUID().toString(),
                    displayName = getString(R.string.image_widget_default_name, WidgetModules.instanceCount(scene, WidgetType.IMAGE) + 1),
                )
                updateScene {
                    val images = (it.images + image).take(ImageComponent.MAX_PER_SCENE)
                    val next = it.copy(images = images)
                    next.copy(
                        layerOrder = WidgetModules.bringToFront(next.layerOrder, LayerRef.image(image.id), next),
                    )
                }
                openImagePicker(image.id)
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
                val widget = NativeWidgetComponent.create(
                    module.type,
                    WidgetModules.instanceCount(scene, module.type) + 1,
                )
                updateScene {
                    val next = it.copy(
                        nativeWidgets = StreamScene.sanitizeNativeWidgets(it.nativeWidgets + widget),
                    )
                    if (widget.type == WidgetType.BACKGROUND) {
                        next.copy(layerOrder = next.normalizedLayerOrder())
                    } else {
                        next.copy(
                            layerOrder = WidgetModules.bringToFront(
                                next.layerOrder,
                                LayerRef.instance(widget.type, widget.id),
                                next,
                            ),
                        )
                    }
                }
                if (widget.type == WidgetType.MEDIA) openMediaPicker(widget.id)
            }
        }
        showMessage(getString(R.string.add_widget_added, module.label))
    }

    private fun setupWidgetLayerDrag() {
        binding.widgetStack.setOnDragListener { stack, event ->
            val key = event.localState as? String ?: return@setOnDragListener false
            val dragged = LayerRef.parse(key) ?: return@setOnDragListener false
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> true
                DragEvent.ACTION_DRAG_LOCATION -> {
                    val targetIndex = insertIndexForY(stack as LinearLayout, event.y)
                    moveWidgetBlock(stack as LinearLayout, dragged, targetIndex)
                    true
                }
                DragEvent.ACTION_DROP -> {
                    commitWidgetLayerOrderFromStack()
                    true
                }
                DragEvent.ACTION_DRAG_ENDED -> {
                    widgetBlock(dragged)?.alpha = 1f
                    if (event.result) {
                        commitWidgetLayerOrderFromStack()
                    } else {
                        applyWidgetStackOrder(currentScene().normalizedLayerOrder())
                    }
                    true
                }
                else -> true
            }
        }
    }

    private fun attachLayerHandle(handle: View, ref: LayerRef) {
        if (ref.type == WidgetType.BACKGROUND) {
            handle.setOnTouchListener(null)
            return
        }
        handle.setOnTouchListener { view, event ->
            if (event.actionMasked != MotionEvent.ACTION_DOWN) return@setOnTouchListener false
            val block = widgetBlock(ref) ?: return@setOnTouchListener false
            val clip = ClipData.newPlainText("widgetLayer", ref.storageKey())
            val shadow = View.DragShadowBuilder(block)
            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                view.startDragAndDrop(clip, shadow, ref.storageKey(), 0)
            } else {
                @Suppress("DEPRECATION")
                view.startDrag(clip, shadow, ref.storageKey(), 0)
            }
            if (started) {
                block.alpha = 0.45f
            }
            started
        }
    }

    private fun widgetBlock(ref: LayerRef): View? = when (ref.type) {
        WidgetType.SCREEN -> binding.screenWidgetBlock
        WidgetType.CAMERA -> binding.cameraWidgetBlock
        WidgetType.CHAT -> binding.chatWidgetBlock
        WidgetType.MICROPHONE -> binding.microphoneWidgetBlock
        WidgetType.IMAGE -> imageWidgetBlocks[ref.instanceId]
        WidgetType.TIMER,
        WidgetType.SHAPE,
        WidgetType.BACKGROUND,
        WidgetType.TICKER,
        WidgetType.MEDIA,
        WidgetType.ALERT,
        WidgetType.POLL,
        WidgetType.TEXT,
        -> nativeWidgetBlocks[ref.instanceId]
    }

    private fun boundsView(ref: LayerRef): View? = when (ref.type) {
        WidgetType.SCREEN -> binding.screenBounds
        WidgetType.CAMERA -> binding.cameraBounds
        WidgetType.CHAT -> binding.chatBounds
        WidgetType.MICROPHONE -> null
        WidgetType.IMAGE -> imageBoundsViews[ref.instanceId]
        WidgetType.TIMER,
        WidgetType.SHAPE,
        WidgetType.TICKER,
        WidgetType.MEDIA,
        WidgetType.ALERT,
        WidgetType.POLL,
        WidgetType.TEXT,
        -> nativeWidgetBoundsViews[ref.instanceId]
        WidgetType.BACKGROUND -> null
    }

    private fun insertIndexForY(stack: LinearLayout, y: Float): Int {
        if (stack.childCount == 0) return 0
        var acc = 0
        for (index in 0 until stack.childCount) {
            val child = stack.getChildAt(index)
            val height = child.height.takeIf { it > 0 } ?: child.measuredHeight
            val mid = acc + height / 2f
            if (y < mid) return index
            acc += height
        }
        return stack.childCount - 1
    }

    private fun moveWidgetBlock(stack: LinearLayout, ref: LayerRef, targetIndex: Int) {
        if (ref.type == WidgetType.BACKGROUND) return
        val block = widgetBlock(ref) ?: return
        val from = stack.indexOfChild(block)
        if (from < 0) return
        val backgroundIndex = (0 until stack.childCount).firstOrNull { index ->
            val layer = LayerRef.parse(stack.getChildAt(index).tag as? String ?: return@firstOrNull false)
            layer?.type == WidgetType.BACKGROUND
        }
        val lastAllowedIndex = if (backgroundIndex == null) {
            (stack.childCount - 1).coerceAtLeast(0)
        } else {
            (backgroundIndex - 1).coerceAtLeast(0)
        }
        val desired = targetIndex.coerceIn(0, lastAllowedIndex)
        if (from == desired) return
        stack.removeView(block)
        stack.addView(block, desired.coerceAtMost(stack.childCount))
    }

    private fun readWidgetStackOrder(): List<LayerRef> {
        val stack = binding.widgetStack
        return buildList {
            for (index in 0 until stack.childCount) {
                val tag = stack.getChildAt(index).tag as? String
                LayerRef.parse(tag.orEmpty())?.let { add(it) }
            }
        }.let { WidgetModules.normalizeLayerOrder(it, currentScene()) }
    }

    private fun commitWidgetLayerOrderFromStack() {
        if (reorderingLayers || rendering) return
        val newOrder = readWidgetStackOrder()
        val current = currentScene().normalizedLayerOrder()
        if (newOrder == current) return
        reorderingLayers = true
        updateScene { it.copy(layerOrder = newOrder) }
        reorderingLayers = false
    }

    private fun applyWidgetStackOrder(orderFrontFirst: List<LayerRef>) {
        val stack = binding.widgetStack
        val scene = currentScene()
        syncImageWidgetBlocks(scene)
        syncNativeWidgetBlocks(scene)
        val normalized = WidgetModules.normalizeLayerOrder(orderFrontFirst, scene)
        normalized.forEachIndexed { index, ref ->
            val block = widgetBlock(ref) ?: return@forEachIndexed
            val currentIndex = stack.indexOfChild(block)
            if (currentIndex != index) {
                stack.removeView(block)
                stack.addView(block, index.coerceAtMost(stack.childCount))
            }
        }
    }

    private fun applyPreviewLayerOrder(orderFrontFirst: List<LayerRef>) {
        val scene = currentScene()
        WidgetModules.visualLayerOrder(orderFrontFirst, scene)
            .asReversed()
            .forEach { ref -> boundsView(ref)?.bringToFront() }
    }

    private fun selectCameraFacing(facing: CameraFacing) {
        if (currentScene().camera.facing == facing) return
        updateScene { it.copy(camera = it.camera.copy(facing = facing)) }
    }

    private fun updateActiveWidgetCount(scene: StreamScene) {
        val count = WidgetModules.all.sumOf { WidgetModules.instanceCount(scene, it.type) }
        binding.activeWidgetsText.text = resources.getQuantityString(R.plurals.active_widgets_count, count, count)
    }

    private fun updateScene(
        debounceSave: Boolean = false,
        render: Boolean = true,
        transform: (StreamScene) -> StreamScene,
    ) {
        scenes[selectedSceneIndex] = transform(currentScene())
        if (debounceSave) {
            mainHandler.removeCallbacks(saveScenes)
            mainHandler.postDelayed(saveScenes, 250)
        } else {
            repository.save(scenes)
        }
        service?.applyScene(currentScene())
        if (render) renderScene()
    }

    private fun showAddSceneDialog() {
        val input = EditText(this).apply {
            hint = "Nom de la scène"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(getString(R.string.numbered_scene, scenes.size + 1))
            setSelectAllOnFocus(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Nouvelle scène")
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Créer") { _, _ ->
                val name = input.text.toString().trim().ifBlank { getString(R.string.numbered_scene, scenes.size + 1) }
                scenes.add(StreamScene(name = name))
                selectedSceneIndex = scenes.lastIndex
                repository.save(scenes)
                refreshSceneAdapter()
                renderScene()
                ensureCameraPermissionForPreview()
            }
            .show()
    }

    private fun deleteCurrentScene() {
        if (scenes.size == 1) {
            showMessage("Il faut conserver au moins une scène")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Supprimer ${currentScene().name} ?")
            .setMessage("Cette action supprime uniquement la configuration locale de la scène.")
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Supprimer") { _, _ ->
                scenes.removeAt(selectedSceneIndex)
                selectedSceneIndex = selectedSceneIndex.coerceAtMost(scenes.lastIndex)
                repository.save(scenes)
                refreshSceneAdapter()
                renderScene()
            }
            .show()
    }

    private fun refreshSceneAdapter() {
        rendering = true
        binding.sceneSpinner.adapter = spinnerAdapter(scenes.map { it.name })
        binding.sceneSpinner.setSelection(selectedSceneIndex, false)
        rendering = false
    }

    private fun addPreviewChatMessage() {
        val text = binding.chatMessageInput.text.toString().trim()
        if (text.isBlank()) {
            showMessage("Saisissez un message de test")
            return
        }
        val author = binding.chatAuthorInput.text.toString().trim().ifBlank { "viewer" }
        updateScene {
            val message = ChatMessage(author, text, CHAT_ACCENTS[it.chat.messages.size % CHAT_ACCENTS.size])
            it.copy(chat = it.chat.copy(messages = (it.chat.messages + message).takeLast(ChatComponent.MAX_MESSAGES)))
        }
        binding.chatMessageInput.text.clear()
    }

    private fun selectedLiveChatPlatform(): LiveChatPlatform = when (binding.platformSpinner.selectedItemPosition) {
        0 -> LiveChatPlatform.TWITCH
        1 -> LiveChatPlatform.YOUTUBE
        else -> LiveChatPlatform.NONE
    }

    private fun buildLiveChatConfig(): LiveChatConfig = LiveChatConfig(
        platform = selectedLiveChatPlatform(),
        twitchChannel = binding.twitchChannelInput.text.toString(),
        twitchLogin = binding.twitchLoginInput.text.toString(),
        twitchOAuthToken = binding.twitchOAuthInput.text.toString(),
        youtubeVideoId = binding.youtubeVideoInput.text.toString(),
        youtubeAccessToken = binding.youtubeAccessTokenInput.text.toString(),
    )

    private fun updateChatPlatformFields() = with(binding) {
        val platform = selectedLiveChatPlatform()
        val showTwitch = platform == LiveChatPlatform.TWITCH
        val showYoutube = platform == LiveChatPlatform.YOUTUBE
        twitchChannelInput.visibility = if (showTwitch) View.VISIBLE else View.GONE
        twitchLoginInput.visibility = if (showTwitch) View.VISIBLE else View.GONE
        twitchOAuthInput.visibility = if (showTwitch) View.VISIBLE else View.GONE
        youtubeVideoInput.visibility = if (showYoutube) View.VISIBLE else View.GONE
        youtubeAccessTokenInput.visibility = if (showYoutube) View.VISIBLE else View.GONE
        chatLiveHelp.setText(
            when (platform) {
                LiveChatPlatform.TWITCH -> R.string.chat_live_help_twitch
                LiveChatPlatform.YOUTUBE -> R.string.chat_live_help_youtube
                LiveChatPlatform.NONE -> R.string.chat_live_help_custom
            },
        )
        connectLiveChatButton.isEnabled = platform != LiveChatPlatform.NONE
    }

    private fun connectLiveChat() {
        if (!currentScene().chat.enabled) {
            showMessage("Activez d’abord le bloc de chat")
            return
        }
        val config = buildLiveChatConfig()
        when (config.platform) {
            LiveChatPlatform.TWITCH -> {
                if (TwitchIrcChatClient.normalizeChannel(config.twitchChannel).isEmpty()) {
                    showMessage("Indiquez le nom de la chaîne Twitch")
                    return
                }
            }
            LiveChatPlatform.YOUTUBE -> {
                if (YouTubeLiveChatClient.normalizeVideoId(config.youtubeVideoId).isEmpty()) {
                    showMessage("Indiquez l’ID ou l’URL de la vidéo YouTube live")
                    return
                }
                if (config.youtubeAccessToken.isBlank()) {
                    showMessage("Collez un jeton OAuth YouTube (youtube.readonly)")
                    return
                }
            }
            LiveChatPlatform.NONE -> {
                showMessage("Choisissez Twitch ou YouTube pour le chat réel")
                return
            }
        }
        val svc = service
        if (svc == null) {
            showMessage("Service d’aperçu indisponible")
            return
        }
        svc.configureLiveChat(config)
        showMessage("Connexion au chat plateforme…")
    }

    private fun Intent.putLiveChatExtras(config: LiveChatConfig): Intent {
        putExtra(StreamService.EXTRA_CHAT_PLATFORM, config.platform.name)
        putExtra(StreamService.EXTRA_TWITCH_CHANNEL, config.twitchChannel)
        putExtra(StreamService.EXTRA_TWITCH_LOGIN, config.twitchLogin)
        putExtra(StreamService.EXTRA_TWITCH_OAUTH, config.twitchOAuthToken)
        putExtra(StreamService.EXTRA_YOUTUBE_VIDEO_ID, config.youtubeVideoId)
        putExtra(StreamService.EXTRA_YOUTUBE_ACCESS_TOKEN, config.youtubeAccessToken)
        return this
    }

    private fun ensureCameraPermissionForPreview() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            service?.applyScene(currentScene())
            return
        }
        permissionPurpose = PermissionPurpose.CAMERA_PREVIEW
        requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_PERMISSIONS)
    }

    private fun prepareBroadcast() {
        val scene = currentScene()
        if (!scene.hasEnabledVisualWidget()) {
            showMessage("Activez au moins une source visuelle")
            return
        }
        if (scene.screen.enabled && screenCapturePreparing) {
            showMessage("Attendez que l’aperçu du partage d’écran soit prêt")
            return
        }
        if (scene.screen.enabled && !screenCapturePrepared) {
            showMessage("Choisissez d’abord l’écran ou l’application à partager")
            binding.selectScreenButton.requestFocus()
            return
        }
        val server = binding.serverInput.text.toString().trim().trimEnd('/')
        val key = binding.streamKeyInput.text.toString().trim().trimStart('/')
        if (!(server.startsWith("rtmp://", true) || server.startsWith("rtmps://", true))) {
            showMessage("L’URL doit commencer par rtmp:// ou rtmps://")
            return
        }
        if (key.isBlank()) {
            showMessage("Saisissez la clé de stream")
            return
        }
        pendingBroadcast = PendingBroadcast("$server/$key", scene)

        val missing = buildList {
            if (scene.camera.enabled && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.CAMERA)
            }
            if (scene.microphoneEnabled && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.RECORD_AUDIO)
            }
            if (AndroidCapabilities.requiresNotificationPermission() &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (missing.isNotEmpty()) {
            permissionPurpose = PermissionPurpose.START_STREAM
            requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
        } else {
            startForegroundBroadcast()
        }
    }

    private fun requestScreenSelection() {
        if (streaming || !currentScene().screen.enabled) return
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_SCREEN_SELECTION)
    }

    private fun startForegroundBroadcast() {
        val pending = pendingBroadcast ?: return
        if (pending.scene.screen.enabled && !screenCapturePrepared) {
            pendingBroadcast = null
            showMessage("La source de partage d’écran n’est plus disponible. Sélectionnez-la à nouveau.")
            updateScreenSelectionUi()
            return
        }
        val intent = Intent(this, StreamService::class.java)
            .setAction(StreamService.ACTION_START)
            .putExtra(StreamService.EXTRA_ENDPOINT, pending.endpoint)
            .putExtra(StreamService.EXTRA_SCENE_JSON, pending.scene.toJson().toString())
            .putLiveChatExtras(buildLiveChatConfig())
        ContextCompat.startForegroundService(this, intent)
        pendingBroadcast = null
        streaming = true
        updateStreamingUi("CONNEXION…")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return
        val purpose = permissionPurpose
        permissionPurpose = null

        if (purpose == PermissionPurpose.CAMERA_PREVIEW) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                service?.applyScene(currentScene())
            } else {
                updateScene { it.copy(camera = it.camera.copy(enabled = false)) }
                showMessage("La caméra a été désactivée faute d’autorisation")
            }
            return
        }

        val scene = pendingBroadcast?.scene ?: return
        val cameraReady = !scene.camera.enabled || ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val microphoneReady = !scene.microphoneEnabled || ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (cameraReady && microphoneReady) {
            startForegroundBroadcast()
        } else {
            pendingBroadcast = null
            showMessage("Les autorisations caméra/microphone requises ont été refusées")
        }
    }

    @Deprecated("MediaProjection still uses an activity result Intent on all supported Android versions")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK_IMAGE) {
            handleImagePickResult(resultCode, data)
            return
        }
        if (requestCode == REQUEST_PICK_MEDIA) {
            handleMediaPickResult(resultCode, data)
            return
        }
        if (requestCode != REQUEST_SCREEN_SELECTION) return
        if (resultCode == RESULT_OK && data != null) {
            screenCapturePreparing = true
            updateScreenSelectionUi()
            val intent = Intent(this, StreamService::class.java)
                .setAction(StreamService.ACTION_PREPARE_SCREEN)
                .putExtra(StreamService.EXTRA_SCENE_JSON, currentScene().toJson().toString())
                .putExtra(StreamService.EXTRA_PROJECTION_RESULT, RESULT_OK)
                .putExtra(StreamService.EXTRA_PROJECTION_DATA, data)
            runCatching { ContextCompat.startForegroundService(this, intent) }
                .onFailure {
                    screenCapturePreparing = false
                    updateScreenSelectionUi()
                    showMessage("Impossible d'ouvrir l'aper?u du partage d'?cran")
                }
        } else {
            showMessage("S?lection du partage d'?cran annul?e")
        }
    }

    private fun openImagePicker(imageId: String) {
        pendingImagePickId = imageId
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "image/jpeg", "image/png", "image/webp", "image/gif",
                "image/bmp", "image/heic", "image/heif", "image/avif", "image/*",
            ))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivityForResult(intent, REQUEST_PICK_IMAGE) }
            .onFailure {
                pendingImagePickId = null
                showMessage(getString(R.string.image_pick_failed))
            }
    }

    private fun handleImagePickResult(resultCode: Int, data: Intent?) {
        val imageId = pendingImagePickId
        pendingImagePickId = null
        if (imageId == null) return
        if (resultCode != RESULT_OK || data?.data == null) {
            showMessage(getString(R.string.image_pick_cancelled))
            return
        }
        val uri: Uri = data.data!!
        val imported = SceneImageStore.importFromUri(this, uri)
        if (imported == null) {
            showMessage(getString(R.string.image_decode_failed))
            return
        }
        updateScene { scene ->
            val old = scene.image(imageId)
            val previousFile = old?.fileName.orEmpty()
            if (previousFile.isNotBlank() && previousFile != imported.fileName) {
                SceneImageStore.delete(this, previousFile)
            }
            scene.copy(
                images = scene.images.map { img ->
                    if (img.id != imageId) img
                    else img.copy(
                        fileName = imported.fileName,
                        displayName = imported.displayName.ifBlank { img.displayName },
                    )
                },
            )
        }
        showMessage(getString(R.string.image_pick_success))
    }

    private fun openMediaPicker(widgetId: String) {
        pendingMediaPickId = widgetId
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivityForResult(intent, REQUEST_PICK_MEDIA) }
            .onFailure {
                pendingMediaPickId = null
                showMessage(getString(R.string.native_widget_media_failed))
            }
    }

    private fun handleMediaPickResult(resultCode: Int, data: Intent?) {
        val widgetId = pendingMediaPickId
        pendingMediaPickId = null
        if (widgetId == null) return
        if (resultCode != RESULT_OK || data?.data == null) {
            showMessage(getString(R.string.native_widget_media_cancelled))
            return
        }
        val uri = data.data!!
        mediaImportExecutor.execute {
            val imported = SceneMediaStore.importFromUri(applicationContext, uri)
            runOnUiThread mediaImported@{
                if (isFinishing || isDestroyed) {
                    imported?.let { SceneMediaStore.delete(applicationContext, it.fileName) }
                    return@mediaImported
                }
                if (imported == null) {
                    showMessage(getString(R.string.native_widget_media_failed))
                    return@mediaImported
                }
                if (currentScene().nativeWidget(widgetId)?.type != WidgetType.MEDIA) {
                    SceneMediaStore.delete(this, imported.fileName)
                    return@mediaImported
                }
                updateScene { scene ->
                    val old = scene.nativeWidget(widgetId)
                    val previousFile = old?.mediaFileName.orEmpty()
                    if (previousFile.isNotBlank() && previousFile != imported.fileName) {
                        SceneMediaStore.delete(this, previousFile)
                    }
                    scene.copy(
                        nativeWidgets = scene.nativeWidgets.map { widget ->
                            if (widget.id != widgetId) widget
                            else widget.copy(
                                mediaFileName = imported.fileName,
                                mediaDisplayName = imported.displayName,
                                bounds = widget.bounds.withPixelAspectKeepingHeight(
                                    pixelAspect = imported.width.toFloat() / imported.height.toFloat(),
                                    sceneWidth = SCENE_ASPECT_WIDTH,
                                    sceneHeight = SCENE_ASPECT_HEIGHT,
                                ),
                            )
                        },
                    )
                }
                showMessage(getString(R.string.native_widget_media_success))
            }
        }
    }

    private fun syncImageWidgets(scene: StreamScene) {
        syncImageWidgetBlocks(scene)
        syncImageBoundsViews(scene)
        attachStaticLayerHandles()
    }

    private fun syncNativeWidgets(scene: StreamScene) {
        syncNativeWidgetBlocks(scene)
        syncNativeWidgetBoundsViews(scene)
    }

    private fun syncNativeWidgetBoundsViews(scene: StreamScene) {
        val preview = binding.previewFrame
        val editableWidgets = scene.nativeWidgets.filter { it.type != WidgetType.BACKGROUND }
        val liveIds = editableWidgets.map { it.id }.toSet()
        nativeWidgetBoundsViews.keys.filter { it !in liveIds }.toList().forEach { id ->
            nativeWidgetBoundsViews.remove(id)?.let { preview.removeView(it) }
        }
        editableWidgets.forEach { widget ->
            val view = nativeWidgetBoundsViews.getOrPut(widget.id) {
                ComponentBoundsView(this).also { boundsView ->
                    boundsView.layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                    )
                    preview.addView(boundsView)
                }
            }
            view.bind(
                widget.bounds,
                widget.enabled,
                nativeWidgetLabel(scene, widget),
                keepAspectRatio = widget.type == WidgetType.MEDIA && widget.mediaKeepAspectRatio,
            ) { bounds ->
                updateScene(debounceSave = true, render = false) { currentScene ->
                    currentScene.copy(
                        nativeWidgets = currentScene.nativeWidgets.map { current ->
                            if (current.id == widget.id) current.copy(bounds = bounds) else current
                        },
                    )
                }
            }
        }
    }

    private fun syncNativeWidgetBlocks(scene: StreamScene) {
        val stack = binding.widgetStack
        val liveIds = scene.nativeWidgets.map { it.id }.toSet()
        nativeWidgetBlocks.keys.filter { it !in liveIds }.toList().forEach { id ->
            nativeWidgetBlocks.remove(id)?.let { stack.removeView(it) }
        }
        scene.nativeWidgets.forEach { widget ->
            val ref = LayerRef.instance(widget.type, widget.id)
            val block = nativeWidgetBlocks.getOrPut(widget.id) {
                NativeWidgetControlView(this, widget.type).also { control ->
                    control.tag = ref.storageKey()
                    stack.addView(control)
                }
            }
            block.tag = ref.storageKey()
            attachLayerHandle(block.layerHandle, ref)
            block.bind(
                widget = widget,
                displayLabel = nativeWidgetLabel(scene, widget),
                editable = !streaming,
                runtimeActionsEnabled = true,
                callbacks = NativeWidgetControlView.Callbacks(
                    onChanged = { updated ->
                        if (!streaming || updated.type == WidgetType.TIMER || updated.type == WidgetType.ALERT) {
                            updateNativeWidget(updated)
                        }
                    },
                    onRemoved = {
                        if (!streaming) removeNativeWidget(widget.id)
                    },
                    onPickMedia = {
                        if (!streaming) openMediaPicker(widget.id)
                    },
                    onValidationError = ::showMessage,
                ),
            )
        }
    }

    private fun nativeWidgetLabel(scene: StreamScene, widget: NativeWidgetComponent): String {
        val module = WidgetModules.of(widget.type)
        val siblings = scene.nativeWidgets.filter { it.type == widget.type }
        val index = siblings.indexOfFirst { it.id == widget.id }.coerceAtLeast(0) + 1
        return if (module.maxInstancesPerScene == 1) module.label else "${module.label} $index"
    }

    private fun updateNativeWidget(updated: NativeWidgetComponent) {
        updateScene { scene ->
            scene.copy(
                nativeWidgets = scene.nativeWidgets.map { current ->
                    if (current.id != updated.id) current else updated.copy(bounds = current.bounds)
                },
            )
        }
    }

    private fun removeNativeWidget(widgetId: String) {
        updateScene { scene ->
            val target = scene.nativeWidget(widgetId)
            if (target?.mediaFileName?.isNotBlank() == true) {
                SceneMediaStore.delete(this, target.mediaFileName)
            }
            scene.copy(nativeWidgets = scene.nativeWidgets.filterNot { it.id == widgetId })
        }
    }

    private fun attachStaticLayerHandles() {
        attachLayerHandle(binding.screenLayerHandle, LayerRef.singleton(WidgetType.SCREEN))
        attachLayerHandle(binding.cameraLayerHandle, LayerRef.singleton(WidgetType.CAMERA))
        attachLayerHandle(binding.chatLayerHandle, LayerRef.singleton(WidgetType.CHAT))
        attachLayerHandle(binding.microphoneLayerHandle, LayerRef.singleton(WidgetType.MICROPHONE))
    }

    private fun syncImageBoundsViews(scene: StreamScene) {
        val preview = binding.previewFrame
        val liveIds = scene.images.map { it.id }.toSet()
        imageBoundsViews.keys.filter { it !in liveIds }.toList().forEach { id ->
            imageBoundsViews.remove(id)?.let { preview.removeView(it) }
        }
        scene.images.forEach { image ->
            val view = imageBoundsViews.getOrPut(image.id) {
                ComponentBoundsView(this).also { v ->
                    v.layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                    )
                    preview.addView(v)
                }
            }
            val label = image.displayName.ifBlank { getString(R.string.image_widget) }
            view.bind(
                image.bounds,
                image.enabled,
                label,
                keepAspectRatio = image.keepAspectRatio,
            ) { bounds ->
                updateScene(debounceSave = true, render = false) { sc ->
                    sc.copy(
                        images = sc.images.map { img ->
                            if (img.id == image.id) img.copy(bounds = bounds) else img
                        },
                    )
                }
            }
        }
    }

    private fun syncImageWidgetBlocks(scene: StreamScene) {
        val stack = binding.widgetStack
        val liveIds = scene.images.map { it.id }.toSet()
        imageWidgetBlocks.keys.filter { it !in liveIds }.toList().forEach { id ->
            imageWidgetBlocks.remove(id)?.let { stack.removeView(it) }
        }
        scene.images.forEachIndexed { index, image ->
            val block = imageWidgetBlocks.getOrPut(image.id) {
                buildImageWidgetBlock(image.id).also { stack.addView(it) }
            }
            bindImageWidgetBlock(block, image, index)
        }
    }

    private fun buildImageWidgetBlock(imageId: String): LinearLayout {
        val density = resources.displayMetrics.density
        val block = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            tag = LayerRef.image(imageId).storageKey()
        }
        block.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (1 * density).toInt(),
            )
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.border_soft))
        })
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        val handle = ImageView(this).apply {
            id = View.generateViewId()
            layoutParams = LinearLayout.LayoutParams((40 * density).toInt(), (52 * density).toInt())
            setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
            scaleType = ImageView.ScaleType.CENTER
            setImageResource(R.drawable.ic_drag_handle)
            contentDescription = getString(R.string.widget_layer_handle)
            tag = "handle"
        }
        attachLayerHandle(handle, LayerRef.image(imageId))
        val enableSwitch = Switch(this).apply {
            id = View.generateViewId()
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setText(R.string.image_widget)
            tag = "enable"
        }
        row.addView(handle)
        row.addView(enableSwitch)
        block.addView(row)

        val options = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = (10 * density).toInt() }
            setPadding((40 * density).toInt(), 0, 0, 0)
            tag = "options"
        }
        val fileLabel = TextView(this).apply {
            tag = "fileLabel"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            textSize = 11f
        }
        val pickButton = Button(this).apply {
            tag = "pick"
            text = getString(R.string.image_choose)
            isAllCaps = false
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_button_outline)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.purple_light))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (46 * density).toInt(),
            ).apply { topMargin = (8 * density).toInt() }
        }
        val keepSwitch = Switch(this).apply {
            tag = "keepAspect"
            setText(R.string.keep_aspect_ratio)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (46 * density).toInt(),
            )
        }
        val removeButton = Button(this).apply {
            tag = "remove"
            text = getString(R.string.image_remove)
            isAllCaps = false
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_button_outline)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.red))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (46 * density).toInt(),
            ).apply { topMargin = (4 * density).toInt() }
        }
        options.addView(fileLabel)
        options.addView(pickButton)
        options.addView(keepSwitch)
        options.addView(removeButton)
        block.addView(options)
        return block
    }

    private fun bindImageWidgetBlock(block: LinearLayout, image: ImageComponent, index: Int) {
        block.tag = LayerRef.image(image.id).storageKey()
        val enableSwitch = block.findViewWithTag<Switch>("enable")
        val options = block.findViewWithTag<LinearLayout>("options")
        val fileLabel = block.findViewWithTag<TextView>("fileLabel")
        val pickButton = block.findViewWithTag<Button>("pick")
        val keepSwitch = block.findViewWithTag<Switch>("keepAspect")
        val removeButton = block.findViewWithTag<Button>("remove")
        val label = getString(R.string.image_widget_numbered, index + 1)
        enableSwitch?.text = label
        if (!rendering) {
            // keep listeners; update checked state under rendering flag
        }
        val wasRendering = rendering
        rendering = true
        enableSwitch?.isChecked = image.enabled
        keepSwitch?.isChecked = image.keepAspectRatio
        rendering = wasRendering
        options?.visibility = if (image.enabled) View.VISIBLE else View.GONE
        fileLabel?.text = if (image.fileName.isBlank()) {
            getString(R.string.image_no_file)
        } else {
            getString(R.string.image_file_label, image.displayName)
        }
        enableSwitch?.setOnCheckedChangeListener { _, enabled ->
            if (!rendering) {
                updateScene { sc ->
                    sc.copy(
                        images = sc.images.map {
                            if (it.id == image.id) it.copy(enabled = enabled) else it
                        },
                    )
                }
            }
        }
        keepSwitch?.setOnCheckedChangeListener { _, enabled ->
            if (!rendering) {
                updateScene { sc ->
                    sc.copy(
                        images = sc.images.map {
                            if (it.id == image.id) it.copy(keepAspectRatio = enabled) else it
                        },
                    )
                }
            }
        }
        pickButton?.setOnClickListener {
            if (!streaming) openImagePicker(image.id)
        }
        removeButton?.setOnClickListener {
            if (streaming || rendering) return@setOnClickListener
            updateScene { sc ->
                val target = sc.image(image.id)
                if (target != null && target.fileName.isNotBlank()) {
                    SceneImageStore.delete(this, target.fileName)
                }
                sc.copy(images = sc.images.filterNot { it.id == image.id })
            }
        }
        enableSwitch?.isEnabled = !streaming
        pickButton?.isEnabled = !streaming
        keepSwitch?.isEnabled = !streaming
        removeButton?.isEnabled = !streaming
        block.findViewWithTag<View>("handle")?.let {
            attachLayerHandle(it, LayerRef.image(image.id))
        }
    }

    private fun updateStreamingUi(status: String) = with(binding) {
        statusText.text = status
        statusText.setTextColor(ContextCompat.getColor(this@MainActivity, if (status.startsWith("ERREUR")) R.color.red else R.color.green))
        streamButton.text = if (streaming) "Arrêter le stream" else getString(R.string.start_stream)

        sceneSpinner.isEnabled = !streaming
        addSceneButton.isEnabled = !streaming
        deleteSceneButton.isEnabled = !streaming
        addWidgetSpinner.isEnabled = addableWidgetModules.isNotEmpty() && !streaming
        addWidgetButton.isEnabled = addableWidgetModules.isNotEmpty() && !streaming
        screenSwitch.isEnabled = !streaming
        microphoneSwitch.isEnabled = !streaming
        cameraSwitch.isEnabled = !streaming
        chatSwitch.isEnabled = !streaming
        platformSpinner.isEnabled = !streaming
        serverInput.isEnabled = !streaming
        streamKeyInput.isEnabled = !streaming
        twitchChannelInput.isEnabled = !streaming
        twitchLoginInput.isEnabled = !streaming
        twitchOAuthInput.isEnabled = !streaming
        youtubeVideoInput.isEnabled = !streaming
        youtubeAccessTokenInput.isEnabled = !streaming
        connectLiveChatButton.isEnabled = !streaming && selectedLiveChatPlatform() != LiveChatPlatform.NONE
        blurSwitch.isEnabled = currentScene().camera.enabled
        frontCameraButton.isEnabled = currentScene().camera.enabled
        backCameraButton.isEnabled = currentScene().camera.enabled
        updateScreenSelectionUi()
        syncImageWidgetBlocks(currentScene())
        syncNativeWidgetBlocks(currentScene())
    }

    private fun updateScreenSelectionUi() = with(binding) {
        val screenEnabled = currentScene().screen.enabled
        val supportsSingleAppSharing = AndroidCapabilities.supportsSingleAppScreenSharing()
        sceneSpinner.isEnabled = !streaming && !screenCapturePreparing
        screenSwitch.isEnabled = !streaming && !screenCapturePreparing
        screenOptions.visibility = if (screenEnabled) View.VISIBLE else View.GONE
        screenCaptureHelp.setText(
            if (supportsSingleAppSharing) R.string.screen_capture_help else R.string.screen_capture_help_legacy,
        )
        selectScreenButton.isEnabled = screenEnabled && !streaming && !screenCapturePreparing
        selectScreenButton.text = getString(
            when {
                screenCapturePrepared -> R.string.change_screen_source
                supportsSingleAppSharing -> R.string.select_screen_source
                else -> R.string.select_screen_source_legacy
            },
        )

        val statusTextRes = when {
            streaming && screenEnabled -> R.string.screen_source_active
            screenCapturePreparing -> R.string.screen_source_preparing
            screenCapturePrepared -> R.string.screen_source_preview
            else -> R.string.screen_source_missing
        }
        screenSourceStatus.setText(statusTextRes)
        screenSourceStatus.visibility = if (screenEnabled) View.VISIBLE else View.GONE
        screenSourceStatus.setBackgroundResource(
            if (streaming && screenEnabled || screenCapturePrepared) R.drawable.bg_status else R.drawable.bg_status_neutral,
        )
        screenSourceStatus.setTextColor(
            ContextCompat.getColor(
                this@MainActivity,
                if (streaming && screenEnabled || screenCapturePrepared) R.color.green else R.color.text_secondary,
            ),
        )
    }

    private fun spinnerAdapter(items: List<String>): ArrayAdapter<String> =
        ArrayAdapter(this, R.layout.spinner_item, items).apply {
            setDropDownViewResource(R.layout.spinner_dropdown_item)
        }

    private fun currentScene(): StreamScene = scenes[selectedSceneIndex]

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val REQUEST_PICK_IMAGE = 4401
        private const val REQUEST_PICK_MEDIA = 4402
        private const val REQUEST_PERMISSIONS = 100
        private const val REQUEST_SCREEN_SELECTION = 101
        private const val KEY_SCENE_INDEX = "scene_index"
        private const val SCENE_ASPECT_WIDTH = 16
        private const val SCENE_ASPECT_HEIGHT = 9

        private val PLATFORMS = listOf(
            PlatformPreset("Twitch", "rtmp://live.twitch.tv/app"),
            PlatformPreset("YouTube", "rtmps://a.rtmps.youtube.com/live2"),
            PlatformPreset("Personnalisé", ""),
        )
        private val CHAT_ACCENTS = intArrayOf(
            0xFF8B5CF6.toInt(),
            0xFF34D399.toInt(),
            0xFFF59E0B.toInt(),
            0xFF60A5FA.toInt(),
        )
    }
}
