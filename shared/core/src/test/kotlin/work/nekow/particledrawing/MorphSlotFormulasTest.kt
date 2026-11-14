package work.nekow.particledrawing

import work.nekow.particledrawing.animation.morph.MorphParticle
import work.nekow.particledrawing.animation.morph.MorphSlotFormulas
import work.nekow.particledrawing.animation.morph.MorphOptions
import work.nekow.particledrawing.animation.morph.MorphRuntime
import work.nekow.particledrawing.math.Vec3
import java.util.UUID
import kotlin.math.abs
import kotlin.test.*

/** 公式驱动的槽位：静态形状、动态目标与每粒子控制点。 */
class MorphSlotFormulasTest {

    @Test
    fun `按 u 生成一圈槽位且半径一致`() {
        val formulas = MorphSlotFormulas(12, "vec3(cos(u*6.2832)*2, 1, sin(u*6.2832)*2)")
        val slots = formulas.build(0.0)
        assertEquals(12, slots.size)
        for (s in slots) {
            val r = abs(s.position.y - 1.0)
            assertTrue(r < 1e-9, "槽位不应有高度差")
            val radius = kotlin.math.sqrt(s.position.x * s.position.x + s.position.z * s.position.z)
            assertTrue(abs(radius - 2.0) < 1e-3, "半径应为 2，实际 $radius")
        }
        // 首尾各一个完整循环的另一端
        assertTrue(abs(slots.first().position.x - 2.0) < 1e-3)
        assertTrue(abs(slots.last().position.x - 2.0) < 1e-3)
    }

    @Test
    fun `动态目标会随时间旋转`() {
        val formulas = MorphSlotFormulas(8, "vec3(cos(u*6.2832+t*0.001)*2, 1, sin(u*6.2832+t*0.001)*2)")
        val a = formulas.build(0.0)
        val b = formulas.build(1000.0)
        assertTrue(abs(a[1].position.x - b[1].position.x) > 1e-3, "一秒后槽位应当转过一个角度")
    }

    @Test
    fun `朝向尺寸与控制点可以分别给`() {
        val formulas = MorphSlotFormulas(
            count = 4,
            position = "vec3(u*4, 0, 0)",
            yaw = "u*90",
            size = "0.1+u*0.2",
            control1 = "vec3(u*4, 2, 1)",
        )
        val slots = formulas.build(0.0)
        assertEquals(0.0, slots.first().yaw)
        assertEquals(90.0, slots.last().yaw)
        assertEquals(0.1, slots.first().size, 1e-9)
        assertEquals(0.3, slots.last().size, 1e-9)
        assertEquals(2.0, slots.first().control1!!.y)
    }

    @Test
    fun `公式槽位能直接驱动一次形变`() {
        val formulas = MorphSlotFormulas(16, "vec3(cos(u*6.2832), 2, sin(u*6.2832))")
        val particles = List(16) { i ->
            val a = 2.0 * Math.PI * i / 16
            MorphParticle(UUID(7L, i.toLong()), Vec3(Math.cos(a) * 4, 0.0, Math.sin(a) * 4), Vec3.ZERO, i)
        }
        val runtime = MorphRuntime(particles, formulas.build(0.0), MorphOptions(ticks = 20))
        var guard = 0
        while (!runtime.isDone() && guard++ < 200) runtime.tick()
        assertTrue(runtime.isDone())
        assertTrue(runtime.maxDistanceToSlot() <= 0.05 + 1e-9)
    }

    @Test
    fun `位置公式不是向量时报错`() {
        val formulas = MorphSlotFormulas(2, "u*2")
        val ex = assertFailsWith<IllegalArgumentException> { formulas.build(0.0) }
        assertTrue(ex.message!!.contains("vec3"))
    }
}
