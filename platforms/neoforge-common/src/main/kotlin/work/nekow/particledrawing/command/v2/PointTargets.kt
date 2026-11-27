package work.nekow.particledrawing.command.v2

import net.minecraft.commands.CommandSourceStack
import work.nekow.particledrawing.api.ParticleImageSampler
import work.nekow.particledrawing.api.PointSource
import work.nekow.particledrawing.api.SampleAsset
import work.nekow.particledrawing.command.CommandParticleGroups
import work.nekow.particledrawing.command.CommandSuggestionCatalog
import work.nekow.particledrawing.i18n.requireNotNullLocalized
import work.nekow.particledrawing.i18n.translation
import work.nekow.particledrawing.math.ResourceId
import work.nekow.particledrawing.pointset.PointSet
import work.nekow.particledrawing.util.ParticleUtils
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * 命令里的点源写法。
 *
 * 这里只保存语法（名字、路径、公式），绑定到具体维度/组时才转成可求值的 [PointSource]；
 * 真正的采样、组快照与空间换算都在 API 层。形变目标与点集定义共用这一套写法。
 */
internal sealed class PointTarget {
    abstract val count: Int?

    data class Formula(override val count: Int?, val position: String) : PointTarget()
    data class Text(val text: String, override val count: Int?, val height: Double) : PointTarget()
    data class Image(val asset: SampleAsset, val options: ParticleImageSampler.Options) : PointTarget() {
        override val count: Int? get() = null
    }
    data class Mesh(val asset: SampleAsset, override val count: Int?, val seed: Int) : PointTarget()
    data class Group(val name: String) : PointTarget() {
        override val count: Int? get() = null
    }
    data class NamedSet(val name: String) : PointTarget() {
        override val count: Int? get() = null
    }

    /** 绑定到当前维度，转成 API 点源；组名在这里解析成组 id。 */
    fun toSource(source: CommandSourceStack): PointSource = when (this) {
        is Formula -> PointSource.Formula(position, count ?: 1)
        is Text -> PointSource.Text(text, count, height)
        is Image -> PointSource.Image(asset, options)
        is Mesh -> PointSource.Mesh(asset, count, seed)
        is Group -> {
            val dimension = ParticleUtils.dimensionUUID(source.level)
            val group = requireNotNullLocalized(CommandParticleGroups.get(dimension, name)) {
                translation("error.group.not_found", name)
            }.group
            PointSource.GroupRef(dimension, group.id, name)
        }
        is NamedSet -> PointSource.NamedSet(ParticleUtils.dimensionUUID(source.level), name)
    }

    /** inspect、dryrun 与反馈用的短描述。 */
    fun label(): String = when (this) {
        is Formula -> "formula"
        is Text -> buildString {
            append("text \"").append(text.take(24))
            if (text.length > 24) append('…')
            append('"')
        }
        is Image -> "image ${asset.path}"
        is Mesh -> "mesh ${asset.path}"
        is Group -> "group $name"
        is NamedSet -> "pointset $name"
    }
}

/** 点源语法的解析：只做语法与补全，取值交给 [PointTarget.toSource]。 */
internal object PointTargets {
    val kinds = listOf("formula", "text", "image", "mesh", "group", "pointset")

    /** 点集定义里数量省略时的默认值，与发射器采样的默认一致。 */
    const val TEXT_DEFAULT_COUNT = 1024
    const val MESH_DEFAULT_COUNT = 256

    private const val MAX_POINTS = PointSet.MAX_POINTS
    private const val DEFAULT_TEXT_HEIGHT = 2.0
    private const val MIN_TEXT_HEIGHT = 0.001
    private const val MAX_TEXT_HEIGHT = 1_000_000.0

    fun parse(input: CommandInput, allowAll: Boolean): PointTarget {
        val dimension = input.suggestionSource?.let { ParticleUtils.dimensionUUID(it.level) }
        return when (val kind = input.choice(kinds)) {
            "formula" -> PointTarget.Formula(count(input, kind, allowAll), input.formula("position"))
            "text" -> {
                val text = input.value("text")
                if (text.isBlank()) input.invalid("nonempty text")
                val height = if (input.take("size")) input.number("size") else DEFAULT_TEXT_HEIGHT
                if (height !in MIN_TEXT_HEIGHT..MAX_TEXT_HEIGHT) {
                    input.invalid("size ($MIN_TEXT_HEIGHT..$MAX_TEXT_HEIGHT)", height.toString())
                }
                val count = if (input.take("count")) input.integer("count", 1, MAX_POINTS) else null
                PointTarget.Text(text, count, height)
            }
            "image" -> {
                val asset = asset(input, "particleImages", ".png")
                val spacing = if (input.take("pixel_spacing")) input.number("pixel_spacing") else 0.05
                val alpha = if (input.take("alpha")) input.number("alpha") else 0.0
                if (spacing <= 0.0 || alpha !in 0.0..1.0) input.invalid("pixel spacing and alpha")
                val stride = if (input.take("stride")) input.integer("stride", 1, MAX_POINTS) else 1
                val plane = if (input.take("plane")) input.choice(listOf("xy", "xz", "yz")).uppercase() else "XY"
                PointTarget.Image(asset, ParticleImageSampler.Options(stride, spacing, alpha, ParticleImageSampler.Plane.valueOf(plane)))
            }
            "mesh" -> {
                val asset = asset(input, "particleMeshes", ".obj")
                val count = if (input.take("count")) input.integer("count", 1, MAX_POINTS) else null
                val seed = if (input.take("seed")) input.integer("seed") else 0
                PointTarget.Mesh(asset, count, seed)
            }
            "group" -> PointTarget.Group(input.name(dimension?.let { CommandParticleGroups.names(it) }.orEmpty()))
            else -> PointTarget.NamedSet(input.name(dimension?.let { work.nekow.particledrawing.api.PointSets.names(it) }.orEmpty()))
        }
    }

    private fun count(input: CommandInput, label: String, allowAll: Boolean): Int? {
        if (allowAll) {
            input.suggest(listOf("all"))
            if (input.take("all")) return null
        }
        return input.integer(label, 1, MAX_POINTS)
    }

    /** 素材路径：`file <相对路径>` 读工作目录，`resource <标识>` 读资源包；与发射器采样共用。 */
    fun asset(input: CommandInput, directory: String, suffix: String): SampleAsset {
        val resource = input.choice(listOf("file", "resource")) == "resource"
        input.valueSuggestions {
            if (resource) {
                val directories = if (suffix == ".png") listOf("particle_images", "images", "textures")
                else listOf("particle_meshes", "meshes", "models")
                input.suggestionSource?.level?.server?.resourceManager?.let {
                    CommandSuggestionCatalog.resources(it, directories, suffix)
                }.orEmpty()
            } else {
                localFiles(Path.of(directory), suffix)
            }
        }
        val path = input.value("path")
        if (resource) ResourceId.parse(path)
        return SampleAsset(path, resource)
    }

    private fun localFiles(root: Path, suffix: String): List<String> = runCatching {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return@runCatching emptyList()
        Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) && it.toString().endsWith(suffix, ignoreCase = true) }
                .map { root.relativize(it).joinToString("/") }.sorted().toList()
        }
    }.getOrDefault(emptyList())
}
