package fr.nicovers06.streamstudio.stream

import kotlin.math.roundToInt

internal object Pcm16Mixer {
    fun mix(
        base: ByteArray,
        baseOffset: Int,
        size: Int,
        overlay: ByteArray,
    ): ByteArray {
        require(baseOffset >= 0 && size >= 0 && baseOffset + size <= base.size)
        val output = base.copyOfRange(baseOffset, baseOffset + size)
        val mixedSize = minOf(size, overlay.size) and -2
        for (index in 0 until mixedSize step 2) {
            val baseSample = sample(output, index)
            val overlaySample = sample(overlay, index)
            writeSample(output, index, (baseSample + overlaySample).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()))
        }
        return output
    }

    private fun sample(buffer: ByteArray, offset: Int): Int =
        (((buffer[offset + 1].toInt() and 0xFF) shl 8) or (buffer[offset].toInt() and 0xFF))
            .toShort()
            .toInt()

    private fun writeSample(buffer: ByteArray, offset: Int, sample: Int) {
        buffer[offset] = (sample and 0xFF).toByte()
        buffer[offset + 1] = ((sample ushr 8) and 0xFF).toByte()
    }
}

internal class PcmByteQueue(capacityBytes: Int) {
    private val lock = Object()
    private val buffer = ByteArray(capacityBytes.coerceAtLeast(2) and -2)
    private var readIndex = 0
    private var writeIndex = 0
    private var available = 0

    fun write(source: ByteArray, offset: Int, size: Int) {
        require(offset >= 0 && size >= 0 && offset + size <= source.size)
        val alignedSize = size and -2
        if (alignedSize == 0) return
        synchronized(lock) {
            val sourceOffset: Int
            val bytesToWrite: Int
            if (alignedSize >= buffer.size) {
                sourceOffset = offset + alignedSize - buffer.size
                bytesToWrite = buffer.size
                readIndex = 0
                writeIndex = 0
                available = 0
            } else {
                sourceOffset = offset
                bytesToWrite = alignedSize
                discardLocked((available + bytesToWrite - buffer.size).coerceAtLeast(0))
            }
            copyIntoRingLocked(source, sourceOffset, bytesToWrite)
            available += bytesToWrite
            lock.notifyAll()
        }
    }

    fun read(target: ByteArray, offset: Int, size: Int): Int {
        require(offset >= 0 && size >= 0 && offset + size <= target.size)
        synchronized(lock) {
            val bytesToRead = minOf(size and -2, available)
            if (bytesToRead == 0) return 0
            val first = minOf(bytesToRead, buffer.size - readIndex)
            buffer.copyInto(target, offset, readIndex, readIndex + first)
            val second = bytesToRead - first
            if (second > 0) buffer.copyInto(target, offset + first, 0, second)
            readIndex = (readIndex + bytesToRead) % buffer.size
            available -= bytesToRead
            return bytesToRead
        }
    }

    fun awaitData(timeoutMillis: Long): Boolean {
        synchronized(lock) {
            if (available > 0) return true
            if (timeoutMillis > 0L) runCatching { lock.wait(timeoutMillis) }
            return available > 0
        }
    }

    fun clear() {
        synchronized(lock) {
            readIndex = 0
            writeIndex = 0
            available = 0
        }
    }

    private fun discardLocked(size: Int) {
        val bytesToDiscard = minOf(size and -2, available)
        readIndex = (readIndex + bytesToDiscard) % buffer.size
        available -= bytesToDiscard
    }

    private fun copyIntoRingLocked(source: ByteArray, offset: Int, size: Int) {
        val first = minOf(size, buffer.size - writeIndex)
        source.copyInto(buffer, writeIndex, offset, offset + first)
        val second = size - first
        if (second > 0) source.copyInto(buffer, 0, offset + first, offset + size)
        writeIndex = (writeIndex + size) % buffer.size
    }
}

internal class StreamingPcm16Converter(
    private val inputSampleRate: Int,
    private val inputChannelCount: Int,
    private val outputSampleRate: Int,
    private val outputChannelCount: Int,
) {
    private val inputFrameSize = inputChannelCount * 2
    private val outputFrameSize = outputChannelCount * 2
    private val inputStep = inputSampleRate.toDouble() / outputSampleRate.toDouble()
    private var nextOutputPosition = 0.0
    private var inputFramePosition = 0L
    private var hasPreviousFrame = false
    private var previousLeft = 0
    private var previousRight = 0

    init {
        require(inputSampleRate > 0 && outputSampleRate > 0)
        require(inputChannelCount in 1..2 && outputChannelCount in 1..2)
    }

    fun convert(source: ByteArray, offset: Int, size: Int): ByteArray {
        require(offset >= 0 && size >= 0 && offset + size <= source.size)
        val inputFrames = size / inputFrameSize
        if (inputFrames == 0) return ByteArray(0)
        val estimatedFrames = (inputFrames * outputSampleRate.toDouble() / inputSampleRate + 2.0)
            .roundToInt()
            .coerceAtLeast(2)
        val output = ByteArray(estimatedFrames * outputFrameSize)
        var outputOffset = 0
        for (frameIndex in 0 until inputFrames) {
            val sourceOffset = offset + frameIndex * inputFrameSize
            val currentLeft = readSample(source, sourceOffset)
            val currentRight = if (inputChannelCount == 2) readSample(source, sourceOffset + 2) else currentLeft
            val currentPosition = inputFramePosition.toDouble()
            if (!hasPreviousFrame) {
                previousLeft = currentLeft
                previousRight = currentRight
                hasPreviousFrame = true
            }
            while (nextOutputPosition <= currentPosition) {
                val fraction = if (inputFramePosition == 0L) {
                    0.0
                } else {
                    (nextOutputPosition - (currentPosition - 1.0)).coerceIn(0.0, 1.0)
                }
                val left = interpolate(previousLeft, currentLeft, fraction)
                val right = interpolate(previousRight, currentRight, fraction)
                if (outputOffset + outputFrameSize > output.size) break
                writeFrame(output, outputOffset, left, right)
                outputOffset += outputFrameSize
                nextOutputPosition += inputStep
            }
            previousLeft = currentLeft
            previousRight = currentRight
            inputFramePosition++
        }
        return output.copyOf(outputOffset)
    }

    fun reset() {
        nextOutputPosition = 0.0
        inputFramePosition = 0L
        hasPreviousFrame = false
        previousLeft = 0
        previousRight = 0
    }

    private fun writeFrame(target: ByteArray, offset: Int, left: Int, right: Int) {
        writeSample(target, offset, left)
        if (outputChannelCount == 2) writeSample(target, offset + 2, right)
    }

    private fun interpolate(start: Int, end: Int, fraction: Double): Int =
        (start + (end - start) * fraction).roundToInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())

    private fun readSample(source: ByteArray, offset: Int): Int =
        (((source[offset + 1].toInt() and 0xFF) shl 8) or (source[offset].toInt() and 0xFF))
            .toShort()
            .toInt()

    private fun writeSample(target: ByteArray, offset: Int, sample: Int) {
        target[offset] = (sample and 0xFF).toByte()
        target[offset + 1] = ((sample ushr 8) and 0xFF).toByte()
    }
}
