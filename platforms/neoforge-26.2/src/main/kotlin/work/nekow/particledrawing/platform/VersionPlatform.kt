package work.nekow.particledrawing.platform

import net.neoforged.fml.ModContainer
import net.neoforged.fml.config.ModConfig
import work.nekow.particledrawing.config.ParticleDrawingConfig

object VersionPlatform {
    fun registerServerConfig(container: ModContainer) {
        container.registerConfig(ModConfig.Type.SERVER, ParticleDrawingConfig.SERVER_SPEC, "particledrawing-server.toml")
    }
}
