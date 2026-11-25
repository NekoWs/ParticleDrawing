package work.nekow.particledrawing.command.v2

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.api.Draw
import work.nekow.particledrawing.api.ParticleGroup
import work.nekow.particledrawing.api.ParticleManager
import work.nekow.particledrawing.command.ParticleCommandOptions
import work.nekow.particledrawing.i18n.LocalizedArgumentException
import work.nekow.particledrawing.i18n.translation
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal object FigureDrawing {
    internal data class EdgePoint(val position: Vec3, val edge: Int)

    data class Plan(
        val shape: String,
        val options: ParticleCommandOptions,
        val lifetime: Int,
        val positions: Map<String, Vec3>,
        val numbers: Map<String, Double>,
        val text: String? = null,
    )

    internal fun cuboidEdges(center: Vec3, numbers: Map<String, Double>): List<EdgePoint> {
        val x = numbers.getValue("width") / 2.0
        val y = numbers.getValue("height") / 2.0
        val z = numbers.getValue("depth") / 2.0
        val density = numbers.getValue("density").toInt()
        val corners = listOf(
            center.add(-x, -y, -z), center.add(x, -y, -z), center.add(x, -y, z), center.add(-x, -y, z),
            center.add(-x, y, -z), center.add(x, y, -z), center.add(x, y, z), center.add(-x, y, z),
        )
        val seen = BooleanArray(corners.size)
        val points = ArrayList<EdgePoint>(8 + 12 * (density - 2))
        var edge = 0
        fun addEdge(from: Int, to: Int) {
            val startSize = points.size
            if (!seen[from]) points.add(EdgePoint(corners[from], edge))
            for (i in 1 until density - 1) {
                points.add(EdgePoint(corners[from].lerp(corners[to], i.toDouble() / (density - 1)), edge))
            }
            if (!seen[to]) points.add(EdgePoint(corners[to], edge))
            seen[from] = true
            seen[to] = true
            if (points.size > startSize) edge++
        }
        for (i in 0..3) addEdge(i, (i + 1) % 4)
        for (i in 0..3) addEdge(i, i + 4)
        for (i in 0..3) addEdge(i + 4, (i + 1) % 4 + 4)
        return points
    }

    internal fun render(plan: Plan, m: ParticleManager, group: ParticleGroup) {
        val o = plan.options
        when (plan.shape) {
            "dot" -> Draw.dot(m, plan.positions.getValue("pos"), o.color, o.scale, group, o.visual, o.lifeCurve, plan.lifetime)
            "line" -> Draw.line(
                m, plan.positions.getValue("start"), plan.positions.getValue("end"),
                plan.numbers.getValue("count").toInt(), o.color, o.scale, o.stagger, group, o.visual, o.taper, o.lifeCurve, plan.lifetime,
            )
            "beam" -> Draw.polyline(
                m, listOf(plan.positions.getValue("start"), plan.positions.getValue("end")),
                o.thickness, o.taper, o.visual.texture, o.visual.additive, o.visual.glowing, o.color, o.stagger,
                group, o.lifeCurve, plan.lifetime,
            )
            "helix" -> {
                val center = plan.positions.getValue("center")
                val radius = plan.numbers.getValue("radius")
                val height = plan.numbers.getValue("height")
                val turns = plan.numbers.getValue("turns")
                Draw.curve(m, { t ->
                    val a = t * turns * PI * 2.0
                    center.add(cos(a) * radius, (t - 0.5) * height, sin(a) * radius)
                }, plan.numbers.getValue("count").toInt(), o.color, o.scale, o.stagger, group, o.visual, o.taper, o.lifeCurve, plan.lifetime)
            }
            "circle" -> Draw.circle(
                m, plan.positions.getValue("center"), plan.numbers.getValue("radius"),
                plan.numbers.getValue("count").toInt(), o.axis, o.color, o.scale, o.stagger, group, o.visual, o.lifeCurve, plan.lifetime,
            )
            "disc" -> Draw.disc(
                m, plan.positions.getValue("center"), plan.numbers.getValue("radius"),
                plan.numbers.getValue("perimeter").toInt(), plan.numbers.getValue("layers").toInt(), o.axis,
                o.color, o.scale, o.stagger, group, o.visual, o.lifeCurve, plan.lifetime,
            )
            "triangle" -> Draw.triangle(
                m, plan.positions.getValue("center"), plan.numbers.getValue("radius"),
                plan.numbers.getValue("segments").toInt(), 0.0, o.axis, o.color, o.scale, o.stagger, group, o.visual, o.lifeCurve, plan.lifetime,
            )
            "hexagram" -> Draw.hexagram(
                m, plan.positions.getValue("center"), plan.numbers.getValue("radius"),
                plan.numbers.getValue("segments").toInt(), o.axis, o.color, o.color, o.scale, o.stagger, group, o.visual, o.lifeCurve, plan.lifetime,
            )
            "rect" -> Draw.rect(
                m, plan.positions.getValue("center"), plan.numbers.getValue("width"), plan.numbers.getValue("height"),
                plan.numbers.getValue("density").toInt(), o.hollow, o.axis, o.color, o.scale, o.stagger, group, o.visual, o.lifeCurve, plan.lifetime,
            )
            "sphere" -> Draw.sphere(
                m, plan.positions.getValue("center"), plan.numbers.getValue("radius"),
                plan.numbers.getValue("count").toInt(), o.color, o.scale, o.stagger, group, o.visual, o.lifeCurve, plan.lifetime,
            )
            "text" -> {
                val center = plan.positions.getValue("center")
                val count = plan.numbers["count"]?.toInt() ?: 1024
                val height = plan.numbers["height"] ?: 2.0
                val points = Draw.textPoints(plan.text.orEmpty(), center, count, height)
                Draw.points(m, center, points, o.color, o.scale, o.stagger, group, o.visual, o.lifeCurve, plan.lifetime)
            }
            "cuboid" -> Draw.cuboid(
                m, plan.positions.getValue("center"), plan.numbers.getValue("width"), plan.numbers.getValue("height"),
                plan.numbers.getValue("depth"), plan.numbers.getValue("density").toInt(), o.hollow,
                o.color, o.scale, o.stagger, group, o.visual, o.lifeCurve, plan.lifetime,
            )
            else -> throw LocalizedArgumentException(translation("error.command.v2.expected", "shape", plan.shape))
        }
    }
}
