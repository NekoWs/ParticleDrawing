package work.nekow.particledrawing

import work.nekow.particledrawing.api.script.FuncsScope
import work.nekow.particledrawing.api.script.ProcessScope
import work.nekow.particledrawing.api.script.SetupScope
import work.nekow.particledrawing.api.script.x
import work.nekow.particledrawing.api.script.r
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 脚本 DSL 核心生成文本回归测试：生成的 setup/process/funcs 文本需与 ScriptParser 语法对齐。
 */
class ScriptBuilderTest {

    @Test
    fun processDslEmitsOutputAssignments() {
        val sb = ProcessScope()
        sb.apply {
            var th by numVar()
            th = index / count * 2 * pi
            position = vec3(cos(th) * v("rad"), 0, sin(th) * v("rad"))
            raw("if (this.index % 2 == 0) { this.color = vec4(1,0,0,1); }")
        }

        assertEquals(
            listOf(
                "th = this.index / this.count * 2 * pi;",
                "this.position = vec3(cos(th) * rad, 0, sin(th) * rad);",
                "if (this.index % 2 == 0) { this.color = vec4(1,0,0,1); }",
            ).joinToString("\n"),
            sb.build(),
        )
    }

    @Test
    fun setupDslEmitsGlobalAndRaw() {
        val sb = SetupScope()
        sb.apply {
            global("arr", array())
            raw("for (k = 0; k < this.count; k = k + 1) { arr.push(vec3(k, 0, 0)); }")
        }

        assertEquals(
            listOf(
                "global arr = [];",
                "for (k = 0; k < this.count; k = k + 1) { arr.push(vec3(k, 0, 0)); }",
            ).joinToString("\n"),
            sb.build(),
        )
    }

    @Test
    fun funcsDslEmitsFunctionDefinition() {
        val sb = FuncsScope()
        sb.func("f", listOf("n")) { return_(v("n") + 1) }

        assertEquals(
            "func f(n) {\n  return n + 1;\n}",
            sb.build(),
        )
    }

    @Test
    fun componentAndIndexAssignment() {
        val sb = ProcessScope()
        sb.apply {
            assign(v("arr").index(0), num(5))
            assign(position.x, num(2))
            assign(color.r, num(1))
        }

        assertEquals(
            listOf(
                "arr[0] = 5;",
                "this.position.x = 2;",
                "this.color.r = 1;",
            ).joinToString("\n"),
            sb.build(),
        )
    }

    @Test
    fun javaStyleCallsProduceSameExpression() {
        val sb = ProcessScope()
        sb.assign(
            "th",
            sb.mul(sb.mul(sb.div(sb.index, sb.count), sb.num(2)), sb.pi),
        )

        assertEquals("th = this.index / this.count * 2 * pi;", sb.build())
    }

    @Test
    fun precedenceKeepsNestedGrouping() {
        val sb = ProcessScope()
        sb.apply {
            assign("x", v("a").plus(v("b")).times(v("c")))
            assign("y", v("a").minus(v("b").minus(v("c"))))
        }

        assertEquals(
            listOf(
                "x = (a + b) * c;",
                "y = a - (b - c);",
            ).joinToString("\n"),
            sb.build(),
        )
    }

    @Test
    fun controlFlowEmitsIndentedBlocks() {
        val sb = ProcessScope()
        sb.apply {
            if_(index.mod(2).eq(0)) {
                position = vec3(1, 0, 0)
            }
            while_(index.lt(10)) {
                position = vec3(0, 0, 0)
                breakStmt()
            }
            for_("k = 0", v("k").lt(count), "k = k + 1") {
                continueStmt()
            }
        }

        assertEquals(
            listOf(
                "if (this.index % 2 == 0) {",
                "  this.position = vec3(1, 0, 0);",
                "}",
                "while (this.index < 10) {",
                "  this.position = vec3(0, 0, 0);",
                "  break;",
                "}",
                "for (k = 0; k < this.count; k = k + 1) {",
                "  continue;",
                "}",
            ).joinToString("\n"),
            sb.build(),
        )
    }

    @Test
    fun ifElseAndJavaBlockEntry() {
        val sb = ProcessScope()
        sb.ifBlock(
            sb.eq(sb.index.mod(sb.num(2)), sb.num(0)),
            { b -> b.assign(b.position, b.vec3(1, 0, 0)) },
            { b -> b.assign(b.position, b.vec3(0, 1, 0)) },
        )

        assertEquals(
            listOf(
                "if (this.index % 2 == 0) {",
                "  this.position = vec3(1, 0, 0);",
                "} else {",
                "  this.position = vec3(0, 1, 0);",
                "}",
            ).joinToString("\n"),
            sb.build(),
        )
    }
}