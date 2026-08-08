package fr.nicovers06.streamstudio.stream.chat

import fr.nicovers06.streamstudio.model.ChatMessage

/**
 * Selects and runs the platform chat client. Tokens stay in [LiveChatConfig] (memory).
 */
class LiveChatCoordinator(
    private val twitchClient: LiveChatClient = TwitchIrcChatClient(),
    private val youtubeClient: LiveChatClient = YouTubeLiveChatClient(),
) {
    private var active: LiveChatClient? = null
    private var lastConfig: LiveChatConfig = LiveChatConfig()

    @Synchronized
    fun start(config: LiveChatConfig, onMessages: (List<ChatMessage>) -> Unit, onStatus: (String) -> Unit) {
        if (config == lastConfig && active != null && config.isActionable()) return
        stop()
        lastConfig = config
        if (!config.isActionable()) return

        val client = when (config.platform) {
            LiveChatPlatform.TWITCH -> {
                onStatus("Chat Twitch : connexion IRC…")
                twitchClient
            }
            LiveChatPlatform.YOUTUBE -> {
                onStatus("Chat YouTube : polling API…")
                youtubeClient
            }
            LiveChatPlatform.NONE -> return
        }
        active = client
        client.start(config) { messages ->
            onMessages(messages)
        }
    }

    @Synchronized
    fun stop() {
        active?.stop()
        active = null
        lastConfig = LiveChatConfig()
    }
}
