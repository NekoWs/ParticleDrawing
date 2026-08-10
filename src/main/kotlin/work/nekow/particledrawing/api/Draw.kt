package work.nekow.particledrawing.api

import net.minecraft.world.phys.Vec3

/**
 * High-level drawing utilities for creating particle shapes.
 *
 * Each method returns a [ParticleGroup] that can be further
 * animated with move, rotate, recolor, and scale operations.
 *
 * Examples:
 * ```
 * val circle = Draw.circle(manager, center, 5.0, 64, Draw.Axis.XZ)
 * circle.rotate(Vec3.Z, Math.PI * 2, 100, EasingType.EASE_IN_OUT)
 * circle.recolor(Color.RED, 40, EasingType.EASE_OUT)
 * ```
 */
@Suppress("unused")
object Draw {

    private val DEFAULT_COLOR = Color.WHITE
    private val DEFAULT_STYLE = ParticleStyle.DUST
    private const val DEFAULT_SCALE = 0.5f

    /**
     * Draw a line of particles between two points.
     *
     * @param manager the particle manager
     * @param start   start point
     * @param end     end point
     * @param count   number of particles along the line
     * @return a group containing all particles on the line
     */
    fun line(manager: ParticleManager, start: Vec3, end: Vec3, count: Int): ParticleGroup {
        return line(manager, start, end, count, DEFAULT_COLOR, DEFAULT_STYLE, DEFAULT_SCALE)
    }

    fun line(manager: ParticleManager, start: Vec3, end: Vec3, count: Int, color: Color): ParticleGroup {
        return line(manager, start, end, count, color, DEFAULT_STYLE, DEFAULT_SCALE)
    }

    fun line(
        manager: ParticleManager, start: Vec3, end: Vec3,
        count: Int, color: Color, style: ParticleStyle, scale: Float
    ): ParticleGroup {
        val pivot = start.add(end).scale(0.5)
        val group = manager.createGroup(pivot)

        val dir = end.subtract(start)
        val length = dir.length()
        if (length < 0.0001) return group

        for (i in 0 until count) {
            val t = if (count > 1) i.toDouble() / (count - 1) else 0.5
            val pos = start.add(dir.scale(t))
            val offset = pos.subtract(pivot)

            val handle = manager.create()
                .style(style)
                .position(pos)
                .color(color)
                .scale(scale)
                .lifetime(-1)
                .group(group.id)
                .offsetFromPivot(offset)
                .spawn()
            group.add(handle)
        }

        return group
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
    fun circle(
        manager: ParticleManager, center: Vec3,
        radius: Double, count: Int, axis: Axis
    ): ParticleGroup {
        return circle(manager, center, radius, count, axis, DEFAULT_COLOR, DEFAULT_STYLE, DEFAULT_SCALE)
    }

    fun circle(
        manager: ParticleManager, center: Vec3,
        radius: Double, count: Int, axis: Axis, color: Color
    ): ParticleGroup {
        return circle(manager, center, radius, count, axis, color, DEFAULT_STYLE, DEFAULT_SCALE)
    }

    fun circle(
        manager: ParticleManager, center: Vec3,
        radius: Double, count: Int, axis: Axis,
        color: Color, style: ParticleStyle, scale: Float
    ): ParticleGroup {
        val group = manager.createGroup(center)

        for (i in 0 until count) {
            val angle = 2.0 * Math.PI * i / count
            val u = Math.cos(angle) * radius
            val v = Math.sin(angle) * radius

            val pos = when (axis) {
                Axis.XZ -> Vec3(center.x + u, center.y, center.z + v)
                Axis.XY -> Vec3(center.x + u, center.y + v, center.z)
                Axis.YZ -> Vec3(center.x, center.y + u, center.z + v)
            }
            val offset = pos.subtract(center)

            val handle = manager.create()
                .style(style)
                .position(pos)
                .color(color)
                .scale(scale)
                .lifetime(-1)
                .group(group.id)
                .offsetFromPivot(offset)
                .spawn()
            group.add(handle)
        }

        return group
    }

    /**
     * Draw a filled circle (disc) by layering concentric circles.
     *
     * @param layers number of concentric rings from center to edge
     */
    fun disc(
        manager: ParticleManager, center: Vec3,
        radius: Double, perimeterCount: Int, layers: Int,
        axis: Axis
    ): ParticleGroup {
        return disc(manager, center, radius, perimeterCount, layers, axis,
            DEFAULT_COLOR, DEFAULT_STYLE, DEFAULT_SCALE)
    }

    fun disc(
        manager: ParticleManager, center: Vec3,
        radius: Double, perimeterCount: Int, layers: Int,
        axis: Axis, color: Color, style: ParticleStyle, scale: Float
    ): ParticleGroup {
        val group = manager.createGroup(center)

        for (layer in 0..layers) {
            val r = radius * layer / maxOf(1, layers)
            val n = maxOf(1, (perimeterCount * r / maxOf(0.001, radius)).toInt())
            for (i in 0 until n) {
                val angle = 2.0 * Math.PI * i / n
                val u = Math.cos(angle) * r
                val v = Math.sin(angle) * r

                val pos = when (axis) {
                    Axis.XZ -> Vec3(center.x + u, center.y, center.z + v)
                    Axis.XY -> Vec3(center.x + u, center.y + v, center.z)
                    Axis.YZ -> Vec3(center.x, center.y + u, center.z + v)
                }
                val offset = pos.subtract(center)

                val handle = manager.create()
                    .style(style)
                    .position(pos)
                    .color(color)
                    .scale(scale)
                    .lifetime(-1)
                    .group(group.id)
                    .offsetFromPivot(offset)
                    .spawn()
                group.add(handle)
            }
        }

        return group
    }

    /**
     * Spawn particles along a parametric curve defined by a position function.
     *
     * @param posFunc function taking t in [0, 1] and returning a world position
     * @param steps   number of sampling points
     */
    fun curve(
        manager: ParticleManager,
        posFunc: (Double) -> Vec3,
        steps: Int, color: Color, style: ParticleStyle, scale: Float
    ): ParticleGroup {
        val first = posFunc(0.0)
        val last = posFunc(1.0)
        val pivot = first.add(last).scale(0.5)
        val group = manager.createGroup(pivot)

        for (i in 0 until steps) {
            val t = i.toDouble() / maxOf(1, steps - 1)
            val pos = posFunc(t)
            val offset = pos.subtract(pivot)

            val handle = manager.create()
                .style(style)
                .position(pos)
                .color(color)
                .scale(scale)
                .lifetime(-1)
                .group(group.id)
                .offsetFromPivot(offset)
                .spawn()
            group.add(handle)
        }

        return group
    }

    /**
     * Describes the plane on which a 2D shape is drawn.
     */
    enum class Axis {
        XZ,
        XY,
        YZ
    }
}
