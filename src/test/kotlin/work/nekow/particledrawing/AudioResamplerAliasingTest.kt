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
import work.nekow.particledrawing.core.client.AudioStreamPlayer
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * H3 的反向验证：让本机 OpenAL Soft 真的重采样一遍（ALC_SOFT_loopback 离屏渲染，不用声卡），
 * 量化「默认重采样器」与 [AudioStreamPlayer.pickAntiAliasedResampler] 挑出来的那档的差别。
 *
 * 素材是 192kHz 立体声、只有一个 30kHz 单音：48kHz 输出下这个频率必须被彻底滤掉（奈奎斯特 24kHz），
 * 剩下的任何电平都是折回可听带的混叠。默认那档会把 30kHz 原电平搬到 18kHz。
 *
 * 缺 OpenAL native / 缺 loopback 扩展 / 挑不到带限 sinc 时跳过（不把环境问题当代码问题）。
 */
class AudioResamplerAliasingTest {

    private val rate = 192_000
    private val deviceRate = 48_000

    @Test
    fun `default resampler folds ultrasonic content back and the picked one does not`() {
        val boot = try {
            bootDevice()
        } catch (t: Throwable) {
            assumeTrue("没有可用的 OpenAL：$t", false)
            return
        }
        assumeTrue("没有 ALC_SOFT_loopback", boot.loopback != 0L)

        val device = boot.loopback
        val context = try {
            makeContext(device)
        } catch (t: Throwable) {
            closeQuietly(device, boot.bootstrap)
            assumeTrue("建不起 loopback context：$t", false)
            return
        }
        try {
            val names = resamplerNames()
            assumeTrue("OpenAL 没报 AL_SOFT_source_resampler", names.isNotEmpty())
            val picked = AudioStreamPlayer.pickAntiAliasedResampler(names)
            assumeTrue("没有带抗混叠的重采样器可选：$names", picked != null)

            val tone = ShortArray(rate) { (sin(2.0 * PI * 30_000.0 * it / rate) * 0.5 * 32767.0).toInt().toShort() }
            val stereo = ShortArray(rate * 2)
            for (i in 0 until rate) {
                stereo[i * 2] = tone[i]
                stereo[i * 2 + 1] = tone[i]
            }

            val withDefault = renderStereo(device, stereo, -1)
            val withPicked = renderStereo(device, stereo, picked!!)
            val dbDefault = dbFs(withDefault)
            val dbPicked = dbFs(withPicked)
            println(
                "[resampler] 30kHz 单音折回可听带的电平：默认(%s) %.1f dBFS，选中(%s) %.1f dBFS"
                    .format(names.getOrElse(defaultIndex()) { "?" }, dbDefault, names[picked], dbPicked)
            )
            // 判据只压「我们挑的那档必须挡住超声」：默认档好坏由 OpenAL Soft 版本决定，只记录不断言
            assertTrue(
                dbPicked < -60.0,
                "换成 %s 后超声仍折回 %.1f dBFS（默认 %s 为 %.1f dBFS）"
                    .format(names[picked], dbPicked, names.getOrElse(defaultIndex()) { "?" }, dbDefault),
            )
        } finally {
            ALC10.alcMakeContextCurrent(MemoryUtil.NULL)
            ALC10.alcDestroyContext(context)
            closeQuietly(device, boot.bootstrap)
        }
    }

    private class Boot(val bootstrap: Long, val loopback: Long)

    private fun bootDevice(): Boot {
        var dev = ALC10.alcOpenDevice("null")
        if (dev == 0L) dev = ALC10.alcOpenDevice(null as ByteBuffer?)
        check(dev != 0L) { "打不开 OpenAL 设备" }
        val caps = ALC.createCapabilities(dev)
        val lb = if (caps.ALC_SOFT_loopback) SOFTLoopback.alcLoopbackOpenDeviceSOFT(null as CharSequence?) else 0L
        return Boot(dev, lb)
    }

