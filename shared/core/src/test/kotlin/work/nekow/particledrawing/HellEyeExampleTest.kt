package work.nekow.particledrawing

import work.nekow.particledrawing.animation.recipe.RecipeExpressions
import work.nekow.particledrawing.animation.recipe.RecipeRuntime
import work.nekow.particledrawing.api.ParticleRecipe
import work.nekow.particledrawing.api.ParticleSampling
import work.nekow.particledrawing.math.Vec3
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 地狱之眼示例（examples/commands/hell-eye*.mcfunction）的可运行性回归。
 *
 * 按命令的口径读文件里真正写下的采样与公式，再用 [RecipeRuntime] 走一遍时间轴：
 * 开场三段绽放、持续四段在第 10 刻接上、第 12 秒收场坍缩。这样示例里的曲线、
 * 发射窗口和寿命一旦写错，不用进游戏就能发现。
 */
class HellEyeExampleTest {
    private fun config(name: String): Pair<Map<String, ParticleExampleDeclarations.Entry>, List<Pair<String, String>>> {
        val configs = ParticleExampleDeclarations.read(name)
        return configs.associateBy { it.name } to configs.mapNotNull { entry -> entry.action?.let { entry.name to it } }
    }

    private fun fast(recipe: ParticleRecipe): Boolean = RecipeExpressions(recipe).fast != null

    @Test fun `地狱之眼开场绽放后在第 10 刻切换持续状态`() {
        val (drafts, invocations) = config("hell-eye")
        assertEquals(9, drafts.size)
        val recipes = drafts.mapValues { it.value.recipe }
        assertEquals(
            listOf("hell_eye_intro_outer", "hell_eye_intro_ring", "hell_eye_intro_inner", "hell_eye_intro_motes",
                "hell_eye_hold_outer", "hell_eye_hold_ring", "hell_eye_hold_motes", "hell_eye_hold_inner"),
            invocations.map { it.first },
        )
        // 全部配方都要能走寄存器快路径，否则几千颗粒子会掉进解释器
        recipes.forEach { (name, recipe) -> assertTrue(fast(recipe), "$name 没有走快路径") }

        val runtimes = invocations.map { (name, action) ->
            name to RecipeRuntime(recipes.getValue(name), burst = action == "burst")
        }
        fun runtime(name: String) = runtimes.first { it.first == name }.second
        fun tickAll() = runtimes.forEach { it.second.tick() }

        tickAll()
        assertEquals(1001, runtime("hell_eye_intro_outer").particles.size)
        assertEquals(1001, runtime("hell_eye_intro_ring").particles.size)
        assertEquals(1001, runtime("hell_eye_intro_inner").particles.size)
        assertEquals(500, runtime("hell_eye_intro_motes").particles.size)
        // 持续段用发射窗口等开场结束
        recipes.filterKeys { it.startsWith("hell_eye_hold_") }.forEach { (name, recipe) ->
            assertEquals(10, recipe.emitFromTick, "$name 应在第 10 刻接上")
        }
        assertTrue(runtime("hell_eye_hold_outer").particles.isEmpty())

        repeat(10) { tickAll() } // 第 11 次调用：开场粒子到寿命，持续状态同刻接上
        val outerIntro = runtime("hell_eye_intro_outer")
        assertTrue(outerIntro.particles.isEmpty(), "开场外层应已到寿命")
        assertTrue(runtime("hell_eye_hold_outer").particles.isNotEmpty(), "第 10 刻外层持续状态应开始出生")
        assertEquals(1001, runtime("hell_eye_hold_inner").particles.size)

        repeat(10) { tickAll() } // 再跑 10 刻到稳态
        runtimes.forEach { (name, runtime) ->
            if (name.startsWith("hell_eye_intro_")) {
                assertTrue(runtime.particles.size <= 500, "$name 开场应已基本清空")
            }
        }
        val outer = runtime("hell_eye_hold_outer")
        assertTrue(outer.particles.size in 4000..8192, "外层稳态应在 4k–8k，实际 ${outer.particles.size}")
        assertTrue(runtime("hell_eye_hold_ring").particles.size in 2000..2600)
        assertTrue(runtime("hell_eye_hold_motes").particles.size in 40..512)

        // 持续段仍在图形附近：随机外扩让边缘略散开，内层几乎不动
        val meanX = outer.particles.map { abs(it.position.x) }.average()
        assertTrue(meanX in 5.0..10.0, "外层平均横向位置异常：$meanX")
        outer.particles.forEach { p ->
            assertTrue(abs(p.position.x) <= 30.0 && abs(p.position.y) <= 9.0, "外层粒子跑出图形：${p.position}")
        }
        val inner = runtime("hell_eye_hold_inner").particles
        inner.forEach { p -> assertTrue(abs(p.position.x) <= 1.2 && abs(p.position.y) <= 2.2, "内侧星跑出图形：${p.position}") }
    }

