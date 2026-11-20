package work.nekow.particledrawing.lighting

import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.world.level.levelgen.Heightmap
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent
import work.nekow.particledrawing.ParticleDrawing

/**
 * 光影包生效时提交地面光照网格。用原版闪电渲染类型（加法混合、纯顶点色），光影包认识它。
 *
 * 1.21.1 用的是另一套渲染事件，见该平台同名的 LightPoolRenderer。
 */
@EventBusSubscriber(modid = ParticleDrawing.MODID, value = [Dist.CLIENT])
object LightPoolRenderer {
    private var lastLog = 0L

    @JvmStatic
    @SubscribeEvent
    fun submit(event: SubmitCustomGeometryEvent) {
        val camera = event.levelRenderState.cameraRenderState.pos
        val mesh = LightPools.build(camera) ?: return
        if (System.nanoTime() - lastLog > 2_000_000_000L) {
            lastLog = System.nanoTime()
            val mc = Minecraft.getInstance()
            val bx = Math.floor(camera.x).toInt()
            val bz = Math.floor(camera.z).toInt()
            val height = mc.level?.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz) ?: -1
            org.apache.logging.log4j.LogManager.getLogger("ParticleDrawing").info(
                "[PD-grid] vertices={} cameraY={} heightAtPlayer={} firstY={}",
                mesh.vertices, camera.y, height, mesh.positions[1],
            )
        }
        event.submitNodeCollector.submitCustomGeometry(event.poseStack, RenderTypes.lightning()) { pose, vertices ->
            LightPools.draw(mesh, camera, pose, vertices)
        }
    }
}
