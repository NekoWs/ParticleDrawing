package work.nekow.particledrawing

import com.mojang.logging.LogUtils
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.ModContainer
import net.neoforged.fml.common.Mod
import net.neoforged.fml.config.ModConfig
import org.slf4j.Logger
import work.nekow.particledrawing.config.ParticleDrawingConfig

@Mod(ParticleDrawing.MODID)
class ParticleDrawing(bus: IEventBus, container: ModContainer) {
    companion object {
        const val MODID = "particledrawing"
        private val LOGGER: Logger = LogUtils.getLogger()
    }

    init {
        LOGGER.info("Loading ParticleDrawing!")

        container.registerConfig(ModConfig.Type.SERVER, ParticleDrawingConfig.SERVER_SPEC)
        container.registerConfig(ModConfig.Type.CLIENT, ParticleDrawingConfig.CLIENT_SPEC)
    }
}
