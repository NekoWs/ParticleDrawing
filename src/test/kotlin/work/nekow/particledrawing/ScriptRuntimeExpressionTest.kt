package work.nekow.particledrawing

import work.nekow.particledrawing.animation.script.ScriptException
import work.nekow.particledrawing.animation.script.ScriptRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * UV 字段裸表达式执行器（ScriptRuntime.evalExpression / ExpressionRunner）回归测试。
 */
class ScriptRuntimeExpressionTest {

    private fun ctx(i: Double = 6.0, n: Double = 8.0, t: Double = 0.0): ScriptRuntime.ProcessCtx =
        ScriptRuntime.ProcessCtx(
            i = i, n = n, t = t, dt = 0.0,
            life = 0.0, uv_x = 0.0, uv_y = 0.0, vars = emptyMap(),
        )

    @Test
    fun moduloOnIndex() {
        // this.index % 4：index=6 → 2.0
        assertEquals(2.0, ScriptRuntime.evalExpression("this.index % 4", ctx(i = 6.0)), 1e-12)
    }

    @Test
    fun countAndArith() {
        // this.count / 2 + this.index
        assertEquals(10.0, ScriptRuntime.evalExpression("this.count / 2 + this.index", ctx(n = 8.0, i = 6.0)), 1e-12)
    }

    @Test
    fun timeScalar() {
        assertEquals(3.5, ScriptRuntime.evalExpression("this.time + 0.5", ctx(t = 3.0)), 1e-12)
    }

    @Test
    fun nonNumberResultRejected() {
        // 布尔不是数字 → 抛异常
        assertFailsWith<ScriptException> {
            ScriptRuntime.evalExpression("this.index == 6", ctx(i = 6.0))
        }
    }

    @Test
    fun parseErrorThrows() {
        // 缺右操作数：解析失败
        assertFailsWith<ScriptException> {
            ScriptRuntime.evalExpression("this.index %", ctx())
        }
    }

    @Test
    fun trailingTokenRejected() {
        // 裸表达式后残留 token
        assertFailsWith<ScriptException> {
            ScriptRuntime.evalExpression("this.index % 4 5", ctx())
        }
    }
}