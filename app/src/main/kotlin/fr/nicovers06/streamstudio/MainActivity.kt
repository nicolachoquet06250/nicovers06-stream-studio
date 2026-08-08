package fr.nicovers06.streamstudio

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
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
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import fr.nicovers06.streamstudio.data.SceneRepository
import fr.nicovers06.streamstudio.databinding.ActivityMainBinding
import fr.nicovers06.streamstudio.model.CameraFacing
import fr.nicovers06.streamstudio.model.ChatComponent
import fr.nicovers06.streamstudio.model.ChatMessage
import fr.nicovers06.streamstudio.model.StreamScene
import fr.nicovers06.streamstudio.model.WidgetModule
import fr.nicovers06.streamstudio.model.WidgetModules
import fr.nicovers06.streamstudio.model.WidgetType
import fr.nicovers06.streamstudio.stream.StreamService
import fr.nicovers06.streamstudio.stream.chat.LiveChatConfig
import fr.nicovers06.streamstudio.stream.chat.LiveChatPlatform
import fr.nicovers06.streamstudio.stream.chat.TwitchIrcChatClient
import fr.nicovers06.streamstudio.stream.chat.YouTubeLiveChatClient
import java.util.Locale

class MainActivity : Activity() {
    private data class PlatformPreset(val label: String, val server: String)
    private data class PendingBroadcast(val endpoint: String, val scene: StreamScene)
    private enum class PermissionPurpose { CAMERA_PREVIEW, START_STREAM }

    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: SceneRepository
    private val mainHandler = Handler(Looper.getMainLooper())
    private val saveScenes = Runnable { repository.save(scenes) }
    private var scenes = mutableListOf<StreamScene>()
    private var selectedSceneIndex = 0
    private var rendering = false
    private var bound = false
    private var service: StreamService? = null
    private var pendingBroadcast: PendingBroadcast? = null
    private var screenCapturePrepared = false
    private var screenCapturePreparing = false
    private var permissionPurpose: PermissionPurpose? = null
    private var streaming = false
    private var addableWidgetModules: List<WidgetModule> = emptyList()
    private var reorderingLayers = false

