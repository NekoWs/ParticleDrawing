package work.nekow.particledrawing.core.client

import org.joml.Quaternionf
import work.nekow.particledrawing.math.shortestAngleDeltaDegrees

/** 朝向的相邻两个刻端点，渲染时按分数刻取最短角差。 */
internal class MorphOrientation(yaw: Double, pitch: Double) {
    private var previousYaw = yaw
    private var previousPitch = pitch
    private var yaw = yaw
    private var pitch = pitch
    private val angles = DoubleArray(3)
    private val rotation = Quaternionf()
    private val combined = Quaternionf()

    fun set(yaw: Double?, pitch: Double?, snap: Boolean) {
        previousYaw = if (snap) yaw ?: this.yaw else this.yaw
        previousPitch = if (snap) pitch ?: this.pitch else this.pitch
        this.yaw = yaw ?: this.yaw
        this.pitch = pitch ?: this.pitch
    }

    fun yawAt(fraction: Float): Double = interpolate(previousYaw, yaw, fraction)
    fun pitchAt(fraction: Float): Double = interpolate(previousPitch, pitch, fraction)

    fun rotationAt(fraction: Float, roll: Double, local: Boolean, camera: Quaternionf?): Quaternionf {
        angles[0] = pitchAt(fraction)
        angles[1] = yawAt(fraction)
        angles[2] = roll
        particleOrientationQuaternion(angles, local, rotation)
        return if (camera == null) rotation else combined.set(camera).mul(rotation)
    }

    private fun interpolate(from: Double, to: Double, fraction: Float): Double =
        from % 360.0 + shortestAngleDeltaDegrees(from, to) * fraction
}

internal fun particleOrientationQuaternion(spinDeg: DoubleArray, spinLocal: Boolean, out: Quaternionf) {
    val rx = Math.toRadians(spinDeg[0]).toFloat()
    val ry = Math.toRadians(spinDeg[1]).toFloat()
    val rz = Math.toRadians(spinDeg[2]).toFloat()
    if (spinLocal) {
        out.rotationXYZ(rx, ry, rz)
    } else {
        out.rotationZ(rz).mul(Quaternionf().rotationY(ry)).mul(Quaternionf().rotationX(rx))
    }
}
