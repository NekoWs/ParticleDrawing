package work.nekow.particledrawing.lighting

import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.RenderType
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.client.event.RenderLevelStageEvent
import work.nekow.particledrawing.ParticleDrawing

@EventBusSubscriber(modid = ParticleDrawing.MODID, value = [Dist.CLIENT])
object SpotlightBeamRenderer {
    @JvmStatic
    @SubscribeEvent
    fun render(event: RenderLevelStageEvent) {
        if (event.stage != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return
        val beams = SpotlightBeams.collect(event.camera.position)
        if (beams.isEmpty()) return
        val type = RenderType.lightning()
        val buffers = Minecraft.getInstance().renderBuffers().bufferSource()
        SpotlightBeams.draw(beams, event.poseStack.last(), buffers.getBuffer(type))
        buffers.endBatch(type)
    }
}
