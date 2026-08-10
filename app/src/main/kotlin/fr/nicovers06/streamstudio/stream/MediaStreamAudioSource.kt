package fr.nicovers06.streamstudio.stream

import android.content.Context
import android.net.Uri
import com.pedro.encoder.Frame
import com.pedro.encoder.input.audio.GetMicrophoneData
import com.pedro.encoder.input.sources.audio.AudioFileSource
import com.pedro.encoder.input.sources.audio.AudioSource
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.audio.SilenceAudioSource
import fr.nicovers06.streamstudio.data.SceneMediaStore
import java.io.File

/** Mélange la piste PCM du widget Média au micro, sans capture audio système. */
internal class MediaStreamAudioSource(
    private val context: Context,
    private val file: File,
    private val mediaInfo: SceneMediaStore.AudioInfo,
    private val loop: Boolean,
    private val microphoneEnabled: Boolean,
    private val startPositionMs: Long,
    private val onMediaAudioUnavailable: () -> Unit,
) : AudioSource() {
    private var baseSource: AudioSource? = null
    private var mediaSource: AudioFileSource? = null
    private var converter: StreamingPcm16Converter? = null
    private var mediaQueue = PcmByteQueue(MIN_QUEUE_BYTES)
    @Volatile private var running = false

    override fun create(
        sampleRate: Int,
        isStereo: Boolean,
        echoCanceler: Boolean,
        noiseSuppressor: Boolean,
    ): Boolean {
        val base = if (microphoneEnabled) MicrophoneSource() else SilenceAudioSource()
        if (!base.init(sampleRate, isStereo, echoCanceler, noiseSuppressor)) return false
        baseSource = base
        converter = StreamingPcm16Converter(
            inputSampleRate = mediaInfo.sampleRate,
            inputChannelCount = mediaInfo.decoderChannelCount,
            outputSampleRate = sampleRate,
            outputChannelCount = if (isStereo) 2 else 1,
        )
        val channelCount = if (isStereo) 2 else 1
        mediaQueue = PcmByteQueue((sampleRate * channelCount * 2 * QUEUE_SECONDS).coerceAtLeast(MIN_QUEUE_BYTES))
        return true
    }

    override fun start(getMicrophoneData: GetMicrophoneData) {
        if (running) return
        val base = checkNotNull(baseSource) { "Source audio non préparée" }
        this.getMicrophoneData = getMicrophoneData
        running = true
        mediaQueue.clear()
        converter?.reset()
        startMediaDecoder()
        try {
            base.start(object : GetMicrophoneData {
                override fun inputPCMData(frame: Frame) {
                    if (!running) return
                    val mediaPcm = ByteArray(frame.size)
                    mediaQueue.read(mediaPcm, 0, mediaPcm.size)
                    val mixed = Pcm16Mixer.mix(
                        base = frame.buffer,
                        baseOffset = frame.offset,
                        size = frame.size,
                        overlay = mediaPcm,
                    )
                    this@MediaStreamAudioSource.getMicrophoneData?.inputPCMData(
                        Frame(mixed, 0, mixed.size, frame.timeStamp),
                    )
                }
            })
        } catch (error: RuntimeException) {
            stop()
            throw error
        }
    }

    override fun stop() {
        running = false
        runCatching { baseSource?.stop() }
        val media = mediaSource
        if (media?.isRunning() == true) runCatching { media.stop() }
        mediaSource = null
        mediaQueue.clear()
        converter?.reset()
        getMicrophoneData = null
    }

    override fun isRunning(): Boolean = running && baseSource?.isRunning() == true

    override fun release() {
        stop()
        runCatching { baseSource?.release() }
        baseSource = null
        converter = null
    }

    private fun startMediaDecoder() {
        val source = AudioFileSource(context, Uri.fromFile(file), loopMode = loop)
        val ready = runCatching {
            check(source.init(mediaInfo.sampleRate, mediaInfo.isStereo, false, false))
            source.start(object : GetMicrophoneData {
                override fun inputPCMData(frame: Frame) {
                    if (!running) return
                    val converted = converter?.convert(frame.buffer, frame.offset, frame.size) ?: return
                    mediaQueue.write(converted, 0, converted.size)
                }
            })
            mediaQueue.awaitData(DECODER_PREROLL_MILLIS)
            val positionMs = startPositionMs.coerceAtLeast(0L)
            if (positionMs > SEEK_THRESHOLD_MILLIS) {
                source.moveTo(positionMs / 1_000.0)
                converter?.reset()
                mediaQueue.clear()
                mediaQueue.awaitData(DECODER_PREROLL_MILLIS)
            }
        }.isSuccess
        if (ready) {
            mediaSource = source
        } else {
            if (source.isRunning()) runCatching { source.stop() }
            onMediaAudioUnavailable()
        }
    }

    companion object {
        private const val QUEUE_SECONDS = 2
        private const val MIN_QUEUE_BYTES = 8_192
        private const val DECODER_PREROLL_MILLIS = 60L
        private const val SEEK_THRESHOLD_MILLIS = 100L
    }
}
