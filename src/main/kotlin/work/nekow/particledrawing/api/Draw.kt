package work.nekow.particledrawing.api

import net.minecraft.world.phys.Vec3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import work.nekow.particledrawing.core.server.AnimationScheduler

/**
 * 颜色来源：按形状参数 t ∈ [0,1] 返回颜色。
 * t 的含义由各形状定义：线段起点→终点、圆周一周、球面顶→底等。
 *
 * Kotlin 可直接用 lambda：`colorFn = { t -> Color.ofHsb(t.toFloat(), .9f, .9f) }`
 */
@Suppress("unused")
fun interface ColorSource {
    fun colorAt(t: Double): Color

    companion object {
        /** 固定颜色。 */
        @JvmStatic
        fun of(color: Color): ColorSource = ColorSource { color }

        /** 彩虹渐变（HSB 色相随 t 扫过一圈的 2/3，饱和度/亮度 0.9）。 */
        @JvmStatic
        @JvmOverloads
        fun rainbow(alpha: Float = 1f): ColorSource =
            ColorSource { t -> Color.ofHsb((t * 2.0 / 3.0).toFloat() % 1f, 0.9f, 0.9f, alpha) }

        /** 从 [from] 到 [to] 线性插值。 */
        @JvmStatic
        fun gradient(from: Color, to: Color): ColorSource = ColorSource { t -> from.lerp(to, t.toFloat()) }
    }
}

/**
 * 创建粒子形状的绘图工具，每个方法返回一个 [ParticleGroup]，
 * 可立即链式调用动画（fadeIn / spin / movePath 等）。
 *
 * 所有形状共享的新特性参数：
 * - [ colorFn ] 沿形状参数渐变着色；
 * - [ scale ] 粒子缩放；
 * - [ stagger ] 逐粒子入场延迟（tick），实现波浪式出现；
 * - [ group ] 复用已有组而非新建。
 */
@Suppress("unused")
object Draw {

    private const val DEFAULT_SCALE = 1f

    /* =====================================================================
     * 低级绘制：单粒子 / 自由曲线
     * ===================================================================== */

    /**
     * 在指定位置放置单个粒子并加入 [group]（为 null 时自动创建新组）。
     */
    @JvmStatic
    @JvmOverloads
    fun dot(
        manager: ParticleManager, pos: Vec3,
        colorFn: ColorSource = ColorSource.of(Color.WHITE), scale: Float = DEFAULT_SCALE,
        group: ParticleGroup? = null
    ): ParticleGroup {
        val g = group ?: manager.createGroup(pos)
        manager.create()
            .position(pos)
            .color(colorFn.colorAt(0.0))
            .scale(scale)
            .lifetime(-1)
            .group(g.id)
            .spawn()
        return g
    }

    /**
     * 两点之间绘制一条由粒子组成的线段。
     *
     * @param start   起点
     * @param end     终点
     * @param count   线段上的粒子数量
     */
    @JvmStatic
    @JvmOverloads
    fun line(
        manager: ParticleManager, start: Vec3, end: Vec3, count: Int,
        colorFn: ColorSource = ColorSource.of(Color.WHITE), scale: Float = DEFAULT_SCALE,
        stagger: Int = 0, group: ParticleGroup? = null
    ): ParticleGroup {
        val g = group ?: manager.createGroup(start.add(end).scale(0.5))
        val dir = end.subtract(start)
        if (dir.length() < 0.0001) return g

        for (i in 0 until count) {
            val t = if (count > 1) i.toDouble() / (count - 1) else 0.5
            place(manager, g, start.add(dir.scale(t)), colorFn.colorAt(t), scale, i, stagger)
        }
        return g
    }

