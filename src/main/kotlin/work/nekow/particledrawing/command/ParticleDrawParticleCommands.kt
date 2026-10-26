package work.nekow.particledrawing.command

import com.mojang.brigadier.arguments.BoolArgumentType
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.ArgumentBuilder
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.commands.arguments.coordinates.Vec3Argument
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.api.Draw
import work.nekow.particledrawing.api.ParticleBatch
import work.nekow.particledrawing.api.ParticleManager
import work.nekow.particledrawing.core.easing.EasingType
import work.nekow.particledrawing.core.network.AnimationProgramCodecs
import work.nekow.particledrawing.util.ParticleUtils
import java.util.concurrent.CompletableFuture
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** 命令驱动的粒子绘图、命名组和组程序。 */
internal object ParticleDrawParticleCommands {
    private const val MAX_FIELD_PARTICLES = 4096

    private val COLOR_OPTIONS = setOf("color", "gradient", "rainbow")
    private val CURVE_OPTIONS = setOf(
        "alpha_curve", "size_curve", "red_curve", "green_curve", "blue_curve", "light_curve",
    )
    private val VISUAL_OPTIONS = setOf(
        "style", "texture", "uv", "size", "size_world", "billboard", "spin", "spin_space",
        "additive", "glowing", "light", "intensity",
    )
    private val SHAPE_OPTIONS = COLOR_OPTIONS + CURVE_OPTIONS + VISUAL_OPTIONS + setOf("scale", "stagger")
    private val FIELD_OPTIONS = (SHAPE_OPTIONS - "stagger") + setOf("lifetime", "velocity", "force", "force_ticks")
    private val BEAM_OPTIONS = COLOR_OPTIONS + CURVE_OPTIONS + setOf(
        "stagger", "taper", "thickness", "texture", "additive", "glowing",
    )
    private val FORMULA_NAME = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
    private val RESERVED_FORMULA_NAMES = setOf(
        "i", "n", "t", "PI", "E", "x", "y", "z", "r", "g", "b", "a", "vx", "vy", "vz", "sc", "glow", "light",
    )

    fun tree(): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal("particle")
        .executes(::help)
        .then(Commands.literal("help").executes(::help))
        .then(groupTree())
        .then(addTree())
        .then(programTree())

    private fun groupTree(): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal("group")
        .executes(::groupHelp)
        .then(Commands.literal("create")
            .then(Commands.argument("group", StringArgumentType.word())
                .then(Commands.argument("pos", Vec3Argument.vec3()).executes(::createGroup))))
        .then(Commands.literal("reset")
            .then(Commands.argument("group", StringArgumentType.word())
                .then(Commands.argument("pos", Vec3Argument.vec3()).executes(::resetGroup))))
        .then(Commands.literal("list").executes(::listGroups))
        .then(Commands.literal("remove")
            .then(groupArgument().executes(::removeGroup)))
        .then(Commands.literal("clear").executes(::clearGroups))

    private fun addTree(): LiteralArgumentBuilder<CommandSourceStack> {
        val root = Commands.literal("add").executes(::addHelp)
        val group = groupArgument()

        group.then(Commands.literal("dot").then(withOptions(
            Commands.argument("pos", Vec3Argument.vec3()), ::addDot,
        )))
        group.then(Commands.literal("field").then(Commands.argument("pos", Vec3Argument.vec3())
            .then(withOptions(Commands.argument("count", IntegerArgumentType.integer(1, MAX_FIELD_PARTICLES)), ::addField))))
        group.then(Commands.literal("line").then(Commands.argument("start", Vec3Argument.vec3())
            .then(Commands.argument("end", Vec3Argument.vec3())
                .then(withOptions(Commands.argument("count", IntegerArgumentType.integer(1, MAX_FIELD_PARTICLES)), ::addLine)))))
        group.then(Commands.literal("beam").then(Commands.argument("start", Vec3Argument.vec3())
            .then(withOptions(Commands.argument("end", Vec3Argument.vec3()), ::addBeam))))
        group.then(Commands.literal("helix").then(Commands.argument("center", Vec3Argument.vec3())
            .then(Commands.argument("radius", DoubleArgumentType.doubleArg(0.0))
                .then(Commands.argument("height", DoubleArgumentType.doubleArg())
                    .then(Commands.argument("turns", DoubleArgumentType.doubleArg())
                        .then(withOptions(Commands.argument("count", IntegerArgumentType.integer(1, MAX_FIELD_PARTICLES)), ::addHelix)))))))
        group.then(Commands.literal("circle").then(Commands.argument("center", Vec3Argument.vec3())
            .then(Commands.argument("radius", DoubleArgumentType.doubleArg(0.0))
                .then(withOptions(Commands.argument("count", IntegerArgumentType.integer(1, MAX_FIELD_PARTICLES)), ::addCircle)))))
        group.then(Commands.literal("disc").then(Commands.argument("center", Vec3Argument.vec3())
            .then(Commands.argument("radius", DoubleArgumentType.doubleArg(0.0))
                .then(Commands.argument("perimeter", IntegerArgumentType.integer(1, MAX_FIELD_PARTICLES))
                    .then(withOptions(Commands.argument("layers", IntegerArgumentType.integer(1, 256)), ::addDisc))))))
        group.then(Commands.literal("triangle").then(Commands.argument("center", Vec3Argument.vec3())
            .then(Commands.argument("radius", DoubleArgumentType.doubleArg(0.0))
                .then(withOptions(Commands.argument("segments", IntegerArgumentType.integer(1, 1024)), ::addTriangle)))))
        group.then(Commands.literal("hexagram").then(Commands.argument("center", Vec3Argument.vec3())
            .then(Commands.argument("radius", DoubleArgumentType.doubleArg(0.0))
                .then(withOptions(Commands.argument("segments", IntegerArgumentType.integer(1, 1024)), ::addHexagram)))))
        group.then(Commands.literal("rect").then(Commands.argument("center", Vec3Argument.vec3())
            .then(Commands.argument("width", DoubleArgumentType.doubleArg(0.0))
                .then(Commands.argument("height", DoubleArgumentType.doubleArg(0.0))
                    .then(withOptions(Commands.argument("density", IntegerArgumentType.integer(2, 512)), ::addRect))))))
        group.then(Commands.literal("sphere").then(Commands.argument("center", Vec3Argument.vec3())
            .then(Commands.argument("radius", DoubleArgumentType.doubleArg(0.0))
                .then(withOptions(Commands.argument("count", IntegerArgumentType.integer(1, MAX_FIELD_PARTICLES)), ::addSphere)))))
        group.then(Commands.literal("cuboid").then(Commands.argument("center", Vec3Argument.vec3())
            .then(Commands.argument("width", DoubleArgumentType.doubleArg(0.0))
                .then(Commands.argument("height", DoubleArgumentType.doubleArg(0.0))
                    .then(Commands.argument("depth", DoubleArgumentType.doubleArg(0.0))
                        .then(withOptions(Commands.argument("density", IntegerArgumentType.integer(2, 256)), ::addCuboid)))))))

        root.then(group)
        return root
    }

