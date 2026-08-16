package work.nekow.particledrawing.command

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.coordinates.Vec3Argument
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.Vec3
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.RegisterCommandsEvent
import work.nekow.particledrawing.ParticleDrawing
import work.nekow.particledrawing.animation.AnimationLoader
import work.nekow.particledrawing.animation.ServerAnimationManager
import work.nekow.particledrawing.util.ParticleUtils

/**
 * 命令注册。提供 /test 及其子命令，用于加载播放网页编辑器导出的动画。
 */
@EventBusSubscriber(modid = ParticleDrawing.MODID)
@Suppress("unused")
object ParticleDrawCommands {

    /**
     * 注册命令。
     * @param event 命令注册事件
     */
    @SubscribeEvent
    @JvmStatic
    fun onRegisterCommands(event: RegisterCommandsEvent) {
        val dispatcher = event.dispatcher

        dispatcher.register(
            Commands.literal("test")
                .executes(::runTest)
                .then(Commands.literal("list").executes(::listAnimations))
                .then(Commands.literal("play")
                    .then(Commands.argument("name", StringArgumentType.string())
                        .executes(::playAnimation)
                        .then(Commands.argument("pos", Vec3Argument.vec3()).executes(::playAnimation))))
                .then(Commands.literal("stop").executes(::stopAnimations))
                .then(Commands.literal("var")
                    .then(Commands.argument("name", StringArgumentType.string())
                        .then(Commands.argument("value", StringArgumentType.greedyString()).executes(::updateVariable))))
        )
    }

    /**
     * /test 命令执行逻辑。
     * @param ctx 命令上下文
     * @return 命令执行结果
     */
    private fun runTest(ctx: CommandContext<CommandSourceStack>): Int {
        // TODO: 在这里编写测试逻辑。
        return 1
    }

    /**
     * /test list —— 列出可用的动画。
     */
    private fun listAnimations(ctx: CommandContext<CommandSourceStack>): Int {
        val names = AnimationLoader.list()
        val msg = if (names.isEmpty()) {
            "暂无动画，请将导出的 .pdraw 放入 animations/ 目录"
        } else {
            "可用动画: " + names.joinToString(", ")
        }
        ctx.source.sendSuccess({ Component.literal(msg) }, false)
        return names.size
    }

    /**
     * /test play <name> [pos] —— 播放动画（客户端本地播放）。
     * pos 可省略（默认在玩家面前 3 格）；命令方块执行时必须提供 pos。
     * pos 支持绝对坐标与 ~ 相对坐标（相对命令执行者）。
     */
    private fun playAnimation(ctx: CommandContext<CommandSourceStack>): Int {
        val name = StringArgumentType.getString(ctx, "name")
        val json = AnimationLoader.load(name)
            ?: run {
                ctx.source.sendFailure(Component.literal("未找到动画: $name"))
                return 0
            }

        val level = ctx.source.level
        val dim = ParticleUtils.dimensionUUID(level)
        val origin: Vec3 = try {
            Vec3Argument.getVec3(ctx, "pos")
        } catch (_: IllegalArgumentException) {
            val player = ctx.source.playerOrException
            player.position().add(player.lookAngle.scale(3.0))
        }

        ServerAnimationManager.play(dim, level.players(), json, origin)
        ctx.source.sendSuccess(
            { Component.literal("正在播放动画: $name（客户端本地渲染）") },
            false
        )
        return 1
    }

    /**
     * /test stop —— 停止当前维度的全部动画。
     */
    private fun stopAnimations(ctx: CommandContext<CommandSourceStack>): Int {
        val level = ctx.source.level
        val dim = ParticleUtils.dimensionUUID(level)
        ServerAnimationManager.stopAll(dim, level.players())
        ctx.source.sendSuccess({ Component.literal("已停止当前维度的全部动画") }, false)
        return 1
    }

    /**
     * /test var <name> <value> —— 更新当前维度全部播放中的函数对象变量（服务端权威下发）。
     */
    private fun updateVariable(ctx: CommandContext<CommandSourceStack>): Int {
        val name = StringArgumentType.getString(ctx, "name")
        val value = StringArgumentType.getString(ctx, "value")
        val level = ctx.source.level
        val dim = ParticleUtils.dimensionUUID(level)
        ServerAnimationManager.updateVariableForDimension(dim, name, value, level.players())
        ctx.source.sendSuccess(
            { Component.literal("已更新变量: $name = $value") },
            false
        )
        return 1
    }
}
