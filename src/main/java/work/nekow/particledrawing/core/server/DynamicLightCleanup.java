package work.nekow.particledrawing.core.server;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import work.nekow.particledrawing.ParticleDrawing;
import work.nekow.particledrawing.lighting.DynamicLightPositions;

/**
 * Cleans up dynamic light blocks on level unload / server stop.
 */
@EventBusSubscriber(modid = ParticleDrawing.MODID)
public final class DynamicLightCleanup {

    private DynamicLightCleanup() {}

    @SubscribeEvent
    static void onServerStopping(ServerStoppingEvent event) {
        for (ServerLevel level : event.getServer().getAllLevels()) {
            DynamicLightPositions.clearAll(level);
        }
    }

    @SubscribeEvent
    static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel sl) {
            DynamicLightPositions.clearAll(sl);
        }
    }
}
