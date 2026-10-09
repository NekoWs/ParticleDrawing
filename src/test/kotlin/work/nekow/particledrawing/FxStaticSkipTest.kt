package work.nekow.particledrawing

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.animation.AudioAsset
import work.nekow.particledrawing.animation.ClientAnimationPlayer
import work.nekow.particledrawing.animation.FunctionObject
import work.nekow.particledrawing.animation.FunctionVar
import work.nekow.particledrawing.animation.ParticleAnimation
import kotlin.math.abs
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 「时间轴为空 + 函数对象 duration = 0」时整支 fx 是否被当成静态动画跳过。
 *
 * 判据看脚本有没有 tick/process 阶段：有阶段就每帧求值，空时间轴的播放头也随时间前进。
 * duration <= 0 表示不限时长。
 */
class FxStaticSkipTest {

    private fun fixture(): String = assertNotNull(
        javaClass.getResourceAsStream("/editor-fixture/oscilloscope-fx1.txt"),
        "缺少夹具 src/test/resources/editor-fixture/oscilloscope-fx1.txt",
    ).use { it.readBytes().toString(Charsets.UTF_8) }

    /** 示波器音频资产替身（真实音频字节不进测试）。[durMs] 给 0 时整条时间轴为空（maxMs = 0）；
     *  脚本只用它取句柄与采样，没有 PCM 时 sampleAt 回 0。 */
    private fun primer(durMs: Int): AudioAsset = AudioAsset(
        id = "aud1", name = "Primer", fmt = 1, data = ByteArray(0), st = 0, durMs = durMs, hopCount = 2,
        bpm = 1.0, beatOffsetMs = 0.0, onsetMax = 1.0, beats = listOf(0, 1000),
        rms = ShortArray(2), peak = ShortArray(2), centroid = ShortArray(2),
        onset = ByteArray(2), rolloff = ByteArray(2), bands = ByteArray(2 * 16),
    )

    /** 示波器 fx1 整支脚本原文 + duration = 0 + 空时间轴。 */
    private fun oscilloscopePlayer(audioDurMs: Int = 0): ClientAnimationPlayer {
        val fx = FunctionObject(
            id = "fx1", name = "X-Y 光束（主迹线）", center = doubleArrayOf(0.0, 0.0, 0.0),
            source = fixture(), seed = 1,
            vars = FX1_VARS.mapValues { FunctionVar(it.value, emptyList()) },
            duration = 0,
        )
        return ClientAnimationPlayer(
            ParticleAnimation(
                loop = false, particles = emptyList(), tracks = emptyList(), groups = emptyMap(),
                functions = listOf(fx), audioAssets = listOf(primer(audioDurMs)),
            ),
            Vec3.ZERO, startGameTick = 0L, currentGameTick = 0L,
        )
    }

    /** 每帧 process 里 spawn 一颗、把当帧时刻写进 x 的函数对象（duration = 0 = 不限时长）。 */
    private fun clockFx(id: String = "fx", phase: String = "process"): FunctionObject = FunctionObject(
        id = id, name = id, center = doubleArrayOf(0.0, 0.0, 0.0),
        source = "func $phase() { let p = this.spawn()\n  p.position = [this.time, 0, 0] }",
        seed = 0, vars = emptyMap(), duration = 0,
    )

    private fun playerOf(fx: FunctionObject, loop: Boolean = false): ClientAnimationPlayer = ClientAnimationPlayer(
        ParticleAnimation(loop, emptyList(), emptyList(), emptyMap(), functions = listOf(fx)),
        Vec3.ZERO, startGameTick = 1000L, currentGameTick = 1000L,
    )

    /** 函数对象 [fxId] 的派生粒子 x，按 spawn 序号排列。 */
    private fun spawnXs(player: ClientAnimationPlayer, fxId: String): List<Double> =
        player.currentStates()
            .filter { it.id.startsWith("$fxId:p") }
            .sortedBy { it.id.removePrefix("$fxId:p").toInt() }
            .map { it.pos.x }

