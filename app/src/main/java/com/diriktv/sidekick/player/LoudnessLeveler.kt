package com.diriktv.sidekick.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Loudness equalization, in two stages. A slow leveler tracks how loud the last second or so has
 * been and turns quiet talking up and loud streams down towards one level; it follows audio
 * getting quieter faster than louder, so one shout doesn't leave the next minute turned down.
 * A fast compressor then catches shouts and sudden bursts within a few milliseconds, and a
 * limiter stops any boost from clipping. Near-silence doesn't count, so pauses aren't pumped up.
 *
 * Tuned on real streams with badly mixed audio: quiet speech comes up by 10–20 dB and bursts
 * come down by 5–10 dB, without the level wobbling more between words than the original.
 *
 * It runs on the decoded audio inside the player, so it behaves the same on every TV.
 * Turning it off fades the gain back to unity rather than jumping.
 */
class LoudnessLeveler(enabled: Boolean) : BaseAudioProcessor() {
    companion object {
        /** Level it aims for, as (bass-filtered) RMS in dBFS; roughly where most streams sit. */
        const val TARGET_DB = -20f
        const val MAX_BOOST_DB = 20f
        const val MAX_CUT_DB = -15f
        /** Blocks quieter than this (pauses, silence) don't move the loudness estimate. */
        private const val GATE_DB = -55f
        /** How quickly the estimate follows audio getting louder / quieter. */
        private const val RISE_S = 1.5f
        private const val FALL_S = 0.5f
        /** How quickly the leveler's gain comes down / goes up. */
        private const val ATTACK_S = 0.3f
        private const val RELEASE_S = 0.4f
        /** The compressor starts this far above the target, at this ratio, with these speeds. */
        private const val COMP_ABOVE_DB = 5f
        private const val COMP_RATIO = 4f
        private const val COMP_ATTACK_S = 0.005f
        private const val COMP_RELEASE_S = 0.12f
        /** Boosted peaks are held under this (about -1 dBFS). */
        private const val CEILING = 0.89f
        private const val BLOCK_MS = 10
        /** The estimate ignores rumble below this, so bass-heavy music isn't judged too loud. */
        private const val HIGH_PASS_HZ = 120f

        private fun dbToGain(db: Float) = 10f.pow(db / 20f)
        private fun powerToDb(p: Double) = (10 * log10(p + 1e-12)).toFloat()
    }

    /** Can be flipped while playing. */
    @Volatile var enabled = enabled

    /** Gain being applied right now, in dB (shown in Stats for nerds). */
    @Volatile var currentGainDb = 0f
        private set

    private var channels = 0
    private var blockFrames = 0
    private var riseCoef = 0f
    private var fallCoef = 0f
    private var attackCoef = 0f
    private var releaseCoef = 0f
    private var hpCoef = 0f
    private var compAttackCoef = 0f
    private var compReleaseCoef = 0f
    /** The compressor's view of the (levelled) block level, in dB. */
    private var compEnv = Float.NEGATIVE_INFINITY

    /** Smoothed mean-square loudness; starts at the target so a new stream begins at unity gain. */
    private var loudness = 0.0
    private var smoothGain = 1f
    private var appliedGain = 1f
    private var hpPrevIn = FloatArray(0)
    private var hpPrevOut = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        // Kick's AAC decodes to 16-bit PCM; anything else passes through untouched.
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount <= 0) return AudioFormat.NOT_SET
        channels = inputAudioFormat.channelCount
        val rate = inputAudioFormat.sampleRate
        blockFrames = max(1, rate * BLOCK_MS / 1000)
        val block = blockFrames.toFloat() / rate
        riseCoef = exp(-block / RISE_S)
        fallCoef = exp(-block / FALL_S)
        attackCoef = exp(-block / ATTACK_S)
        releaseCoef = exp(-block / RELEASE_S)
        compAttackCoef = exp(-block / COMP_ATTACK_S)
        compReleaseCoef = exp(-block / COMP_RELEASE_S)
        val rc = 1f / (2f * Math.PI.toFloat() * HIGH_PASS_HZ)
        hpCoef = rc / (rc + 1f / rate)
        return inputAudioFormat
    }

    override fun onReset() {
        loudness = 0.0
        smoothGain = 1f
        appliedGain = 1f
        compEnv = Float.NEGATIVE_INFINITY
        currentGainDb = 0f
        hpPrevIn = FloatArray(0)
        hpPrevOut = FloatArray(0)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        if (bytes == 0) return
        val out = replaceOutputBuffer(bytes)
        if (hpPrevIn.size != channels) {
            hpPrevIn = FloatArray(channels)
            hpPrevOut = FloatArray(channels)
        }
        if (loudness <= 0.0) loudness = 10.0.pow(TARGET_DB / 10.0)

        val frameBytes = 2 * channels
        var frames = bytes / frameBytes
        while (frames > 0) {
            val n = min(blockFrames, frames)
            val start = inputBuffer.position()

            // Measure the block: high-passed power for loudness, raw peak for the limiter.
            var sum = 0.0
            var peak = 0
            for (i in 0 until n) {
                for (c in 0 until channels) {
                    val s = inputBuffer.getShort(start + (i * channels + c) * 2).toInt()
                    peak = max(peak, abs(s))
                    val x = s / 32768f
                    val y = hpCoef * (hpPrevOut[c] + x - hpPrevIn[c])
                    hpPrevIn[c] = x
                    hpPrevOut[c] = y
                    sum += (y * y).toDouble()
                }
            }
            val power = sum / (n * channels)
            if (powerToDb(power) > GATE_DB) {
                val coef = if (power > loudness) riseCoef else fallCoef
                loudness = coef * loudness + (1 - coef) * power
            }

            val desired = if (enabled) dbToGain((TARGET_DB - powerToDb(loudness)).coerceIn(MAX_CUT_DB, MAX_BOOST_DB)) else 1f
            val coef = if (desired < smoothGain) attackCoef else releaseCoef
            smoothGain = coef * smoothGain + (1 - coef) * desired

            // Compressor: how far this block would sit above the target once levelled.
            var gain = smoothGain
            if (enabled) {
                val level = powerToDb(power) + 20 * log10(smoothGain)
                compEnv = if (compEnv == Float.NEGATIVE_INFINITY) level else {
                    val c = if (level > compEnv) compAttackCoef else compReleaseCoef
                    c * compEnv + (1 - c) * level
                }
                val over = compEnv - (TARGET_DB + COMP_ABOVE_DB)
                if (over > 0) gain *= dbToGain(-over * (1 - 1 / COMP_RATIO))
            }

            // The limiter keeps peaks under the ceiling. It only ever undoes a boost: audio that
            // was already that loud is left as it came. Its limit applies from the first sample
            // of the block; other changes ramp across it.
            val limit = if (peak > 0) max(min(1f, gain), CEILING * 32768f / peak) else Float.MAX_VALUE
            val from = min(appliedGain, limit)
            val to = min(gain, limit)
            for (i in 0 until n) {
                val g = from + (to - from) * (i + 1) / n
                for (c in 0 until channels) {
                    val s = inputBuffer.getShort(start + (i * channels + c) * 2)
                    val v = (s * g).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    out.putShort(v.toShort())
                }
            }
            appliedGain = to
            inputBuffer.position(start + n * frameBytes)
            frames -= n
        }
        currentGainDb = 20 * log10(appliedGain)
        // A stray partial frame (shouldn't happen) passes through.
        while (inputBuffer.hasRemaining()) out.put(inputBuffer.get())
        out.flip()
    }
}
