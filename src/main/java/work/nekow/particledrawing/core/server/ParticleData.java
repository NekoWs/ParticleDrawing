package work.nekow.particledrawing.core.server;

import work.nekow.particledrawing.api.Color;
import work.nekow.particledrawing.api.ParticleStyle;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * Server-authoritative particle state.
 * Lives on the server thread, mutated by tick logic and API calls.
 */
@SuppressWarnings("unused")
public final class ParticleData {

    private final UUID id;
    private final ParticleStyle style;
    private Vec3 position;
    private Color color;
    private float scale;
    private int lifetime;          // remaining ticks
    private final int maxLifetime; // original lifetime, for progress calcs
    private final UUID groupId;
    private boolean glowing;
    private Vec3 offsetFromPivot;  // offset at creation time, for group transforms

    ParticleData(UUID id, ParticleStyle style, Vec3 position, Color color,
                 float scale, int lifetime, UUID groupId, boolean glowing,
                 Vec3 offsetFromPivot) {
        this.id = id;
        this.style = style;
        this.position = position;
        this.color = color;
        this.scale = scale;
        this.lifetime = lifetime;
        this.maxLifetime = lifetime;
        this.groupId = groupId;
        this.glowing = glowing;
        this.offsetFromPivot = offsetFromPivot != null ? offsetFromPivot : Vec3.ZERO;
    }

    public UUID id() { return id; }
    public ParticleStyle style() { return style; }
    public Vec3 position() { return position; }
    public Color color() { return color; }
    public float scale() { return scale; }
    public int lifetime() { return lifetime; }
    public int maxLifetime() { return maxLifetime; }
    public UUID groupId() { return groupId; }
    public boolean glowing() { return glowing; }
    public Vec3 offsetFromPivot() { return offsetFromPivot; }

    public void setPosition(Vec3 position) { this.position = position; }
    public void setColor(Color color) { this.color = color; }
    public void setScale(float scale) { this.scale = scale; }
    public void setLifetime(int lifetime) { this.lifetime = lifetime; }
    public void setGlowing(boolean glowing) { this.glowing = glowing; }
    public void setOffsetFromPivot(Vec3 offset) { this.offsetFromPivot = offset; }

    public boolean isExpired() {
        return lifetime == 0;
    }

    public int tick() {
        if (lifetime > 0) {
            lifetime--;
        }
        return lifetime;
    }

    public float lifeProgress() {
        if (maxLifetime < 0) return 0f;
        return 1f - (float) lifetime / maxLifetime;
    }

    public ParticleSnapshot toSnapshot() {
        return new ParticleSnapshot(id, style, position, color, scale, glowing);
    }

    /**
     * Lightweight immutable snapshot for visibility checks and comparison.
     */
    public record ParticleSnapshot(
        UUID id,
        ParticleStyle style,
        Vec3 position,
        Color color,
        float scale,
        boolean glowing
    ) {}

    public static ParticleData create(UUID id, ParticleStyle style, Vec3 position,
                                       Color color, float scale, int lifetime,
                                       UUID groupId, boolean glowing, Vec3 offsetFromPivot) {
        return new ParticleData(id, style, position, color, scale, lifetime,
                                groupId, glowing, offsetFromPivot);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ParticleData that)) return false;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "ParticleData{" + id + " " + style + " @ " + position + "}";
    }
}