    /** process 写进迹线的粒子：颜色 (0.30, 1.0, 0.50, *)。setup 只把 scale 置 0，颜色保持默认白。 */
    private fun traced(player: ClientAnimationPlayer): Int = player.currentStates().count {
        abs(it.color.r - 0.30f) < 1e-3f && abs(it.color.g - 1.0f) < 1e-3f && abs(it.color.b - 0.50f) < 1e-3f
    }

    @Test
    fun `duration 为 0 且时间轴为空的示波器 fx1 必须每帧跑 process`() {
        val player = oscilloscopePlayer()
        assertFalse(player.isStatic(), "有 process 的 fx 不能被当成静态动画跳过")
        assertTrue(player.particleCount > 1000, "setup 应当铺出环上的粒子，实际 ${player.particleCount}")

        // 构造期那一次 process 只补得起 maxApp 个采样，要把整环写完得再跑一帧
        player.advanceFrame(16.7)
        assertTrue(
            traced(player) > 1000,
            "advanceFrame 之后整环应当被 process 写成迹线色，实际 ${traced(player)}/${player.particleCount}",
        )
    }

    @Test
    fun `只有 setup 的 fx 在空时间轴上仍然跳过每帧求值`() {
        val fx = FunctionObject(
            id = "fx0", name = "fx0", center = doubleArrayOf(0.0, 0.0, 0.0),
            source = "func setup() { this.spawn() }", seed = 0, vars = emptyMap(), duration = 0,
        )
        val player = playerOf(fx)
        assertTrue(player.isStatic(), "没有 tick/process 的 fx 保持静态判定（静态粒子云靠它省每刻重算）")
        // setup 的粒子在构造期已同步进渲染状态，跳过的是每帧重算
        assertEquals(listOf(0.0), spawnXs(player, "fx0"))
    }

    @Test
    fun `只有 tick 的 fx 在空时间轴上也不能被跳过`() {
        val player = playerOf(clockFx(id = "fx", phase = "tick"))
        assertFalse(player.isStatic(), "脚本有 tick 阶段同样随时间变化")
        // 构造期跑到 t=0 的边界，这一帧再补 50ms 边界上的 tick
        player.advanceFrame(50.0)
        assertEquals(listOf(0.0, 50.0), spawnXs(player, "fx"))
    }

    @Test
    fun `空时间轴的播放头随时间前进，函数对象运行时不被每 tick 重建`() {
        val player = playerOf(clockFx())
        fun frame() {
            // 与 ClientAnimationManager.frameTick 同口径：本 tick 毫秒 + 帧内 partialTick × 50ms
            player.advanceFrame(player.currentMsValue + 20.0)
            player.advanceFrame(player.currentMsValue + 40.0)
        }
        frame() // 构造期已经有 t=0 那一帧，这里补两帧
        player.tick(1001L) // elapsed 1 tick → 50ms
        frame()
        player.tick(1002L) // 100ms
        frame()
        player.tick(1003L) // 150ms
        frame()

        assertEquals(150, player.currentMsValue, "空时间轴的播放头也要跟着 gameTime 走")
        // 每帧时刻单调前进
        assertEquals(
            listOf(0.0, 20.0, 40.0, 70.0, 90.0, 120.0, 140.0, 170.0, 190.0),
            spawnXs(player, "fx"),
        )
    }

    @Test
    fun `外部播放时钟驱动的空时间轴特效同样让播放头前进`() {
        val player = playerOf(clockFx(), loop = true)
        assertTrue(player.tickExternal(5_000))
        assertEquals(5_000, player.currentMsValue, "特效时钟给多少毫秒，播放头就该在哪（旧逻辑钉在 0）")
        player.advanceFrame(player.currentMsValue + 20.0)
        player.tickExternal(5_050)
        player.advanceFrame(player.currentMsValue + 20.0)
        assertEquals(5_050, player.currentMsValue)
        assertEquals(listOf(0.0, 5_020.0, 5_070.0), spawnXs(player, "fx"))
    }

