package work.nekow.particledrawing.config

import net.neoforged.neoforge.common.ModConfigSpec

/**
 * 粒子绘制的服务端与客户端配置。
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
     * 服务端配置，控制粒子限制与可见性。
     */
    class ServerConfig(builder: ModConfigSpec.Builder) {
        val maxParticlesPerDimension: ModConfigSpec.IntValue
        val maxParticlesPerPlayer: ModConfigSpec.IntValue
        val visibilityRadius: ModConfigSpec.DoubleValue
        val visibilityCheckInterval: ModConfigSpec.IntValue

        init {
            builder.push("particle_limits")
            maxParticlesPerDimension = builder
                .comment("每个维度中同时存活的最大粒子总数。")
                .defineInRange("maxParticlesPerDimension", 100_000, 1, 1_000_000)
            maxParticlesPerPlayer = builder
                .comment("单个玩家同时可见的最大粒子数。")
                .defineInRange("maxParticlesPerPlayer", 20_000, 1, 100_000)
            builder.pop()

            builder.push("visibility")
            visibilityRadius = builder
                .comment("向玩家发送粒子的最大距离（格）。")
                .defineInRange("visibilityRadius", 128.0, 16.0, 512.0)
            visibilityCheckInterval = builder
                .comment("重新检查每个玩家粒子可见性的刻间隔。")
                .defineInRange("visibilityCheckInterval", 10, 1, 100)
            builder.pop()
        }
    }

    /**
     * 客户端配置，控制动态光照与渲染参数。
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
                .comment("启用来自发光粒子的动态照明。")
                .define("enableDynamicLights", true)
            maxDynamicLights = builder
                .comment("同时存在的动态光源最大数量。")
                .defineInRange("maxDynamicLights", 256, 0, 1024)
            dynamicLightMaxDistance = builder
                .comment("动态光照影响世界的最大距离。")
                .defineInRange("dynamicLightMaxDistance", 16.0, 1.0, 64.0)
            builder.pop()

            builder.push("rendering")
            maxRenderParticles = builder
                .comment("每帧渲染的最大粒子数。")
                .defineInRange("maxRenderParticles", 50_000, 1, 200_000)
            particleBatchSize = builder
                .comment("单次绘制调用中批处理的粒子数量。")
                .defineInRange("particleBatchSize", 4096, 64, 65536)
            builder.pop()
        }
    }
}
