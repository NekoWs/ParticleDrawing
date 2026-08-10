package work.nekow.particledrawing.core.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.world.phys.Vec3;
import work.nekow.particledrawing.api.Color;
import work.nekow.particledrawing.api.ParticleStyle;
import work.nekow.particledrawing.core.easing.EasingType;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side particle engine running on the render thread.
 * Uses {@link RenderParticle} for easing/interpolation state,
 * and {@link BridgeParticle} (via vanilla {@link ParticleEngine}) for batched rendering.
 */
public final class ClientParticleEngine {

    private static ClientParticleEngine INSTANCE;

    private final Map<UUID, RenderParticle> particles;
    private final Map<UUID, BridgeParticle> bridges;
    private final Map<UUID, Set<UUID>> groups;

    public ClientParticleEngine() {
        this.particles = new ConcurrentHashMap<>();
        this.bridges = new ConcurrentHashMap<>();
        this.groups = new ConcurrentHashMap<>();
    }

    public static void init() { INSTANCE = new ClientParticleEngine(); }
    public static ClientParticleEngine instance() { return INSTANCE; }
    public static void dispose() { INSTANCE = null; }

    public void spawnParticle(UUID id, ParticleStyle style, double x, double y, double z,
                               float r, float g, float b, float a, float scale,
                               int lifetimeTicks, UUID groupId, boolean glowing) {
        long lifetimeMs = lifetimeTicks > 0 ? lifetimeTicks * 50L : 0;
        RenderParticle rp = new RenderParticle(id, style, new Vec3(x, y, z),
            Color.of(r, g, b, a), scale, glowing, lifetimeMs);
        particles.put(id, rp);

        ParticleEngine pe = Minecraft.getInstance().particleEngine;
        if (pe != null) {
            var level = Minecraft.getInstance().level;
            if (level != null) {
                BridgeParticle bp = new BridgeParticle(id, style, level, x, y, z,
                    Color.of(r, g, b, a), scale, glowing);
                pe.add(bp);
                bridges.put(id, bp);
            }
        }

        if (groupId != null) {
            groups.computeIfAbsent(groupId, k -> ConcurrentHashMap.newKeySet()).add(id);
        }
    }

    public void updateParticle(UUID id, double x, double y, double z,
                                float r, float g, float b, float a, float scale,
                                boolean hasPos, boolean hasColor, boolean hasScale,
                                int durationTicks, EasingType easing) {
        RenderParticle rp = particles.get(id);
        if (rp == null) return;

        long durationMs = durationTicks * 50L;
        Vec3 pos = new Vec3(hasPos ? x : rp.x(), hasPos ? y : rp.y(), hasPos ? z : rp.z());
        Color color = Color.of(
            hasColor ? r : rp.r(), hasColor ? g : rp.g(),
            hasColor ? b : rp.b(), hasColor ? a : rp.a());
        float scl = hasScale ? scale : rp.scale();
        rp.setTarget(pos, color, scl, easing, durationMs);
    }

    public void destroyParticles(UUID[] ids) {
        for (UUID id : ids) {
            particles.remove(id);
            BridgeParticle bp = bridges.remove(id);
            if (bp != null) {
                bp.remove();
            }
        }
        for (Set<UUID> gms : groups.values()) {
            for (UUID id : ids) gms.remove(id);
        }
    }

    public void applyGroupTransform(UUID groupId, int transformType,
                                     double dx, double dy, double dz,
                                     double ax, double ay, double az, double radians,
                                     float r, float g, float b, float a,
                                     float targetScale, double px, double py, double pz,
                                     int durationTicks, EasingType easing) {
        Set<UUID> members = groups.get(groupId);
        if (members == null) return;

        Vec3 pivot = new Vec3(px, py, pz);
        long durationMs = durationTicks * 50L;

        for (UUID memberId : members) {
            RenderParticle rp = particles.get(memberId);
            if (rp == null) continue;

            Vec3 curPos = new Vec3(rp.x(), rp.y(), rp.z());
            Vec3 newPos;
            Color newColor;
            float newScale = rp.scale();

            if (transformType == 0) {
                newPos = curPos.add(dx, dy, dz);
                newColor = Color.of(rp.r(), rp.g(), rp.b(), rp.a());
            } else if (transformType == 1) {
                Vec3 rel = curPos.subtract(pivot);
                Vec3 axis = new Vec3(ax, ay, az).normalize();
                Vec3 rotated = rotateAroundAxis(rel, axis, radians);
                newPos = pivot.add(rotated);
                newColor = Color.of(rp.r(), rp.g(), rp.b(), rp.a());
            } else if (transformType == 2) {
                newPos = curPos;
                newColor = Color.of(r, g, b, a);
            } else if (transformType == 3) {
                Vec3 rel = curPos.subtract(pivot);
                newPos = pivot.add(rel.scale(targetScale));
                newScale = targetScale;
                newColor = Color.of(rp.r(), rp.g(), rp.b(), rp.a());
            } else {
                continue;
            }

            rp.setTarget(newPos, newColor, newScale, easing, durationMs);
        }
    }

    /**
     * Called every render frame to advance interpolation and sync to BridgeParticle.
     */
    public void frameUpdate() {
        for (RenderParticle rp : particles.values()) {
            rp.tick();

            BridgeParticle bp = bridges.get(rp.id());
            if (bp != null) {
                bp.syncPosition(rp.x(), rp.y(), rp.z());
                bp.syncColor(rp.r(), rp.g(), rp.b(), rp.a());
                bp.syncScale(rp.scale());
            }
        }

        Iterator<Map.Entry<UUID, RenderParticle>> it = particles.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, RenderParticle> entry = it.next();
            if (entry.getValue().isDead()) {
                UUID id = entry.getKey();
                BridgeParticle bp = bridges.remove(id);
                if (bp != null) {
                    bp.remove();
                }
                it.remove();
            }
        }

        groups.values().removeIf(Set::isEmpty);
    }

    public int activeCount() { return particles.size(); }

    public List<RenderParticle> getGlowingParticles() {
        List<RenderParticle> glowing = new ArrayList<>();
        for (RenderParticle p : particles.values()) {
            if (p.glowing() && p.isAlive() && p.a() > 0.01f) {
                glowing.add(p);
            }
        }
        return glowing;
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
}
