package work.nekow.particledrawing.core.client

import org.lwjgl.openal.AL
import org.lwjgl.openal.AL10
import org.lwjgl.openal.AL11
import org.lwjgl.openal.ALC10
import org.lwjgl.openal.SOFTSourceResampler
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
 * 队列 source 跨实现不可靠）；播放位置由「队列起始帧 + 已完成帧数 + AL_SAMPLE_OFFSET」计算（实测
 * AL_SAMPLE_OFFSET 相对当前队列计数、alSourceStop 会把它归零），动画 tick 侧对比期望毫秒做漂移校正；
 * 音量走 AL_GAIN、倍速走 AL_PITCH。声像：立体声素材走 `AL_PAN_SOFT`（`AL_SOFT_source_panning`，
 * 真左右平衡；`AL_POSITION` 对立体声源在立体声输出下不参与混音），拿不到扩展或单声道素材才退回
 * `AL_POSITION`。与编辑器仍有两点差别，见 doc/README.md 的「已知限制」。
 *
 * 素材采样率由 WAV/OGG 头决定（常见 44.1/48kHz，也有 192kHz 整曲），原样交给 `alBufferData`，
 * 由 OpenAL 重采样到设备率。OpenAL 默认重采样器是纯插值、没有抗混叠的，高采样率素材会把
 * 24kHz 以上的内容按原电平折回可听带（听感就是「电音」/金属声），所以建 source 时换成带限 sinc
 * （见 [pickAntiAliasedResampler]）。
 *
 * 所有 AL 调用在客户端主线程（update）执行——context 线程局部，换线程调用无效；
 * context 不可用（设备切换/音效重载）时静默跳过，下次可用时重建 source 续播。
 * AL 调用全部经 [AudioSink]，测试里换成假实现就能不接声音设备逐采样验证分块链路。
 */