    private fun programTree(): LiteralArgumentBuilder<CommandSourceStack> {
        val root = Commands.literal("program").executes(::programHelp)
        val group = groupArgument()
        group.then(Commands.literal("pivot")
            .then(Commands.argument("pos", Vec3Argument.vec3()).executes(::programPivot)))
        group.then(Commands.literal("follow")
            .then(Commands.argument("entity", EntityArgument.entity())
                .executes(::programFollow)
                .then(Commands.argument("x", DoubleArgumentType.doubleArg())
                    .then(Commands.argument("y", DoubleArgumentType.doubleArg())
                        .then(Commands.argument("z", DoubleArgumentType.doubleArg())
                            .executes(::programFollow)
                            .then(Commands.argument("local", BoolArgumentType.bool()).executes(::programFollow)))))))
        group.then(Commands.literal("anchor")
            .then(Commands.literal("fixed")
                .then(Commands.argument("pos", Vec3Argument.vec3()).executes(::programPivot)))
            .then(Commands.literal("entity")
                .then(Commands.argument("entity", EntityArgument.entity())
                    .executes(::programFollow)
                    .then(Commands.argument("x", DoubleArgumentType.doubleArg())
                        .then(Commands.argument("y", DoubleArgumentType.doubleArg())
                            .then(Commands.argument("z", DoubleArgumentType.doubleArg())
                                .executes(::programFollow)
                                .then(Commands.argument("local", BoolArgumentType.bool()).executes(::programFollow))))))))
        group.then(Commands.literal("delay")
            .then(Commands.argument("ticks", IntegerArgumentType.integer(0)).executes(::programDelay)))
        group.then(timedEasing("fade_in", ::programFadeIn))
        group.then(timedEasing("fade_out", ::programFadeOut))
        group.then(timedEasing("fade_out_keep", ::programFadeOutKeep))
        group.then(Commands.literal("move")
            .then(Commands.argument("x", DoubleArgumentType.doubleArg())
                .then(Commands.argument("y", DoubleArgumentType.doubleArg())
                    .then(Commands.argument("z", DoubleArgumentType.doubleArg())
                        .then(ticksAndEasing(::programMove))))))
        group.then(Commands.literal("move_offset")
            .then(Commands.argument("ratio", DoubleArgumentType.doubleArg())
                .then(ticksAndEasing(::programMoveOffset))))
        group.then(Commands.literal("move_path")
            .then(Commands.argument("ticks", IntegerArgumentType.integer(0))
                .then(Commands.argument("points", StringArgumentType.word())
                    .executes(::programMovePath)
                    .then(Commands.argument("easing", StringArgumentType.word())
                        .suggests(::suggestEasings).executes(::programMovePath)))))
        group.then(Commands.literal("rotate")
            .then(Commands.argument("x", DoubleArgumentType.doubleArg())
                .then(Commands.argument("y", DoubleArgumentType.doubleArg())
                    .then(Commands.argument("z", DoubleArgumentType.doubleArg())
                        .then(Commands.argument("degrees", DoubleArgumentType.doubleArg())
                            .then(ticksAndEasing(::programRotate)))))))
        group.then(Commands.literal("recolor")
            .then(Commands.argument("color", StringArgumentType.word())
                .then(ticksAndEasing(::programRecolor))))
        group.then(Commands.literal("scale_by")
            .then(Commands.argument("ratio", DoubleArgumentType.doubleArg(0.0))
                .then(ticksAndEasing(::programScaleBy))))
        group.then(Commands.literal("scale_to")
            .then(Commands.argument("ratio", DoubleArgumentType.doubleArg(0.0))
                .then(ticksAndEasing(::programScaleTo))))
        group.then(Commands.literal("spin")
            .then(Commands.argument("x", DoubleArgumentType.doubleArg())
                .then(Commands.argument("y", DoubleArgumentType.doubleArg())
                    .then(Commands.argument("z", DoubleArgumentType.doubleArg())
                        .then(Commands.argument("degrees_per_tick", DoubleArgumentType.doubleArg()).executes(::programSpin))))))
        group.then(Commands.literal("pulse")
            .then(Commands.argument("peak", DoubleArgumentType.doubleArg(0.0))
                .then(Commands.argument("half_period", IntegerArgumentType.integer(1))
                    .executes(::programPulse)
                    .then(Commands.argument("cycles", IntegerArgumentType.integer(-1)).executes(::programPulse)))))
        group.then(Commands.literal("stop_continuous").executes(::programStop))
        group.then(Commands.literal("destroy_after")
            .then(Commands.argument("ticks", IntegerArgumentType.integer(0)).executes(::programDestroyAfter)))
        group.then(Commands.literal("retire")
            .executes(::programRetire)
            .then(Commands.argument("grace", IntegerArgumentType.integer(0)).executes(::programRetire)))
        group.then(Commands.literal("entity")
            .then(Commands.argument("handle", StringArgumentType.word())
                .then(Commands.argument("entity", EntityArgument.entity()).executes(::programEntity))))
        group.then(Commands.literal("variable")
            .then(Commands.literal("set")
                .then(Commands.argument("name", StringArgumentType.word())
                    .then(Commands.argument("value", DoubleArgumentType.doubleArg()).executes(::programVariableSet))))
            .then(Commands.literal("live")
                .then(Commands.argument("name", StringArgumentType.word())
                    .then(Commands.argument("value", StringArgumentType.greedyString()).executes(::programVariableLive))))
            .then(Commands.literal("ease")
                .then(Commands.argument("name", StringArgumentType.word())
                    .then(Commands.argument("value", StringArgumentType.word())
                        .then(Commands.argument("ticks", IntegerArgumentType.integer(0))
                            .executes(::programVariableEase)
                            .then(Commands.argument("easing", StringArgumentType.word())
                                .suggests(::suggestEasings).executes(::programVariableEase)))))))
        group.then(Commands.literal("expression")
            .then(Commands.argument("ticks", IntegerArgumentType.integer(0))
                .then(Commands.argument("code", StringArgumentType.greedyString()).executes(::programExpression))))
        root.then(group)
        return root
    }

