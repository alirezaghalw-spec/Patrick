package com.personal.emo

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

object Sounds {
    private const val SR = 22050
    private val tracks = HashMap<String, AudioTrack>()

    private fun s(f0: Double, f1: Double, ms: Int, vib: Double = 0.0, vd: Double = 0.0, square: Boolean = false): ShortArray {
        val n = SR * ms / 1000
        val out = ShortArray(n)
        var ph = 0.0
        for (i in 0 until n) {
            val p = i.toDouble() / n
            val f = f0 + (f1 - f0) * p + sin(2 * PI * vib * i / SR) * vd
            ph += 2 * PI * f / SR
            var v = sin(ph)
            if (square) v = if (v > 0) 0.6 else -0.6
            val env = min(1.0, i / (SR * 0.008)) * min(1.0, (n - i) / (SR * 0.03))
            out[i] = (v * env * 0.45 * 32767).toInt().toShort()
        }
        return out
    }

    private fun g(ms: Int) = ShortArray(SR * ms / 1000)

    fun init() {
        if (tracks.isNotEmpty()) return
        add("hello", s(500.0, 1400.0, 150) + g(30) + s(900.0, 1700.0, 170))
        add("giggle", s(800.0, 1000.0, 70) + g(40) + s(850.0, 1100.0, 70) + g(40) + s(900.0, 1250.0, 90))
        add("love", s(700.0, 900.0, 120, 12.0, 60.0) + s(900.0, 1150.0, 260, 12.0, 80.0))
        add("wow", s(400.0, 1700.0, 240))
        add("angry", s(180.0, 140.0, 260, 30.0, 40.0, true))
        var tr = ShortArray(0)
        for (f in doubleArrayOf(700.0, 900.0, 800.0, 1100.0, 1000.0, 1300.0)) tr = tr + s(f, f * 1.15, 70)
        add("trill", tr)
        add("sleepy", s(520.0, 240.0, 520, 4.0, 20.0))
        add("boop", s(320.0, 190.0, 130))
        add("cry", s(700.0, 350.0, 300, 6.0, 30.0) + g(40) + s(650.0, 300.0, 350, 6.0, 30.0))
        add("dizzy", s(300.0, 900.0, 500, 8.0, 150.0) + s(900.0, 300.0, 500, 8.0, 150.0))
        add("chirp", s(600.0, 1300.0, 110) + g(30) + s(1300.0, 900.0, 90))
    }

    private fun add(name: String, d: ShortArray) {
        try {
            val t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SR).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(d.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            t.write(d, 0, d.size)
            t.setVolume(0.6f)
            tracks[name] = t
        } catch (e: Exception) {
        }
    }

    fun play(name: String, vol: Float = 0.6f) {
        try {
            val t = tracks[name] ?: return
            t.setVolume(vol)
            t.stop()
            t.reloadStaticData()
            t.play()
        } catch (e: Exception) {
        }
    }

    fun release() {
        for (t in tracks.values) try { t.release() } catch (e: Exception) { }
        tracks.clear()
    }
}
