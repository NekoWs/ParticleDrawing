package work.nekow.particledrawing.config;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

public final class ParticleDrawingConfig {

    public static final ServerConfig SERVER;
    public static final ModConfigSpec SERVER_SPEC;

    public static final ClientConfig CLIENT;
    public static final ModConfigSpec CLIENT_SPEC;

    static {
        Pair<ServerConfig, ModConfigSpec> serverPair =
            new ModConfigSpec.Builder().configure(ServerConfig::new);
        SERVER = serverPair.getLeft();
        SERVER_SPEC = serverPair.getRight();

        Pair<ClientConfig, ModConfigSpec> clientPair =
            new ModConfigSpec.Builder().configure(ClientConfig::new);
        CLIENT = clientPair.getLeft();
        CLIENT_SPEC = clientPair.getRight();
    }

    public static final class ServerConfig {
        public final ModConfigSpec.IntValue maxParticlesPerDimension;
        public final ModConfigSpec.IntValue maxParticlesPerPlayer;
        public final ModConfigSpec.DoubleValue visibilityRadius;
        public final ModConfigSpec.IntValue visibilityCheckInterval;

        ServerConfig(ModConfigSpec.Builder builder) {
            builder.push("particle_limits");
            maxParticlesPerDimension = builder
                .comment("Maximum total particles alive per dimension at once.")
                .defineInRange("maxParticlesPerDimension", 100_000, 1, 1_000_000);
            maxParticlesPerPlayer = builder
                .comment("Maximum particles visible to a single player at once.")
                .defineInRange("maxParticlesPerPlayer", 20_000, 1, 100_000);
            builder.pop();

            builder.push("visibility");
            visibilityRadius = builder
                .comment("Maximum distance (blocks) at which particles are sent to a player.")
                .defineInRange("visibilityRadius", 128.0, 16.0, 512.0);
            visibilityCheckInterval = builder
                .comment("Tick interval for re-checking particle visibility per player.")
                .defineInRange("visibilityCheckInterval", 10, 1, 100);
            builder.pop();
        }
    }

    public static final class ClientConfig {
        public final ModConfigSpec.IntValue maxDynamicLights;
        public final ModConfigSpec.DoubleValue dynamicLightMaxDistance;
        public final ModConfigSpec.BooleanValue enableDynamicLights;
        public final ModConfigSpec.IntValue maxRenderParticles;
        public final ModConfigSpec.IntValue particleBatchSize;

        ClientConfig(ModConfigSpec.Builder builder) {
            builder.push("dynamic_lights");
            enableDynamicLights = builder
                .comment("Enable dynamic lighting from glowing particles.")
                .define("enableDynamicLights", true);
            maxDynamicLights = builder
                .comment("Maximum number of simultaneous dynamic light sources.")
                .defineInRange("maxDynamicLights", 256, 0, 1024);
            dynamicLightMaxDistance = builder
                .comment("Maximum distance for a dynamic light to affect the world.")
                .defineInRange("dynamicLightMaxDistance", 16.0, 1.0, 64.0);
            builder.pop();

            builder.push("rendering");
            maxRenderParticles = builder
                .comment("Maximum particles rendered per frame.")
                .defineInRange("maxRenderParticles", 50_000, 1, 200_000);
            particleBatchSize = builder
                .comment("Number of particles to batch in a single draw call.")
                .defineInRange("particleBatchSize", 4096, 64, 65536);
            builder.pop();
        }
    }
}