    private fun timedEasing(name: String, action: (CommandContext<CommandSourceStack>) -> Int) =
        Commands.literal(name).then(ticksAndEasing(action))

    private fun ticksAndEasing(action: (CommandContext<CommandSourceStack>) -> Int) =
        Commands.argument("ticks", IntegerArgumentType.integer(0))
            .executes(action)
            .then(Commands.argument("easing", StringArgumentType.word())
                .suggests(::suggestEasings).executes(action))

    private fun <T : ArgumentBuilder<CommandSourceStack, T>> withOptions(
        node: T,
        action: (CommandContext<CommandSourceStack>, String) -> Int,
    ): T {
        node.executes { action(it, "") }
        node.then(Commands.argument("options", StringArgumentType.greedyString())
            .executes { action(it, StringArgumentType.getString(it, "options")) })
        return node
    }

    private fun groupArgument() = Commands.argument("group", StringArgumentType.word())
        .suggests(::suggestGroups)

    private fun dimension(ctx: CommandContext<CommandSourceStack>) = ParticleUtils.dimensionUUID(ctx.source.level)

    private fun entry(ctx: CommandContext<CommandSourceStack>, allowProgrammed: Boolean = true): CommandParticleGroups.Entry {
        val name = StringArgumentType.getString(ctx, "group")
        val entry = CommandParticleGroups.get(dimension(ctx), name)
            ?: throw IllegalArgumentException("当前维度没有粒子组 '$name'")
        if (!allowProgrammed && entry.programmed) {
            throw IllegalArgumentException("粒子组 '$name' 已开始编排；请先添加完全部图元，再执行 program")
        }
        return entry
    }

    private fun options(raw: String): ParticleCommandOptions = ParticleCommandOptionParser.parse(raw)

    private fun requireOptions(options: ParticleCommandOptions, allowed: Set<String>) {
        val unsupported = options.provided - allowed
        require(unsupported.isEmpty()) { "这个图元不使用参数: ${unsupported.sorted().joinToString(", ")}" }
    }

