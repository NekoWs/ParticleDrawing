package work.nekow.particledrawing

import com.mojang.brigadier.exceptions.CommandSyntaxException
import work.nekow.particledrawing.command.v2.CommandInput
import work.nekow.particledrawing.command.v2.ProgramCommands
import work.nekow.particledrawing.command.v2.ProgramTemplates
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** GEN-3 程序模板与有界控制流的语法层：节点解析、参数替换与模板注册表。 */
class ProgramCompositionTest {
    private val dimension = UUID.fromString("00000000-0000-0000-0000-000000000003")

    @Test
    fun `if 两支与 end 解析成节点`() {
        val input = CommandInput("then if n > 2 then delay 1 else then delay 3 end")
        val nodes = ProgramCommands.chain(input)
        input.finish()
        assertEquals(1, nodes.size)
        val node = nodes.single() as ProgramCommands.Node.If
        assertEquals("n > 2", node.condition)
        assertTrue(node.thenRaw.startsWith("then delay 1"))
        assertTrue(node.elseRaw!!.startsWith("then delay 3"))
    }

    @Test
    fun `repeat 与调用参数解析成节点`() {
        val input = CommandInput("then repeat 3 then delay 2 end then call wave rx 4")
        val nodes = ProgramCommands.chain(input)
        input.finish()
        val repeat = nodes[0] as ProgramCommands.Node.Repeat
        assertEquals(3, repeat.times)
        assertTrue(repeat.bodyRaw.startsWith("then delay 2"))
        val call = nodes[1] as ProgramCommands.Node.Invoke
        assertEquals("wave", call.name)
        assertEquals(mapOf("rx" to "4"), call.arguments)
    }

    @Test
    fun `控制流上界与括号完整性在解析时报错`() {
        assertFailsWith<CommandSyntaxException> {
            ProgramCommands.chain(CommandInput("then repeat 0 then delay 1 end"))
        }
        assertFailsWith<CommandSyntaxException> {
            ProgramCommands.chain(CommandInput("then repeat 257 then delay 1 end"))
        }
        assertFailsWith<CommandSyntaxException> {
            ProgramCommands.chain(CommandInput("then if n > 1 then delay 1"))
        }
    }

    @Test
    fun `参数替换只替换已知占位符`() {
        val text = "then delay {rx} then morph target text \"{word}\" ticks {tx}"
        assertEquals(
            "then delay 2 then morph target text \"WAVE\" ticks {tx}",
            ProgramCommands.substitute(text, mapOf("rx" to "2", "word" to "WAVE")),
        )
    }

    @Test
    fun `模板语法用缺省参数校验`() {
        assertTrue(ProgramCommands.parseTemplateBody("then delay 2 then call other").isNotEmpty())
        assertFailsWith<CommandSyntaxException> { ProgramCommands.parseTemplateBody("delay 2") }
    }

    @Test
    fun `模板按维度隔离、可覆盖与通配删除`() {
        ProgramTemplates.clear()
        ProgramTemplates.define(dimension, "wave", mapOf("rx" to "5"), "then delay {rx}")
        assertEquals(listOf("wave"), ProgramTemplates.names(dimension))
        assertEquals("5", ProgramTemplates.find(dimension, "wave")?.parameters?.get("rx"))
        ProgramTemplates.define(dimension, "wave", mapOf("rx" to "6"), "then delay {rx}")
        assertEquals("6", ProgramTemplates.find(dimension, "wave")?.parameters?.get("rx"))
        val other = UUID.fromString("00000000-0000-0000-0000-000000000004")
        assertTrue(ProgramTemplates.names(other).isEmpty())
        assertFailsWith<IllegalArgumentException> { ProgramTemplates.get(other, "wave") }
        ProgramTemplates.remove(dimension, listOf("wave"))
        assertTrue(ProgramTemplates.names(dimension).isEmpty())
        ProgramTemplates.clear()
    }
}
