package work.nekow.particledrawing.lighting

import net.minecraft.client.renderer.rendertype.RenderTypes
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent
import work.nekow.particledrawing.ParticleDrawing
import work.nekow.particledrawing.math.ResourceId
import work.nekow.particledrawing.platform.toMinecraft

/**
 * 光影包生效时提交地面光斑。用原版的半透明自发光渲染类型，光影包认识它，不会整批丢掉。
 *
 * 1.21.1 用的是另一套渲染事件，见该平台同名的 LightPoolRenderer。
 */
@EventBusSubscriber(modid = ParticleDrawing.MODID, value = [Dist.CLIENT])
object LightPoolRenderer {
    private val texture = ResourceId.parse("particledrawing:textures/particle/light_pool.png")

    @JvmStatic
    @SubscribeEvent
    fun submit(event: SubmitCustomGeometryEvent) {
        val camera = event.levelRenderState.cameraRenderState.pos
        val pools = LightPools.collect(camera)
        if (pools.isEmpty()) return
        event.submitNodeCollector.submitCustomGeometry(
            event.poseStack, RenderTypes.entityTranslucentEmissive(texture.toMinecraft()),
        ) { pose, vertices ->
            LightPools.draw(pools, camera, pose, vertices)
        }
    }
}
