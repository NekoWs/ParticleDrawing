package work.nekow.particledrawing.core.client;

import net.minecraft.world.phys.Vec3;
import work.nekow.particledrawing.api.Color;
import work.nekow.particledrawing.api.ParticleStyle;
import work.nekow.particledrawing.core.easing.EasingCurve;
import work.nekow.particledrawing.core.easing.EasingType;

import java.util.UUID;

/**
 * Client-side particle state, updated every frame on the render thread.
 * Interpolates between current state and target state using an easing curve.
 */
@SuppressWarnings("unused")
public final class RenderParticle {

    private final UUID id;
    private final ParticleStyle style;

    private double curX, curY, curZ;
    private double tgtX, tgtY, tgtZ;

    private float curR, curG, curB, curA;
    private float tgtR, tgtG, tgtB, tgtA;

    private float curScale;
    private float tgtScale;

    private boolean glowing;
    private long deathTime;      // System.nanoTime when this expires (0 = immortal)

    private EasingCurve easing;
    private long easeDurationNs;
    private long easeStartTime;

    RenderParticle(UUID id, ParticleStyle style, Vec3 position, Color color,
                   float scale, boolean glowing, long lifetimeMs) {
        this.id = id;
        this.style = style;
        this.curX = this.tgtX = position.x;
        this.curY = this.tgtY = position.y;
        this.curZ = this.tgtZ = position.z;
        this.curR = this.tgtR = color.r();
        this.curG = this.tgtG = color.g();
        this.curB = this.tgtB = color.b();
        this.curA = this.tgtA = color.a();
        this.curScale = this.tgtScale = scale;
        this.glowing = glowing;
        this.deathTime = lifetimeMs > 0 ? System.nanoTime() + lifetimeMs * 1_000_000L : 0;
        this.easing = EasingCurvePresets.LINEAR;
        this.easeDurationNs = 0;
        this.easeStartTime = 0;
    }

    public UUID id() { return id; }
    public ParticleStyle style() { return style; }
    public boolean glowing() { return glowing; }

    public double x() { return curX; }
    public double y() { return curY; }
    public double z() { return curZ; }
    public float r() { return curR; }
    public float g() { return curG; }
    public float b() { return curB; }
    public float a() { return curA; }
    public float scale() { return curScale; }

    public boolean isAlive() {
        if (deathTime == 0) return true;
        return System.nanoTime() < deathTime;
    }

    public boolean isDead() {
        return !isAlive();
    }

    /**
     * Set a new target with easing, called when server sends an update.
     */
    public void setTarget(Vec3 position, Color color, float scale, EasingType easingType, long durationMs) {
        this.tgtX = position.x;
        this.tgtY = position.y;
        this.tgtZ = position.z;
        this.tgtR = color.r();
        this.tgtG = color.g();
        this.tgtB = color.b();
        this.tgtA = color.a();
        this.tgtScale = scale;
        this.easing = easingType.curve();
        this.easeDurationNs = durationMs * 1_000_000L;
        this.easeStartTime = System.nanoTime();
    }

    public void setPositionDirect(Vec3 position) {
        this.curX = this.tgtX = position.x;
        this.curY = this.tgtY = position.y;
        this.curZ = this.tgtZ = position.z;
        this.easeStartTime = 0;
    }

    public void setColorDirect(Color color) {
        this.curR = this.tgtR = color.r();
        this.curG = this.tgtG = color.g();
        this.curB = this.tgtB = color.b();
        this.curA = this.tgtA = color.a();
        this.easeStartTime = 0;
    }

    public void setGlowing(boolean glowing) {
        this.glowing = glowing;
    }

    public void setLifetime(long lifetimeMs) {
        this.deathTime = lifetimeMs > 0 ? System.nanoTime() + lifetimeMs * 1_000_000L : 0;
    }

    /**
     * Called every frame to advance interpolation.
     */
    public void tick() {
        if (easeStartTime == 0) return;

        long now = System.nanoTime();
        long elapsed = now - easeStartTime;

        if (elapsed >= easeDurationNs) {
            curX = tgtX;
            curY = tgtY;
            curZ = tgtZ;
            curR = tgtR;
            curG = tgtG;
            curB = tgtB;
            curA = tgtA;
            curScale = tgtScale;
            easeStartTime = 0;
            return;
        }

        float t = (float) elapsed / easeDurationNs;
        float easedT = easing.evaluate(t);

        curX = lerp(curX, tgtX, easedT);
        curY = lerp(curY, tgtY, easedT);
        curZ = lerp(curZ, tgtZ, easedT);
        curR = lerp(curR, tgtR, easedT);
        curG = lerp(curG, tgtG, easedT);
        curB = lerp(curB, tgtB, easedT);
        curA = lerp(curA, tgtA, easedT);
        curScale = lerp(curScale, tgtScale, easedT);
    }

    private static double lerp(double a, double b, float t) {
        return a + (b - a) * t;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static final class EasingCurvePresets {
        static final EasingCurve LINEAR = new EasingCurve(0, 0, 1, 1);
    }
}