    /**
     * 沿由位置函数定义的参数曲线生成粒子。
     *
     * @param posFunc 接收 t ∈ [0, 1] 并返回世界坐标的函数
     * @param steps   采样点数
     */
    @JvmStatic
    @JvmOverloads
    fun curve(
        manager: ParticleManager, posFunc: (Double) -> Vec3, steps: Int,
        colorFn: ColorSource = ColorSource.of(Color.WHITE), scale: Float = DEFAULT_SCALE,
        stagger: Int = 0, group: ParticleGroup? = null
    ): ParticleGroup {
        val first = posFunc(0.0)
        val last = posFunc(1.0)
        val g = group ?: manager.createGroup(first.add(last).scale(0.5))
        for (i in 0 until steps) {
            val t = i.toDouble() / steps.coerceAtLeast(1)
            place(manager, g, posFunc(t), colorFn.colorAt(t), scale, i, stagger)
        }
        return g
    }

    /* =====================================================================
     * 2D 图形（axis 选择所在平面）
     * ===================================================================== */

    /**
     * 绘制一个由粒子组成的圆周。
     *
     * @param radius 圆半径
     * @param count  粒子数量
     * @param axis   绘制平面 (XZ = 水平, XY = 朝 Z 方向的垂直面, YZ = 朝 X 方向的垂直面)
     */
    @JvmStatic
    @JvmOverloads
    fun circle(
        manager: ParticleManager, center: Vec3,
        radius: Double, count: Int, axis: Axis = Axis.XZ,
        colorFn: ColorSource = ColorSource.of(Color.WHITE), scale: Float = DEFAULT_SCALE,
        stagger: Int = 0, group: ParticleGroup? = null
    ): ParticleGroup {
        val g = group ?: manager.createGroup(center)
        for (i in 0 until count) {
            val angle = 2.0 * PI * i / count
            val u = cos(angle) * radius
            val v = sin(angle) * radius
            place(manager, g, axisPoint(center, axis, u, v), colorFn.colorAt(i.toDouble() / count), scale, i, stagger)
        }
        return g
    }

    /**
     * 通过叠加同心圆绘制实心圆盘。
     *
     * @param perimeterCount 最外层圆周的粒子数（内层按半径比例递减）
     * @param layers         从圆心到边缘的同心环层数
     */
    @JvmStatic
    @JvmOverloads
    fun disc(
        manager: ParticleManager, center: Vec3,
        radius: Double, perimeterCount: Int, layers: Int, axis: Axis = Axis.XZ,
        colorFn: ColorSource = ColorSource.of(Color.WHITE), scale: Float = DEFAULT_SCALE,
        stagger: Int = 0, group: ParticleGroup? = null
    ): ParticleGroup {
        val g = group ?: manager.createGroup(center)
        var placed = 0
        for (layer in 0..layers) {
            val r = radius * layer / maxOf(1, layers)
            val n = maxOf(if (layer == 0) 1 else 0, (perimeterCount * r / maxOf(0.001, radius)).toInt())
            for (i in 0 until n) {
                val angle = 2.0 * PI * i / n
                val u = cos(angle) * r
                val v = sin(angle) * r
                place(manager, g, axisPoint(center, axis, u, v),
                    colorFn.colorAt(layer.toDouble() / maxOf(1, layers)), scale, placed++, stagger)
            }
        }
        return g
    }

    /**
     * 绘制正三角形轮廓。
     *
     * @param segmentsPerEdge 每条边细分的粒子数
     * @param rotationOffset  整体旋转偏移（弧度）
     */
    @JvmStatic
    @JvmOverloads
    fun triangle(
        manager: ParticleManager, center: Vec3, radius: Double, segmentsPerEdge: Int = 30,
        rotationOffset: Double = 0.0, axis: Axis = Axis.XZ,
        colorFn: ColorSource = ColorSource.of(Color.WHITE), scale: Float = DEFAULT_SCALE,
        stagger: Int = 0, group: ParticleGroup? = null
    ): ParticleGroup {
        val g = group ?: manager.createGroup(center)
        var placed = 0
        for (v in 0..2) {
            val a1 = rotationOffset + 2.0 * PI * v / 3.0
            val a2 = rotationOffset + 2.0 * PI * (v + 1) / 3.0
            for (j in 0 until segmentsPerEdge) {
                val t = j.toDouble() / segmentsPerEdge
                val x = (cos(a1) * (1 - t) + cos(a2) * t) * radius
                val z = (sin(a1) * (1 - t) + sin(a2) * t) * radius
                place(manager, g, axisPoint(center, axis, x, z),
                    colorFn.colorAt(placed.toDouble() / (segmentsPerEdge * 3)), scale, placed++, stagger)
            }
        }
        return g
    }

