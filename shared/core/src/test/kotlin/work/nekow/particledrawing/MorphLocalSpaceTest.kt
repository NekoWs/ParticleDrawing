package work.nekow.particledrawing

import work.nekow.particledrawing.animation.morph.MorphOptions
import work.nekow.particledrawing.animation.morph.MorphParticle
import work.nekow.particledrawing.animation.morph.MorphRuntime
import work.nekow.particledrawing.animation.morph.MorphSlot
import work.nekow.particledrawing.animation.morph.MorphSpace
import work.nekow.particledrawing.math.Vec3
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 形变槽位的世界 / 局域坐标系。 */
class MorphLocalSpaceTest {
    private val id = UUID(0x4D4F524C, 1)

    private fun runToEnd(runtime: MorphRuntime) {
        repeat(200) { runtime.tick(1.0) }
        assertTrue(runtime.arrivedOf(id), "形变未收敛")
    }

    @Test
    fun `局域槽位以锚点为原点并在锚点移动后保持偏移`() {
        var anchor = Vec3(1000.0, 80.0, 0.0)
        val runtime = MorphRuntime(
            listOf(MorphParticle(id, Vec3(1000.0, 80.0, 0.0))),
            listOf(MorphSlot(Vec3(3.0, 0.0, 0.0))),
            MorphOptions(ticks = 10, space = MorphSpace.LOCAL),
            anchorProvider = { anchor },
        )
        runToEnd(runtime)
        assertEquals(Vec3(1003.0, 80.0, 0.0), runtime.positionOf(id))
        assertEquals(Vec3(1003.0, 80.0, 0.0), runtime.slotPositionOf(id))

        // 锚点平移时目标跟着走，粒子保持在“锚点 + 偏移”上。
        anchor = Vec3(2000.0, 90.0, -5.0)
        runtime.tick(1.0)
        assertEquals(Vec3(2003.0, 90.0, -5.0), runtime.positionOf(id))
        assertEquals(Vec3(2003.0, 90.0, -5.0), runtime.slotPositionOf(id))
    }

    @Test
    fun `锚点移动中途目标随之改变且不产生跳变`() {
        var anchor = Vec3(0.0, 64.0, 0.0)
        val runtime = MorphRuntime(
            listOf(MorphParticle(id, Vec3(0.0, 64.0, 0.0))),
            listOf(MorphSlot(Vec3(10.0, 0.0, 0.0))),
            MorphOptions(ticks = 20, maxSpeed = 0.5, space = MorphSpace.LOCAL),
            anchorProvider = { anchor },
        )
        var previous = runtime.positionOf(id)!!
        repeat(10) {
            runtime.tick(1.0)
            val now = runtime.positionOf(id)!!
            assertTrue(now.distanceTo(previous) <= 0.5 + 1e-6, "位移超过限速")
            previous = now
        }
        anchor = Vec3(5.0, 70.0, 0.0)
        runtime.tick(1.0)
        val now = runtime.positionOf(id)!!
        assertEquals(Vec3(15.0, 70.0, 0.0), runtime.slotPositionOf(id))
        assertTrue(now.x > previous.x && now.y > previous.y, "锚点移动后目标应跟着平移")
    }

    @Test
    fun `世界坐标模式忽略锚点`() {
        val anchor = Vec3(1000.0, 80.0, 0.0)
        val runtime = MorphRuntime(
            listOf(MorphParticle(id, Vec3(0.0, 0.0, 0.0))),
            listOf(MorphSlot(Vec3(3.0, 1.0, -2.0))),
            MorphOptions(ticks = 10),
            anchorProvider = { anchor },
        )
        runToEnd(runtime)
        assertEquals(Vec3(3.0, 1.0, -2.0), runtime.positionOf(id))
        assertEquals(MorphSpace.WORLD, MorphOptions().space)
    }

    @Test
    fun `动态局域槽位每刻按锚点重算`() {
        var anchor = Vec3(0.0, 64.0, 0.0)
        val runtime = MorphRuntime(
            listOf(MorphParticle(id, Vec3(0.0, 64.0, 0.0))),
            listOf(MorphSlot(Vec3(4.0, 0.0, 0.0))),
            MorphOptions(ticks = 5, space = MorphSpace.LOCAL),
            slotProvider = { t ->
                val angle = t / 1000.0
                listOf(MorphSlot(Vec3(cos(angle) * 4.0, sin(angle) * 4.0, 0.0)))
            },
            anchorProvider = { anchor },
        )
        repeat(30) { runtime.tick(1.0) }
        anchor = Vec3(-8.0, 64.0, 3.0)
        repeat(5) { runtime.tick(1.0) }
        val position = runtime.positionOf(id)!!
        val slot = runtime.slotPositionOf(id)!!
        assertEquals(4.0, slot.distanceTo(anchor), 1e-6, "局域槽位应落在锚点半径为 4 的圆周上")
        assertTrue(position.distanceTo(slot) < 1.0, "动态局域槽位应在锚点附近收敛")
    }
}
