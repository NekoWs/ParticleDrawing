package work.nekow.particledrawing.core.server

import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.Vec3

/**
 * 粒子可见性判定，基于距离判断粒子是否对玩家可见。
 */
object ParticleVisibilityManager {

    /**
     * 判断玩家与粒子之间的欧氏距离是否在给定半径内。
     * @param player 目标玩家
     * @param particlePos 粒子世界坐标
     * @param radius 可见半径（格）
     * @return 在范围内返回 true
     */
    fun isWithinRange(player: ServerPlayer, particlePos: Vec3, radius: Double): Boolean {
        val dx = player.x - particlePos.x
        val dy = player.y - particlePos.y
        val dz = player.z - particlePos.z
        return dx * dx + dy * dy + dz * dz <= radius * radius
    }
}
