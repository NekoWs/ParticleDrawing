package work.nekow.particledrawing.command

import com.mojang.brigadier.StringReader
import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.i18n.LocalizedArgumentException
import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.i18n.translation

/** 完整保留贴图引用、资源路径和逗号坐标；使用原版可同步的字符串参数类型。 */
internal object ParticleCommandArguments {
    fun textType(): ArgumentType<String> = StringArgumentType.greedyString()

    fun text(raw: String): String {
        val value = raw.trim()
        requireLocalized(value.isNotEmpty()) { translation("error.command.argument_empty") }
        if (!value.startsWith('"')) return value
        val reader = StringReader(value)
        val result = reader.readString()
        reader.skipWhitespace()
        requireLocalized(!reader.canRead()) { translation("error.command.trailing_quote") }
        return result
    }

    fun bounds(raw: String): Pair<Vec3, Vec3> {
        val parts = raw.trim().split(Regex("\\s+"))
        requireLocalized(parts.size == 2) { translation("error.command.bounds_format") }
        return vector(parts[0]) to vector(parts[1])
    }

    fun vector(raw: String): Vec3 {
        val n = raw.split(',').map { it.toDoubleOrNull() ?: throw LocalizedArgumentException(translation("error.command.coordinate_format")) }
        requireLocalized(n.size == 3 && n.all(Double::isFinite)) { translation("error.command.coordinate_format") }
        return Vec3(n[0], n[1], n[2])
    }
}
