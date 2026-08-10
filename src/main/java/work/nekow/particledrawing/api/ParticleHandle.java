package work.nekow.particledrawing.api;

import net.minecraft.world.phys.Vec3;
import work.nekow.particledrawing.core.easing.EasingType;
import work.nekow.particledrawing.core.server.ParticleData;
import work.nekow.particledrawing.core.server.ServerParticleEngine;

import java.util.UUID;

/**
 * Handle for a spawned particle, allowing property updates and lifecycle control.
 * Created via {@link ParticleManager#create()}.
 */
@SuppressWarnings("unused")
public final class ParticleHandle {

    private final UUID id;
    private final ParticleManager manager;

    ParticleHandle(UUID id, ParticleManager manager) {
        this.id = id;
        this.manager = manager;
    }

    public UUID id() { return id; }

    /**
     * Move this particle to a new position with easing.
     */
    public ParticleHandle move(Vec3 target, int durationTicks, EasingType easing) {
        var engine = manager.getEngine();
        var data = engine.getParticle(id);
        if (data == null) return this;

        engine.updateParticle(id, target, data.color(), data.scale(),
            true, false, false, durationTicks, easing, manager.getPlayers());
        return this;
    }

    /**
     * Move this particle immediately (no easing).
     */
    public ParticleHandle moveInstant(Vec3 target) {
        return move(target, 0, EasingType.LINEAR);
    }

    /**
     * Change color with easing.
     */
    public ParticleHandle recolor(Color color, int durationTicks, EasingType easing) {
        var engine = manager.getEngine();
        var data = engine.getParticle(id);
        if (data == null) return this;

        engine.updateParticle(id, data.position(), color, data.scale(),
            false, true, false, durationTicks, easing, manager.getPlayers());
        return this;
    }

    /**
     * Change scale with easing.
     */
    public ParticleHandle resize(float scale, int durationTicks, EasingType easing) {
        var engine = manager.getEngine();
        var data = engine.getParticle(id);
        if (data == null) return this;

        engine.updateParticle(id, data.position(), data.color(), scale,
            false, false, true, durationTicks, easing, manager.getPlayers());
        return this;
    }

    /**
     * Destroy this particle immediately.
     */
    public void remove() {
        manager.getEngine().destroyParticle(id, manager.getPlayers());
    }

    /**
     * Get the current server-side state.
     */
    public ParticleData data() {
        return manager.getEngine().getParticle(id);
    }

    /**
     * Builder for creating particles with a fluent API.
     */
    @SuppressWarnings("unused")
    public static final class Builder {

        private final ParticleManager manager;
        private ParticleStyle style = ParticleStyle.DUST;
        private Vec3 position = Vec3.ZERO;
        private Color color = Color.WHITE;
        private float scale = 1.0f;
        private int lifetime = -1; // immortal
        private UUID groupId = null;
        private boolean glowing = false;
        private Vec3 offsetFromPivot = Vec3.ZERO;

        Builder(ParticleManager manager) {
            this.manager = manager;
        }

        public Builder style(ParticleStyle style) {
            this.style = style;
            return this;
        }

        public Builder position(Vec3 pos) {
            this.position = pos;
            return this;
        }

        public Builder position(double x, double y, double z) {
            return position(new Vec3(x, y, z));
        }

        public Builder color(Color color) {
            this.color = color;
            return this;
        }

        public Builder color(int r, int g, int b) {
            return color(Color.ofInt(r, g, b));
        }

        public Builder color(int r, int g, int b, int a) {
            return color(Color.ofInt(r, g, b, a));
        }

        public Builder scale(float scale) {
            this.scale = scale;
            return this;
        }

        /**
         * Set particle lifetime in ticks. -1 for immortal.
         */
        public Builder lifetime(int ticks) {
            this.lifetime = ticks;
            return this;
        }

        public Builder group(UUID groupId) {
            this.groupId = groupId;
            return this;
        }

        public Builder glowing(boolean glowing) {
            this.glowing = glowing;
            return this;
        }

        public Builder offsetFromPivot(Vec3 offset) {
            this.offsetFromPivot = offset;
            return this;
        }

        /**
         * Spawn the particle and return a handle for further control.
         */
        public ParticleHandle spawn() {
            ServerParticleEngine engine = manager.getEngine();
            ParticleData data = engine.spawnParticle(
                style, position, color, scale, lifetime,
                groupId, glowing, offsetFromPivot,
                manager.getPlayers()
            );

            if (offsetFromPivot != Vec3.ZERO) {
                engine.setOffsetFromPivot(data.id(), offsetFromPivot);
            }

            return new ParticleHandle(data.id(), manager);
        }
    }
}
