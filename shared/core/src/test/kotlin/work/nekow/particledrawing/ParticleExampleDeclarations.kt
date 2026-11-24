package work.nekow.particledrawing

import work.nekow.particledrawing.api.Color
import work.nekow.particledrawing.api.ParticleImageSampler
import work.nekow.particledrawing.api.ParticleRecipe
import work.nekow.particledrawing.api.ParticleSampling
import work.nekow.particledrawing.api.ParticleSeed
import work.nekow.particledrawing.math.Vec3
import java.nio.file.Files
import java.nio.file.Path

/** 提取运行时回归用的配方字段，游戏服自测另用正式分派器遍历同一批函数。 */
internal object ParticleExampleDeclarations {
    data class Entry(val name: String, val action: String?, val recipe: ParticleRecipe)

    private val tokens = Regex("""\"(?:\\.|[^"\\])*\"|[^\s"]+""")
    private val flags = setOf("glow", "glowing", "additive", "light_only", "align_velocity", "billboard")
    private val pairs = setOf("size_world", "size_relative", "light_dir", "trail_width", "trail_color")

    fun read(file: String): List<Entry> = Files.readAllLines(Path.of("examples/commands/$file.mcfunction")).mapNotNull { line ->
        if (!line.startsWith("pdraw particle emitter ")) null else parse(line)
    }

    private fun parse(line: String): Entry {
        val args = tokens.findAll(line).map { it.value }.toList()
        var cursor = 3
        fun next(): String = args[cursor++].removeSurrounding("\"")
        fun take(expected: String): Boolean {
            if (args.getOrNull(cursor) != expected) return false
            cursor++
            return true
        }
        fun expect(expected: String) = check(take(expected)) { "期望 $expected: $line" }
        fun number() = next().toDouble()
        fun vector() = Vec3(number(), number(), number())
        val name = next()
        val lets = linkedMapOf<String, String>()
        val birth = linkedMapOf<String, String>()
        val motion = linkedMapOf<String, String>()
        val variables = linkedMapOf<String, Double>()
        var seeds = ParticleSampling.count(1)
        var recipe = ParticleRecipe(seeds)
        var action: String? = null
        while (cursor < args.size) {
            when (val stage = next()) {
                "at" -> {
                    val anchor = next()
                    if (anchor == "emitter") next()
                    else if (!anchor.startsWith("@")) repeat(2) { next() }
                    if (take("offset")) repeat(3) { next() }
                    take("local")
                }
                "sample" -> seeds = when (val sample = next()) {
                    "count" -> ParticleSampling.count(next().toInt())
                    "range" -> ParticleSampling.range(number(), number(), number())
                    "grid" -> {
                        expect("from")
                        val min = vector()
                        expect("to")
                        val max = vector()
                        expect("step")
                        ParticleSampling.grid(min, max, number())
                    }
                    "image" -> {
                        expect("file")
                        check(next() == "pd-icon.png")
                        val spacing = if (take("pixel_spacing")) number() else 0.05
                        val alpha = if (take("alpha")) number() else 0.0
                        val stride = if (take("stride")) next().toInt() else 1
                        val plane = if (take("plane")) ParticleImageSampler.Plane.valueOf(next().uppercase()) else ParticleImageSampler.Plane.XY
                        Files.newInputStream(Path.of("platforms/neoforge-common/src/main/resources/icon.png")).use {
                            ParticleImageSampler.sample(it, ParticleImageSampler.Options(stride, spacing, alpha, plane))
                        }
                    }
                    else -> error("示例采样 $sample: $line")
                }
                "let" -> lets[next()] = next()
                "spawn", "move" -> {
                    val field = if (take("set")) next() else if (stage == "spawn") "position" else "velocity"
                    (if (stage == "spawn") birth else motion)[field] = next()
                }
                "filter" -> recipe = recipe.copy(where = next())
                "kill" -> recipe = recipe.copy(kill = next())
                "orbit" -> recipe = recipe.copy(orbit = next())
                "look" -> while (args.getOrNull(cursor) in lookFields) {
                    when (val field = next()) {
                        in flags -> if (!take("true")) take("false")
                        in pairs -> repeat(2) { next() }
                        "color" -> {
                            val raw = next().removePrefix("#")
                            check(raw.length in setOf(6, 8))
                            val rgba = raw.chunked(2).map { it.toInt(16) }
                            val color = Color.ofInt(rgba[0], rgba[1], rgba[2], rgba.getOrElse(3) { 255 })
                            birth += mapOf("r" to color.r.toString(), "g" to color.g.toString(),
                                "b" to color.b.toString(), "a" to color.a.toString())
                        }
                        "scale" -> birth["sc"] = next()
                        else -> next()
                    }
                }
                "life" -> recipe = recipe.copy(lifetime = next().toInt())
                "rate" -> recipe = recipe.copy(rate = next().toInt())
                "interval" -> recipe = recipe.copy(interval = next().toInt())
                "max" -> recipe = recipe.copy(maxAlive = next().toInt())
                "spacing" -> recipe = recipe.copy(spacing = number())
                "follow" -> recipe = recipe.copy(follow = next().toBooleanStrict())
                "window" -> {
                    val start = next().toInt()
                    val end = if (take("until")) next().toInt() else -1
                    recipe = recipe.copy(emitFromTick = start, emitUntilTick = end)
                }
                "with" -> {
                    expect("variable")
                    variables[next()] = number()
                }
                "start", "burst" -> {
                    action = stage
                    if (args.getOrNull(cursor) in setOf("standard", "instanced", "gpu", "synced")) next()
                }
                else -> error("示例字段 $stage: $line")
            }
        }
        return Entry(name, action, recipe.copy(seeds = seeds, lets = lets, birth = birth, motion = motion, variables = variables)
            .also(ParticleRecipe::validate))
    }

    private val lookFields = flags + pairs + setOf("color", "style", "texture", "size", "scale", "light",
        "light_intensity", "trail", "trail_linger", "trail_sides", "soft", "stretch", "model")
}
