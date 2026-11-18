package work.nekow.particledrawing.api

import work.nekow.particledrawing.animation.script.noise3D
import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.i18n.translation
import work.nekow.particledrawing.math.Vec3
import kotlin.math.abs
import kotlin.math.pow

enum class ForceFieldKind { WIND, ATTRACT, VORTEX, TURBULENCE }

/** 球形影响范围内的加速度，坐标和距离均使用世界格。 */
data class ParticleForceField(
    val kind: ForceFieldKind,
    val radius: Double,
    val strength: Double,
    val direction: Vec3 = Vec3(0.0, 1.0, 0.0),
    val falloff: Double = 1.0,
    val seed: Int = 0,
) {
    init {
        requireLocalized(radius in 0.05..128.0 && abs(strength) <= 4.0 && falloff in 0.0..8.0) {
            translation("error.field.parameters")
        }
        requireLocalized(direction.x.isFinite() && direction.y.isFinite() && direction.z.isFinite()) {
            translation("error.field.direction")
        }
        if (kind == ForceFieldKind.WIND || kind == ForceFieldKind.VORTEX) {
            requireLocalized(direction.lengthSqr() >= 1e-8 && direction.lengthSqr().isFinite()) {
                translation("error.field.direction")
            }
        }
    }

    /** 位移从力场中心指向采样点。吸引为正，排斥为负。 */
    fun acceleration(offset: Vec3, worldDirection: Vec3 = direction, tick: Long = 0): Vec3 {
        val distanceSquared = offset.lengthSqr()
        if (distanceSquared >= radius * radius || strength == 0.0) return Vec3.ZERO
        val distance = kotlin.math.sqrt(distanceSquared)
        val weight = (1.0 - distance / radius).pow(falloff) * strength
        val unit = if (distance > 1e-8) offset.scale(1.0 / distance) else Vec3.ZERO
        val vector = when (kind) {
            ForceFieldKind.WIND -> worldDirection.normalize()
            ForceFieldKind.ATTRACT -> unit.scale(-1.0)
            ForceFieldKind.VORTEX -> worldDirection.cross(unit).normalize()
            ForceFieldKind.TURBULENCE -> {
                val t = (tick % 100_000L) * 0.035
                val q = offset.scale(0.45)
                Vec3(
                    noise3D(q.x + t, q.y, q.z, seed),
                    noise3D(q.x, q.y + t, q.z, seed xor 0x631a2),
                    noise3D(q.x, q.y, q.z + t, seed xor 0x362ce),
                )
            }
        }
        return vector.scale(weight)
    }

    companion object {
        const val MAX_FIELDS = 32
    }
}
