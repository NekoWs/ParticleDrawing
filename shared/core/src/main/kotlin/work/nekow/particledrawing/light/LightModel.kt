package work.nekow.particledrawing.light

import work.nekow.particledrawing.math.Vec3
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 光照衰减曲线：`t = 距离 / 半径`，取值都在 t=0 时是 1、t=1 时是 0（反比类在 t=1 时截断）。
 */
enum class LightFalloff {
    /** 恒定：整段范围内一样亮，边界硬切。 */
    CONSTANT,

    /** 线性：目前行为，最省也最平。 */
    LINEAR,

    /** 平滑：贴边衰减更快，中心更饱满。 */
    SMOOTH,

    /** 反比：近处强烈、远处拖尾，像真实灯泡。 */
    INVERSE,

    /** 反比平方：物理感最强，衰减最陡。 */
    INVERSE_SQUARE,
    ;

    companion object {
        fun byName(name: String): LightFalloff? = when (name.lowercase()) {
            "constant", "flat", "none" -> CONSTANT
            "linear" -> LINEAR
            "smooth", "smoothstep" -> SMOOTH
            "inverse", "falloff" -> INVERSE
            "inverse_square", "inverse-square", "physical" -> INVERSE_SQUARE
            else -> null
        }
    }
}

/**
 * 一个动态光源在这一帧的样子（纯数据，平台无关）。
 *
 * 位置用世界坐标；[dirX]/[dirY]/[dirZ] 是射灯轴向（长度 0 表示点光，向四面八方照）；
 * [innerCos]/[outerCos] 是内外锥夹角的余弦，内锥内是满亮、外锥外为 0，中间按 [edge] 过渡。
 * 颜色单独保存，亮度等级仍按 0-15 的老口径给出，方便直接写光图。
 */
class LightEmission(
    val x: Double,
    val y: Double,
    val z: Double,
    val luminance: Double,
    val range: Double,
    val dirX: Double = 0.0,
    val dirY: Double = 0.0,
    val dirZ: Double = 0.0,
    val innerCos: Double = 1.0,
    val outerCos: Double = -1.0,
    val falloff: LightFalloff = LightFalloff.LINEAR,
    /** 边缘过渡的锐度：1 = 平滑过渡，越大越接近硬边。 */
    val edge: Double = 1.0,
    val red: Double = 1.0,
    val green: Double = 1.0,
    val blue: Double = 1.0,
) {
    /** 是否带方向（射灯）。轴向为零向量时退回点光。 */
    val isSpot: Boolean get() = dirX != 0.0 || dirY != 0.0 || dirZ != 0.0
}

/**
 * 光照求值的全部数学：衰减、锥体、颜色合成。
 *
 * 这里只做纯函数计算，不碰渲染与网络；平台层拿结果去写光图或喂给第三方光照管线。
 */
object LightMath {

    /** 几度转成余弦，方便把「锥角 30°」直接变成比较值。 */
    fun cosOfDegrees(degrees: Double): Double {
        val rad = Math.toRadians(degrees.coerceIn(0.0, 180.0))
        return Math.cos(rad)
    }

    /** 由偏航/俯仰（度）得到单位轴向：偏航绕 Y，俯仰向上为正。 */
    fun directionOf(yawDeg: Double, pitchDeg: Double): Vec3 {
        val yaw = Math.toRadians(yawDeg)
        val pitch = Math.toRadians(pitchDeg.coerceIn(-90.0, 90.0))
        val cosPitch = Math.cos(pitch)
        return Vec3(
            -Math.sin(yaw) * cosPitch,
            Math.sin(pitch),
            Math.cos(yaw) * cosPitch,
        )
    }

    /**
     * 距离衰减：t=0 得 1，t>=1 得 0；反比类在半径处截断，不会留一条永远不灭的尾巴。
     */
    fun attenuation(distance: Double, range: Double, falloff: LightFalloff): Double {
        if (range <= 0.0) return 0.0
        if (distance <= 0.0) return 1.0
        if (distance >= range) return 0.0
        val t = distance / range
        return when (falloff) {
            LightFalloff.CONSTANT -> 1.0
            LightFalloff.LINEAR -> 1.0 - t
            LightFalloff.SMOOTH -> {
                val u = 1.0 - t * t
                u * u
            }
            // 反比类在 t=1 处并不为 0，乘一个端点归零的窗口，避免半径边界出现亮边
            LightFalloff.INVERSE -> (1.0 / (1.0 + 4.0 * t)) * (1.0 - t)
            LightFalloff.INVERSE_SQUARE -> (1.0 / (1.0 + 16.0 * t * t)) * (1.0 - t)
        }
    }

    /**
     * 锥体权重：内锥内 1、外锥外 0，中间按 [LightEmission.edge] 过渡。
     *
     * 点光（轴向为零）恒为 1；朝向与视线相反的背面不会漏光。
     */
    fun coneFactor(light: LightEmission, ox: Double, oy: Double, oz: Double): Double =
        coneFactorOf(ox, oy, oz, light.dirX, light.dirY, light.dirZ, light.innerCos, light.outerCos, light.edge)

