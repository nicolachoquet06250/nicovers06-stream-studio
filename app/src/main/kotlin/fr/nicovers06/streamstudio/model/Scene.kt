package fr.nicovers06.streamstudio.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class NormalizedRect(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
) {
    fun constrained(minSize: Float = 0.12f): NormalizedRect {
        val safeWidth = width.coerceIn(minSize, 1f)
        val safeHeight = height.coerceIn(minSize, 1f)
        return copy(
            x = x.coerceIn(0f, 1f - safeWidth),
            y = y.coerceIn(0f, 1f - safeHeight),
            width = safeWidth,
            height = safeHeight,
        )
    }

    /** Ratio largeur/hauteur du rectangle en pixels d'une scène [sceneWidth] × [sceneHeight]. */
    fun pixelAspect(sceneWidth: Int, sceneHeight: Int): Float {
        val safe = constrained()
        val w = (safe.width * sceneWidth).coerceAtLeast(1f)
        val h = (safe.height * sceneHeight).coerceAtLeast(1f)
        return w / h
    }

    /**
     * Conserve la hauteur et le centre horizontal du cadre, puis adapte sa largeur au ratio pixel demandé.
     */
    fun withPixelAspectKeepingHeight(
        pixelAspect: Float,
        sceneWidth: Int,
        sceneHeight: Int,
    ): NormalizedRect {
        val safe = constrained()
        if (!pixelAspect.isFinite() || pixelAspect <= 0f || sceneWidth <= 0 || sceneHeight <= 0) {
            return safe
        }
        val targetWidth = (
            safe.height * pixelAspect * sceneHeight.toFloat() / sceneWidth.toFloat()
        ).coerceIn(0.12f, 1f)
        val centerX = safe.x + safe.width / 2f
        return safe.copy(
            x = (centerX - targetWidth / 2f).coerceIn(0f, 1f - targetWidth),
            width = targetWidth,
        )
    }

    fun toJson(): JSONObject = JSONObject()
        .put("x", x.toDouble())
        .put("y", y.toDouble())
        .put("width", width.toDouble())
        .put("height", height.toDouble())

    companion object {
        fun fromJson(json: JSONObject, fallback: NormalizedRect): NormalizedRect = NormalizedRect(
            x = json.optDouble("x", fallback.x.toDouble()).toFloat(),
            y = json.optDouble("y", fallback.y.toDouble()).toFloat(),
            width = json.optDouble("width", fallback.width.toDouble()).toFloat(),
            height = json.optDouble("height", fallback.height.toDouble()).toFloat(),
        ).constrained()
    }
}

enum class CameraFacing { FRONT, BACK }

data class ScreenComponent(
    val enabled: Boolean = true,
    val bounds: NormalizedRect = NormalizedRect(0.01f, 0.02f, 0.72f, 0.96f),
    /** Verrouille le ratio du cadre au redimensionnement. */
    val keepAspectRatio: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("enabled", enabled)
        .put("bounds", bounds.toJson())
        .put("keepAspectRatio", keepAspectRatio)

    companion object {
        fun fromJson(json: JSONObject, legacyEnabled: Boolean = true): ScreenComponent {
            val defaults = ScreenComponent(enabled = legacyEnabled)
            return ScreenComponent(
                enabled = json.optBoolean("enabled", defaults.enabled),
                bounds = NormalizedRect.fromJson(json.optJSONObject("bounds") ?: JSONObject(), defaults.bounds),
                keepAspectRatio = json.optBoolean("keepAspectRatio", defaults.keepAspectRatio),
            )
        }
    }
}

data class CameraComponent(
    val enabled: Boolean = true,
    val backgroundBlur: Boolean = true,
    val facing: CameraFacing = CameraFacing.FRONT,
    val bounds: NormalizedRect = NormalizedRect(0.02f, 0.64f, 0.30f, 0.32f),
    /** Verrouille le ratio du cadre au redimensionnement. */
    val keepAspectRatio: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("enabled", enabled)
        .put("backgroundBlur", backgroundBlur)
        .put("facing", facing.name)
        .put("bounds", bounds.toJson())
        .put("keepAspectRatio", keepAspectRatio)

    companion object {
        fun fromJson(json: JSONObject): CameraComponent {
            val defaults = CameraComponent()
            return CameraComponent(
                enabled = json.optBoolean("enabled", defaults.enabled),
                backgroundBlur = json.optBoolean("backgroundBlur", defaults.backgroundBlur),
                facing = runCatching {
                    CameraFacing.valueOf(json.optString("facing", defaults.facing.name))
                }.getOrDefault(defaults.facing),
                bounds = NormalizedRect.fromJson(json.optJSONObject("bounds") ?: JSONObject(), defaults.bounds),
                keepAspectRatio = json.optBoolean("keepAspectRatio", defaults.keepAspectRatio),
            )
        }
    }
}

