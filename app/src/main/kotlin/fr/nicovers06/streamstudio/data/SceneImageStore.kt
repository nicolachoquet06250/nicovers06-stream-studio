package fr.nicovers06.streamstudio.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID

/**
 * Copie les images choisies dans le stockage interne de l’app
 * et les décode via les APIs Android (JPEG, PNG, WebP, GIF, BMP, HEIF/HEIC selon l’appareil).
 */
object SceneImageStore {
    private const val DIR = "scene_images"
    private const val MAX_DECODE_SIDE = 1920

    fun directory(context: Context): File =
        File(context.filesDir, DIR).also { it.mkdirs() }

    fun fileFor(context: Context, fileName: String): File =
        File(directory(context), fileName)

    fun importFromUri(context: Context, uri: Uri): ImportedImage? {
        val resolver = context.contentResolver
        val displayName = queryDisplayName(context, uri) ?: "image"
        val mime = resolver.getType(uri).orEmpty()
        val ext = extensionFor(mime, displayName)
        val fileName = "${UUID.randomUUID()}$ext"
        val target = fileFor(context, fileName)
        return runCatching {
            resolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            // Valide que le fichier est décodable avant de le garder.
            val probe = decodeFile(target, maxSide = 64) ?: run {
                target.delete()
                return null
            }
            probe.recycle()
            ImportedImage(fileName = fileName, displayName = displayName.substringAfterLast('/'))
        }.getOrNull()
    }

    fun delete(context: Context, fileName: String) {
        if (fileName.isBlank()) return
        runCatching { fileFor(context, fileName).delete() }
    }

    fun decodeFile(file: File, maxSide: Int = MAX_DECODE_SIDE): Bitmap? {
        if (!file.isFile || file.length() <= 0L) return null
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(file)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.isMutableRequired = false
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val w = info.size.width.coerceAtLeast(1)
                    val h = info.size.height.coerceAtLeast(1)
                    val longest = maxOf(w, h)
                    if (longest > maxSide) {
                        val sample = (longest + maxSide - 1) / maxSide
                        decoder.setTargetSampleSize(sample.coerceAtLeast(1))
                    }
                }.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                val longest = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
                var sample = 1
                while (longest / sample > maxSide) sample *= 2
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                BitmapFactory.decodeFile(file.absolutePath, opts)
            }
        }.getOrNull()
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        val resolver = context.contentResolver
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index)
            }
        }
        return uri.lastPathSegment
    }

    private fun extensionFor(mime: String, displayName: String): String {
        val fromName = displayName.substringAfterLast('.', missingDelimiterValue = "")
            .lowercase()
            .takeIf { it.length in 2..5 && it.all { ch -> ch.isLetterOrDigit() } }
        if (fromName != null) return ".$fromName"
        return when (mime.lowercase()) {
            "image/jpeg", "image/jpg" -> ".jpg"
            "image/png" -> ".png"
            "image/webp" -> ".webp"
            "image/gif" -> ".gif"
            "image/bmp", "image/x-ms-bmp" -> ".bmp"
            "image/heic", "image/heif" -> ".heic"
            "image/avif" -> ".avif"
            else -> ".img"
        }
    }

    data class ImportedImage(
        val fileName: String,
        val displayName: String,
    )
}