    private fun reserveMembers(entry: CommandParticleGroups.Entry, count: Int, stagger: Int = 0) {
        require(count >= 0) { "粒子数量不能为负" }
        require((count.toLong() - 1L).coerceAtLeast(0L) * stagger <= Int.MAX_VALUE) {
            "stagger 与粒子数量的乘积过大"
        }
        val total = entry.expectedMembers.toLong() + count
        require(total <= AnimationProgramCodecs.MAX_PROGRAM_MEMBERS) {
            "粒子组 '${entry.name}' 最多可编排 ${AnimationProgramCodecs.MAX_PROGRAM_MEMBERS} 颗粒子；请拆成多个组"
        }
        entry.expectedMembers = total.toInt()
        CommandParticleGroups.waitForMembers(entry, count, stagger)
    }

    private fun added(ctx: CommandContext<CommandSourceStack>, before: Int, entry: CommandParticleGroups.Entry): Int {
        val count = (entry.group.size() - before).coerceAtLeast(0)
        val pending = if (CommandParticleGroups.hasPendingMembers(entry)) {
            (entry.expectedMembers - entry.group.size()).coerceAtLeast(0)
        } else {
            0
        }
        val message = if (pending > 0) {
            "已向 '${entry.name}' 生成 $count 颗粒子，另有 $pending 颗按 stagger 入场"
        } else {
            "已向 '${entry.name}' 添加 $count 颗粒子（当前 ${entry.group.size()} 颗）"
        }
        ctx.source.sendSuccess({ Component.literal(message) }, false)
        return count.coerceAtLeast(1)
    }

    private inline fun safe(ctx: CommandContext<CommandSourceStack>, action: () -> Int): Int = try {
        action()
    } catch (e: IllegalArgumentException) {
        ctx.source.sendFailure(Component.literal(e.message ?: "粒子命令参数错误"))
        0
    }

    private fun createGroup(ctx: CommandContext<CommandSourceStack>): Int = safe(ctx) {
        createGroup(ctx, replace = false)
    }

    private fun resetGroup(ctx: CommandContext<CommandSourceStack>): Int = safe(ctx) {
        createGroup(ctx, replace = true)
    }

    private fun createGroup(ctx: CommandContext<CommandSourceStack>, replace: Boolean): Int {
        val name = StringArgumentType.getString(ctx, "group")
        val pos = Vec3Argument.getVec3(ctx, "pos")
        val manager = ParticleManager.of(ctx.source.level)
        if (replace) CommandParticleGroups.remove(dimension(ctx), name)
        CommandParticleGroups.create(dimension(ctx), name, manager.createGroup(pos))
        val verb = if (replace) "已重置" else "已创建"
        ctx.source.sendSuccess({ Component.literal("$verb 粒子组 '$name'，轴心 $pos") }, false)
        return 1
    }

    private fun listGroups(ctx: CommandContext<CommandSourceStack>): Int {
        val names = CommandParticleGroups.names(dimension(ctx))
        ctx.source.sendSuccess({ Component.literal(if (names.isEmpty()) "当前维度没有命令粒子组" else "命令粒子组: ${names.joinToString(", ")}") }, false)
        return names.size
    }

    private fun removeGroup(ctx: CommandContext<CommandSourceStack>): Int {
        val name = StringArgumentType.getString(ctx, "group")
        if (!CommandParticleGroups.remove(dimension(ctx), name)) {
            ctx.source.sendFailure(Component.literal("当前维度没有粒子组 '$name'"))
            return 0
        }
        ctx.source.sendSuccess({ Component.literal("已移除粒子组 '$name'") }, false)
        return 1
    }

    private fun clearGroups(ctx: CommandContext<CommandSourceStack>): Int {
        val count = CommandParticleGroups.clear(dimension(ctx))
        ctx.source.sendSuccess({ Component.literal("已清除当前维度的 $count 个命令粒子组") }, false)
        return count
    }

    private fun addDot(ctx: CommandContext<CommandSourceStack>, raw: String): Int = safe(ctx) {
        val e = entry(ctx, false); val before = e.group.size(); val o = options(raw)
        requireOptions(o, SHAPE_OPTIONS - "stagger")
        reserveMembers(e, 1)
        Draw.dot(ParticleManager.of(ctx.source.level), Vec3Argument.getVec3(ctx, "pos"), o.color, o.scale, e.group, o.visual, o.lifeCurve)
        added(ctx, before, e)
    }

    private fun addField(ctx: CommandContext<CommandSourceStack>, raw: String): Int = safe(ctx) {
        val e = entry(ctx, false); val before = e.group.size(); val o = options(raw)
        requireOptions(o, FIELD_OPTIONS)
        val manager = ParticleManager.of(ctx.source.level)
        val pos = Vec3Argument.getVec3(ctx, "pos")
        val count = IntegerArgumentType.getInteger(ctx, "count")
        reserveMembers(e, count)
        val batch = ParticleBatch(manager)
        repeat(count) { i ->
            val progress = if (count > 1) i.toDouble() / (count - 1) else 0.0
            val builder = manager.create().position(pos).color(o.color.colorAt(progress))
                .scale(o.scale).lifetime(o.lifetime).group(e.group.id).offsetFromPivot(pos.subtract(e.group.pivot))
                .visual(o.visual).glowing(o.visual.glowing).lightLevel(o.visual.lightLevel)
            o.lifeCurve?.let(builder::lifeCurve)
            batch.add(builder.spawn())
        }
        o.velocity?.let { v -> batch.setVelocityAll(List(batch.size()) { v }) }
        o.force?.let { v -> batch.applyForceAll(List(batch.size()) { v }, o.forceTicks) }
        added(ctx, before, e)
    }

