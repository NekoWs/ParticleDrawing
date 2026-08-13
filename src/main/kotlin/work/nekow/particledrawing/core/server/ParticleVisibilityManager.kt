package work.nekow.particledrawing.core.server

import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.Vec3

/**
 * 粒子可见性判定，基于距离判断粒子是否对玩家可见。
 */
object ParticleVisibilityManager {

    fun isWithinRange(player: ServerPlayer, particlePos: Vec3, radius: Double): Boolean {
        val dx = player.x - particlePos.x
        val dy = player.y - particlePos.y
        val dz = player.z - particlePos.z
        return dx * dx + dy * dy + dz * dz <= radius * radius
    }
}
