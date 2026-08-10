package work.nekow.particledrawing

import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.ModContainer
import net.neoforged.fml.common.Mod
import net.neoforged.fml.config.ModConfig
import work.nekow.particledrawing.config.ParticleDrawingConfig

@Mod(ParticleDrawing.MODID)
@Suppress("unused")
class ParticleDrawing(bus: IEventBus, container: ModContainer) {
    companion object {
        const val MODID = "particledrawing"
    }

    init {
        container.registerConfig(ModConfig.Type.SERVER, ParticleDrawingConfig.SERVER_SPEC)
        container.registerConfig(ModConfig.Type.CLIENT, ParticleDrawingConfig.CLIENT_SPEC)
    }
}
