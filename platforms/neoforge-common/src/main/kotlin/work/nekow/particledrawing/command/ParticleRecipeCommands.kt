package work.nekow.particledrawing.command

import com.mojang.brigadier.arguments.BoolArgumentType
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.commands.arguments.coordinates.Vec3Argument
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.animation.recipe.RecipeExpressions
import work.nekow.particledrawing.api.*
import work.nekow.particledrawing.util.ParticleUtils
import java.util.Locale

/** /pdraw particle emitter：每条命令配置一个职责，公式按属性拆开。 */
internal object ParticleRecipeCommands {
    fun tree(): LiteralArgumentBuilder<CommandSourceStack> {
        val root = Commands.literal("emitter").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(::help)
        root.then(Commands.literal("help").executes(::help))
        for (verb in listOf("create", "reset")) root.then(Commands.literal(verb)
            .then(Commands.argument("name", StringArgumentType.string()).executes { ctx -> safe(ctx) {
                val name = word(ctx, "name")
                if (verb == "reset") CommandParticleRecipes.reset(dim(ctx), name, RecipeAnchor(ctx.source.position))
                else CommandParticleRecipes.create(dim(ctx), name, RecipeAnchor(ctx.source.position))
                success(ctx, "已创建生成器 '$name'；配置完成后 start 或 burst")
            } }))
        root.then(Commands.literal("list").executes { ctx -> safe(ctx) { success(ctx, "生成器: ${CommandParticleRecipes.names(dim(ctx)).joinToString(", ")}") } })
        root.then(Commands.literal("copy").then(Commands.argument("from", StringArgumentType.string())
            .then(Commands.argument("to", StringArgumentType.string()).executes { ctx -> safe(ctx) {
                CommandParticleRecipes.copy(dim(ctx), word(ctx, "from"), word(ctx, "to")); success(ctx, "已复制生成器配置")
            } })))

        val name = Commands.argument("name", StringArgumentType.string()).suggests { ctx, b -> SharedSuggestionProvider.suggest(CommandParticleRecipes.names(dim(ctx)).map(StringArgumentType::escapeIfRequired), b) }
        name.then(Commands.literal("inspect").executes { ctx -> safe(ctx) {
            val d = draft(ctx); val r = d.recipe
            success(ctx, "${word(ctx, "name")}: sample=${d.sampling.name.lowercase()}, rate=${r.rate}/${r.interval}t, lifetime=${r.lifetime}t, max_alive=${r.maxAlive}, follow=${r.follow}, running=${d.handle != null}; birth=${r.birth}; motion=${r.motion}; variables=${r.variables}; let=${r.lets}; where=${r.where}; kill=${r.kill}")
        } })
        for (verb in listOf("start", "burst", "stop", "remove", "apply", "remove_selected")) name.then(Commands.literal(verb).executes { ctx -> safe(ctx) {
            val d = draft(ctx)
            when (verb) {
                "start", "burst" -> d.start(ctx.source.level, verb == "burst")
                "stop" -> d.handles().forEach { it.stop() }
                "remove" -> d.removeParticles()
                "apply" -> { require(d.changes.isNotEmpty()) { "请先用 change 配置要修改的属性" }; d.handles().forEach { it.changeWhere(d.selector, d.changes) } }
                "remove_selected" -> d.handles().forEach { it.removeWhere(d.selector) }
            }
            val counts = if (verb == "start" || verb == "burst") {
                val r = d.recipe
                "；样本 ${r.seeds.size}，同时存活上限 ${r.maxAlive}" +
                    if (r.seeds.size > r.maxAlive) "；超过上限的样本会等待空位。若需同时显示完整图形，请提高 max_alive" else ""
            } else ""
            success(ctx, "生成器 '${word(ctx, "name")}' 已执行 $verb$counts")
        } })
        name.then(Commands.literal("delete").executes { ctx -> safe(ctx) { CommandParticleRecipes.remove(dim(ctx), word(ctx, "name")); success(ctx, "已删除生成器与配置") } })
        name.then(anchorTree())
        name.then(sampleTree())
        name.then(imageTree())
        for (key in listOf("rate", "interval", "lifetime", "max_alive")) name.then(Commands.literal(key)
            .then(Commands.argument("value", IntegerArgumentType.integer()).executes { ctx -> configure(ctx) { d ->
                val v = IntegerArgumentType.getInteger(ctx, "value")
                d.update(when (key) { "rate" -> d.recipe.copy(rate = v); "interval" -> d.recipe.copy(interval = v); "lifetime" -> d.recipe.copy(lifetime = v); else -> d.recipe.copy(maxAlive = v) })
            } }))
        name.then(Commands.literal("follow").then(Commands.argument("enabled", BoolArgumentType.bool()).executes { ctx -> configure(ctx) { d -> d.update(d.recipe.copy(follow = BoolArgumentType.getBool(ctx, "enabled"))) } }))

        for (kind in listOf("variable", "state")) name.then(Commands.literal(kind)
            .then(Commands.argument("field", StringArgumentType.word())
                .then(Commands.argument("value", DoubleArgumentType.doubleArg()).executes { ctx -> configure(ctx) { d ->
                    val field = word(ctx, "field"); val value = DoubleArgumentType.getDouble(ctx, "value")
                    if (kind == "variable") d.variable(field, value) else d.update(d.recipe.copy(state = d.recipe.state + (field to value)))
                } })))
        for (stage in listOf("let", "birth", "motion", "change")) name.then(Commands.literal(stage)
            .then(Commands.argument("field", StringArgumentType.word()).suggests { ctx, b -> SharedSuggestionProvider.suggest(RecipeExpressions.OUTPUTS + draft(ctx).recipe.state.keys, b) }
                .then(Commands.argument("formula", StringArgumentType.greedyString()).executes { ctx -> configure(ctx) { d ->
                    val field = word(ctx, "field"); val formula = word(ctx, "formula")
                    when (stage) {
                        "let" -> d.update(d.recipe.copy(lets = d.recipe.lets + (field to formula)))
                        "birth" -> d.update(d.recipe.copy(birth = d.recipe.birth + (field to formula)))
                        "motion" -> d.update(d.recipe.copy(motion = d.recipe.motion + (field to formula)))
                        else -> { val next = d.changes + (field to formula); d.recipe.copy(birth = next, where = d.selector).validate(); d.changes[field] = formula }
                    }
                } })))
        for (kind in listOf("where", "kill", "select")) name.then(Commands.literal(kind)
            .then(Commands.argument("formula", StringArgumentType.greedyString()).executes { ctx -> configure(ctx) { d ->
                val f = word(ctx, "formula")
                when (kind) { "where" -> d.update(d.recipe.copy(where = f)); "kill" -> d.update(d.recipe.copy(kill = f)); else -> { d.recipe.copy(where = f).validate(); d.selector = f } }
            } }))
        name.then(Commands.literal("unset").then(Commands.argument("section", StringArgumentType.word())
            .suggests { _, b -> SharedSuggestionProvider.suggest(listOf("variable", "state", "let", "birth", "motion", "change"), b) }
            .then(Commands.argument("field", StringArgumentType.word()).executes { ctx -> configure(ctx) { d ->
                val field = word(ctx, "field")
                when (word(ctx, "section")) {
                    "variable" -> d.update(d.recipe.copy(variables = d.recipe.variables - field))
                    "state" -> d.update(d.recipe.copy(state = d.recipe.state - field))
                    "let" -> d.update(d.recipe.copy(lets = d.recipe.lets - field))
                    "birth" -> d.update(d.recipe.copy(birth = d.recipe.birth - field))
                    "motion" -> d.update(d.recipe.copy(motion = d.recipe.motion - field))
                    "change" -> d.changes.remove(field)
                    else -> throw IllegalArgumentException("未知配置节")
                }
            } })))

        name.then(Commands.literal("texture").then(Commands.argument("value", ParticleCommandArguments.textType()).executes { ctx -> configure(ctx) { appearance(it, "texture", ParticleCommandArguments.text(word(ctx, "value"))) } }))
        name.then(Commands.literal("appearance").then(Commands.argument("key", StringArgumentType.word())
            .suggests { _, b -> SharedSuggestionProvider.suggest(APPEARANCE, b) }
            .then(Commands.argument("value", StringArgumentType.greedyString()).executes { ctx -> configure(ctx) { appearance(it, word(ctx, "key"), word(ctx, "value")) } })))
        root.then(name)
        return root
    }

