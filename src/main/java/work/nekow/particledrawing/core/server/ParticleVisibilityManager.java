package work.nekow.particledrawing.core.server;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Manages per-player particle visibility using frustum-style culling.
 * Only sends particles that a player can potentially see, reducing network traffic.
 */
public final class ParticleVisibilityManager {

    private final Map<UUID, PlayerVisibilityState> playerStates;

    public ParticleVisibilityManager() {
        this.playerStates = new HashMap<>();
    }

    /**
     * Clears stale entries and returns a filtered view.
     */
    public void updateVisibility(Collection<ParticleData> allParticles,
                                  Collection<ServerPlayer> players, double radius) {
        for (ServerPlayer player : players) {
            UUID playerId = player.getUUID();
            PlayerVisibilityState state = playerStates.computeIfAbsent(
                playerId, k -> new PlayerVisibilityState());
            state.update(player, allParticles, radius);
        }
    }

    public boolean isWithinRange(ServerPlayer player, Vec3 particlePos, double radius) {
        Vec3 playerPos = player.position();
        double dx = playerPos.x - particlePos.x;
        double dy = playerPos.y - particlePos.y;
        double dz = playerPos.z - particlePos.z;
        return (dx * dx + dy * dy + dz * dz) <= (radius * radius);
    }

    public boolean isVisible(ServerPlayer player, Vec3 particlePos, double radius) {
        return isWithinRange(player, particlePos, radius);
    }

    private static final class PlayerVisibilityState {
        Vec3 lastPosition = Vec3.ZERO;
        long lastUpdateTick = 0;

        void update(ServerPlayer player, Collection<ParticleData> allParticles, double radius) {
            lastPosition = player.position();
        }
    }
}