    private val streamListener = object : StreamService.Listener {
        override fun onStateChanged(isStreaming: Boolean, status: String) = runOnUiThread {
            streaming = isStreaming
            updateStreamingUi(status)
        }

        override fun onScreenCaptureChanged(isReady: Boolean) = runOnUiThread {
            screenCapturePrepared = isReady
            screenCapturePreparing = false
            updateScreenSelectionUi()
        }

        override fun onBitrateChanged(bitsPerSecond: Long) = runOnUiThread {
            if (streaming) {
                val megabits = bitsPerSecond / 1_000_000.0
                binding.statusText.text = String.format(Locale.FRANCE, "EN DIRECT · %.1f Mb/s", megabits)
            }
        }

        override fun onWarning(message: String) = runOnUiThread { showMessage(message) }

        override fun onLiveChatMessages(messages: List<ChatMessage>) = runOnUiThread {
            // Overlay is updated by the service; toast only on first batch if needed.
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val localService = (binder as StreamService.LocalBinder).service
            service = localService
            bound = true
            localService.setListener(streamListener)
            localService.applyScene(currentScene())
            localService.attachPreview(binding.streamPreview)
            binding.previewHint.visibility = View.GONE
            streaming = localService.isStreaming()
            updateStreamingUi(if (streaming) "EN DIRECT" else "PRÊT")
            if (currentScene().camera.enabled &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
            ) {
                ensureCameraPermissionForPreview()
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            bound = false
            service = null
            screenCapturePrepared = false
            screenCapturePreparing = false
            binding.previewHint.visibility = View.VISIBLE
            updateScreenSelectionUi()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

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

    override fun onStart() {
        super.onStart()
        if (!bound) bindService(Intent(this, StreamService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        mainHandler.removeCallbacks(saveScenes)
        repository.save(scenes)
        if (bound) {
            service?.detachPreview()
            service?.setListener(null)
            unbindService(serviceConnection)
            bound = false
            service = null
        }
        super.onStop()
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
                "${module.label} (${getString(R.string.widget_max_hint, module.maxInstancesPerScene)})"
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
            showMessage(
                getString(
                    R.string.add_widget_max_reached,
                    module.label,
                    module.maxInstancesPerScene,
                ),
            )
            refreshAddWidgetSpinner()
            return
        }
        when (module.type) {
            WidgetType.SCREEN -> updateScene {
                it.copy(
                    screen = it.screen.copy(enabled = true),
                    layerOrder = WidgetModules.bringToFront(it.layerOrder, WidgetType.SCREEN),
                )
            }
            WidgetType.CAMERA -> {
                updateScene {
                    it.copy(
                        camera = it.camera.copy(enabled = true),
                        layerOrder = WidgetModules.bringToFront(it.layerOrder, WidgetType.CAMERA),
                    )
                }
                ensureCameraPermissionForPreview()
            }
            WidgetType.CHAT -> updateScene {
                it.copy(
                    chat = it.chat.copy(enabled = true),
                    layerOrder = WidgetModules.bringToFront(it.layerOrder, WidgetType.CHAT),
                )
            }
            WidgetType.MICROPHONE -> updateScene {
                it.copy(
                    microphoneEnabled = true,
                    layerOrder = WidgetModules.bringToFront(it.layerOrder, WidgetType.MICROPHONE),
                )
            }
        }
        showMessage(getString(R.string.add_widget_added, module.label))
    }

    private fun setupWidgetLayerDrag() {
        val handles = listOf(
            binding.screenLayerHandle to WidgetType.SCREEN,
            binding.cameraLayerHandle to WidgetType.CAMERA,
            binding.chatLayerHandle to WidgetType.CHAT,
            binding.microphoneLayerHandle to WidgetType.MICROPHONE,
        )
        handles.forEach { (handle, type) ->
            handle.setOnTouchListener { view, event ->
                if (event.actionMasked != MotionEvent.ACTION_DOWN) return@setOnTouchListener false
                val block = widgetBlock(type)
                val clip = ClipData.newPlainText("widgetLayer", type.name)
                val shadow = View.DragShadowBuilder(block)
                val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    view.startDragAndDrop(clip, shadow, type, 0)
                } else {
                    @Suppress("DEPRECATION")
                    view.startDrag(clip, shadow, type, 0)
                }
                if (started) {
                    block.alpha = 0.45f
                }
                started
            }
        }
        binding.widgetStack.setOnDragListener { stack, event ->
            val dragged = event.localState as? WidgetType ?: return@setOnDragListener false
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
                    widgetBlock(dragged).alpha = 1f
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

    private fun widgetBlock(type: WidgetType): View = when (type) {
        WidgetType.SCREEN -> binding.screenWidgetBlock
        WidgetType.CAMERA -> binding.cameraWidgetBlock
        WidgetType.CHAT -> binding.chatWidgetBlock
        WidgetType.MICROPHONE -> binding.microphoneWidgetBlock
    }

    private fun boundsView(type: WidgetType): View? = when (type) {
        WidgetType.SCREEN -> binding.screenBounds
        WidgetType.CAMERA -> binding.cameraBounds
        WidgetType.CHAT -> binding.chatBounds
        WidgetType.MICROPHONE -> null
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

    private fun moveWidgetBlock(stack: LinearLayout, type: WidgetType, targetIndex: Int) {
        val block = widgetBlock(type)
        val from = stack.indexOfChild(block)
        if (from < 0) return
        val desired = targetIndex.coerceIn(0, (stack.childCount - 1).coerceAtLeast(0))
        if (from == desired) return
        stack.removeView(block)
        // desired est l’index cible dans la liste d’origine ; après retrait,
        // insertAt = desired convient pour les deux directions avec coerceAtMost.
        stack.addView(block, desired.coerceAtMost(stack.childCount))
    }

    private fun readWidgetStackOrder(): List<WidgetType> {
        val stack = binding.widgetStack
        return buildList {
            for (index in 0 until stack.childCount) {
                val tag = stack.getChildAt(index).tag as? String
                val type = tag?.let { runCatching { WidgetType.valueOf(it) }.getOrNull() }
                if (type != null) add(type)
            }
        }.let { WidgetModules.normalizeLayerOrder(it) }
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

    private fun applyWidgetStackOrder(orderFrontFirst: List<WidgetType>) {
        val stack = binding.widgetStack
        val normalized = WidgetModules.normalizeLayerOrder(orderFrontFirst)
        normalized.forEachIndexed { index, type ->
            val block = widgetBlock(type)
            val currentIndex = stack.indexOfChild(block)
            if (currentIndex != index) {
                stack.removeView(block)
                stack.addView(block, index.coerceAtMost(stack.childCount))
            }
        }
    }

    private fun applyPreviewLayerOrder(orderFrontFirst: List<WidgetType>) {
        // FrameLayout : dernier bringToFront = devant.
        WidgetModules.visualLayerOrder(orderFrontFirst)
            .asReversed()
            .forEach { type -> boundsView(type)?.bringToFront() }
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
        if (!scene.screen.enabled && !scene.camera.enabled && !scene.chat.enabled) {
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
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
                    showMessage("Impossible d’ouvrir l’aperçu du partage d’écran")
                }
        } else {
            showMessage("Sélection du partage d’écran annulée")
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
    }

    private fun updateScreenSelectionUi() = with(binding) {
        val screenEnabled = currentScene().screen.enabled
        val supportsSingleAppSharing = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
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
        private const val REQUEST_PERMISSIONS = 100
        private const val REQUEST_SCREEN_SELECTION = 101
        private const val KEY_SCENE_INDEX = "scene_index"

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
