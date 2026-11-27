package work.nekow.particledrawing.config

import net.neoforged.neoforge.common.ModConfigSpec

// 服务端与客户端配置定义。ServerConfig：粒子上限与可见性；ClientConfig：动态光照与渲染。
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
     * 服务端配置：粒子上限与可见性。
     */
    class ServerConfig(builder: ModConfigSpec.Builder) {
        val maxParticlesPerDimension: ModConfigSpec.IntValue
        val maxParticlesPerPlayer: ModConfigSpec.IntValue
        val visibilityCheckInterval: ModConfigSpec.IntValue

        init {
            builder.push("particle_limits")
            maxParticlesPerDimension = builder
                .comment("The maximum total number of particles alive at once per dimension.")
                .defineInRange("maxParticlesPerDimension", 1_000_000, 1, 10_000_000)
            maxParticlesPerPlayer = builder
                .comment("The maximum number of particles visible to a single player at once.")
                .defineInRange("maxParticlesPerPlayer", 500_000, 1, 5_000_000)
            builder.pop()

            builder.push("visibility")
            visibilityCheckInterval = builder
                .comment("The interval in ticks to re-check particle visibility for each player (particles are synced within the player's render distance).")
                .defineInRange("visibilityCheckInterval", 10, 1, 100)
            builder.pop()
        }
    }

    /**
     * 客户端配置：动态光照与渲染参数。
     */
    class ClientConfig(builder: ModConfigSpec.Builder) {
        val maxDynamicLights: ModConfigSpec.IntValue
        val maxDynamicLightsPerCell: ModConfigSpec.IntValue
        val dynamicLightMaxDistance: ModConfigSpec.DoubleValue
        val showSpotlightBeams: ModConfigSpec.BooleanValue
        val maxShadowedParticleLights: ModConfigSpec.IntValue
        val enableDynamicLights: ModConfigSpec.BooleanValue
        val lightBrightness: ModConfigSpec.DoubleValue
        val maxRenderParticles: ModConfigSpec.IntValue
        val maxParticleGeometryQuads: ModConfigSpec.IntValue
        val particleLodDistance: ModConfigSpec.DoubleValue
        val particleBatchSize: ModConfigSpec.IntValue
        val customAdditivePipeline: ModConfigSpec.BooleanValue
        val sceneMaterials: ModConfigSpec.BooleanValue
        val terrainDynamicLights: ModConfigSpec.BooleanValue
        val shaderCompat: ModConfigSpec.BooleanValue
        val weightedParticleTransparency: ModConfigSpec.BooleanValue
        val shaderLightPools: ModConfigSpec.BooleanValue
        val debugLogFps: ModConfigSpec.BooleanValue
        val forceLegacyPipelines: ModConfigSpec.BooleanValue

        init {
            builder.push("dynamic_lights")
            enableDynamicLights = builder
                .comment("Enable dynamic lighting from glowing particles.")
                .define("enableDynamicLights", true)
            lightBrightness = builder
                .comment("Multiplier applied to the light level particle lights contribute. Lower it when many strong lights wash out the ground or blow out additive particles on bright surfaces.")
                .defineInRange("lightBrightness", 1.0, 0.0, 2.0)
            showSpotlightBeams = builder
                .comment("Draw a faint beam for nearby spotlights.")
                .define("showSpotlightBeams", false)
            maxShadowedParticleLights = builder
                .comment("Number of nearby particle lights with up to 16 blocks of range that cast terrain shadows. Zero disables occlusion; raise it when several lights in a scene still bleed through blocks. Higher values cost more raycasts per frame.")
                .defineInRange("maxShadowedParticleLights", 0, 0, 16)
            maxDynamicLights = builder
                .comment("The maximum number of dynamic light sources active at once (global upper bound).")
                .defineInRange("maxDynamicLights", 4096, 0, 65_536)
            maxDynamicLightsPerCell = builder
                .comment("The maximum number of dynamic light sources computed per 16x16x16 cell. Lights beyond this are dropped for that cell, which can look like a lamp switching off; raise it if that happens.")
                .defineInRange("maxDynamicLightsPerCell", 256, 1, 4096)
            dynamicLightMaxDistance = builder
                .comment("Default range of particle lights without an explicit range.")
                .defineInRange("dynamicLightMaxDistance", 64.0, 1.0, 256.0)
            builder.pop()

            builder.push("rendering")
            maxRenderParticles = builder
                .comment("The maximum number of particles the client keeps in its render pipeline.")
                .defineInRange("maxRenderParticles", 1_000_000, 1, 5_000_000)
            maxParticleGeometryQuads = builder
                .comment("Maximum quads submitted by particle trails and models per frame")
                .defineInRange("maxParticleGeometryQuads", 2_000_000, 1_000, 20_000_000)
            particleLodDistance = builder
                .comment("Distance in blocks for full particle density. Density falls with squared distance, and particles beyond four times this range are hidden. Zero disables LOD.")
                .defineInRange("particleLodDistance", 0.0, 0.0, 512.0)
            particleBatchSize = builder
                .comment("The number of particles whose eased state is synchronized to the renderer per frame (fair round-robin batch).")
                .defineInRange("particleBatchSize", 65_536, 64, 1_000_000)
            builder.pop()

            builder.push("compatibility")
            shaderCompat = builder
                .comment("Draw additive particles after Iris composites the world so they retain additive blending. Scene materials, instancing and weighted OIT also use the composed scene.")
                .define("shaderCompat", true)
            weightedParticleTransparency = builder
                .comment("Use weighted order-independent blending for regular translucent ParticleDrawing particles on 1.21.1 and 26.2, or with an active Iris shader pack on 26.3.")
                .define("weightedParticleTransparency", true)
            shaderLightPools = builder
                .comment("Optional fallback for shader packs that ignore the vanilla lightmap: draw a soft light patch on the surface under each particle light. Replaces ParticleDrawing terrain light baking while compatibility mode is active. Off by default because packs that use the vanilla lightmap already receive terrain lighting.")
                .define("shaderLightPools", false)
            customAdditivePipeline = builder
                .comment("Draw additive particles with the mod's own blend pipeline. Turn this off when a shader pack overexposes or mis-sorts additive particles; they then use the vanilla translucent particle pipeline without additive blending.")
                .define("customAdditivePipeline", true)
            sceneMaterials = builder
                .comment("Let particles sample the frame's color and depth for depth_fade and distortion. Turn this off when a shader pack makes those particles look wrong; they then draw as ordinary particles.")
                .define("sceneMaterials", true)
            terrainDynamicLights = builder
                .comment("Let particle lights brighten terrain vertices. Turn this off when a shader pack computes its own terrain lighting and the ground overexposes; entities and particles still receive dynamic light through the lightmap.")
                .define("terrainDynamicLights", true)
            builder.pop()

            builder.push("debug")
            debugLogFps = builder
                .comment("Log render frame time and particle count every 5 seconds. Diagnostic switch; leave off in normal play.")
                .define("debugLogFps", false)
            forceLegacyPipelines = builder
                .comment("Force the vanilla pipeline fallback even without a shader pack. Diagnostic switch for checking what shader compatibility looks like.")
                .define("forceLegacyPipelines", false)
            builder.pop()

        }
    }
}





