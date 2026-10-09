package work.nekow.particledrawing.animation.script

import org.lwjgl.stb.STBVorbis
import org.lwjgl.stb.STBVorbisInfo
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import work.nekow.particledrawing.animation.AudioAsset
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * 脚本采样级 PCM：a.sampleAt(ms[, ch]) / a.peakAt(ms, win[, ch]) 取真实波形，
 * a.sampleRate / a.channels / a.waveReady 为状态字段。ms 为资产本地毫秒，单点线性插值，
 * peakAt 取半开区间 [ms, ms+win) 内 |sample| 最大，窗口不足一个采样取该点，越界与未就绪一律回 0。
 *
 * WAV（PCM）按 data 偏移直读原始字节；OGG 按窗口解码（8 秒一个窗口，最多留 2 个）。
 * 采样按资产对象弱引用缓存（动画卸载后随对象回收），只在客户端主线程使用。
 */
object ScriptWavePcm {

    /** OGG 解码窗口长度（毫秒）：挪一次窗口是一次 seek 加顺序解码。 */
    private const val OGG_WINDOW_MS = 8000

    /** 每个 OGG 资产最多留几个已解码窗口（LRU 淘汰）。 */
    private const val OGG_WINDOW_SLOTS = 2

    /** 单窗口帧数上限，防止异常采样率把窗口撑爆内存。 */
    private const val OGG_WINDOW_MAX_FRAMES = 4_000_000L

    /** 采样缓存；值为 null 表示该资产没有可读的采样（非 PCM WAV、OGG 打不开）。 */
    private val cache = WeakHashMap<AudioAsset, Pcm?>()

    /** 采样数据是否就绪（WAV 解析出 RIFF 头 / OGG 打开解码器即为就绪）。 */
    fun ready(a: AudioAsset): Boolean = pcmOf(a) != null

    /** 采样率；未就绪回 0。 */
    fun rate(a: AudioAsset): Int = pcmOf(a)?.rate ?: 0

    /** 声道数；未就绪回 0。 */
    fun channels(a: AudioAsset): Int = pcmOf(a)?.channels ?: 0

    /** 单点采样（线性插值）；未就绪回 0。 */
    fun sampleAt(a: AudioAsset, ms: Double, ch: Int): Double = pcmOf(a)?.sampleAt(ms, ch) ?: 0.0

    /** 窗口峰值（[ms, ms+windowMs) 内 |sample| 最大）；未就绪回 0。 */
    fun peakAt(a: AudioAsset, ms: Double, windowMs: Double, ch: Int): Double =
        pcmOf(a)?.peakAt(ms, windowMs, ch) ?: 0.0

    /** 测试用：清掉采样缓存。 */
    internal fun clearCache() {
        synchronized(cache) { cache.clear() }
    }

    private fun pcmOf(a: AudioAsset): Pcm? {
        synchronized(cache) {
            if (cache.containsKey(a)) return cache[a]
            val pcm = build(a)
            cache[a] = pcm
            return pcm
        }
    }

    private fun build(a: AudioAsset): Pcm? {
        if (a.data.isEmpty()) return null
        if (a.fmt == 1) {
            val wav = parseWav(a.data) ?: return null
            return WavPcm(a.data, wav)
        }
        val info = try {
            oggReader.info(a.data)
        } catch (_: Exception) {
            null
        } catch (_: LinkageError) {
            null
        } ?: return null
        if (info.rate <= 0 || info.channels !in 1..2 || info.frames <= 0) return null
        return OggPcm(a.data, info)
    }

    /** 帧时换算、插值与窗口峰值；子类只实现某一帧某声道的取值。 */
    private abstract class Pcm(val rate: Int, val channels: Int, val frames: Int) {

        /** 某帧某声道的样本值（-1..1）；越界回 0。 */
        protected abstract fun sample(frame: Int, ch: Int): Double

        fun sampleAt(ms: Double, ch: Int): Double {
            if (ch < 0 || ch >= channels) return 0.0
            val idx = sampleIndex(ms) ?: return 0.0
            val i0 = floor(idx).toInt()
            val i1 = minOf(i0 + 1, frames - 1)
            val f = idx - i0
            return sample(i0, ch) * (1.0 - f) + sample(i1, ch) * f
        }

