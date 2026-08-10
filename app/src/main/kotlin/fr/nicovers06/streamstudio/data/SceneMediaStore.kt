package fr.nicovers06.streamstudio.data

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID

/** Stockage interne des vidéos locales utilisées par le widget Média. */
object SceneMediaStore {
    private const val DIR = "scene_media"
    private const val MAX_FILE_BYTES = 512L * 1024L * 1024L

    fun directory(context: Context): File = File(context.filesDir, DIR).also { it.mkdirs() }

    fun fileFor(context: Context, fileName: String): File =
        File(directory(context), File(fileName).name)

    fun importFromUri(context: Context, uri: Uri): ImportedMedia? {
        val resolver = context.contentResolver
        val displayName = queryDisplayName(context, uri)?.substringAfterLast('/') ?: "video"
        val extension = displayName.substringAfterLast('.', missingDelimiterValue = "")
            .lowercase()
            .takeIf { it.length in 2..5 && it.all(Char::isLetterOrDigit) }
            ?.let { ".$it" }
            ?: ".mp4"
        val fileName = "${UUID.randomUUID()}$extension"
        val target = fileFor(context, fileName)
        return runCatching {
            resolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        check(total <= MAX_FILE_BYTES) { "Fichier média trop volumineux" }
                        output.write(buffer, 0, read)
                    }
                }
            } ?: return null
            val metadata = probe(target) ?: error("Vidéo illisible")
            ImportedMedia(
                fileName = fileName,
                displayName = displayName,
                width = metadata.first,
                height = metadata.second,
            )
        }.onFailure {
            runCatching { target.delete() }
        }.getOrNull()
    }

    fun isPlayable(context: Context, fileName: String): Boolean = probe(fileFor(context, fileName)) != null

    fun displaySize(context: Context, fileName: String): Pair<Int, Int>? =
        probe(fileFor(context, fileName))

    fun audioInfo(context: Context, fileName: String): AudioInfo? =
        probeAudio(fileFor(context, fileName))

    fun delete(context: Context, fileName: String) {
        if (fileName.isBlank()) return
        runCatching { fileFor(context, fileName).delete() }
    }

    private fun probe(file: File): Pair<Int, Int>? {
        if (!file.isFile || file.length() <= 0L) return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull().orZero()
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull().orZero()
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull().orZero()
            val normalizedRotation = ((rotation % 360) + 360) % 360
            if (width <= 0 || height <= 0) {
                null
            } else if (normalizedRotation == 90 || normalizedRotation == 270) {
                height to width
            } else {
                width to height
            }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun probeAudio(file: File): AudioInfo? {
        if (!file.isFile || file.length() <= 0L) return null
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            (0 until extractor.trackCount).firstNotNullOfOrNull { index ->
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (!mime.startsWith("audio/", ignoreCase = true)) {
                    null
                } else {
                    val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    AudioInfo(sampleRate, channelCount).takeIf {
                        it.sampleRate > 0 && it.channelCount > 0
                    }
                }
            }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index)
            }
        }
        return uri.lastPathSegment
    }

    private fun Int?.orZero(): Int = this ?: 0

    data class ImportedMedia(
        val fileName: String,
        val displayName: String,
        val width: Int,
        val height: Int,
    )

    data class AudioInfo(
        val sampleRate: Int,
        val channelCount: Int,
    ) {
        val decoderChannelCount: Int get() = if (channelCount > 1) 2 else 1
        val isStereo: Boolean get() = decoderChannelCount == 2
    }
}
