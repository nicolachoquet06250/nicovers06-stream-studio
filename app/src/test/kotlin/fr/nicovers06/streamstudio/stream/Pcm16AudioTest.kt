package fr.nicovers06.streamstudio.stream

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class Pcm16AudioTest {
    @Test
    fun mixerAddsPcmSamplesAndSaturates() {
        val base = pcm(30_000, -30_000, 1_000)
        val overlay = pcm(10_000, -10_000, -250)

        val mixed = Pcm16Mixer.mix(base, 0, base.size, overlay)

        assertArrayEquals(pcm(Short.MAX_VALUE.toInt(), Short.MIN_VALUE.toInt(), 750), mixed)
    }

    @Test
    fun queueKeepsNewestSamplesWhenFull() {
        val queue = PcmByteQueue(capacityBytes = 8)
        queue.write(pcm(1, 2, 3), 0, 6)
        queue.write(pcm(4, 5), 0, 4)
        val output = ByteArray(8)

        val read = queue.read(output, 0, output.size)

        assertEquals(8, read)
        assertArrayEquals(pcm(2, 3, 4, 5), output)
    }

    @Test
    fun converterResamplesMonoToStereo() {
        val converter = StreamingPcm16Converter(
            inputSampleRate = 22_050,
            inputChannelCount = 1,
            outputSampleRate = 44_100,
            outputChannelCount = 2,
        )

        val converted = converter.convert(pcm(1_000, 1_000, 1_000, 1_000), 0, 8)

        assertEquals(7 * 4, converted.size)
        assertArrayEquals(pcm(*IntArray(14) { 1_000 }), converted)
    }

    private fun pcm(vararg samples: Int): ByteArray = ByteArray(samples.size * 2).also { output ->
        samples.forEachIndexed { index, sample ->
            val offset = index * 2
            output[offset] = (sample and 0xFF).toByte()
            output[offset + 1] = ((sample ushr 8) and 0xFF).toByte()
        }
    }
}
