package work.nekow.particledrawing.core.server;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import work.nekow.particledrawing.ParticleDrawing;
import work.nekow.particledrawing.util.ParticleUtils;

/**
 * Handles server-side particle engine lifecycle.
 */
@EventBusSubscriber(modid = ParticleDrawing.MODID)
public final class ServerParticleHandler {

    private ServerParticleHandler() {}

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        var server = event.getServer();
        for (ServerLevel level : server.getAllLevels()) {
            ServerParticleEngine engine = ServerParticleEngine.getOrCreate(ParticleUtils.dimensionUUID(level));
            engine.tick(level.players());
        }
    }

    @SubscribeEvent
    static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel sl) {
            ServerParticleEngine.clearDimension(ParticleUtils.dimensionUUID(sl));
        }
    }
}
