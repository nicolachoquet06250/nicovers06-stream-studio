package fr.nicovers06.streamstudio.stream.chat

import fr.nicovers06.streamstudio.model.ChatComponent
import fr.nicovers06.streamstudio.model.ChatMessage
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * YouTube Live Chat via Live Streaming API polling.
 * Docs:
 * - videos.list (liveStreamingDetails.activeLiveChatId)
 * - liveChatMessages.list
 *
 * Requires a user OAuth access token with youtube.readonly (or broader YouTube scope).
 * Token is never logged or written to disk by this client.
 */
class YouTubeLiveChatClient(
    private val httpClient: OkHttpClient = defaultClient(),
) : LiveChatClient {
    private val running = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "youtube-live-chat").apply { isDaemon = true }
    }
    private var job: Future<*>? = null
    private val buffer = ArrayDeque<ChatMessage>(ChatComponent.MAX_MESSAGES)
    private val seenIds = LinkedHashSet<String>()
    private var listener: LiveChatListener? = null

    override fun start(config: LiveChatConfig, listener: LiveChatListener) {
        stop()
        val videoId = normalizeVideoId(config.youtubeVideoId)
        val token = config.youtubeAccessToken.trim()
        if (videoId.isEmpty() || token.isEmpty()) {
            listener.onMessages(emptyList())
            return
        }
        this.listener = listener
        running.set(true)
        job = executor.submit {
            try {
                val liveChatId = fetchActiveLiveChatId(videoId, token)
                    ?: throw IOException("Aucun liveChatId actif pour cette vidéo (live non démarré ?)")
                var pageToken: String? = null
                while (running.get()) {
                    val page = fetchMessages(liveChatId, token, pageToken)
                    page.messages.forEach { push(it) }
                    pageToken = page.nextPageToken
                    val waitMs = page.pollingIntervalMillis.coerceIn(1_000L, 15_000L)
                    var waited = 0L
                    while (running.get() && waited < waitMs) {
                        Thread.sleep(200)
                        waited += 200
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (_: Exception) {
                // Failures are non-fatal for the encoder; keep last buffered messages.
            }
        }
    }

    override fun stop() {
        running.set(false)
        job?.cancel(true)
        job = null
        listener = null
        synchronized(buffer) {
            buffer.clear()
            seenIds.clear()
        }
    }

    private fun fetchActiveLiveChatId(videoId: String, accessToken: String): String? {
        val url =
            "https://www.googleapis.com/youtube/v3/videos?part=liveStreamingDetails&id=$videoId"
        val json = authorizedGet(url, accessToken)
        val items = json.optJSONArray("items") ?: return null
        if (items.length() == 0) return null
        val details = items.getJSONObject(0).optJSONObject("liveStreamingDetails") ?: return null
        return details.optString("activeLiveChatId").takeIf { it.isNotBlank() }
    }

    private data class Page(
        val messages: List<ChatMessage>,
        val nextPageToken: String?,
        val pollingIntervalMillis: Long,
    )

    private fun fetchMessages(liveChatId: String, accessToken: String, pageToken: String?): Page {
        val base =
            "https://www.googleapis.com/youtube/v3/liveChatMessages" +
                "?liveChatId=$liveChatId&part=snippet,authorDetails&maxResults=200"
        val url = if (pageToken.isNullOrBlank()) base else "$base&pageToken=$pageToken"
        val json = authorizedGet(url, accessToken)
        val items = json.optJSONArray("items")
        val messages = buildList {
            if (items != null) {
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    val id = item.optString("id")
                    if (id.isNotBlank()) {
                        val fresh = synchronized(buffer) {
                            val added = seenIds.add(id)
                            while (seenIds.size > 400) {
                                val first = seenIds.firstOrNull() ?: break
                                seenIds.remove(first)
                            }
                            added
                        }
                        if (!fresh) continue
                    }
                    val snippet = item.optJSONObject("snippet") ?: continue
                    val type = snippet.optString("type")
                    if (type.isNotBlank() && type != "textMessageEvent") continue
                    val text = snippet.optString("displayMessage")
                        .ifBlank {
                            snippet.optJSONObject("textMessageDetails")
                                ?.optString("messageText")
                                .orEmpty()
                        }
                        .trim()
                    if (text.isEmpty()) continue
                    val author = item.optJSONObject("authorDetails")
                        ?.optString("displayName")
                        ?.ifBlank { null }
                        ?: "viewer"
                    add(ChatMessage(author, text, ChatAccentPalette.forAuthor(author)))
                }
            }
        }
        return Page(
            messages = messages,
            nextPageToken = json.optString("nextPageToken").takeIf { it.isNotBlank() },
            pollingIntervalMillis = json.optLong("pollingIntervalMillis", 5_000L),
        )
    }

    private fun authorizedGet(url: String, accessToken: String): JSONObject {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .get()
            .build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("YouTube API ${response.code}")
            }
            return JSONObject(body)
        }
    }

    private fun push(message: ChatMessage) {
        val snapshot = synchronized(buffer) {
            buffer.addLast(message)
            while (buffer.size > ChatComponent.MAX_MESSAGES) buffer.removeFirst()
            buffer.toList()
        }
        listener?.onMessages(snapshot)
    }

    companion object {
        fun normalizeVideoId(raw: String): String {
            val value = raw.trim()
            if (value.isEmpty()) return ""
            if (!value.contains("://") && !value.contains("youtu") && !value.contains("/")) {
                return value.takeWhile { it.isLetterOrDigit() || it == '_' || it == '-' }
            }
            val patterns = listOf(
                Regex("""[?&]v=([A-Za-z0-9_-]{6,})"""),
                Regex("""youtu\.be/([A-Za-z0-9_-]{6,})"""),
                Regex("""/live/([A-Za-z0-9_-]{6,})"""),
                Regex("""/embed/([A-Za-z0-9_-]{6,})"""),
            )
            for (pattern in patterns) {
                val match = pattern.find(value)
                if (match != null) return match.groupValues[1]
            }
            return value.takeWhile { it.isLetterOrDigit() || it == '_' || it == '-' }
        }

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
