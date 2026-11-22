package work.nekow.particledrawing.command

import work.nekow.particledrawing.i18n.failureComponent
import work.nekow.particledrawing.i18n.component
import work.nekow.particledrawing.i18n.LocalizedText
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import work.nekow.particledrawing.animation.morph.MorphAllocation
import work.nekow.particledrawing.animation.morph.MorphEasing
import work.nekow.particledrawing.animation.morph.MorphOptions
import work.nekow.particledrawing.animation.morph.MorphPath
import work.nekow.particledrawing.animation.morph.MorphWobble
import work.nekow.particledrawing.core.easing.EasingType
import work.nekow.particledrawing.util.ParticleUtils
import java.util.UUID
import work.nekow.particledrawing.i18n.LocalizedArgumentException
import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.i18n.translation

/**
 * `pdraw particle program <组> morph …` 的命令树。
 *
 * 形变参数多，逐个下发不现实，所以先攒在一个草稿里，`morph start` 时才真正下发；
 * 草稿按「维度 + 组名」分开存，不会互相污染。
 */
object MorphCommandTree {

    private class Draft {
        var slotCount: Int = 16
        var position: String = ""
        var yaw: String? = null
        var pitch: String? = null
        var size: String? = null
        var control1: String? = null
        var control2: String? = null
        var ticks: Int = 40
        var stagger: Int = 0
        var maxSpeed: Double = 0.0
        var maxAccel: Double = 0.0
        var path: MorphPath = MorphPath.STRAIGHT
        var easing: String = "linear"
        var wobble: MorphWobble = MorphWobble.NONE
        var wobbleStrength: Double = 0.0
        var wobblePeaks: Double = 1.0
        var convergeEps: Double = 0.05
        var loop: Int = 0
        var allocation: MorphAllocation = MorphAllocation.NEAREST
        var keepVelocity: Boolean = false
        var attributes: Boolean? = null
        var dynamic: Boolean = false

        fun options(): MorphOptions = MorphOptions(
            ticks = ticks,
            staggerTicks = stagger,
            maxSpeed = maxSpeed,
            maxAccel = maxAccel,
            path = path,
            easing = MorphEasing.byName(easing) ?: MorphEasing.LINEAR,
            wobble = wobble,
            wobbleStrength = wobbleStrength,
            wobblePeaks = wobblePeaks,
            convergeEps = convergeEps,
            loop = loop,
            allocation = allocation,
            keepVelocity = keepVelocity,
            writeAttributes = attributes ?: (yaw != null || pitch != null || size != null),
        )
    }

    private val draftNames = HashMap<UUID, MutableMap<String, Draft>>()

    /** 每个维度最多缓存的 morph 草稿数，避免随便写名字就能堆住公式字符串。 */
    private const val MAX_DRAFTS = 64

    private fun draft(ctx: CommandContext<CommandSourceStack>): Draft {
        val dim = ParticleUtils.dimensionUUID(ctx.source.level)
        val name = StringArgumentType.getString(ctx, "group")
        // 草稿只能挂在真实存在的组上，名字打错要明确报错而不是默默建一张表。
        CommandParticleGroups.get(dim, name)
            ?: throw LocalizedArgumentException(translation("error.group.not_found", name))
        val drafts = draftNames.getOrPut(dim) { HashMap() }
        if (name !in drafts) {
            requireLocalized(drafts.size < MAX_DRAFTS) { translation("error.morph.drafts", MAX_DRAFTS) }
        }
        return drafts.getOrPut(name) { Draft() }
    }

    /** 维度卸载：草稿随关卡作废。 */
    fun discard(dimension: UUID) {
        draftNames.remove(dimension)
    }

    /** 服务器关闭：全部丢弃。 */
    fun clear() {
        draftNames.clear()
    }

    private fun entry(ctx: CommandContext<CommandSourceStack>) = run {
        val dim = ParticleUtils.dimensionUUID(ctx.source.level)
        val name = StringArgumentType.getString(ctx, "group")
        CommandParticleGroups.get(dim, name)
            ?: throw LocalizedArgumentException(translation("error.group.not_found", name))
    }

    private fun success(ctx: CommandContext<CommandSourceStack>, message: LocalizedText): Int {
        ctx.source.sendSuccess({ message.component() }, false)
        return 1
    }