class AudioStreamPlayer internal constructor(
    private val asset: AudioAsset,
    chunkMs: Int,
    private val sink: AudioSink,
) : AutoCloseable {

    /** 生产构造：真实 OpenAL + 250ms 分块（[chunkMs]/[sink] 只在测试里换掉）。 */
    constructor(asset: AudioAsset) : this(asset, CHUNK_MS, OpenAlSink())

    companion object {
        private const val BUFFER_COUNT = 4
        private const val CHUNK_MS = 250
        private const val MAX_FRAMES = Int.MAX_VALUE - 100

        /**
         * 带抗混叠的重采样器名字，按实测抑制能力从好到差排（192k→48k，25~85kHz 多音，
         * 最差档位的抑制量）：23rd Sinc fast 87.8dB > 47th Sinc fast 64.7dB > 11th Sinc 51.6dB，
         * 而 OpenAL 默认的 Cubic Spline 是 0dB（超声原电平折回可听带）。
         * 同阶里 fast 变体在整数比降采样下更好：整数比能直接取表里的比例，非 fast 会在相邻比例间插值。
         * 名字在 OpenAL Soft 各版本间稳定，因此按名字匹配而不按索引。
         */
        private val RESAMPLER_PREFERENCE = listOf(
            "23rd order sinc (fast)",
            "47th order sinc (fast)",
            "23rd order sinc",
            "47th order sinc",
            "11th order sinc (fast)",
            "11th order sinc",
        )

        /**
         * 从 OpenAL 报出的重采样器名字里挑一个带抗混叠的，挑不到回 null。
         * point/linear/spline/gaussian 都只是插值，降采样时不做抗混叠，不能用。
         */
        internal fun pickAntiAliasedResampler(names: List<String>): Int? {
            for (want in RESAMPLER_PREFERENCE) {
                val i = names.indexOfFirst { it.trim().lowercase() == want }
                if (i >= 0) return i
            }
            val i = names.indexOfFirst { it.contains("sinc", ignoreCase = true) }
            return if (i >= 0) i else null
        }
    }

    private var decoder: AudioDecoder? = null
    private var sampleRate = 0
    private var channels = 0
    private var chunkFrames = 0
    private var eof = false

    private var source = 0
    private val buffers = IntArray(BUFFER_COUNT)
    private val bufferFrames = IntArray(BUFFER_COUNT)
    private var queuedFrames = 0
    /** 当前队列头部对应的内容帧号（seek 目标；没 seek 过就是从内容起点续上的位置）。 */
    private var queueStartFrame = 0L
    /** 当前队列里已经播完、被取回的帧数。 */
    private var playedFrames = 0L

    init {
        decoder = try {
            val d = if (asset.fmt == 1) WavDecoder(asset.data) else OggDecoder(asset.data)
            sampleRate = d.sampleRate
            channels = d.channels
            chunkFrames = maxOf(256, sampleRate * chunkMs / 1000)
            d
        } catch (e: Exception) {
            println("[pdrawc] 音频解码失败 ${asset.name}: ${e.message}")
            null
        }
    }

    fun available(): Boolean = decoder != null

    /**
     * 客户端主线程每 tick 调用：play = 是否出声；seekMs 非空时跳转到该毫秒（内容本地毫秒）；
     * gain = 音量（已含淡入淡出包络，0..1）；pan = 声像（-1 左 / 0 中 / 1 右）；rate = 倍速。
     */
    fun update(play: Boolean, seekMs: Double?, gain: Float, pan: Float, rate: Float) {
        if (decoder == null) return
        if (!sink.hasContext()) {
            closeSource()
            return
        }
        try {
            if (source == 0) {
                openSource()
                if (source == 0) return
            }
            if (seekMs != null) doSeek(seekMs)
            refill()
            applyMix(gain, pan, rate)
            val playing = sink.isPlaying(source)
            if (play && !playing && !(eof && queuedFrames == 0)) {
                sink.play(source)
            } else if (!play && playing) {
                sink.pause(source)
            }
        } catch (_: Exception) {
            // 设备/context 异常：整体重建，下轮可用时按当前位置续播
            closeSource()
        }
    }

    /**
     * 当前内容播放位置（毫秒，相对音频内容起点；倍速下已是内容本地时间）。
     * 必须给绝对值——调用方拿它跟播放头算漂移，(seek 之后从头计数) 会让漂移判据每 tick 都成立，
     * source 被反复停掉重灌，听到的就是连续咔哒。
     */
    fun positionMs(): Double {
        if (source == 0 || sampleRate <= 0 || decoder == null) return 0.0
        return try {
            (queueStartFrame + playedFrames + sink.sampleOffset(source)) * 1000.0 / sampleRate
        } catch (_: Exception) {
            0.0
        }
    }

    override fun close() {
        closeSource()
        decoder?.close()
        decoder = null
    }

    /** 每 tick 套用音量/声像/倍速。 */
    private fun applyMix(gain: Float, pan: Float, rate: Float) {
        if (source == 0) return
        sink.setGain(source, gain.coerceIn(0f, 2f))
        sink.setPitch(source, rate.coerceIn(0.25f, 4f))
        // 声像：源坐标取相对听者（右为 +X、前方为 -Z），并把距离衰减关掉，效果与玩家朝向/位置无关
        sink.setPan(source, pan.coerceIn(-1f, 1f))
    }

    private fun openSource() {
        val s = sink.createSource()
        if (s == 0) return
        source = s
        sink.prepareSource(source, sampleRate, channels)
        for (i in 0 until BUFFER_COUNT) buffers[i] = sink.createBuffer(chunkFrames, channels)
        // 重建 source（设备切换/音效重载）时解码器已经读到队列末尾了，新队列从那里接上
        queueStartFrame += playedFrames
        playedFrames = 0
        queuedFrames = 0
        eof = false
    }

    private fun closeSource() {
        if (source == 0) return
        try {
            sink.stop(source)
            playedFrames += drainQueue()
            for (i in 0 until BUFFER_COUNT) {
                if (buffers[i] != 0) sink.deleteBuffer(buffers[i])
                buffers[i] = 0
                bufferFrames[i] = 0
            }
            sink.deleteSource(source)
        } catch (_: Exception) {
        } finally {
            source = 0
            queuedFrames = 0
        }
    }

    /** 清空队列；返回被丢掉的帧数（这些帧解码器已经读过了）。 */
    private fun drainQueue(): Long {
        var n = sink.queuedBuffers(source)
        var frames = 0L
        while (n-- > 0) {
            val b = sink.unqueueBuffers(source, 1)
            if (b.isEmpty()) break
            val idx = buffers.indexOf(b[0])
            if (idx >= 0) {
                frames += bufferFrames[idx]
                bufferFrames[idx] = 0
            }
        }
        return frames
    }

    private fun doSeek(ms: Double) {
        val target = (ms / 1000.0 * sampleRate).toLong().coerceIn(0, MAX_FRAMES.toLong())
        decoder?.seekFrame(target)
        queueStartFrame = target
        playedFrames = 0
        eof = false
        sink.stop(source)
        drainQueue()
        queuedFrames = 0
    }

    private fun fillBuffer(idx: Int): Int {
        val dst = ShortArray(chunkFrames * channels)
        val frames = decoder?.read(dst) ?: 0
        if (frames <= 0) {
            eof = true
            return 0
        }
        sink.upload(buffers[idx], dst, frames, sampleRate, channels == 2)
        sink.queueBuffer(source, buffers[idx])
        return frames
    }

    private fun refill() {
        if (source == 0) return
        val processed = sink.processedBuffers(source)
        var n = processed
        while (n-- > 0) {
            val b = sink.unqueueBuffers(source, 1)
            if (b.isEmpty()) break
            val idx = buffers.indexOf(b[0])
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
            bufferFrames[i] = fr
            queuedFrames += fr
        }
    }
}

