package com.diriktv.sidekick.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Dev-only: runs the leveler over raw 48 kHz stereo s16le audio (LEVELER_IN) and writes LEVELER_OUT. */
class LevelerHarness {
    @Test
    fun run() {
        val inPath = System.getenv("LEVELER_IN")
        assumeTrue(inPath != null)
        val bytes = File(inPath!!).readBytes()
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val p = LoudnessLeveler(true)
        p.configure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT))
        p.flush()
        val out = java.io.ByteArrayOutputStream()
        val chunk = 4096 * 4
        while (data.hasRemaining()) {
            val n = minOf(chunk, data.remaining())
            val b = ByteBuffer.allocateDirect(n).order(ByteOrder.nativeOrder())
            val slice = data.slice().order(ByteOrder.LITTLE_ENDIAN); slice.limit(n)
            b.put(slice); b.flip()
            data.position(data.position() + n)
            p.queueInput(b)
            val o = p.output
            val arr = ByteArray(o.remaining()); o.get(arr); out.write(arr)
        }
        val pcm = out.toByteArray()
        RandomAccessFile(System.getenv("LEVELER_OUT"), "rw").use { f ->
            f.setLength(0)
            f.write(pcm)
        }
    }
}
