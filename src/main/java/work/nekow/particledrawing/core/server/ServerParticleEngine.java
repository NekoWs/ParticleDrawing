package work.nekow.particledrawing.core.server;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import work.nekow.particledrawing.api.Color;
import work.nekow.particledrawing.api.ParticleStyle;
import work.nekow.particledrawing.api.TransformOp;
import work.nekow.particledrawing.config.ParticleDrawingConfig;
import work.nekow.particledrawing.core.easing.EasingType;
import work.nekow.particledrawing.core.network.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-authoritative particle engine, one instance per dimension.
 * Manages particle lifecycle, visibility, and network synchronization.
 */
@SuppressWarnings("unused")
public final class ServerParticleEngine {

    private static final Map<UUID, ServerParticleEngine> DIMENSION_ENGINES = new ConcurrentHashMap<>();

    private final UUID dimensionId;
    private final Map<UUID, ParticleData> particles;
    private final Map<UUID, ParticleGroupData> groups;
    private final ParticleVisibilityManager visibilityManager;
    private int tickCounter;

    ServerParticleEngine(UUID dimensionId) {
        this.dimensionId = dimensionId;
        this.particles = new ConcurrentHashMap<>();
        this.groups = new ConcurrentHashMap<>();
        this.visibilityManager = new ParticleVisibilityManager();
        this.tickCounter = 0;
    }

    public static ServerParticleEngine getOrCreate(UUID dimensionId) {
        return DIMENSION_ENGINES.computeIfAbsent(dimensionId, ServerParticleEngine::new);
    }

    public static ServerParticleEngine get(UUID dimensionId) {
        return DIMENSION_ENGINES.get(dimensionId);
    }

    /**
     * Spawn a single particle and broadcast to visible players.
     */
    @SuppressWarnings("DataFlowIssue")
    public ParticleData spawnParticle(ParticleStyle style, Vec3 position, Color color,
                                       float scale, int lifetime, UUID groupId,
                                       boolean glowing, Vec3 offsetFromPivot,
                                       Collection<ServerPlayer> playersInDimension) {
        UUID id = UUID.randomUUID();
        ParticleData data = ParticleData.create(id, style, position, color, scale,
            lifetime, groupId, glowing, offsetFromPivot);
        particles.put(id, data);

        if (groupId != null) {
            ParticleGroupData group = groups.get(groupId);
            if (group != null) {
                group.addMember(id);
            }
        }

        ParticleSpawnPayload payload = new ParticleSpawnPayload(
            id, style, position.x, position.y, position.z,
            color.r(), color.g(), color.b(), color.a(),
            scale, lifetime, groupId, glowing
        );

        sendToVisible(playersInDimension, position, payload);
        return data;
    }

    /**
     * Update a particle's properties with easing.
     */
    public void updateParticle(UUID id, Vec3 position, Color color, float scale,
                                boolean updatePos, boolean updateColor, boolean updateScale,
                                int durationTicks, EasingType easing,
                                Collection<ServerPlayer> playersInDimension) {
        ParticleData data = particles.get(id);
        if (data == null) return;

        if (updatePos) data.setPosition(position);
        if (updateColor) data.setColor(color);
        if (updateScale) data.setScale(scale);

        ParticleUpdatePayload payload;
        if (updatePos && updateColor && updateScale) {
            payload = ParticleUpdatePayload.full(id,
                position.x, position.y, position.z,
                color.r(), color.g(), color.b(), color.a(),
                scale, durationTicks, easing);
        } else if (updatePos) {
            payload = ParticleUpdatePayload.positionOnly(id,
                position.x, position.y, position.z, durationTicks, easing);
        } else if (updateColor) {
            payload = ParticleUpdatePayload.colorOnly(id,
                color.r(), color.g(), color.b(), color.a(), durationTicks, easing);
        } else {
            payload = ParticleUpdatePayload.scaleOnly(id, scale, durationTicks, easing);
        }

        sendToVisible(playersInDimension, data.position(), payload);
    }

