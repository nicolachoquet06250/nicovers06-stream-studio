package fr.nicovers06.streamstudio.stream

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
import android.text.TextUtils
import android.view.Surface
import fr.nicovers06.streamstudio.model.ChatMessage

class ChatOverlayRenderer(surfaceTexture: SurfaceTexture) {
    private val width = 640
    private val height = 360
    private val mainHandler = Handler(Looper.getMainLooper())
    private val surface: Surface
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD9141821.toInt() }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2C3442.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val headingPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFAAB2C0.toInt()
        textSize = 19f
        isFakeBoldText = true
        letterSpacing = 0.1f
    }
    private val authorPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 24f
        isFakeBoldText = true
    }
    private val messagePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 23f
    }

    init {
        surfaceTexture.setDefaultBufferSize(width, height)
        surface = Surface(surfaceTexture)
    }

    fun update(messages: List<ChatMessage>, enabled: Boolean) {
        mainHandler.post { draw(messages.takeLast(4), enabled) }
    }

    fun release() {
        mainHandler.removeCallbacksAndMessages(null)
        surface.release()
    }

    private fun draw(messages: List<ChatMessage>, enabled: Boolean) {
        if (!surface.isValid) return
        val canvas = runCatching { surface.lockCanvas(null) }.getOrNull() ?: return
        try {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            if (!enabled) return
            val panel = RectF(2f, 2f, width - 2f, height - 2f)
            canvas.drawRoundRect(panel, 24f, 24f, backgroundPaint)
            canvas.drawRoundRect(panel, 24f, 24f, borderPaint)
            canvas.drawText("CHAT EN DIRECT", 24f, 38f, headingPaint)

            var y = 82f
            messages.forEach { message ->
                authorPaint.color = message.accent
                val author = TextUtils.ellipsize(message.author, authorPaint, 175f, TextUtils.TruncateAt.END).toString()
                canvas.drawText(author, 24f, y, authorPaint)
                val authorWidth = authorPaint.measureText(author)
                val available = width - 42f - authorWidth
                val text = TextUtils.ellipsize(message.text, messagePaint, available, TextUtils.TruncateAt.END).toString()
                canvas.drawText(text, 34f + authorWidth, y, messagePaint)
                y += 62f
            }
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
    }
}
