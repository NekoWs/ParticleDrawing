package work.nekow.particledrawing.core.server;

import net.minecraft.world.phys.Vec3;
import work.nekow.particledrawing.api.Color;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Holds a group of particles that can be transformed together.
 * The pivot is the reference point for rotations and scaling.
 */
public final class ParticleGroupData {

    private final UUID id;
    private final List<UUID> memberIds;
    private Vec3 pivot;

    ParticleGroupData(UUID id, Vec3 pivot) {
        this.id = id;
        this.pivot = pivot;
        this.memberIds = new CopyOnWriteArrayList<>();
    }

    public UUID id() { return id; }
    public List<UUID> memberIds() { return Collections.unmodifiableList(memberIds); }
    public int size() { return memberIds.size(); }
    public Vec3 pivot() { return pivot; }

    public void setPivot(Vec3 pivot) {
        this.pivot = pivot;
    }

    public void addMember(UUID particleId) {
        if (!memberIds.contains(particleId)) {
            memberIds.add(particleId);
        }
    }

    public void removeMember(UUID particleId) {
        memberIds.remove(particleId);
    }

    public void addMembers(Collection<UUID> ids) {
        for (UUID id : ids) {
            addMember(id);
        }
    }

    public boolean isEmpty() {
        return memberIds.isEmpty();
    }

    public static ParticleGroupData create(UUID id, Vec3 pivot) {
        return new ParticleGroupData(id, pivot);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ParticleGroupData that)) return false;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "ParticleGroupData{" + id + " members=" + memberIds.size() + "}";
    }
}
