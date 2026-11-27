package work.nekow.particledrawing.command.v2

import net.minecraft.commands.CommandSourceStack
import work.nekow.particledrawing.api.PointSets
import work.nekow.particledrawing.api.PointSourceContext
import work.nekow.particledrawing.i18n.component
import work.nekow.particledrawing.i18n.reply
import work.nekow.particledrawing.i18n.requireNotNullLocalized
import work.nekow.particledrawing.i18n.translation
import work.nekow.particledrawing.util.ParticleUtils
import java.util.UUID

/** `/pdraw particle pointset`：定义、查看与删除命名点集；注册表与求值都在 API 层。 */
internal object PointSetCommands {
    fun parse(input: CommandInput, source: CommandSourceStack): () -> Int {
        val dimension = ParticleUtils.dimensionUUID(source.level)
        return when (input.choice(listOf("define", "list", "inspect", "delete"))) {
            "define" -> define(input, source, dimension)
            "inspect" -> inspect(input, source)
            "delete" -> delete(input, source, dimension)
            else -> list(source, dimension)
        }
    }

    private fun define(input: CommandInput, source: CommandSourceStack, dimension: UUID): () -> Int {
        val name = input.name(PointSets.names(dimension))
        val target = PointTargets.parse(input, allowAll = false)
        val dryrun = input.end()
        return {
            PointSets.checkCapacity(source.level, name)
            val context = PointSourceContext(source.level)
            val fallback = when (target) {
                is PointTarget.Text -> PointTargets.TEXT_DEFAULT_COUNT
                is PointTarget.Mesh -> PointTargets.MESH_DEFAULT_COUNT
                is PointTarget.Formula -> requireNotNullLocalized(target.count) { translation("error.pointset.count") }
                else -> 1
            }
            val points = target.toSource(source).definitionPoints(context, fallback)
            requireNotNullLocalized(points.takeIf { !it.isEmpty }) { translation("error.pointset.empty") }
            if (!dryrun) PointSets.define(source.level, name, points, target.label())
            EmitterCommands.success(source, name, if (dryrun) "dryrun" else "define")
        }
    }

    private fun inspect(input: CommandInput, source: CommandSourceStack): () -> Int {
        val name = input.name(PointSets.names(ParticleUtils.dimensionUUID(source.level)))
        input.finish()
        return {
            val entry = PointSets.get(source.level, name)
            source.reply({ translation("command.v2.pointset", name, entry.source, entry.points.size).component() })
            1
        }
    }

    private fun delete(input: CommandInput, source: CommandSourceStack, dimension: UUID): () -> Int {
        val pattern = input.namePattern(PointSets.names(dimension))
        input.finish()
        return {
            val names = pattern.select(PointSets.names(dimension))
            PointSets.remove(source.level, names)
            EmitterCommands.success(source, pattern, "delete", names.size)
        }
    }

    private fun list(source: CommandSourceStack, dimension: UUID): () -> Int {
        return { EmitterCommands.success(source, PointSets.names(dimension).joinToString(", "), "list") }
    }
}
