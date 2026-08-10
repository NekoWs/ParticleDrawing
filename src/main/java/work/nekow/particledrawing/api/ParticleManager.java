package work.nekow.particledrawing.api;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import work.nekow.particledrawing.core.server.ServerParticleEngine;
import work.nekow.particledrawing.util.ParticleUtils;

import java.util.Collection;
import java.util.UUID;

/**
 * Entry point for creating and managing particles.
 * Provides both high-level drawing utilities and low-level particle control.
 *
 * <p>Usage:
 * <pre>{@code
 * ParticleManager manager = ParticleManager.of(serverLevel);
 * ParticleHandle handle = manager.create()
 *     .style(ParticleStyle.DUST)
 *     .position(0, 64, 0)
 *     .color(Color.RED)
 *     .lifetime(100)
 *     .spawn();
 *
 * ParticleGroup circle = Draw.circle(manager, center, 5, 64);
 * circle.rotate(Vec3.Z, Math.PI / 4, EasingType.EASE_IN_OUT.duration(40));
 * }</pre>
 */
public final class ParticleManager {

    private final UUID dimensionId;
    private final ServerLevel level;

    private ParticleManager(ServerLevel level) {
        this.level = level;
        this.dimensionId = ParticleUtils.dimensionUUID(level);
        ServerParticleEngine.getOrCreate(dimensionId);
    }

    public static ParticleManager of(ServerLevel level) {
        return new ParticleManager(level);
    }

    public static ParticleManager of(Level level) {
        if (!(level instanceof ServerLevel sl)) {
            throw new IllegalArgumentException("ParticleManager requires a ServerLevel");
        }
        return new ParticleManager(sl);
    }

    /**
     * Creates a new particle builder.
     */
    public ParticleHandle.Builder create() {
        return new ParticleHandle.Builder(this);
    }

    /**
     * Creates a new empty particle group.
     */
    public ParticleGroup createGroup(Vec3 pivot) {
        UUID groupId = UUID.randomUUID();
        ServerParticleEngine engine = getEngine();
        engine.createGroup(groupId, pivot);
        return new ParticleGroup(groupId, pivot, this);
    }

    /**
     * Retrieves an existing group.
     */
    public ParticleGroup getGroup(UUID groupId) {
        ServerParticleEngine engine = getEngine();
        var groupData = engine.getGroup(groupId);
        if (groupData == null) return null;
        return new ParticleGroup(groupId, groupData.pivot(), this);
    }

    public ServerLevel level() {
        return level;
    }

    public ServerParticleEngine getEngine() {
        return ServerParticleEngine.getOrCreate(dimensionId);
    }

    Collection<ServerPlayer> getPlayers() {
        return level.players();
    }

    UUID dimensionId() {
        return dimensionId;
    }
}
