package work.nekow.particledrawing

import com.mojang.logging.LogUtils
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.ModContainer
import net.neoforged.fml.common.Mod
import org.slf4j.Logger

@Mod(ParticleDrawing.MODID)
class ParticleDrawing(bus: IEventBus, container: ModContainer) {
    companion object {
        const val MODID = "particledrawing"
        private val LOGGER: Logger = LogUtils.getLogger()
    }

    init {
        LOGGER.info("Loading ParticleDrawing!")
    }
}
