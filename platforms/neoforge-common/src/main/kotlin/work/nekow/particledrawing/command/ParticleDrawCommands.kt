package work.nekow.particledrawing.command

import work.nekow.particledrawing.platform.PlatformCompat
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.commands.arguments.coordinates.Vec3Argument
import work.nekow.particledrawing.lighting.DynamicLightManager
import net.minecraft.world.phys.Vec3
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.api.distmarker.Dist
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.fml.loading.FMLEnvironment
import net.neoforged.neoforge.event.RegisterCommandsEvent
import work.nekow.particledrawing.ParticleDrawing
import work.nekow.particledrawing.animation.AnimationLoader
import work.nekow.particledrawing.animation.ServerAnimationManager
import work.nekow.particledrawing.animation.ServerEffectManager
import work.nekow.particledrawing.core.client.CameraController
import work.nekow.particledrawing.core.client.ClientAnimationManager
import work.nekow.particledrawing.core.client.ClientParticleEngine
import work.nekow.particledrawing.util.ParticleUtils
import java.util.Locale
import java.util.concurrent.CompletableFuture
import work.nekow.particledrawing.i18n.component
import work.nekow.particledrawing.i18n.translation

/**
 * 命令注册：/pdraw 及粒子绘制、动画播放、贴图管理子命令。
 */
@EventBusSubscriber(modid = ParticleDrawing.MODID)
@Suppress("unused")
object ParticleDrawCommands {

    /**
     * 注册 /pdraw。
     *
     * @param event 命令注册事件
     */
    @SubscribeEvent
    @JvmStatic
    fun onRegisterCommands(event: RegisterCommandsEvent) {
        val dispatcher = event.dispatcher

        val animation = Commands.literal("animation")
                .executes(::animationUsage)
                .then(Commands.literal("list").executes(::listAnimations))
                .then(Commands.literal("play")
                    .then(Commands.argument("name", StringArgumentType.string())
                        .suggests(::suggestAnimations)
                        .executes(::playAnimation)
                        .then(Commands.argument("pos", Vec3Argument.vec3()).executes(::playAnimation))))
                .then(Commands.literal("stop").executes(::stopAnimations))
                .then(Commands.literal("camera")
                    .executes(::cameraUsage)
                    .then(Commands.literal("stop").executes(::stopCamera))
                    .then(Commands.argument("name", StringArgumentType.string())
                        .suggests(::suggestCameras)
                        .executes(::switchCamera)))
                .then(Commands.literal("debug").executes(::debugAnimations))
                .then(Commands.literal("var")
                    .then(Commands.argument("name", StringArgumentType.string())
                        .then(Commands.argument("value", StringArgumentType.greedyString()).executes(::updateVariable))))

        dispatcher.register(
            Commands.literal("pdraw")
                .executes(::rootUsage)
                .then(ParticleDrawParticleCommands.tree())
                .then(animation)
                .then(Commands.literal("texture")
                    .then(Commands.literal("reload").executes(::reloadTextures)))
                .then(Commands.literal("light").executes(::lightProbe))
        )
    }

    /**
     * /pdraw 的执行逻辑。
     *
     * @param ctx 命令上下文
     * @return 命令执行结果
     */
    private fun rootUsage(ctx: CommandContext<CommandSourceStack>): Int {
        ctx.source.sendSuccess(
            { translation("command.root.usage").component() },
            false,
        )
        return 1
    }

