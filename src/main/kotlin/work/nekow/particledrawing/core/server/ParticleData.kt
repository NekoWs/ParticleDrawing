package work.nekow.particledrawing.core.server

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.api.Color
import work.nekow.particledrawing.api.ParticleStyle
import java.util.UUID

@Suppress("unused")
class ParticleData(
    val id: UUID,
    val style: ParticleStyle,
    private var position: Vec3,
    private var color: Color,
    private var scale: Float,
    private var lifetime: Int,
    val maxLifetime: Int,
    val groupId: UUID?,
    private var glowing: Boolean,
    private var offsetFromPivot: Vec3 = Vec3.ZERO
) {

    init {
        this.offsetFromPivot = offsetFromPivot.let { if (it != Vec3.ZERO) it else Vec3.ZERO }
    }

    fun position(): Vec3 = position
    fun color(): Color = color
    fun scale(): Float = scale
    fun lifetime(): Int = lifetime
    fun glowing(): Boolean = glowing
    fun offsetFromPivot(): Vec3 = offsetFromPivot

    fun setPosition(position: Vec3) { this.position = position }
    fun setColor(color: Color) { this.color = color }
    fun setScale(scale: Float) { this.scale = scale }
    fun setLifetime(lifetime: Int) { this.lifetime = lifetime }
    fun setGlowing(glowing: Boolean) { this.glowing = glowing }
    fun setOffsetFromPivot(offset: Vec3) { this.offsetFromPivot = offset }

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
        return ParticleSnapshot(id, style, position, color, scale, glowing)
    }

    data class ParticleSnapshot(
        val id: UUID,
        val style: ParticleStyle,
        val position: Vec3,
        val color: Color,
        val scale: Float,
        val glowing: Boolean
    )

    companion object {
        fun create(id: UUID, style: ParticleStyle, position: Vec3,
                   color: Color, scale: Float, lifetime: Int,
                   groupId: UUID?, glowing: Boolean, offsetFromPivot: Vec3?): ParticleData {
            return ParticleData(id, style, position, color, scale, lifetime, lifetime,
                groupId, glowing, offsetFromPivot ?: Vec3.ZERO)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ParticleData) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "ParticleData{$id $style @ $position}"
}
