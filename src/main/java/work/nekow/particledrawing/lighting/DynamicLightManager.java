package work.nekow.particledrawing.lighting;

import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import work.nekow.particledrawing.config.ParticleDrawingConfig;
import work.nekow.particledrawing.core.client.ClientParticleEngine;
import work.nekow.particledrawing.core.client.RenderParticle;

import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Manages dynamic light sources from glowing particles.
 * Maintains a sorted pool of the top-N closest/brightest light sources per frame.
 *
 * <p>Thread safety: writes happen on the render thread; reads may happen
 * on the chunk building thread (LightEngine) via mixin calls.
 */
public final class DynamicLightManager {

    private static final ReentrantReadWriteLock LOCK = new ReentrantReadWriteLock();
    private static final List<LightEntry> ACTIVE_LIGHTS = new ArrayList<>();
    private static volatile int lightCount = 0;

    private DynamicLightManager() {}

    /**
     * Called each frame on the render thread to update the light pool.
     */
    public static void renderDynamicLights(ClientParticleEngine engine, Camera camera) {
        if (!ParticleDrawingConfig.CLIENT.enableDynamicLights.get()) {
            LOCK.writeLock().lock();
            try {
                ACTIVE_LIGHTS.clear();
                lightCount = 0;
            } finally {
                LOCK.writeLock().unlock();
            }
            return;
        }

        List<RenderParticle> glowing = engine.getGlowingParticles();
        if (glowing.isEmpty()) {
            LOCK.writeLock().lock();
            try {
                ACTIVE_LIGHTS.clear();
                lightCount = 0;
            } finally {
                LOCK.writeLock().unlock();
            }
            return;
        }

        Vec3 camPos = camera.position();
        double maxDist = ParticleDrawingConfig.CLIENT.dynamicLightMaxDistance.get();
        int maxLights = ParticleDrawingConfig.CLIENT.maxDynamicLights.get();

        // Score each glowing particle by brightness / distance
        List<LightEntry> entries = new ArrayList<>(glowing.size());
        for (RenderParticle p : glowing) {
            double dx = p.x() - camPos.x;
            double dy = p.y() - camPos.y;
            double dz = p.z() - camPos.z;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist > maxDist) continue;

            float brightness = p.r() * 0.3f + p.g() * 0.59f + p.b() * 0.11f; // luminance
            float score = brightness / (float)(1.0 + dist * 0.1);
            entries.add(new LightEntry(p.x(), p.y(), p.z(), p.r(), p.g(), p.b(), p.a(), brightness, score));
        }

        // Sort by score descending, take top N
        entries.sort((a, b) -> Float.compare(b.score, a.score));

        LOCK.writeLock().lock();
        try {
            ACTIVE_LIGHTS.clear();
            int count = Math.min(entries.size(), maxLights);
            for (int i = 0; i < count; i++) {
                ACTIVE_LIGHTS.add(entries.get(i));
            }
            lightCount = count;
        } finally {
            LOCK.writeLock().unlock();
        }
    }

    /**
     * Query the dynamic light level at a world position.
     * Called from LightEngine mixin (possibly on another thread).
     * Returns a light level in [0, 15] contributed by dynamic lights.
     */
    public static int getDynamicLightLevel(double x, double y, double z) {
        if (lightCount == 0) return 0;

        LOCK.readLock().lock();
        try {
            double maxContrib = 0;
            double maxDistConfig = ParticleDrawingConfig.CLIENT.dynamicLightMaxDistance.get();

            for (LightEntry light : ACTIVE_LIGHTS) {
                double dx = x - light.x;
                double dy = y - light.y;
                double dz = z - light.z;
                double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (dist > maxDistConfig) continue;

                double atten = LightAttenuation.SMOOTHSTEP.evaluate((float) dist, (float) maxDistConfig);
                double contrib = light.brightness * atten * 15.0;
                if (contrib > maxContrib) {
                    maxContrib = contrib;
                }
            }

            return (int) Math.round(maxContrib);
        } finally {
            LOCK.readLock().unlock();
        }
    }

    /**
     * Query the combined dynamic light pack (sky << 20 | block << 4).
     * Used by entity renderer mixin.
     */
    public static int getDynamicLightPacked(double x, double y, double z) {
        int level = getDynamicLightLevel(x, y, z);
        if (level <= 0) return 0;
        return (level << 4) | level;
    }

    /**
     * Internal light entry for sorting.
     */
    private static final class LightEntry {
        final double x, y, z;
        final float r, g, b, a;
        final float brightness;
        final float score;

        LightEntry(double x, double y, double z, float r, float g, float b, float a,
                   float brightness, float score) {
            this.x = x; this.y = y; this.z = z;
            this.r = r; this.g = g; this.b = b; this.a = a;
            this.brightness = brightness;
            this.score = score;
        }
    }
}