        fun peakAt(ms: Double, windowMs: Double, ch: Int): Double {
            if (ch < 0 || ch >= channels) return 0.0
            val from = ms / 1000.0 * rate
            val to = (ms + maxOf(0.0, windowMs)) / 1000.0 * rate
            var a = floor(from)
            var b = ceil(to)
            if (a.isNaN() || b.isNaN()) return 0.0            // NaN 进不了循环，同样是 0
            if (b <= a) b = a + 1                              // 窗口不足一个采样：取该点
            if (b <= 0.0 || a >= frames) return 0.0            // 窗口整个在音频之外
            if (a < 0.0) a = 0.0
            if (b > frames) b = frames.toDouble()
            var peak = 0.0
            var i = a.toInt()
            val end = b.toInt()
            while (i < end) {
                val v = abs(sample(i, ch))
                if (v > peak) peak = v
                i++
            }
            return peak
        }

        /** 本地毫秒 → 采样下标（可为小数）；越界回 null。 */
        private fun sampleIndex(ms: Double): Double? {
            val idx = ms / 1000.0 * rate
            if (!idx.isFinite() || idx < 0.0 || idx > frames - 1) return null
            return idx
        }
    }

    // WAV：扫 RIFF 头，样本按索引读原始字节

    /** WAV 块解析结果。 */
    internal class WavInfo(
        val channels: Int,
        val rate: Int,
        val bits: Int,
        val float: Boolean,
        val dataOffset: Int,
        val dataLength: Int,
        val frames: Int,
    )

    private class WavPcm(private val bytes: ByteArray, private val w: WavInfo) :
        Pcm(w.rate, w.channels, w.frames) {

        private val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        override fun sample(frame: Int, ch: Int): Double {
            if (frame < 0 || frame >= frames || ch < 0 || ch >= channels) return 0.0
            val bps = w.bits / 8
            val o = w.dataOffset + (frame * channels + ch) * bps
            if (o + bps > bytes.size) return 0.0
            return if (w.float) buf.getFloat(o).toDouble() else buf.getShort(o) / 32768.0
        }
    }

    /**
     * 扫 RIFF/WAVE 的 fmt / data 块；不是可直读的 PCM WAV 时回 null。
     * 只支持 16bit 整数与 32bit 浮点，8bit/24bit 走解码播放那条路。
     */
    private fun parseWav(bytes: ByteArray): WavInfo? {
        if (bytes.size < 12) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (tag(bytes, 0) != "RIFF" || tag(bytes, 8) != "WAVE") return null
        var channels = 0
        var rate = 0
        var bits = 0
        var float = false
        var dataOffset = 0
        var dataLength = 0
        var o = 12L
        while (o + 8 <= bytes.size) {
            val id = tag(bytes, o.toInt())
            val size = buf.getInt(o.toInt() + 4).toLong() and 0xFFFFFFFFL
            val body = o + 8
            if (id == "fmt ") {
                if (body + 16 > bytes.size) return null
                val audioFormat = buf.getShort(body.toInt()).toInt() and 0xFFFF
                channels = buf.getShort(body.toInt() + 2).toInt() and 0xFFFF
                rate = buf.getInt(body.toInt() + 4)
                bits = buf.getShort(body.toInt() + 14).toInt() and 0xFFFF
                float = audioFormat == 3
                if (float) bits = 32
            } else if (id == "data") {
                dataOffset = body.toInt()
                dataLength = minOf(size, bytes.size - body).toInt()
            }
            o = body + size + (size and 1L)
            if (channels != 0 && dataLength != 0) break
        }
        if (channels <= 0 || rate <= 0 || dataOffset <= 0) return null
        if (bits != 16 && !(float && bits == 32)) return null   // 只认 16bit 整数与 32bit 浮点
        val bytesPerSample = bits / 8
        val frames = dataLength / (bytesPerSample * channels)
        if (frames <= 0) return null
        return WavInfo(channels, rate, bits, float, dataOffset, dataLength, frames)
    }

    private fun tag(bytes: ByteArray, o: Int): String =
        String(byteArrayOf(bytes[o], bytes[o + 1], bytes[o + 2], bytes[o + 3]), Charsets.US_ASCII)

    // OGG：按窗口解码，不整首常驻

    /** OGG 元数据：采样率、声道数、总帧数。 */
    internal class OggInfo(val rate: Int, val channels: Int, val frames: Int)