    /**
     * Apply a group transform and send to clients.
     */
    public void applyGroupTransform(UUID groupId, TransformOp.Type transformType,
                                     Vec3 delta, Vec3 axis, double radians,
                                     Color targetColor, float targetScale,
                                     Vec3 pivot, int durationTicks, EasingType easing,
                                     Collection<ServerPlayer> playersInDimension) {
        ParticleGroupData group = groups.get(groupId);
        if (group == null) return;

        // Server-side computation of new particle positions
        List<ParticleData> groupParticles = new ArrayList<>();
        for (UUID memberId : group.memberIds()) {
            ParticleData data = particles.get(memberId);
            if (data != null) {
                groupParticles.add(data);
            }
        }

        Vec3 groupPivot = pivot != null ? pivot : group.pivot();

        switch (transformType) {
            case TRANSLATE -> {
                for (ParticleData p : groupParticles) {
                    p.setPosition(p.position().add(delta));
                }
            }
            case ROTATE -> {
                Vec3 nAxis = axis.normalize();
                for (ParticleData p : groupParticles) {
                    Vec3 rel = p.position().subtract(groupPivot);
                    Vec3 rotated = rotateAroundAxis(rel, nAxis, radians);
                    p.setPosition(groupPivot.add(rotated));
                    p.setOffsetFromPivot(rotated);
                }
            }
            case RECOLOR -> {
                for (ParticleData p : groupParticles) {
                    p.setColor(targetColor);
                }
            }
            case SCALE -> {
                for (ParticleData p : groupParticles) {
                    Vec3 rel = p.offsetFromPivot();
                    Vec3 scaled = rel.scale(targetScale);
                    p.setPosition(groupPivot.add(scaled));
                    p.setOffsetFromPivot(scaled);
                    p.setScale(targetScale);
                }
            }
        }

        // Build network payload
        ParticleGroupTransformPayload payload;
        switch (transformType) {
            case TRANSLATE -> payload = ParticleGroupTransformPayload.translate(
                groupId, delta.x, delta.y, delta.z,
                groupPivot.x, groupPivot.y, groupPivot.z,
                durationTicks, easing);
            case ROTATE -> payload = ParticleGroupTransformPayload.rotate(
                groupId, axis.x, axis.y, axis.z, radians,
                groupPivot.x, groupPivot.y, groupPivot.z,
                durationTicks, easing);
            case RECOLOR -> payload = ParticleGroupTransformPayload.recolor(
                groupId, targetColor.r(), targetColor.g(), targetColor.b(), targetColor.a(),
                durationTicks, easing);
            case SCALE -> payload = ParticleGroupTransformPayload.scale(
                groupId, targetScale, groupPivot.x, groupPivot.y, groupPivot.z,
                durationTicks, easing);
            default -> { return; }
        }

        for (ServerPlayer player : playersInDimension) {
            PacketDistributor.sendToPlayer(player, payload);
        }
    }

    public void destroyParticle(UUID id, Collection<ServerPlayer> playersInDimension) {
        ParticleData data = particles.remove(id);
        if (data == null) return;

        UUID groupId = data.groupId();
        if (groupId != null) {
            ParticleGroupData group = groups.get(groupId);
            if (group != null) {
                group.removeMember(id);
            }
        }

        ParticleDestroyPayload payload = ParticleDestroyPayload.single(id);
        sendToAllInDimension(playersInDimension, payload);
    }

    public void destroyGroup(UUID groupId, Collection<ServerPlayer> playersInDimension) {
        ParticleGroupData group = groups.remove(groupId);
        if (group == null) return;

        List<UUID> ids = new ArrayList<>(group.memberIds());
        for (UUID id : ids) {
            particles.remove(id);
        }

        ParticleDestroyPayload payload = ParticleDestroyPayload.group(groupId, ids);
        sendToAllInDimension(playersInDimension, payload);
    }

