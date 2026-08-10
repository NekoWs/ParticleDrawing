package work.nekow.particledrawing.api;

import net.minecraft.world.phys.Vec3;

/**
 * High-level drawing utilities for creating particle shapes.
 *
 * <p>Each method returns a {@link ParticleGroup} that can be further
 * animated with move, rotate, recolor, and scale operations.
 *
 * <p>Examples:
 * <pre>{@code
 * ParticleGroup circle = Draw.circle(manager, center, 5, 64, Axis.XZ);
 * circle.rotate(Vec3.Z, Math.PI * 2, 100, EasingType.EASE_IN_OUT);
 * circle.recolor(Color.RED, 40, EasingType.EASE_OUT);
 * }</pre>
 */
@SuppressWarnings("unused")
public final class Draw {

    private Draw() {}

    private static final Color DEFAULT_COLOR = Color.WHITE;
    private static final ParticleStyle DEFAULT_STYLE = ParticleStyle.DUST;
    private static final float DEFAULT_SCALE = 0.5f;

    /**
     * Draw a line of particles between two points.
     *
     * @param manager the particle manager
     * @param start   start point
     * @param end     end point
     * @param count   number of particles along the line
     * @return a group containing all particles on the line
     */
    public static ParticleGroup line(ParticleManager manager, Vec3 start, Vec3 end, int count) {
        return line(manager, start, end, count, DEFAULT_COLOR, DEFAULT_STYLE, DEFAULT_SCALE);
    }

    public static ParticleGroup line(ParticleManager manager, Vec3 start, Vec3 end,
                                      int count, Color color) {
        return line(manager, start, end, count, color, DEFAULT_STYLE, DEFAULT_SCALE);
    }

    public static ParticleGroup line(ParticleManager manager, Vec3 start, Vec3 end,
                                      int count, Color color, ParticleStyle style, float scale) {
        Vec3 pivot = start.add(end).scale(0.5);
        ParticleGroup group = manager.createGroup(pivot);

        Vec3 dir = end.subtract(start);
        double length = dir.length();
        if (length < 0.0001) return group;

        for (int i = 0; i < count; i++) {
            double t = count > 1 ? (double) i / (count - 1) : 0.5;
            Vec3 pos = start.add(dir.scale(t));
            Vec3 offset = pos.subtract(pivot);

            ParticleHandle handle = manager.create()
                .style(style)
                .position(pos)
                .color(color)
                .scale(scale)
                .lifetime(-1) // immortal by default
                .group(group.id())
                .offsetFromPivot(offset)
                .spawn();
            group.add(handle);
        }

        return group;
    }

    /**
     * Draw a circle of particles.
     *
     * @param manager the particle manager
     * @param center  center point of the circle
     * @param radius  circle radius
     * @param count   number of particles
     * @param axis    the plane to draw on (XZ = horizontal, XY = vertical facing Z, YZ = vertical facing X)
     */
    public static ParticleGroup circle(ParticleManager manager, Vec3 center,
                                        double radius, int count, Axis axis) {
        return circle(manager, center, radius, count, axis, DEFAULT_COLOR, DEFAULT_STYLE, DEFAULT_SCALE);
    }

    public static ParticleGroup circle(ParticleManager manager, Vec3 center,
                                        double radius, int count, Axis axis, Color color) {
        return circle(manager, center, radius, count, axis, color, DEFAULT_STYLE, DEFAULT_SCALE);
    }

    public static ParticleGroup circle(ParticleManager manager, Vec3 center,
                                        double radius, int count, Axis axis,
                                        Color color, ParticleStyle style, float scale) {
        ParticleGroup group = manager.createGroup(center);

        for (int i = 0; i < count; i++) {
            double angle = 2.0 * Math.PI * i / count;
            double u = Math.cos(angle) * radius;
            double v = Math.sin(angle) * radius;

            Vec3 pos = switch (axis) {
                case XZ -> new Vec3(center.x + u, center.y, center.z + v);
                case XY -> new Vec3(center.x + u, center.y + v, center.z);
                case YZ -> new Vec3(center.x, center.y + u, center.z + v);
            };
            Vec3 offset = pos.subtract(center);

            ParticleHandle handle = manager.create()
                .style(style)
                .position(pos)
                .color(color)
                .scale(scale)
                .lifetime(-1)
                .group(group.id())
                .offsetFromPivot(offset)
                .spawn();
            group.add(handle);
        }

        return group;
    }

    /**
     * Draw a filled circle (disc) by layering concentric circles.
     *
     * @param layers number of concentric rings from center to edge
     */
    public static ParticleGroup disc(ParticleManager manager, Vec3 center,
                                      double radius, int perimeterCount, int layers,
                                      Axis axis) {
        return disc(manager, center, radius, perimeterCount, layers, axis,
            DEFAULT_COLOR, DEFAULT_STYLE, DEFAULT_SCALE);
    }

    public static ParticleGroup disc(ParticleManager manager, Vec3 center,
                                      double radius, int perimeterCount, int layers,
                                      Axis axis, Color color, ParticleStyle style, float scale) {
        ParticleGroup group = manager.createGroup(center);

        for (int layer = 0; layer <= layers; layer++) {
            double r = radius * layer / Math.max(1, layers);
            int n = Math.max(1, (int)(perimeterCount * r / Math.max(0.001, radius)));
            for (int i = 0; i < n; i++) {
                double angle = 2.0 * Math.PI * i / n;
                double u = Math.cos(angle) * r;
                double v = Math.sin(angle) * r;

                Vec3 pos = switch (axis) {
                    case XZ -> new Vec3(center.x + u, center.y, center.z + v);
                    case XY -> new Vec3(center.x + u, center.y + v, center.z);
                    case YZ -> new Vec3(center.x, center.y + u, center.z + v);
                };
                Vec3 offset = pos.subtract(center);

                ParticleHandle handle = manager.create()
                    .style(style)
                    .position(pos)
                    .color(color)
                    .scale(scale)
                    .lifetime(-1)
                    .group(group.id())
                    .offsetFromPivot(offset)
                    .spawn();
                group.add(handle);
            }
        }

        return group;
    }

    /**
     * Spawn particles along a parametric curve defined by a position function.
     *
     * @param posFunc function taking t in [0, 1] and returning a world position
     * @param steps   number of sampling points
     */
    public static ParticleGroup curve(ParticleManager manager,
                                       java.util.function.Function<Double, Vec3> posFunc,
                                       int steps, Color color, ParticleStyle style, float scale) {
        Vec3 first = posFunc.apply(0.0);
        Vec3 last = posFunc.apply(1.0);
        Vec3 pivot = first.add(last).scale(0.5);
        ParticleGroup group = manager.createGroup(pivot);

        for (int i = 0; i < steps; i++) {
            double t = (double) i / Math.max(1, steps - 1);
            Vec3 pos = posFunc.apply(t);
            Vec3 offset = pos.subtract(pivot);

            ParticleHandle handle = manager.create()
                .style(style)
                .position(pos)
                .color(color)
                .scale(scale)
                .lifetime(-1)
                .group(group.id())
                .offsetFromPivot(offset)
                .spawn();
            group.add(handle);
        }

        return group;
    }

    /**
     * Describes the plane on which a 2D shape is drawn.
     */
    public enum Axis {
        XZ,
        XY,
        YZ
    }
}
