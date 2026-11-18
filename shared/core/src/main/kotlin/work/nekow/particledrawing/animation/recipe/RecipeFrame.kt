package work.nekow.particledrawing.animation.recipe

import work.nekow.particledrawing.math.Vec3
import work.nekow.particledrawing.math.rotateEntityOffset

/** 同一锚点变换供渲染、碰撞和生命周期事件使用。 */
internal class RecipeFrame(
    private val origin: Vec3,
    yaw: Float = 0f,
    pitch: Float = 0f,
    local: Boolean = false,
) {
    private val xAxis = if (local) axis(yaw, pitch, 1.0, 0.0, 0.0) else Vec3(1.0, 0.0, 0.0)
    private val yAxis = if (local) axis(yaw, pitch, 0.0, 1.0, 0.0) else Vec3(0.0, 1.0, 0.0)
    private val zAxis = if (local) xAxis.cross(yAxis).normalize() else Vec3(0.0, 0.0, 1.0)

    fun position(x: Double, y: Double, z: Double): Vec3 =
        Vec3(worldX(x, y, z), worldY(x, y, z), worldZ(x, y, z))

    fun positionInto(x: Double, y: Double, z: Double, out: DoubleArray) {
        out[0] = worldX(x, y, z)
        out[1] = worldY(x, y, z)
        out[2] = worldZ(x, y, z)
    }

    fun velocity(v: Vec3): Vec3 = xAxis.scale(v.x).add(yAxis.scale(v.y)).add(zAxis.scale(v.z))
    fun localPosition(p: Vec3): Vec3 = localVelocity(p.subtract(origin))
    fun localVelocity(v: Vec3): Vec3 = Vec3(v.dot(xAxis), v.dot(yAxis), v.dot(zAxis))

    private fun worldX(x: Double, y: Double, z: Double) = origin.x + xAxis.x * x + yAxis.x * y + zAxis.x * z
    private fun worldY(x: Double, y: Double, z: Double) = origin.y + xAxis.y * x + yAxis.y * y + zAxis.y * z
    private fun worldZ(x: Double, y: Double, z: Double) = origin.z + xAxis.z * x + yAxis.z * y + zAxis.z * z

    companion object {
        val IDENTITY = RecipeFrame(Vec3.ZERO)

        private fun axis(yaw: Float, pitch: Float, x: Double, y: Double, z: Double): Vec3 =
            rotateEntityOffset(yaw, pitch, x, y, z) { a, b, c -> Vec3(a.toDouble(), b.toDouble(), c.toDouble()).normalize() }
    }
}
