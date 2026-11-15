package work.nekow.particledrawing.math

/** 起点到目标的最短角差，恰好半圈时取 −180°。 */
internal fun shortestAngleDeltaDegrees(from: Double, to: Double): Double {
    val delta = (to % 360.0 - from % 360.0) % 360.0
    return when {
        delta >= 180.0 -> delta - 360.0
        delta < -180.0 -> delta + 360.0
        else -> delta
    }
}

/** 先绕 X 轴转 pitch，再绕 Y 轴转负 yaw；局部偏移沿用实体朝向的 float 精度。 */
internal inline fun <T> rotateEntityOffset(
    yawDeg: Float, pitchDeg: Float,
    ox: Double, oy: Double, oz: Double,
    result: (Float, Float, Float) -> T,
): T {
    val yaw = -Math.toRadians(yawDeg.toDouble()).toFloat()
    val pitch = Math.toRadians(pitchDeg.toDouble()).toFloat()
    val cy = kotlin.math.cos(yaw)
    val sy = kotlin.math.sin(yaw)
    val cx = kotlin.math.cos(pitch)
    val sx = kotlin.math.sin(pitch)
    val x = ox.toFloat()
    val y = oy.toFloat()
    val z = oz.toFloat()
    val y1 = y * cx - z * sx
    val z1 = y * sx + z * cx
    return result(x * cy + z1 * sy, y1, -x * sy + z1 * cy)
}
