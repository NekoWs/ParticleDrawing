package work.nekow.particledrawing.core.motion.algorithms

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.api.Color
import work.nekow.particledrawing.core.motion.MotionAlgorithm
import work.nekow.particledrawing.core.motion.at
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 涡旋算法：粒子绕轴心螺旋内卷，卷到中心后从外缘循环再生；
 * 叠加向外扩散的波纹、差分旋转与螺旋色相。
 *
 * 参数布局（DoubleArray，缺省值见括号）：
 * ```
 * [0]  spin      基准角速度 rad/s（默认 1.2）
 * [1]  falloff   角速度随半径衰减系数，实际角速度 = spin/(1 + r*falloff)（默认 0.25）
 * [2]  inflow    径向内卷速度 blocks/s（默认 0.6）
 * [3]  waveFreq  波纹空间频率（默认 2.5）
 * [4]  waveSpeed 波纹相位速度，负值波纹向外扩散（默认 -3.2）
 * [5]  amp       波纹振幅 blocks（默认 0.55）
 * [6]  maxR      外缘半径 blocks（默认 5.5）
 * [7]  hueBase   基础色相（默认 0.5）
 * [8]  hueSpan   色相沿角度跨度（默认 0.35）
 * [9..11] axis   旋转轴（默认 Y 轴 0,1,0）
 * ```
 */
class VortexAlgorithm(params: DoubleArray) : MotionAlgorithm {
    override val id = ID

    private val spin = params.at(0, 1.2)
    private val falloff = params.at(1, 0.25)
    private val inflow = params.at(2, 0.6)
    private val waveFreq = params.at(3, 2.5)
    private val waveSpeed = params.at(4, -3.2)
    private val amp = params.at(5, 0.55)
    private val maxR = params.at(6, 5.5)
    private val hueBase = params.at(7, 0.5)
    private val hueSpan = params.at(8, 0.35)
    private val minR = 0.3

    /** 涡旋平面正交基：u、v 张成与 axis 垂直的平面。 */
    private val axis: Vec3
    private val u: Vec3
    private val v: Vec3

    init {
        val n = Vec3(params.at(9, 0.0), params.at(10, 1.0), params.at(11, 0.0)).normalize()
        axis = if (n == Vec3.ZERO) Vec3(0.0, 1.0, 0.0) else n
        val ref = if (abs(axis.x) < 0.9) Vec3(1.0, 0.0, 0.0) else Vec3(0.0, 0.0, 1.0)
        u = axis.cross(ref).normalize()
        v = axis.cross(u)
    }

    override fun compute(basePos: Vec3, pivot: Vec3, elapsedSeconds: Double, target: Vec3?): MotionAlgorithm.Result {
        val rel = basePos.subtract(pivot)
        val height = rel.dot(axis)
        val ru = rel.dot(u)
        val rv = rel.dot(v)
        val r0 = sqrt(ru * ru + rv * rv)

        // 中心粒子: 沿轴浮动 + 呼吸缩放
        if (r0 < 0.2) {
            val s = sin(elapsedSeconds * 3.0)
            return MotionAlgorithm.Result(
                position = pivot.add(axis.scale(s * 0.25)),
                scale = (0.45 + 0.35 * s).toFloat()
            )
        }

        val span = maxR - minR
        val r = maxR - ((elapsedSeconds * inflow + (maxR - r0)) % span)
        val theta = atan2(rv, ru) + elapsedSeconds * spin / (1.0 + r * falloff)
        val wave = sin(r * waveFreq + elapsedSeconds * waveSpeed)
        val fade = (1.0 - r / maxR).coerceIn(0.0, 1.0)
        val y = height + wave * amp * fade

        val hue = ((hueBase + hueSpan * theta / (2.0 * PI)) % 1.0).toFloat()
        val brightness = (0.45 + 0.45 * fade + 0.1 * wave).coerceIn(0.2, 1.0).toFloat()

        val plane = u.scale(cos(theta) * r).add(v.scale(sin(theta) * r))
        return MotionAlgorithm.Result(
            position = pivot.add(plane).add(axis.scale(y)),
            color = Color.ofHsb(hue, 0.85f, brightness),
            scale = (0.18 + 0.14 * (0.5 + 0.5 * wave) + 0.1 * fade).toFloat()
        )
    }

    companion object {
        const val ID = "vortex"
    }
}
