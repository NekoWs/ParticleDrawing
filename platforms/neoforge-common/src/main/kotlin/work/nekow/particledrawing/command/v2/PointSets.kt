package work.nekow.particledrawing.command.v2

import net.minecraft.commands.CommandSourceStack
import work.nekow.particledrawing.api.PointSets
import work.nekow.particledrawing.api.PointSourceContext
import work.nekow.particledrawing.i18n.component
import work.nekow.particledrawing.i18n.reply
import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.i18n.requireNotNullLocalized
import work.nekow.particledrawing.i18n.translation
import work.nekow.particledrawing.math.Vec3
import work.nekow.particledrawing.platform.toCore
import work.nekow.particledrawing.pointset.PointAxis
import work.nekow.particledrawing.pointset.PointSet
import work.nekow.particledrawing.pointset.PointTransform
import work.nekow.particledrawing.util.ParticleUtils
import java.util.UUID

/**
 * `/pdraw particle pointset`：定义、组合与删除命名点集。
 *
 * 变换、合并、筛选、排序都在核心 `PointSet` 上完成，注册表在 API `PointSets`；
 * 这里只解析语法与写回结果。所有变换都写进 `as <名称>` 指定的点集，源点集不变（写成同名即原地更新）。
 */
internal object PointSetCommands {
    fun parse(input: CommandInput, source: CommandSourceStack): () -> Int {
        val dimension = ParticleUtils.dimensionUUID(source.level)
        return when (input.choice(listOf("define", "list", "inspect", "delete", "copy", "transform", "combine", "filter", "sort", "shuffle"))) {
            "define" -> define(input, source, dimension)
            "inspect" -> inspect(input, source)
            "delete" -> delete(input, source, dimension)
            "copy" -> copy(input, source, dimension)
            "transform" -> transform(input, source, dimension)
            "combine" -> combine(input, source, dimension)
            "filter" -> filter(input, source, dimension)
            "sort" -> sort(input, source, dimension)
            "shuffle" -> shuffle(input, source, dimension)
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
            write(source, name, target.toSource(source).definitionPoints(context, fallback), target.label(), dryrun)
        }
    }

    private fun copy(input: CommandInput, source: CommandSourceStack, dimension: UUID): () -> Int {
        val from = input.name(PointSets.names(dimension))
        input.expect("as")
        val to = input.name(PointSets.names(dimension))
        val dryrun = input.end()
        return {
            val entry = PointSets.get(source.level, from)
            write(source, to, entry.points, "copy $from", dryrun)
        }
    }

    private fun transform(input: CommandInput, source: CommandSourceStack, dimension: UUID): () -> Int {
        val from = input.name(PointSets.names(dimension))
        val transforms = parseTransforms(input).also {
            requireLocalized(it.isNotEmpty()) { translation("error.pointset.transform") }
        }
        input.expect("as")
        val to = input.name(PointSets.names(dimension))
        val dryrun = input.end()
        return {
            val entry = PointSets.get(source.level, from)
            write(source, to, entry.points.transformed(transforms), "transform $from", dryrun)
        }
    }

    /** 变换序列：`translate` / `rotate` / `scale` / `mirror` 可任意重复，顺序即应用顺序。 */
    internal fun parseTransforms(input: CommandInput): List<PointTransform> = buildList {
        while (true) {
            when (input.peek()) {
                "translate" -> {
                    input.take("translate")
                    add(PointTransform.Translate(input.vector("translate").toCore()))
                }
                "rotate" -> {
                    input.take("rotate")
                    add(PointTransform.Rotate(axis(input), input.number("degrees")))
                }
                "scale" -> {
                    input.take("scale")
                    add(PointTransform.Scale(input.number("factor")))
                }
                "mirror" -> {
                    input.take("mirror")
                    add(PointTransform.Mirror(axis(input)))
                }
                else -> return@buildList
            }
        }
    }

    private fun axis(input: CommandInput): PointAxis =
        PointAxis.valueOf(input.choice(listOf("x", "y", "z")).uppercase())

    private fun combine(input: CommandInput, source: CommandSourceStack, dimension: UUID): () -> Int {
        val first = input.name(PointSets.names(dimension))
        val mode = input.choice(listOf("merge", "sequence"))
        val second = input.name(PointSets.names(dimension))
        input.expect("as")
        val to = input.name(PointSets.names(dimension))
        val dryrun = input.end()
        return {
            val a = PointSets.get(source.level, first)
            val b = PointSets.get(source.level, second)
            write(source, to, a.points.concatenated(b.points), "$mode $first $second", dryrun)
        }
    }

    private fun filter(input: CommandInput, source: CommandSourceStack, dimension: UUID): () -> Int {
        val from = input.name(PointSets.names(dimension))
        val formula = input.formula("filter")
        input.expect("as")
        val to = input.name(PointSets.names(dimension))
        val dryrun = input.end()
        return {
            val entry = PointSets.get(source.level, from)
            write(source, to, entry.points.filteredByFormula(formula), "filter $from", dryrun)
        }
    }

    private fun sort(input: CommandInput, source: CommandSourceStack, dimension: UUID): () -> Int {
        val from = input.name(PointSets.names(dimension))
        val key = input.choice(listOf("x", "y", "z", "index"))
        input.expect("as")
        val to = input.name(PointSets.names(dimension))
        val dryrun = input.end()
        return {
            val entry = PointSets.get(source.level, from)
            val sorted = when (key) {
                "x" -> entry.points.sortedBy { _, point -> point.x }
                "y" -> entry.points.sortedBy { _, point -> point.y }
                "z" -> entry.points.sortedBy { _, point -> point.z }
                else -> entry.points
            }
            write(source, to, sorted, "sort $from $key", dryrun)
        }
    }

    private fun shuffle(input: CommandInput, source: CommandSourceStack, dimension: UUID): () -> Int {
        val from = input.name(PointSets.names(dimension))
        val seed = if (input.take("seed")) input.integer("seed") else 0
        input.expect("as")
        val to = input.name(PointSets.names(dimension))
        val dryrun = input.end()
        return {
            val entry = PointSets.get(source.level, from)
            write(source, to, entry.points.shuffled(seed.toLong()), "shuffle $from", dryrun)
        }
    }

    private fun write(source: CommandSourceStack, name: String, points: PointSet, label: String, dryrun: Boolean): Int {
        PointSets.checkCapacity(source.level, name)
        requireLocalized(!points.isEmpty) { translation("error.pointset.empty") }
        if (!dryrun) PointSets.define(source.level, name, points, label)
        EmitterCommands.success(source, name, if (dryrun) "dryrun" else "define")
        return 1
    }

    private fun inspect(input: CommandInput, source: CommandSourceStack): () -> Int {
        val name = input.name(PointSets.names(ParticleUtils.dimensionUUID(source.level)))
        input.finish()
        return {
            val entry = PointSets.get(source.level, name)
            source.reply({
                translation("command.v2.pointset", name, entry.source, entry.points.size, entry.revision).component()
            })
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
