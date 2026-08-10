package fr.nicovers06.streamstudio.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

enum class TimerMode { COUNTDOWN, STOPWATCH }

enum class ShapeKind { RECTANGLE, ELLIPSE, LINE }

enum class PollKind { POLL, QUESTION }

enum class TextKind { TEXT, LOWER_THIRD }

data class PollOption(
    val label: String,
    val value: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("label", label)
        .put("value", value.coerceAtLeast(0))

    companion object {
        fun fromJson(json: JSONObject): PollOption = PollOption(
            label = json.optString("label", "Option").ifBlank { "Option" },
            value = json.optInt("value", 0).coerceAtLeast(0),
        )
    }
}

/**
 * Configuration persistante d'un widget de scène dessiné avec les API Android natives.
 *
 * Le modèle est volontairement commun aux widgets dynamiques : seules les propriétés utiles au
 * [type] sont lues par l'éditeur et le moteur de rendu. Cette structure garde la sérialisation
 * rétrocompatible et permet de réordonner tous les widgets avec le même [LayerRef].
 */
data class NativeWidgetComponent(
    val id: String = UUID.randomUUID().toString(),
    val type: WidgetType,
    val enabled: Boolean = true,
    val bounds: NormalizedRect = defaultBounds(type),
    val title: String = defaultTitle(type),
    val subtitle: String = defaultSubtitle(type),
    val foregroundColor: Int = DEFAULT_FOREGROUND,
    val backgroundColor: Int = defaultBackground(type),
    val accentColor: Int = DEFAULT_ACCENT,
    val shapeKind: ShapeKind = ShapeKind.RECTANGLE,
    val gradientEnabled: Boolean = type == WidgetType.BACKGROUND,
    val timerMode: TimerMode = TimerMode.COUNTDOWN,
    val timerDurationSeconds: Long = DEFAULT_TIMER_SECONDS,
    val timerRunning: Boolean = false,
    val timerBaseElapsedMs: Long = 0L,
    val timerStartedAtEpochMs: Long = 0L,
    val tickerSpeed: Float = DEFAULT_TICKER_SPEED,
    /** Nom de fichier relatif sous filesDir/scene_media. */
    val mediaFileName: String = "",
    val mediaDisplayName: String = "Aucun média sélectionné",
    val mediaLoop: Boolean = true,
    val mediaPlaying: Boolean = true,
    /** Verrouille le ratio du cadre ; le contenu vidéo reste toujours en crop/cover. */
    val mediaKeepAspectRatio: Boolean = true,
    val alertDurationSeconds: Int = DEFAULT_ALERT_SECONDS,
    val alertTriggeredAtEpochMs: Long = 0L,
    val pollKind: PollKind = PollKind.POLL,
    val pollOptions: List<PollOption> = defaultPollOptions(),
    val textKind: TextKind = TextKind.TEXT,
) {
    init {
        require(type in NATIVE_TYPES) { "${type.name} is not a native dynamic widget" }
    }

    fun elapsedTimerMs(nowEpochMs: Long = System.currentTimeMillis()): Long {
        val runningDelta = if (timerRunning && timerStartedAtEpochMs > 0L) {
            (nowEpochMs - timerStartedAtEpochMs).coerceAtLeast(0L)
        } else {
            0L
        }
        return (timerBaseElapsedMs + runningDelta).coerceAtLeast(0L)
    }

    fun displayedTimerMs(nowEpochMs: Long = System.currentTimeMillis()): Long = when (timerMode) {
        TimerMode.COUNTDOWN -> (timerDurationSeconds.coerceAtLeast(1L) * 1_000L - elapsedTimerMs(nowEpochMs))
            .coerceAtLeast(0L)
        TimerMode.STOPWATCH -> elapsedTimerMs(nowEpochMs)
    }

    fun formattedTimer(nowEpochMs: Long = System.currentTimeMillis()): String {
        val totalSeconds = displayedTimerMs(nowEpochMs) / 1_000L
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
        }
    }

    fun startTimer(nowEpochMs: Long = System.currentTimeMillis()): NativeWidgetComponent {
        if (timerRunning) return this
        val shouldReset = timerMode == TimerMode.COUNTDOWN &&
            timerBaseElapsedMs >= timerDurationSeconds.coerceAtLeast(1L) * 1_000L
        return copy(
            timerRunning = true,
            timerBaseElapsedMs = if (shouldReset) 0L else timerBaseElapsedMs,
            timerStartedAtEpochMs = nowEpochMs,
        )
    }

    fun pauseTimer(nowEpochMs: Long = System.currentTimeMillis()): NativeWidgetComponent {
        if (!timerRunning) return this
        return copy(
            timerRunning = false,
            timerBaseElapsedMs = elapsedTimerMs(nowEpochMs),
            timerStartedAtEpochMs = 0L,
        )
    }

    fun resetTimer(): NativeWidgetComponent = copy(
        timerRunning = false,
        timerBaseElapsedMs = 0L,
        timerStartedAtEpochMs = 0L,
    )

    fun isMediaPlaybackActive(): Boolean =
        type == WidgetType.MEDIA && enabled && mediaPlaying && mediaFileName.isNotBlank()

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("type", type.name)
        .put("enabled", enabled)
        .put("bounds", bounds.toJson())
        .put("title", title)
        .put("subtitle", subtitle)
        .put("foregroundColor", foregroundColor.toLong())
        .put("backgroundColor", backgroundColor.toLong())
        .put("accentColor", accentColor.toLong())
        .put("shapeKind", shapeKind.name)
        .put("gradientEnabled", gradientEnabled)
        .put("timerMode", timerMode.name)
        .put("timerDurationSeconds", timerDurationSeconds.coerceAtLeast(1L))
        .put("timerRunning", timerRunning)
        .put("timerBaseElapsedMs", timerBaseElapsedMs.coerceAtLeast(0L))
        .put("timerStartedAtEpochMs", timerStartedAtEpochMs.coerceAtLeast(0L))
        .put("tickerSpeed", tickerSpeed.toDouble())
        .put("mediaFileName", mediaFileName)
        .put("mediaDisplayName", mediaDisplayName)
        .put("mediaLoop", mediaLoop)
        .put("mediaPlaying", mediaPlaying)
        .put("mediaKeepAspectRatio", mediaKeepAspectRatio)
        .put("alertDurationSeconds", alertDurationSeconds.coerceIn(1, 60))
        .put("alertTriggeredAtEpochMs", alertTriggeredAtEpochMs.coerceAtLeast(0L))
        .put("pollKind", pollKind.name)
        .put(
            "pollOptions",
            JSONArray().apply { pollOptions.take(MAX_POLL_OPTIONS).forEach { put(it.toJson()) } },
        )
        .put("textKind", textKind.name)

    companion object {
        const val DEFAULT_TIMER_SECONDS = 300L
        const val DEFAULT_TICKER_SPEED = 0.22f
        const val DEFAULT_ALERT_SECONDS = 5
        const val MAX_POLL_OPTIONS = 6
        const val DEFAULT_FOREGROUND = 0xFFFFFFFF.toInt()
        const val DEFAULT_ACCENT = 0xFF8B5CF6.toInt()

        val NATIVE_TYPES: Set<WidgetType> = setOf(
            WidgetType.TIMER,
            WidgetType.SHAPE,
            WidgetType.BACKGROUND,
            WidgetType.TICKER,
            WidgetType.MEDIA,
            WidgetType.ALERT,
            WidgetType.POLL,
            WidgetType.TEXT,
        )

        fun create(type: WidgetType, index: Int = 1): NativeWidgetComponent {
            require(type in NATIVE_TYPES)
            return NativeWidgetComponent(
                type = type,
                title = when (type) {
                    WidgetType.SHAPE -> "Forme $index"
                    WidgetType.TICKER -> "Bienvenue sur le stream · Merci de votre présence !"
                    WidgetType.ALERT -> "Nouvelle alerte"
                    WidgetType.TEXT -> "Votre texte"
                    else -> defaultTitle(type)
                },
            )
        }

        fun fromJson(json: JSONObject): NativeWidgetComponent? {
            val type = runCatching {
                WidgetType.valueOf(json.optString("type"))
            }.getOrNull()?.takeIf { it in NATIVE_TYPES } ?: return null
            val defaults = NativeWidgetComponent(type = type)
            val options = buildList {
                val stored = json.optJSONArray("pollOptions")
                if (stored != null) {
                    for (index in 0 until stored.length().coerceAtMost(MAX_POLL_OPTIONS)) {
                        stored.optJSONObject(index)?.let { add(PollOption.fromJson(it)) }
                    }
                }
            }.ifEmpty { defaults.pollOptions }
            return NativeWidgetComponent(
                id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
                type = type,
                enabled = json.optBoolean("enabled", defaults.enabled),
                bounds = NormalizedRect.fromJson(json.optJSONObject("bounds") ?: JSONObject(), defaults.bounds),
                title = json.optString("title", defaults.title),
                subtitle = json.optString("subtitle", defaults.subtitle),
                foregroundColor = json.optLong("foregroundColor", defaults.foregroundColor.toLong()).toInt(),
                backgroundColor = json.optLong("backgroundColor", defaults.backgroundColor.toLong()).toInt(),
                accentColor = json.optLong("accentColor", defaults.accentColor.toLong()).toInt(),
                shapeKind = enumValue(json, "shapeKind", defaults.shapeKind),
                gradientEnabled = json.optBoolean("gradientEnabled", defaults.gradientEnabled),
                timerMode = enumValue(json, "timerMode", defaults.timerMode),
                timerDurationSeconds = json.optLong("timerDurationSeconds", defaults.timerDurationSeconds)
                    .coerceIn(1L, 359_999L),
                timerRunning = json.optBoolean("timerRunning", defaults.timerRunning),
                timerBaseElapsedMs = json.optLong("timerBaseElapsedMs", defaults.timerBaseElapsedMs)
                    .coerceAtLeast(0L),
                timerStartedAtEpochMs = json.optLong("timerStartedAtEpochMs", defaults.timerStartedAtEpochMs)
                    .coerceAtLeast(0L),
                tickerSpeed = json.optDouble("tickerSpeed", defaults.tickerSpeed.toDouble()).toFloat()
                    .coerceIn(0.05f, 1.2f),
                mediaFileName = json.optString("mediaFileName", defaults.mediaFileName),
                mediaDisplayName = json.optString("mediaDisplayName", defaults.mediaDisplayName)
                    .ifBlank { defaults.mediaDisplayName },
                mediaLoop = json.optBoolean("mediaLoop", defaults.mediaLoop),
                mediaPlaying = json.optBoolean("mediaPlaying", defaults.mediaPlaying),
                mediaKeepAspectRatio = json.optBoolean("mediaKeepAspectRatio", defaults.mediaKeepAspectRatio),
                alertDurationSeconds = json.optInt("alertDurationSeconds", defaults.alertDurationSeconds)
                    .coerceIn(1, 60),
                alertTriggeredAtEpochMs = json.optLong("alertTriggeredAtEpochMs", defaults.alertTriggeredAtEpochMs)
                    .coerceAtLeast(0L),
                pollKind = enumValue(json, "pollKind", defaults.pollKind),
                pollOptions = options,
                textKind = enumValue(json, "textKind", defaults.textKind),
            )
        }

        fun defaultBounds(type: WidgetType): NormalizedRect = when (type) {
            WidgetType.TIMER -> NormalizedRect(0.38f, 0.05f, 0.24f, 0.15f)
            WidgetType.SHAPE -> NormalizedRect(0.34f, 0.34f, 0.32f, 0.28f)
            WidgetType.BACKGROUND -> NormalizedRect(0f, 0f, 1f, 1f)
            WidgetType.TICKER -> NormalizedRect(0f, 0.86f, 1f, 0.14f)
            WidgetType.MEDIA -> NormalizedRect(0.18f, 0.18f, 0.64f, 0.64f)
            WidgetType.ALERT -> NormalizedRect(0.25f, 0.08f, 0.50f, 0.20f)
            WidgetType.POLL -> NormalizedRect(0.55f, 0.52f, 0.40f, 0.40f)
            WidgetType.TEXT -> NormalizedRect(0.06f, 0.70f, 0.48f, 0.20f)
            else -> NormalizedRect(0.25f, 0.25f, 0.50f, 0.50f)
        }

        fun defaultTitle(type: WidgetType): String = when (type) {
            WidgetType.TIMER -> "Minuteur"
            WidgetType.SHAPE -> "Forme"
            WidgetType.BACKGROUND -> "Arrière-plan"
            WidgetType.TICKER -> "Bienvenue sur le stream"
            WidgetType.MEDIA -> "Média"
            WidgetType.ALERT -> "Nouvelle alerte"
            WidgetType.POLL -> "Votre avis ?"
            WidgetType.TEXT -> "Votre texte"
            else -> type.name
        }

        fun defaultSubtitle(type: WidgetType): String = when (type) {
            WidgetType.ALERT -> "Merci pour votre soutien !"
            WidgetType.POLL -> "Choisissez une réponse"
            WidgetType.TEXT -> "Sous-titre"
            else -> ""
        }

        fun defaultBackground(type: WidgetType): Int = when (type) {
            WidgetType.BACKGROUND -> 0xFF080E18.toInt()
            WidgetType.SHAPE -> DEFAULT_ACCENT
            else -> 0xDD111827.toInt()
        }

        fun defaultPollOptions(): List<PollOption> = listOf(
            PollOption("Oui", 60),
            PollOption("Non", 40),
        )

        private inline fun <reified T : Enum<T>> enumValue(
            json: JSONObject,
            key: String,
            fallback: T,
        ): T = runCatching { enumValueOf<T>(json.optString(key, fallback.name)) }.getOrDefault(fallback)
    }
}
