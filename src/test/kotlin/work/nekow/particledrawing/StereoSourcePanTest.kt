package work.nekow.particledrawing

import org.lwjgl.openal.AL10
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 立体声源的 `pan` 是已知的两端分叉（只记录，不实施）：
 * 编辑器把声像串在 Web Audio StereoPanner 上（`objects/audio-playback.js`：source → gain → panner →
 * destination），是真正的左右平衡；播放端把它塞进 `AL_POSITION`，而 OpenAL 对**立体声源**在立体声
 * 输出下走直通声道，位置分量根本不参与混音——实测 pan 从 -1 到 +1 电平差 0.0 dB，也就是**完全无效**。
 *
 * 这个用例把分叉钉住：哪天 OpenAL 开始对立体声源认 pan，说明这条已知限制过时了，用例会失败提醒
 * 去改 `doc/README.md` 的「已知限制」。同时量一下「拆成单声道源」为什么能修——单声道源下 pan 有效。
 */
class StereoSourcePanTest {

    private val rate = 48_000
    private val leftFreq = 1000.0
    private val rightFreq = 3000.0

    /** `AL_SOFTX_source_panning`（OpenAL Soft 草稿扩展，MC 那套 OpenAL 用 SOFTX 名字报出来）。 */
    private val panningExt = "AL_SOFTX_source_panning"
    private val panningExtAlt = "AL_SOFT_source_panning"

    /** AL_PANNING_ENABLED_SOFT / AL_PAN_SOFT（alc/inprogext.h）。 */
    private val panningEnabledSoft = 0x19EC
    private val panSoft = 0x19ED

    /**
     * 给单 source 做真左右平衡的另一条路：开 `AL_SOFTX_source_panning` 后用 `AL_PAN_SOFT`，
     * 不用拆双源。量出来它确实是平衡（贴边时对侧被压掉几十 dB），行为与编辑器 StereoPanner 同类。
     */
    @Test
    fun `source panning extension gives a real balance on a stereo source`() {
        OpenAlLoopback.withDevice(rate) { device ->
            val ext = if (AL10.alIsExtensionPresent(panningExt)) panningExt
            else if (AL10.alIsExtensionPresent(panningExtAlt)) panningExtAlt else null
            org.junit.Assume.assumeTrue("OpenAL 没有 source panning 扩展", ext != null)

            val pcm = ShortArray(rate * 2)
            for (i in 0 until rate) {
                pcm[i * 2] = (sin(2.0 * PI * leftFreq * i / rate) * 0.5 * 32767.0).toInt().toShort()
                pcm[i * 2 + 1] = (sin(2.0 * PI * rightFreq * i / rate) * 0.5 * 32767.0).toInt().toShort()
            }

            fun measure(pan: Float): Pair<Double, Double> {
                val y = OpenAlLoopback.renderPcm(device, pcm, rate, rate, stereoBuffer = true) { src ->
                    AL10.alSourcei(src, panningEnabledSoft, AL10.AL_TRUE)
                    AL10.alSourcef(src, panSoft, pan)
                }
                return OpenAlLoopback.toneDb(y, leftFreq, 0, rate) to
                    OpenAlLoopback.toneDb(y, rightFreq, 1, rate)
            }

            val hardLeft = measure(-1f)
            val center = measure(0f)
            val hardRight = measure(1f)
            println("[pan] 用 $ext + AL_PAN_SOFT 对立体声源做声像：")
            for (pan in listOf(-1f, -0.75f, -0.5f, -0.25f, 0f, 0.25f, 0.5f, 0.75f, 1f)) {
                val m = measure(pan)
                println("        pan=%+.2f  左 %.1f dB / 右 %.1f dB".format(pan, m.first, m.second))
            }

            // 真平衡：贴边时对侧被压掉，本侧基本不动
            assertTrue(
                hardRight.first < center.first - 20.0,
                "pan=+1 没把左声道压下去（%.1f → %.1f），不是平衡控制".format(center.first, hardRight.first),
            )
            assertTrue(
                hardLeft.second < center.second - 20.0,
                "pan=-1 没把右声道压下去（%.1f → %.1f），不是平衡控制".format(center.second, hardLeft.second),
            )
            assertTrue(
                abs(hardLeft.first - center.first) < 1.0 && abs(hardRight.second - center.second) < 1.0,
                "贴边时本侧也被改了（左 %.1f→%.1f，右 %.1f→%.1f），不是纯平衡"
                    .format(center.first, hardLeft.first, center.second, hardRight.second),
            )
            // 中间单调：右声道随 pan 单调不减
            var prev = -1e9
            for (pan in listOf(-1f, -0.75f, -0.5f, -0.25f, 0f, 0.25f, 0.5f, 0.75f, 1f)) {
                val right = measure(pan).second
                assertTrue(right >= prev - 0.5, "右声道在 pan=%+.2f 处没单调（%.1f < %.1f）".format(pan, right, prev))
                prev = right
            }

            // 与编辑器的剩余差别：Web Audio 的 StereoPanner 对立体声输入会把对侧声道**折进**本侧
            // （规范：pan<=0 时 outputL = inputL + inputR*gainL），而 AL_PAN_SOFT 是纯平衡、直接丢掉对侧。
            val fold = OpenAlLoopback.renderPcm(device, pcm, rate, rate, stereoBuffer = true) { src ->
                AL10.alSourcei(src, panningEnabledSoft, AL10.AL_TRUE)
                AL10.alSourcef(src, panSoft, -1f)
            }
            val leakedRightIntoLeft = OpenAlLoopback.toneDb(fold, rightFreq, 0, rate)
            println("        pan=-1 时左声道里的右声道分量：%.1f dB（纯平衡应为极低；Web Audio 会折叠进来）"
                .format(leakedRightIntoLeft))
            assertTrue(
                leakedRightIntoLeft < center.first - 30.0,
                "对侧声道被折进了本侧（%.1f dB），与「纯平衡」的预期不符".format(leakedRightIntoLeft),
            )
        }
    }

