package fr.nicovers06.streamstudio

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
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
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Toast
import androidx.core.content.ContextCompat
import fr.nicovers06.streamstudio.data.SceneRepository
import fr.nicovers06.streamstudio.databinding.ActivityMainBinding
import fr.nicovers06.streamstudio.model.CameraFacing
import fr.nicovers06.streamstudio.model.ChatComponent
import fr.nicovers06.streamstudio.model.ChatMessage
import fr.nicovers06.streamstudio.model.StreamScene
import fr.nicovers06.streamstudio.stream.StreamService
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
    private var selectedProjectionData: Intent? = null
    private var permissionPurpose: PermissionPurpose? = null
    private var streaming = false

    private val streamListener = object : StreamService.Listener {
        override fun onStateChanged(isStreaming: Boolean, status: String) = runOnUiThread {
            streaming = isStreaming
            updateStreamingUi(status)
        }

        override fun onBitrateChanged(bitsPerSecond: Long) = runOnUiThread {
            if (streaming) {
                val megabits = bitsPerSecond / 1_000_000.0
                binding.statusText.text = String.format(Locale.FRANCE, "EN DIRECT · %.1f Mb/s", megabits)
            }
        }

        override fun onWarning(message: String) = runOnUiThread { showMessage(message) }
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
            binding.previewHint.visibility = View.VISIBLE
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
        renderScene()
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
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun setupActions() = with(binding) {
        addSceneButton.setOnClickListener { showAddSceneDialog() }
        deleteSceneButton.setOnClickListener { deleteCurrentScene() }

        screenSwitch.setOnCheckedChangeListener { _, enabled ->
            if (!rendering) {
                if (!enabled) selectedProjectionData = null
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
        chatSwitch.setOnCheckedChangeListener { _, enabled ->
            if (!rendering) updateScene { it.copy(chat = it.chat.copy(enabled = enabled)) }
        }
        flipCameraButton.setOnClickListener {
            updateScene {
                val facing = if (it.camera.facing == CameraFacing.FRONT) CameraFacing.BACK else CameraFacing.FRONT
                it.copy(camera = it.camera.copy(facing = facing))
            }
        }
        addChatMessageButton.setOnClickListener { addPreviewChatMessage() }
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
        binding.chatSwitch.isChecked = scene.chat.enabled
        binding.cameraOptions.visibility = if (scene.camera.enabled) View.VISIBLE else View.GONE
        binding.chatOptions.visibility = if (scene.chat.enabled) View.VISIBLE else View.GONE
        binding.flipCameraButton.text = if (scene.camera.facing == CameraFacing.FRONT) {
            "Utiliser la caméra arrière"
        } else {
            "Utiliser la caméra avant"
        }
        binding.screenBounds.bind(
            scene.screen.bounds,
            scene.screen.enabled,
            "ÉCRAN",
            resizeFromTopRight = true,
        ) { bounds ->
            updateScene(debounceSave = true, render = false) {
                it.copy(screen = it.screen.copy(bounds = bounds))
            }
        }
        binding.cameraBounds.bind(scene.camera.bounds, scene.camera.enabled, "CAMÉRA") { bounds ->
            updateScene(debounceSave = true, render = false) {
                it.copy(camera = it.camera.copy(bounds = bounds))
            }
        }
        binding.chatBounds.bind(scene.chat.bounds, scene.chat.enabled, "CHAT") { bounds ->
            updateScene(debounceSave = true, render = false) {
                it.copy(chat = it.chat.copy(bounds = bounds))
            }
        }
        rendering = false
        service?.applyScene(scene)
        updateStreamingUi(binding.statusText.text.toString())
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
        if (scene.screen.enabled && selectedProjectionData == null) {
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
        val projectionData = if (pending.scene.screen.enabled) selectedProjectionData else null
        if (pending.scene.screen.enabled && projectionData == null) {
            pendingBroadcast = null
            showMessage("La source de partage d’écran n’est plus disponible. Sélectionnez-la à nouveau.")
            updateScreenSelectionUi()
            return
        }
        val intent = Intent(this, StreamService::class.java)
            .setAction(StreamService.ACTION_START)
            .putExtra(StreamService.EXTRA_ENDPOINT, pending.endpoint)
            .putExtra(StreamService.EXTRA_SCENE_JSON, pending.scene.toJson().toString())
        if (projectionData != null) {
            intent.putExtra(StreamService.EXTRA_PROJECTION_RESULT, RESULT_OK)
            intent.putExtra(StreamService.EXTRA_PROJECTION_DATA, projectionData)
        }
        ContextCompat.startForegroundService(this, intent)
        pendingBroadcast = null
        selectedProjectionData = null
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
            selectedProjectionData = data
            updateScreenSelectionUi()
            showMessage("Source de partage d’écran sélectionnée")
        } else {
            showMessage("Sélection du partage d’écran annulée")
        }
    }

    private fun updateStreamingUi(status: String) = with(binding) {
        statusText.text = status
        statusText.setTextColor(ContextCompat.getColor(this@MainActivity, if (status.startsWith("ERREUR")) R.color.red else R.color.green))
        streamButton.text = if (streaming) "Arrêter le stream" else "Démarrer le stream"
        streamButton.backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, if (streaming) R.color.red else R.color.purple)

        sceneSpinner.isEnabled = !streaming
        addSceneButton.isEnabled = !streaming
        deleteSceneButton.isEnabled = !streaming
        screenSwitch.isEnabled = !streaming
        microphoneSwitch.isEnabled = !streaming
        cameraSwitch.isEnabled = !streaming
        chatSwitch.isEnabled = !streaming
        platformSpinner.isEnabled = !streaming
        serverInput.isEnabled = !streaming
        streamKeyInput.isEnabled = !streaming
        blurSwitch.isEnabled = currentScene().camera.enabled
        flipCameraButton.isEnabled = currentScene().camera.enabled
        updateScreenSelectionUi()
    }

    private fun updateScreenSelectionUi() = with(binding) {
        val screenEnabled = currentScene().screen.enabled
        val sourceSelected = selectedProjectionData != null
        val supportsSingleAppSharing = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        screenOptions.visibility = if (screenEnabled) View.VISIBLE else View.GONE
        screenCaptureHelp.setText(
            if (supportsSingleAppSharing) R.string.screen_capture_help else R.string.screen_capture_help_legacy,
        )
        selectScreenButton.isEnabled = screenEnabled && !streaming
        selectScreenButton.text = getString(
            when {
                sourceSelected -> R.string.change_screen_source
                supportsSingleAppSharing -> R.string.select_screen_source
                else -> R.string.select_screen_source_legacy
            },
        )

        val statusTextRes = when {
            streaming && screenEnabled -> R.string.screen_source_active
            sourceSelected -> R.string.screen_source_ready
            else -> R.string.screen_source_missing
        }
        screenSourceStatus.setText(statusTextRes)
        screenSourceStatus.setTextColor(
            ContextCompat.getColor(
                this@MainActivity,
                if (streaming && screenEnabled || sourceSelected) R.color.green else R.color.text_secondary,
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
