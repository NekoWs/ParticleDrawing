package work.nekow.particledrawing.api;

import net.minecraft.world.phys.Vec3;
import work.nekow.particledrawing.core.easing.EasingType;
import work.nekow.particledrawing.core.server.ServerParticleEngine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * A collection of particles that can be transformed together.
 * Created via {@link Draw} utilities or {@link ParticleManager#createGroup(Vec3)}.
 */
public final class ParticleGroup {

    private final UUID id;
    private Vec3 pivot;
    private final ParticleManager manager;

    ParticleGroup(UUID id, Vec3 pivot, ParticleManager manager) {
        this.id = id;
        this.pivot = pivot;
        this.manager = manager;
    }

    public UUID id() { return id; }
    public Vec3 pivot() { return pivot; }

    /**
     * Set the pivot point for future transforms.
     */
    public ParticleGroup setPivot(Vec3 pivot) {
        this.pivot = pivot;
        var group = manager.getEngine().getGroup(id);
        if (group != null) {
            group.setPivot(pivot);
        }
        return this;
    }

    /**
     * Translate all particles in the group with easing.
     */
    public ParticleGroup move(Vec3 delta, int durationTicks, EasingType easing) {
        manager.getEngine().applyGroupTransform(
            id, TransformOp.Type.TRANSLATE,
            delta, null, 0, null, 0, pivot,
            durationTicks, easing, manager.getPlayers()
        );
        pivot = pivot.add(delta);
        return this;
    }

    /**
     * Rotate all particles around the pivot with easing.
     * @param axis normalized rotation axis (e.g. Vec3.Z for Z-axis)
     * @param radians rotation angle in radians
     */
    public ParticleGroup rotate(Vec3 axis, double radians, int durationTicks, EasingType easing) {
        manager.getEngine().applyGroupTransform(
            id, TransformOp.Type.ROTATE,
            null, axis, radians, null, 0, pivot,
            durationTicks, easing, manager.getPlayers()
        );
        return this;
    }

    /**
     * Recolor all particles in the group with easing.
     */
    public ParticleGroup recolor(Color targetColor, int durationTicks, EasingType easing) {
        manager.getEngine().applyGroupTransform(
            id, TransformOp.Type.RECOLOR,
            null, null, 0, targetColor, 0, null,
            durationTicks, easing, manager.getPlayers()
        );
        return this;
    }

    /**
     * Scale all particles relative to the pivot with easing.
     */
    public ParticleGroup scale(float targetScale, int durationTicks, EasingType easing) {
        manager.getEngine().applyGroupTransform(
            id, TransformOp.Type.SCALE,
            null, null, 0, null, targetScale, pivot,
            durationTicks, easing, manager.getPlayers()
        );
        return this;
    }

    /**
     * Add a particle to this group.
     */
    public void add(ParticleHandle handle) {
        var group = manager.getEngine().getGroup(id);
        if (group != null) {
            group.addMember(handle.id());
        }
    }

    /**
     * Get the member count.
     */
    public int size() {
        var group = manager.getEngine().getGroup(id);
        return group != null ? group.size() : 0;
    }

    /**
     * Destroy the entire group and all its particles.
     */
    public void remove() {
        manager.getEngine().destroyGroup(id, manager.getPlayers());
    }

    @Override
    public String toString() {
        return "ParticleGroup{" + id + " size=" + size() + "}";
    }
}
