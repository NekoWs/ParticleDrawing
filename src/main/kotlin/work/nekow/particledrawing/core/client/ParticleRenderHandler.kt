package work.nekow.particledrawing.core.client

import net.minecraft.client.Minecraft
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.client.event.ClientTickEvent
import work.nekow.particledrawing.ParticleDrawing
import work.nekow.particledrawing.lighting.DynamicLightEngine
import work.nekow.particledrawing.lighting.DynamicLightManager

/**
 * 客户端粒子渲染处理器。
 * 在每帧客户端 Tick 时初始化引擎、更新粒子状态并渲染动态光照。
 */
@EventBusSubscriber(modid = ParticleDrawing.MODID, value = [Dist.CLIENT])
@Suppress("unused")
object ParticleRenderHandler {

    private var engineInitialized = false

    /**
     * 客户端 Tick 事件处理。
     * 负责粒子引擎的延迟初始化及每帧更新。
     * @param event 客户端 Tick 事件
     */
    @SubscribeEvent
    @JvmStatic
    @Suppress("UNUSED_PARAMETER")
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
