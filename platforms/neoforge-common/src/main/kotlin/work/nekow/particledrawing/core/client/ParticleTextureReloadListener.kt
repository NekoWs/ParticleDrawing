package work.nekow.particledrawing.core.client

import net.minecraft.resources.Identifier
import net.minecraft.server.packs.resources.ResourceManagerReloadListener
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent
import work.nekow.particledrawing.ParticleDrawing

/** F3+T 或切换资源包后，现存 PD 粒子重新读取所引用的粒子贴图。 */
@EventBusSubscriber(modid = ParticleDrawing.MODID, value = [Dist.CLIENT])
@Suppress("unused")
object ParticleTextureReloadListener {
    @SubscribeEvent
    @JvmStatic
    fun register(event: AddClientReloadListenersEvent) {
        event.addListener(
            Identifier.fromNamespaceAndPath(ParticleDrawing.MODID, "particle_textures"),
            ResourceManagerReloadListener { TextureCache.clearParticleResources() },
        )
    }
}
