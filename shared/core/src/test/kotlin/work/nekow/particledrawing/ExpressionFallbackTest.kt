package work.nekow.particledrawing

import work.nekow.particledrawing.animation.script.Reg
import work.nekow.particledrawing.animation.script.compileExpressionBlock
import work.nekow.particledrawing.animation.script.evaluate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** 程序表达式：快路径编不出时由完整解释器接管。 */
class ExpressionFallbackTest {

    private fun evalBlock(code: String, extNames: List<String> = emptyList(), external: DoubleArray? = null): DoubleArray {
        val program = assertNotNull(compileExpressionBlock(code, extNames), "应当能编译: $code")
        val regs = program.allocRegs()
        program.eval(0.0, 1.0, 0.0, regs, program.allocStack(), external)
        return regs
    }

    @Test
    fun `向量和高级函数走解释器`() {
        val regs = evalBlock("x = 1; y = vec3(1,2,3).y; z = hash(1,2)")
        assertEquals(1.0, regs[Reg.X])
        assertEquals(2.0, regs[Reg.Y])
        assertEquals(evaluate("hash(1,2)", emptyMap()), regs[Reg.Z], 0.0)
    }

    @Test
    fun `向量解包走解释器`() {
        val regs = evalBlock("[x,y,z] = vec3(1,2,3)")
        assertEquals(1.0, regs[Reg.X])
        assertEquals(2.0, regs[Reg.Y])
        assertEquals(3.0, regs[Reg.Z])
    }

    @Test
    fun `数组解包仍可走快路径`() {
        val regs = evalBlock("[x,y,z] = [4,5,6]")
        assertEquals(4.0, regs[Reg.X])
        assertEquals(5.0, regs[Reg.Y])
        assertEquals(6.0, regs[Reg.Z])
    }

    @Test
    fun `快路径与高级引擎的 rand 一致`() {
        val regs = evalBlock("x = rand(7)")
        assertEquals(evaluate("rand(7)", emptyMap()), regs[Reg.X], 0.0)
    }

    @Test
    fun `外部输入在解释器里也能读到`() {
        val regs = evalBlock("x = __in0 + 1", listOf("__in0"), doubleArrayOf(4.0))
        assertEquals(5.0, regs[Reg.X])
    }

    @Test
    fun `解释器里的属性默认值可读`() {
        val regs = evalBlock("y = x + a")
        assertEquals(1.0, regs[Reg.Y])
    }

    @Test
    fun `布尔输出按 0 或 1 落到属性槽`() {
        val regs = evalBlock("glow = true")
        assertEquals(1.0, regs[Reg.GLOW])
    }
}
