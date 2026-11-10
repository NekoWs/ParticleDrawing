package work.nekow.particledrawing.command

import com.mojang.brigadier.StringReader
import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import net.minecraft.world.phys.Vec3

/** 完整保留贴图引用、资源路径和逗号坐标；使用原版可同步的字符串参数类型。 */
internal object ParticleCommandArguments {
    fun textType(): ArgumentType<String> = StringArgumentType.greedyString()

    fun text(raw: String): String {
        val value = raw.trim()
        require(value.isNotEmpty()) { "参数不能为空" }
        if (!value.startsWith('"')) return value
        val reader = StringReader(value)
        val result = reader.readString()
        reader.skipWhitespace()
        require(!reader.canRead()) { "引号后有多余内容" }
        return result
    }

    fun bounds(raw: String): Pair<Vec3, Vec3> {
        val parts = raw.trim().split(Regex("\\s+"))
        require(parts.size == 2) { "bounds 格式为 x,y,z x,y,z" }
        return vector(parts[0]) to vector(parts[1])
    }

    private fun vector(raw: String): Vec3 {
        val n = raw.split(',').map { it.toDoubleOrNull() ?: throw IllegalArgumentException("坐标格式为 x,y,z") }
        require(n.size == 3 && n.all(Double::isFinite)) { "坐标格式为 x,y,z，分量必须有限" }
        return Vec3(n[0], n[1], n[2])
    }
}
