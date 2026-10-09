package work.nekow.particledrawing

import org.junit.Assume.assumeTrue
import org.lwjgl.openal.AL10
import org.lwjgl.system.MemoryStack
import work.nekow.particledrawing.core.client.OpenAlSink
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 立体声源的 pan：立体声素材走 AL_SOFT_source_panning 的 AL_PAN_SOFT，扩展不可用时退回 AL_POSITION。
 *
 * 中段曲线是线性的（编辑器用等功率，两端一致），硬声像不做对侧声道折叠。
 */
class StereoSourcePanTest {

    private val rate = 48_000
    private val leftFreq = 1000.0
    private val rightFreq = 3000.0

    /** 与 OpenAlSink 内部同一组常量。 */
    private val panningEnabledSoft = 0x19EC

    /** 左声道 1kHz、右声道 3kHz：一次渲染能分别量左右。 */
    private fun stereoPair(): ShortArray {
        val pcm = ShortArray(rate * 2)
        for (i in 0 until rate) {
            pcm[i * 2] = (sin(2.0 * PI * leftFreq * i / rate) * 0.5 * 32767.0).toInt().toShort()
            pcm[i * 2 + 1] = (sin(2.0 * PI * rightFreq * i / rate) * 0.5 * 32767.0).toInt().toShort()
        }
        return pcm
    }

    @Test
    fun `source panning gives a real balance through the production sink`() {
        OpenAlLoopback.withDevice(rate) { device ->
            assumeTrue("OpenAL 没有 source panning 扩展", OpenAlSink().panningAvailable())

            val pcm = stereoPair()
            fun measure(pan: Float): FloatArray = OpenAlLoopback.renderPcm(
                device, pcm, rate, rate, stereoBuffer = true,
            ) { src ->
                val sink = OpenAlSink()
                sink.prepareSource(src, rate, 2)
                sink.setPan(src, pan)
            }

            val hardLeft = measure(-1f)
            val center = measure(0f)
            val hardRight = measure(1f)
            val lLeft = OpenAlLoopback.toneDb(hardLeft, leftFreq, 0, rate)
            val lRight = OpenAlLoopback.toneDb(hardRight, rightFreq, 1, rate)
            val cLeft = OpenAlLoopback.toneDb(center, leftFreq, 0, rate)
            val cRight = OpenAlLoopback.toneDb(center, rightFreq, 1, rate)
            println("[pan] 立体声源走 AL_PAN_SOFT 后各声道自身分量电平：")
            println(
                "        pan=-1  左 %.1f dB / 右 %.1f dB"
                    .format(lLeft, OpenAlLoopback.toneDb(hardLeft, rightFreq, 1, rate))
            )
            println("        pan= 0  左 %.1f dB / 右 %.1f dB".format(cLeft, cRight))
            println(
                "        pan=+1  左 %.1f dB / 右 %.1f dB"
                    .format(OpenAlLoopback.toneDb(hardRight, leftFreq, 0, rate), lRight)
            )

            // 贴边：对侧被压到很低，本侧不动
            assertTrue(
                OpenAlLoopback.toneDb(hardRight, leftFreq, 0, rate) < cLeft - 20.0,
                "pan=+1 没把左声道压下去，立体声声像还是无效",
            )
            assertTrue(
                OpenAlLoopback.toneDb(hardLeft, rightFreq, 1, rate) < cRight - 20.0,
                "pan=-1 没把右声道压下去，立体声声像还是无效",
            )
            assertTrue(abs(lLeft - cLeft) < 1.0 && abs(lRight - cRight) < 1.0, "贴边时本侧也被改了，不是平衡")
            // 居中：两侧都不动
            assertTrue(abs(cLeft - cRight) < 0.5, "pan=0 两侧不对称")
        }
    }

    @Test
    fun `the fallback path is the old AL_POSITION behaviour and does not error`() {
        OpenAlLoopback.withDevice(rate) { device ->
            val pcm = stereoPair()
            // 强制没有扩展：退回 AL_POSITION（立体声下无效），不能抛错
            fun measure(pan: Float): Pair<Double, Double> {
                val y = OpenAlLoopback.renderPcm(device, pcm, rate, rate, stereoBuffer = true) { src ->
                    val sink = OpenAlSink(panningOverride = false)
                    sink.prepareSource(src, rate, 2)
                    sink.setPan(src, pan)
                }
                return OpenAlLoopback.toneDb(y, leftFreq, 0, rate) to
                    OpenAlLoopback.toneDb(y, rightFreq, 1, rate)
            }
            val hardLeft = measure(-1f)
            val center = measure(0f)
            val hardRight = measure(1f)
            val worst = maxOf(
                abs(hardLeft.first - center.first), abs(hardLeft.second - center.second),
                abs(hardRight.first - center.first), abs(hardRight.second - center.second),
            )
            println("[pan] 扩展不可用时的退路（AL_POSITION）最大电平变化：%.1f dB".format(worst))
            assertTrue(
                worst < 0.5,
                "退路下 pan 对立体声源生效了（最大变化 %.1f dB）——文档里的已知限制可能过时了".format(worst),
            )
        }
    }