    /**
     * Called every server tick.
     */
    public void tick(Collection<ServerPlayer> playersInDimension) {
        tickCounter++;

        // Lifecycle: decrement lifetimes
        Iterator<Map.Entry<UUID, ParticleData>> it = particles.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, ParticleData> entry = it.next();
            ParticleData data = entry.getValue();
            data.tick();
            if (data.isExpired()) {
                UUID groupId = data.groupId();
                if (groupId != null) {
                    ParticleGroupData group = groups.get(groupId);
                    if (group != null) {
                        group.removeMember(entry.getKey());
                    }
                }
                ParticleDestroyPayload payload = ParticleDestroyPayload.single(entry.getKey());
                if (!playersInDimension.isEmpty()) {
                    sendToAllInDimension(playersInDimension, payload);
                }
                it.remove();
            }
        }

        // Cleanup empty groups
        groups.entrySet().removeIf(entry -> entry.getValue().isEmpty());

        // Visibility check (throttled)
        int interval = ParticleDrawingConfig.SERVER.visibilityCheckInterval.get();
        if (tickCounter % interval == 0) {
            visibilityManager.updateVisibility(particles.values(), playersInDimension,
                ParticleDrawingConfig.SERVER.visibilityRadius.get());
        }
    }

    public int particleCount() { return particles.size(); }
    public int groupCount() { return groups.size(); }

    public ParticleGroupData getGroup(UUID groupId) {
        return groups.get(groupId);
    }

    @SuppressWarnings("unused")
    public ParticleGroupData createGroup(UUID groupId, Vec3 pivot) {
        ParticleGroupData group = ParticleGroupData.create(groupId, pivot);
        groups.put(groupId, group);
        return group;
    }

    public ParticleData getParticle(UUID id) {
        return particles.get(id);
    }

    public void setOffsetFromPivot(UUID id, Vec3 offset) {
        ParticleData data = particles.get(id);
        if (data != null) {
            data.setOffsetFromPivot(offset);
        }
    }

    private void sendToVisible(Collection<ServerPlayer> players, Vec3 position,
                                CustomPacketPayload payload) {
        double radius = ParticleDrawingConfig.SERVER.visibilityRadius.get();
        for (ServerPlayer player : players) {
            if (visibilityManager.isWithinRange(player, position, radius)) {
                PacketDistributor.sendToPlayer(player, payload);
            }
        }
    }

    private void sendToAllInDimension(Collection<ServerPlayer> players, CustomPacketPayload payload) {
        for (ServerPlayer player : players) {
            PacketDistributor.sendToPlayer(player, payload);
        }
    }

    private static Vec3 rotateAroundAxis(Vec3 v, Vec3 axis, double radians) {
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        double dot = v.dot(axis);
        Vec3 cross = axis.cross(v);
        return new Vec3(
            v.x * cos + cross.x * sin + axis.x * dot * (1 - cos),
            v.y * cos + cross.y * sin + axis.y * dot * (1 - cos),
            v.z * cos + cross.z * sin + axis.z * dot * (1 - cos)
        );
    }

    public UUID dimensionId() { return dimensionId; }

    /**
     * Clears all particles and groups in this dimension, sending batched destroy
     * packets to all players before removal.
     */
    public int clearAll(Collection<ServerPlayer> playersInDimension) {
        int count = particles.size();

        if (!particles.isEmpty()) {
            UUID[] allIds = particles.keySet().toArray(new UUID[0]);
            int batchSize = 1000;

            for (int offset = 0; offset < allIds.length; offset += batchSize) {
                int end = Math.min(offset + batchSize, allIds.length);
                UUID[] batch = java.util.Arrays.copyOfRange(allIds, offset, end);
                ParticleDestroyPayload payload = new ParticleDestroyPayload(batch, null);

                for (ServerPlayer player : playersInDimension) {
                    PacketDistributor.sendToPlayer(player, payload);
                }
            }
        }

        particles.clear();
        groups.clear();
        return count;
    }

    public static void clearDimension(UUID dimensionId) {
        DIMENSION_ENGINES.remove(dimensionId);
    }
}
