package fr.nicovers06.streamstudio.stream.chat

import fr.nicovers06.streamstudio.model.ChatMessage

enum class LiveChatPlatform {
    TWITCH,
    YOUTUBE,
    NONE,
}

/**
 * Credentials and targets for live chat ingestion.
 * OAuth / access tokens must stay in memory only — never persist them.
 */
data class LiveChatConfig(
    val platform: LiveChatPlatform = LiveChatPlatform.NONE,
    val twitchChannel: String = "",
    /**
     * Optional broadcaster/bot login (lowercase) when [twitchOAuthToken] is set.
     * Must match the account that owns the token (Twitch IRC requirement).
     */
    val twitchLogin: String = "",
    /** Optional user access token with chat:read. Empty = anonymous justinfan read-only IRC. */
    val twitchOAuthToken: String = "",
    /** YouTube video id of the live broadcast (or completed live with active chat). */
    val youtubeVideoId: String = "",
    /** OAuth 2 access token with youtube.readonly or youtube.force-ssl. Memory only. */
    val youtubeAccessToken: String = "",
) {
    fun isActionable(): Boolean = when (platform) {
        LiveChatPlatform.TWITCH -> twitchChannel.isNotBlank()
        LiveChatPlatform.YOUTUBE -> youtubeVideoId.isNotBlank() && youtubeAccessToken.isNotBlank()
        LiveChatPlatform.NONE -> false
    }
}

fun interface LiveChatListener {
    fun onMessages(messages: List<ChatMessage>)
}

interface LiveChatClient {
    fun start(config: LiveChatConfig, listener: LiveChatListener)
    fun stop()
}

object ChatAccentPalette {
    private val accents = intArrayOf(
        0xFF8B5CF6.toInt(),
        0xFF34D399.toInt(),
        0xFFF59E0B.toInt(),
        0xFF60A5FA.toInt(),
        0xFFF472B6.toInt(),
        0xFF22D3EE.toInt(),
    )

    fun forAuthor(author: String): Int {
        val hash = author.lowercase().fold(0) { acc, c -> 31 * acc + c.code }
        return accents[kotlin.math.abs(hash) % accents.size]
    }

    fun parseHexColor(raw: String?, fallbackAuthor: String): Int {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return forAuthor(fallbackAuthor)
        val hex = value.removePrefix("#")
        return runCatching {
            when (hex.length) {
                6 -> (0xFF000000 or hex.toLong(16)).toInt()
                8 -> hex.toLong(16).toInt()
                else -> forAuthor(fallbackAuthor)
            }
        }.getOrDefault(forAuthor(fallbackAuthor))
    }
}
