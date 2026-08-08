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

    /**
     * Ratio largeur/hauteur du rectangle en pixels d'une sc?ne [sceneWidth]?[sceneHeight].
     */
    fun pixelAspect(sceneWidth: Int, sceneHeight: Int): Float {
        val safe = constrained()
        val w = (safe.width * sceneWidth).coerceAtLeast(1f)
        val h = (safe.height * sceneHeight).coerceAtLeast(1f)
        return w / h
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

data class StreamScene(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Scène principale",
    val screen: ScreenComponent = ScreenComponent(),
    val microphoneEnabled: Boolean = true,
    val camera: CameraComponent = CameraComponent(),
    val chat: ChatComponent = ChatComponent(),
    /**
     * Ordre de superposition des widgets visuels.
     * Index 0 = le plus devant (premier de la liste sidebar).
     */
    val layerOrder: List<WidgetType> = WidgetModules.defaultLayerOrder,
) {
    fun normalizedLayerOrder(): List<WidgetType> = WidgetModules.normalizeLayerOrder(layerOrder)

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("screen", screen.toJson())
        .put("microphoneEnabled", microphoneEnabled)
        .put("camera", camera.toJson())
        .put("chat", chat.toJson())
        .put(
            "layerOrder",
            JSONArray().apply { normalizedLayerOrder().forEach { put(it.name) } },
        )

    companion object {
        fun fromJson(json: JSONObject): StreamScene {
            val legacyScreenEnabled = json.optBoolean("screenEnabled", true)
            val storedOrder = json.optJSONArray("layerOrder")
            val parsedOrder = buildList {
                if (storedOrder != null) {
                    for (index in 0 until storedOrder.length()) {
                        val raw = storedOrder.optString(index)
                        runCatching { WidgetType.valueOf(raw) }.getOrNull()?.let { add(it) }
                    }
                }
            }
            return StreamScene(
                id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
                name = json.optString("name", "Scène"),
                screen = ScreenComponent.fromJson(
                    json.optJSONObject("screen") ?: JSONObject(),
                    legacyEnabled = legacyScreenEnabled,
                ),
                microphoneEnabled = json.optBoolean("microphoneEnabled", true),
                camera = CameraComponent.fromJson(json.optJSONObject("camera") ?: JSONObject()),
                chat = ChatComponent.fromJson(json.optJSONObject("chat") ?: JSONObject()),
                layerOrder = WidgetModules.normalizeLayerOrder(parsedOrder),
            )
        }
    }
}
