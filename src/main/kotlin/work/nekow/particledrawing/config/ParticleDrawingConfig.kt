package work.nekow.particledrawing.config

import net.neoforged.neoforge.common.ModConfigSpec

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

    class ServerConfig(builder: ModConfigSpec.Builder) {
        val maxParticlesPerDimension: ModConfigSpec.IntValue
        val maxParticlesPerPlayer: ModConfigSpec.IntValue
        val visibilityRadius: ModConfigSpec.DoubleValue
        val visibilityCheckInterval: ModConfigSpec.IntValue

        init {
            builder.push("particle_limits")
            maxParticlesPerDimension = builder
                .comment("Maximum total particles alive per dimension at once.")
                .defineInRange("maxParticlesPerDimension", 100_000, 1, 1_000_000)
            maxParticlesPerPlayer = builder
                .comment("Maximum particles visible to a single player at once.")
                .defineInRange("maxParticlesPerPlayer", 20_000, 1, 100_000)
            builder.pop()

            builder.push("visibility")
            visibilityRadius = builder
                .comment("Maximum distance (blocks) at which particles are sent to a player.")
                .defineInRange("visibilityRadius", 128.0, 16.0, 512.0)
            visibilityCheckInterval = builder
                .comment("Tick interval for re-checking particle visibility per player.")
                .defineInRange("visibilityCheckInterval", 10, 1, 100)
            builder.pop()
        }
    }

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
                .comment("Maximum number of simultaneous dynamic light sources.")
                .defineInRange("maxDynamicLights", 256, 0, 1024)
            dynamicLightMaxDistance = builder
                .comment("Maximum distance for a dynamic light to affect the world.")
                .defineInRange("dynamicLightMaxDistance", 16.0, 1.0, 64.0)
            builder.pop()

            builder.push("rendering")
            maxRenderParticles = builder
                .comment("Maximum particles rendered per frame.")
                .defineInRange("maxRenderParticles", 50_000, 1, 200_000)
            particleBatchSize = builder
                .comment("Number of particles to batch in a single draw call.")
                .defineInRange("particleBatchSize", 4096, 64, 65536)
            builder.pop()
        }
    }
}
