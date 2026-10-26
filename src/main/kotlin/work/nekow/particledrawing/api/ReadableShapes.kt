package work.nekow.particledrawing.api

import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// 命名坐标基与形状门面：点先在局部平面生成，再交给一个坐标基落到世界。
// 生成结果仍是普通 ParticleGroup 与 ParticleHandle，不新增播放器或成员表。

/** 右手坐标基：局部 x = right，y = up，z = forward。 */
class CoordinateFrame internal constructor(
    val origin: Vec3,
    val right: Vec3,
    val up: Vec3,
    val forward: Vec3,
    internal val entity: Entity? = null,
    internal val followPosition: Boolean = false,
    internal val followYaw: Boolean = false,
    internal val followOffset: Vec3 = Vec3.ZERO,
) {

    fun apply(local: Vec3): Vec3 = origin
        .add(right.scale(local.x))
        .add(up.scale(local.y))
        .add(forward.scale(local.z))

    internal fun createGroup(manager: ParticleManager): ParticleGroup {
        val target = entity
        val base = if (target != null && followPosition) origin.subtract(followOffset) else origin
        val group = manager.createGroup(base)
        if (target != null && followPosition) {
            group.followEntity(target.uuid, followOffset, followYaw)
        }
        return group
    }
}

/** 坐标基入口。 */
object Frames {

    /** 固定坐标基；[forward] 与 [upHint] 必须能张成非退化平面。 */
    @JvmStatic
    @JvmOverloads
    fun fixed(
        origin: Vec3,
        forward: Vec3 = Vec3(0.0, 0.0, 1.0),
        upHint: Vec3 = Vec3(0.0, 1.0, 0.0),
    ): CoordinateFrame {
        val basis = basis(forward, upHint)
        return CoordinateFrame(origin, basis.right, basis.up, basis.forward)
    }

    /**
     * 地面坐标基：默认跟随实体位置但不跟随朝向；[followYaw] = true 时连朝向一起跟随。
     *
     * 跟随由组的 `followEntity` 完成，调用方不需要每 tick 重建坐标基。
     */
    @JvmStatic
    @JvmOverloads
    fun ground(
        holder: Entity,
        followPosition: Boolean = true,
        followYaw: Boolean = false,
        offset: Vec3 = Vec3.ZERO,
    ): CoordinateFrame {
        require(followPosition || !followYaw) { "followYaw 需要同时 followPosition" }
        val origin = holder.position().add(offset)
        if (!followYaw) {
            return CoordinateFrame(
                origin,
                Vec3(1.0, 0.0, 0.0),
                Vec3(0.0, 1.0, 0.0),
                Vec3(0.0, 0.0, 1.0),
                holder,
                followPosition,
                false,
                offset,
            )
        }
        val yaw = Math.toRadians(holder.yRot.toDouble())
        val forward = Vec3(-sin(yaw), 0.0, cos(yaw)).normalize()
        val right = Vec3(0.0, 1.0, 0.0).cross(forward).normalize()
        return CoordinateFrame(origin, right, Vec3(0.0, 1.0, 0.0), forward, holder, true, true, offset)
    }

    /** 从正前方与上方向构造正交基；退化输入显式报错，不返回任意轴。 */
    @JvmStatic
    fun basis(forward: Vec3, upHint: Vec3 = Vec3(0.0, 1.0, 0.0)): CoordinateFrame {
        require(forward.lengthSqr() > 1e-12) { "坐标基的 forward 不能是零向量" }
        require(upHint.lengthSqr() > 1e-12) { "坐标基的 upHint 不能是零向量" }
        val f = forward.normalize()
        val right = upHint.cross(f)
        require(right.lengthSqr() > 1e-12) { "坐标基的 forward 与 upHint 平行，请换一个 upHint" }
        val r = right.normalize()
        val up = f.cross(r).normalize()
        return CoordinateFrame(Vec3.ZERO, r, up, f)
    }
}

/** 部件选择器：按全局采样下标、边下标与边内进度选择。 */
fun interface PartSelector {
    fun select(index: Int, edge: Int, edgeT: Double): Boolean
}

/** 一个命名部件的声明。 */
class ShapePartSpec internal constructor(
    val name: String,
    val selector: PartSelector,
    var color: Color? = null,
    var appearance: ParticleVisual? = null,
)

/** 已生成的命名部件句柄。 */
class ShapePart internal constructor(
    val name: String,
    val requested: Int,
    val handles: List<ParticleHandle>,
) {

    fun size(): Int = handles.size

    fun recolor(color: Color, durationTicks: Int = 0, easing: Ease = Ease.LINEAR) {
        for (h in handles) h.recolor(color, durationTicks, easing.toEasingType())
    }

    fun resize(scale: Float, durationTicks: Int = 0, easing: Ease = Ease.LINEAR) {
        for (h in handles) h.resize(scale, durationTicks, easing.toEasingType())
    }

    fun remove() {
        for (h in handles) h.remove()
    }
}