data class ChatMessage(
    val author: String,
    val text: String,
    val accent: Int,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("author", author)
        .put("text", text)
        .put("accent", accent)

    companion object {
        fun fromJson(json: JSONObject): ChatMessage = ChatMessage(
            author = json.optString("author", "viewer"),
            text = json.optString("text", "Hello !"),
            accent = json.optInt("accent", 0xFF8B5CF6.toInt()),
        )
    }
}

data class ChatComponent(
    val enabled: Boolean = true,
    val bounds: NormalizedRect = NormalizedRect(0.74f, 0.02f, 0.25f, 0.96f),
    val messages: List<ChatMessage> = previewMessages(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("enabled", enabled)
        .put("bounds", bounds.toJson())
        .put("messages", JSONArray().apply { messages.takeLast(MAX_MESSAGES).forEach { put(it.toJson()) } })

    companion object {
        const val MAX_MESSAGES = 6

        fun previewMessages(): List<ChatMessage> = listOf(
            ChatMessage("luna_streams", "Le live est lancé 🔥", 0xFF8B5CF6.toInt()),
            ChatMessage("mat_dev", "La scène est super propre !", 0xFF34D399.toInt()),
            ChatMessage("naya", "Hello tout le monde 👋", 0xFFF59E0B.toInt()),
        )

        fun fromJson(json: JSONObject): ChatComponent {
            val defaults = ChatComponent()
            val stored = json.optJSONArray("messages")
            val messages = buildList {
                if (stored != null) {
                    for (index in 0 until stored.length()) {
                        stored.optJSONObject(index)?.let { add(ChatMessage.fromJson(it)) }
                    }
                }
            }.ifEmpty { defaults.messages }
            return ChatComponent(
                enabled = json.optBoolean("enabled", defaults.enabled),
                bounds = NormalizedRect.fromJson(json.optJSONObject("bounds") ?: JSONObject(), defaults.bounds),
                messages = messages.takeLast(MAX_MESSAGES),
            )
        }
    }
}

data class ImageComponent(
    val id: String = UUID.randomUUID().toString(),
    val enabled: Boolean = true,
    val bounds: NormalizedRect = NormalizedRect(0.36f, 0.34f, 0.28f, 0.32f),
    /**
     * Verrouille le ratio du cadre au redimensionnement sur la scène.
     * Le contenu image est toujours cropté (cover), coché ou non — jamais déformé.
     */
    val keepAspectRatio: Boolean = true,
    /** Nom de fichier relatif sous filesDir/scene_images. */
    val fileName: String = "",
    val displayName: String = "Image",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("enabled", enabled)
        .put("bounds", bounds.toJson())
        .put("keepAspectRatio", keepAspectRatio)
        .put("fileName", fileName)
        .put("displayName", displayName)

    companion object {
        const val MAX_PER_SCENE = 10

        fun fromJson(json: JSONObject): ImageComponent {
            val defaults = ImageComponent()
            return ImageComponent(
                id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
                enabled = json.optBoolean("enabled", defaults.enabled),
                bounds = NormalizedRect.fromJson(json.optJSONObject("bounds") ?: JSONObject(), defaults.bounds),
                keepAspectRatio = json.optBoolean("keepAspectRatio", defaults.keepAspectRatio),
                fileName = json.optString("fileName", defaults.fileName),
                displayName = json.optString("displayName", defaults.displayName).ifBlank { defaults.displayName },
            )
        }
    }
}

data class StreamScene(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Scène principale",
    val screenPresent: Boolean = true,
    val screen: ScreenComponent = ScreenComponent(),
    val microphonePresent: Boolean = true,
    val microphoneEnabled: Boolean = true,
    val cameraPresent: Boolean = true,
    val camera: CameraComponent = CameraComponent(),
    val chatPresent: Boolean = true,
    val chat: ChatComponent = ChatComponent(),
    val images: List<ImageComponent> = emptyList(),
    val nativeWidgets: List<NativeWidgetComponent> = emptyList(),
    /**
     * Ordre de superposition des widgets.
     * Index 0 = le plus devant (premier de la liste sidebar).
     */
    val layerOrder: List<LayerRef> = WidgetModules.defaultLayerOrder,
) {
    fun image(id: String): ImageComponent? = images.firstOrNull { it.id == id }

    fun nativeWidget(id: String): NativeWidgetComponent? = nativeWidgets.firstOrNull { it.id == id }

    fun hasEnabledVisualWidget(): Boolean =
        screenPresent && screen.enabled ||
            cameraPresent && camera.enabled ||
            chatPresent && chat.enabled ||
            images.any { it.enabled } ||
            nativeWidgets.any { it.enabled }

    fun normalizedLayerOrder(): List<LayerRef> = WidgetModules.normalizeLayerOrder(layerOrder, this)

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("screenPresent", screenPresent)
        .put("screen", screen.toJson())
        .put("microphonePresent", microphonePresent)
        .put("microphoneEnabled", microphoneEnabled)
        .put("cameraPresent", cameraPresent)
        .put("camera", camera.toJson())
        .put("chatPresent", chatPresent)
        .put("chat", chat.toJson())
        .put(
            "images",
            JSONArray().apply { images.take(ImageComponent.MAX_PER_SCENE).forEach { put(it.toJson()) } },
        )
        .put(
            "nativeWidgets",
            JSONArray().apply { sanitizeNativeWidgets(nativeWidgets).forEach { put(it.toJson()) } },
        )
        .put(
            "layerOrder",
            JSONArray().apply { normalizedLayerOrder().forEach { put(it.storageKey()) } },
        )

    companion object {
        fun fromJson(json: JSONObject): StreamScene {
            val legacyScreenEnabled = json.optBoolean("screenEnabled", true)
            val screenPresent = json.optBoolean("screenPresent", true)
            val microphonePresent = json.optBoolean("microphonePresent", true)
            val cameraPresent = json.optBoolean("cameraPresent", true)
            val chatPresent = json.optBoolean("chatPresent", true)
            val screen = ScreenComponent.fromJson(
                json.optJSONObject("screen") ?: JSONObject(),
                legacyEnabled = legacyScreenEnabled,
            ).let { it.copy(enabled = screenPresent && it.enabled) }
            val camera = CameraComponent.fromJson(json.optJSONObject("camera") ?: JSONObject())
                .let { it.copy(enabled = cameraPresent && it.enabled) }
            val chat = ChatComponent.fromJson(json.optJSONObject("chat") ?: JSONObject())
                .let { it.copy(enabled = chatPresent && it.enabled) }
            val images = buildList {
                val stored = json.optJSONArray("images")
                if (stored != null) {
                    for (index in 0 until stored.length()) {
                        stored.optJSONObject(index)?.let { add(ImageComponent.fromJson(it)) }
                    }
                }
            }.take(ImageComponent.MAX_PER_SCENE)
            val storedOrder = json.optJSONArray("layerOrder")
            val nativeWidgets = buildList {
                val stored = json.optJSONArray("nativeWidgets")
                if (stored != null) {
                    for (index in 0 until stored.length()) {
                        stored.optJSONObject(index)?.let { widgetJson ->
                            NativeWidgetComponent.fromJson(widgetJson)?.let { add(it) }
                        }
                    }
                }
            }.let(::sanitizeNativeWidgets)
            val parsedOrder = buildList {
                if (storedOrder != null) {
                    for (index in 0 until storedOrder.length()) {
                        LayerRef.parse(storedOrder.optString(index))?.let { add(it) }
                    }
                }
            }
            val scene = StreamScene(
                id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
                name = json.optString("name", "Scène"),
                screenPresent = screenPresent,
                screen = screen,
                microphonePresent = microphonePresent,
                microphoneEnabled = microphonePresent && json.optBoolean("microphoneEnabled", true),
                cameraPresent = cameraPresent,
                camera = camera,
                chatPresent = chatPresent,
                chat = chat,
                images = images,
                nativeWidgets = nativeWidgets,
                layerOrder = parsedOrder,
            )
            return scene.copy(layerOrder = scene.normalizedLayerOrder())
        }

        /** Applique également les plafonds lors de la lecture d'un fichier potentiellement modifié. */
        fun sanitizeNativeWidgets(widgets: List<NativeWidgetComponent>): List<NativeWidgetComponent> {
            val counts = mutableMapOf<WidgetType, Int>()
            val ids = mutableSetOf<String>()
            return widgets.filter { widget ->
                val max = WidgetModules.of(widget.type).maxInstancesPerScene
                val count = counts.getOrDefault(widget.type, 0)
                val accepted = widget.id.isNotBlank() && widget.id !in ids && count < max
                if (accepted) {
                    ids += widget.id
                    counts[widget.type] = count + 1
                }
                accepted
            }
        }
    }
}
