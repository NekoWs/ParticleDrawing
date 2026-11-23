package work.nekow.particledrawing.command.batch

import net.minecraft.commands.CommandSourceStack
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.api.Draw
import work.nekow.particledrawing.api.ParticleBatch
import work.nekow.particledrawing.api.ParticleManager
import work.nekow.particledrawing.command.CommandParticleGroups
import work.nekow.particledrawing.command.ParticleCommandOptionParser
import work.nekow.particledrawing.command.ParticleCommandOptions
import work.nekow.particledrawing.core.network.AnimationProgramCodecs
import work.nekow.particledrawing.i18n.LocalizedArgumentException
import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.i18n.translation
import java.util.UUID
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * `kind=<形状>` 的一次性粒子：把键解析成与 `/pdraw particle add` 相同的参数，
 * 先校验形状必填项与成员上限，通过后建组并生成粒子。
 */
internal object BatchShapes {

    data class Plan(
        val shape: String,
        val pivot: Vec3,
        val expected: Int,
        val options: ParticleCommandOptions,
        val stagger: Int,
        val lifetime: Int,
        val positions: Map<String, Vec3>,
        val numbers: Map<String, Double>,
    )

    private val POSITION_KEYS = setOf("pos", "start", "end", "center")
    private val NUMBER_KEYS = setOf(
        "radius", "height", "turns", "count", "perimeter", "layers", "segments", "density", "depth", "width",
    )
    private val OPTION_MAP = mapOf(
        "particle.lifetime" to "lifetime",
        "particle.velocity" to "velocity",
        "particle.force" to "force",
        "particle.force_ticks" to "force_ticks",
        "particle.stagger" to "stagger",
        "shape.axis" to "axis",
        "shape.hollow" to "hollow",
        "shape.taper" to "taper",
        "shape.thickness" to "thickness",
    )

