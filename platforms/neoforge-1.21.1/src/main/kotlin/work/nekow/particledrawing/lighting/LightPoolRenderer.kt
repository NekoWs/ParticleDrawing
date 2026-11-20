package work.nekow.particledrawing.lighting

import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.RenderType
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.client.event.RenderLevelStageEvent
import work.nekow.particledrawing.ParticleDrawing

/** 光影包生效时提交地面光照网格；1.21.1 走 RenderLevelStageEvent + RenderType。 */
@EventBusSubscriber(modid = ParticleDrawing.MODID, value = [Dist.CLIENT])
object LightPoolRenderer {
    @JvmStatic
    @SubscribeEvent
    fun render(event: RenderLevelStageEvent) {
        if (event.stage != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return
        val camera = event.camera.position
        val mesh = LightPools.build(camera) ?: return
        val type = RenderType.lightning()
        val buffers = Minecraft.getInstance().renderBuffers().bufferSource()
        LightPools.draw(mesh, camera, event.poseStack.last(), buffers.getBuffer(type))
        buffers.endBatch(type)
    }
}
