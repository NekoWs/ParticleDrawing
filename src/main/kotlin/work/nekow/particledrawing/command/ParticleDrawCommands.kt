package work.nekow.particledrawing.command

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.coordinates.Vec3Argument
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.Vec3
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.api.distmarker.Dist
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.fml.loading.FMLEnvironment
import net.neoforged.neoforge.event.RegisterCommandsEvent
import work.nekow.particledrawing.ParticleDrawing
import work.nekow.particledrawing.animation.AnimationLoader
import work.nekow.particledrawing.animation.ServerAnimationManager
import work.nekow.particledrawing.core.client.ClientAnimationManager
import work.nekow.particledrawing.core.client.ClientParticleEngine
import work.nekow.particledrawing.util.ParticleUtils

/**
 * 命令注册。提供 /pdraw 及其子命令，用于加载播放网页编辑器导出的动画。
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
            Commands.literal("pdraw")
                .executes(::runTest)
                .then(Commands.literal("list").executes(::listAnimations))
                .then(Commands.literal("play")
                    .then(Commands.argument("name", StringArgumentType.string())
                        .executes(::playAnimation)
                        .then(Commands.argument("pos", Vec3Argument.vec3()).executes(::playAnimation))))
                .then(Commands.literal("stop").executes(::stopAnimations))
                .then(Commands.literal("reload").executes(::reloadTextures))
                .then(Commands.literal("debug").executes(::debugAnimations))
                .then(Commands.literal("var")
                    .then(Commands.argument("name", StringArgumentType.string())
                        .then(Commands.argument("value", StringArgumentType.greedyString()).executes(::updateVariable))))
        )
    }

    /**
     * /pdraw 命令执行逻辑。
     * @param ctx 命令上下文
     * @return 命令执行结果
     */
    private fun runTest(ctx: CommandContext<CommandSourceStack>): Int {
        // TODO: 在这里编写测试逻辑。
        return 1
    }

    /**
     * /pdraw list —— 列出可用的动画。
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
     * /pdraw play <name> [pos] —— 播放动画（客户端本地播放）。
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
     * /pdraw stop —— 停止当前维度的全部动画。
     */
    private fun stopAnimations(ctx: CommandContext<CommandSourceStack>): Int {
        val level = ctx.source.level
        val dim = ParticleUtils.dimensionUUID(level)
        ServerAnimationManager.stopAll(dim, level.players())
        ctx.source.sendSuccess({ Component.literal("已停止当前维度的全部动画") }, false)
        return 1
    }

    /**
     * /pdraw reload —— 重建贴图纹理（清空 TextureCache 并重新从 textures/ 目录加载全部贴图）。
     * 用于贴图 PNG 在磁盘上被外部修改后热重载，避免重启客户端。
     * 贴图渲染仅在客户端，故服务端（dedicated server）执行时直接返回提示。
     */
    private fun reloadTextures(ctx: CommandContext<CommandSourceStack>): Int {
        if (FMLEnvironment.getDist() != Dist.CLIENT) {
            ctx.source.sendFailure(Component.literal("贴图渲染仅在客户端，/pdraw reload 仅客户端可用"))
            return 0
        }
        ClientAnimationManager.reloadTextures()
        ctx.source.sendSuccess(
            { Component.literal("已重建贴图纹理（重新播放动画以应用）") },
            false
        )
        return 1
    }

    /**
     * /pdraw debug —— 显示所有播放中动画的调试信息（每刻求值用时 / 帧数 / 粒子数 / 时间轴）。
     */
    private fun debugAnimations(ctx: CommandContext<CommandSourceStack>): Int {
        val infos = ClientAnimationManager.debugInfo()
        val engine = ClientParticleEngine.instance()
        val engineParticles = engine?.activeCount() ?: 0

        if (infos.isEmpty()) {
            ctx.source.sendSuccess(
                { Component.literal("无播放中的动画（引擎活跃粒子总数: $engineParticles）") },
                false
            )
            return 0
        }

        for (info in infos) {
            val shortId = info.animId.toString().take(8)
            val lastMs = java.lang.String.format(java.util.Locale.ROOT, "%.2f", info.lastAdvanceMillis)
            val avgMs = java.lang.String.format(java.util.Locale.ROOT, "%.2f", info.avgAdvanceMillis)
            val msg = "动画 $shortId… | 粒子: ${info.particleCount} | " +
                "时间轴: ${info.currentTick}/${info.maxTick} tick | " +
                "已播放帧: ${info.frameCount} | " +
                "每刻求值: 上次 ${lastMs}ms / 平均 ${avgMs}ms"
            ctx.source.sendSuccess({ Component.literal(msg) }, false)
        }
        ctx.source.sendSuccess(
            { Component.literal("播放中动画数: ${infos.size}，引擎活跃粒子总数: $engineParticles") },
            false
        )
        return infos.size
    }

    /**
     * /pdraw var <name> <value> —— 更新当前维度全部播放中的函数对象变量（服务端权威下发）。
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
