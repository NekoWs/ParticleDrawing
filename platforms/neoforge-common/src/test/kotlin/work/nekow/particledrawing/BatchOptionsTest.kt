package work.nekow.particledrawing

import work.nekow.particledrawing.command.batch.BatchKind
import work.nekow.particledrawing.command.batch.BatchKeys
import work.nekow.particledrawing.command.batch.BatchOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** 批量命令的分词与值解析：空格位置、引号内逗号、动态键前缀都靠这些行为。 */
class BatchOptionsTest {

    @Test
    fun parsesKeyValuePairsInOrder() {
        val tokens = BatchOptions.parse("kind=emitter rate=4 appearance.color=#ff4040 mode=start")
        assertEquals(listOf("kind", "rate", "appearance.color", "mode"), tokens.map { it.key })
        assertEquals(listOf("emitter", "4", "#ff4040", "start"), tokens.map { it.value })
    }

    @Test
    fun keepsSpacesInPositionValue() {
        val tokens = BatchOptions.parse("anchor=fixed anchor.pos=~ ~4 ~ sample.count=1")
        assertEquals(3, tokens.size)
        assertEquals("~ ~4 ~", tokens[1].value)
        val coords = BatchOptions.position(tokens[1].value, tokens[1].key)
        assertEquals(3, coords.size)
        assertTrue(coords[0] is work.nekow.particledrawing.command.batch.BatchCoord.Relative)
        assertEquals(4.0, (coords[1] as work.nekow.particledrawing.command.batch.BatchCoord.Relative).value)
    }

    @Test
    fun keepsQuotedFormulaWithSpaces() {
        val tokens = BatchOptions.parse("motion.vx=\"x * 2 + 1\" appearance.color=#ffffff")
        assertEquals("motion.vx", tokens[0].key)
        assertEquals("\"x * 2 + 1\"", tokens[0].value)
        assertEquals("x * 2 + 1", BatchOptions.formula(tokens[0].value))
    }

    @Test
    fun nestedQuotesDoNotSplitOuterCommas() {
        val tokens = BatchOptions.parse("morph.slots=4,\"vec3(i,0,0)\" morph.ticks=10")
        assertEquals("4,\"vec3(i,0,0)\"", tokens[0].value)
        val parts = BatchOptions.splitList(tokens[0].value)
        assertEquals(listOf("4", "\"vec3(i,0,0)\""), parts)
        assertEquals("vec3(i,0,0)", BatchOptions.formula(parts[1]))
    }

    @Test
    fun listValuesAllowSpacesAfterComma() {
        val tokens = BatchOptions.parse("sample.bounds=0, 0, 0, 2, 2, 2")
        val parts = BatchOptions.splitList(tokens[0].value)
        assertEquals(listOf("0", "0", "0", "2", "2", "2"), parts)
    }

    @Test
    fun rejectsUnclosedQuote() {
        assertFailsWith<IllegalArgumentException> { BatchOptions.parse("motion.vx=\"unclosed") }
    }

    @Test
    fun rejectsTextBeforeKey() {
        assertFailsWith<IllegalArgumentException> { BatchOptions.parse("garbage kind=emitter") }
    }

    @Test
    fun dynamicNamesKeepCase() {
        val tokens = BatchOptions.parse("let.ANGLE=\"u*PI\" program.3=delay,20")
        assertEquals("let.ANGLE", tokens[0].key)
        assertEquals("ANGLE", tokens[0].key.substringAfter('.'))
        assertEquals("program.3", tokens[1].key)
    }
}

/** 键注册表：补全、help 和校验共用它，必须覆盖三种 kind 且不重复。 */
class BatchKeysTest {

    @Test
    fun namesAreUnique() {
        val names = BatchKeys.all.map { it.name }
        assertEquals(names.size, names.distinct().size)
    }

    @Test
    fun appearanceKeysMatchCommandOptions() {
        val appearance = BatchKeys.all.filter { it.name.startsWith("appearance.") }.map { it.name.removePrefix("appearance.") }
        assertEquals(work.nekow.particledrawing.command.ParticleCommandOptionParser.appearanceKeys.sorted(), appearance.sorted())
    }

    @Test
    fun scopesAllowExpectedKeys() {
        BatchKeys.require("kind", BatchKind.EMITTER)
        BatchKeys.require("keep", BatchKind.EMITTER)
        BatchKeys.require("mode", BatchKind.EMITTER)
        BatchKeys.require("sample.count", BatchKind.EMITTER)
        BatchKeys.require("appearance.color", BatchKind.SHAPE)
        BatchKeys.require("center", BatchKind.SHAPE)
        BatchKeys.require("program.7", BatchKind.PROGRAM)
        BatchKeys.require("morph.slots", BatchKind.PROGRAM)
        assertFailsWith<IllegalArgumentException> { BatchKeys.require("mode", BatchKind.PROGRAM) }
        assertFailsWith<IllegalArgumentException> { BatchKeys.require("center", BatchKind.EMITTER) }
    }

    @Test
    fun shapesHaveRequiredKeys() {
        for (shape in BatchKind.SHAPES) {
            assertTrue(BatchKeys.shapeRequired(shape).isNotEmpty(), shape)
        }
        assertEquals(listOf("center", "radius", "count"), BatchKeys.shapeRequired("circle"))
    }

    @Test
    fun programActionsAreDocumented() {
        assertTrue(BatchKeys.actionArgs.keys.containsAll(BatchKeys.actionNames))
        assertEquals(BatchKeys.actionNames.size, BatchKeys.actionNames.distinct().size)
    }
}
