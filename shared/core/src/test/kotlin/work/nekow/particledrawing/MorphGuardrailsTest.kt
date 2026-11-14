package work.nekow.particledrawing

import work.nekow.particledrawing.animation.morph.*
import work.nekow.particledrawing.math.Vec3
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.*

/**
 * 编队变换的十条护栏（G1–G10）。
 *
 * 每条都对着一个可以被机器判定的性质，不靠肉眼：位置差、收敛距离、分配多重集、
 * 轨迹哈希与耗时都在这里断言。性能一条（G8）默认跑，设 `-Dpd.skipPerf=true` 可跳过。
 */
class MorphGuardrailsTest {

    private fun id(n: Int) = UUID(0x4D4F525048L, n.toLong())

    /** 一圈粒子，半径 3。 */
    private fun ringParticles(n: Int): List<MorphParticle> = List(n) { i ->
        val a = 2.0 * Math.PI * i / n
        MorphParticle(id(i), Vec3(Math.cos(a) * 3.0, 0.0, Math.sin(a) * 3.0), Vec3.ZERO, i)
    }

    /** 一圈槽位，半径 1，抬高 2。 */
    private fun ringSlots(n: Int): List<MorphSlot> = List(n) { i ->
        val a = 2.0 * Math.PI * i / n
        MorphSlot(Vec3(Math.cos(a) * 1.0, 2.0, Math.sin(a) * 1.0))
    }

    private fun squareSlots(): List<MorphSlot> = listOf(
        MorphSlot(Vec3(1.0, 2.0, 1.0)),
        MorphSlot(Vec3(-1.0, 2.0, 1.0)),
        MorphSlot(Vec3(-1.0, 2.0, -1.0)),
        MorphSlot(Vec3(1.0, 2.0, -1.0)),
    )

    // ── G1 无跳变 ──────────────────────────────────────────────────────────────

    @Test
    fun `G1 单帧位移不超过速度上限`() {
        val maxSpeed = 0.4
        val runtime = MorphRuntime(
            ringParticles(32), ringSlots(32),
            MorphOptions(ticks = 10, maxSpeed = maxSpeed, easing = MorphEasing.OUT_BACK),
        )
        val last = HashMap<UUID, Vec3>()
        for (p in runtime.ids()) last[p] = runtime.positionOf(p)!!
        repeat(60) {
            runtime.tick(1.0)
            for (p in runtime.ids()) {
                val now = runtime.positionOf(p)!!
                val prev = last[p]!!
                val step = now.distanceTo(prev)
                assertTrue(step <= maxSpeed + 1e-6, "单帧位移 $step 超过上限 $maxSpeed")
                last[p] = now
            }
        }
    }

    @Test
    fun `G1 重入那一帧同样不跳变`() {
        val maxSpeed = 0.35
        val runtime = MorphRuntime(ringParticles(16), ringSlots(16), MorphOptions(ticks = 8, maxSpeed = maxSpeed))
        repeat(4) { runtime.tick() }
        val before = runtime.ids().associateWith { runtime.positionOf(it)!! }
        // 中途换目标：按当前实际位置与速度为起点重建。
        val next = MorphRuntime(
            runtime.ids().mapIndexed { i, p ->
                MorphParticle(p, runtime.positionOf(p)!!, runtime.velocityOf(p)!!, i)
            },
            squareSlots() + ringSlots(12),
            MorphOptions(ticks = 8, maxSpeed = maxSpeed),
        )
        var last = before
        repeat(40) {
            next.tick()
            for (p in next.ids()) {
                val now = next.positionOf(p)!!
                val prev = last[p]!!
                assertTrue(now.distanceTo(prev) <= maxSpeed + 1e-6, "重入后单帧位移超限")
            }
            last = next.ids().associateWith { next.positionOf(it)!! }
        }
    }

    // ── G2 收敛 ────────────────────────────────────────────────────────────────

    @Test
    fun `G2 过渡结束后全部进入收敛阈值`() {
        val eps = 0.05
        val runtime = MorphRuntime(ringParticles(64), ringSlots(64), MorphOptions(ticks = 30, convergeEps = eps))
        var guard = 0
        while (!runtime.isDone() && guard++ < 200) runtime.tick()
        assertTrue(runtime.isDone(), "过渡没有完成")
        assertTrue(runtime.maxDistanceToSlot() <= eps, "最大距离 ${runtime.maxDistanceToSlot()} 超过阈值 $eps")
    }

    @Test
    fun `G2 动态目标按相对位移判定收敛`() {
        // 目标一边绕圈一边等粒子追，粒子要能稳定贴住而不是永远追不上。
        val eps = 0.08
        val runtime = MorphRuntime(
            ringParticles(32), ringSlots(32),
            MorphOptions(ticks = 20, convergeEps = eps, maxSpeed = 2.0),
        ) { tMs ->
            val a = tMs * 0.0005
            List(32) { i ->
                val b = 2.0 * Math.PI * i / 32 + a
                MorphSlot(Vec3(Math.cos(b) * 2.0, 2.0, Math.sin(b) * 2.0))
            }
        }
        var guard = 0
        while (!runtime.isDone() && guard++ < 400) runtime.tick()
        assertTrue(guard < 400, "动态目标下没有收敛")
    }

