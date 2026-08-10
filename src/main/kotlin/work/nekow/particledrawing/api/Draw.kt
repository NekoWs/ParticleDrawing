package work.nekow.particledrawing.api

import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.sin

/**
 * 创建粒子形状的高级绘图工具。
 *
 * 每个方法返回一个 [ParticleGroup]，
 * 可进一步使用移动、旋转、重新着色和缩放操作进行动画处理。
 *
 * 示例：
 * ```
 * val circle = Draw.circle(manager, center, 5.0, 64, Draw.Axis.XZ)
 * circle.rotate(Vec3.Z, Math.PI * 2, 100, EasingType.EASE_IN_OUT)
 * circle.recolor(Color.RED, 40, EasingType.EASE_OUT)
 * ```
 */
@Suppress("unused")
object Draw {

    private val DEFAULT_COLOR = Color.WHITE
    private val DEFAULT_STYLE = ParticleStyle.DUST
    private const val DEFAULT_SCALE = 0.5f

    /**
     * 在两点之间绘制一条由粒子组成的线段。
     *
     * @param manager 粒子管理器
     * @param start   起点
     * @param end     终点
     * @param count   线段上的粒子数量
     * @return 包含线段上所有粒子的粒子组
     */
    fun line(manager: ParticleManager, start: Vec3, end: Vec3, count: Int): ParticleGroup {
        return line(manager, start, end, count, DEFAULT_COLOR, DEFAULT_STYLE, DEFAULT_SCALE)
    }

    fun line(manager: ParticleManager, start: Vec3, end: Vec3, count: Int, color: Color): ParticleGroup {
        return line(manager, start, end, count, color, DEFAULT_STYLE, DEFAULT_SCALE)
    }

    fun line(
        manager: ParticleManager, start: Vec3, end: Vec3,
        count: Int, color: Color, style: ParticleStyle, scale: Float
    ): ParticleGroup {
        val pivot = start.add(end).scale(0.5)
        val group = manager.createGroup(pivot)

        val dir = end.subtract(start)
        val length = dir.length()
        if (length < 0.0001) return group

        for (i in 0 until count) {
            val t = if (count > 1) i.toDouble() / (count - 1) else 0.5
            val pos = start.add(dir.scale(t))
            val offset = pos.subtract(pivot)

            val handle = manager.create()
                .style(style)
                .position(pos)
                .color(color)
                .scale(scale)
                .lifetime(-1)
                .group(group.id)
                .offsetFromPivot(offset)
                .spawn()
            group.add(handle)
        }

        return group
    }

    /**
     * 绘制一个由粒子组成的圆。
     *
     * @param manager 粒子管理器
     * @param center  圆心
     * @param radius  圆半径
     * @param count   粒子数量
     * @param axis    绘制平面 (XZ = 水平, XY = 朝 Z 方向的垂直面, YZ = 朝 X 方向的垂直面)
     */
    fun circle(
        manager: ParticleManager, center: Vec3,
        radius: Double, count: Int, axis: Axis
    ): ParticleGroup {
        return circle(manager, center, radius, count, axis, DEFAULT_COLOR, DEFAULT_STYLE, DEFAULT_SCALE)
    }

    fun circle(
        manager: ParticleManager, center: Vec3,
        radius: Double, count: Int, axis: Axis, color: Color
    ): ParticleGroup {
        return circle(manager, center, radius, count, axis, color, DEFAULT_STYLE, DEFAULT_SCALE)
    }

    fun circle(
        manager: ParticleManager, center: Vec3,
        radius: Double, count: Int, axis: Axis,
        color: Color, style: ParticleStyle, scale: Float
    ): ParticleGroup {
        val group = manager.createGroup(center)

        for (i in 0 until count) {
            val angle = 2.0 * Math.PI * i / count
            val u = cos(angle) * radius
            val v = sin(angle) * radius

            val pos = when (axis) {
                Axis.XZ -> Vec3(center.x + u, center.y, center.z + v)
                Axis.XY -> Vec3(center.x + u, center.y + v, center.z)
                Axis.YZ -> Vec3(center.x, center.y + u, center.z + v)
            }
            val offset = pos.subtract(center)

            val handle = manager.create()
                .style(style)
                .position(pos)
                .color(color)
                .scale(scale)
                .lifetime(-1)
                .group(group.id)
                .offsetFromPivot(offset)
                .spawn()
            group.add(handle)
        }

        return group
    }

    /**
     * 通过叠加同心圆绘制实心圆（圆盘）。
     *
     * @param layers 从圆心到边缘的同心环层数
     */
    fun disc(
        manager: ParticleManager, center: Vec3,
        radius: Double, perimeterCount: Int, layers: Int,
        axis: Axis
    ): ParticleGroup {
        return disc(manager, center, radius, perimeterCount, layers, axis,
            DEFAULT_COLOR, DEFAULT_STYLE, DEFAULT_SCALE)
    }

    fun disc(
        manager: ParticleManager, center: Vec3,
        radius: Double, perimeterCount: Int, layers: Int,
        axis: Axis, color: Color, style: ParticleStyle, scale: Float
    ): ParticleGroup {
        val group = manager.createGroup(center)

        for (layer in 0..layers) {
            val r = radius * layer / maxOf(1, layers)
            val n = maxOf(1, (perimeterCount * r / maxOf(0.001, radius)).toInt())
            for (i in 0 until n) {
                val angle = 2.0 * Math.PI * i / n
                val u = cos(angle) * r
                val v = sin(angle) * r

                val pos = when (axis) {
                    Axis.XZ -> Vec3(center.x + u, center.y, center.z + v)
                    Axis.XY -> Vec3(center.x + u, center.y + v, center.z)
                    Axis.YZ -> Vec3(center.x, center.y + u, center.z + v)
                }
                val offset = pos.subtract(center)

                val handle = manager.create()
                    .style(style)
                    .position(pos)
                    .color(color)
                    .scale(scale)
                    .lifetime(-1)
                    .group(group.id)
                    .offsetFromPivot(offset)
                    .spawn()
                group.add(handle)
            }
        }

        return group
    }

    /**
     * 沿由位置函数定义的参数曲线生成粒子。
     *
     * @param posFunc 接收 t ∈ [0, 1] 并返回世界坐标的函数
     * @param steps   采样点数
     */
    fun curve(
        manager: ParticleManager,
        posFunc: (Double) -> Vec3,
        steps: Int, color: Color, style: ParticleStyle, scale: Float
    ): ParticleGroup {
        val first = posFunc(0.0)
        val last = posFunc(1.0)
        val pivot = first.add(last).scale(0.5)
        val group = manager.createGroup(pivot)

        for (i in 0 until steps) {
            val t = i.toDouble() / maxOf(1, steps - 1)
            val pos = posFunc(t)
            val offset = pos.subtract(pivot)

            val handle = manager.create()
                .style(style)
                .position(pos)
                .color(color)
                .scale(scale)
                .lifetime(-1)
                .group(group.id)
                .offsetFromPivot(offset)
                .spawn()
            group.add(handle)
        }

        return group
    }

    /**
     * 描述 2D 图形所绘制的平面。
     */
    enum class Axis {
        XZ,
        XY,
        YZ
    }
}