/**
 * OpenAL 侧的薄封装：抽出来是为了让「解码 → 分块 → 排队 → 取块」这条链路不带声音设备也能
 * 逐采样验证（测试里换成假实现）。真实实现 [OpenAlSink] 全部走 LWJGL OpenAL。
 */
internal interface AudioSink {

    /** 当前线程有可用的 AL context（Minecraft 的 context 是线程局部的）。 */
    fun hasContext(): Boolean

    /** 建一个 source，失败回 0。 */
    fun createSource(): Int

    /**
     * source 的固定属性：相对坐标 + 关掉距离衰减 + 抗混叠重采样器；
     * 立体声素材还会开平衡声像（[useSourcePanning]）。
     */
    fun prepareSource(source: Int, sampleRate: Int, channels: Int)

    fun deleteSource(source: Int)

    /** 建一个缓冲；[maxFrames]×[channels] 是它要装的最大采样数，实现自己准备直接内存。 */
    fun createBuffer(maxFrames: Int, channels: Int): Int

    fun deleteBuffer(buffer: Int)

    /** 上传一块 16bit 交错 PCM（[frames] 个采样帧）并绑定到 [buffer]。 */
    fun upload(buffer: Int, pcm: ShortArray, frames: Int, sampleRate: Int, stereo: Boolean)

    fun queueBuffer(source: Int, buffer: Int)

    /** 取回已播完的缓冲（按入队顺序，最多 [count] 个）。 */
    fun unqueueBuffers(source: Int, count: Int): IntArray

    /** 队列里已播完、可回收的缓冲个数。 */
    fun processedBuffers(source: Int): Int

    /** 队列里尚未取回的缓冲个数。 */
    fun queuedBuffers(source: Int): Int

    /** 当前队列内已播的采样帧数（不含已取回的缓冲——取回时由调用方累加）。 */
    fun sampleOffset(source: Int): Int

    fun isPlaying(source: Int): Boolean

    fun play(source: Int)

    fun pause(source: Int)

    fun stop(source: Int)

    fun setGain(source: Int, gain: Float)

    fun setPitch(source: Int, pitch: Float)

    fun setPan(source: Int, pan: Float)
}