    private fun addLine(ctx: CommandContext<CommandSourceStack>, raw: String): Int = safe(ctx) {
        val e = entry(ctx, false); val before = e.group.size(); val o = options(raw)
        requireOptions(o, SHAPE_OPTIONS + "taper")
        reserveMembers(e, IntegerArgumentType.getInteger(ctx, "count"), o.stagger)
        Draw.line(ParticleManager.of(ctx.source.level), Vec3Argument.getVec3(ctx, "start"), Vec3Argument.getVec3(ctx, "end"),
            IntegerArgumentType.getInteger(ctx, "count"), o.color, o.scale, o.stagger, e.group, o.visual, o.taper, o.lifeCurve)
        added(ctx, before, e)
    }

    private fun addBeam(ctx: CommandContext<CommandSourceStack>, raw: String): Int = safe(ctx) {
        val e = entry(ctx, false); val before = e.group.size(); val o = options(raw)
        requireOptions(o, BEAM_OPTIONS)
        reserveMembers(e, 1)
        Draw.polyline(ParticleManager.of(ctx.source.level), listOf(Vec3Argument.getVec3(ctx, "start"), Vec3Argument.getVec3(ctx, "end")),
            o.thickness, o.taper, o.visual.texture, o.visual.additive, o.visual.glowing, o.color, o.stagger, e.group, o.lifeCurve)
        added(ctx, before, e)
    }

    private fun addHelix(ctx: CommandContext<CommandSourceStack>, raw: String): Int = safe(ctx) {
        val e = entry(ctx, false); val before = e.group.size(); val o = options(raw)
        requireOptions(o, SHAPE_OPTIONS + "taper")
        reserveMembers(e, IntegerArgumentType.getInteger(ctx, "count"), o.stagger)
        val center = Vec3Argument.getVec3(ctx, "center")
        val radius = DoubleArgumentType.getDouble(ctx, "radius")
        val height = DoubleArgumentType.getDouble(ctx, "height")
        val turns = DoubleArgumentType.getDouble(ctx, "turns")
        Draw.curve(ParticleManager.of(ctx.source.level), { t ->
            val a = t * turns * PI * 2.0
            center.add(cos(a) * radius, (t - 0.5) * height, sin(a) * radius)
        }, IntegerArgumentType.getInteger(ctx, "count"), o.color, o.scale, o.stagger, e.group, o.visual, o.taper, o.lifeCurve)
        added(ctx, before, e)
    }

    private fun addCircle(ctx: CommandContext<CommandSourceStack>, raw: String): Int = safe(ctx) {
        val e = entry(ctx, false); val before = e.group.size(); val o = options(raw)
        requireOptions(o, SHAPE_OPTIONS + "axis")
        reserveMembers(e, IntegerArgumentType.getInteger(ctx, "count"), o.stagger)
        Draw.circle(ParticleManager.of(ctx.source.level), Vec3Argument.getVec3(ctx, "center"), DoubleArgumentType.getDouble(ctx, "radius"),
            IntegerArgumentType.getInteger(ctx, "count"), o.axis, o.color, o.scale, o.stagger, e.group, o.visual, o.lifeCurve)
        added(ctx, before, e)
    }

    private fun addDisc(ctx: CommandContext<CommandSourceStack>, raw: String): Int = safe(ctx) {
        val e = entry(ctx, false); val before = e.group.size(); val o = options(raw)
        requireOptions(o, SHAPE_OPTIONS + "axis")
        val perimeter = IntegerArgumentType.getInteger(ctx, "perimeter")
        val layers = IntegerArgumentType.getInteger(ctx, "layers")
        reserveMembers(e, perimeter * (layers + 1), o.stagger)
        Draw.disc(ParticleManager.of(ctx.source.level), Vec3Argument.getVec3(ctx, "center"), DoubleArgumentType.getDouble(ctx, "radius"),
            perimeter, layers, o.axis,
            o.color, o.scale, o.stagger, e.group, o.visual, o.lifeCurve)
        added(ctx, before, e)
    }

    private fun addTriangle(ctx: CommandContext<CommandSourceStack>, raw: String): Int = safe(ctx) {
        val e = entry(ctx, false); val before = e.group.size(); val o = options(raw)
        requireOptions(o, SHAPE_OPTIONS + "axis")
        reserveMembers(e, IntegerArgumentType.getInteger(ctx, "segments") * 3, o.stagger)
        Draw.triangle(ParticleManager.of(ctx.source.level), Vec3Argument.getVec3(ctx, "center"), DoubleArgumentType.getDouble(ctx, "radius"),
            IntegerArgumentType.getInteger(ctx, "segments"), 0.0, o.axis, o.color, o.scale, o.stagger, e.group, o.visual, o.lifeCurve)
        added(ctx, before, e)
    }