    private fun anchorTree(): LiteralArgumentBuilder<CommandSourceStack> {
        val root = Commands.literal("anchor")
        root.then(Commands.literal("fixed").then(Commands.argument("pos", Vec3Argument.vec3()).executes { ctx -> configure(ctx) {
            CommandParticleRecipes.anchor(dim(ctx), word(ctx, "name"), RecipeAnchor(Vec3Argument.getVec3(ctx, "pos")))
        } }))
        root.then(Commands.literal("entity").then(Commands.argument("entity", EntityArgument.entity()).executes(::entityAnchor)
            .then(Commands.argument("offset_x", DoubleArgumentType.doubleArg())
                .then(Commands.argument("offset_y", DoubleArgumentType.doubleArg())
                    .then(Commands.argument("offset_z", DoubleArgumentType.doubleArg())
                        .then(Commands.argument("local", BoolArgumentType.bool()).executes(::entityAnchor)))))))
        root.then(Commands.literal("emitter").then(Commands.argument("target", StringArgumentType.string()).executes { ctx -> configure(ctx) {
            CommandParticleRecipes.linkAnchor(dim(ctx), word(ctx, "name"), word(ctx, "target"))
        } }))
        return root
    }

    private fun entityAnchor(ctx: CommandContext<CommandSourceStack>) = configure(ctx) {
        val e = EntityArgument.getEntity(ctx, "entity")
        val offset = try { Vec3(DoubleArgumentType.getDouble(ctx, "offset_x"), DoubleArgumentType.getDouble(ctx, "offset_y"), DoubleArgumentType.getDouble(ctx, "offset_z")) } catch (_: IllegalArgumentException) { Vec3.ZERO }
        val local = try { BoolArgumentType.getBool(ctx, "local") } catch (_: IllegalArgumentException) { false }
        val a = RecipeAnchor(e.position(), e.uuid, e.id, offset, local)
        CommandParticleRecipes.anchor(dim(ctx), word(ctx, "name"), a)
    }

