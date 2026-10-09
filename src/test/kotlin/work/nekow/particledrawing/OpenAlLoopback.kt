package work.nekow.particledrawing

import org.junit.Assume.assumeTrue
import org.lwjgl.openal.AL
import org.lwjgl.openal.AL10
import org.lwjgl.openal.AL11
import org.lwjgl.openal.ALC
import org.lwjgl.openal.ALC10
import org.lwjgl.openal.SOFTLoopback
import org.lwjgl.openal.SOFTSourceResampler
import org.lwjgl.openal.SOFTSourceSpatialize
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import java.nio.ByteBuffer

/**
 * 用 ALC_SOFT_loopback 离屏渲染真实 OpenAL Soft：不需要声卡、不写文件、按需喂帧。
 *
 * 缺 OpenAL native 或 loopback 扩展时 [withDevice] 会 assume 跳过用例。
 */
internal object OpenAlLoopback {

    /** 一台已设为当前 context 的 loopback 设备（固定立体声输出）。 */
    class Device internal constructor(
        val bootstrap: Long,
        val handle: Long,
        /** 实际生效的设备率（由 ALC_FREQUENCY 查回，可能与申请值不同）。 */
        val rate: Int,
    )

    /** 建一台 [rate] Hz 的立体声 loopback 设备并设为当前 context，跑完（含异常）一定清理。 */
    fun <T> withDevice(rate: Int, body: (Device) -> T): T {
        var dev = ALC10.alcOpenDevice("null")
        if (dev == 0L) dev = ALC10.alcOpenDevice(null as ByteBuffer?)
        if (dev == 0L) {
            assumeTrue("打不开 OpenAL 设备（没有 native 或没有可用后端）", false)
            throw IllegalStateException("unreachable")
        }
        val caps = ALC.createCapabilities(dev)
        if (!caps.ALC_SOFT_loopback) {
            ALC10.alcCloseDevice(dev)
            assumeTrue("OpenAL 不支持 ALC_SOFT_loopback", false)
        }
        val lb = SOFTLoopback.alcLoopbackOpenDeviceSOFT(null as CharSequence?)
        if (lb == 0L) {
            ALC10.alcCloseDevice(dev)
            assumeTrue("alcLoopbackOpenDeviceSOFT 失败", false)
        }

        var ctx = 0L
        try {
            ctx = MemoryStack.stackPush().use { stack ->
                val attrs = stack.ints(
                    SOFTLoopback.ALC_FORMAT_CHANNELS_SOFT, SOFTLoopback.ALC_STEREO_SOFT,
                    SOFTLoopback.ALC_FORMAT_TYPE_SOFT, SOFTLoopback.ALC_FLOAT_SOFT,
                    ALC10.ALC_FREQUENCY, rate,
                    0,
                )
                ALC10.alcCreateContext(lb, attrs)
            }
            assumeTrue("loopback 建不起 context", ctx != 0L)
            ALC10.alcMakeContextCurrent(ctx)
            AL.createCapabilities(ALC.createCapabilities(lb))
            val actual = MemoryStack.stackPush().use { stack ->
                val p = stack.mallocInt(1)
                ALC10.alcGetIntegerv(lb, ALC10.ALC_FREQUENCY, p)
                p.get(0)
            }
            assumeTrue("loopback 不接受 $rate Hz（实际 $actual）", actual == rate)
            return body(Device(dev, lb, actual))
        } finally {
            ALC10.alcMakeContextCurrent(MemoryUtil.NULL)
            if (ctx != 0L) ALC10.alcDestroyContext(ctx)
            try {
                ALC10.alcCloseDevice(lb)
            } catch (_: Exception) {
            }
            try {
                ALC10.alcCloseDevice(dev)
            } catch (_: Exception) {
            }
        }
    }

    /** OpenAL 报出的重采样器名字（不支持扩展时空表）。 */
    fun resamplerNames(): List<String> {
        if (!AL.getCapabilities().AL_SOFT_source_resampler) return emptyList()
        return MemoryStack.stackPush().use { stack ->
            val p = stack.mallocInt(1)
            AL11.alGetIntegerv(SOFTSourceResampler.AL_NUM_RESAMPLERS_SOFT, p)
            (0 until p.get(0)).map {
                SOFTSourceResampler.alGetStringiSOFT(SOFTSourceResampler.AL_RESAMPLER_NAME_SOFT, it) ?: ""
            }
        }
    }

