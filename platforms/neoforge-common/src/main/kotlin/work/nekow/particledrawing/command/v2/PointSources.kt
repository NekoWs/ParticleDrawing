package work.nekow.particledrawing.command.v2

import net.minecraft.commands.CommandSourceStack
import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.animation.morph.MorphSlotFormulas
import work.nekow.particledrawing.api.ParticleImageSampler
import work.nekow.particledrawing.api.ParticleMeshSampler
import work.nekow.particledrawing.api.ParticleRecipe
import work.nekow.particledrawing.api.ParticleTextSampler
import work.nekow.particledrawing.command.CommandParticleGroups
import work.nekow.particledrawing.command.CommandParticleRecipes
import work.nekow.particledrawing.command.CommandSuggestionCatalog
import work.nekow.particledrawing.core.server.ServerParticleEngine
import work.nekow.particledrawing.i18n.LocalizedArgumentException
import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.i18n.requireNotNullLocalized
import work.nekow.particledrawing.i18n.translation
import work.nekow.particledrawing.math.ResourceId
import work.nekow.particledrawing.platform.toMinecraft
import work.nekow.particledrawing.util.ParticleUtils
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.UUID

/**
 * 命令层的通用点源：形变目标与命名点集共用同一套“点从哪来”的写法。
 *
 * 采样类（文字/图片/模型）给出以原点为中心的形状；[GroupRef] 取目标组当前世界坐标的快照；
 * [PointSetRef] 取已定义的点集（点集自身就是相对坐标）；[Formula] 交给形变槽位原样处理。
 */
internal sealed class PointSource {
    /** 显式写出的点数；null 表示跟随使用方的默认值（形变取成员数，点集取各自的默认数）。 */
    abstract val count: Int?

    /** 采样/公式源可以在准备阶段一次求值；引用型源等到真正使用时再读。 */
    val standalone: Boolean get() = this is Formula || this is Text || this is Image || this is Mesh

    data class Formula(override val count: Int?, val position: String) : PointSource()
    data class Text(val text: String, override val count: Int?, val height: Double) : PointSource()
    data class Image(val asset: CommandParticleRecipes.SampleAsset, val options: ParticleImageSampler.Options) : PointSource() {
        override val count: Int? get() = null
    }
    data class Mesh(val asset: CommandParticleRecipes.SampleAsset, override val count: Int?, val seed: Int) : PointSource()
    data class GroupRef(val name: String) : PointSource() {
        override val count: Int? get() = null
    }
    data class PointSetRef(val name: String) : PointSource() {
        override val count: Int? get() = null
    }
}

internal object PointSources {
    val kinds = listOf("formula", "text", "image", "mesh", "group", "pointset")

    const val MAX_POINTS = 8192
    const val DEFAULT_TEXT_COUNT = 1024
    const val DEFAULT_MESH_COUNT = 256
    private const val DEFAULT_TEXT_HEIGHT = 2.0
    private const val MIN_TEXT_HEIGHT = 0.001
    private const val MAX_TEXT_HEIGHT = 1_000_000.0

