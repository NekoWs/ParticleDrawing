package work.nekow.particledrawing.api

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.animation.morph.MorphSlotFormulas
import work.nekow.particledrawing.core.server.ServerParticleEngine
import work.nekow.particledrawing.i18n.LocalizedArgumentException
import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.i18n.requireNotNullLocalized
import work.nekow.particledrawing.i18n.translation
import work.nekow.particledrawing.platform.toCore
import work.nekow.particledrawing.platform.toMinecraft
import work.nekow.particledrawing.pointset.PointSet
import work.nekow.particledrawing.util.ParticleUtils
import java.util.UUID

/** 点自身的坐标系：相对消费者锚点，或已经是世界坐标。 */
enum class PointFrame { RELATIVE, WORLD }

/** 点源求值上下文：读取文件/资源、组快照与命名点集都要用到它。 */
class PointSourceContext(val level: ServerLevel) {
    val dimensionId: UUID = ParticleUtils.dimensionUUID(level)
}

/**
 * 一处可以解析成点的来源：公式、文字、图片、模型、已有组或命名点集。
 *
 * 消费者（形变、点集定义、之后的绘制与发射器采样）只依赖这一层，命令层只负责把语法映射成实例。
 * 采样类源可以在准备阶段求值一次（[standalone]）；组与点集这类引用源等到真正使用的时候才读（[reference]）。
 * 自定义来源实现本接口即可被所有消费者接受。
 */
interface PointSource {
    /** 显式点数；null 表示跟随使用方默认（形变取组成员数，点集按来源默认）。 */
    val count: Int?

    /** 点自身的坐标系，决定消费者在 world/local 模式下如何落锚点。 */
    val frame: PointFrame

    /** 采样/公式源可在准备阶段求值；引用源为 false。 */
    val standalone: Boolean get() = true

    /** 采样类源的求值；引用型源调用时底部实现应报错。 */
    fun sample(context: PointSourceContext, fallbackCount: Int): PointSet =
        throw LocalizedArgumentException(translation("error.morph.target_kind"))

    /** 引用型源在真正使用时的求值；组给世界坐标快照，点集给自身的相对坐标。 */
    fun reference(context: PointSourceContext): PointSet =
        throw LocalizedArgumentException(translation("error.morph.target_kind"))

    /** 引用型源在准备阶段的点数，用于时长账本；取不到时返回 0，真正使用时会再校验。 */
    fun referenceCount(context: PointSourceContext): Int = 0

    /** 点集定义使用的点：组取相对自己轴心的形状，其余同消费者语义。 */
    fun definitionPoints(context: PointSourceContext, fallbackCount: Int): PointSet = sample(context, fallbackCount)

    /** 准备阶段的结果；采样源缓存点，引用源只缓存点数。 */
    fun prepare(context: PointSourceContext, members: Int): PreparedPointSource {
        if (!standalone) return PreparedPointSource(this, null, referenceCount(context))
        val points = sample(context, count ?: members)
        return PreparedPointSource(this, points, points.size)
    }

    /** inspect、dryrun 与反馈用的短描述。 */
    fun label(): String

    /** 形变槽位公式写法的点源；与旧 `slots` 的公式语义完全一致。 */
    data class Formula(val position: String, override val count: Int) : PointSource {
        override val frame: PointFrame get() = PointFrame.RELATIVE
        override fun sample(context: PointSourceContext, fallbackCount: Int): PointSet =
            PointSet.of(MorphSlotFormulas(count, position).build(0.0).map { it.position })

        override fun label(): String = "formula"
    }

    /** 服务端按字形采样的文字点源。 */
    data class Text(val text: String, override val count: Int?, val height: Double) : PointSource {
        override val frame: PointFrame get() = PointFrame.RELATIVE
        override fun sample(context: PointSourceContext, fallbackCount: Int): PointSet =
            PointSet.of(ParticleTextSampler.sample(text, count ?: fallbackCount, height))

        override fun label(): String = buildString {
            append("text \"").append(text.take(24))
            if (text.length > 24) append('…')
            append('"')
        }
    }

    /** 图片采样：点数由采样器按像素与间距决定。 */
    data class Image(val asset: SampleAsset, val options: ParticleImageSampler.Options) : PointSource {
        override val count: Int? get() = null
        override val frame: PointFrame get() = PointFrame.RELATIVE
        override fun sample(context: PointSourceContext, fallbackCount: Int): PointSet =
            PointSet.of(
                asset.open(context.level, "particleImages", "error.image.missing")
                    .use { ParticleImageSampler.sample(it, options) }.map { it.position },
            )

        override fun label(): String = "image ${asset.path}"
    }

