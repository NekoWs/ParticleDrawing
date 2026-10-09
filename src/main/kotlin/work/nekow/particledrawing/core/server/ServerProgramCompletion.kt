package work.nekow.particledrawing.core.server

import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.server.ServerLifecycleHooks
import work.nekow.particledrawing.core.network.StopAnimationProgramPayload
import work.nekow.particledrawing.util.ParticleUtils
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 编排动画的「跑完了」登记表。
 *
 * 服务端**不知道**客户端什么时候收尾（网络延迟、时钟锚点、渲染帧插值都会让它晚一点），
 * 所以凭估算时间去销毁就会提前切掉画面。这里改为等客户端上报（`ProgramCompletePayload`）：
 * - [onComplete]：上报时回调（服务端主线程）；
 * - [retire]：上报后再等若干 tick 销毁组与粒子，销毁时刻因此与「客户端真正到零」对齐。
 *
 * 兜底：注册时按服务端自己的时间轴末端排一个**更晚**的销毁（+[FALLBACK_MARGIN_TICKS]），
 * 客户端不在场或一直不上报时不让组泄漏；它一定晚于合理的客户端完成时刻，不会切掉还在收尾的画面。
 *
 * 线程约定：与服务端粒子引擎一致，只在主线程调用。
 */
object ServerProgramCompletion {

    /** 兜底销毁比客户端完成信号晚多少 tick。 */
    private const val FALLBACK_MARGIN_TICKS = 20

    private class Entry(val dimensionId: UUID) {
        /** 完成时的回调（`onAnimationComplete`）。 */
        var action: ((UUID) -> Unit)? = null

        /** 完成后等多少 tick 销毁（`retire`）；-1 = 不销毁。 */
        var retireGrace: Int = -1

        var done = false
    }

    private val entries = ConcurrentHashMap<UUID, Entry>()

    /** 登记「客户端跑完后回调」；[fallbackTicks] < 0 表示不排兜底。 */
    fun onComplete(groupId: UUID, dimensionId: UUID, fallbackTicks: Int, action: (UUID) -> Unit) {
        val entry = entries.computeIfAbsent(groupId) { Entry(dimensionId) }
        entry.action = action
        scheduleFallback(groupId, fallbackTicks)
    }

    /** 登记「客户端跑完后（+[graceTicks]）销毁整组」；[fallbackTicks] < 0 表示不排兜底。 */
    fun retire(groupId: UUID, dimensionId: UUID, graceTicks: Int, fallbackTicks: Int) {
        val entry = entries.computeIfAbsent(groupId) { Entry(dimensionId) }
        entry.retireGrace = graceTicks.coerceAtLeast(0)
        scheduleFallback(groupId, fallbackTicks)
    }

    /** 客户端上报完成：回调 + 按需排定销毁。一次性（触发后注销）。 */
    fun complete(groupId: UUID) {
        val entry = entries.remove(groupId) ?: return
        if (entry.done) return
        entry.done = true
        fire(groupId, entry)
    }

    /** 组已停止/销毁：注销登记。 */
    fun cancel(groupId: UUID) {
        entries.remove(groupId)
    }

    fun clearDimension(dimensionId: UUID) {
        entries.keys.removeIf { entries[it]?.dimensionId == dimensionId }
    }

    fun clearAll() {
        entries.clear()
    }

    /** 当前登记数（调试用）。 */
    fun size(): Int = entries.size

    private fun scheduleFallback(groupId: UUID, fallbackTicks: Int) {
        if (fallbackTicks < 0) return
        AnimationScheduler.schedule(fallbackTicks + FALLBACK_MARGIN_TICKS) {
            val entry = entries.remove(groupId) ?: return@schedule
            if (entry.done) return@schedule
            entry.done = true
            fire(groupId, entry)
        }
    }

    private fun fire(groupId: UUID, entry: Entry) {
        entry.action?.invoke(groupId)
        if (entry.retireGrace < 0) return
        val grace = entry.retireGrace
        if (grace == 0) {
            destroy(groupId, entry.dimensionId)
        } else {
            AnimationScheduler.schedule(grace) { destroy(groupId, entry.dimensionId) }
        }
    }

    /** 销毁整组粒子，并让客户端停掉这段程序（粒子已经没了，程序不必再跑）。 */
    private fun destroy(groupId: UUID, dimensionId: UUID) {
        val level = levelOf(ServerLifecycleHooks.getCurrentServer(), dimensionId) ?: return
        ServerParticleEngine.get(dimensionId)?.destroyGroup(groupId, level.players())
        val stop = StopAnimationProgramPayload(groupId, destroyParticles = false)
        for (player in level.players()) PacketDistributor.sendToPlayer(player, stop)
    }

    private fun levelOf(server: MinecraftServer?, dimensionId: UUID): ServerLevel? {
        if (server == null) return null
        for (level in server.allLevels) {
            if (ParticleUtils.dimensionUUID(level) == dimensionId) return level
        }
        return null
    }
}
