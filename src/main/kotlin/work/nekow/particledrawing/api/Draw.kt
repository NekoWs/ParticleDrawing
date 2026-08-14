package work.nekow.particledrawing.api

import net.minecraft.world.phys.Vec3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 创建粒子形状的绘图工具，每个方法返回一个 [ParticleGroup]。
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
    @JvmStatic
    fun line(manager: ParticleManager, start: Vec3, end: Vec3, count: Int): ParticleGroup {
        return line(manager, start, end, count, DEFAULT_COLOR, DEFAULT_STYLE, DEFAULT_SCALE)
    }

    @JvmStatic
    fun line(manager: ParticleManager, start: Vec3, end: Vec3, count: Int, color: Color): ParticleGroup {
        return line(manager, start, end, count, color, DEFAULT_STYLE, DEFAULT_SCALE)
    }

    @JvmStatic
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

            manager.create()
                .style(style)
                .position(pos)
                .color(color)
                .scale(scale)
                .lifetime(-1)
                .group(group.id)
                .offsetFromPivot(offset)
                .spawn()
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
    @JvmStatic
    fun circle(
        manager: ParticleManager, center: Vec3,
        radius: Double, count: Int, axis: Axis
    ): ParticleGroup {
        return circle(manager, center, radius, count, axis, DEFAULT_COLOR, DEFAULT_STYLE, DEFAULT_SCALE)
    }

    @JvmStatic
    fun circle(
        manager: ParticleManager, center: Vec3,
        radius: Double, count: Int, axis: Axis, color: Color
    ): ParticleGroup {
        return circle(manager, center, radius, count, axis, color, DEFAULT_STYLE, DEFAULT_SCALE)
    }

    @JvmStatic
    fun circle(
        manager: ParticleManager, center: Vec3,
        radius: Double, count: Int, axis: Axis,
        color: Color, style: ParticleStyle, scale: Float
    ): ParticleGroup {
        val group = manager.createGroup(center)

        for (i in 0 until count) {
            val angle = 2.0 * PI * i / count
            val u = cos(angle) * radius
            val v = sin(angle) * radius

            val pos = when (axis) {
                Axis.XZ -> Vec3(center.x + u, center.y, center.z + v)
                Axis.XY -> Vec3(center.x + u, center.y + v, center.z)
                Axis.YZ -> Vec3(center.x, center.y + u, center.z + v)
            }
            val offset = pos.subtract(center)

            manager.create()
                .style(style)
                .position(pos)
                .color(color)
                .scale(scale)
                .lifetime(-1)
                .group(group.id)
                .offsetFromPivot(offset)
                .spawn()
        }

        return group
    }

    /**
     * 通过叠加同心圆绘制实心圆（圆盘）。
     *
     * @param manager 粒子管理器
     * @param center 圆心
     * @param radius 圆盘半径
     * @param perimeterCount 最外层圆周的粒子数（内层按半径比例递减）
     * @param layers 从圆心到边缘的同心环层数
     * @param axis 绘制平面
     */
    @JvmStatic
    fun disc(
        manager: ParticleManager, center: Vec3,
        radius: Double, perimeterCount: Int, layers: Int,
        axis: Axis
    ): ParticleGroup {
        return disc(manager, center, radius, perimeterCount, layers, axis,
            DEFAULT_COLOR, DEFAULT_STYLE, DEFAULT_SCALE)
    }

    @JvmStatic
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
                val angle = 2.0 * PI * i / n
                val u = cos(angle) * r
                val v = sin(angle) * r

                val pos = when (axis) {
                    Axis.XZ -> Vec3(center.x + u, center.y, center.z + v)
                    Axis.XY -> Vec3(center.x + u, center.y + v, center.z)
                    Axis.YZ -> Vec3(center.x, center.y + u, center.z + v)
                }
                val offset = pos.subtract(center)

                manager.create()
                    .style(style)
                    .position(pos)
                    .color(color)
                    .scale(scale)
                    .lifetime(-1)
                    .group(group.id)
                    .offsetFromPivot(offset)
                    .spawn()
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
    @JvmStatic
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

            manager.create()
                .style(style)
                .position(pos)
                .color(color)
                .scale(scale)
                .lifetime(-1)
                .group(group.id)
                .offsetFromPivot(offset)
                .spawn()
        }

        return group
    }

    /**
     * 绘制斐波那契球面分布的粒子球体。
     * 粒子均匀分布在球面上，顶部到底部有彩虹渐变。
     *
     * @param manager 粒子管理器
     * @param center 球心位置
     * @param radius 球体半径
     * @param count 粒子数量
     * @param style 粒子样式
     * @param scale 粒子缩放
     * @return 包含所有球面粒子的组
     */
    @JvmStatic
    @JvmOverloads
    fun sphere(
        manager: ParticleManager, center: Vec3, radius: Double, count: Int,
        style: ParticleStyle = ParticleStyle.DUST, scale: Float = 0.2f
    ): ParticleGroup {
        val group = manager.createGroup(center)
        val phi = PI * (3.0 - sqrt(5.0))
        for (i in 0 until count) {
            val y = 1.0 - (i.toDouble() / maxOf(1, count - 1)) * 2.0
            val r = sqrt(1.0 - y * y)
            val theta = phi * i
            val x = cos(theta) * r * radius
            val z = sin(theta) * r * radius

            val hue = ((1.0 - y) / 2.0).toFloat()
            manager.create()
                .style(style).scale(scale)
                .position(center.x + x, center.y + y * radius, center.z + z)
                .color(Color.ofHsb(hue, 0.9f, 0.9f))
                .lifetime(-1).group(group.id)
                .spawn()
        }
        return group
    }

    /**
     * 绘制正三角形（三个顶点两两连线的轮廓）。
     *
     * @param segmentsPerEdge 每条边细分的粒子段数
     * @param rotationOffset 整体旋转偏移（弧度）
     * @param group 可选，若提供则粒子归入该组而非创建新组
     */
    @JvmStatic
    @JvmOverloads
    fun triangle(
        manager: ParticleManager, center: Vec3, radius: Double, segmentsPerEdge: Int = 30,
        rotationOffset: Double = 0.0, axis: Axis = Axis.XZ,
        color: Color = Color.WHITE, style: ParticleStyle = ParticleStyle.DUST, scale: Float = 0.2f,
        group: ParticleGroup? = null
    ): ParticleGroup {
        val g = group ?: manager.createGroup(center)
        for (v in 0..2) {
            val a1 = rotationOffset + 2.0 * PI * v / 3.0
            val a2 = rotationOffset + 2.0 * PI * (v + 1) / 3.0
            for (j in 0 until segmentsPerEdge) {
                val t = j.toDouble() / segmentsPerEdge
                val x = (cos(a1) * (1 - t) + cos(a2) * t) * radius
                val z = (sin(a1) * (1 - t) + sin(a2) * t) * radius
                val pos = when (axis) {
                    Axis.XZ -> Vec3(center.x + x, center.y, center.z + z)
                    Axis.XY -> Vec3(center.x + x, center.y + z, center.z)
                    Axis.YZ -> Vec3(center.x, center.y + x, center.z + z)
                }
                manager.create().style(style).scale(scale)
                    .position(pos).color(color).lifetime(-1).group(g.id)
                    .spawn()
            }
        }
        return g
    }

    /**
     * 绘制六芒星（两个正三角形旋转 60° 叠加）。
     *
     * @param color1 第一个三角形的颜色
     * @param color2 第二个（旋转 60°）三角形的颜色
     */
    @JvmStatic
    @JvmOverloads
    fun hexagram(
        manager: ParticleManager, center: Vec3, radius: Double, segmentsPerEdge: Int = 40,
        axis: Axis = Axis.XZ,
        color1: Color = Color.WHITE,
        color2: Color = Color.WHITE,
        style: ParticleStyle = ParticleStyle.DUST, scale: Float = 0.2f
    ): ParticleGroup {
        val group = manager.createGroup(center)
        triangle(manager, center, radius, segmentsPerEdge, 0.0, axis, color1, style, scale, group)
        triangle(manager, center, radius, segmentsPerEdge, PI / 3.0, axis, color2, style, scale, group)
        return group
    }

    /**
     * 绘制 3D 长方体粒子网格（表面或实心）。
     *
     * @param particlesPerAxis 每条边期望的粒子数（用于推导网格间距）
     * @param hollow 为 true 时只绘制表面，否则填充内部
     */
    @JvmStatic
    @JvmOverloads
    fun cuboid(
        manager: ParticleManager, center: Vec3,
        width: Double, height: Double, depth: Double,
        particlesPerAxis: Int = 15, hollow: Boolean = false,
        color: Color = Color.WHITE, style: ParticleStyle = ParticleStyle.DUST, scale: Float = 0.2f
    ): ParticleGroup {
        val group = manager.createGroup(center)
        val hw = width / 2; val hh = height / 2; val hd = depth / 2
        val step = particlesPerAxis.coerceAtLeast(2)
        val sp = maxOf(width, maxOf(height, depth)) / (step - 1)
        val nx = (width / sp).toInt() + 1; val ny = (height / sp).toInt() + 1; val nz = (depth / sp).toInt() + 1

        for (ix in 0..nx) {
            val x = center.x - hw + ix * sp
            for (iy in 0..ny) {
                val y = center.y - hh + iy * sp
                for (iz in 0..nz) {
                    val z = center.z - hd + iz * sp
                    if (hollow && ix > 0 && ix < nx && iy > 0 && iy < ny && iz > 0 && iz < nz) continue
                    manager.create().style(style).scale(scale)
                        .position(x, y, z).color(color).lifetime(-1).group(group.id)
                        .spawn()
                }
            }
        }
        return group
    }

    /**
     * 绘制 2D 矩形网格（边框或填充）。
     *
     * @param particlesPerAxis 每条边期望的粒子数（用于推导网格间距）
     * @param hollow 为 true 时只绘制边框，否则填充内部
     * @param axis 矩形所在平面
     */
    @JvmStatic
    @JvmOverloads
    fun rect(
        manager: ParticleManager, center: Vec3,
        width: Double, height: Double, particlesPerAxis: Int = 15,
        hollow: Boolean = false, axis: Axis = Axis.XZ,
        color: Color = Color.WHITE, style: ParticleStyle = ParticleStyle.DUST, scale: Float = 0.2f
    ): ParticleGroup {
        val group = manager.createGroup(center)
        val hw = width / 2; val hh = height / 2
        val step = particlesPerAxis.coerceAtLeast(2)
        val sp = maxOf(width, height) / (step - 1)
        val nu = (width / sp).toInt() + 1; val nv = (height / sp).toInt() + 1

        for (iu in 0..nu) {
            for (iv in 0..nv) {
                val u = center.x - hw + iu * sp
                val v = center.y - hh + iv * sp
                if (hollow && iu > 0 && iu < nu && iv > 0 && iv < nv) continue
                val pos = when (axis) {
                    Axis.XZ -> Vec3(u, center.y, v)
                    Axis.XY -> Vec3(u, v, center.z)
                    Axis.YZ -> Vec3(center.x, u, v)
                }
                manager.create().style(style).scale(scale)
                    .position(pos).color(color).lifetime(-1).group(group.id)
                    .spawn()
            }
        }
        return group
    }

    /**
     * 描述 2D 图形所绘制的平面。
     */
    enum class Axis {
        /** 水平面（默认，Y 为法线，图形铺在 XZ 平面） */
        XZ,
        /** 朝 Z 方向的垂直面（图形铺在 XY 平面） */
        XY,
        /** 朝 X 方向的垂直面（图形铺在 YZ 平面） */
        YZ
    }
}