    private fun addHexagram(ctx: CommandContext<CommandSourceStack>, raw: String): Int = safe(ctx) {
        val e = entry(ctx, false); val before = e.group.size(); val o = options(raw)
        requireOptions(o, SHAPE_OPTIONS + "axis")
        reserveMembers(e, IntegerArgumentType.getInteger(ctx, "segments") * 6, o.stagger)
        Draw.hexagram(ParticleManager.of(ctx.source.level), Vec3Argument.getVec3(ctx, "center"), DoubleArgumentType.getDouble(ctx, "radius"),
            IntegerArgumentType.getInteger(ctx, "segments"), o.axis, o.color, o.color, o.scale, o.stagger, e.group, o.visual, o.lifeCurve)
        added(ctx, before, e)
    }

    private fun addRect(ctx: CommandContext<CommandSourceStack>, raw: String): Int = safe(ctx) {
        val e = entry(ctx, false); val before = e.group.size(); val o = options(raw)
        requireOptions(o, SHAPE_OPTIONS + setOf("axis", "hollow"))
        val density = IntegerArgumentType.getInteger(ctx, "density")
        reserveMembers(e, (density + 1) * (density + 1), o.stagger)
        Draw.rect(ParticleManager.of(ctx.source.level), Vec3Argument.getVec3(ctx, "center"), DoubleArgumentType.getDouble(ctx, "width"),
            DoubleArgumentType.getDouble(ctx, "height"), density, o.hollow, o.axis,
            o.color, o.scale, o.stagger, e.group, o.visual, o.lifeCurve)
        added(ctx, before, e)
    }

    private fun addSphere(ctx: CommandContext<CommandSourceStack>, raw: String): Int = safe(ctx) {
        val e = entry(ctx, false); val before = e.group.size(); val o = options(raw)
        requireOptions(o, SHAPE_OPTIONS)
        reserveMembers(e, IntegerArgumentType.getInteger(ctx, "count"), o.stagger)
        Draw.sphere(ParticleManager.of(ctx.source.level), Vec3Argument.getVec3(ctx, "center"), DoubleArgumentType.getDouble(ctx, "radius"),
            IntegerArgumentType.getInteger(ctx, "count"), o.color, o.scale, o.stagger, e.group, o.visual, o.lifeCurve)
        added(ctx, before, e)
    }

    private fun addCuboid(ctx: CommandContext<CommandSourceStack>, raw: String): Int = safe(ctx) {
        val e = entry(ctx, false); val before = e.group.size(); val o = options(raw)
        requireOptions(o, SHAPE_OPTIONS + "hollow")
        val density = IntegerArgumentType.getInteger(ctx, "density")
        reserveMembers(e, (density + 1) * (density + 1) * (density + 1), o.stagger)
        Draw.cuboid(ParticleManager.of(ctx.source.level), Vec3Argument.getVec3(ctx, "center"), DoubleArgumentType.getDouble(ctx, "width"),
            DoubleArgumentType.getDouble(ctx, "height"), DoubleArgumentType.getDouble(ctx, "depth"),
            density, o.hollow, o.color, o.scale, o.stagger, e.group, o.visual, o.lifeCurve)
        added(ctx, before, e)
    }

    private fun program(
        ctx: CommandContext<CommandSourceStack>,
        activates: Boolean = true,
        requiresActive: Boolean = false,
        action: (CommandParticleGroups.Entry) -> Unit,
    ): Int = safe(ctx) {
        // 这些值会在 stagger 队列到点后使用，先在命令执行时校验，避免延迟到后台才报错。
        easing(ctx)
        optionalString(ctx, "color")?.let(ParticleCommandOptionParser::parseColor)
        optionalString(ctx, "points")?.let(::parsePath)
        optionalString(ctx, "handle")?.let { requireFormulaName(it, "实体句柄") }
        optionalString(ctx, "name")?.let { requireFormulaName(it, "变量名") }
        val e = entry(ctx)
        require(e.group.size() > 0) { "粒子组 '${e.name}' 还没有成员；先用 particle add 添加图元" }
        require(!requiresActive || e.activated) { "粒子组 '${e.name}' 还没有启动程序" }
        require(activates || !e.activated) { "静态变量和实体句柄必须在首条动画或 expression 之前声明" }
        val queued = CommandParticleGroups.hasPendingMembers(e)
        if (queued) {
            e.programmed = true
            if (activates) e.activated = true
            CommandParticleGroups.runWhenMembersReady(dimension(ctx), e) {
                try {
                    action(e)
                } catch (error: Exception) {
                    ctx.source.sendFailure(Component.literal(error.message ?: "粒子编排参数错误"))
                }
            }
        } else {
            action(e)
            e.programmed = true
            if (activates) e.activated = true
        }
        val message = if (queued) "已排入粒子组 '${e.name}' 的程序，错峰成员到齐后执行" else "已更新粒子组 '${e.name}' 的程序"
        ctx.source.sendSuccess({ Component.literal(message) }, false)
        1
    }

