package work.nekow.particledrawing.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.network.chat.Component
import work.nekow.particledrawing.animation.morph.MorphAllocation
import work.nekow.particledrawing.animation.morph.MorphEasing
import work.nekow.particledrawing.animation.morph.MorphOptions
import work.nekow.particledrawing.animation.morph.MorphPath
import work.nekow.particledrawing.animation.morph.MorphSlotFormulas
import work.nekow.particledrawing.animation.morph.MorphWobble
import work.nekow.particledrawing.core.easing.EasingType
import work.nekow.particledrawing.util.ParticleUtils
import java.util.UUID

/**
 * `pdraw particle group <组> morph …` 的命令树。
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
        var attributes: Boolean = false
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
            writeAttributes = attributes,
        )
    }

    private val drafts = HashMap<UUID, Draft>()
    private val draftNames = HashMap<UUID, MutableMap<String, Draft>>()

    private fun draft(ctx: CommandContext<CommandSourceStack>): Draft {
        val dim = ParticleUtils.dimensionUUID(ctx.source.level)
        val name = StringArgumentType.getString(ctx, "group")
        return draftNames.getOrPut(dim) { HashMap() }.getOrPut(name) { Draft() }
    }

    private fun entry(ctx: CommandContext<CommandSourceStack>) = run {
        val dim = ParticleUtils.dimensionUUID(ctx.source.level)
        val name = StringArgumentType.getString(ctx, "group")
        CommandParticleGroups.get(dim, name)
            ?: throw IllegalArgumentException("当前维度没有粒子组 '$name'")
    }

    private fun success(ctx: CommandContext<CommandSourceStack>, message: String): Int {
        ctx.source.sendSuccess({ Component.literal(message) }, false)
        return 1
    }

    private fun safe(ctx: CommandContext<CommandSourceStack>, action: () -> Int): Int = try {
        action()
    } catch (e: IllegalArgumentException) {
        ctx.source.sendFailure(Component.literal(e.message ?: "编队变换参数错误"))
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
                            success(ctx, "槽位 ${d.slotCount} 个：${d.position}")
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
                            success(ctx, "槽位 $field = $f")
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
                            success(ctx, "$field = $v")
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
                            success(ctx, "$field = $v")
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
                                else -> throw IllegalArgumentException("路径只能是 straight / quadratic / cubic")
                            }
                            success(ctx, "path = ${d.path}")
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
                        require(MorphEasing.byName(name) != null) { "未知缓动 '$name'" }
                        d.easing = name
                        success(ctx, "easing = $name")
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
                                else -> throw IllegalArgumentException("扰动只能是 none / bell / noise / vortex")
                            }
                            success(ctx, "wobble = ${d.wobble}")
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
                                        success(ctx, "wobble = ${d.wobble} 强度 ${d.wobbleStrength} 峰值 ${d.wobblePeaks}")
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
                                else -> throw IllegalArgumentException("分配策略只能是 nearest / index / angle")
                            }
                            success(ctx, "allocation = ${d.allocation}")
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
                            success(ctx, "$field = $v")
                        }
                    },
                ),
            )
        }

        root.then(
            Commands.literal("start").executes { ctx ->
                safe(ctx) {
                    val d = draft(ctx)
                    require(d.position.isNotBlank()) { "还没有设置槽位：先 morph slots <数量> <位置公式>" }
                    // 先在服务端解析一遍，公式写错立刻报错。
                    MorphSlotFormulas(d.slotCount, d.position, d.yaw, d.pitch, d.size, d.control1, d.control2).build(0.0)
                    val e = entry(ctx)
                    e.group.morph(
                        d.slotCount, d.position, d.options(),
                        d.yaw, d.pitch, d.size, d.control1, d.control2, d.dynamic,
                    )
                    success(
                        ctx,
                        "编队变换已启动：${d.slotCount} 个槽位，${d.ticks} 刻，分配 ${d.allocation}，路径 ${d.path}",
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
                        "morph: 槽位 ${d.slotCount} 个 [${d.position}]；${d.ticks} 刻，错峰 ${d.stagger}，" +
                            "限速 ${d.maxSpeed}，路径 ${d.path}，缓动 ${d.easing}，扰动 ${d.wobble}，" +
                            "分配 ${d.allocation}，循环 ${d.loop}，动态 ${d.dynamic}",
                    )
                }
            },
        )

        root.then(Commands.literal("reset").executes { ctx ->
            safe(ctx) {
                val dim = ParticleUtils.dimensionUUID(ctx.source.level)
                val name = StringArgumentType.getString(ctx, "group")
                draftNames[dim]?.remove(name)
                success(ctx, "已清空 '$name' 的形变草稿")
            }
        })

        return root
    }
}