    fun defaultResamplerIndex(): Int = MemoryStack.stackPush().use { stack ->
        val p = stack.mallocInt(1)
        AL11.alGetIntegerv(SOFTSourceResampler.AL_DEFAULT_RESAMPLER_SOFT, p)
        p.get(0)
    }

    /**
     * 把一个 16bit 交错 buffer 挂到新 source 上离屏渲染 [outFrames] 帧，返回 float 交错样本。
     * [stereoBuffer] 只决定 buffer 格式，输出固定立体声交错；[configure] 拿到 source 句柄，
     * 可设声像/重采样器/关空间化等属性，排队与播放由这里做。
     */
    fun renderPcm(
        device: Device,
        pcm: ShortArray,
        pcmRate: Int,
        outFrames: Int,
        stereoBuffer: Boolean = true,
        configure: (Int) -> Unit = {},
    ): FloatArray {
        val buffer = AL10.alGenBuffers()
        AL10.alBufferData(
            buffer,
            if (stereoBuffer) AL10.AL_FORMAT_STEREO16 else AL10.AL_FORMAT_MONO16,
            pcm, pcmRate,
        )
        val source = AL10.alGenSources()
        // 相对坐标 + 无距离衰减：只听位置分量的左右，跟生产端一样
        AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE)
        AL10.alSourcef(source, AL10.AL_ROLLOFF_FACTOR, 0f)
        AL10.alSource3f(source, AL10.AL_POSITION, 0f, 0f, 0f)
        configure(source)
        AL10.alSourceQueueBuffers(source, buffer)
        AL10.alSourcePlay(source)
        val out = FloatArray(outFrames * OUT_CHANNELS)
        val chunk = 4096
        var done = 0
        MemoryStack.stackPush().use { stack ->
            val fb = stack.mallocFloat(chunk * OUT_CHANNELS)
            while (done < outFrames) {
                val n = minOf(chunk, outFrames - done)
                fb.clear()
                fb.limit(n * OUT_CHANNELS)
                SOFTLoopback.alcRenderSamplesSOFT(device.handle, fb, n)
                fb.get(out, done * OUT_CHANNELS, n * OUT_CHANNELS)
                done += n
            }
        }
        AL10.alDeleteSources(source)
        AL10.alDeleteBuffers(buffer)
        return out
    }

    /** loopback 设备固定立体声输出。 */
    const val OUT_CHANNELS = 2

    /** 关掉空间化（直通声道），用来当「不做空间化」的对照。 */
    fun disableSpatialize(source: Int) {
        if (AL.getCapabilities().AL_SOFT_source_spatialize) {
            AL10.alSourcei(source, SOFTSourceSpatialize.AL_SOURCE_SPATIALIZE_SOFT, AL10.AL_FALSE)
        }
    }

    /** 某个频率分量在 [channel]（0 左 / 1 右）上的电平（dB，Hann 窗单点 DFT），跳过起始瞬态。 */
    fun toneDb(samples: FloatArray, freq: Double, channel: Int, rate: Int, skipFrames: Int = 4800): Double {
        val frames = samples.size / 2
        val n = minOf(frames - skipFrames, 48_000)
        if (n <= 0) return -200.0
        var re = 0.0
        var im = 0.0
        var winSum = 0.0
        for (i in 0 until n) {
            val w = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (n - 1))
            val t = (skipFrames + i).toDouble() / rate
            val v = samples[(skipFrames + i) * 2 + channel] * w
            re += v * Math.cos(2.0 * Math.PI * freq * t)
            im -= v * Math.sin(2.0 * Math.PI * freq * t)
            winSum += w
        }
        return 20.0 * Math.log10((2.0 * Math.sqrt(re * re + im * im) / winSum) / 2.0 + 1e-30)
    }

    /** 整段 RMS（dBFS）。 */
    fun rmsDb(samples: FloatArray, skipFrames: Int = 4800): Double {
        val from = skipFrames * 2
        if (from >= samples.size) return -200.0
        var sum = 0.0
        for (i in from until samples.size) sum += samples[i] * samples[i].toDouble()
        return 20.0 * Math.log10(Math.sqrt(sum / (samples.size - from)) + 1e-30)
    }
}