    /** 模型表面采样。 */
    data class Mesh(val asset: SampleAsset, override val count: Int?, val seed: Int) : PointSource {
        override val frame: PointFrame get() = PointFrame.RELATIVE
        override fun sample(context: PointSourceContext, fallbackCount: Int): PointSet {
            val provider = asset.open(context.level, "particleMeshes", "error.mesh.missing").use(ParticleMeshSampler::objProvider)
            return PointSet.of(ParticleMeshSampler.surface(provider, count ?: fallbackCount, seed).map { it.position })
        }

        override fun label(): String = "mesh ${asset.path}"
    }

    /** 已有组：真正使用时快照成员的世界坐标。 */
    data class GroupRef(val dimensionId: UUID, val groupId: UUID, private val display: String? = null) : PointSource {
        override val count: Int? get() = null
        override val frame: PointFrame get() = PointFrame.WORLD
        override val standalone: Boolean get() = false

        override fun reference(context: PointSourceContext): PointSet = PointSet.of(engine(context).let { engine ->
            val data = requireNotNullLocalized(engine.getGroup(groupId)) { translation("error.group.not_found", label()) }
            data.memberIds().mapNotNull { engine.getParticle(it)?.position()?.toCore() }
        })

        override fun referenceCount(context: PointSourceContext): Int {
            val engine = ServerParticleEngine.get(dimensionId) ?: return 0
            return (engine.getGroup(groupId)?.size() ?: 0) + engine.pendingDrawCount(groupId)
        }

        override fun definitionPoints(context: PointSourceContext, fallbackCount: Int): PointSet {
            val engine = engine(context)
            val pivot = requireNotNullLocalized(engine.getGroup(groupId)) {
                translation("error.group.not_found", label())
            }.pivot()
            return PointSet.of(reference(context).points.map { it.subtract(pivot.toCore()) })
        }

        override fun label(): String = display ?: "group $groupId"

        private fun engine(context: PointSourceContext): ServerParticleEngine =
            ServerParticleEngine.get(context.dimensionId)
                ?: throw LocalizedArgumentException(translation("error.group.not_found", label()))
    }

    /** 命名点集：相对坐标，真正使用时从注册表取当前版本。 */
    data class NamedSet(val dimensionId: UUID, val name: String) : PointSource {
        override val count: Int? get() = null
        override val frame: PointFrame get() = PointFrame.RELATIVE
        override val standalone: Boolean get() = false

        override fun reference(context: PointSourceContext): PointSet = PointSets.get(context.level, name).points

        override fun definitionPoints(context: PointSourceContext, fallbackCount: Int): PointSet = reference(context)

        override fun referenceCount(context: PointSourceContext): Int = PointSets.find(context.level, name)?.points?.size ?: 0

        override fun label(): String = "pointset $name"
    }

    /** 直接内联一组点：程序化生成或从其它来源拼出来的结果。 */
    data class Points(val points: PointSet, private val display: String? = null) : PointSource {
        override val count: Int? get() = points.size
        override val frame: PointFrame get() = PointFrame.RELATIVE
        override fun sample(context: PointSourceContext, fallbackCount: Int): PointSet = points
        override fun label(): String = display ?: "points"
    }

    companion object {
        /** 从已有组构造引用源；命令层与模组都可直接传组对象。 */
        @JvmStatic
        fun group(group: ParticleGroup): GroupRef = GroupRef(group.manager.dimensionId, group.id)
    }
}

/**
 * 形变目标准备好的点。
 *
 * 采样源缓存点，引用源在 [morphPoints] 时才取快照；本类只做坐标系换算，
 * 形变数学仍由 `MorphRuntime` / `ParticleGroup.morph(points)` 负责。
 */
class PreparedPointSource(
    val source: PointSource,
    private val points: PointSet?,
    val count: Int,
) {
    /**
     * 形变使用的最终点：世界模式给绝对坐标，局域模式给相对锚点的偏移。
     * 组是世界快照，局域模式下换算成相对锚点；其它源是形状，世界模式下落一次锚点。
     * 引用型源没有缓存点时 [context] 必需。
     */
    fun morphPoints(context: PointSourceContext?, anchor: Vec3, local: Boolean): List<Vec3> {
        val set = points ?: requireNotNull(context) { "point source reference needs a context" }.let { source.reference(it) }
        requireLocalized(!set.isEmpty) { translation("error.morph.target_empty") }
        val offset = anchor.toCore()
        val result = when {
            source.frame == PointFrame.WORLD -> if (local) set.points.map { it.subtract(offset) } else set.points
            local -> set.points
            else -> set.points.map { it.add(offset) }
        }
        return result.map { it.toMinecraft() }
    }
}