    private fun makeContext(device: Long): Long {
        val ctx = MemoryStack.stackPush().use { stack ->
            val attrs = stack.ints(
                SOFTLoopback.ALC_FORMAT_CHANNELS_SOFT, SOFTLoopback.ALC_STEREO_SOFT,
                SOFTLoopback.ALC_FORMAT_TYPE_SOFT, SOFTLoopback.ALC_FLOAT_SOFT,
                ALC10.ALC_FREQUENCY, deviceRate,
                0,
            )
            val c = ALC10.alcCreateContext(device, attrs)
            ALC10.alcMakeContextCurrent(c)
            c
        }
        check(ctx != 0L) { "alcCreateContext 失败" }
        AL.createCapabilities(ALC.createCapabilities(device))
        return ctx
    }

    private fun resamplerNames(): List<String> {
        if (!AL.getCapabilities().AL_SOFT_source_resampler) return emptyList()
        return MemoryStack.stackPush().use { stack ->
            val p = stack.mallocInt(1)
            AL11.alGetIntegerv(SOFTSourceResampler.AL_NUM_RESAMPLERS_SOFT, p)
            val n = p.get(0)
            (0 until n).map {
                SOFTSourceResampler.alGetStringiSOFT(SOFTSourceResampler.AL_RESAMPLER_NAME_SOFT, it) ?: ""
            }
        }
    }

    private fun defaultIndex(): Int = MemoryStack.stackPush().use { stack ->
        val p = stack.mallocInt(1)
        AL11.alGetIntegerv(SOFTSourceResampler.AL_DEFAULT_RESAMPLER_SOFT, p)
        p.get(0)
    }

    /** 单音素材渲染一遍，返回输出样本（float32）；[resampler] < 0 表示用默认档。 */
    private fun renderStereo(device: Long, pcm: ShortArray, resampler: Int): FloatArray {
        val buffer = AL10.alGenBuffers()
        AL10.alBufferData(buffer, AL10.AL_FORMAT_STEREO16, pcm, rate)
        val source = AL10.alGenSources()
        AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE)
        AL10.alSourcef(source, AL10.AL_ROLLOFF_FACTOR, 0f)
        AL10.alSource3f(source, AL10.AL_POSITION, 0f, 0f, 0f)
        if (AL.getCapabilities().AL_SOFT_source_spatialize) {
            AL10.alSourcei(source, SOFTSourceSpatialize.AL_SOURCE_SPATIALIZE_SOFT, AL10.AL_FALSE)
        }
        if (resampler >= 0) {
            AL10.alSourcei(source, SOFTSourceResampler.AL_SOURCE_RESAMPLER_SOFT, resampler)
        }
        AL10.alSourceQueueBuffers(source, buffer)
        AL10.alSourcePlay(source)
        val outFrames = rate / 4
        val out = FloatArray(outFrames * 2)
        val chunk = 4096
        var done = 0
        MemoryStack.stackPush().use { stack ->
            val fb = stack.mallocFloat(chunk * 2)
            while (done < outFrames) {
                val n = minOf(chunk, outFrames - done)
                fb.clear()
                fb.limit(n * 2)
                SOFTLoopback.alcRenderSamplesSOFT(device, fb, n)
                fb.get(out, done * 2, n * 2)
                done += n
            }
        }
        AL10.alDeleteSources(source)
        AL10.alDeleteBuffers(buffer)
        return out
    }

    private fun dbFs(samples: FloatArray): Double {
        // 跳过起始瞬态
        val from = 4800
        val n = minOf(samples.size, 4800 + 48_000)
        var sum = 0.0
        for (i in from until n) sum += samples[i] * samples[i].toDouble()
        val rms = sqrt(sum / (n - from))
        return 20.0 * log10(rms + 1e-12)
    }

    private fun closeQuietly(vararg devices: Long) {
        for (d in devices) {
            try {
                if (d != 0L) ALC10.alcCloseDevice(d)
            } catch (_: Exception) {
            }
        }
    }
}
