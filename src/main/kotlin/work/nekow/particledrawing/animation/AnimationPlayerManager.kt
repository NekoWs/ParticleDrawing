package work.nekow.particledrawing.animation

import net.minecraft.server.level.ServerPlayer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 管理各维度活跃的动画播放器。
 */
object AnimationPlayerManager {

    private val byDimension = ConcurrentHashMap<UUID, MutableList<AnimationPlayer>>()

    /** 在指定维度启动一个动画播放器。 */
    @JvmStatic
    fun start(dimensionId: UUID, player: AnimationPlayer) {
        byDimension.getOrPut(dimensionId) { CopyOnWriteArrayList() }.add(player)
    }

    /** 推进指定维度所有动画一 tick，移除已结束的播放器。 */
    @JvmStatic
    fun tick(dimensionId: UUID, players: Collection<ServerPlayer>) {
        val list = byDimension[dimensionId] ?: return
        val it = list.iterator()
        while (it.hasNext()) {
            val p = it.next()
            if (!p.tick(players)) it.remove()
        }
    }

    /** 停止并清理指定维度的全部动画。 */
    @JvmStatic
    fun stopAll(dimensionId: UUID, players: Collection<ServerPlayer>) {
        val list = byDimension.remove(dimensionId) ?: return
        for (p in list) p.stop(players)
    }
}