/** [AudioSink] 的真实实现：复用 Minecraft 当前 context 的 OpenAL。 */
internal class OpenAlSink(
    /** 测试用：强制「本机有没有 source panning 扩展」，null = 运行时探测。 */
    private val panningOverride: Boolean? = null,
) : AudioSink {

    private var resampler = UNPROBED
    private var panning = UNPROBED
    private val staging = HashMap<Int, ByteBuffer>()
    /** 用平衡声像（AL_PAN_SOFT）而不是 AL_POSITION 的那些 source。 */
    private val panningSources = HashSet<Int>()

    override fun hasContext(): Boolean = ALC10.alcGetCurrentContext() != MemoryUtil.NULL

    override fun createSource(): Int = AL10.alGenSources()

    override fun prepareSource(source: Int, sampleRate: Int, channels: Int) {
        // 相对坐标 + 无衰减：声像与玩家朝向/位置无关
        AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE)
        AL10.alSourcef(source, AL10.AL_ROLLOFF_FACTOR, 0f)
        applySourcePanning(source, channels)
        useAntiAliasedResampler(source, sampleRate)
    }
    override fun deleteSource(source: Int) {
        panningSources.remove(source)
        AL10.alDeleteSources(source)
    }

    override fun createBuffer(maxFrames: Int, channels: Int): Int {
        val b = AL10.alGenBuffers()
        staging[b] = MemoryUtil.memAlloc(maxFrames * channels * 2)
        return b
    }

    override fun deleteBuffer(buffer: Int) {
        AL10.alDeleteBuffers(buffer)
        MemoryUtil.memFree(staging.remove(buffer))
    }

    override fun upload(buffer: Int, pcm: ShortArray, frames: Int, sampleRate: Int, stereo: Boolean) {
        val buf = staging[buffer] ?: return
        buf.clear()
        buf.asShortBuffer().put(pcm, 0, frames * (if (stereo) 2 else 1))
        buf.position(0).limit(frames * (if (stereo) 2 else 1) * 2)
        AL10.alBufferData(
            buffer,
            if (stereo) AL10.AL_FORMAT_STEREO16 else AL10.AL_FORMAT_MONO16,
            buf, sampleRate,
        )
    }

    override fun queueBuffer(source: Int, buffer: Int) = AL10.alSourceQueueBuffers(source, buffer)

    override fun unqueueBuffers(source: Int, count: Int): IntArray {
        if (count <= 0) return IntArray(0)
        return if (count == 1) {
            intArrayOf(AL10.alSourceUnqueueBuffers(source))
        } else {
            MemoryStack.stackPush().use { stack ->
                val p = stack.mallocInt(count)
                AL10.alSourceUnqueueBuffers(source, p)
                val out = IntArray(count) { p.get(it) }
                out
            }
        }
    }

    override fun processedBuffers(source: Int): Int =
        AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED)

    override fun queuedBuffers(source: Int): Int =
        AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED)

    override fun sampleOffset(source: Int): Int = AL11.alGetSourcei(source, AL11.AL_SAMPLE_OFFSET)

    override fun isPlaying(source: Int): Boolean =
        AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) == AL10.AL_PLAYING

    override fun play(source: Int) = AL10.alSourcePlay(source)

    override fun pause(source: Int) = AL10.alSourcePause(source)

    override fun stop(source: Int) = AL10.alSourceStop(source)

    override fun setGain(source: Int, gain: Float) = AL10.alSourcef(source, AL10.AL_GAIN, gain)

    override fun setPitch(source: Int, pitch: Float) = AL10.alSourcef(source, AL10.AL_PITCH, pitch)

    override fun setPan(source: Int, pan: Float) {
        // 开了平衡声像的 source 走 AL_PAN_SOFT；其余（单声道素材、扩展不可用）仍用 AL_POSITION
        if (source in panningSources) AL10.alSourcef(source, AL_PAN_SOFT, pan)
        else AL10.alSource3f(source, AL10.AL_POSITION, pan, 0f, -1f)
    }

    /**
     * 立体声素材改用平衡声像（`AL_SOFT_source_panning`，见 [useSourcePanning]）：它给的是
     * 真正的左右平衡，而 `AL_POSITION` 对立体声源在立体声输出下根本不参与混音（声像完全无效）。
     * 单声道素材不动（`AL_POSITION` 对单声道有效，行为不变）。
     *
     * 开了这模式之后 `AL_POSITION` 就不再参与混音（实测：位置留在右侧、`AL_PAN_SOFT` 给 0 时
     * 左右仍完全对称），所以两套声像不会叠加；这里仍把位置钉在原点，免得留下一个和实际声像
     * 对不上的旧值。
     *
     * 扩展还是草稿（本机报的是 `AL_SOFTX_source_panning`），所以设完读回确认；位被改过就退回
     * `AL_POSITION`，顺手清掉可能挂起的 AL 错误，别把无效应答留在共享 context 里给 MC 看到。
     */
    private fun applySourcePanning(source: Int, channels: Int) {
        if (!useSourcePanning(channels == 2, panningSupported())) return
        val enabled = try {
            AL10.alSource3f(source, AL10.AL_POSITION, 0f, 0f, 0f)
            AL10.alSourcei(source, AL_PANNING_ENABLED_SOFT, AL10.AL_TRUE)
            AL10.alGetSourcei(source, AL_PANNING_ENABLED_SOFT) == AL10.AL_TRUE
        } catch (_: Exception) {
            false
        } catch (_: LinkageError) {
            false
        }
        if (enabled) {
            panningSources.add(source)
        } else {
            try {
                AL10.alGetError()
            } catch (_: Exception) {
            }
        }
    }

    /** 本机有没有 source panning 扩展（测试用来决定这条用例跑不跑）。 */
    internal fun panningAvailable(): Boolean = panningSupported()

    /** 本机有没有 source panning 扩展；只探一次。 */
    private fun panningSupported(): Boolean {
        panningOverride?.let { return it }
        if (panning != UNPROBED) return panning == 1
        val present = try {
            AL10.alIsExtensionPresent(EXT_PANNING) || AL10.alIsExtensionPresent(EXT_PANNING_DRAFT)
        } catch (_: Exception) {
            false
        } catch (_: LinkageError) {
            false
        }
        panning = if (present) 1 else 0
        return present
    }

    /**
     * 素材采样率与设备率不一致时 OpenAL 会自己重采样，方法由实现自选。默认那档是纯插值，
     * 高采样率素材的超声会按原电平折回可听带；这里换成带限 sinc（AL_SOFT_source_resampler）。
     * 采样率一致时 mixer 走 1:1 快路径，不动它。
     */
    private fun useAntiAliasedResampler(source: Int, sampleRate: Int) {
        val rate = deviceFrequency()
        if (rate > 0 && rate == sampleRate) return
        val idx = resamplerIndex()
        if (idx >= 0) AL10.alSourcei(source, SOFTSourceResampler.AL_SOURCE_RESAMPLER_SOFT, idx)
    }

    /** 设备率（= mixer 率）；查询失败回 0（那就照旧按可能的设备率差异处理）。 */
    private fun deviceFrequency(): Int {
        // 不缓存：设备切换会换掉设备率，而 sink 实例跨一次播放的设备重建还活着，
        // 缓存住旧设备率会让「新设备率 == 素材率」误判成立、漏掉换重采样器。
        var rate = 0
        try {
            val ctx = ALC10.alcGetCurrentContext()
            if (ctx != MemoryUtil.NULL) {
                val dev = ALC10.alcGetContextsDevice(ctx)
                if (dev != MemoryUtil.NULL) {
                    MemoryStack.stackPush().use { stack ->
                        val p = stack.mallocInt(1)
                        ALC10.alcGetIntegerv(dev, ALC10.ALC_FREQUENCY, p)
                        rate = p.get(0)
                    }
                }
            }
        } catch (_: Exception) {
        } catch (_: LinkageError) {
        }
        return rate
    }

    /** 挑出来的重采样器索引；没有可用的回 -1。缺扩展/查不到时只探一次。 */
    private fun resamplerIndex(): Int {
        if (resampler != UNPROBED) return resampler
        resampler = -1
        try {
            if (AL.getCapabilities().AL_SOFT_source_resampler) {
                MemoryStack.stackPush().use { stack ->
                    val p = stack.mallocInt(1)
                    AL11.alGetIntegerv(SOFTSourceResampler.AL_NUM_RESAMPLERS_SOFT, p)
                    val n = p.get(0)
                    val names = ArrayList<String>(n)
                    for (i in 0 until n) {
                        names.add(
                            SOFTSourceResampler.alGetStringiSOFT(
                                SOFTSourceResampler.AL_RESAMPLER_NAME_SOFT, i
                            ) ?: ""
                        )
                    }
                    resampler = AudioStreamPlayer.pickAntiAliasedResampler(names) ?: -1
                }
            }
        } catch (_: Exception) {
        } catch (_: LinkageError) {
        }
        return resampler
    }

    internal companion object {
        /** 尚未查询过。 */
        const val UNPROBED = -2

        /** `AL_SOFT_source_panning`（alc/inprogext.h；草稿期叫 AL_SOFTX_source_panning）。 */
        const val EXT_PANNING = "AL_SOFT_source_panning"
        const val EXT_PANNING_DRAFT = "AL_SOFTX_source_panning"

        /** AL_PANNING_ENABLED_SOFT（开平衡声像）/ AL_PAN_SOFT（声像值）。 */
        const val AL_PANNING_ENABLED_SOFT = 0x19EC
        const val AL_PAN_SOFT = 0x19ED

        /**
         * 是否走 AL_PAN_SOFT 这条平衡声像路径：只有立体声素材需要（`AL_POSITION` 对立体声源
         * 在立体声输出下不参与混音），且扩展得在。单声道继续走 `AL_POSITION`（对它有效，行为不变）。
         */
        internal fun useSourcePanning(stereo: Boolean, extensionPresent: Boolean): Boolean =
            stereo && extensionPresent
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
