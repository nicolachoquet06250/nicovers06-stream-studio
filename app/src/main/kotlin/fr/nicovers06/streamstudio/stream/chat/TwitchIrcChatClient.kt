package fr.nicovers06.streamstudio.stream.chat

import fr.nicovers06.streamstudio.model.ChatComponent
import fr.nicovers06.streamstudio.model.ChatMessage
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * Twitch chat over IRC WebSocket (official Chat IRC interface).
 * Docs: https://dev.twitch.tv/docs/chat/irc
 *
 * Anonymous read-only: NICK justinfan&lt;n&gt; without a user token.
 * Authenticated: PASS oauth:&lt;user_access_token&gt; with scope chat:read + NICK = login.
 */
class TwitchIrcChatClient(
    private val httpClient: OkHttpClient = defaultClient(),
) : LiveChatClient {
    private val running = AtomicBoolean(false)
    private var socket: WebSocket? = null
    private val buffer = ArrayDeque<ChatMessage>(ChatComponent.MAX_MESSAGES)
    private var listener: LiveChatListener? = null

    override fun start(config: LiveChatConfig, listener: LiveChatListener) {
        stop()
        val channel = normalizeChannel(config.twitchChannel)
        if (channel.isEmpty()) {
            listener.onMessages(emptyList())
            return
        }
        this.listener = listener
        running.set(true)

        val token = config.twitchOAuthToken.trim().removePrefix("oauth:").trim()
        val login = TwitchIrcChatClient.normalizeChannel(config.twitchLogin)
        val useAuth = token.isNotEmpty() && login.isNotEmpty()
        val nick = if (useAuth) login else "justinfan${Random.nextInt(10000, 99999)}"
        val pass = if (useAuth) "oauth:$token" else "SCHMOOPIIE"

        val request = Request.Builder()
            .url(WS_URL)
            .build()

        socket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!running.get()) return
                webSocket.send("CAP REQ :twitch.tv/tags twitch.tv/commands")
                webSocket.send("PASS $pass")
                webSocket.send("NICK $nick")
                webSocket.send("JOIN #$channel")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!running.get()) return
                text.split("\r\n", "\n").forEach { line ->
                    if (line.isBlank()) return@forEach
                    handleLine(webSocket, line)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!running.get()) return
                // Soft fail: keep last messages; UI already warned via empty reconnect attempts.
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                // no-op
            }
        })
    }

    override fun stop() {
        running.set(false)
        socket?.close(1000, "stop")
        socket = null
        listener = null
        synchronized(buffer) { buffer.clear() }
    }

    private fun handleLine(webSocket: WebSocket, line: String) {
        if (line.startsWith("PING")) {
            val payload = line.removePrefix("PING").trim().ifBlank { ":tmi.twitch.tv" }
            webSocket.send("PONG $payload")
            return
        }

        val privmsg = PRIVMSG_REGEX.find(line) ?: return
        val tagsRaw = privmsg.groupValues[1]
        val channelLogin = privmsg.groupValues[2]
        val messageText = privmsg.groupValues[3].trim()
        if (messageText.isEmpty()) return

        val tags = parseTags(tagsRaw)
        val author = tags["display-name"]
            ?.takeIf { it.isNotBlank() }
            ?: tags["login"]
            ?: channelLogin.ifBlank { "viewer" }
        val accent = ChatAccentPalette.parseHexColor(tags["color"], author)
        val message = ChatMessage(author = author, text = messageText, accent = accent)
        push(message)
    }

    private fun push(message: ChatMessage) {
        val snapshot = synchronized(buffer) {
            buffer.addLast(message)
            while (buffer.size > ChatComponent.MAX_MESSAGES) buffer.removeFirst()
            buffer.toList()
        }
        listener?.onMessages(snapshot)
    }

    private fun parseTags(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return raw.split(';').mapNotNull { part ->
            val idx = part.indexOf('=')
            if (idx <= 0) null
            else part.substring(0, idx) to part.substring(idx + 1)
        }.toMap()
    }

    companion object {
        private const val WS_URL = "wss://irc-ws.chat.twitch.tv:443"

        private val PRIVMSG_REGEX = Regex(
            """^(?:@(\S+)\s+)?(?::\S+\s+)?PRIVMSG\s+#(\S+)\s+:?(.*)$""",
        )

        fun normalizeChannel(raw: String): String {
            return raw.trim()
                .removePrefix("https://")
                .removePrefix("http://")
                .removePrefix("www.")
                .removePrefix("twitch.tv/")
                .removePrefix("m.twitch.tv/")
                .substringBefore('/')
                .substringBefore('?')
                .removePrefix("@")
                .removePrefix("#")
                .lowercase()
                .filter { it.isLetterOrDigit() || it == '_' }
        }

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .pingInterval(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }
}