    /**
     * 诊断：把动态光场在本地的实际数值念出来。
     *
     * 排查「画面没亮」时先看这里的数字：源数量、版本号、以及玩家脚下与身前的光照等级，
     * 就能分清是光源没被收集、还是光图没写进去。
     */
    private fun lightProbe(ctx: CommandContext<CommandSourceStack>): Int {
        if (ctx.source.server.isDedicatedServer) {
            ctx.source.sendFailure(translation("error.light.singleplayer").component())
            return 0
        }
        // 定时函数是以服务端身份跑的，这里不能用 playerOrException；按命令源坐标取样即可。
        val enabled = DynamicLightManager.isEnabled()
        val count = DynamicLightManager.sourceCount()
        val version = DynamicLightManager.version()
        val pos = ctx.source.position
        val x = pos.x
        val y = pos.y
        val z = pos.z
        val here = DynamicLightManager.getDynamicLightLevel(x, y, z)
        val below = DynamicLightManager.getDynamicLightLevel(x, y - 1.0, z)
        val above = DynamicLightManager.getDynamicLightLevel(x, y + 2.0, z)
        val first = DynamicLightManager.lightSnapshot().firstOrNull()
        val source = if (first == null) translation("diagnostic.light.no_source") else translation(
            "diagnostic.light.source",
            "%.1f, %.1f, %.1f".format(Locale.ROOT, first.x, first.y, first.z),
            "%.2f".format(Locale.ROOT, first.intensity), "%.1f".format(Locale.ROOT, first.range),
        )
        val line = translation(
            "command.light.status", enabled, count, version, "%.2f".format(Locale.ROOT, here),
            "%.2f".format(Locale.ROOT, below), "%.2f".format(Locale.ROOT, above),
            DynamicLightManager.diagnosticsText(), source,
        )
        org.apache.logging.log4j.LogManager.getLogger("ParticleDrawing").info("[PD-light] {}", line.resolve())
        ctx.source.sendSuccess({ line.component() }, false)
        return 1
    }

    private fun animationUsage(ctx: CommandContext<CommandSourceStack>): Int {
        ctx.source.sendSuccess(
            { translation("command.animation.usage").component() },
            false,
        )
        return 1
    }

    /**
     * /pdraw animation list：列出可用的动画。
     */
    private fun listAnimations(ctx: CommandContext<CommandSourceStack>): Int {
        val names = AnimationLoader.list()
        val msg = if (names.isEmpty()) {
            translation("command.animation.empty")
        } else {
            translation("command.animation.list", names.joinToString(", "))
        }
        ctx.source.sendSuccess({ msg.component() }, false)
        return names.size
    }

    /** /pdraw animation play <name> 的参数补全：animations/ 下可播放的 .pdrawc 名称。 */
    private fun suggestAnimations(
        ctx: CommandContext<CommandSourceStack>,
        builder: SuggestionsBuilder,
    ): CompletableFuture<Suggestions> =
        SharedSuggestionProvider.suggest(AnimationLoader.list(), builder)

    /**
     * /pdraw animation play <name> [pos]：播放动画（客户端本地播放）。
     * pos 省略时取执行者面前 3 格，支持绝对坐标与 ~ 相对坐标（相对命令执行者）；
     * 命令方块没有执行者，必须显式给出 pos。
     */
    private fun playAnimation(ctx: CommandContext<CommandSourceStack>): Int {
        val name = StringArgumentType.getString(ctx, "name")
        val data = AnimationLoader.load(name)
            ?: run {
                val reason = if (AnimationLoader.has(name)) translation("error.animation.signature", name)
                    else translation("error.animation.not_found", name)
                ctx.source.sendFailure(reason.component())
                return 0
            }

        val level = ctx.source.level
        val dim = ParticleUtils.dimensionUUID(level)
        val origin: Vec3 = try {
            Vec3Argument.getVec3(ctx, "pos")
        } catch (_: IllegalArgumentException) {
            // 命令方块、告示牌一类非玩家执行者拿不到朝向，回退不了就明确报错，别抛原始异常
            val player = ctx.source.player
            if (player == null) {
                ctx.source.sendFailure(translation("error.animation.position").component())
                return 0
            }
            player.position().add(player.lookAngle.scale(3.0))
        }

        ServerAnimationManager.play(dim, level.players(), data, origin)
        ctx.source.sendSuccess(
            { translation("command.animation.play", name).component() },
            false
        )
        return 1
    }

    /**
     * /pdraw animation stop：停止当前维度的全部动画与特效播放。
     */
    private fun stopAnimations(ctx: CommandContext<CommandSourceStack>): Int {
        val level = ctx.source.level
        val dim = ParticleUtils.dimensionUUID(level)
        ServerAnimationManager.stopAll(dim, level.players())
        // 特效与文件动画共用客户端的播放表：只停动画会留下服务端还在、客户端已消失的特效记录
        ServerEffectManager.stopAll(dim, level.players())
        ctx.source.sendSuccess({ translation("command.animation.stop").component() }, false)
        return 1
    }