    // ── G3 映射完整性 ──────────────────────────────────────────────────────────

    @Test
    fun `G3 等量时槽位恰好各占一次`() {
        val particles = ringParticles(20)
        val slots = ringSlots(20)
        val result = MorphAllocator.allocate(particles, slots, MorphAllocation.NEAREST)
        assertEquals(20, result.slotOfParticle.size)
        assertEquals(20, result.slotOfParticle.values.toSet().size)
        assertTrue(result.unmatchedSlots.isEmpty())
    }

    @Test
    fun `G3 粒子多于槽位时多出的待命且可查`() {
        val runtime = MorphRuntime(ringParticles(12), ringSlots(8), MorphOptions(ticks = 5))
        assertEquals(4, runtime.waiting.size, "应有 4 颗待命")
        // 待命的粒子仍是群体成员：身份不变，只是没有目标、不参与推进。
        assertEquals(12, runtime.ids().size)
        assertTrue(runtime.unmatchedSlots.isEmpty())
        for (p in runtime.waiting) assertNull(runtime.slotOf(p), "待命粒子不应有槽位")
    }

    @Test
    fun `G3 槽位多于粒子时空置槽位可查`() {
        val runtime = MorphRuntime(ringParticles(6), ringSlots(10), MorphOptions(ticks = 5))
        assertEquals(4, runtime.unmatchedSlots.size)
        assertTrue(runtime.waiting.isEmpty())
    }

    // ── G4 可重入 ──────────────────────────────────────────────────────────────

    @Test
    fun `G4 中途重入不产生非法值且身份不变`() {
        val runtime = MorphRuntime(ringParticles(48), ringSlots(48), MorphOptions(ticks = 40))
        val ids = runtime.ids().toSet()
        repeat(13) { runtime.tick() }
        val restarted = MorphRuntime(
            runtime.ids().mapIndexed { i, p -> MorphParticle(p, runtime.positionOf(p)!!, runtime.velocityOf(p)!!, i) },
            squareSlots() + ringSlots(44),
            MorphOptions(ticks = 25),
            slotProvider = null,
        )
        repeat(25) { restarted.tick() }
        assertEquals(ids, restarted.ids().toSet())
        for (p in restarted.ids()) {
            val pos = restarted.positionOf(p)!!
            assertTrue(pos.x.isFinite() && pos.y.isFinite() && pos.z.isFinite(), "出现非有限坐标")
        }
    }

    // ── G5 可观测 ──────────────────────────────────────────────────────────────

    @Test
    fun `G5 查询值与实际位置一致`() {
        val runtime = MorphRuntime(ringParticles(16), ringSlots(16), MorphOptions(ticks = 20))
        repeat(9) { runtime.tick() }
        for (p in runtime.ids()) {
            val queried = runtime.positionOf(p)!!
            val slot = runtime.slotPositionOf(p)!!
            assertTrue(runtime.slotOf(p) != null)
            assertTrue(runtime.progressOf(p) in 0.0..1.0)
            assertTrue(queried.distanceTo(slot) >= 0.0)
            assertEquals(runtime.arrivedOf(p), runtime.progressOf(p) >= 1.0 && queried.distanceTo(slot) <= 0.05 + 1e-9)
        }
    }

    // ── G6 确定性 ──────────────────────────────────────────────────────────────

    @Test
    fun `G6 相同输入得到完全相同轨迹`() {
        fun run(): Double {
            val runtime = MorphRuntime(
                ringParticles(32), ringSlots(32),
                MorphOptions(ticks = 15, wobble = MorphWobble.NOISE, wobbleStrength = 0.3),
            )
            var hash = 17.0
            repeat(20) {
                runtime.tick()
                for (p in runtime.ids()) {
                    val v = runtime.positionOf(p)!!
                    hash = hash * 31 + v.x + v.y * 7 + v.z * 13
                }
            }
            return hash
        }
        assertEquals(run(), run(), "同输入两次运行的轨迹哈希不一致")
    }

    // ── G7 属性隔离 ────────────────────────────────────────────────────────────

    @Test
    fun `G7 默认不改朝向与尺寸`() {
        val slots = ringSlots(8).mapIndexed { i, s -> s.copy(yaw = i * 30.0, pitch = 10.0, size = 0.5) }
        val runtime = MorphRuntime(ringParticles(8), slots, MorphOptions(ticks = 10))
        repeat(12) { runtime.tick() }
        for (p in runtime.ids()) {
            assertNull(runtime.yawOf(p), "默认不应写朝向")
            assertNull(runtime.sizeOf(p), "默认不应写尺寸")
        }
    }

