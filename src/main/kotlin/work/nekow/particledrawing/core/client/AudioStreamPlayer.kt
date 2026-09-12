package work.nekow.particledrawing.core.client

import org.lwjgl.openal.AL10
import org.lwjgl.openal.AL11
import org.lwjgl.openal.ALC10
import org.lwjgl.stb.STBVorbis
import org.lwjgl.stb.STBVorbisInfo
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import work.nekow.particledrawing.animation.AudioAsset
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 游戏内音频播放（.pdrawc 音频资产）。复用 Minecraft 的 OpenAL context 自建 source：
 * 分块解码 + 4×250ms 队列缓冲，seek 用「停源 → 清队列 → 从目标偏移重灌」（AL_SEC_OFFSET 对
 * 队列 source 跨实现不可靠）；播放位置由「已完成帧数 + AL_SAMPLE_OFFSET」计算，动画 tick 侧
 * 对比期望毫秒做漂移校正。所有 AL 调用在客户端主线程（update）执行——context 线程局部，
 * 换线程调用无效；context 不可用（设备切换/音效重载）时静默跳过，下次可用时重建 source 续播。
 */
class AudioStreamPlayer(private val asset: AudioAsset) : AutoCloseable {

    companion object {
        private const val BUFFER_COUNT = 4
        private const val CHUNK_MS = 250
        private const val MAX_FRAMES = Int.MAX_VALUE - 100
    }

    private var decoder: AudioDecoder? = null
    private var sampleRate = 0
    private var channels = 0
    private var chunkFrames = 0
    private var eof = false

    private var source = 0
    private val buffers = IntArray(BUFFER_COUNT)
    private val bufferData = arrayOfNulls<ByteBuffer>(BUFFER_COUNT)
    private val bufferFrames = IntArray(BUFFER_COUNT)
    private var queuedFrames = 0
    private var playedFrames = 0L

    init {
        decoder = try {
            val d = if (asset.fmt == 1) WavDecoder(asset.data) else OggDecoder(asset.data)
            sampleRate = d.sampleRate
            channels = d.channels
            chunkFrames = maxOf(256, sampleRate * CHUNK_MS / 1000)
            d
        } catch (e: Exception) {
            println("[pdrawc] 音频解码失败 ${asset.name}: ${e.message}")
            null
        }
    }

    fun available(): Boolean = decoder != null

    private fun ensureContext(): Boolean = ALC10.alcGetCurrentContext() != MemoryUtil.NULL

    /** 客户端主线程每 tick 调用：play = 是否出声；seekMs 非空时跳转到该毫秒。 */
    fun update(play: Boolean, seekMs: Double?) {
        if (decoder == null) return
        if (!ensureContext()) {
            closeSource()
            return
        }
        try {
            if (source == 0) openSource()
            if (seekMs != null) doSeek(seekMs)
            refill()
            val state = AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE)
            if (play && state != AL10.AL_PLAYING && !(eof && queuedFrames == 0)) {
                AL10.alSourcePlay(source)
            } else if (!play && state == AL10.AL_PLAYING) {
                AL10.alSourcePause(source)
            }
        } catch (e: Exception) {
            // 设备/context 异常：整体重建，下轮可用时按当前位置续播
            closeSource()
        }
    }

    /** 当前播放位置（毫秒，相对资产起点）。 */
    fun positionMs(): Double {
        if (source == 0 || decoder == null) return 0.0
        return try {
            val off = AL11.alGetSourcei(source, AL11.AL_SAMPLE_OFFSET)
            (playedFrames + off) * 1000.0 / sampleRate
        } catch (e: Exception) {
            0.0
        }
    }

    override fun close() {
        closeSource()
        decoder?.close()
        decoder = null
    }

    private fun format(): Int = if (channels == 2) AL10.AL_FORMAT_STEREO16 else AL10.AL_FORMAT_MONO16

    private fun openSource() {
        source = AL10.alGenSources()
        AL10.alSourcef(source, AL10.AL_GAIN, 1f)
        for (i in 0 until BUFFER_COUNT) {
            buffers[i] = AL10.alGenBuffers()
            bufferData[i] = MemoryUtil.memAlloc(chunkFrames * channels * 2)
        }
        playedFrames = 0
        queuedFrames = 0
        eof = false
    }

    private fun closeSource() {
        if (source == 0) return
        try {
            AL10.alSourceStop(source)
            drainQueue()
            for (i in 0 until BUFFER_COUNT) {
                if (buffers[i] != 0) AL10.alDeleteBuffers(buffers[i])
                buffers[i] = 0
                bufferFrames[i] = 0
                MemoryUtil.memFree(bufferData[i])
                bufferData[i] = null
            }
            AL10.alDeleteSources(source)
        } catch (_: Exception) {
        } finally {
            source = 0
            queuedFrames = 0
        }
    }

    private fun drainQueue() {
        var n = AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED)
        while (n-- > 0) AL10.alSourceUnqueueBuffers(source)
    }

    private fun doSeek(ms: Double) {
        decoder?.seekFrame((ms / 1000.0 * sampleRate).toLong().coerceIn(0, MAX_FRAMES.toLong()))
        playedFrames = 0
        eof = false
        AL10.alSourceStop(source)
        drainQueue()
        for (i in 0 until BUFFER_COUNT) bufferFrames[i] = 0
        queuedFrames = 0
    }

    private fun fillBuffer(idx: Int): Int {
        val dst = ShortArray(chunkFrames * channels)
        val frames = decoder?.read(dst) ?: 0
        if (frames <= 0) {
            eof = true
            return 0
        }
        val buf = bufferData[idx] ?: return 0
        buf.clear()
        buf.asShortBuffer().put(dst, 0, frames * channels)
        buf.position(0).limit(frames * channels * 2)
        AL10.alBufferData(buffers[idx], format(), buf, sampleRate)
        AL10.alSourceQueueBuffers(source, buffers[idx])
        return frames
    }

    private fun refill() {
        if (source == 0) return
        val processed = AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED)
        for (i in 0 until processed) {
            val b = AL10.alSourceUnqueueBuffers(source)
            val idx = buffers.indexOf(b)
            if (idx >= 0) {
                playedFrames += bufferFrames[idx]
                queuedFrames -= bufferFrames[idx]
                bufferFrames[idx] = 0
            }
        }
        for (i in 0 until BUFFER_COUNT) {
            if (bufferFrames[i] > 0) continue
            val fr = fillBuffer(i)
            if (fr <= 0) break
            queuedFrames += fr
        }
    }
}

