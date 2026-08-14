package work.nekow.particledrawing.core.server

import net.minecraft.server.level.ServerLevel
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.level.LevelEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import work.nekow.particledrawing.ParticleDrawing
import work.nekow.particledrawing.util.ParticleUtils

/**
 * 服务端 tick 事件处理器，驱动粒子引擎每 tick 更新。
 */
@EventBusSubscriber(modid = ParticleDrawing.MODID)
@Suppress("unused")
object ServerParticleHandler {

    @SubscribeEvent
    @JvmStatic
    fun onServerTick(event: ServerTickEvent.Post) {
        val server = event.server
        for (level in server.allLevels) {
            val engine = ServerParticleEngine.getOrCreate(ParticleUtils.dimensionUUID(level))
            engine.tick(level.players())
        }
    }

    @SubscribeEvent
    @JvmStatic
    fun onLevelUnload(event: LevelEvent.Unload) {
        if (event.level is ServerLevel) {
            ServerParticleEngine.clearDimension(ParticleUtils.dimensionUUID(event.level as ServerLevel))
        }
    }
}