    /**
     * 绘制六芒星（两个正三角形旋转 60° 叠加）。
     *
     * @param colorFn1 第一个三角形的颜色来源
     * @param colorFn2 第二个（旋转 60°）三角形的颜色来源
     */
    @JvmStatic
    @JvmOverloads
    fun hexagram(
        manager: ParticleManager, center: Vec3, radius: Double, segmentsPerEdge: Int = 40,
        axis: Axis = Axis.XZ,
        colorFn1: ColorSource = ColorSource.of(Color.WHITE),
        colorFn2: ColorSource = ColorSource.of(Color.WHITE),
        scale: Float = DEFAULT_SCALE,
        stagger: Int = 0, group: ParticleGroup? = null
    ): ParticleGroup {
        val g = group ?: manager.createGroup(center)
        triangle(manager, center, radius, segmentsPerEdge, 0.0, axis, colorFn1, scale, stagger, g)
        triangle(manager, center, radius, segmentsPerEdge, PI / 3.0, axis, colorFn2, scale, stagger, g)
        return g
    }

    /**
     * 2D 矩形网格。
     *
     * @param particlesPerAxis 每条边期望的粒子数（推导网格间距）
     * @param hollow 为 true 时只绘制边框，否则填充内部
     */
    @JvmStatic
    @JvmOverloads
    fun rect(
        manager: ParticleManager, center: Vec3,
        width: Double, height: Double, particlesPerAxis: Int = 15,
        hollow: Boolean = false, axis: Axis = Axis.XZ,
        colorFn: ColorSource = ColorSource.of(Color.WHITE), scale: Float = DEFAULT_SCALE,
        stagger: Int = 0, group: ParticleGroup? = null
    ): ParticleGroup {
        val g = group ?: manager.createGroup(center)
        val sp = maxOf(width, height) / (particlesPerAxis.coerceAtLeast(2) - 1)
        val nu = (width / sp).toInt() + 1
        val nv = (height / sp).toInt() + 1
        var placed = 0
        val total = ((nu + 1) * (nv + 1)).coerceAtLeast(1)
        for (iu in 0..nu) {
            for (iv in 0..nv) {
                if (hollow && iu > 0 && iu < nu && iv > 0 && iv < nv) continue
                val u = center.x - width / 2 + iu * sp
                val v = center.y - height / 2 + iv * sp
                val t = placed.toDouble() / total
                val pos = when (axis) {
                    Axis.XZ -> Vec3(u, center.y, center.z - height / 2 + iv * sp)
                    Axis.XY -> Vec3(u, v, center.z)
                    Axis.YZ -> Vec3(center.x, u, v)
                }
                place(manager, g, pos, colorFn.colorAt(t), scale, placed++, stagger)
            }
        }
        return g
    }

    /* =====================================================================
     * 3D 图形
     * ===================================================================== */

    /**
     * 斐波那契球面分布的粒子球体，默认彩虹渐变（顶部到底部）。
     *
     * @param count 粒子数量
     */
    @JvmStatic
    @JvmOverloads
    fun sphere(
        manager: ParticleManager, center: Vec3, radius: Double, count: Int,
        colorFn: ColorSource = ColorSource.of(Color.WHITE), scale: Float = DEFAULT_SCALE,
        stagger: Int = 0, group: ParticleGroup? = null
    ): ParticleGroup {
        val g = group ?: manager.createGroup(center)
        val phi = PI * (3.0 - sqrt(5.0))
        for (i in 0 until count) {
            val y = 1.0 - (i.toDouble() / maxOf(1, count - 1)) * 2.0
            val r = sqrt(1.0 - y * y)
            val theta = phi * i
            val x = cos(theta) * r * radius
            val z = sin(theta) * r * radius
            place(manager, g, Vec3(center.x + x, center.y + y * radius, center.z + z),
                colorFn.colorAt((1.0 - y) / 2.0), scale, i, stagger)
        }
        return g
    }

