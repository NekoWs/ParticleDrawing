package work.nekow.particledrawing.config

import net.neoforged.neoforge.common.ModConfigSpec

/**
 * 服务端与客户端配置定义。
 * - [ServerConfig]：粒子上限（`particle_limits`）与可见性（`visibility`）
 * - [ClientConfig]：动态光照（`dynamic_lights`）与渲染（`rendering`）
 */
object ParticleDrawingConfig {

    val SERVER: ServerConfig
    val SERVER_SPEC: ModConfigSpec

    val CLIENT: ClientConfig
    val CLIENT_SPEC: ModConfigSpec

    init {
        val serverPair = ModConfigSpec.Builder().configure(::ServerConfig)
        SERVER = serverPair.left
        SERVER_SPEC = serverPair.right

        val clientPair = ModConfigSpec.Builder().configure(::ClientConfig)
        CLIENT = clientPair.left
        CLIENT_SPEC = clientPair.right
    }

    /**
     * Server configuration, controlling particle limits and visibility.
     */
    class ServerConfig(builder: ModConfigSpec.Builder) {
        val maxParticlesPerDimension: ModConfigSpec.IntValue
        val maxParticlesPerPlayer: ModConfigSpec.IntValue
        val visibilityCheckInterval: ModConfigSpec.IntValue

        init {
            builder.push("particle_limits")
            maxParticlesPerDimension = builder
                .comment("The maximum total number of particles alive at once per dimension.")
                .defineInRange("maxParticlesPerDimension", 100_000, 1, 1_000_000)
            maxParticlesPerPlayer = builder
                .comment("The maximum number of particles visible to a single player at once.")
                .defineInRange("maxParticlesPerPlayer", 20_000, 1, 100_000)
            builder.pop()

            builder.push("visibility")
            visibilityCheckInterval = builder
                .comment("The interval in ticks to re-check particle visibility for each player (particles are synced within the player's render distance).")
                .defineInRange("visibilityCheckInterval", 10, 1, 100)
            builder.pop()
        }
    }

    /**
     * Client configuration, controlling dynamic lighting and rendering parameters.
     */
    class ClientConfig(builder: ModConfigSpec.Builder) {
        val maxDynamicLights: ModConfigSpec.IntValue
        val maxDynamicLightsPerCell: ModConfigSpec.IntValue
        val dynamicLightMaxDistance: ModConfigSpec.DoubleValue
        val enableDynamicLights: ModConfigSpec.BooleanValue
        val maxRenderParticles: ModConfigSpec.IntValue
        val particleBatchSize: ModConfigSpec.IntValue

        init {
            builder.push("dynamic_lights")
            enableDynamicLights = builder
                .comment("Enable dynamic lighting from glowing particles.")
                .define("enableDynamicLights", true)
            maxDynamicLights = builder
                .comment("The maximum number of dynamic light sources active at once (global upper bound).")
                .defineInRange("maxDynamicLights", 512, 0, 4096)
            maxDynamicLightsPerCell = builder
                .comment("The maximum number of dynamic light sources computed per 16x16x16 cell. Limits lights locally so each area keeps its brightest sources instead of being starved by distant ones.")
                .defineInRange("maxDynamicLightsPerCell", 8, 1, 64)
            dynamicLightMaxDistance = builder
                .comment("The maximum distance dynamic lighting affects the world.")
                .defineInRange("dynamicLightMaxDistance", 16.0, 1.0, 64.0)
            builder.pop()

            builder.push("rendering")
            maxRenderParticles = builder
                .comment("The maximum number of particles the client keeps in its render pipeline.")
                .defineInRange("maxRenderParticles", 50_000, 1, 200_000)
            particleBatchSize = builder
                .comment("The number of particles whose eased state is synchronized to the renderer per frame (fair round-robin batch).")
                .defineInRange("particleBatchSize", 4096, 64, 65536)
            builder.pop()
        }
    }
}
