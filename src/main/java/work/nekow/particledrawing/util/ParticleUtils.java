package work.nekow.particledrawing.util;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;

import java.util.UUID;

/**
 * Utility methods for the particle drawing system.
 */
public final class ParticleUtils {

    private ParticleUtils() {}

    /**
     * Generate a stable UUID from a dimension's ResourceLocation.
     * This ensures the same dimension always gets the same engine instance.
     */
    public static UUID dimensionUUID(ServerLevel level) {
        return dimensionUUID(level.dimension().identifier());
    }

    public static UUID dimensionUUID(Identifier location) {
        long hash = location.toString().hashCode();
        if (hash < 0) {
            return new UUID(0, Math.abs(hash));
        }
        return new UUID(hash, 0);
    }
}