    @Test fun `地狱之眼收场在三组图元上同时向内坍缩`() {
        val (startDrafts, invocations) = config("hell-eye")
        assertTrue(invocations.isNotEmpty())
        startDrafts.mapValues { it.value.recipe } // 起手配置本身必须合法
        val (drafts, _) = config("hell-eye-end")
        assertEquals(3, drafts.size)
        val recipes = drafts.mapValues { it.value.recipe }
        assertEquals(1001, recipes.getValue("hell_eye_end_outer").seeds.size)
        assertEquals(1001, recipes.getValue("hell_eye_end_ring").seeds.size)
        assertEquals(1001, recipes.getValue("hell_eye_end_inner").seeds.size)
        recipes.forEach { (name, recipe) ->
            assertEquals(5, recipe.lifetime, "$name 坍缩寿命应为 5 刻")
            assertTrue(fast(recipe), "$name 没有走快路径")
        }
        val runtimes = recipes.values.map { RecipeRuntime(it, burst = true) }
        runtimes.forEach { it.tick() }
        assertEquals(3003, runtimes.sumOf { it.particles.size })
        val outer = runtimes.first()
        val before = outer.particles.maxOf { abs(it.position.x) }
        runtimes.forEach { it.tick() }
        val after = outer.particles.maxOf { abs(it.position.x) }
        assertTrue(after < before * 0.6, "坍缩一刻后半径应明显变小：$before -> $after")
        repeat(5) { runtimes.forEach { it.tick() } }
        runtimes.forEach { assertTrue(it.finished, "坍缩应在 5 刻寿命后结束") }
        // 服务端回收上界要罩得住整段窗口
        recipes.values.forEach { recipe ->
            assertTrue(RecipeRuntime.retirementBudget(recipe) >= 6, "回收上界太短")
        }
    }

    @Test fun `清空函数覆盖地狱之眼全部配置`() {
        val all = linkedSetOf<String>()
        all += config("hell-eye").first.keys
        all += config("hell-eye-end").first.keys
        val cleared = Files.readAllLines(Path.of("examples/commands/hell-eye-clear.mcfunction"))
            .filter { it.startsWith("pdraw particle delete emitter ") }
            .map { it.substringAfterLast(' ') }.toSet()
        assertEquals(all, cleared, "clear 函数漏掉了配置")
        assertTrue(Files.readAllLines(Path.of("examples/commands/hell-eye-clear.mcfunction"))
            .any { it.trim().startsWith("schedule clear ") })
    }

    @Test fun `持续状态的内侧星寿命无限且整体在锚点平面内`() {
        val (drafts, _) = config("hell-eye")
        val inner = drafts.getValue("hell_eye_hold_inner").recipe
        assertEquals(-1, inner.lifetime)
        val outer = drafts.getValue("hell_eye_hold_outer").recipe
        assertEquals(8, outer.lifetime)
        assertEquals(1024, outer.rate)
        assertEquals(8192, outer.maxAlive)
        val runtime = RecipeRuntime(inner, burst = true)
        repeat(11) { runtime.tick() } // emit_from 10：第 11 颗 tick 出生
        val particles = runtime.particles
        assertEquals(1001, particles.size)
        particles.forEach { assertEquals(0.0, it.position.z, 1e-12) }
        val width = particles.maxOf { abs(it.position.x) }
        val height = particles.maxOf { abs(it.position.y) }
        assertEquals(1.0, width, 1e-3)
        assertEquals(2.0, height, 1e-3)
    }

    @Test fun `开场曲线尺寸与 ColorBlock 一致`() {
        val (drafts, _) = config("hell-eye")
        val outer = RecipeRuntime(drafts.getValue("hell_eye_intro_outer").recipe, burst = true)
        val ring = RecipeRuntime(drafts.getValue("hell_eye_intro_ring").recipe, burst = true)
        val inner = RecipeRuntime(drafts.getValue("hell_eye_intro_inner").recipe, burst = true)
        repeat(9) { outer.tick(); ring.tick(); inner.tick() }
        // 第 9 刻接近最终尺寸（ColorBlock 指数绽放到 97%–111%，采样步长让极值略小于理论值）
        val maxOuterX = outer.particles.maxOf { abs(it.position.x) }
        val maxOuterY = outer.particles.maxOf { abs(it.position.y) }
        val ringRadius = ring.particles.maxOf { it.position.length() }
        val innerX = inner.particles.maxOf { abs(it.position.x) }
        val innerY = inner.particles.maxOf { abs(it.position.y) }
        assertTrue(maxOuterX in 12.5..13.5, "外层横向 $maxOuterX")
        assertTrue(maxOuterY in 4.2..4.6, "外层纵向 $maxOuterY")
        assertTrue(ringRadius in 2.30..2.38, "圆环半径 $ringRadius")
        assertTrue(innerX in 1.00..1.08, "内侧横向 $innerX")
        assertTrue(innerY in 2.00..2.15, "内侧纵向 $innerY")
        assertTrue(outer.particles.all { abs(it.position.z) < 1e-12 })
    }
}