    /**
     * 单盏灯在某点的标量贡献（0 到 [LightEmission.luminance]）。
     *
     * 超出范围或落在锥外直接是 0，调用方不必先做裁剪。
     */
    fun contribution(light: LightEmission, px: Double, py: Double, pz: Double): Double {
        return contributionOf(
            px, py, pz,
            light.x, light.y, light.z,
            light.luminance, light.range,
            light.dirX, light.dirY, light.dirZ,
            light.innerCos, light.outerCos,
            light.falloff, light.edge,
        )
    }

    /**
     * 无分配的求值入口：参数全是原始类型，热路径（逐顶点查询光图）直接用它，
     * 不必为每盏灯构造 [LightEmission]。
     */
    fun contributionOf(
        px: Double, py: Double, pz: Double,
        lx: Double, ly: Double, lz: Double,
        luminance: Double, range: Double,
        dirX: Double, dirY: Double, dirZ: Double,
        innerCos: Double, outerCos: Double,
        falloff: LightFalloff, edge: Double,
        sampleRadius: Double = 0.0,
    ): Double {
        val ox = px - lx
        val oy = py - ly
        val oz = pz - lz
        val distSq = ox * ox + oy * oy + oz * oz
        if (distSq >= range * range) return 0.0
        val fall = attenuation(sqrt(distSq), range, falloff)
        if (fall <= 0.0) return 0.0
        val cone = coneFactorOf(ox, oy, oz, dirX, dirY, dirZ, innerCos, outerCos, edge, sampleRadius)
        if (cone <= 0.0) return 0.0
        return luminance * fall * cone
    }

    /** [sampleRadius] 为采样半径，0 时按单点精确求值。 */
    fun coneFactorOf(
        ox: Double, oy: Double, oz: Double,
        dirX: Double, dirY: Double, dirZ: Double,
        innerCos: Double, outerCos: Double, edge: Double,
        sampleRadius: Double = 0.0,
    ): Double {
        if (dirX == 0.0 && dirY == 0.0 && dirZ == 0.0) return 1.0
        val len = sqrt(ox * ox + oy * oy + oz * oz)
        if (len <= 1e-9) return 1.0
        val dirLen = sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ)
        if (dirLen <= 1e-9) return 1.0
        val cosA = (ox * dirX + oy * dirY + oz * dirZ) / (len * dirLen)
        // 方块顶点之间约隔一格。临近锥边时把小于采样间距的过渡带加宽。
        // 余弦的变化率为 sin(角度) / 距离，因此远处使用更小的角度补偿。
        val width = innerCos - outerCos
        val padding = if (sampleRadius > 0.0) {
            val sinOuter = sqrt(max(0.0, 1.0 - outerCos * outerCos))
            max(0.0, sampleRadius * sinOuter / len - width * 0.5)
        } else 0.0
        val filteredWidth = width + 2.0 * padding
        if (filteredWidth <= 0.0) return if (cosA >= innerCos) 1.0 else 0.0
        val raw = ((cosA - outerCos + padding) / filteredWidth).coerceIn(0.0, 1.0)
        val smoothed = raw * raw * (3.0 - 2.0 * raw)
        val e = if (edge < 1e-3) 1e-3 else edge
        return if (e == 1.0) smoothed else Math.pow(smoothed, e)
    }

    /**
     * 多盏灯的合成亮度和颜色。
     *
     * 亮度取各灯贡献的最大值（与原版光图语义一致，不会叠加过曝）；
     * 颜色按贡献加权平均写进 [outRgb]（长度至少 3），没有光时写 0。
     */
    fun combine(lights: List<LightEmission>, px: Double, py: Double, pz: Double, outRgb: DoubleArray): Double {
        var best = 0.0
        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        var sumWeight = 0.0
        for (light in lights) {
            val c = contribution(light, px, py, pz)
            if (c <= 0.0) continue
            if (c > best) best = c
            sumR += c * light.red
            sumG += c * light.green
            sumB += c * light.blue
            sumWeight += c
        }
        if (outRgb.size >= 3) {
            if (sumWeight > 0.0) {
                outRgb[0] = (sumR / sumWeight).coerceIn(0.0, 1.0)
                outRgb[1] = (sumG / sumWeight).coerceIn(0.0, 1.0)
                outRgb[2] = (sumB / sumWeight).coerceIn(0.0, 1.0)
            } else {
                outRgb[0] = 0.0
                outRgb[1] = 0.0
                outRgb[2] = 0.0
            }
        }
        return min(best, 15.0)
    }

    /**
     * 射灯在地面上的光斑半径估算：给定光源到目标平面的距离，返回外锥在平面上的半径。
     *
     * 用于让地形重建范围贴着实际光斑，而不是一律按半径铺满整个立方体。
     */
    fun spotFootprintRadius(light: LightEmission, distanceToPlane: Double): Double {
        if (!light.isSpot || distanceToPlane <= 0.0) return 0.0
        val cosOuter = light.outerCos.coerceIn(-1.0, 1.0)
        if (cosOuter <= 0.0) return Double.MAX_VALUE
        val tanOuter = sqrt(max(0.0, 1.0 - cosOuter * cosOuter)) / cosOuter
        return distanceToPlane * tanOuter
    }

    /** 把亮度等级映射成 0-1 的归一化强度（光图口径 15 级）。 */
    fun normalized(luminance: Double): Double = (luminance / 15.0).coerceIn(0.0, 1.0)
}
