@file:JvmName("MotionMath")

package work.nekow.particledrawing.core.motion

import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.sin

/**
 * 绕单位轴旋转向量（Rodrigues 旋转公式）。
 * 返回新向量，不修改原向量；[unitAxis] 需为单位向量。
 */
fun Vec3.rotateAround(unitAxis: Vec3, radians: Double): Vec3 {
    val c = cos(radians)
    val s = sin(radians)
    val dot = dot(unitAxis)
    val cross = unitAxis.cross(this)
    return Vec3(
        x * c + cross.x * s + unitAxis.x * dot * (1 - c),
        y * c + cross.y * s + unitAxis.y * dot * (1 - c),
        z * c + cross.z * s + unitAxis.z * dot * (1 - c)
    )
}
