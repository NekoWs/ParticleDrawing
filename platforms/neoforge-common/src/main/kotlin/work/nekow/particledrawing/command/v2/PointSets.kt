package work.nekow.particledrawing.command.v2

import net.minecraft.commands.CommandSourceStack
import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.i18n.LocalizedArgumentException
import work.nekow.particledrawing.i18n.component
import work.nekow.particledrawing.i18n.reply
import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.i18n.translation
import work.nekow.particledrawing.util.ParticleUtils
import java.util.UUID

/**
 * 维度内命名的点集：只存相对坐标，不给任何消费者绑死。
 *
 * 形变把它当目标，之后 `draw` / `emitter` 也可以把它当采样源；对象跟组一样是内存对象，
 * 关卡卸载和停服时丢弃。
 */
internal object CommandPointSets {
    data class Entry(val points: List<Vec3>, val source: String)

    private val dimensions = hashMapOf<UUID, LinkedHashMap<String, Entry>>()

    fun names(dimension: UUID): List<String> = dimensions[dimension]?.keys?.toList() ?: emptyList()

    fun find(dimension: UUID, name: String): Entry? = dimensions[dimension]?.get(name)

    fun get(dimension: UUID, name: String): Entry =
        find(dimension, name) ?: throw LocalizedArgumentException(translation("error.pointset.not_found", name))

    fun checkCapacity(dimension: UUID, name: String) {
        val entries = dimensions[dimension].orEmpty()
        requireLocalized(name in entries || entries.size < 256) { translation("error.pointset.limit", 256) }
    }

    fun install(dimension: UUID, name: String, entry: Entry) {
        dimensions.getOrPut(dimension) { linkedMapOf() }[name] = entry
    }

    fun remove(dimension: UUID, names: List<String>) {
        val entries = dimensions[dimension] ?: return
        entries.keys.removeAll(names.toSet())
        if (entries.isEmpty()) dimensions.remove(dimension)
    }

    fun discard(dimension: UUID) { dimensions.remove(dimension) }
    fun clear() { dimensions.clear() }
}

/** `/pdraw particle pointset`：定义、查看与删除命名点集。 */
internal object PointSetCommands {
    fun parse(input: CommandInput, source: CommandSourceStack): () -> Int {
        val dimension = ParticleUtils.dimensionUUID(source.level)
        return when (input.choice(listOf("define", "list", "inspect", "delete"))) {
            "define" -> define(input, source, dimension)
            "inspect" -> inspect(input, source, dimension)
            "delete" -> delete(input, source, dimension)
            else -> list(source, dimension)
        }
    }

    private fun define(input: CommandInput, source: CommandSourceStack, dimension: UUID): () -> Int {
        val name = input.name(CommandPointSets.names(dimension))
        val spec = PointSources.parse(input, allowAll = false)
        val dryrun = input.end()
        return {
            CommandPointSets.checkCapacity(dimension, name)
            val points = PointSources.sampleForPointSet(source, spec)
            requireLocalized(points.size in 1..PointSources.MAX_POINTS) {
                translation("error.pointset.size", PointSources.MAX_POINTS)
            }
            if (!dryrun) CommandPointSets.install(dimension, name, CommandPointSets.Entry(points, PointSources.label(spec)))
            EmitterCommands.success(source, name, if (dryrun) "dryrun" else "define")
        }
    }

    private fun inspect(input: CommandInput, source: CommandSourceStack, dimension: UUID): () -> Int {
        val name = input.name(CommandPointSets.names(dimension))
        input.finish()
        return {
            val entry = CommandPointSets.get(dimension, name)
            source.reply({ translation("command.v2.pointset", name, entry.source, entry.points.size).component() })
            1
        }
    }

    private fun delete(input: CommandInput, source: CommandSourceStack, dimension: UUID): () -> Int {
        val pattern = input.namePattern(CommandPointSets.names(dimension))
        input.finish()
        return {
            val names = pattern.select(CommandPointSets.names(dimension))
            CommandPointSets.remove(dimension, names)
            EmitterCommands.success(source, pattern, "delete", names.size)
        }
    }

    private fun list(source: CommandSourceStack, dimension: UUID): () -> Int {
        return { EmitterCommands.success(source, CommandPointSets.names(dimension).joinToString(", "), "list") }
    }
}
