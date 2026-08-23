package work.nekow.particledrawing.core.server

import net.minecraft.server.level.ServerLevel
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.level.LevelEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import work.nekow.particledrawing.ParticleDrawing
import work.nekow.particledrawing.animation.ServerAnimationManager
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
        for (level in server.allLevels) {
            val dim = ParticleUtils.dimensionUUID(level)
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
            ServerParticleEngine.clearDimension(dim)
            AnimationScheduler.clear()
        }
    }
}