/** 形状结果：组仍是可继续编排的普通 [ParticleGroup]。 */
class Shape internal constructor(
    val name: String,
    val group: ParticleGroup,
    val parts: Map<String, ShapePart>,
    val requested: Int,
    val spawned: Int,
) {

    fun part(name: String): ShapePart? = parts[name]

    fun size(): Int = group.size()

    fun retire(graceTicks: Int = 2): ParticleGroup = group.retire(graceTicks)

    fun remove() = group.remove()
}

/** 正多边形边上的一点：先取两端顶点，再按 [edgeT] 在边内线性插值。 */
internal fun polygonPoint(sides: Int, radius: Double, initialAngle: Double, edge: Int, edgeT: Double): Vec3 {
    val step = 2.0 * PI / sides
    val startAngle = initialAngle + edge * step
    val endAngle = startAngle + step
    val start = Vec3(cos(startAngle) * radius, 0.0, sin(startAngle) * radius)
    val end = Vec3(cos(endAngle) * radius, 0.0, sin(endAngle) * radius)
    return start.add(end.subtract(start).scale(edgeT))
}

/**
 * 正多边形轮廓规格。Java 可直接 new 后调用 setter；Kotlin 用 `Draw.polygon(manager) { ... }`。
 */
class PolygonSpec {

    var name: String = "polygon"
    var center: Vec3 = Vec3.ZERO
    var coordinateFrame: CoordinateFrame? = null
    var sides: Int = 3
    var radius: Length = Length.blocks(1.0)

    /**
     * 每条边的采样点数，含该边起点、不含下一条边起点。
     *
     * 总粒子数 = [sides] × [samplesPerEdge]；顶点只由一条边的起点表示，不会重复。
     */
    var samplesPerEdge: Int = 1
    var initialAngle: Angle = Angle.radians(0.0)
    var angularSpeed: AngularSpeed? = null
    var appearance: ParticleVisual = ParticleVisual()
    var color: Color = Color.WHITE
    var colorSource: ColorSource? = null
    var lifeCurve: ParticleLifeCurve? = null

    private val partSpecs = ArrayList<ShapePartSpec>()

    fun part(
        name: String,
        selector: PartSelector,
        color: Color? = null,
        appearance: ParticleVisual? = null,
    ): PolygonSpec {
        require(partSpecs.none { it.name == name }) { "部件名 '$name' 重复" }
        partSpecs.add(ShapePartSpec(name, selector, color, appearance))
        return this
    }

    fun build(manager: ParticleManager): Shape {
        require(sides >= 3) { "正多边形至少需要 3 条边" }
        require(samplesPerEdge >= 1) { "每条边至少需要 1 个采样" }
        radius.requirePositive("多边形半径")
        val frame = coordinateFrame ?: Frames.fixed(center)
        val group = frame.createGroup(manager)
        angularSpeed?.let { group.spin(frame.up, it.radiansPerTick) }

        val created = HashMap<String, MutableList<ParticleHandle>>()
        val requestedByPart = HashMap<String, Int>()
        for (p in partSpecs) {
            created[p.name] = ArrayList()
            requestedByPart[p.name] = 0
        }
        var requested = 0
        var spawned = 0

        for (edge in 0 until sides) {
            for (j in 0 until samplesPerEdge) {
                // 采样沿边线性插值；每条边含起点、不含下一条边的起点，避免顶点重复。
                val edgeT = j.toDouble() / samplesPerEdge
                val local = polygonPoint(sides, radius.blocks, initialAngle.radians, edge, edgeT)
                val globalIndex = edge * samplesPerEdge + j
                val u = (edge + edgeT) / sides
                val world = frame.apply(local)
                val part = partSpecs.firstOrNull { it.selector.select(globalIndex, edge, edgeT) }
                val visual = part?.appearance ?: appearance
                val color = part?.color ?: colorSource?.colorAt(u) ?: this.color
                requested++
                part?.let { requestedByPart[it.name] = (requestedByPart[it.name] ?: 0) + 1 }

                val builder = manager.create()
                    .position(world)
                    .color(color)
                    .scale(1f)
                    .lifetime(-1)
                    .group(group.id)
                    .offsetFromPivot(world.subtract(group.pivot))
                if (!visual.isPristine()) {
                    builder.visual(visual).glowing(visual.glowing).lightLevel(visual.lightLevel)
                }
                lifeCurve?.let { builder.lifeCurve(it) }
                val handle = builder.spawn()
                if (handle != null) {
                    spawned++
                    // builder.group 已把成员登记进服务端组，这里只记录部件句柄
                    part?.let { created[it.name]?.add(handle) }
                }
            }
        }

        val parts = partSpecs.associate { p ->
            p.name to ShapePart(p.name, requestedByPart[p.name] ?: 0, created[p.name] ?: emptyList())
        }
        return Shape(name, group, parts, requested, spawned)
    }
}

/**
 * 生成正多边形轮廓，可在 [PolygonSpec] 中声明命名部件、坐标基、半径、角速度与外观。
 * 返回的 [Shape.group] 仍是普通组，可以继续 `spin` / `expression` / `retire`。
 */
/** Java 静态入口；Kotlin 可直接用 `Draw.polygon`。 */
object Shapes {
    @JvmStatic
    fun polygon(manager: ParticleManager, spec: PolygonSpec): Shape = spec.build(manager)

    @JvmStatic
    fun builder(): PolygonSpec = PolygonSpec()
}
