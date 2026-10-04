package work.nekow.particledrawing.core.server

import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.level.LevelEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import work.nekow.particledrawing.ParticleDrawing
import work.nekow.particledrawing.animation.ServerAnimationManager
import work.nekow.particledrawing.animation.ServerEffectManager
import work.nekow.particledrawing.util.ParticleUtils

/**
 * 服务端 tick 事件处理器，驱动粒子引擎与动画播放器每 tick 更新。
 */
@EventBusSubscriber(modid = ParticleDrawing.MODID)
@Suppress("unused")
object ServerParticleHandler {

    @SubscribeEvent
    @JvmStatic
    fun onServerTick(event: ServerTickEvent.Post) {
        val server = event.server
        // 先推进编排式动画调度（spin/movePath/stagger 等任务可能生成粒子）
        AnimationScheduler.tick()
        // 特效 API：推进参考时钟（清理已播完）并批量下发本 tick 累积的可移动锚点更新
        ServerEffectManager.tickClocks()
        ServerEffectManager.flushAnchorUpdates(server)
        // 发射器：定期给「刚进范围/后进服」的玩家补发声明
        ServerEmitterManager.tick(server)
        for (level in server.allLevels) {
            val dim = ParticleUtils.dimensionUUID(level)
            // 清理该维度已播完（非循环）的动画播放记录，避免迟到玩家收到已结束的播放
            ServerAnimationManager.removeFinished(dim, level.gameTime)
            val engine = ServerParticleEngine.getOrCreate(dim)
            engine.tick(level.players())
        }
    }

    @SubscribeEvent
    @JvmStatic
    fun onLevelUnload(event: LevelEvent.Unload) {
        if (event.level is ServerLevel) {
            val dim = ParticleUtils.dimensionUUID(event.level as ServerLevel)
            ServerAnimationManager.stopAll(dim, (event.level as ServerLevel).players())
            ServerEffectManager.stopAll(dim, (event.level as ServerLevel).players())
            ServerEmitterManager.clearDimension(dim, (event.level as ServerLevel).players())
            ServerParticleEngine.clearDimension(dim)
            AnimationScheduler.clear()
        }
    }

    // 维度切换 / 重生 / 登录后，客户端会重建 ClientLevel 与原版 ParticleEngine，
    // 本地动画播放的粒子桥接随之销毁——重新下发该玩家所在维度的活跃播放，粒子回来后恢复显示。
    @SubscribeEvent
    @JvmStatic
    fun onPlayerChangedDimension(event: PlayerEvent.PlayerChangedDimensionEvent) {
        (event.entity as? ServerPlayer)?.let {
            ServerAnimationManager.syncPlaybacksToPlayer(it)
            ServerEffectManager.syncPlaybacksToPlayer(it)
            ServerEmitterManager.syncToPlayer(it)
        }
    }

    @SubscribeEvent
    @JvmStatic
    fun onPlayerRespawn(event: PlayerEvent.PlayerRespawnEvent) {
        (event.entity as? ServerPlayer)?.let {
            ServerAnimationManager.syncPlaybacksToPlayer(it)
            ServerEffectManager.syncPlaybacksToPlayer(it)
            ServerEmitterManager.syncToPlayer(it)
        }
    }

    @SubscribeEvent
    @JvmStatic
    fun onPlayerLoggedIn(event: PlayerEvent.PlayerLoggedInEvent) {
        (event.entity as? ServerPlayer)?.let {
            ServerAnimationManager.syncPlaybacksToPlayer(it)
            ServerEffectManager.syncPlaybacksToPlayer(it)
            ServerEmitterManager.syncToPlayer(it)
            // 程序化贴图：登记通常发生在任何人进服之前，进服时补发一遍（客户端按名幂等）
            TextureSyncService.sendAll(it)
        }
    }

    @SubscribeEvent
    @JvmStatic
    fun onPlayerLoggedOut(event: PlayerEvent.PlayerLoggedOutEvent) {
        ServerEmitterManager.forgetPlayer(event.entity.uuid)
    }
}
