package work.nekow.particledrawing

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder.literal
import com.mojang.brigadier.builder.RequiredArgumentBuilder.argument
import com.mojang.brigadier.arguments.StringArgumentType
import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.command.ParticleCommandArguments
import kotlin.test.*

/** 用实际 Brigadier 解析器验证 mcfunction 中容易被单词参数截断的值。 */
class ParticleCommandArgumentsTest {
    private fun parse(value: String): String {
        var result: String? = null
        val dispatcher = CommandDispatcher<Any>()
        dispatcher.register(literal<Any>("value").then(argument<Any, String>("text", ParticleCommandArguments.textType()).executes {
            result = StringArgumentType.getString(it, "text")
            1
        }))
        assertEquals(1, dispatcher.execute("value $value", Any()))
        return assertNotNull(result)
    }

    @Test fun `贴图引用完整保留命名空间帧号和帧率`() {
        for (name in listOf("particle:minecraft:end_rod", "particle:minecraft:flame@8", "particle:minecraft:end_rod#3")) {
            assertEquals(name, ParticleCommandArguments.text(parse(name)))
        }
    }

    @Test fun `两组逗号坐标作为完整参数进入校验`() {
        val (min, max) = ParticleCommandArguments.bounds(parse("-2,-2,-2 2,2,2"))
        assertEquals(Vec3(-2.0, -2.0, -2.0), min)
        assertEquals(Vec3(2.0, 2.0, 2.0), max)
    }

    @Test fun `数据包资源和含空格文件名都可读取`() {
        assertEquals("demo:images/ring.png", ParticleCommandArguments.text(parse("demo:images/ring.png")))
        assertEquals("magic ring.png", ParticleCommandArguments.text(parse("\"magic ring.png\"")))
        assertEquals("magic ring.png", ParticleCommandArguments.text(parse("magic ring.png")))
    }

    @Test fun `坏坐标和引号后的多余参数明确拒绝`() {
        for (value in listOf("-2,-2,-2", "0,0,0 1,1,1 2,2,2", "0,0,0 NaN,1,1", "0,0 1,1,1")) {
            assertFailsWith<IllegalArgumentException> { ParticleCommandArguments.bounds(parse(value)) }
        }
        assertFailsWith<IllegalArgumentException> { ParticleCommandArguments.text(parse("\"ring.png\" extra")) }
    }
}