    /** 解析一段点源；[allowAll] 为真时数量可以写 `all`（跟着形变组的成员数走）。 */
    fun parse(input: CommandInput, allowAll: Boolean): PointSource {
        val dimension = input.suggestionSource?.let { ParticleUtils.dimensionUUID(it.level) }
        return when (val kind = input.choice(kinds)) {
            "formula" -> PointSource.Formula(count(input, kind, allowAll), input.formula("position"))
            "text" -> {
                val text = input.value("text")
                if (text.isBlank()) input.invalid("nonempty text")
                val height = if (input.take("size")) input.number("size") else DEFAULT_TEXT_HEIGHT
                if (height !in MIN_TEXT_HEIGHT..MAX_TEXT_HEIGHT) {
                    input.invalid("size ($MIN_TEXT_HEIGHT..$MAX_TEXT_HEIGHT)", height.toString())
                }
                val count = if (input.take("count")) input.integer("count", 1, MAX_POINTS) else null
                PointSource.Text(text, count, height)
            }
            "image" -> {
                val asset = asset(input, "particleImages", ".png")
                val spacing = if (input.take("pixel_spacing")) input.number("pixel_spacing") else 0.05
                val alpha = if (input.take("alpha")) input.number("alpha") else 0.0
                if (spacing <= 0.0 || alpha !in 0.0..1.0) input.invalid("pixel spacing and alpha")
                val stride = if (input.take("stride")) input.integer("stride", 1, MAX_POINTS) else 1
                val plane = if (input.take("plane")) input.choice(listOf("xy", "xz", "yz")).uppercase() else "XY"
                PointSource.Image(asset, ParticleImageSampler.Options(stride, spacing, alpha, ParticleImageSampler.Plane.valueOf(plane)))
            }
            "mesh" -> {
                val asset = asset(input, "particleMeshes", ".obj")
                val count = if (input.take("count")) input.integer("count", 1, MAX_POINTS) else null
                val seed = if (input.take("seed")) input.integer("seed") else 0
                PointSource.Mesh(asset, count, seed)
            }
            "group" -> {
                val names = dimension?.let { CommandParticleGroups.names(it) }.orEmpty()
                PointSource.GroupRef(input.name(names))
            }
            else -> {
                val names = dimension?.let { CommandPointSets.names(it) }.orEmpty()
                PointSource.PointSetRef(input.name(names))
            }
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
    fun asset(input: CommandInput, directory: String, suffix: String): CommandParticleRecipes.SampleAsset {
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
        return CommandParticleRecipes.SampleAsset(path, resource)
    }

    private fun localFiles(root: Path, suffix: String): List<String> = runCatching {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return@runCatching emptyList()
        Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) && it.toString().endsWith(suffix, ignoreCase = true) }
                .map { root.relativize(it).joinToString("/") }.sorted().toList()
        }
    }.getOrDefault(emptyList())

    /**
     * 采样/公式源求值一次得到点。
     *
     * [fallbackCount] 是数量省略时用的默认值：形变传成员数，点集按来源给自己的默认数。
     * 图片的点数由采样器决定，忽略这个值。
     */
    fun sample(source: CommandSourceStack, spec: PointSource, fallbackCount: Int): List<Vec3> {
        requireLocalized(fallbackCount in 1..MAX_POINTS || spec.count != null) {
            translation("error.morph.slot_count", MAX_POINTS)
        }
        return when (spec) {
            is PointSource.Formula -> formulaPoints(spec.position, spec.count ?: fallbackCount)
            is PointSource.Text -> ParticleTextSampler.sample(spec.text, spec.count ?: fallbackCount, spec.height)
                .map { it.toMinecraft() }
            is PointSource.Image -> spec.asset.open(source.level, "particleImages", "error.image.missing")
                .use { ParticleImageSampler.sample(it, spec.options) }.map { it.position.toMinecraft() }
            is PointSource.Mesh -> {
                val provider = spec.asset.open(source.level, "particleMeshes", "error.mesh.missing").use(ParticleMeshSampler::objProvider)
                ParticleMeshSampler.surface(provider, spec.count ?: fallbackCount, spec.seed).map { it.position.toMinecraft() }
            }
            else -> throw LocalizedArgumentException(translation("error.morph.target_kind"))
        }
    }

    /** 点集定义求值：统一转成相对坐标，之后被任何消费者放到自己的锚点上。 */
    fun sampleForPointSet(source: CommandSourceStack, spec: PointSource): List<Vec3> = when (spec) {
        is PointSource.GroupRef -> {
            val dimension = ParticleUtils.dimensionUUID(source.level)
            val group = requireNotNullLocalized(CommandParticleGroups.get(dimension, spec.name)) {
                translation("error.group.not_found", spec.name)
            }.group
            groupPoints(dimension, spec.name).map { it.subtract(group.pivot) }
        }
        is PointSource.PointSetRef -> CommandPointSets.get(ParticleUtils.dimensionUUID(source.level), spec.name).points.toList()
        is PointSource.Formula -> sample(source, spec, requireNotNullLocalized(spec.count) { translation("error.pointset.count") })
        is PointSource.Mesh -> sample(source, spec, DEFAULT_MESH_COUNT)
        else -> sample(source, spec, DEFAULT_TEXT_COUNT)
    }

    /** 引用型源在准备阶段的点数，用于时长账本；取不到时按 1 处理，真正开始时会再校验。 */
    fun referenceCount(source: CommandSourceStack, spec: PointSource): Int {
        val dimension = ParticleUtils.dimensionUUID(source.level)
        return when (spec) {
            is PointSource.GroupRef -> CommandParticleGroups.get(dimension, spec.name)?.let { groupMembers(it.group.id, dimension) } ?: 0
            is PointSource.PointSetRef -> CommandPointSets.find(dimension, spec.name)?.points?.size ?: 0
            else -> spec.count ?: 0
        }
    }

    /** 形变准备阶段的结果：采样源直接给点，引用型源只给点数，真正开始时再取点。 */
    data class Prepared(val points: List<Vec3>?, val count: Int)

    fun prepareTarget(source: CommandSourceStack, spec: PointSource, members: Int): Prepared =
        if (spec.standalone) sample(source, spec, members).let { Prepared(it, it.size) }
        else Prepared(null, referenceCount(source, spec))

    /** 引用型源在真正开始形变时取：组给世界坐标快照，点集给自身的相对坐标。 */
    fun resolveReference(dimension: UUID, spec: PointSource): List<Vec3> = when (spec) {
        is PointSource.GroupRef -> groupPoints(dimension, spec.name)
        is PointSource.PointSetRef -> CommandPointSets.get(dimension, spec.name).points.toList()
        else -> throw LocalizedArgumentException(translation("error.morph.target_kind"))
    }

    /** 组的成员世界坐标；组不存在或没有成员时报本地化错误。 */
    fun groupPoints(dimension: UUID, name: String): List<Vec3> {
        val group = requireNotNullLocalized(CommandParticleGroups.get(dimension, name)) {
            translation("error.group.not_found", name)
        }.group
        val engine = requireNotNullLocalized(ServerParticleEngine.get(dimension)) {
            translation("error.group.not_found", name)
        }
        val data = requireNotNullLocalized(engine.getGroup(group.id)) { translation("error.group.not_found", name) }
        return data.memberIds().mapNotNull { engine.getParticle(it)?.position() }
    }

    private fun groupMembers(groupId: UUID, dimension: UUID): Int {
        val engine = ServerParticleEngine.get(dimension) ?: return 0
        return (engine.getGroup(groupId)?.size() ?: 0) + engine.pendingDrawCount(groupId)
    }

    /**
     * 形变使用的最终点：按坐标系把源的点换算成世界坐标或相对锚点的偏移。
     *
     * 组给的是世界快照，局域模式减去锚点，之后锚点移动时整组跟着走；
     * 采样源与点集给的是形状，世界模式在这里落一次锚点，局域模式交给客户端逐 tick 加锚点。
     */
    fun toMorphPoints(points: List<Vec3>, spec: PointSource, local: Boolean, anchor: Vec3): List<Vec3> = when {
        spec is PointSource.GroupRef -> if (local) points.map { it.subtract(anchor) } else points
        local -> points
        else -> points.map { it.add(anchor) }
    }

    fun label(spec: PointSource): String = when (spec) {
        is PointSource.Formula -> "formula"
        is PointSource.Text -> buildString {
            append("text \"").append(spec.text.take(24))
            if (spec.text.length > 24) append('…')
            append('"')
        }
        is PointSource.Image -> "image ${spec.asset.name}"
        is PointSource.Mesh -> "mesh ${spec.asset.name}"
        is PointSource.GroupRef -> "group ${spec.name}"
        is PointSource.PointSetRef -> "pointset ${spec.name}"
    }

    private fun formulaPoints(formula: String, count: Int): List<Vec3> {
        requireLocalized(count in 1..MAX_POINTS) { translation("error.morph.slot_count", MAX_POINTS) }
        return MorphSlotFormulas(count, formula).build(0.0).map { it.position.toMinecraft() }
    }
}