    @Test
    fun `pan is a no-op for a stereo source`() {
        OpenAlLoopback.withDevice(rate) { device ->
            // 左声道 1kHz、右声道 3kHz：一次渲染能分别量左右
            val pcm = ShortArray(rate * 2)
            for (i in 0 until rate) {
                pcm[i * 2] = (sin(2.0 * PI * leftFreq * i / rate) * 0.5 * 32767.0).toInt().toShort()
                pcm[i * 2 + 1] = (sin(2.0 * PI * rightFreq * i / rate) * 0.5 * 32767.0).toInt().toShort()
            }

            fun measure(pan: Float): Pair<Double, Double> {
                val y = OpenAlLoopback.renderPcm(device, pcm, rate, rate, stereoBuffer = true) { src ->
                    // 与 AudioStreamPlayer.applyMix 同一套：相对坐标 + 无距离衰减
                    AL10.alSource3f(src, AL10.AL_POSITION, pan, 0f, -1f)
                }
                return OpenAlLoopback.toneDb(y, leftFreq, 0, rate) to
                    OpenAlLoopback.toneDb(y, rightFreq, 1, rate)
            }

            val hardLeft = measure(-1f)
            val center = measure(0f)
            val hardRight = measure(1f)
            println("[pan] 立体声源（左=1kHz, 右=3kHz）各声道自身分量电平：")
            println("        pan=-1  左 %.1f dB / 右 %.1f dB".format(hardLeft.first, hardLeft.second))
            println("        pan= 0  左 %.1f dB / 右 %.1f dB".format(center.first, center.second))
            println("        pan=+1  左 %.1f dB / 右 %.1f dB".format(hardRight.first, hardRight.second))

            // 已知限制：立体声源下 pan 完全不起作用（编辑器里是明显的左右平衡）
            val worst = maxOf(
                abs(hardLeft.first - center.first), abs(hardLeft.second - center.second),
                abs(hardRight.first - center.first), abs(hardRight.second - center.second),
            )
            assertTrue(
                worst < 0.5,
                "pan 对立体声源生效了（最大变化 %.1f dB）——已知限制过时，请更新 doc/README.md".format(worst),
            )
        }
    }

    @Test
    fun `pan does work on a mono source which is what a channel split would give`() {
        OpenAlLoopback.withDevice(rate) { device ->
            val mono = ShortArray(rate) { (sin(2.0 * PI * leftFreq * it / rate) * 0.5 * 32767.0).toInt().toShort() }
            fun measure(pan: Float): Pair<Double, Double> {
                val y = OpenAlLoopback.renderPcm(device, mono, rate, rate, stereoBuffer = false) { src ->
                    AL10.alSource3f(src, AL10.AL_POSITION, pan, 0f, -1f)
                }
                return OpenAlLoopback.toneDb(y, leftFreq, 0, rate) to
                    OpenAlLoopback.toneDb(y, leftFreq, 1, rate)
            }
            val hardLeft = measure(-1f)
            val center = measure(0f)
            val hardRight = measure(1f)
            println("[pan] 单声道源（1kHz）输出左右电平：")
            println("        pan=-1  左 %.1f dB / 右 %.1f dB".format(hardLeft.first, hardLeft.second))
            println("        pan= 0  左 %.1f dB / 右 %.1f dB".format(center.first, center.second))
            println("        pan=+1  左 %.1f dB / 右 %.1f dB".format(hardRight.first, hardRight.second))

            // 单声道源下 pan 确实改变左右分配：这就是拆双单声道源能修 pan 的原因
            assertTrue(
                hardRight.second > hardLeft.second + 6.0,
                "单声道源下 pan 也没把声音挪到右边（左 %.1f 右 %.1f），拆源方案不成立"
                    .format(hardRight.first, hardRight.second),
            )
        }
    }
}
