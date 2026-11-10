package work.nekow.particledrawing.animation.script

import work.nekow.particledrawing.animation.AudioAsset
import kotlin.math.floor

/**
 * 音频特征查表，与编辑器的 audioValueAt 逐位一致。
 *
 * rms/peak/centroid 为 u16（0..65535 映射 0..1 或 0..nyquist），onset/rolloff/bands 为 u8（0..255）；
 * 查询时刻 localMs 落在两 hop 之间时线性插值。
 */
object ScriptAudio {

    const val BANDS = 16
    const val NYQUIST = 22050.0

    /** 特征 hop 宽（毫秒）：512 采样 @ 44.1kHz，与编辑器的 AUDIO_HOP_MS 同值。 */
    const val HOP_MS = 512.0 / 44100.0 * 1000.0

    data class Values(
        val rms: Double,
        val peak: Double,
        val centroid: Double,
        val rolloff: Double,
        val onset: Double,
        val bands: DoubleArray,
    )

    /**
     * 内容时长（毫秒）：优先用资产自带的 durMs；
     * durMs <= 0（文件没写时长）时按 hop 数推算，与 [valueAt] 的查表跨度同一口径。
     */
    fun durationMs(a: AudioAsset): Double =
        if (a.durMs > 0) a.durMs.toDouble() else a.hopCount * HOP_MS

    /** 播放头（时间轴毫秒）是否落在音频内容区间 [st, st+时长) 内；时长为 0 时恒 false。 */
    fun windowAt(a: AudioAsset, ms: Double): Boolean {
        val st = a.st.toDouble()
        return ms >= st && ms < st + durationMs(a)
    }

    fun valueAt(a: AudioAsset, localMs: Double): Values {
        val n = a.hopCount
        if (n <= 0) return Values(0.0, 0.0, 0.0, 0.0, 0.0, DoubleArray(BANDS))
        val durMs = durationMs(a)
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