    /**
     * 3D 长方体粒子网格。
     *
     * @param particlesPerAxis 每条边期望的粒子数（推导网格间距）
     * @param hollow 为 true 时只绘制表面，否则填充内部
     */
    @JvmStatic
    @JvmOverloads
    fun cuboid(
        manager: ParticleManager, center: Vec3,
        width: Double, height: Double, depth: Double,
        particlesPerAxis: Int = 12, hollow: Boolean = true,
        colorFn: ColorSource = ColorSource.of(Color.WHITE), scale: Float = DEFAULT_SCALE,
        stagger: Int = 0, group: ParticleGroup? = null
    ): ParticleGroup {
        val g = group ?: manager.createGroup(center)
        val step = particlesPerAxis.coerceAtLeast(2)
        val sp = maxOf(width, height, depth) / (step - 1)
        val nx = (width / sp).toInt() + 1
        val ny = (height / sp).toInt() + 1
        val nz = (depth / sp).toInt() + 1
        var placed = 0
        val total = ((nx + 1) * (ny + 1) * (nz + 1)).coerceAtLeast(1)
        for (ix in 0..nx) {
            val x = center.x - width / 2 + ix * sp
            for (iy in 0..ny) {
                val y = center.y - height / 2 + iy * sp
                for (iz in 0..nz) {
                    val z = center.z - depth / 2 + iz * sp
                    if (hollow && ix > 0 && ix < nx && iy > 0 && iy < ny && iz > 0 && iz < nz) continue
                    place(manager, g, Vec3(x, y, z), colorFn.colorAt(placed.toDouble() / total), scale, placed++, stagger)
                }
            }
        }
        return g
    }

    /* =====================================================================
     * 内部工具
     * ===================================================================== */

    /** 放置一个粒子；[index] × [stagger] 大于 0 时调度延迟出现（波浪入场）。 */
    private fun place(
        manager: ParticleManager, group: ParticleGroup, pos: Vec3,
        color: Color, scale: Float, index: Int, stagger: Int
    ) {
        if (stagger <= 0 || index == 0) {
            spawnInto(manager, group, pos, color, scale)
            return
        }
        AnimationScheduler.schedule(index * stagger) {
            spawnInto(manager, group, pos, color, scale)
        }
    }

    private fun spawnInto(manager: ParticleManager, group: ParticleGroup, pos: Vec3, color: Color, scale: Float) {
        manager.create()
            .position(pos)
            .color(color)
            .scale(scale)
            .lifetime(-1)
            .group(group.id)
            .offsetFromPivot(pos.subtract(group.pivot)) // 组变换（旋转/缩放）依赖该偏移
            .spawn()
    }

    /** 平面坐标 → 世界坐标：把平面内 (u, v) 放到以 [c] 为中心的对应平面。 */
    private fun axisPoint(c: Vec3, axis: Axis, u: Double, v: Double): Vec3 = when (axis) {
        Axis.XZ -> Vec3(c.x + u, c.y, c.z + v)
        Axis.XY -> Vec3(c.x + u, c.y + v, c.z)
        Axis.YZ -> Vec3(c.x, c.y + u, c.z + v)
    }

    /**
     * 描述 2D 图形所绘制的平面。
     */
    enum class Axis {
        /** 水平面（Y 为法线，图形铺在 XZ 平面） */
        XZ,
        /** 朝 Z 方向的垂直面（图形铺在 XY 平面） */
        XY,
        /** 朝 X 方向的垂直面（图形铺在 YZ 平面） */
        YZ
    }
}