    private fun safe(ctx: CommandContext<CommandSourceStack>, action: () -> Int): Int = try {
        action()
    } catch (e: IllegalArgumentException) {
        ctx.source.sendFailure(e.failureComponent("error.morph.parameters"))
        0
    }

    fun tree(): LiteralArgumentBuilder<CommandSourceStack> {
        val root = Commands.literal("morph")

        root.then(
            Commands.literal("slots").then(
                Commands.argument("count", IntegerArgumentType.integer(1, 65536)).then(
                    Commands.argument("position", StringArgumentType.greedyString()).executes { ctx ->
                        safe(ctx) {
                            val d = draft(ctx)
                            d.slotCount = IntegerArgumentType.getInteger(ctx, "count")
                            d.position = StringArgumentType.getString(ctx, "position")
                            success(ctx, translation("command.morph.slots", d.slotCount, d.position))
                        }
                    },
                ),
            ),
        )

        for (field in listOf("yaw", "pitch", "size", "control1", "control2")) {
            root.then(
                Commands.literal(field).then(
                    Commands.argument("formula", StringArgumentType.greedyString()).executes { ctx ->
                        safe(ctx) {
                            val d = draft(ctx)
                            val f = StringArgumentType.getString(ctx, "formula")
                            when (field) {
                                "yaw" -> d.yaw = f
                                "pitch" -> d.pitch = f
                                "size" -> d.size = f
                                "control1" -> d.control1 = f
                                else -> d.control2 = f
                            }
                            success(ctx, translation("command.setting", field, f))
                        }
                    },
                ),
            )
        }

        for (field in listOf("ticks", "stagger", "loop")) {
            root.then(
                Commands.literal(field).then(
                    Commands.argument("value", IntegerArgumentType.integer()).executes { ctx ->
                        safe(ctx) {
                            val d = draft(ctx)
                            val v = IntegerArgumentType.getInteger(ctx, "value")
                            when (field) {
                                "ticks" -> d.ticks = v
                                "stagger" -> d.stagger = v
                                else -> d.loop = v
                            }
                            success(ctx, translation("command.setting", field, v))
                        }
                    },
                ),
            )
        }

        for (field in listOf("max_speed", "max_accel", "converge")) {
            root.then(
                Commands.literal(field).then(
                    Commands.argument("value", DoubleArgumentType.doubleArg(0.0)).executes { ctx ->
                        safe(ctx) {
                            val d = draft(ctx)
                            val v = DoubleArgumentType.getDouble(ctx, "value")
                            when (field) {
                                "max_speed" -> d.maxSpeed = v
                                "max_accel" -> d.maxAccel = v
                                else -> d.convergeEps = if (v <= 0.0) 0.05 else v
                            }
                            success(ctx, translation("command.setting", field, v))
                        }
                    },
                ),
            )
        }

        root.then(
            Commands.literal("path").then(
                Commands.argument("kind", StringArgumentType.word())
                    .suggests { _, b -> SharedSuggestionProvider.suggest(listOf("straight", "quadratic", "cubic"), b) }
                    .executes { ctx ->
                        safe(ctx) {
                            val d = draft(ctx)
                            d.path = when (StringArgumentType.getString(ctx, "kind").lowercase()) {
                                "straight", "line" -> MorphPath.STRAIGHT
                                "quadratic", "quad", "bezier" -> MorphPath.QUADRATIC
                                "cubic" -> MorphPath.CUBIC
                                else -> throw LocalizedArgumentException(translation("error.morph.path"))
                            }
                            success(ctx, translation("command.setting", "path", d.path))
                        }
                    },
            ),
        )

        root.then(
            Commands.literal("easing").then(
                Commands.argument("name", StringArgumentType.word()).executes { ctx ->
                    safe(ctx) {
                        val d = draft(ctx)
                        val name = StringArgumentType.getString(ctx, "name")
                        requireLocalized(MorphEasing.byName(name) != null) { translation("error.easing.unknown", name) }
                        d.easing = name
                        success(ctx, translation("command.setting", "easing", name))
                    }
                },
            ),
        )

        root.then(
            Commands.literal("wobble").then(
                Commands.argument("kind", StringArgumentType.word())
                    .suggests { _, b -> SharedSuggestionProvider.suggest(listOf("none", "bell", "noise", "vortex"), b) }
                    .executes { ctx ->
                        safe(ctx) {
                            val d = draft(ctx)
                            d.wobble = when (StringArgumentType.getString(ctx, "kind").lowercase()) {
                                "none" -> MorphWobble.NONE
                                "bell" -> MorphWobble.BELL
                                "noise" -> MorphWobble.NOISE
                                "vortex" -> MorphWobble.VORTEX
                                else -> throw LocalizedArgumentException(translation("error.morph.wobble"))
                            }
                            success(ctx, translation("command.setting", "wobble", d.wobble))
                        }
                    }
                    .then(
                        Commands.argument("strength", DoubleArgumentType.doubleArg(0.0))
                            .then(
                                Commands.argument("peaks", DoubleArgumentType.doubleArg(0.0)).executes { ctx ->
                                    safe(ctx) {
                                        val d = draft(ctx)
                                        d.wobble = when (StringArgumentType.getString(ctx, "kind").lowercase()) {
                                            "bell" -> MorphWobble.BELL
                                            "noise" -> MorphWobble.NOISE
                                            "vortex" -> MorphWobble.VORTEX
                                            else -> MorphWobble.NONE
                                        }
                                        d.wobbleStrength = DoubleArgumentType.getDouble(ctx, "strength")
                                        d.wobblePeaks = DoubleArgumentType.getDouble(ctx, "peaks")
                                        success(ctx, translation("command.morph.wobble", d.wobble, d.wobbleStrength, d.wobblePeaks))
                                    }
                                },
                            ),
                    ),
            ),
        )

        root.then(
            Commands.literal("allocation").then(
                Commands.argument("kind", StringArgumentType.word())
                    .suggests { _, b -> SharedSuggestionProvider.suggest(listOf("nearest", "index", "angle"), b) }
                    .executes { ctx ->
                        safe(ctx) {
                            val d = draft(ctx)
                            d.allocation = when (StringArgumentType.getString(ctx, "kind").lowercase()) {
                                "nearest" -> MorphAllocation.NEAREST
                                "index" -> MorphAllocation.INDEX
                                "angle" -> MorphAllocation.ANGLE
                                else -> throw LocalizedArgumentException(translation("error.morph.allocation"))
                            }
                            success(ctx, translation("command.setting", "allocation", d.allocation))
                        }
                    },
            ),
        )

        for (field in listOf("dynamic", "keep_velocity", "attributes")) {
            root.then(
                Commands.literal(field).then(
                    Commands.argument("value", com.mojang.brigadier.arguments.BoolArgumentType.bool()).executes { ctx ->
                        safe(ctx) {
                            val d = draft(ctx)
                            val v = com.mojang.brigadier.arguments.BoolArgumentType.getBool(ctx, "value")
                            when (field) {
                                "dynamic" -> d.dynamic = v
                                "keep_velocity" -> d.keepVelocity = v
                                else -> d.attributes = v
                            }
                            success(ctx, translation("command.setting", field, v))
                        }
                    },
                ),
            )
        }

        root.then(
            Commands.literal("start").executes { ctx ->
                safe(ctx) {
                    val d = draft(ctx)
                    requireLocalized(d.position.isNotBlank()) { translation("error.morph.slots_command") }
                    val e = entry(ctx)
                    e.group.morph(
                        d.slotCount, d.position, d.options(),
                        d.yaw, d.pitch, d.size, d.control1, d.control2, d.dynamic,
                    )
                    success(
                        ctx,
                        translation("command.morph.start", d.slotCount, d.ticks),
                    )
                }
            },
        )

        root.then(
            Commands.literal("inspect").executes { ctx ->
                safe(ctx) {
                    val d = draft(ctx)
                    success(
                        ctx,
                        translation(
                            "command.morph.inspect", d.slotCount, d.position, d.ticks, d.stagger, d.maxSpeed,
                            d.path, d.easing, d.wobble, d.allocation, d.loop, d.dynamic,
                        ),
                    )
                }
            },
        )

        root.then(Commands.literal("reset").executes { ctx ->
            safe(ctx) {
                val dim = ParticleUtils.dimensionUUID(ctx.source.level)
                val name = StringArgumentType.getString(ctx, "group")
                draftNames[dim]?.remove(name)
                success(ctx, translation("command.morph.reset", name))
            }
        })

        return root
    }
}
