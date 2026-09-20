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
 * 对比期望毫秒做漂移校正；音量走 AL_GAIN、声像走相对坐标 AL_POSITION、倍速走 AL_PITCH。
 * 所有 AL 调用在客户端主线程（update）执行——context 线程局部，
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

    /**
     * 客户端主线程每 tick 调用：play = 是否出声；seekMs 非空时跳转到该毫秒（内容本地毫秒）；
     * gain = 音量（已含淡入淡出包络，0..1）；pan = 声像（-1 左 / 0 中 / 1 右）；rate = 倍速。
     */
    fun update(play: Boolean, seekMs: Double?, gain: Float, pan: Float, rate: Float) {
        if (decoder == null) return
        if (!ensureContext()) {
            closeSource()
            return
        }
        try {
            if (source == 0) openSource()
            if (seekMs != null) doSeek(seekMs)
            refill()
            applyMix(gain, pan, rate)
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

    /** 当前内容播放位置（毫秒，相对音频内容起点；倍速下已是内容本地时间）。 */
    fun positionMs(): Double {
        if (source == 0 || sampleRate <= 0 || decoder == null) return 0.0
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

    /** 每 tick 套用音量/声像/倍速。 */
    private fun applyMix(gain: Float, pan: Float, rate: Float) {
        if (source == 0) return
        AL10.alSourcef(source, AL10.AL_GAIN, gain.coerceIn(0f, 2f))
        AL10.alSourcef(source, AL10.AL_PITCH, rate.coerceIn(0.25f, 4f))
        // 声像：源坐标取相对听者（右为 +X、前方为 -Z），并把距离衰减关掉，效果与玩家朝向/位置无关
        AL10.alSource3f(source, AL10.AL_POSITION, pan.coerceIn(-1f, 1f), 0f, -1f)
    }

    private fun openSource() {
        source = AL10.alGenSources()
        AL10.alSourcef(source, AL10.AL_GAIN, 1f)
        // 相对坐标 + 无衰减：只借 AL_POSITION 的左/右分量做声像，不听距离与朝向
        AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE)
        AL10.alSourcef(source, AL10.AL_ROLLOFF_FACTOR, 0f)
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

/** 音频解码器：按采样帧 seek（seek 后下一次 read 就从该帧开始），读交错 16bit PCM（返回样本帧数）。 */
internal interface AudioDecoder : AutoCloseable {
    val sampleRate: Int
    val channels: Int
    fun seekFrame(frame: Long)
    fun read(dst: ShortArray): Int
}

/** OGG Vorbis 解码（LWJGL STB，公版库）。 */
internal class OggDecoder(bytes: ByteArray) : AudioDecoder {

    // stb_vorbis 在句柄存活期间会一直读这块输入内存，必须堆分配并留到 close——
    // 栈内存（MemoryStack）pop 之后随时会被后续分配覆盖。整份字节只此一份拷贝，close 时释放。
    private val mem: ByteBuffer = MemoryUtil.memAlloc(bytes.size)
    private var memFreed = false
    private var handle = 0L
    override val sampleRate: Int
    override val channels: Int

    init {
        var rate = 0
        var ch = 0
        try {
            mem.put(bytes).flip()
            var errCode = 0
            MemoryStack.stackPush().use { stack ->
                val err = stack.mallocInt(1)
                handle = STBVorbis.stb_vorbis_open_memory(mem, err, null)
                errCode = err.get(0)
            }
            if (handle == 0L) throw IllegalStateException("STB Vorbis 打开失败: $errCode")
            val info = STBVorbisInfo.malloc()
            try {
                STBVorbis.stb_vorbis_get_info(handle, info)
                rate = info.sample_rate()
                ch = info.channels()
            } finally {
                info.free()
            }
            if (ch !in 1..2) throw IllegalStateException("OGG 声道数不支持: $ch")
        } catch (e: Exception) {
            close()
            throw e
        }
        sampleRate = rate
        channels = ch
    }

    override fun seekFrame(frame: Long) {
        // stb_vorbis_seek 是采样级精确的；seek_frame 只保证下一帧「包含」目标采样，
        // 取样本会从帧边界开始，最多偏移一个块，播放头与听到的位置就对不上了。
        STBVorbis.stb_vorbis_seek(handle, frame.toInt().coerceAtLeast(0))
    }

    override fun read(dst: ShortArray): Int =
        STBVorbis.stb_vorbis_get_samples_short_interleaved(handle, channels, dst)

    override fun close() {
        if (handle != 0L) {
            STBVorbis.stb_vorbis_close(handle)
            handle = 0L
        }
        if (!memFreed) {
            memFreed = true
            MemoryUtil.memFree(mem)
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
                    // remaining() 已经是「从 data 块正文到文件末尾」的字节数，body 是绝对位置，不能再减一次；
                    // 减两次会把每首曲子尾部砍掉 body 字节，短 WAV 还会直接判成没有 data 块。
                    dataOffset = body
                    dataLength = size.coerceAtMost(buf.remaining())
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
        // 没 seek 过就直接 read 时也要从 data 块正文开始，否则会把 RIFF 头当样本读出来
        dataPos = dataOffset
    }

    override fun seekFrame(frame: Long) {
        val frameBytes = channels * (bits / 8)
        // 先按 Long 钳到 data 区再转 Int：frame 很大时 frame*frameBytes 会溢出成负数，
        // 位置跑到 data 块之前会让后面的 buf.position 直接抛异常。
        val offset = (frame * frameBytes).coerceIn(0L, dataLength.toLong())
        dataPos = dataOffset + offset.toInt()
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