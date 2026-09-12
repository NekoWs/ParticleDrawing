package work.nekow.particledrawing.animation.script

import work.nekow.particledrawing.animation.AudioAsset
import kotlin.math.floor

/**
 * 音频特征查表（与编辑器 core/audio-assets.js 的 audioValueAt 逐位一致）。
 * 特征列是编辑器导入时烘焙的量化数据：rms/peak/centroid 为 u16（0..65535 映射 0..1 或 0..nyquist），
 * onset/rolloff/bands 为 u8（0..255）。查询时刻 localMs 落在两 hop 之间时线性插值。
 */
object ScriptAudio {

    const val BANDS = 16
    const val NYQUIST = 22050.0

    data class Values(
        val rms: Double,
        val peak: Double,
        val centroid: Double,
        val rolloff: Double,
        val onset: Double,
        val bands: DoubleArray,
    )

    /** 播放头是否在音频区间 [st, st+durMs) 内。 */
    fun windowAt(a: AudioAsset, ms: Double): Boolean {
        val st = a.st.toDouble()
        return ms >= st && ms < st + a.durMs
    }

    fun valueAt(a: AudioAsset, localMs: Double): Values {
        val n = a.hopCount
        if (n <= 0) return Values(0.0, 0.0, 0.0, 0.0, 0.0, DoubleArray(BANDS))
        val durMs = if (a.durMs > 0) a.durMs.toDouble() else n * (512.0 / 44100.0 * 1000.0)
        val pos = localMs.coerceIn(0.0, durMs) / (durMs / n)
        var i = floor(pos).toInt()
        var f = pos - i
        if (i >= n - 1) { i = n - 1; f = 0.0 }
        if (i < 0) { i = 0; f = 0.0 }
        val j = i + if (f > 0.0) 1 else 0
        val u16 = { a: Int -> (a and 0xFFFF) / 65535.0 }
        val u8 = { a: Int -> (a and 0xFF) / 255.0 }
        val lerp = { a: Double, b: Double -> a + (b - a) * f }
        val out = DoubleArray(BANDS)
        for (b in 0 until BANDS) {
            out[b] = lerp(u8(a.bands[i * BANDS + b].toInt()), u8(a.bands[j * BANDS + b].toInt()))
        }
        return Values(
            rms = lerp(u16(a.rms[i].toInt()), u16(a.rms[j].toInt())),
            peak = lerp(u16(a.peak[i].toInt()), u16(a.peak[j].toInt())),
            centroid = lerp(u16(a.centroid[i].toInt()), u16(a.centroid[j].toInt())) * NYQUIST,
            rolloff = lerp(u8(a.rolloff[i].toInt()), u8(a.rolloff[j].toInt())) * NYQUIST,
            onset = lerp(u8(a.onset[i].toInt()), u8(a.onset[j].toInt())) * a.onsetMax,
            bands = out,
        )
    }
}