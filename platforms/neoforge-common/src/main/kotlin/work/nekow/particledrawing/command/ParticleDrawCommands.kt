package work.nekow.particledrawing.command

import work.nekow.particledrawing.platform.PlatformCompat
import com.mojang.brigadier.arguments.FloatArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.resources.Identifier
import work.nekow.particledrawing.core.network.ParticleKeyframesPayload
import net.minecraft.commands.arguments.coordinates.Vec3Argument
import net.neoforged.neoforge.network.PacketDistributor
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
import work.nekow.particledrawing.core.network.CameraPosePayload
import work.nekow.particledrawing.core.client.ClientAnimationManager
import work.nekow.particledrawing.core.client.ClientParticleEngine
import work.nekow.particledrawing.command.help.HelpBook
import work.nekow.particledrawing.command.help.HelpCommands
import work.nekow.particledrawing.util.ParticleUtils
import java.util.Locale
import java.util.concurrent.CompletableFuture
import work.nekow.particledrawing.i18n.component
import work.nekow.particledrawing.i18n.reply
import work.nekow.particledrawing.i18n.replyFailure
import work.nekow.particledrawing.i18n.failureComponent
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
                .requires(work.nekow.particledrawing.platform.PlatformCompat.gamemastersOnly())
                .executes(::animationUsage)
                .then(Commands.literal("list").executes(::listAnimations))
                .then(Commands.literal("play")
                    .then(Commands.argument("name", StringArgumentType.string())
                        .suggests(::suggestAnimations)
                        .executes(::playAnimation)
                        .then(Commands.argument("pos", Vec3Argument.vec3()).executes(::playAnimation))))
                .then(Commands.literal("stop").executes(::stopAnimations))
                .then(Commands.literal("keyframes")
                    .then(Commands.literal("play")
                        .then(Commands.argument("asset", StringArgumentType.greedyString())
                            .executes { ctx ->
                                val name = StringArgumentType.getString(ctx, "asset")
                                val asset = Identifier.tryParse(name)
                                if (asset == null || name.length > 240 || asset.path.split('/').any { it.isEmpty() || it == "." || it == ".." }) {
                                    ctx.source.replyFailure(translation("error.resource.invalid", "particle_keyframes", name).component())
                                    return@executes 0
                                }
                                val pos = ctx.source.position
                                val payload = ParticleKeyframesPayload(
                                    asset, pos.x, pos.y, pos.z, ctx.source.rotation.y,
                                )
                                for (player in ctx.source.level.players()) PacketDistributor.sendToPlayer(player, payload)
                                1
                            }))
                    .then(Commands.literal("stop").executes { ctx ->
                        val payload = ParticleKeyframesPayload(null)
                        for (player in ctx.source.level.players()) PacketDistributor.sendToPlayer(player, payload)
                        1
                    }))
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
                .executes { HelpBook.open(it.source, 1) }
                .then(ParticleDrawParticleCommands.tree())
                .then(Commands.literal("help")
                    .executes { HelpBook.open(it.source, 1) }
                    .then(Commands.argument("args", StringArgumentType.greedyString())
                        .suggests(::suggestHelpArgs)
                        .executes { ctx -> openHelp(ctx, StringArgumentType.getString(ctx, "args")) }))
                .then(animation)
                .then(Commands.literal("texture")
                    .then(Commands.literal("reload").executes(::reloadTextures)))
                .then(Commands.literal("light").executes(::lightProbe))
                .then(Commands.literal("camera")
                    .executes(::freeCameraUsage)
                    .then(Commands.literal("stop").executes(::stopFreeCamera))
                    .then(Commands.literal("pos")
                        .then(Commands.argument("pos", Vec3Argument.vec3())
                            .then(Commands.argument("yaw", FloatArgumentType.floatArg(-180.0f, 180.0f))
                                .then(Commands.argument("pitch", FloatArgumentType.floatArg(-90.0f, 90.0f))
                                    .executes { ctx -> applyFreeCameraPos(ctx, 70.0f, 12.0f) }
                                    .then(Commands.argument("fov", FloatArgumentType.floatArg(1.0f, 179.0f))
                                        .executes { ctx -> applyFreeCameraPos(ctx, FloatArgumentType.getFloat(ctx, "fov"), 12.0f) }
                                        .then(Commands.argument("smooth", FloatArgumentType.floatArg(0.0f, 60.0f))
                                            .executes { ctx ->
                                                applyFreeCameraPos(
                                                    ctx,
                                                    FloatArgumentType.getFloat(ctx, "fov"),
                                                    FloatArgumentType.getFloat(ctx, "smooth"),
                                                )
                                            }))))))
                    .then(Commands.literal("look")
                        .then(Commands.argument("pos", Vec3Argument.vec3())
                            .then(Commands.argument("target", Vec3Argument.vec3())
                                .executes { ctx -> applyFreeCameraLook(ctx, 70.0f, 12.0f) }
                                .then(Commands.argument("fov", FloatArgumentType.floatArg(1.0f, 179.0f))
                                    .executes { ctx -> applyFreeCameraLook(ctx, FloatArgumentType.getFloat(ctx, "fov"), 12.0f) }
                                    .then(Commands.argument("smooth", FloatArgumentType.floatArg(0.0f, 60.0f))
                                        .executes { ctx ->
                                            applyFreeCameraLook(
                                                ctx,
                                                FloatArgumentType.getFloat(ctx, "fov"),
                                                FloatArgumentType.getFloat(ctx, "smooth"),
                                            )
                                        }))))))
        )
    }

    /**
     * /pdraw 的执行逻辑。
     *
     * @param ctx 命令上下文
     * @return 命令执行结果
     */
    private fun openHelp(ctx: CommandContext<CommandSourceStack>, raw: String): Int {
        val parts = raw.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
        if (parts.isEmpty()) return HelpBook.open(ctx.source, 1)
        return when (parts[0].lowercase()) {
            "page" -> HelpBook.open(ctx.source, parts.getOrNull(1)?.toIntOrNull() ?: 1)
            "cmd" -> HelpBook.command(ctx.source, parts.drop(1).joinToString(" "))
            "examples" -> HelpBook.examples(ctx.source)
            else -> HelpBook.topic(ctx.source, parts[0])
        }
    }

    private fun suggestHelpArgs(
        ctx: CommandContext<CommandSourceStack>, builder: SuggestionsBuilder,
    ): CompletableFuture<Suggestions> {
        val raw = try { StringArgumentType.getString(ctx, "args") } catch (_: IllegalArgumentException) { "" }
        val prefix = raw.substringAfterLast(' ')
        val before = raw.substring(0, raw.length - prefix.length)
        val options = when {
            before.isBlank() -> listOf("page", "cmd", "examples") + HelpBook.ids()
            before.trim() == "cmd" -> HelpCommands.ids()
            else -> HelpBook.ids()
        }
        return SharedSuggestionProvider.suggest(
            options.filter { it.startsWith(prefix, ignoreCase = true) },
            builder.createOffset(builder.input.lastIndexOf(' ') + 1),
        )
    }

    /**
     * 诊断：把动态光场在本地的实际数值念出来。
     *
     * 排查「画面没亮」时先看这里的数字：源数量、版本号、以及玩家脚下与身前的光照等级，
     * 就能分清是光源没被收集、还是光图没写进去。
     */
    private fun lightProbe(ctx: CommandContext<CommandSourceStack>): Int {
        if (ctx.source.server.isDedicatedServer) {
            ctx.source.replyFailure(translation("error.light.singleplayer").component())
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
        ctx.source.reply({ line.component() })
        return 1
    }

    private fun animationUsage(ctx: CommandContext<CommandSourceStack>): Int {
        ctx.source.reply(translation("command.animation.usage").component())
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
        ctx.source.reply({ msg.component() })
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
        val data = try {
            AnimationLoader.load(name)
        } catch (error: Exception) {
            ctx.source.replyFailure(error.failureComponent("error.animation.play"))
            return 0
        } ?: run {
            val reason = if (AnimationLoader.has(name)) translation("error.animation.signature", name)
                else translation("error.animation.not_found", name)
            ctx.source.replyFailure(reason.component())
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
                ctx.source.replyFailure(translation("error.animation.position").component())
                return 0
            }
            player.position().add(player.lookAngle.scale(3.0))
        }

        ServerAnimationManager.play(dim, level.players(), data, origin)
        ctx.source.reply({ translation("command.animation.play", name).component() })
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
        ctx.source.reply({ translation("command.animation.stop").component() })
        return 1
    }

    /**
     * /pdraw texture reload：清空 TextureCache 并从 textures/ 重新加载全部贴图，供贴图 PNG 在磁盘上被外部修改后热重载。
     * 贴图渲染仅在客户端，服务端执行时返回提示。
     */
    private fun reloadTextures(ctx: CommandContext<CommandSourceStack>): Int {
        if (!PlatformCompat.isClientDist) {
            ctx.source.replyFailure(translation("error.texture.client_only").component())
            return 0
        }
        ClientAnimationManager.reloadTextures()
        ctx.source.reply({ translation("command.texture.reload").component() })
        return 1
    }

    /**
     * /pdraw animation camera：显示摄像机子命令用法。
     */
    private fun cameraUsage(ctx: CommandContext<CommandSourceStack>): Int {
        ctx.source.reply({ translation("command.camera.usage").component() })
        return 1
    }

    /**
     * /pdraw animation camera <name>：把玩家视角切到当前播放动画的某个摄像机（客户端本地预览）。
     * 只改客户端相机的位置、旋转与 FOV，不动玩家实体；动画播完或停止时自动退出。
     */
    private fun switchCamera(ctx: CommandContext<CommandSourceStack>): Int {
        if (!PlatformCompat.isClientDist) {
            ctx.source.replyFailure(translation("error.camera.client_only").component())
            return 0
        }
        if (!PlatformCompat.supportsCameraOverride) {
            ctx.source.replyFailure(translation("error.camera.unsupported").component())
            return 0
        }
        val name = StringArgumentType.getString(ctx, "name")
        val target = ClientAnimationManager.findCamera(name)
        if (target == null) {
            ctx.source.replyFailure(translation("error.camera.not_found", name).component())
            return 0
        }
        CameraController.attach(target.animationId, target.cameraId, target.origin)
        CameraController.updatePose(target.pose)
        ctx.source.reply({ translation("command.camera.switch", target.cameraId).component() })
        return 1
    }

    /**
     * /pdraw animation camera stop：退出摄像机预览，恢复正常视角。
     */
    private fun stopCamera(ctx: CommandContext<CommandSourceStack>): Int {
        if (!PlatformCompat.isClientDist) {
            ctx.source.replyFailure(translation("error.camera.client_only").component())
            return 0
        }
        CameraController.detach()
        ctx.source.reply({ translation("command.camera.stop").component() })
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
            ctx.source.reply({ translation("command.animation.none_playing", engineParticles).component() })
            return 0
        }

        for ((animId, particleCount, currentMs, maxMs, frameCount, lastAdvanceMillis, avgAdvanceMillis) in infos) {
            val shortId = animId.toString().take(8)
            val lastMs = String.format(Locale.ROOT, "%.2f", lastAdvanceMillis)
            val avgMs = String.format(Locale.ROOT, "%.2f", avgAdvanceMillis)
            val msg = translation(
                "command.animation.debug_row", shortId, particleCount, currentMs, maxMs, frameCount, lastMs, avgMs,
            )
            ctx.source.reply({ msg.component() })
        }
        ctx.source.reply({ translation("command.animation.debug_total", infos.size, engineParticles).component() })
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
        ctx.source.reply({ translation("command.animation.variable", name, value).component() })
        return 1
    }

    /** /pdraw camera：显示自由镜头用法。 */
    private fun freeCameraUsage(ctx: CommandContext<CommandSourceStack>): Int {
        ctx.source.reply({ translation("command.free_camera.usage").component() })
        return 1
    }

    /** /pdraw camera stop：恢复玩家视角。 */
    private fun stopFreeCamera(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.player ?: return freeCameraFailure(ctx)
        PacketDistributor.sendToPlayer(player, CameraPosePayload.stop())
        ctx.source.reply({ translation("command.free_camera.stop").component() })
        return 1
    }

    /** /pdraw camera pos：绝对位置 + 偏航/俯仰角（MC 玩家视角口径）。 */
    private fun applyFreeCameraPos(ctx: CommandContext<CommandSourceStack>, fov: Float, smooth: Float): Int {
        val player = ctx.source.player ?: return freeCameraFailure(ctx)
        val pos = Vec3Argument.getVec3(ctx, "pos")
        val yaw = FloatArgumentType.getFloat(ctx, "yaw")
        val pitch = FloatArgumentType.getFloat(ctx, "pitch")
        sendFreeCamera(ctx, player, pos.x, pos.y, pos.z, yaw, pitch, fov, smooth)
        return 1
    }

    /** /pdraw camera look：位置 + 目标点，反算偏航/俯仰角。 */
    private fun applyFreeCameraLook(ctx: CommandContext<CommandSourceStack>, fov: Float, smooth: Float): Int {
        val player = ctx.source.player ?: return freeCameraFailure(ctx)
        val pos = Vec3Argument.getVec3(ctx, "pos")
        val target = Vec3Argument.getVec3(ctx, "target")
        val dx = target.x - pos.x
        val dy = target.y - pos.y
        val dz = target.z - pos.z
        val length = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
        if (length < 1.0E-4) {
            ctx.source.replyFailure(translation("error.free_camera.direction").component())
            return 0
        }
        val yaw = Math.toDegrees(kotlin.math.atan2(-dx, dz)).toFloat()
        val pitch = Math.toDegrees(-kotlin.math.asin((dy / length).coerceIn(-1.0, 1.0))).toFloat()
        sendFreeCamera(ctx, player, pos.x, pos.y, pos.z, yaw, pitch, fov, smooth)
        return 1
    }

    private fun sendFreeCamera(
        ctx: CommandContext<CommandSourceStack>,
        player: net.minecraft.server.level.ServerPlayer,
        x: Double, y: Double, z: Double,
        yaw: Float, pitch: Float, fov: Float, smooth: Float,
    ) {
        PacketDistributor.sendToPlayer(player, CameraPosePayload(true, x, y, z, yaw, pitch, fov, smooth))
        ctx.source.reply({ translation("command.free_camera.set").component() })
    }

    private fun freeCameraFailure(ctx: CommandContext<CommandSourceStack>): Int {
        ctx.source.replyFailure(translation("error.free_camera.player").component())
        return 0
    }
}
