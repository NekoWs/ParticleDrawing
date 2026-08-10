package work.nekow.particledrawing.core.server

import net.minecraft.server.level.ServerLevel
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.level.LevelEvent
import net.neoforged.neoforge.event.server.ServerStoppingEvent
import work.nekow.particledrawing.ParticleDrawing
import work.nekow.particledrawing.lighting.DynamicLightPositions

@EventBusSubscriber(modid = ParticleDrawing.MODID)
@Suppress("unused")
object DynamicLightCleanup {

    @SubscribeEvent
    @JvmStatic
    fun onServerStopping(event: ServerStoppingEvent) {
        for (level in event.server.allLevels) {
            DynamicLightPositions.clearAll(level)
        }
    }

    @SubscribeEvent
    @JvmStatic
    fun onLevelUnload(event: LevelEvent.Unload) {
        if (event.level is ServerLevel) {
            DynamicLightPositions.clearAll(event.level as ServerLevel)
        }
    }
}