    @Test
    fun `mono sources keep the old AL_POSITION path`() {
        OpenAlLoopback.withDevice(rate) { device ->
            val mono = ShortArray(rate) { (sin(2.0 * PI * leftFreq * it / rate) * 0.5 * 32767.0).toInt().toShort() }
            var checkedFlag = false
            fun measure(pan: Float): Pair<Double, Double> {
                val y = OpenAlLoopback.renderPcm(device, mono, rate, rate, stereoBuffer = false) { src ->
                    val sink = OpenAlSink()
                    sink.prepareSource(src, rate, 1)
                    sink.setPan(src, pan)
                    // 单声道不开平衡声像模式
                    if (AL10.alGetSourcei(src, panningEnabledSoft) != AL10.AL_TRUE) checkedFlag = true
                }
                return OpenAlLoopback.toneDb(y, leftFreq, 0, rate) to
                    OpenAlLoopback.toneDb(y, leftFreq, 1, rate)
            }
            val hardLeft = measure(-1f)
            val hardRight = measure(1f)
            println(
                "[pan] 单声道源（仍走 AL_POSITION）：pan=-1 左 %.1f 右 %.1f；pan=+1 左 %.1f 右 %.1f"
                    .format(hardLeft.first, hardLeft.second, hardRight.first, hardRight.second)
            )
            assertTrue(checkedFlag, "单声道素材不该开 source panning")
            assertTrue(
                hardRight.second > hardLeft.second + 6.0,
                "单声道源的 pan 失效了（左 %.1f 右 %.1f）".format(hardRight.first, hardRight.second),
            )
        }
    }

    @Test
    fun `enabling source panning takes the source off position based panning`() {
        OpenAlLoopback.withDevice(rate) { device ->
            assumeTrue("OpenAL 没有 source panning 扩展", OpenAlSink().panningAvailable())
            val pcm = stereoPair()
            val pinned = OpenAlLoopback.renderPcm(device, pcm, rate, rate, stereoBuffer = true) { src ->
                val sink = OpenAlSink()
                sink.prepareSource(src, rate, 2)
                sink.setPan(src, 0f)
                assertEquals(AL10.AL_TRUE, AL10.alGetSourcei(src, panningEnabledSoft), "没开平衡声像")
                // 生产路径把位置钉在原点
                MemoryStack.stackPush().use { stack ->
                    val f = stack.mallocFloat(3)
                    AL10.alGetSourcefv(src, AL10.AL_POSITION, f)
                    assertEquals(0f, f.get(0))
                    assertEquals(0f, f.get(1))
                    assertEquals(0f, f.get(2))
                }
            }
            // 开了平衡声像后再把位置挪到右边、AL_PAN_SOFT 仍给 0
            val moved = OpenAlLoopback.renderPcm(device, pcm, rate, rate, stereoBuffer = true) { src ->
                val sink = OpenAlSink()
                sink.prepareSource(src, rate, 2)
                AL10.alSource3f(src, AL10.AL_POSITION, 1f, 0f, -1f)
                sink.setPan(src, 0f)
                assertEquals(AL10.AL_TRUE, AL10.alGetSourcei(src, panningEnabledSoft))
            }
            val pinnedLeft = OpenAlLoopback.toneDb(pinned, leftFreq, 0, rate)
            val pinnedRight = OpenAlLoopback.toneDb(pinned, rightFreq, 1, rate)
            val movedLeft = OpenAlLoopback.toneDb(moved, leftFreq, 0, rate)
            val movedRight = OpenAlLoopback.toneDb(moved, rightFreq, 1, rate)
            println(
                "[pan] 开平衡声像后：位置钉原点 左 %.1f / 右 %.1f；位置挪到右侧且 pan=0 左 %.1f / 右 %.1f"
                    .format(pinnedLeft, pinnedRight, movedLeft, movedRight)
            )
            // 开了这模式位置分量不再参与混音，两套声像不会叠加
            assertTrue(
                abs(movedLeft - movedRight) < 1.0,
                "开了平衡声像后位置声像又生效了（左 %.1f / 右 %.1f）——会两套叠加，请改文档与注释"
                    .format(movedLeft, movedRight),
            )
        }
    }

    @Test
    fun `source panning is only used for stereo material`() {
        // 纯决策函数：立体声 + 有扩展才走平衡声像
        assertEquals(false, OpenAlSink.useSourcePanning(stereo = false, extensionPresent = true))
        assertEquals(false, OpenAlSink.useSourcePanning(stereo = true, extensionPresent = false))
        assertEquals(true, OpenAlSink.useSourcePanning(stereo = true, extensionPresent = true))
    }
}