    /** OGG 解码入口；测试里替换成合成样本，不依赖本机 STB 原生库。 */
    internal interface OggReader {
        fun info(data: ByteArray): OggInfo?

        /** 解出 [startFrame, startFrame+frameCount) 的交错样本（-1..1）；失败回 null。 */
        fun read(data: ByteArray, startFrame: Int, frameCount: Int, channels: Int): FloatArray?
    }

    internal var oggReader: OggReader = StbOggReader

    private class OggPcm(private val data: ByteArray, private val info: OggInfo) :
        Pcm(info.rate, info.channels, info.frames) {

        private val windowFrames = minOf(
            info.frames.toLong(),
            maxOf(1L, info.rate.toLong() * OGG_WINDOW_MS / 1000),
            OGG_WINDOW_MAX_FRAMES,
        ).toInt()
        private val starts = IntArray(OGG_WINDOW_SLOTS) { -1 }
        private val windows = arrayOfNulls<FloatArray>(OGG_WINDOW_SLOTS)
        private val usedAt = IntArray(OGG_WINDOW_SLOTS)
        private var clock = 0

        override fun sample(frame: Int, ch: Int): Double {
            if (frame < 0 || frame >= frames || ch < 0 || ch >= channels) return 0.0
            val slot = slotOf(frame / windowFrames * windowFrames)
            val win = windows[slot] ?: return 0.0
            val at = (frame - starts[slot]) * channels + ch
            return if (at in win.indices) win[at].toDouble() else 0.0
        }

        /** 取含 [start] 的窗口；没有就解一个（解不出来也记下空窗口，避免逐帧重复 seek）。 */
        private fun slotOf(start: Int): Int {
            for (i in starts.indices) {
                if (starts[i] == start) {
                    usedAt[i] = ++clock
                    return i
                }
            }
            val slot = pickSlot()
            val want = minOf(windowFrames, frames - start)
            val samples = try {
                oggReader.read(data, start, want, channels)
            } catch (_: Exception) {
                null
            } catch (_: LinkageError) {
                null
            }
            starts[slot] = start
            windows[slot] = samples ?: FloatArray(0)
            usedAt[slot] = ++clock
            return slot
        }

        private fun pickSlot(): Int {
            for (i in starts.indices) if (starts[i] < 0) return i
            var slot = 0
            for (i in starts.indices) if (usedAt[i] < usedAt[slot]) slot = i
            return slot
        }
    }

    /**
     * LWJGL STB Vorbis 解码：每次取值开一次内存句柄、用完即关。
     * STB 要求输入缓冲在句柄生命周期内一直有效，因此单独 memAlloc 并在关闭后释放。
     */
    private object StbOggReader : OggReader {

        override fun info(data: ByteArray): OggInfo? = withHandle(data) { handle ->
            val info = STBVorbisInfo.malloc()
            try {
                STBVorbis.stb_vorbis_get_info(handle, info)
                OggInfo(
                    info.sample_rate(),
                    info.channels(),
                    STBVorbis.stb_vorbis_stream_length_in_samples(handle),
                )
            } finally {
                info.free()
            }
        }

        override fun read(data: ByteArray, startFrame: Int, frameCount: Int, channels: Int): FloatArray? =
            withHandle(data) { handle ->
                // 用 seek 而非 seek_frame：后者取样本会从帧边界开始，波形整体偏移最多一个块。
                if (frameCount <= 0) {
                    null
                } else if (!STBVorbis.stb_vorbis_seek(handle, startFrame)) {
                    null
                } else {
                    val out = FloatArray(frameCount * channels)
                    val got = STBVorbis.stb_vorbis_get_samples_float_interleaved(handle, channels, out)
                    when {
                        got <= 0 -> null
                        got == frameCount -> out
                        else -> out.copyOf(got * channels)
                    }
                }
            }

        private fun <T> withHandle(data: ByteArray, body: (Long) -> T): T? {
            val mem = MemoryUtil.memAlloc(data.size)
            try {
                mem.put(data).flip()
                val handle = MemoryStack.stackPush().use { stack ->
                    STBVorbis.stb_vorbis_open_memory(mem, stack.mallocInt(1), null)
                }
                if (handle == 0L) return null
                try {
                    return body(handle)
                } finally {
                    STBVorbis.stb_vorbis_close(handle)
                }
            } finally {
                MemoryUtil.memFree(mem)
            }
        }
    }
}
