package work.nekow.particledrawing.lighting

import net.minecraft.client.renderer.rendertype.RenderTypes
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent
import work.nekow.particledrawing.ParticleDrawing

@EventBusSubscriber(modid = ParticleDrawing.MODID, value = [Dist.CLIENT])
object SpotlightBeamRenderer {
    @JvmStatic
    @SubscribeEvent
    fun submit(event: SubmitCustomGeometryEvent) {
        val beams = SpotlightBeams.collect(event.levelRenderState.cameraRenderState.pos)
        if (beams.isEmpty()) return
        event.submitNodeCollector.submitCustomGeometry(event.poseStack, RenderTypes.lightning()) { pose, vertices ->
            SpotlightBeams.draw(beams, pose, vertices)
        }
    }
}
