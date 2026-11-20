package work.nekow.particledrawing.lighting

import net.minecraft.client.renderer.rendertype.RenderTypes
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent
import work.nekow.particledrawing.ParticleDrawing

/**
 * 光影包生效时提交地面光照网格。用原版闪电渲染类型（加法混合、纯顶点色），光影包认识它。
 *
 * 只是给“不读光图的光影包”准备的兜底，配置默认关闭；1.21.1 用另一套渲染事件，见该平台同名文件。
 */
@EventBusSubscriber(modid = ParticleDrawing.MODID, value = [Dist.CLIENT])
object LightPoolRenderer {
    @JvmStatic
    @SubscribeEvent
    fun submit(event: SubmitCustomGeometryEvent) {
        val camera = event.levelRenderState.cameraRenderState.pos
        val mesh = LightPools.build(camera) ?: return
        event.submitNodeCollector.submitCustomGeometry(event.poseStack, RenderTypes.lightning()) { pose, vertices ->
            LightPools.draw(mesh, camera, pose, vertices)
        }
    }
}
