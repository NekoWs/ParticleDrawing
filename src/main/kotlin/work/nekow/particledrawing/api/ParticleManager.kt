package work.nekow.particledrawing.api

import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.core.server.ServerParticleEngine
import work.nekow.particledrawing.util.ParticleUtils
import java.util.UUID

/**
 * Entry point for creating and managing particles.
 * Provides both high-level drawing utilities and low-level particle control.
 *
 * Usage:
 * ```
 * val manager = ParticleManager.of(serverLevel)
 * val handle = manager.create()
 *     .style(ParticleStyle.DUST)
 *     .position(0.0, 64.0, 0.0)
 *     .color(Color.RED)
 *     .lifetime(100)
 *     .spawn()
 *
 * val circle = Draw.circle(manager, center, 5.0, 64)
 * circle.rotate(Vec3.Z, Math.PI / 4, EasingType.EASE_IN_OUT.duration(40))
 * ```
 */
@Suppress("unused")
class ParticleManager private constructor(val level: ServerLevel) {

    val dimensionId: UUID = ParticleUtils.dimensionUUID(level)

    init {
        ServerParticleEngine.getOrCreate(dimensionId)
    }

    /**
     * Creates a new particle builder.
     */
    fun create() = ParticleHandle.Builder(this)

    /**
     * Creates a new empty particle group.
     */
    fun createGroup(pivot: Vec3): ParticleGroup {
        val groupId = UUID.randomUUID()
        val engine = getEngine()
        engine.createGroup(groupId, pivot)
        return ParticleGroup(groupId, pivot, this)
    }

    /**
     * Retrieves an existing group.
     */
    fun getGroup(groupId: UUID): ParticleGroup? {
        val engine = getEngine()
        val groupData = engine.getGroup(groupId) ?: return null
        return ParticleGroup(groupId, groupData.pivot(), this)
    }

    fun getEngine() = ServerParticleEngine.getOrCreate(dimensionId)

    internal fun getPlayers(): Collection<ServerPlayer> = level.players()

    companion object {
        fun of(level: ServerLevel) = ParticleManager(level)

        fun of(level: Level): ParticleManager {
            if (level !is ServerLevel) {
                throw IllegalArgumentException("ParticleManager requires a ServerLevel")
            }
            return ParticleManager(level)
        }
    }
}