    /**
     * 每帧开销实测：只打印读数，断言只钉静态跳过的 advanceFrame 近乎零成本。
     */
    @Test
    fun `每帧开销实测`() {
        val staticFx = FunctionObject(
            id = "fx0", name = "fx0", center = doubleArrayOf(0.0, 0.0, 0.0),
            source = "func setup() { this.spawn() }", seed = 0, vars = emptyMap(), duration = 0,
        )
        val idleFx = FunctionObject(
            id = "fx0", name = "fx0", center = doubleArrayOf(0.0, 0.0, 0.0),
            source = "func setup() { let i = 0\n while (i < 1151) { let p = this.spawn()\n p.scale = 0\n i = i + 1 } }\n" +
                "func process() { let z = 1 }",
            seed = 0, vars = emptyMap(), duration = 0,
        )

        val staticPlayer = playerOf(staticFx)
        val idlePlayer = playerOf(idleFx)
        val smallPlayer = playerOf(clockFx())
        val emptyTimeline = oscilloscopePlayer(audioDurMs = 0)
        val realAudio60 = oscilloscopePlayer(audioDurMs = 356_308)
        val realAudio160 = oscilloscopePlayer(audioDurMs = 356_308)

        val staticUs = usPerFrame(staticPlayer)
        val idleUs = usPerFrame(idlePlayer)
        val smallUs = usPerFrame(smallPlayer)
        val emptyUs = usPerFrame(emptyTimeline)
        val real60Us = usPerFrame(realAudio60)
        val real160Us = usPerFrame(realAudio160, stepMs = 6.25)

        val line = "[每帧开销] 静态跳过 %.2fµs | 只有 reconcile（%d 派生粒子） %.2fµs | 小 fx（%d 粒子） %.2fµs | " +
            "示波器 fx1 空时间轴（%d 粒子） %.2fµs | 示波器 fx1 真实音频 60fps %.2fµs / 160fps %.2fµs（%d 粒子）"
        println(
            line.format(
                staticUs, idlePlayer.particleCount, idleUs, smallPlayer.particleCount, smallUs,
                emptyTimeline.particleCount, emptyUs, real60Us, real160Us, realAudio160.particleCount,
            ),
        )
        assertTrue(staticUs < 100.0, "静态动画的 advanceFrame 应当还是近乎零成本，实际 %.2fµs".format(staticUs))
    }

    /** 每帧开销（µs）：先热身 [_warmup] 帧不计数，再计时 [_frames] 帧，帧内时刻按 [stepMs] 递增。 */
    private fun usPerFrame(player: ClientAnimationPlayer, stepMs: Double = 16.7, _frames: Int = 500, _warmup: Int = 200): Double {
        var t = 0.0
        repeat(_warmup) {
            t += stepMs
            player.advanceFrame(t)
        }
        val ns = measureNanoTime {
            repeat(_frames) {
                t += stepMs
                player.advanceFrame(t)
            }
        }
        return ns / 1000.0 / _frames
    }

    private companion object {
        /** 示波器工程 fx1 的 vars 默认值（base 值，无关键帧）。 */
        val FX1_VARS: Map<String, Double> = mapOf(
            "winMs" to 12.0, "segMs" to 0.0104166667, "segsMax" to 4000.0, "size" to 1.0,
            "halfW" to 3.3, "halfH" to 2.45, "fill" to 1.85, "gain" to 8.0,
            "dcCut" to 1.0, "dcTauMs" to 200.0, "lineW" to 0.008, "overlap" to 1.15,
            "bright" to 0.3, "velPow" to 1.0, "ageMix" to 0.0, "tauMs" to 6.0,
            "cutExp" to 2.0, "slopeRef" to 2.4, "velMin" to 0.05, "glowOn" to 1.0,
            "fillFrames" to 64.0,
        )
    }
}
