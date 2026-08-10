package work.nekow.particledrawing.api;

import net.minecraft.world.phys.Vec3;

/**
 * Describes a transform operation applied to a {@link ParticleGroup}.
 * Immutable, designed for network serialization.
 */
public final class TransformOp {

    private final Type type;
    private final Vec3 delta;
    private final Vec3 axis;
    private final double radians;
    private final Vec3 pivot;
    private final Color targetColor;
    private final float targetScale;

    private TransformOp(Type type, Vec3 delta, Vec3 axis, double radians,
                        Vec3 pivot, Color targetColor, float targetScale) {
        this.type = type;
        this.delta = delta;
        this.axis = axis;
        this.radians = radians;
        this.pivot = pivot;
        this.targetColor = targetColor;
        this.targetScale = targetScale;
    }

    public Type type() { return type; }
    public Vec3 delta() { return delta; }
    public Vec3 axis() { return axis; }
    public double radians() { return radians; }
    public Vec3 pivot() { return pivot; }
    public Color targetColor() { return targetColor; }
    public float targetScale() { return targetScale; }

    public static TransformOp translate(Vec3 delta, Vec3 pivot) {
        return new TransformOp(Type.TRANSLATE, delta, null, 0, pivot, null, 0);
    }

    public static TransformOp rotate(Vec3 axis, double radians, Vec3 pivot) {
        return new TransformOp(Type.ROTATE, null, axis.normalize(), radians, pivot, null, 0);
    }

    public static TransformOp recolor(Color targetColor) {
        return new TransformOp(Type.RECOLOR, null, null, 0, null, targetColor, 0);
    }

    public static TransformOp scale(float targetScale, Vec3 pivot) {
        return new TransformOp(Type.SCALE, null, null, 0, pivot, null, targetScale);
    }

    public enum Type {
        TRANSLATE,
        ROTATE,
        RECOLOR,
        SCALE
    }

    @Override
    public String toString() {
        return "TransformOp{" + type + "}";
    }
}
