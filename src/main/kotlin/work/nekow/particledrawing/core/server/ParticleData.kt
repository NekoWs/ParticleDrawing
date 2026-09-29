package work.nekow.particledrawing.core.server

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.api.Color
import work.nekow.particledrawing.util.AttachMath
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

    // 加速度（力）：>0 = 还有这么多 tick；<0 = 无限；0 = 无
    private var acceleration: Vec3 = Vec3.ZERO
    private var accelTicks: Int = 0

    // 实体锚点：非 -1 时位置每 tick 由锚点解析，速度与力清空
    private var attachEntityId: Int = AttachMath.noEntity()
    private var attachOffset: Vec3 = Vec3.ZERO
    private var attachLocal: Boolean = false

    fun position(): Vec3 = position
    fun color(): Color = color
    fun scale(): Float = scale
    fun lifetime(): Int = lifetime
    fun glowing(): Boolean = glowing
    fun lightLevel(): Int = lightLevel
    fun offsetFromPivot(): Vec3 = offsetFromPivot
    fun velocity(): Vec3 = velocity
    fun hasVelocity(): Boolean = velocity.x != 0.0 || velocity.y != 0.0 || velocity.z != 0.0
    fun hasActiveForce(): Boolean = accelTicks != 0
    fun attachedEntityId(): Int = attachEntityId
    fun attachOffset(): Vec3 = attachOffset
    fun attachLocal(): Boolean = attachLocal

    fun setPosition(position: Vec3) { this.position = position }
    fun setColor(color: Color) { this.color = color }
    fun setScale(scale: Float) { this.scale = scale }
    fun setLifetime(lifetime: Int) { this.lifetime = lifetime }
    fun setGlowing(glowing: Boolean) { this.glowing = glowing }
    fun setLightLevel(lightLevel: Int) { this.lightLevel = lightLevel.coerceIn(0, 15) }
    fun setOffsetFromPivot(offset: Vec3) { this.offsetFromPivot = offset }
    fun setVelocity(velocity: Vec3) { this.velocity = velocity }

    /**
     * 设置加速度（力）与施加 tick 数。
     * @param ticks >0 = 施加这么多 tick；<0 = 无限（直到被下一次力/速度/位置指令覆盖）；0 = 清除
     */
    fun setAcceleration(acceleration: Vec3, ticks: Int) {
        this.acceleration = acceleration
        this.accelTicks = ticks
    }

    /** 挂到实体上：位置交给锚点解析，速度与力一并清零。 */
    fun attach(entityId: Int, offset: Vec3, local: Boolean) {
        attachEntityId = entityId
        attachOffset = offset
        attachLocal = local
        velocity = Vec3.ZERO
        acceleration = Vec3.ZERO
        accelTicks = 0
    }

    /** 解除实体锚点：位置/速度指令接管时调用。 */
    fun detach() {
        attachEntityId = AttachMath.noEntity()
    }

    /**
     * 推进一个 tick 的运动：先施力（速度 += 加速度），再按速度位移。
     * 与服务端引擎、客户端 `RenderParticle.tick` 同一顺序，两端位置必须一致。
     */
    fun stepMotion() {
        if (accelTicks != 0) {
            velocity = velocity.add(acceleration)
            if (accelTicks > 0) accelTicks--
        }
        if (velocity.x != 0.0 || velocity.y != 0.0 || velocity.z != 0.0) {
            position = position.add(velocity)
        }
    }

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