    @Test
    fun `G7 显式开启后写入朝向与尺寸`() {
        val slots = ringSlots(8).map { it.copy(yaw = 45.0, pitch = 5.0, size = 0.7) }
        val runtime = MorphRuntime(ringParticles(8), slots, MorphOptions(ticks = 5, writeAttributes = true))
        repeat(8) { runtime.tick() }
        for (p in runtime.ids()) {
            assertEquals(45.0, runtime.yawOf(p))
            assertEquals(0.7, runtime.sizeOf(p))
        }
    }

    // ── G8 性能 ────────────────────────────────────────────────────────────────

    @Test
    fun `G8 4096 粒子单刻耗时在预算内`() {
        if (System.getProperty("pd.skipPerf") != null) return
        val n = 4096
        val runtime = MorphRuntime(
            List(n) { i ->
                val a = 2.0 * Math.PI * i / n
                MorphParticle(id(i), Vec3(Math.cos(a) * 6.0, 0.0, Math.sin(a) * 6.0), Vec3.ZERO, i)
            },
            List(n) { i ->
                val a = 2.0 * Math.PI * i / n
                MorphSlot(Vec3(Math.cos(a) * 2.0, 3.0, Math.sin(a) * 2.0))
            },
            MorphOptions(ticks = 40, wobble = MorphWobble.BELL, wobbleStrength = 0.1),
        )
        repeat(5) { runtime.tick() } // 预热
        val start = System.nanoTime()
        repeat(20) { runtime.tick() }
        val perTickMs = (System.nanoTime() - start) / 1e6 / 20.0
        println("G8 morph 4096 粒子单刻耗时 ${"%.3f".format(perTickMs)} ms")
        assertTrue(perTickMs <= 2.0, "单刻耗时 $perTickMs ms 超出 2ms 预算")
    }

    // ── G9 生命周期不变量 ──────────────────────────────────────────────────────

    @Test
    fun `G9 过渡不增不减成员`() {
        val runtime = MorphRuntime(ringParticles(24), ringSlots(24), MorphOptions(ticks = 10))
        val before = runtime.ids().toSet()
        repeat(15) { runtime.tick() }
        assertEquals(before, runtime.ids().toSet())
    }

    // ── G10 中断可恢复 ────────────────────────────────────────────────────────

    @Test
    fun `G10 连续中断后最终仍能收敛`() {
        var runtime = MorphRuntime(ringParticles(32), ringSlots(32), MorphOptions(ticks = 30))
        repeat(6) { k ->
            repeat(3 + k) { runtime.tick() }
            val snapshot = runtime.ids().mapIndexed { i, p ->
                MorphParticle(p, runtime.positionOf(p)!!, runtime.velocityOf(p)!!, i)
            }
            runtime = MorphRuntime(
                snapshot,
                if (k % 2 == 0) squareSlots() + ringSlots(28) else ringSlots(32),
                MorphOptions(ticks = 20),
            )
        }
        var guard = 0
        while (!runtime.isDone() && guard++ < 400) runtime.tick()
        assertTrue(runtime.isDone(), "连续中断后无法收敛")
        assertTrue(runtime.maxDistanceToSlot() <= 0.05 + 1e-9, "最终距离仍超阈值")
    }

    // ── 路径与扰动 ────────────────────────────────────────────────────────────

    @Test
    fun `三次贝塞尔路径经过控制点附近`() {
        val control1 = Vec3(6.0, 4.0, 0.0)
        val control2 = Vec3(6.0, 6.0, 0.0)
        val runtime = MorphRuntime(
            listOf(MorphParticle(id(0), Vec3(0.0, 0.0, 0.0))),
            listOf(MorphSlot(Vec3(10.0, 0.0, 0.0), control1 = control1, control2 = control2)),
            MorphOptions(ticks = 10, path = MorphPath.CUBIC),
        )
        var maxX = 0.0
        repeat(12) {
            runtime.tick()
            maxX = maxOf(maxX, runtime.positionOf(id(0))!!.x)
        }
        assertTrue(maxX > 5.0, "三次贝塞尔没有把路径拉出去，maxX=$maxX")
    }

    @Test
    fun `涡流扰动最后收敛且过程有偏转`() {
        val runtime = MorphRuntime(
            listOf(MorphParticle(id(0), Vec3(0.0, 0.0, 0.0))),
            listOf(MorphSlot(Vec3(4.0, 0.0, 0.0))),
            MorphOptions(ticks = 10, wobble = MorphWobble.VORTEX, wobbleStrength = 0.8, wobblePeaks = 1.0),
        )
        var maxZ = 0.0
        repeat(12) {
            runtime.tick()
            maxZ = maxOf(maxZ, abs(runtime.positionOf(id(0))!!.z))
        }
        assertTrue(maxZ > 0.1, "涡流没有产生横向偏移")
        assertTrue(runtime.maxDistanceToSlot() <= 0.05 + 1e-9, "扰动后没有收敛")
    }
}