    private fun easing(
        ctx: CommandContext<CommandSourceStack>,
        default: EasingType = EasingType.LINEAR,
    ): EasingType {
        val raw = try {
            StringArgumentType.getString(ctx, "easing")
        } catch (_: IllegalArgumentException) {
            return default
        }
        return ParticleCommandOptionParser.easing(raw)
    }

    private fun programPivot(ctx: CommandContext<CommandSourceStack>) = program(ctx) { it.group.setPivot(Vec3Argument.getVec3(ctx, "pos")) }
    private fun programFollow(ctx: CommandContext<CommandSourceStack>): Int {
        val entity = EntityArgument.getEntity(ctx, "entity")
        val offset = try { Vec3(DoubleArgumentType.getDouble(ctx, "x"), DoubleArgumentType.getDouble(ctx, "y"), DoubleArgumentType.getDouble(ctx, "z")) } catch (_: IllegalArgumentException) { Vec3.ZERO }
        val local = try { BoolArgumentType.getBool(ctx, "local") } catch (_: IllegalArgumentException) { false }
        return program(ctx) { it.group.followEntity(entity.uuid, offset, local) }
    }
    private fun programDelay(ctx: CommandContext<CommandSourceStack>) = program(ctx) { it.group.delay(IntegerArgumentType.getInteger(ctx, "ticks")) }
    private fun programFadeIn(ctx: CommandContext<CommandSourceStack>) = program(ctx) {
        it.group.fadeIn(IntegerArgumentType.getInteger(ctx, "ticks"), easing(ctx, EasingType.EASE_OUT))
    }
    private fun programFadeOut(ctx: CommandContext<CommandSourceStack>) = program(ctx) {
        it.group.fadeOut(IntegerArgumentType.getInteger(ctx, "ticks"), true, easing(ctx, EasingType.EASE_IN))
    }
    private fun programFadeOutKeep(ctx: CommandContext<CommandSourceStack>) = program(ctx) {
        it.group.fadeOut(IntegerArgumentType.getInteger(ctx, "ticks"), false, easing(ctx, EasingType.EASE_IN))
    }
    private fun programMove(ctx: CommandContext<CommandSourceStack>) = program(ctx) { it.group.move(vec3(ctx), IntegerArgumentType.getInteger(ctx, "ticks"), easing(ctx)) }
    private fun programMoveOffset(ctx: CommandContext<CommandSourceStack>) = program(ctx) { it.group.moveAlongOffset(DoubleArgumentType.getDouble(ctx, "ratio").toFloat(), IntegerArgumentType.getInteger(ctx, "ticks"), easing(ctx)) }
    private fun programMovePath(ctx: CommandContext<CommandSourceStack>) = program(ctx) {
        val points = parsePath(StringArgumentType.getString(ctx, "points"))
        it.group.movePath(points, IntegerArgumentType.getInteger(ctx, "ticks"), easing(ctx))
    }
    private fun programRotate(ctx: CommandContext<CommandSourceStack>) = program(ctx) {
        it.group.rotate(vec3(ctx), Math.toRadians(DoubleArgumentType.getDouble(ctx, "degrees")), IntegerArgumentType.getInteger(ctx, "ticks"), easing(ctx))
    }
    private fun programRecolor(ctx: CommandContext<CommandSourceStack>) = program(ctx) {
        it.group.recolor(ParticleCommandOptionParser.parseColor(StringArgumentType.getString(ctx, "color")), IntegerArgumentType.getInteger(ctx, "ticks"), easing(ctx))
    }
    private fun programScaleBy(ctx: CommandContext<CommandSourceStack>) = program(ctx) { it.group.scaleBy(DoubleArgumentType.getDouble(ctx, "ratio").toFloat(), IntegerArgumentType.getInteger(ctx, "ticks"), easing(ctx)) }
    private fun programScaleTo(ctx: CommandContext<CommandSourceStack>) = program(ctx) { it.group.scaleTo(DoubleArgumentType.getDouble(ctx, "ratio").toFloat(), IntegerArgumentType.getInteger(ctx, "ticks"), easing(ctx)) }
    private fun programSpin(ctx: CommandContext<CommandSourceStack>) = program(ctx) { it.group.spin(vec3(ctx), Math.toRadians(DoubleArgumentType.getDouble(ctx, "degrees_per_tick"))) }
    private fun programPulse(ctx: CommandContext<CommandSourceStack>) = program(ctx) {
        val cycles = try { IntegerArgumentType.getInteger(ctx, "cycles") } catch (_: IllegalArgumentException) { -1 }
        it.group.pulse(DoubleArgumentType.getDouble(ctx, "peak").toFloat(), IntegerArgumentType.getInteger(ctx, "half_period"), cycles)
    }
    private fun programStop(ctx: CommandContext<CommandSourceStack>) = program(ctx, requiresActive = true) { it.group.stopContinuous() }
    private fun programDestroyAfter(ctx: CommandContext<CommandSourceStack>) = program(ctx) { it.group.destroyAfter(IntegerArgumentType.getInteger(ctx, "ticks")) }
    private fun programRetire(ctx: CommandContext<CommandSourceStack>) = program(ctx, requiresActive = true) {
        val grace = try { IntegerArgumentType.getInteger(ctx, "grace") } catch (_: IllegalArgumentException) { 2 }
        it.group.retire(grace)
    }
    private fun programEntity(ctx: CommandContext<CommandSourceStack>) = program(ctx, activates = false) {
        it.group.defineEntity(StringArgumentType.getString(ctx, "handle"), EntityArgument.getEntity(ctx, "entity").uuid)
    }
    private fun programVariableSet(ctx: CommandContext<CommandSourceStack>) = program(ctx, activates = false) {
        it.group.setVariable(StringArgumentType.getString(ctx, "name"), DoubleArgumentType.getDouble(ctx, "value"))
    }
    private fun programVariableLive(ctx: CommandContext<CommandSourceStack>) = program(ctx, requiresActive = true) {
        it.group.setVariableLive(StringArgumentType.getString(ctx, "name"), StringArgumentType.getString(ctx, "value"))
    }
    private fun programVariableEase(ctx: CommandContext<CommandSourceStack>) = program(ctx, requiresActive = true) {
        it.group.setVariableInterpolated(StringArgumentType.getString(ctx, "name"), StringArgumentType.getString(ctx, "value"),
            IntegerArgumentType.getInteger(ctx, "ticks"), easing(ctx))
    }
    private fun programExpression(ctx: CommandContext<CommandSourceStack>) = program(ctx) {
        it.group.expression(StringArgumentType.getString(ctx, "code"), IntegerArgumentType.getInteger(ctx, "ticks"))
    }

