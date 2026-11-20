package work.nekow.particledrawing.lighting

import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.RenderType
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.client.event.RenderLevelStageEvent
import work.nekow.particledrawing.ParticleDrawing
import work.nekow.particledrawing.math.ResourceId
import work.nekow.particledrawing.platform.toMinecraft

/** 光影包生效时提交地面光斑；1.21.1 走 RenderLevelStageEvent + RenderType。 */
@EventBusSubscriber(modid = ParticleDrawing.MODID, value = [Dist.CLIENT])
object LightPoolRenderer {
    private val texture = ResourceId.parse("particledrawing:textures/particle/light_pool.png")

    @JvmStatic
    @SubscribeEvent
    fun render(event: RenderLevelStageEvent) {
        if (event.stage != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return
        val camera = event.camera.position
        val pools = LightPools.collect(camera)
        if (pools.isEmpty()) return
        val type = RenderType.entityTranslucentEmissive(texture.toMinecraft())
        val buffers = Minecraft.getInstance().renderBuffers().bufferSource()
        LightPools.draw(pools, camera, event.poseStack.last(), buffers.getBuffer(type))
        buffers.endBatch(type)
    }
}