    fun parse(shape: String, tokens: List<BatchToken>, source: CommandSourceStack): Plan {
        val seen = HashMap<String, Int>()
        val positions = LinkedHashMap<String, Vec3>()
        val numbers = LinkedHashMap<String, Double>()
        val options = ArrayList<String>()
        for (token in tokens) {
            val spec = BatchKeys.require(token.key, BatchKind.SHAPE)
            if (!spec.repeatable) {
                val count = seen.merge(token.key, 1, Int::plus) ?: 1
                requireLocalized(count <= 1) { translation("error.batch.duplicate_key", token.key) }
            }
            when {
                token.key == "kind" || token.key == "dryrun" -> Unit
                token.key in POSITION_KEYS -> positions[token.key] = BatchOptions.position(token.value, token.key).resolve(source)
                token.key in NUMBER_KEYS -> numbers[token.key] = BatchOptions.double(token.value, token.key)
                token.key.startsWith("appearance.") -> {
                    val field = token.key.substringAfter('.')
                    requireLocalized(field in ParticleCommandOptionParser.appearanceKeys) {
                        translation("error.recipe.appearance_key", field)
                    }
                    options += "${field}=\"${BatchOptions.text(token.value).replace("\"", "\\\"")}\""
                }
                token.key in OPTION_MAP -> options += "${OPTION_MAP.getValue(token.key)}=\"${BatchOptions.text(token.value).replace("\"", "\\\"")}\""
                else -> throw LocalizedArgumentException(translation("error.batch.unknown_key", token.key, "shape"))
            }
        }
        for (required in BatchKeys.shapeRequired(shape)) {
            requireLocalized(required in positions || required in numbers) {
                translation("error.batch.shape_required", shape, required)
            }
        }
        val parsed = ParticleCommandOptionParser.parse(options.joinToString(" "))
        val count = expectedCount(shape, numbers)
        requireLocalized(count in 1..AnimationProgramCodecs.MAX_PROGRAM_MEMBERS) {
            translation("error.batch.range", "count", 1, AnimationProgramCodecs.MAX_PROGRAM_MEMBERS)
        }
        val pivot = positions["center"] ?: positions["pos"] ?: positions["start"] ?: source.position
        return Plan(
            shape = shape,
            pivot = pivot,
            expected = count,
            options = parsed,
            stagger = parsed.stagger,
            lifetime = parsed.lifetime,
            positions = positions,
            numbers = numbers,
        )
    }

    private fun expectedCount(shape: String, numbers: Map<String, Double>): Int {
        fun int(key: String): Int = numbers[key]?.toInt() ?: throw LocalizedArgumentException(
            translation("error.batch.shape_required", shape, key),
        )
        return when (shape) {
            "dot", "beam" -> 1
            "field", "line", "helix", "circle", "sphere" -> int("count")
            "disc" -> int("perimeter") * (int("layers") + 1)
            "triangle" -> int("segments") * 3
            "hexagram" -> int("segments") * 6
            "rect" -> {
                val density = int("density")
                (density + 1) * (density + 1)
            }
            "cuboid" -> {
                val density = int("density")
                (density + 1) * (density + 1) * (density + 1)
            }
            else -> throw LocalizedArgumentException(translation("error.batch.shape", shape))
        }
    }

    /** 校验成员数上限与形状参数范围；不建组、不生成粒子。 */
    fun validate(plan: Plan, dimension: UUID, name: String) {
        validateRanges(plan)
        val existing = CommandParticleGroups.get(dimension, name)
        val total = (existing?.expectedMembers ?: 0).toLong() + plan.expected
        requireLocalized(total <= AnimationProgramCodecs.MAX_PROGRAM_MEMBERS) {
            translation("error.group.capacity", name, AnimationProgramCodecs.MAX_PROGRAM_MEMBERS)
        }
        if (existing == null) {
            requireLocalized(CommandParticleGroups.names(dimension).size < 256) { translation("error.group.limit", 256) }
        }
    }

    private fun validateRanges(plan: Plan) {
        val n = plan.numbers
        fun atLeast(key: String, min: Double) {
            n[key]?.let { requireLocalized(it >= min) { translation("error.batch.range", key, min, "∞") } }
        }
        fun between(key: String, min: Int, max: Int) {
            n[key]?.let { value ->
                requireLocalized(value == value.toInt().toDouble() && value.toInt() in min..max) {
                    translation("error.batch.range", key, min, max)
                }
            }
        }
        atLeast("radius", 0.0)
        atLeast("width", 0.0)
        atLeast("height", 0.0)
        atLeast("depth", 0.0)
        between("count", 1, 4096)
        between("perimeter", 1, 4096)
        between("layers", 1, 256)
        between("segments", 1, 1024)
        between("density", 2, 512)
        requireLocalized(plan.options.lifetime >= -1) { translation("error.command.lifetime") }
        requireLocalized(plan.options.forceTicks >= -1) { translation("error.command.force_duration") }
    }

    /** 建组（不存在时按形状中心自动创建）并生成粒子。 */
    fun commit(plan: Plan, level: ServerLevel, dimension: UUID, name: String): CommandParticleGroups.Entry {
        val manager = ParticleManager.of(level)
        val entry = CommandParticleGroups.get(dimension, name)
            ?: CommandParticleGroups.create(dimension, name, manager.createGroup(plan.pivot))
        reserve(entry, plan.expected, plan.stagger)
        val o = plan.options
        val group = entry.group
        val m = manager
        when (plan.shape) {
            "dot" -> Draw.dot(m, plan.positions.getValue("pos"), o.color, o.scale, group, o.visual, o.lifeCurve, plan.lifetime)
            "field" -> spawnField(m, entry, plan, o)
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
            "cuboid" -> Draw.cuboid(
                m, plan.positions.getValue("center"), plan.numbers.getValue("width"), plan.numbers.getValue("height"),
                plan.numbers.getValue("depth"), plan.numbers.getValue("density").toInt(), o.hollow,
                o.color, o.scale, o.stagger, group, o.visual, o.lifeCurve, plan.lifetime,
            )
            else -> throw LocalizedArgumentException(translation("error.batch.shape", plan.shape))
        }
        return entry
    }

    private fun spawnField(manager: ParticleManager, entry: CommandParticleGroups.Entry, plan: Plan, o: ParticleCommandOptions) {
        val pos = plan.positions.getValue("pos")
        val count = plan.numbers.getValue("count").toInt()
        val batch = ParticleBatch(manager)
        repeat(count) { i ->
            val progress = if (count > 1) i.toDouble() / (count - 1) else 0.0
            val builder = manager.create().position(pos).color(o.color.colorAt(progress))
                .scale(o.scale).lifetime(plan.lifetime).group(entry.group.id).offsetFromPivot(pos.subtract(entry.group.pivot))
                .visual(o.visual).glowing(o.visual.glowing).lightLevel(o.visual.lightLevel)
            o.lifeCurve?.let(builder::lifeCurve)
            batch.add(builder.spawn())
        }
        o.velocity?.let { velocity -> batch.setVelocityAll(List(batch.size()) { velocity }) }
        o.force?.let { force -> batch.applyForceAll(List(batch.size()) { force }, o.forceTicks) }
    }

    private fun reserve(entry: CommandParticleGroups.Entry, count: Int, stagger: Int) {
        requireLocalized(count >= 0) { translation("error.command.particle_count") }
        requireLocalized((count.toLong() - 1L).coerceAtLeast(0L) * stagger <= Int.MAX_VALUE) {
            translation("error.command.stagger_overflow")
        }
        entry.expectedMembers += count
        CommandParticleGroups.waitForMembers(entry, count, stagger)
    }
}
