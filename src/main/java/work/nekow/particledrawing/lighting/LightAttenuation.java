package work.nekow.particledrawing.lighting;

/**
 * Defines how dynamic light brightness attenuates over distance.
 * Implementations must be thread-safe and fast (called per-light-source per-frame).
 */
@FunctionalInterface
public interface LightAttenuation {

    /**
     * Evaluates the attenuation factor at a given distance.
     *
     * @param distance    the distance from the light source, in blocks
     * @param maxDistance the maximum influence radius of this light source
     * @return attenuation factor in [0, 1], where 1 = full brightness, 0 = no contribution
     */
    float evaluate(float distance, float maxDistance);

    LightAttenuation LINEAR = (distance, maxDistance) -> {
        if (distance >= maxDistance) return 0f;
        return 1f - (distance / maxDistance);
    };

    LightAttenuation INVERSE_SQUARE = (distance, maxDistance) -> {
        if (distance >= maxDistance) return 0f;
        float t = distance / maxDistance;
        return 1f / (1f + t * t * 8f);
    };

    LightAttenuation SMOOTHSTEP = (distance, maxDistance) -> {
        if (distance >= maxDistance) return 0f;
        float t = distance / maxDistance;
        return 1f - (t * t * (3f - 2f * t));
    };

    LightAttenuation INVERSE_LINEAR = (distance, maxDistance) -> {
        if (distance >= maxDistance) return 0f;
        return 1f / (1f + distance * 2f);
    };

    /**
     * Creates a cubic-bezier-based attenuation.
     */
    static LightAttenuation bezier(double x1, double y1, double x2, double y2) {
        work.nekow.particledrawing.core.easing.EasingCurve curve =
            new work.nekow.particledrawing.core.easing.EasingCurve(x1, y1, x2, y2);
        return (distance, maxDistance) -> {
            if (distance >= maxDistance) return 0f;
            float t = distance / maxDistance;
            return 1f - curve.evaluate(t);
        };
    }
}