    /**
     * /pdraw texture reload：清空 TextureCache 并从 textures/ 重新加载全部贴图，供贴图 PNG 在磁盘上被外部修改后热重载。
     * 贴图渲染仅在客户端，服务端执行时返回提示。
     */
    private fun reloadTextures(ctx: CommandContext<CommandSourceStack>): Int {
        if (!PlatformCompat.isClientDist) {
            ctx.source.sendFailure(translation("error.texture.client_only").component())
            return 0
        }
        ClientAnimationManager.reloadTextures()
        ctx.source.sendSuccess(
            { translation("command.texture.reload").component() },
            false
        )
        return 1
    }

    /**
     * /pdraw animation camera：显示摄像机子命令用法。
     */
    private fun cameraUsage(ctx: CommandContext<CommandSourceStack>): Int {
        ctx.source.sendSuccess(
            { translation("command.camera.usage").component() },
            false
        )
        return 1
    }

    /**
     * /pdraw animation camera <name>：把玩家视角切到当前播放动画的某个摄像机（客户端本地预览）。
     * 只改客户端相机的位置、旋转与 FOV，不动玩家实体；动画播完或停止时自动退出。
     */
    private fun switchCamera(ctx: CommandContext<CommandSourceStack>): Int {
        if (!PlatformCompat.isClientDist) {
            ctx.source.sendFailure(translation("error.camera.client_only").component())
            return 0
        }
        val name = StringArgumentType.getString(ctx, "name")
        val target = ClientAnimationManager.findCamera(name)
        if (target == null) {
            ctx.source.sendFailure(translation("error.camera.not_found", name).component())
            return 0
        }
        CameraController.attach(target.animationId, target.cameraId, target.origin)
        CameraController.updatePose(target.pose)
        ctx.source.sendSuccess({ translation("command.camera.switch", target.cameraId).component() }, false)
        return 1
    }

    /**
     * /pdraw animation camera stop：退出摄像机预览，恢复正常视角。
     */
    private fun stopCamera(ctx: CommandContext<CommandSourceStack>): Int {
        if (!PlatformCompat.isClientDist) {
            ctx.source.sendFailure(translation("error.camera.client_only").component())
            return 0
        }
        CameraController.detach()
        ctx.source.sendSuccess({ translation("command.camera.stop").component() }, false)
        return 1
    }

    /** /pdraw animation camera <name> 的参数补全：所有播放中动画的摄像机 id/name。 */
    private fun suggestCameras(
        ctx: CommandContext<CommandSourceStack>,
        builder: SuggestionsBuilder,
    ): CompletableFuture<Suggestions> =
        SharedSuggestionProvider.suggest(ClientAnimationManager.listCameras(), builder)

    /**
     * /pdraw animation debug：显示所有播放中动画的调试信息，含每刻求值用时、帧数、粒子数与时间轴。
     */
    private fun debugAnimations(ctx: CommandContext<CommandSourceStack>): Int {
        val infos = ClientAnimationManager.debugInfo()
        val engine = ClientParticleEngine.instance()
        val engineParticles = engine?.activeCount() ?: 0

        if (infos.isEmpty()) {
            ctx.source.sendSuccess(
                { translation("command.animation.none_playing", engineParticles).component() },
                false
            )
            return 0
        }

        for ((animId, particleCount, currentMs, maxMs, frameCount, lastAdvanceMillis, avgAdvanceMillis) in infos) {
            val shortId = animId.toString().take(8)
            val lastMs = String.format(Locale.ROOT, "%.2f", lastAdvanceMillis)
            val avgMs = String.format(Locale.ROOT, "%.2f", avgAdvanceMillis)
            val msg = translation(
                "command.animation.debug_row", shortId, particleCount, currentMs, maxMs, frameCount, lastMs, avgMs,
            )
            ctx.source.sendSuccess({ msg.component() }, false)
        }
        ctx.source.sendSuccess(
            { translation("command.animation.debug_total", infos.size, engineParticles).component() },
            false
        )
        return infos.size
    }

    /**
     * /pdraw animation var <name> <value>：更新当前维度全部播放中动画的函数对象变量，由服务端权威下发。
     */
    private fun updateVariable(ctx: CommandContext<CommandSourceStack>): Int {
        val name = StringArgumentType.getString(ctx, "name")
        val value = StringArgumentType.getString(ctx, "value")
        val level = ctx.source.level
        val dim = ParticleUtils.dimensionUUID(level)
        ServerAnimationManager.updateVariableForDimension(dim, name, value, level.players())
        ctx.source.sendSuccess(
            { translation("command.animation.variable", name, value).component() },
            false
        )
        return 1
    }
}