    private fun vec3(ctx: CommandContext<CommandSourceStack>) = Vec3(
        DoubleArgumentType.getDouble(ctx, "x"), DoubleArgumentType.getDouble(ctx, "y"), DoubleArgumentType.getDouble(ctx, "z"),
    )

    private fun optionalString(ctx: CommandContext<CommandSourceStack>, name: String): String? = try {
        StringArgumentType.getString(ctx, name)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun requireFormulaName(name: String, label: String) {
        require(FORMULA_NAME.matches(name)) { "$label '$name' 不是合法标识符" }
        require(name !in RESERVED_FORMULA_NAMES) { "$label '$name' 与公式内建名冲突" }
    }

    private fun parsePath(rawPath: String): List<Vec3> = rawPath.split(';').mapIndexed { index, raw ->
        val parts = raw.split(',')
        require(parts.size == 3) { "move_path 第 ${index + 1} 个点需要写成 x,y,z" }
        val values = parts.map { it.toDoubleOrNull()?.takeIf(Double::isFinite) }
        require(values.all { it != null }) { "move_path 第 ${index + 1} 个点含无效数字" }
        Vec3(values[0]!!, values[1]!!, values[2]!!)
    }

    private fun help(ctx: CommandContext<CommandSourceStack>): Int {
        ctx.source.sendSuccess({ Component.literal("/pdraw particle group|add|program；先建命名组，再添加图元，最后逐条编排") }, false)
        ctx.source.sendSuccess({ Component.literal("add 参数: color/gradient/rainbow, scale/stagger, style/texture/uv/size, additive/glowing/light, *_curve；输入子命令可看补全") }, false)
        return 1
    }
    private fun groupHelp(ctx: CommandContext<CommandSourceStack>): Int {
        ctx.source.sendSuccess({ Component.literal("group create|reset <名称> <轴心> | list | remove <名称> | clear") }, false)
        return 1
    }
    private fun addHelp(ctx: CommandContext<CommandSourceStack>): Int {
        ctx.source.sendSuccess({ Component.literal("add <组> dot|field|line|beam|helix|circle|disc|triangle|hexagram|rect|sphere|cuboid ... [key=value]") }, false)
        return 1
    }
    private fun programHelp(ctx: CommandContext<CommandSourceStack>): Int {
        ctx.source.sendSuccess({ Component.literal("program <组> anchor|pivot|follow|delay|fade_in|fade_out|move|move_path|rotate|recolor|scale_by|scale_to|spin|pulse|variable|entity|expression|retire ...") }, false)
        return 1
    }

    private fun suggestGroups(ctx: CommandContext<CommandSourceStack>, builder: SuggestionsBuilder): CompletableFuture<Suggestions> =
        SharedSuggestionProvider.suggest(CommandParticleGroups.names(dimension(ctx)), builder)

    private fun suggestEasings(ctx: CommandContext<CommandSourceStack>, builder: SuggestionsBuilder): CompletableFuture<Suggestions> =
        SharedSuggestionProvider.suggest(EASINGS, builder)

    private val EASINGS = listOf(
        "linear", "ease_in", "ease_out", "ease_in_out", "ease_in_quad", "ease_out_quad", "ease_in_out_quad",
        "ease_in_cubic", "ease_out_cubic", "ease_in_out_cubic", "ease_in_bounce", "ease_out_bounce",
        "ease_in_elastic", "ease_out_elastic", "none",
    )
}
