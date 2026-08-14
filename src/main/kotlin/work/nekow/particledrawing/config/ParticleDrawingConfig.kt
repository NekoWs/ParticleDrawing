package work.nekow.particledrawing.config

import net.neoforged.neoforge.common.ModConfigSpec

/**
 * Server and client configuration for ParticleDrawing.
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
        val visibilityRadius: ModConfigSpec.DoubleValue
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
            visibilityRadius = builder
                .comment("The maximum distance (in blocks) to send particles to a player.")
                .defineInRange("visibilityRadius", 128.0, 16.0, 512.0)
            visibilityCheckInterval = builder
                .comment("The interval in ticks to re-check particle visibility for each player.")
                .defineInRange("visibilityCheckInterval", 10, 1, 100)
            builder.pop()
        }
    }

    /**
     * Client configuration, controlling dynamic lighting and rendering parameters.
     */
    class ClientConfig(builder: ModConfigSpec.Builder) {
        val maxDynamicLights: ModConfigSpec.IntValue
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
                .comment("The maximum number of dynamic light sources active at once.")
                .defineInRange("maxDynamicLights", 256, 0, 1024)
            dynamicLightMaxDistance = builder
                .comment("The maximum distance dynamic lighting affects the world.")
                .defineInRange("dynamicLightMaxDistance", 16.0, 1.0, 64.0)
            builder.pop()

            builder.push("rendering")
            maxRenderParticles = builder
                .comment("The maximum number of particles rendered per frame.")
                .defineInRange("maxRenderParticles", 50_000, 1, 200_000)
            particleBatchSize = builder
                .comment("The number of particles batched in a single draw call.")
                .defineInRange("particleBatchSize", 4096, 64, 65536)
            builder.pop()
        }
    }
}
