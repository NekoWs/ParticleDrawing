package work.nekow.particledrawing.pointset

import work.nekow.particledrawing.animation.script.ScriptRuntime
import work.nekow.particledrawing.i18n.LocalizedArgumentException
import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.i18n.translation
import work.nekow.particledrawing.math.Vec3
import work.nekow.particledrawing.util.rotateAround
import kotlin.random.Random

/** 点集的三个轴；旋转与镜像都绕原点。 */
enum class PointAxis {
    X, Y, Z;

    fun unit(): Vec3 = when (this) {
        X -> Vec3(1.0, 0.0, 0.0)
        Y -> Vec3(0.0, 1.0, 0.0)
        Z -> Vec3(0.0, 0.0, 1.0)
    }
}

/**
 * 点集的一条变换，按书写顺序应用。
 *
 * [Scaled] 与 [Mirrored] 都相对原点；要围绕别的中心先 [PointTransform.Translate] 再变换再移回，
 * 组合由调用方决定，这里不隐式挑中心。
 */
sealed class PointTransform {
    data class Translate(val delta: Vec3) : PointTransform()
    data class Rotate(val axis: PointAxis, val degrees: Double) : PointTransform()
    data class Scale(val factor: Double) : PointTransform()
    data class Mirror(val axis: PointAxis) : PointTransform()

    fun apply(point: Vec3): Vec3 = when (this) {
        is Translate -> point.add(delta)
        is Rotate -> point.rotateAround(axis.unit(), Math.toRadians(degrees))
        is Scale -> point.scale(factor)
        is Mirror -> when (axis) {
            PointAxis.X -> Vec3(-point.x, point.y, point.z)
            PointAxis.Y -> Vec3(point.x, -point.y, point.z)
            PointAxis.Z -> Vec3(point.x, point.y, -point.z)
        }
    }
}

/**
 * 只存点的不可变点集，用来在采样、命名点集、形变目标之间传递。
 *
 * 所有操作返回新对象；坐标是消费方自己的空间（通常相对消费者锚点），
 * 需要世界坐标时由消费者在最终使用处落锚点。点数上限沿用编排成员上限。
 */
data class PointSet(val points: List<Vec3>) {
    init {
        requireLocalized(points.size <= MAX_POINTS) { translation("error.pointset.size", MAX_POINTS) }
    }

    val size: Int get() = points.size
    val isEmpty: Boolean get() = points.isEmpty()

    /** 依次应用多条变换；空点集直接返回自身。 */
    fun transformed(transforms: Iterable<PointTransform>): PointSet {
        if (isEmpty) return this
        var current = points
        for (transform in transforms) current = current.map(transform::apply)
        return PointSet(current)
    }

    fun transformed(vararg transforms: PointTransform): PointSet = transformed(transforms.asIterable())

    fun translated(delta: Vec3): PointSet = PointSet(points.map { it.add(delta) })

    fun scaled(factor: Double): PointSet = PointSet(points.map { it.scale(factor) })

    fun rotated(axis: PointAxis, degrees: Double): PointSet = PointSet(points.map { it.rotateAround(axis.unit(), Math.toRadians(degrees)) })

    fun mirrored(axis: PointAxis): PointSet = transformed(PointTransform.Mirror(axis))

    /** 按顺序拼接，用于合并点集或拼出路径。 */
    fun concatenated(other: PointSet): PointSet = PointSet(points + other.points)

    fun filtered(predicate: (index: Int, point: Vec3) -> Boolean): PointSet =
        PointSet(points.filterIndexed(predicate))

    /**
     * 按公式保留点。变量 `x` / `y` / `z` 是坐标，`i` 是序号，`n` 是总数，`u = i/(n-1)`；
     * 真值保留（布尔 true 或非 0 有限数）。公式编译一次，可放心用于上千个点。
     */
    fun filteredByFormula(formula: String): PointSet {
        if (isEmpty) return this
        val runner = try {
            ScriptRuntime.ExpressionRunner(formula)
        } catch (error: RuntimeException) {
            throw LocalizedArgumentException(translation("error.pointset.filter", formula, error.message ?: error.javaClass.simpleName), error)
        }
        val n = points.size.toDouble()
        val kept = ArrayList<Vec3>(points.size)
        for ((index, point) in points.withIndex()) {
            val i = index.toDouble()
            val ctx = ScriptRuntime.ProcessCtx(
                i = i, n = n, t = 0.0, dt = 1.0, life = 1.0, uv_x = 0.0, uv_y = 0.0,
                vars = mapOf(
                    "x" to point.x, "y" to point.y, "z" to point.z,
                    "i" to i, "n" to n, "u" to if (n <= 1) 0.0 else i / (n - 1),
                ),
            )
            val value = try {
                runner.evalAny(ctx)
            } catch (error: RuntimeException) {
                throw LocalizedArgumentException(translation("error.pointset.filter", formula, error.message ?: error.javaClass.simpleName), error)
            }
            val keep = when (value) {
                is Boolean -> value
                is Double -> {
                    requireLocalized(value.isFinite()) { translation("error.pointset.filter_value", formula) }
                    value != 0.0
                }
                else -> throw LocalizedArgumentException(translation("error.pointset.filter_value", formula))
            }
            if (keep) kept += point
        }
        return PointSet(kept)
    }

    /** 按标量排序，用于 `allocation index` 的配对顺序；排序稳定。 */
    fun sortedBy(selector: (index: Int, point: Vec3) -> Double): PointSet =
        PointSet(points.withIndex().sortedBy { selector(it.index, it.value) }.map { it.value })

    /** 固定种子的洗牌，结果可复现。 */
    fun shuffled(seed: Long): PointSet = PointSet(points.shuffled(Random(seed)))

    companion object {
        const val MAX_POINTS = 8192
        val EMPTY = PointSet(emptyList())

        fun of(points: List<Vec3>): PointSet = if (points.isEmpty()) EMPTY else PointSet(points)
    }
}