/** 音频解码器：按帧 seek，读交错 16bit PCM（返回样本帧数）。 */
internal interface AudioDecoder : AutoCloseable {
    val sampleRate: Int
    val channels: Int
    fun seekFrame(frame: Long)
    fun read(dst: ShortArray): Int
}

/** OGG Vorbis 解码（LWJGL STB，公版库）。 */
internal class OggDecoder(bytes: ByteArray) : AudioDecoder {

    private var handle = 0L
    override val sampleRate: Int
    override val channels: Int

    init {
        MemoryStack.stackPush().use { stack ->
            val buf = stack.malloc(bytes.size).put(bytes).flip()
            val err = stack.mallocInt(1)
            handle = STBVorbis.stb_vorbis_open_memory(buf, err, null)
            if (handle == 0L) throw IllegalStateException("STB Vorbis 打开失败: ${err.get(0)}")
            val info = STBVorbisInfo.malloc()
            STBVorbis.stb_vorbis_get_info(handle, info)
            sampleRate = info.sample_rate()
            channels = info.channels()
            info.free()
        }
        if (channels !in 1..2) {
            close()
            throw IllegalStateException("OGG 声道数不支持: $channels")
        }
    }

    override fun seekFrame(frame: Long) {
        STBVorbis.stb_vorbis_seek_frame(handle, frame.toInt().coerceAtLeast(0))
    }

    override fun read(dst: ShortArray): Int =
        STBVorbis.stb_vorbis_get_samples_short_interleaved(handle, channels, dst)

    override fun close() {
        if (handle != 0L) {
            STBVorbis.stb_vorbis_close(handle)
            handle = 0L
        }
    }
}

/** WAV（RIFF）解码：PCM 16bit / 32bit float / 8bit 无符号，单/双声道。 */
internal class WavDecoder(bytes: ByteArray) : AudioDecoder {

    private val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    private var dataOffset = 0
    private var dataLength = 0
    private var dataPos = 0
    private var bits = 16
    private var isFloat = false
    override val sampleRate: Int
    override val channels: Int

    init {
        if (buf.remaining() < 12 || String(byteArrayOf(buf.get(0), buf.get(1), buf.get(2), buf.get(3))) != "RIFF") {
            throw IllegalStateException("不是 RIFF/WAV 文件")
        }
        buf.position(12)
        var fmtChannels = 1
        var fmtRate = 8000
        var fmtBits = 16
        var fmtFloat = false
        while (buf.remaining() >= 8) {
            val id = String(byteArrayOf(buf.get(), buf.get(), buf.get(), buf.get()))
            val size = buf.int
            val body = buf.position()
            when (id) {
                "fmt " -> {
                    val audioFormat = buf.short.toInt() and 0xFFFF
                    fmtChannels = buf.short.toInt() and 0xFFFF
                    fmtRate = buf.int
                    buf.int  // byteRate
                    buf.short  // blockAlign
                    fmtBits = buf.short.toInt() and 0xFFFF
                    fmtFloat = audioFormat == 3
                    if (fmtFloat) fmtBits = 32
                }
                "data" -> {
                    dataOffset = body
                    dataLength = size.coerceAtMost(buf.remaining() - body)
                }
            }
            buf.position(body + size + (size and 1))
            if (fmtBits != 0 && dataLength > 0) break
        }
        if (dataLength <= 0) throw IllegalStateException("WAV 缺少 data 块")
        channels = fmtChannels.coerceIn(1, 2)
        sampleRate = fmtRate
        bits = fmtBits
        isFloat = fmtFloat
        if (bits != 16 && bits != 8 && !isFloat) {
            throw IllegalStateException("WAV 位深不支持: $fmtBits")
        }
    }

    override fun seekFrame(frame: Long) {
        val frameBytes = channels * (bits / 8)
        dataPos = dataOffset + (frame * frameBytes).toInt().coerceAtMost(dataLength)
    }

    override fun read(dst: ShortArray): Int {
        val frameBytes = channels * (bits / 8)
        var frames = 0
        val maxFrames = dst.size / channels
        while (frames < maxFrames && dataPos + frameBytes <= dataOffset + dataLength) {
            buf.position(dataPos)
            for (c in 0 until channels) {
                val v: Int = when {
                    isFloat -> (buf.float.coerceIn(-1f, 1f) * 32767f).toInt()
                    bits == 16 -> buf.short.toInt()
                    else -> (buf.get().toInt() and 0xFF) * 256 - 32768
                }
                dst[frames * channels + c] = v.toShort()
            }
            dataPos += frameBytes
            frames++
        }
        return frames
    }

    override fun close() = Unit
}