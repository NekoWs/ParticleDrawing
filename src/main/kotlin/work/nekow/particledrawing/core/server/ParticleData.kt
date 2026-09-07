package work.nekow.particledrawing.core.server

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.api.Color
import java.util.UUID

// 粒子运行时数据：位置、颜色、缩放、寿命等。
@Suppress("unused")
class ParticleData(
    val id: UUID,
    private var position: Vec3,
    private var color: Color,
    private var scale: Float,
    private var lifetime: Int,
    val maxLifetime: Int,
    val groupId: UUID?,
    private var glowing: Boolean,
    private var lightLevel: Int,
    private var offsetFromPivot: Vec3 = Vec3.ZERO
) {

    private var velocity: Vec3 = Vec3.ZERO

    fun position(): Vec3 = position
    fun color(): Color = color
    fun scale(): Float = scale
    fun lifetime(): Int = lifetime
    fun glowing(): Boolean = glowing
    fun lightLevel(): Int = lightLevel
    fun offsetFromPivot(): Vec3 = offsetFromPivot
    fun velocity(): Vec3 = velocity

    fun setPosition(position: Vec3) { this.position = position }
    fun setColor(color: Color) { this.color = color }
    fun setScale(scale: Float) { this.scale = scale }
    fun setLifetime(lifetime: Int) { this.lifetime = lifetime }
    fun setGlowing(glowing: Boolean) { this.glowing = glowing }
    fun setLightLevel(lightLevel: Int) { this.lightLevel = lightLevel.coerceIn(0, 15) }
    fun setOffsetFromPivot(offset: Vec3) { this.offsetFromPivot = offset }
    fun setVelocity(velocity: Vec3) { this.velocity = velocity }

    fun isExpired(): Boolean = lifetime == 0

    @Suppress("unused")
    fun tick(): Int {
        if (lifetime > 0) {
            lifetime--
        }
        return lifetime
    }

    fun lifeProgress(): Float {
        if (maxLifetime < 0) return 0f
        return 1f - lifetime.toFloat() / maxLifetime
    }

    fun toSnapshot(): ParticleSnapshot {
        return ParticleSnapshot(id, position, color, scale, glowing, lightLevel)
    }

    data class ParticleSnapshot(
        val id: UUID,
        val position: Vec3,
        val color: Color,
        val scale: Float,
        val glowing: Boolean,
        val lightLevel: Int
    )

    companion object {
        fun create(id: UUID, position: Vec3,
                   color: Color, scale: Float, lifetime: Int,
                   groupId: UUID?, glowing: Boolean, lightLevel: Int,
                   offsetFromPivot: Vec3?): ParticleData {
            return ParticleData(id, position, color, scale, lifetime, lifetime,
                groupId, glowing, lightLevel, offsetFromPivot ?: Vec3.ZERO)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ParticleData) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "ParticleData{$id @ $position}"
}
