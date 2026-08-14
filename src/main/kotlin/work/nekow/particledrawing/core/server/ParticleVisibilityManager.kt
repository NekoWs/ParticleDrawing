package work.nekow.particledrawing.core.server

import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.Vec3

/**
 * 粒子可见性判定，基于玩家当前的渲染距离判断粒子是否对玩家可见。
 */
object ParticleVisibilityManager {

    /**
     * 判断玩家与粒子之间的欧氏距离是否在玩家渲染距离内。
     * @param player 目标玩家
     * @param particlePos 粒子世界坐标
     * @return 在渲染距离内返回 true
     */
    @JvmStatic
    fun isWithinViewDistance(player: ServerPlayer, particlePos: Vec3): Boolean {
        val chunks = player.requestedViewDistance().coerceAtLeast(2)
        val radius = chunks * 16.0
        val dx = player.x - particlePos.x
        val dy = player.y - particlePos.y
        val dz = player.z - particlePos.z
        return dx * dx + dy * dy + dz * dz <= radius * radius
    }
}
