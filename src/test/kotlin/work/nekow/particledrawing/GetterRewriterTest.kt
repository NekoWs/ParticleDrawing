package work.nekow.particledrawing

import work.nekow.particledrawing.animation.script.GetterRewriter
import work.nekow.particledrawing.animation.script.InputKey
import work.nekow.particledrawing.animation.script.Reg
import work.nekow.particledrawing.animation.script.compileFunctionObject
import work.nekow.particledrawing.animation.script.evaluate
import work.nekow.particledrawing.api.EntityProp
import work.nekow.particledrawing.api.WorldProp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 被动输入 getter 的纯 JVM 回归测试：
 * 重写/发现语义（与客户端 ClientAnimationProgramManager 注入约定对齐）+ 纯标量快路径端到端求值。
 */
class GetterRewriterTest {

    /* ---------------- 重写与发现 ---------------- */

    @Test
    fun discoversComponentGetters() {
        val rw = GetterRewriter.rewrite(
            "x = get_entity_yaw(t0) + get_world_rain()",
            handles = mapOf("t0" to 0), entityCount = 1,
        )
        assertEquals(listOf("__in0", "__in1"), rw.extNames)
        assertEquals(
            listOf<InputKey>(InputKey.Entity(0, EntityProp.YAW), InputKey.World(WorldProp.RAIN)),
            rw.keys,
        )
        assertTrue("__in0" in rw.code && "__in1" in rw.code)
        assertTrue("get_" !in rw.code)
    }

    @Test
    fun posTripleExpandsToComponents() {
        val rw = GetterRewriter.rewrite("[x,y,z] = get_entity_pos(e1)", mapOf("e1" to 1), entityCount = 2)
        assertEquals(
            listOf<InputKey>(
                InputKey.Entity(1, EntityProp.X), InputKey.Entity(1, EntityProp.Y), InputKey.Entity(1, EntityProp.Z),
            ),
            rw.keys,
        )
        assertEquals("[x,y,z] = [__in0, __in1, __in2]", rw.code)
    }

    @Test
    fun numericHandleAndBounds() {
        val ok = GetterRewriter.rewrite("y = get_entity_hp(1)", emptyMap(), entityCount = 2)
        assertEquals(listOf<InputKey>(InputKey.Entity(1, EntityProp.HP)), ok.keys)

        assertFailsWith<IllegalArgumentException> {
            GetterRewriter.rewrite("y = get_entity_hp(5)", emptyMap(), entityCount = 2)
        }
        assertFailsWith<IllegalArgumentException> {
            GetterRewriter.rewrite("y = get_entity_hp(1.5)", emptyMap(), entityCount = 2)
        }
    }

    @Test
    fun unknownNamesFailFast() {
        assertFailsWith<IllegalArgumentException> {
            GetterRewriter.rewrite("x = get_entity_bogus(e)", mapOf("e" to 0), 1)
        }
        assertFailsWith<IllegalArgumentException> {
            GetterRewriter.rewrite("x = get_world_bogus()", emptyMap(), 0)
        }
        // world 不接受参数 / entity 缺参 / 未登记句柄
        assertFailsWith<IllegalArgumentException> { GetterRewriter.rewrite("x = get_world_rain(1)", emptyMap(), 0) }
        assertFailsWith<IllegalArgumentException> { GetterRewriter.rewrite("x = get_entity_hp()", mapOf("e" to 0), 1) }
        assertFailsWith<IllegalArgumentException> { GetterRewriter.rewrite("x = get_entity_hp(who)", mapOf("e" to 0), 1) }
        // pos 整取不允许出现在普通表达式上下文
        assertFailsWith<IllegalArgumentException> { GetterRewriter.rewrite("x = get_entity_pos(e) + 1", mapOf("e" to 0), 1) }
    }

    @Test
    fun noGetterPassesThrough() {
        val rw = GetterRewriter.rewrite("[x,y,z] = [i, n, t]", emptyMap(), 0)
        assertEquals("[x,y,z] = [i, n, t]", rw.code)
        assertTrue(rw.extNames.isEmpty() && rw.keys.isEmpty())
    }

    /* ---------------- lint（服务端 best-effort 预警） ---------------- */

    @Test
    fun lintReportsDefiniteErrorsOnly() {
        val problems = GetterRewriter.lint("x = get_entity_bogus(h); y = get_world_rain(1); z = get_entity_yaw(target)")
        assertEquals(2, problems.size) // 未知名 + world 带参；get_entity_yaw(target) 合法形态不报
        assertTrue(problems.any { "bogus" in it })
        assertTrue(problems.any { "rain" in it })
    }

    /* ---------------- 快路径端到端：重写 → 编译 → 外部注入求值 ---------------- */

    @Test
    fun compiledEvalInjectsDiscoveredInputs() {
        val rw = GetterRewriter.rewrite(
            "[x,y,z] = [get_entity_x(e) + i, get_entity_y(e), get_entity_z(e)]",
            mapOf("e" to 0), entityCount = 1,
        )
        // 模拟 Manager 布局：externals = 合成输入名 + 程序变量名
        val extAll = rw.extNames + listOf("speed")
        val cf = compileFunctionObject(rw.code, emptyList(), extAll)!!

        val regs = cf.allocRegs()
        val stack = cf.allocStack()
        // latestInputs 快照：__in0..2 = 实体坐标；speed = 变量值
        val external = doubleArrayOf(10.0, 20.0, 30.0, 2.0)
        cf.eval(i = 5.0, n = 1.0, t = 0.0, regs, stack, external)

        assertEquals(15.0, regs[Reg.X])
        assertEquals(20.0, regs[Reg.Y])
        assertEquals(30.0, regs[Reg.Z])

        // 变量参与运算：speed * get_entity_x(e)
        val rw2 = GetterRewriter.rewrite("x = speed * get_entity_x(e)", mapOf("e" to 0), 1)
        val cf2 = compileFunctionObject(rw2.code, emptyList(), rw2.extNames + listOf("speed"))!!
        val regs2 = cf2.allocRegs()
        cf2.eval(0.0, 1.0, 0.0, regs2, cf2.allocStack(), doubleArrayOf(10.0, 3.0))
        assertEquals(30.0, regs2[Reg.X])
    }

    /* ---------------- 解释器路径（setVariableLive 同款） ---------------- */

    @Test
    fun interpreterPathEvaluatesRewrittenExpr() {
        val rw = GetterRewriter.rewrite("get_world_day_time() / 24000 + get_entity_exists(e)", mapOf("e" to 0), 1)
        val scope = HashMap<String, Any>()
        rw.extNames.forEachIndexed { idx, name -> scope[name] = if (idx == 0) 12000.0 else 1.0 }
        val result = evaluate(rw.code, scope)
        assertEquals(1.5, result)
    }
}