    private fun sampleTree(): LiteralArgumentBuilder<CommandSourceStack> {
        val root = Commands.literal("sample")
        root.then(Commands.literal("count").then(Commands.argument("count", IntegerArgumentType.integer(1, ParticleRecipe.MAX_SEEDS)).executes { ctx -> sampleConfig(ctx) { d ->
            d.sampling = CommandParticleRecipes.Sampling.PARAMETER; d.count = IntegerArgumentType.getInteger(ctx, "count"); d.range = null
        } }))
        root.then(Commands.literal("range").then(Commands.argument("begin", DoubleArgumentType.doubleArg())
            .then(Commands.argument("end", DoubleArgumentType.doubleArg()).then(Commands.argument("step", DoubleArgumentType.doubleArg()).executes { ctx -> sampleConfig(ctx) { d ->
                val begin = DoubleArgumentType.getDouble(ctx, "begin"); val end = DoubleArgumentType.getDouble(ctx, "end"); val step = DoubleArgumentType.getDouble(ctx, "step")
                ParticleSampling.range(begin, end, step)
                d.sampling = CommandParticleRecipes.Sampling.PARAMETER; d.range = Triple(begin, end, step)
            } }))))
        root.then(Commands.literal("grid").executes { ctx -> sampleConfig(ctx) { it.sampling = CommandParticleRecipes.Sampling.GRID } })
        root.then(Commands.literal("bounds").then(Commands.argument("bounds", ParticleCommandArguments.textType()).executes { ctx -> sampleConfig(ctx) { d ->
                val (min, max) = ParticleCommandArguments.bounds(word(ctx, "bounds")); ParticleSampling.grid(min, max, d.step)
                d.min = min; d.max = max
            } }))
        root.then(Commands.literal("step").then(Commands.argument("step", DoubleArgumentType.doubleArg(0.000001)).executes { ctx -> sampleConfig(ctx) { d ->
            val step = DoubleArgumentType.getDouble(ctx, "step"); ParticleSampling.grid(d.min, d.max, step); d.step = step
        } }))
        return root
    }

