package work.nekow.particledrawing

import work.nekow.particledrawing.animation.recipe.ParticleProbe
import work.nekow.particledrawing.animation.recipe.ParticleWorld
import work.nekow.particledrawing.animation.recipe.RecipeCollision
import work.nekow.particledrawing.animation.recipe.RecipeRuntime
import work.nekow.particledrawing.animation.recipe.SurfaceHit
import work.nekow.particledrawing.api.ParticleRecipe
import work.nekow.particledrawing.api.ParticleSampling
import work.nekow.particledrawing.math.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 多组探测：每组可以有独立的起点、方向和筛选，结果按序号进公式。
 *
 * 这里用一个只认「沿 +z 撞到 z=2 的墙」的假世界，验证三组探测各自拿到自己的结果。
 */
class ProbeFieldTest {

    private fun world(calls: MutableList<Triple<Double, Double, Double>>): ParticleWorld =
        ParticleWorld { _, from, to ->
            calls += Triple(from.x, from.y, from.z)
            val hitZ = 2.0
            if (to.z > from.z && from.z <= hitZ && to.z >= hitZ) {
                SurfaceHit(Vec3(from.x, from.y, hitZ), Vec3(0.0, 0.0, -1.0))
            } else {
                null
            }
        }

    private fun recipe(probes: List<ParticleProbe>) = ParticleRecipe(
        ParticleSampling.count(1),
        probes = probes,
        birth = mapOf("vz" to "1"),
        motion = mapOf(
            "r" to "probe_hit",
            "g" to "probe1_hit",
            "b" to "probe2_hit",
            "sc" to "probe2_py",
        ),
    )

    @Test
    fun `三组探测各自按起点与方向判定`() {
        val calls = ArrayList<Triple<Double, Double, Double>>()
        val probes = listOf(
            // 0：沿速度（+z），从粒子位置出发 → 命中
            ParticleProbe(8.0),
            // 1：固定朝 +x，与墙面平行 → 不命中
            ParticleProbe(8.0, direction = Vec3(1.0, 0.0, 0.0)),
            // 2：起点抬高 5 格，仍沿速度 → 命中，且命中点 y 约为 5
            ParticleProbe(8.0, origin = Vec3(0.0, 5.0, 0.0)),
        )
        for (preferFast in listOf(true, false)) {
            calls.clear()
            val runtime = RecipeRuntime(recipe(probes), burst = true, world = world(calls), preferFast = preferFast)
            runtime.tick()
            runtime.tick()
            val p = runtime.particles.single()
            assertEquals(1.0, p.value("r"), "第 0 组应当命中")
            assertEquals(0.0, p.value("g"), "第 1 组朝 +x 不该命中")
            assertEquals(1.0, p.value("b"), "第 2 组应当命中")
            assertTrue(p.value("sc") >= 5.0, "第 2 组从抬高 5 格的起点出发，命中点 y 应不小于 5，实际 ${p.value("sc")}")
        }
        // 三组各探测一次：两刻共 6 次（第 1 刻出生也做准备）
        assertTrue(calls.size >= 6, "每组每刻都应查询，实际 ${calls.size} 次")
    }

    @Test
    fun `材质筛选只影响匹配字段，不改变是否命中`() {
        val iron = work.nekow.particledrawing.math.ResourceId.parse("minecraft:iron_block")
        val probes = listOf(ParticleProbe(8.0, mode = RecipeCollision.SOLID, material = iron))
        val plain = RecipeRuntime(
            ParticleRecipe(ParticleSampling.count(1), probes = probes, birth = mapOf("vz" to "1"),
                motion = mapOf("r" to "probe_hit", "g" to "probe_matches")),
            burst = true, world = world(ArrayList()), preferFast = false,
        )
        plain.tick(); plain.tick()
        val p = plain.particles.single()
        assertEquals(1.0, p.value("r"), "命中为真")
        assertEquals(0.0, p.value("g"), "假世界不带材质，筛选应当不匹配")
    }

    @Test
    fun `没有声明探测时探测输入不可用`() {
        val failing = runCatching {
            ParticleRecipe(
                ParticleSampling.count(1),
                motion = mapOf("r" to "probe_hit"),
            ).validate()
        }
        assertTrue(failing.isFailure, "没声明探测却读探测输入应当在配置期报错")
    }
}
