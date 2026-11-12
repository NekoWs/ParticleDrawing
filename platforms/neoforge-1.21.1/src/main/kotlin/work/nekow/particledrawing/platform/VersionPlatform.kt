package work.nekow.particledrawing.platform

import net.neoforged.fml.ModContainer
import net.neoforged.fml.config.ModConfig
import work.nekow.particledrawing.config.ParticleDrawingConfig

/**
 * 1.21.1 / NeoForge 21.1.x 的版本差异点。
 *
 * 21.1 没有 `ModConfig.Type.SYNCED`，服务端配置用 `SERVER`：配置由服务端下发前由模组自行同步，
 * 行为与 26.x 的 SYNCED 一致（两端都跑同一份 `SERVER_SPEC`）。
 */
object VersionPlatform {
    fun registerServerConfig(container: ModContainer) {
        container.registerConfig(ModConfig.Type.SERVER, ParticleDrawingConfig.SERVER_SPEC, "particledrawing-server.toml")
    }
}
