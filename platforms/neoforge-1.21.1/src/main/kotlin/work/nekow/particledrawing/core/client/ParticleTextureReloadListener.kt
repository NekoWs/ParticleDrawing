package work.nekow.particledrawing.core.client

import net.minecraft.server.packs.resources.ResourceManagerReloadListener
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent
import work.nekow.particledrawing.ParticleDrawing

/**
 * F3+T 或切换资源包后，现存 PD 粒子重新读取所引用的粒子贴图。
 *
 * 1.21.1 的注册事件是 `RegisterClientReloadListenersEvent`，监听器不带名字参数。
 */
@EventBusSubscriber(modid = ParticleDrawing.MODID, value = [Dist.CLIENT])
@Suppress("unused")
object ParticleTextureReloadListener {
    @SubscribeEvent
    @JvmStatic
    fun register(event: RegisterClientReloadListenersEvent) {
        event.registerReloadListener(
            ResourceManagerReloadListener {
                TextureCache.clearParticleResources()
                ParticleModelRenderer.clear()
                ClientCommandSuggestions.invalidate()
            },
        )
    }
}
