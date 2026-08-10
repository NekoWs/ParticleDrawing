package work.nekow.particledrawing.core.client;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import work.nekow.particledrawing.ParticleDrawing;
import work.nekow.particledrawing.lighting.DynamicLightEngine;
import work.nekow.particledrawing.lighting.DynamicLightManager;

@EventBusSubscriber(modid = ParticleDrawing.MODID, value = Dist.CLIENT)
public final class ParticleRenderHandler {

    private static boolean engineInitialized = false;

    private ParticleRenderHandler() {}

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        if (!engineInitialized) {
            ClientParticleEngine.init();
            engineInitialized = true;
        }

        ClientParticleEngine engine = ClientParticleEngine.instance();
        if (engine != null) {
            engine.frameUpdate();
            var camera = Minecraft.getInstance().gameRenderer.mainCamera();
            DynamicLightManager.renderDynamicLights(engine, camera);

            // Apply dynamic lights to server light engine (block illumination)
            DynamicLightEngine.tick(engine.getGlowingParticles());
        }
    }
}
