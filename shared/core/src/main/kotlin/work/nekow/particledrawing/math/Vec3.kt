package work.nekow.particledrawing.math

import kotlin.math.sqrt

/** 世界坐标和模拟向量；宿主在入口和帧提交处转换原生向量。 */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    fun add(other: Vec3) = add(other.x, other.y, other.z)
    fun add(x: Double, y: Double, z: Double) = Vec3(this.x + x, this.y + y, this.z + z)
    fun subtract(other: Vec3) = Vec3(x - other.x, y - other.y, z - other.z)
    fun subtract(x: Double, y: Double, z: Double) = Vec3(this.x - x, this.y - y, this.z - z)
    fun scale(value: Double) = Vec3(x * value, y * value, z * value)
    fun multiply(x: Double, y: Double, z: Double) = Vec3(this.x * x, this.y * y, this.z * z)
    fun multiply(other: Vec3) = multiply(other.x, other.y, other.z)
    fun dot(other: Vec3) = x * other.x + y * other.y + z * other.z
    fun cross(other: Vec3) = Vec3(y * other.z - z * other.y, z * other.x - x * other.z, x * other.y - y * other.x)
    fun lengthSqr() = x * x + y * y + z * z
    fun length() = sqrt(lengthSqr())
    fun horizontalDistanceSqr() = x * x + z * z
    fun horizontalDistance() = sqrt(horizontalDistanceSqr())
    fun distanceTo(other: Vec3) = subtract(other).length()
    fun distanceToSqr(other: Vec3) = subtract(other).lengthSqr()
    fun normalize(): Vec3 {
        val length = length()
        return if (length < 1.0E-4) ZERO else Vec3(x / length, y / length, z / length)
    }
    fun lerp(other: Vec3, partial: Double) = Vec3(
        x + (other.x - x) * partial, y + (other.y - y) * partial, z + (other.z - z) * partial,
    )

    companion object { @JvmField val ZERO = Vec3(0.0, 0.0, 0.0) }
}
