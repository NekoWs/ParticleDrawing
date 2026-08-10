package work.nekow.particledrawing.core.server

import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.Vec3
import java.util.HashMap
import java.util.UUID

@Suppress("unused")
class ParticleVisibilityManager {

    private val playerStates: MutableMap<UUID, PlayerVisibilityState> = HashMap()

    fun updateVisibility(allParticles: Collection<ParticleData>,
                         players: Collection<ServerPlayer>, radius: Double) {
        for (player in players) {
            val state = playerStates.computeIfAbsent(player.uuid) { PlayerVisibilityState() }
            state.update(player)
        }
    }

    fun isWithinRange(player: ServerPlayer, particlePos: Vec3, radius: Double): Boolean {
        val playerPos = player.position()
        val dx = playerPos.x - particlePos.x
        val dy = playerPos.y - particlePos.y
        val dz = playerPos.z - particlePos.z
        return (dx * dx + dy * dy + dz * dz) <= (radius * radius)
    }

    fun isVisible(player: ServerPlayer, particlePos: Vec3, radius: Double): Boolean {
        return isWithinRange(player, particlePos, radius)
    }

    private class PlayerVisibilityState {
        var lastPosition: Vec3 = Vec3.ZERO

        fun update(player: ServerPlayer) {
            lastPosition = player.position()
        }
    }
}
