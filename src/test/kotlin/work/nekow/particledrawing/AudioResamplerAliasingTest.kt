package work.nekow.particledrawing

import org.junit.Assume.assumeTrue
import org.lwjgl.openal.AL10
import org.lwjgl.openal.SOFTSourceResampler
import work.nekow.particledrawing.core.client.AudioStreamPlayer
import work.nekow.particledrawing.core.client.OpenAlSink
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 采样率不匹配时的重采样行为（真 OpenAL Soft，ALC_SOFT_loopback 离屏渲染）。
 *
 * 素材是 192kHz、只有一个 30kHz 单音：48kHz 输出下这个频率必须被彻底滤掉（奈奎斯特 24kHz），
 * 剩下的任何电平都是折回可听带的混叠。OpenAL 默认那档是纯插值，会把 30kHz 原电平搬到 18kHz。
 *
 * 同时把「什么情况才会去换重采样器」钉成矩阵：素材率 == 设备率时一个 AL 调用都不发（零开销、
 * 行为与改动前完全一致），不一致时才换。换素材/换设备前看这张矩阵就不会再踩。
 */
class AudioResamplerAliasingTest {

    private val rate = 192_000

    @Test
    fun `default resampler folds ultrasonic content back and the picked one does not`() {
        OpenAlLoopback.withDevice(48_000) { device ->
            val names = OpenAlLoopback.resamplerNames()
            assumeTrue("OpenAL 没报 AL_SOFT_source_resampler", names.isNotEmpty())
            val pickedIdx = AudioStreamPlayer.pickAntiAliasedResampler(names)
            assumeTrue("没有带抗混叠的重采样器可选：$names", pickedIdx != null)
            val picked = pickedIdx!!

            val stereo = tone(rate, 30_000.0, 0.5, 1.0)
            val outFrames = rate / 4
            val withDefault = OpenAlLoopback.renderPcm(device, stereo, rate, outFrames) {
                OpenAlLoopback.disableSpatialize(it)
            }
            val withPicked = OpenAlLoopback.renderPcm(device, stereo, rate, outFrames) {
                OpenAlLoopback.disableSpatialize(it)
                AL10.alSourcei(it, SOFTSourceResampler.AL_SOURCE_RESAMPLER_SOFT, picked)
            }
            val dbDefault = OpenAlLoopback.rmsDb(withDefault)
            val dbPicked = OpenAlLoopback.rmsDb(withPicked)
            println(
                "[resampler] 30kHz 单音折回可听带的电平：默认(%s) %.1f dBFS，选中(%s) %.1f dBFS"
                    .format(names[OpenAlLoopback.defaultResamplerIndex()], dbDefault, names[picked], dbPicked)
            )
            // 判据只压「我们挑的那档必须挡住超声」：默认档好坏由 OpenAL Soft 版本决定，只记录不断言
            assertTrue(dbPicked < -60.0, "换成 %s 后超声仍折回 %.1f dBFS".format(names[picked], dbPicked))
        }
    }

    @Test
    fun `resampler is only touched when the buffer rate differs from the device rate`() {
        // 素材率 == 设备率：mixer 走 1:1 快路径，不该发任何 AL_SOURCE_RESAMPLER_SOFT
        OpenAlLoopback.withDevice(48_000) { _ ->
            val names = OpenAlLoopback.resamplerNames()
            assumeTrue("OpenAL 没报 AL_SOFT_source_resampler", names.isNotEmpty())
            val pickedIdx = AudioStreamPlayer.pickAntiAliasedResampler(names)
            assumeTrue("没有带抗混叠的重采样器可选：$names", pickedIdx != null)
            val picked = pickedIdx!!
            assumeTrue("挑中的档位与默认档相同，用例没意义", picked != OpenAlLoopback.defaultResamplerIndex())

            val sink = OpenAlSink()
            val sameRate = sink.createSource()
            sink.prepareSource(sameRate, 48_000, 2)
            assertEquals(
                OpenAlLoopback.defaultResamplerIndex(), resamplerOf(sameRate),
                "素材 48k + 设备 48k：不该去设重采样器",
            )

            val otherRate = sink.createSource()
            sink.prepareSource(otherRate, rate, 2)
            assertEquals(picked, resamplerOf(otherRate), "素材 192k + 设备 48k：应当换成带限 sinc")

            val slightlyOff = sink.createSource()
            sink.prepareSource(slightlyOff, 44_100, 2)
            assertEquals(picked, resamplerOf(slightlyOff), "素材 44.1k + 设备 48k：也要换（非整数比）")

            sink.deleteSource(sameRate)
            sink.deleteSource(otherRate)
            sink.deleteSource(slightlyOff)
        }
    }

    @Test
    fun `a 192k device leaves 192k material alone`() {
        OpenAlLoopback.withDevice(192_000) { device ->
            val names = OpenAlLoopback.resamplerNames()
            assumeTrue("OpenAL 没报 AL_SOFT_source_resampler", names.isNotEmpty())
            assumeTrue("设备率不是 192k，跳过", device.rate == 192_000)
            val sink = OpenAlSink()
            val src = sink.createSource()
            sink.prepareSource(src, 192_000, 2)
            assertEquals(
                OpenAlLoopback.defaultResamplerIndex(), resamplerOf(src),
                "素材 192k + 设备 192k：不该去设重采样器",
            )
            sink.deleteSource(src)
        }
    }

    @Test
    fun `a device rate change during one players lifetime is not masked by a cached rate`() {
        val names = OpenAlLoopback.withDevice(48_000) { OpenAlLoopback.resamplerNames() }
        assumeTrue("OpenAL 没报 AL_SOFT_source_resampler", names.isNotEmpty())
        val pickedIdx = AudioStreamPlayer.pickAntiAliasedResampler(names)
        assumeTrue("没有带抗混叠的重采样器可选：$names", pickedIdx != null)
        val picked = pickedIdx!!

        // 同一个 sink 实例跨设备：先按 48k 设备判定，再换到 192k 设备。
        // 48k 素材在 192k 设备上同样要重采样，若缓存了旧设备率就会漏掉这次换挡。
        val sink = OpenAlSink()
        OpenAlLoopback.withDevice(48_000) { _ ->
            val src = sink.createSource()
            sink.prepareSource(src, 48_000, 2)
            assertEquals(OpenAlLoopback.defaultResamplerIndex(), resamplerOf(src))
            sink.deleteSource(src)
        }
        OpenAlLoopback.withDevice(192_000) { device ->
            assumeTrue("设备率不是 192k，跳过", device.rate == 192_000)
            val src = sink.createSource()
            sink.prepareSource(src, 48_000, 2)
            assertEquals(picked, resamplerOf(src), "换设备后不该沿用旧设备率的判定")
            sink.deleteSource(src)
        }
    }

    private fun resamplerOf(source: Int): Int =
        AL10.alGetSourcei(source, SOFTSourceResampler.AL_SOURCE_RESAMPLER_SOFT)

    private fun tone(rate: Int, freq: Double, amp: Double, seconds: Double): ShortArray {
        val frames = (rate * seconds).toInt()
        return ShortArray(frames * 2) { (sin(2.0 * PI * freq * (it / 2) / rate) * amp * 32767.0).toInt().toShort() }
    }
}
