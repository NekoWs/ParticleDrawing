package work.nekow.particledrawing.core.client

import net.minecraft.client.Minecraft
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.client.event.ClientTickEvent
import work.nekow.particledrawing.ParticleDrawing
import work.nekow.particledrawing.lighting.DynamicLightEngine
import work.nekow.particledrawing.lighting.DynamicLightManager

@EventBusSubscriber(modid = ParticleDrawing.MODID, value = [Dist.CLIENT])
object ParticleRenderHandler {

    private var engineInitialized = false

    @SubscribeEvent
    @JvmStatic
    fun onClientTick(event: ClientTickEvent.Post) {
        if (!engineInitialized) {
            ClientParticleEngine.init()
            engineInitialized = true
        }

        val engine = ClientParticleEngine.instance()
        if (engine != null) {
            engine.frameUpdate()
            val camera = Minecraft.getInstance().gameRenderer.mainCamera()
            DynamicLightManager.renderDynamicLights(engine, camera)

            DynamicLightEngine.tick(engine.getGlowingParticles())
        }
    }
}