    private fun imageTree(): LiteralArgumentBuilder<CommandSourceStack> {
        val root = Commands.literal("image")
        for (kind in listOf("file", "resource")) root.then(Commands.literal(kind).then(Commands.argument("path", ParticleCommandArguments.textType()).executes { ctx -> sampleConfig(ctx) { d ->
            d.sampling = CommandParticleRecipes.Sampling.IMAGE; d.image = ParticleCommandArguments.text(word(ctx, "path")); d.imageResource = kind == "resource"
        } }))
        root.then(Commands.literal("stride").then(Commands.argument("stride", IntegerArgumentType.integer(1, 256)).executes { ctx -> sampleConfig(ctx) { d -> d.imageOptions = d.imageOptions.copy(stride = IntegerArgumentType.getInteger(ctx, "stride")) } }))
        for (key in listOf("spacing", "alpha")) root.then(Commands.literal(key).then(Commands.argument("value", DoubleArgumentType.doubleArg()).executes { ctx -> sampleConfig(ctx) { d ->
            val v = DoubleArgumentType.getDouble(ctx, "value")
            require(v.isFinite() && (if (key == "spacing") v > 0 else v in 0.0..1.0)) { "spacing 必须为正，alpha 需要在 0..1" }
            d.imageOptions = if (key == "spacing") d.imageOptions.copy(spacing = v) else d.imageOptions.copy(alpha = v)
        } }))
        root.then(Commands.literal("plane").then(Commands.argument("plane", StringArgumentType.word()).suggests { _, b -> SharedSuggestionProvider.suggest(listOf("xy", "xz", "yz"), b) }
            .executes { ctx -> sampleConfig(ctx) { d -> d.imageOptions = d.imageOptions.copy(plane = ParticleImageSampler.Plane.valueOf(word(ctx, "plane").uppercase(Locale.ROOT))) } }))
        return root
    }

    private fun appearance(d: CommandParticleRecipes.Draft, key: String, value: String) {
        require(key in APPEARANCE) { "未知外观参数 '$key'" }
        val text = ParticleCommandArguments.text(value)
        val values = LinkedHashMap(d.appearance).apply { put(key, text) }
        val parsed = ParticleCommandOptionParser.parse(values.entries.joinToString(" ") {
            "${it.key}=\"${it.value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        })
        require(parsed.provided == values.keys) { "每条 appearance 命令只设置一个参数" }
        var birth = d.recipe.birth
        if (key == "color") {
            val c = ParticleCommandOptionParser.parseColor(text)
            birth = birth + mapOf("r" to c.r.toString(), "g" to c.g.toString(), "b" to c.b.toString(), "a" to c.a.toString())
        }
        if (key == "scale") birth = birth + ("sc" to parsed.scale.toString())
        d.update(d.recipe.copy(visual = parsed.visual, lifeCurve = parsed.lifeCurve, birth = birth))
        d.appearance.clear(); d.appearance.putAll(values)
    }

    private fun sampleConfig(ctx: CommandContext<CommandSourceStack>, action: (CommandParticleRecipes.Draft) -> Unit) = configure(ctx) { d ->
        require(d.handle == null) { "采样配置只影响出生布局；请先 remove，再配置并 start；运动和变量可以直接热更新" }
        action(d)
    }

    private fun configure(ctx: CommandContext<CommandSourceStack>, action: (CommandParticleRecipes.Draft) -> Unit) = safe(ctx) {
        action(draft(ctx)); success(ctx, "已更新生成器 '${word(ctx, "name")}'")
    }
    private fun draft(ctx: CommandContext<CommandSourceStack>) = CommandParticleRecipes.get(dim(ctx), word(ctx, "name"))
    private fun dim(ctx: CommandContext<CommandSourceStack>) = ParticleUtils.dimensionUUID(ctx.source.level)
    private fun word(ctx: CommandContext<CommandSourceStack>, name: String) = StringArgumentType.getString(ctx, name)
    private fun success(ctx: CommandContext<CommandSourceStack>, message: String): Int { ctx.source.sendSuccess({ Component.literal(message) }, false); return 1 }
    private inline fun safe(ctx: CommandContext<CommandSourceStack>, action: () -> Int): Int = try { action() } catch (e: Exception) {
        ctx.source.sendFailure(Component.literal(e.message ?: "粒子生成配置错误")); 0
    }
    private fun help(ctx: CommandContext<CommandSourceStack>) = success(ctx,
        "emitter create|reset|copy|list；emitter <名称> anchor|sample|image|variable|state|let|birth|motion|where|kill|appearance|texture|rate|interval|lifetime|max_alive|follow|select|change|apply|remove_selected|start|burst|stop|remove|delete|inspect|unset；公式中的 u 是采样参数，age 是粒子年龄（刻），t 是发射器时间（毫秒）")

    private val APPEARANCE = setOf("texture", "style", "color", "scale", "uv", "size", "size_world", "billboard", "spin", "spin_space", "additive", "glowing", "light", "light_only", "intensity", "alpha_curve", "size_curve", "red_curve", "green_curve", "blue_curve", "light_curve")
}
