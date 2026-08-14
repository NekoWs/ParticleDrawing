package work.nekow.particledrawing.command

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.RegisterCommandsEvent
import work.nekow.particledrawing.ParticleDrawing
import work.nekow.particledrawing.animation.AnimationLoader
import work.nekow.particledrawing.animation.AnimationPlayer
import work.nekow.particledrawing.animation.AnimationPlayerManager
import work.nekow.particledrawing.core.server.ServerParticleEngine
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
                    .then(Commands.argument("name", StringArgumentType.string()).executes(::playAnimation)))
                .then(Commands.literal("stop").executes(::stopAnimations))
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
            "暂无动画，请将导出的 .json 放入 animations/ 目录"
        } else {
            "可用动画: " + names.joinToString(", ")
        }
        ctx.source.sendSuccess({ Component.literal(msg) }, false)
        return names.size
    }

    /**
     * /test play <name> —— 在玩家面前播放动画。
     */
    private fun playAnimation(ctx: CommandContext<CommandSourceStack>): Int {
        val name = StringArgumentType.getString(ctx, "name")
        val json = AnimationLoader.load(name)
            ?: run {
                ctx.source.sendFailure(Component.literal("未找到动画: $name"))
                return 0
            }
        val animation = try {
            AnimationLoader.parse(json)
        } catch (e: Exception) {
            ctx.source.sendFailure(Component.literal("解析失败: ${e.message}"))
            return 0
        }

        val level = ctx.source.level
        val dim = ParticleUtils.dimensionUUID(level)
        val engine = ServerParticleEngine.getOrCreate(dim)
        val player = ctx.source.playerOrException
        val origin = player.position().add(player.lookAngle.scale(3.0))

        AnimationPlayerManager.start(dim, AnimationPlayer(engine, origin, animation, level.players()))
        ctx.source.sendSuccess(
            { Component.literal("正在播放动画: $name（粒子 ${animation.particles.size}，轨道 ${animation.tracks.size}）") },
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
        AnimationPlayerManager.stopAll(dim, level.players())
        ctx.source.sendSuccess({ Component.literal("已停止当前维度的全部动画") }, false)
        return 1
    }
}
