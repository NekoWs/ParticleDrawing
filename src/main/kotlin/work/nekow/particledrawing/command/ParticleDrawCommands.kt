package work.nekow.particledrawing.command

import com.mojang.brigadier.arguments.FloatArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.context.CommandContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.Vec3
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.RegisterCommandsEvent
import work.nekow.particledrawing.ParticleDrawing
import work.nekow.particledrawing.api.*
import work.nekow.particledrawing.core.easing.EasingType
import work.nekow.particledrawing.core.motion.algorithms.SwirlAlgorithm
import work.nekow.particledrawing.core.motion.algorithms.VortexAlgorithm
import work.nekow.particledrawing.core.server.ServerParticleEngine
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * ParticleDraw 命令注册与演示系统。
 *
 * 架构概览:
 * - ParticleManager —— 入口门面，每个维度一个实例
 *   - .create() → ParticleHandle.Builder → .spawn() (单粒子: style / position / color / scale / lifetime)
 *   - .createGroup(pivot) → ParticleGroup (粒子组: move / rotate / recolor / remove)
 *   - Draw.line/circle/disc/curve(...) → ParticleGroup (快捷绘图，自动创建粒子并加入组)
 * - EasingType —— 缓动曲线 (LINEAR / EASE_IN / EASE_OUT / EASE_IN_OUT / custom(x1,y1,x2,y2))
 * - ParticleGroup —— 组操作
 *   - g.rotate(axis, radians, durationTicks, easing)
 *   - g.move(delta, durationTicks, easing)
 *   - g.recolor(color, durationTicks, easing)
 *   - durationTicks=0 → 瞬移（无动画）
 *   - axis: Vec3(0,1,0)=Y轴, Vec3(1,0,0)=X轴, Vec3(0,0,1)=Z轴
 * - ServerParticleEngine —— 底层引擎
 *   - engine.update(id) → UpdateBuilder (.position / .color / .scale / .easing / .send)
 *   - engine.destroyGroup(id, players) / engine.clearAll(players)
 *
 * 数据流: 服务端计算 → 网络发送 → 客户端缓动插值 → 渲染
 */
@EventBusSubscriber(modid = ParticleDrawing.MODID)
@Suppress("unused")
object ParticleDrawCommands {

    /**
     * 注册 /particledraw 命令及其所有子命令。
     * @param event 命令注册事件
     */
    @SubscribeEvent
    @JvmStatic
    fun onRegisterCommands(event: RegisterCommandsEvent) {
        val dispatcher = event.dispatcher

        dispatcher.register(
            Commands.literal("particledraw")
                .then(Commands.literal("line")
                    .then(Commands.argument("count", IntegerArgumentType.integer(1, 5000))
                        .executes { ctx ->
                            spawnLine(ctx, IntegerArgumentType.getInteger(ctx, "count"))
                        }))
                .then(Commands.literal("circle")
                    .then(Commands.argument("radius", FloatArgumentType.floatArg(0.5f, 50f))
                        .then(Commands.argument("count", IntegerArgumentType.integer(4, 10000))
                            .executes { ctx ->
                                spawnCircle(
                                    ctx,
                                    FloatArgumentType.getFloat(ctx, "radius"),
                                    IntegerArgumentType.getInteger(ctx, "count")
                                )
                            })))
                .then(Commands.literal("disc")
                    .then(Commands.argument("radius", FloatArgumentType.floatArg(0.5f, 30f))
                        .then(Commands.argument("count", IntegerArgumentType.integer(4, 5000))
                            .executes { ctx ->
                                spawnDisc(
                                    ctx,
                                    FloatArgumentType.getFloat(ctx, "radius"),
                                    IntegerArgumentType.getInteger(ctx, "count")
                                )
                            })))
                .then(Commands.literal("glow")
                    .then(Commands.argument("count", IntegerArgumentType.integer(1, 500))
                        .executes { ctx ->
                            spawnGlow(ctx, IntegerArgumentType.getInteger(ctx, "count"))
                        }))
                .then(Commands.literal("stress")
                    .then(Commands.argument("count", IntegerArgumentType.integer(100, 50000))
                        .executes { ctx ->
                            stressTest(ctx, IntegerArgumentType.getInteger(ctx, "count"))
                        }))
                .then(Commands.literal("group")
                    .executes(::spawnGroupTest)
                    .then(Commands.literal("rotate")
                        .executes(::rotateGroup))
                    .then(Commands.literal("move")
                        .executes(::moveGroup))
                    .then(Commands.literal("recolor")
                        .executes(::recolorGroup)))
                .then(Commands.literal("status")
                    .executes(::showStatus))
                .then(Commands.literal("styles")
                    .executes(::showAllStyles))
                .then(Commands.literal("demo")
                    .executes(::startDemo)
                    .then(Commands.literal("wave")
                        .executes(::startWaveDemo))
                    .then(Commands.literal("rain")
                        .executes(::startRainDemo))
                    .then(Commands.literal("sphere")
                        .executes(::startSphereDemo))
                    .then(Commands.literal("magic")
                        .executes(::startMagicCircleDemo))
                    .then(Commands.literal("matrix")
                        .executes(::startMatrixDemo))
                    .then(Commands.literal("tornado")
                        .executes(::startTornadoDemo))
                    .then(Commands.literal("vortex")
                        .executes(::startVortexDemo))
                    .then(Commands.literal("heart")
                        .executes(::startHeartDemo))
                    .then(Commands.literal("helix")
                        .executes(::startHelixDemo))
                    .then(Commands.literal("spiral")
                        .executes(::startSpiralDemo))
                    .then(Commands.literal("shockwave")
                        .executes(::startShockwaveDemo)))
                .then(Commands.literal("clear")
                    .executes(::clearAll))
        )
    }

    /**
     * 生成一条彩色线段粒子。
     * @param ctx 命令上下文
     * @param count 粒子数量
     * @return 实际生成的粒子数
     */
    private fun spawnLine(ctx: CommandContext<CommandSourceStack>, count: Int): Int {
        val player = ctx.source.playerOrException
        val level = player.level()
        val pm = ParticleManager.of(level)

        val start = player.position().add(player.lookAngle.scale(3.0))
        val end = start.add(player.lookAngle.scale(10.0))
        val colors = arrayOf(Color.RED, Color.ORANGE, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA)

        for (i in 0 until count) {
            val t = i.toDouble() / maxOf(1, count - 1).toDouble()
            pm.create()
                .style(ParticleStyle.DUST)
                .position(start.add(end.subtract(start).scale(t)))
                .color(colors[i % colors.size])
                .scale(0.4f)
                .lifetime(600)
                .spawn()
        }

        ctx.source.sendSuccess(
            { Component.literal("Spawned $count particles in a line") }, false)
        return count
    }

    /**
     * 生成一个 XZ 平面上的圆形粒子环。
     * @param ctx 命令上下文
     * @param radius 半径
     * @param count 粒子数量
     * @return 实际生成的粒子数
     */
    private fun spawnCircle(ctx: CommandContext<CommandSourceStack>, radius: Float, count: Int): Int {
        val player = ctx.source.playerOrException
        val level = player.level()
        val pm = ParticleManager.of(level)

        val center = player.position().add(player.lookAngle.scale(3.0))
        Draw.circle(pm, center, radius.toDouble(), count, Draw.Axis.XZ,
            Color.CYAN, ParticleStyle.DUST, 0.4f)

        ctx.source.sendSuccess(
            { Component.literal("Spawned circle: $count particles, radius=$radius") }, false)
        return count
    }

    /**
     * 生成 XZ 平面上的填充圆盘粒子。
     * @param ctx 命令上下文
     * @param radius 半径
     * @param count 粒子总数
     * @return 实际生成的粒子数
     */
    private fun spawnDisc(ctx: CommandContext<CommandSourceStack>, radius: Float, count: Int): Int {
        val player = ctx.source.playerOrException
        val level = player.level()
        val pm = ParticleManager.of(level)

        val center = player.position().add(player.lookAngle.scale(3.0))
        val layers = maxOf(1, (radius * 2).toInt())
        Draw.disc(pm, center, radius.toDouble(), count, layers, Draw.Axis.XZ,
            Color.ofHsb(0.55f, 0.8f, 1.0f), ParticleStyle.DUST, 0.3f)

        ctx.source.sendSuccess(
            { Component.literal("Spawned disc with radius=$radius") }, false)
        return count
    }

    /**
     * 生成一圈彩虹色发光粒子。
     * @param ctx 命令上下文
     * @param count 粒子数量
     * @return 实际生成的粒子数
     */
    private fun spawnGlow(ctx: CommandContext<CommandSourceStack>, count: Int): Int {
        val player = ctx.source.playerOrException
        val level = player.level()
        val pm = ParticleManager.of(level)

        val center = player.position().add(player.lookAngle.scale(3.0))
        for (i in 0 until count) {
            val angle = 2.0 * PI * i / count
            val x = center.x + cos(angle) * 2.5
            val z = center.z + sin(angle) * 2.5
            val hue = i.toFloat() / count

            pm.create()
                .style(ParticleStyle.GLOW)
                .position(x, center.y + 1.5, z)
                .color(Color.ofHsb(hue, 1.0f, 1.0f))
                .scale(1.2f)
                .lifetime(1200)
                .glowing(true)
                .spawn()
        }

        ctx.source.sendSuccess(
            { Component.literal("Spawned $count GLOWING particles!") }, false)
        return count
    }

    /**
     * 压力测试: 分批生成大量粒子。
     * @param ctx 命令上下文
     * @param count 粒子总数
     * @return 引擎中的当前粒子总数
     */
    private fun stressTest(ctx: CommandContext<CommandSourceStack>, count: Int): Int {
        val player = ctx.source.playerOrException
        val level = player.level()
        val pm = ParticleManager.of(level)

        val center = player.position().add(player.lookAngle.scale(5.0))
        val startTime = System.currentTimeMillis()

        val batchSize = 500
        var batch = 0
        while (batch < count) {
            val toSpawn = minOf(batchSize, count - batch)
            for (i in 0 until toSpawn) {
                val angle = (batch + i).toDouble() / count * 2.0 * PI
                val dist = 2.0 + Math.random() * 8.0
                val x = center.x + cos(angle) * dist
                val z = center.z + sin(angle) * dist
                val y = center.y + Math.random() * 4.0
                val hue = (batch + i).toFloat() / count

                pm.create()
                    .style(ParticleStyle.DUST)
                    .position(x, y, z)
                    .color(Color.ofHsb(hue, 0.9f, 1.0f))
                    .scale(0.25f)
                    .lifetime(400)
                    .spawn()
            }
            batch += batchSize
        }

        val elapsed = System.currentTimeMillis() - startTime
        val total = pm.getEngine().particleCount()

        ctx.source.sendSuccess(
            { Component.literal("Stress test: $total particles in ${elapsed}ms") }, false)
        return total
    }

    /** 当前测试粒子组，供 rotate/move/recolor 命令操作 */
    private var testGroup: ParticleGroup? = null

    /**
     * 生成一个测试用粒子组（红色圆形）。
     * @param ctx 命令上下文
     * @return 组中粒子数量
     */
    private fun spawnGroupTest(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException
        val level = player.level()
        val pm = ParticleManager.of(level)

        val center = player.position().add(player.lookAngle.scale(4.0))
        testGroup = Draw.circle(pm, center, 3.0, 36, Draw.Axis.XZ,
            Color.RED, ParticleStyle.DUST, 0.5f)

        ctx.source.sendSuccess(
            { Component.literal("Test group: ${testGroup!!.size()} particles. /particledraw group rotate|move|recolor") }, false)
        return testGroup!!.size()
    }

    /**
     * 旋转测试粒子组 360 度。
     * @param ctx 命令上下文
     * @return 1 成功, 0 无测试组
     */
    private fun rotateGroup(ctx: CommandContext<CommandSourceStack>): Int {
        if (testGroup == null) {
            ctx.source.sendFailure(Component.literal("No test group! Run /particledraw group first."))
            return 0
        }
        testGroup!!.rotate(Vec3(0.0, 1.0, 0.0), PI * 2, 80, EasingType.EASE_IN_OUT)
        ctx.source.sendSuccess(
            { Component.literal("Rotating group 360 deg over 80 ticks") }, false)
        return 1
    }

    /**
     * 将测试粒子组向上移动 2 格（弹跳缓动）。
     * @param ctx 命令上下文
     * @return 1 成功, 0 无测试组
     */
    private fun moveGroup(ctx: CommandContext<CommandSourceStack>): Int {
        if (testGroup == null) {
            ctx.source.sendFailure(Component.literal("No test group! Run /particledraw group first."))
            return 0
        }
        testGroup!!.move(Vec3(0.0, 2.0, 0.0), 60, EasingType.EASE_OUT_BOUNCE)
        ctx.source.sendSuccess(
            { Component.literal("Moving group up by 2 blocks (bounce easing)") }, false)
        return 1
    }

    /**
     * 将测试粒子组重新着色为蓝色。
     * @param ctx 命令上下文
     * @return 1 成功, 0 无测试组
     */
    private fun recolorGroup(ctx: CommandContext<CommandSourceStack>): Int {
        if (testGroup == null) {
            ctx.source.sendFailure(Component.literal("No test group! Run /particledraw group first."))
            return 0
        }
        testGroup!!.recolor(Color.BLUE, 40, EasingType.EASE_IN_OUT)
        ctx.source.sendSuccess(
            { Component.literal("Recoloring group to BLUE") }, false)
        return 1
    }

    /**
     * 显示服务端与客户端粒子数量状态。
     * @param ctx 命令上下文
     * @return 服务端粒子总数
     */
    private fun showStatus(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException
        val level = player.level()
        val engine = ServerParticleEngine.getOrCreate(
            work.nekow.particledrawing.util.ParticleUtils.dimensionUUID(level))
        val serverCount = engine.particleCount()
        val serverGroups = engine.groupCount()

        var clientCount = 0
        val clientEngine = work.nekow.particledrawing.core.client.ClientParticleEngine.instance()
        if (clientEngine != null) {
            clientCount = clientEngine.activeCount()
        }

        ctx.source.sendSuccess(
            { Component.literal(
                "Server: $serverCount particles, $serverGroups groups | "
                        + "Client: $clientCount particles") }, false)
        return serverCount
    }

    /**
     * 在面前排列显示所有粒子样式。
     */
    private fun showAllStyles(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException
        val level = player.level()
        val pm = ParticleManager.of(level)

        val styles = ParticleStyle.entries.toTypedArray()
        val base = player.position().add(player.lookAngle.scale(4.0))
        val right = player.lookAngle.cross(Vec3(0.0, 1.0, 0.0)).normalize()
        val cols = 5
        val spacing = 2.0

        for ((i, style) in styles.withIndex()) {
            val row = i / cols
            val col = i % cols
            val pos = base.add(right.scale(col * spacing)).add(0.0, -row * spacing, 0.0)

            pm.create()
                .style(style)
                .position(pos)
                .color(if (style.supportsColor) Color.ofHsb(i.toFloat() / styles.size, 0.9f, 0.9f) else Color.WHITE)
                .scale(0.5f)
                .lifetime(600)
                .spawn()
        }

        ctx.source.sendSuccess(
            { Component.literal("Showing ${styles.size} particle styles ahead") }, false)
        return styles.size
    }

    /**
     * 演示运行状态。
     * 记录每个演示的粒子组、管理器、动画类型和原点。
     * tickCounter 由 [tickDemos] 每 tick 递增，用于波动画的时间变量。
     */
    data class DemoState(
        val group: ParticleGroup,
        val manager: ParticleManager,
        val type: DemoType,
        val origin: Vec3,
        var tickCounter: Int = 0,
        val extra: List<Double> = emptyList()
    )

    /** 演示类型枚举 */
    enum class DemoType { CIRCLE, WAVE, RAIN, SPHERE, MAGIC_CIRCLE, MATRIX, TORNADO, VORTEX, HEART, HELIX, SPIRAL, SHOCKWAVE }

    /** 当前活跃的演示状态列表 */
    @JvmField
    var demoStates: MutableList<DemoState> = mutableListOf()

    /**
     * 由 [work.nekow.particledrawing.core.server.ServerParticleHandler.onServerTick] 每 tick 调用。
     * 根据 DemoType 推进动画: 旋转组、更新粒子 Y 坐标等。
     */
    @JvmStatic
    fun tickDemos() {
        val it = demoStates.iterator()
        while (it.hasNext()) {
            val state = it.next()
            val g = state.group
            state.tickCounter++
            try {
                when (state.type) {
                    DemoType.CIRCLE -> {
                        g.rotate(
                            Vec3(0.0, 1.0, 0.0),
                            Math.toRadians(3.0),
                            0,
                            EasingType.LINEAR
                        )
                    }

                    DemoType.WAVE -> {
                        val tick = state.tickCounter.toDouble()
                        val engine = state.manager.getEngine()
                        val groupData = engine.getGroup(g.id) ?: continue
                        val players = state.manager.getPlayers()

                        var idx = 0
                        for (memberId in groupData.memberIds()) {
                            val data = engine.getParticle(memberId) ?: continue
                            val angle = 2.0 * PI * idx / groupData.size()
                            val newY = state.origin.y + sin(tick * 0.5 + angle * 4.0) * 0.5

                            engine.update(memberId)
                                .position(data.position().x, newY, data.position().z)
                                .easing(EasingType.EASE_IN_OUT, 2)
                                .send(players)
                            idx++
                        }
                    }

                    DemoType.RAIN -> {
                        val engine = state.manager.getEngine()
                        val groupData = engine.getGroup(g.id) ?: continue
                        val players = state.manager.getPlayers()

                        for (memberId in groupData.memberIds()) {
                            val data = engine.getParticle(memberId) ?: continue
                            val newY = data.position().y - 0.15

                            if (newY < state.origin.y - 3.5) {
                                val angle = Math.random() * 2.0 * PI
                                val dist = Math.random() * 3.0
                                engine.update(memberId)
                                    .position(
                                        state.origin.x + cos(angle) * dist,
                                        state.origin.y + Math.random() * 2.5,
                                        state.origin.z + sin(angle) * dist
                                    )
                                    .send(players)
                            } else {
                                engine.update(memberId)
                                    .position(data.position().x, newY, data.position().z)
                                    .easing(EasingType.EASE_IN_OUT, 2)
                                    .send(players)
                            }
                        }
                    }

                    DemoType.SPHERE -> {
                        // 旋转由客户端持续旋转系统处理，无需服务端更新
                    }
                    DemoType.MAGIC_CIRCLE -> {
                        // 运动由 MotionSystem 客户端预测处理
                    }
                    DemoType.MATRIX -> {
                        // 运动由 MotionSystem 客户端预测处理
                    }
                    DemoType.TORNADO -> {
                        // 扭转由 SwirlAlgorithm 客户端帧级计算
                    }

                    DemoType.VORTEX -> {
                        // 涡旋由 VortexAlgorithm 客户端帧级计算
                    }

                    DemoType.HEART -> {
                        // extra[0] 角色: 1=外轮廓心跳, 2=内轮廓心跳, 3=粒子雨, 4=星光闪烁
                        val role = state.extra.firstOrNull() ?: 0.0
                        val tick = state.tickCounter.toDouble()
                        val engine = state.manager.getEngine()
                        val groupData = engine.getGroup(g.id) ?: continue
                        val players = state.manager.getPlayers()

                        when (role.toInt()) {
                            1, 2 -> {
                                val base = state.extra.getOrElse(1) { 0.3 }
                                val scale = (base * (1.0 + 0.1 * sin(tick * 0.31))).toFloat()
                                for (memberId in groupData.memberIds()) {
                                    engine.update(memberId)
                                        .scale(scale)
                                        .easing(EasingType.EASE_IN_OUT, 3)
                                        .send(players)
                                }
                            }

                            3 -> {
                                val lowY = state.origin.y - 4.5
                                val span = 8.5
                                for (memberId in groupData.memberIds()) {
                                    val data = engine.getParticle(memberId) ?: continue
                                    val newY = data.position().y + 0.18
                                    val reset = newY > state.origin.y + 4.0
                                    val targetY = if (reset) lowY else newY
                                    val fade = (1.0 - (targetY - lowY) / span).coerceIn(0.15, 1.0)

                                    engine.update(memberId)
                                        .position(data.position().x, targetY, data.position().z)
                                        .color(Color.ofHsb(0.95f, 1.0f, fade.toFloat()))
                                        .easing(if (reset) EasingType.LINEAR else EasingType.EASE_OUT, if (reset) 0 else 2)
                                        .send(players)
                                }
                            }

                            else -> {
                                val base = state.extra.getOrElse(1) { 0.35 }
                                var idx = 0
                                for (memberId in groupData.memberIds()) {
                                    val scl = (base * (0.35 + 0.65 * (0.5 + 0.5 * sin(tick * 0.6 + idx * 1.7)))).toFloat()
                                    engine.update(memberId)
                                        .scale(scl)
                                        .easing(EasingType.LINEAR, 2)
                                        .send(players)
                                    idx++
                                }
                            }
                        }
                    }

                    DemoType.HELIX -> {
                        // 旋转由 MotionSystem 客户端预测处理
                    }
                    DemoType.SPIRAL -> {
                        // 旋转与跟随由 MotionSystem 客户端预测处理
                    }

                    DemoType.SHOCKWAVE -> {
                        val tick = state.tickCounter.toDouble()
                        val engine = state.manager.getEngine()
                        val groupData = engine.getGroup(g.id) ?: continue
                        val players = state.manager.getPlayers()
                        val maxD = 7.2
                        val step = 0.25

                        for (memberId in groupData.memberIds()) {
                            val data = engine.getParticle(memberId) ?: continue
                            val dx = data.position().x - state.origin.x
                            val dz = data.position().z - state.origin.z
                            val dist = sqrt(dx * dx + dz * dz)

                            if (dist < 0.05) {
                                engine.update(memberId)
                                    .scale((0.8 + 0.6 * sin(tick * 0.5)).toFloat())
                                    .easing(EasingType.LINEAR, 2)
                                    .send(players)
                            } else {
                                val theta = atan2(dz, dx)
                                var newD = dist + step
                                if (newD > maxD) newD = 0.35
                                val t = newD / maxD

                                engine.update(memberId)
                                    .position(
                                        state.origin.x + cos(theta) * newD,
                                        data.position().y,
                                        state.origin.z + sin(theta) * newD
                                    )
                                    .color(Color.ofHsb(0.58f, 0.8f, ((1.0 - t) * 0.9 + 0.1).toFloat()))
                                    .scale((0.45 - 0.25 * t).toFloat())
                                    .easing(EasingType.LINEAR, 2)
                                    .send(players)
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                it.remove()
            }
        }
    }

    /**
     * 停止所有演示，销毁全部粒子组。
     */
    @JvmStatic
    fun stopDemos() {
        for ((group, manager) in demoStates) {
            try { manager.getEngine().destroyGroup(group.id, manager.getPlayers()) }
            catch (_: Exception) {}
        }
        demoStates.clear()
    }

    /**
     * CIRCLE 演示: XZ 平面水平圆环, 绕 Y 轴旋转。
     *
     * API 流程:
     * 1. ParticleManager.of(ServerLevel) —— 获取维度入口
     * 2. Draw.circle(manager, center, radius, count, Axis, color, style, scale)
     *    —— 在指定平面等间距排列 count 个粒子, 返回 ParticleGroup
     * 3. 将 group 存入 DemoState, 由 tickDemos() 驱动旋转
     *
     * @param ctx 命令上下文
     * @return 组中粒子数量
     */
    fun startDemo(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException
        val level = player.level()
        val pm = ParticleManager.of(level)

        val center = player.position().add(player.lookAngle.scale(4.0))
        val group = Draw.circle(pm, center, 3.0, 120, Draw.Axis.XZ,
            Color.WHITE, ParticleStyle.DUST, 0.35f)

        demoStates += DemoState(group, pm, DemoType.CIRCLE, center)
        ctx.source.sendSuccess(
            { Component.literal("Circle demo! ${group.size()} particles, XZ plane, rotating") }, false)
        return group.size()
    }

    /**
     * WAVE 演示: 80 粒子圆环, 每个粒子独立 Y 轴正弦波动 + 绕中心旋转。
     *
     * API 流程:
     * 1. Draw.circle() → 创建圆环, 返回 ParticleGroup
     * 2. tickDemos() 通过 groupData.memberIds() 遍历每个粒子
     * 3. engine.update() 只更新 Y 坐标
     *    不影响颜色和缩放
     * 4. durationTicks=2 → Y 变化 2 tick 缓动, 丝滑
     *
     * @param ctx 命令上下文
     * @return 组中粒子数量
     */
    fun startWaveDemo(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException
        val level = player.level()
        val pm = ParticleManager.of(level)

        val center = player.position().add(player.lookAngle.scale(4.0))
        val group = Draw.circle(pm, center, 2.5, 80, Draw.Axis.XZ,
            Color.ofHsb(0.55f, 1.0f, 1.0f), ParticleStyle.DUST, 0.3f)

        demoStates += DemoState(group, pm, DemoType.WAVE, center)
        ctx.source.sendSuccess(
            { Component.literal("Wave demo! ${group.size()} particles with sine wave animation") }, false)
        return group.size()
    }

    /**
     * RAIN 演示: 玩家头顶 2.5 格处 80 个蓝色粒子持续下落。
     *
     * API 流程:
     * 1. ParticleManager.createGroup(center) —— 创建空粒子组 (pivot=center)
     * 2. ParticleManager.create() (Builder 模式)
     *        .style(ParticleStyle.DUST)
     *        .position(x, y, z)        —— 必填
     *        .color(Color.ofHsb(...))  —— 可选, 默认白色
     *        .scale(0.15f)             —— 可选, 默认 1.0
     *        .lifetime(-1)             —— -1 = 永生 (不自动过期)
     *        .group(group.id)          —— 关联到组
     *        .spawn()                  —— 实际创建粒子, 返回 ParticleHandle
     * 3. group.add(handle) —— 将粒子加入组 (可用组操作统一管理)
     * 4. tickDemos() 每 tick 更新 Y:
     *    - 下落: durationTicks=2 → 平滑
     *    - 触底: durationTicks=0 → 瞬移复位, 无动画
     *
     * @param ctx 命令上下文
     * @return 组中粒子数量
     */
    fun startRainDemo(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException
        val level = player.level()
        val pm = ParticleManager.of(level)

        val center = player.position().add(0.0, 2.5, 0.0)
        val group = pm.createGroup(center)

        for (i in 0 until 80) {
            val angle = Math.random() * 2.0 * PI
            val dist = Math.random() * 3.0
            val x = center.x + cos(angle) * dist
            val y = center.y - 3.0 + Math.random() * 3.5
            val z = center.z + sin(angle) * dist

            val handle = pm.create()
                .style(ParticleStyle.DUST)
                .position(x, y, z)
                .color(Color.ofHsb(0.55f, 0.8f, 0.9f))
                .scale(0.15f)
                .lifetime(-1)
                .group(group.id)
                .spawn()
            group.add(handle)
        }

        demoStates += DemoState(group, pm, DemoType.RAIN, center)
        ctx.source.sendSuccess(
            { Component.literal("Rain demo! ${group.size()} particles, cloud with falling rain") }, false)
        return group.size()
    }

    /**
     * 球体 Demo: 半径 3 格的球面分布粒子，随旋转 RGB 渐变。
     *
     * 使用球坐标均匀分布 300 个粒子于球面。
     * 每 tick 绕 Y 轴旋转并基于时间和粒子位置循环色相。
     */
    fun startSphereDemo(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException
        val level = player.level()
        val pm = ParticleManager.of(level)

        val center = player.position().add(player.lookAngle.scale(5.0))
        val group = Draw.sphere(pm, center, 3.0, 800)
        group.rotateMotion(Math.toRadians(100.0))  // 100°/秒，不受 /tick 影响
        group.colorGradientMotion()                     // 固定表面纹理

        demoStates += DemoState(group, pm, DemoType.SPHERE, center)
        ctx.source.sendSuccess(
            { Component.literal("Sphere demo! ${group.size()} particles, X-axis rotation") }, false)
        return group.size()
    }

    /**
     * 法阵演示: 六芒星 + 内外圆 + 跟随玩家。
     * 多个组叠加，各自独立运动算法。
     */
    fun startMagicCircleDemo(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException
        val level = player.level()
        val pm = ParticleManager.of(level)
        val center = player.position()
        val radius = 2.5
        val steps = 200
        var total = 0

        // --- 六芒星 (两个三角叠加) ---
        val starGroup = Draw.hexagram(pm, center, radius, segmentsPerEdge = 40,
            color1 = Color.ofHsb(0.10f, 1.0f, 0.9f),
            color2 = Color.ofHsb(0.60f, 1.0f, 0.9f))
        starGroup.addMotion("follow_player")
        starGroup.addMotion("rotate", doubleArrayOf(0.0, 1.0, 0.0, Math.toRadians(-45.0)))
        demoStates += DemoState(starGroup, pm, DemoType.MAGIC_CIRCLE, center)
        total += starGroup.size()

        // --- 外圆 (贴六芒星顶点, 逆时针) ---
        val outerGroup = Draw.circle(pm, center, radius, steps, Draw.Axis.XZ,
            Color.ofHsb(0.85f, 1.0f, 0.8f), ParticleStyle.DUST, 0.2f)
        outerGroup.addMotion("follow_player")
        outerGroup.addMotion("rotate", doubleArrayOf(0.0, 1.0, 0.0, Math.toRadians(30.0)))  // Y轴逆时针
        demoStates += DemoState(outerGroup, pm, DemoType.MAGIC_CIRCLE, center)
        total += steps

        // --- 内圆 (贴六芒星内六边形, 逆时针) ---
        val innerR = radius * cos(PI / 6.0)
        val innerGroup = Draw.circle(pm, center, innerR, steps / 2, Draw.Axis.XZ,
            Color.ofHsb(0.85f, 1.0f, 0.8f), ParticleStyle.DUST, 0.2f)
        innerGroup.addMotion("follow_player")
        innerGroup.addMotion("rotate", doubleArrayOf(0.0, 1.0, 0.0, Math.toRadians(30.0)))  // Y轴逆时针
        demoStates += DemoState(innerGroup, pm, DemoType.MAGIC_CIRCLE, center)
        total += steps / 2

        ctx.source.sendSuccess(
            { Component.literal("Magic circle! $total particles, hexagram + circles following player") }, false)
        return total
    }

    /**
     * 粒子矩阵演示：静态立方体网格，粒子大小随玩家距离动态变化。
     * 越近粒子越大填满格子，越远越小至默认尺寸。
     */
    fun startMatrixDemo(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException
        val level = player.level()
        val pm = ParticleManager.of(level)
        val center = player.position()
        val size = 9.0
        val perAxis = 20

        val group = Draw.cuboid(pm, center, size, size, size, perAxis, hollow = false,
            color = Color.WHITE, style = ParticleStyle.DUST, scale = 0.05f)

        val spacing = size / (perAxis - 1)
        group.addMotion("scale_by_distance", doubleArrayOf(spacing * 1.3, 0.03, 10.0))
        demoStates += DemoState(group, pm, DemoType.MATRIX, center)

        val total = group.size()
        ctx.source.sendSuccess(
            { Component.literal("Matrix demo! $total particles, size $size, distance-based scale") }, false)
        return total
    }

    /**
     * 龙卷风演示: 24 条螺旋线沿漏斗轮廓上升 (底部窄、顶部喇叭口) + 底部旋转碎屑 + 发光核心。
     * 扭转由 SwirlAlgorithm 客户端帧级计算 (角速度随高度增大), 零网络开销, ~7k 粒子。
     */
    fun startTornadoDemo(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException
        val level = player.level()
        val pm = ParticleManager.of(level)

        val center = player.position().add(player.lookAngle.scale(8.0))
        val height = 9.0
        val strands = 24
        val perStrand = 250
        val group = pm.createGroup(center)
        var total = 0

        // 漏斗主体螺旋线
        for (s in 0 until strands) {
            val phi0 = s * 2.0 * PI / strands
            for (i in 0 until perStrand) {
                val t = i.toDouble() / (perStrand - 1)
                val y = center.y + t * height
                val r = 0.6 + 4.6 * t.pow(0.7)
                val ang = phi0 + t * height * 0.5
                val handle = pm.create()
                    .style(ParticleStyle.DUST)
                    .position(center.x + cos(ang) * r, y, center.z + sin(ang) * r)
                    .color(Color.ofHsb(0.58f, 0.22f, (0.45 + 0.4 * t).toFloat()))
                    .scale(0.32f)
                    .lifetime(-1)
                    .group(group.id)
                    .spawn()
                group.add(handle)
                total++
            }
        }

        // 底部碎屑
        for (i in 0 until 1200) {
            val ang = Math.random() * 2.0 * PI
            val r = 0.5 + Math.random() * 5.5
            val handle = pm.create()
                .style(ParticleStyle.DUST)
                .position(center.x + cos(ang) * r, center.y + Math.random() * 1.2, center.z + sin(ang) * r)
                .color(Color.ofHsb(0.09f, 0.3f, (0.4 + Math.random() * 0.3).toFloat()))
                .scale(0.2f)
                .lifetime(-1)
                .group(group.id)
                .spawn()
            group.add(handle)
            total++
        }

        // 发光核心
        for (i in 0 until 20) {
            val handle = pm.create()
                .style(ParticleStyle.GLOW)
                .position(center.x, center.y + i * (height / 19.0), center.z)
                .scale(0.4f)
                .lifetime(-1)
                .group(group.id)
                .glowing(true)
                .spawn()
            group.add(handle)
            total++
        }

        group.addMotion(SwirlAlgorithm.ID, doubleArrayOf(0.0, 1.0, 0.0, 0.8, 0.32))
        demoStates += DemoState(group, pm, DemoType.TORNADO, center)

        ctx.source.sendSuccess(
            { Component.literal("Tornado demo! $total particles, twisting funnel + debris") }, false)
        return total
    }

    /**
     * 涡旋演示: 填充圆盘 + 发光外环, 粒子螺旋内卷至中心后从外缘循环再生,
     * 叠加向外扩散的波纹、差分旋转与螺旋色相, 由 VortexAlgorithm 客户端帧级计算。
     */
    fun startVortexDemo(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException
        val level = player.level()
        val pm = ParticleManager.of(level)

        val center = player.position().add(0.0, 1.5, 0.0)
        val group = Draw.disc(pm, center, 5.0, 160, 14, Draw.Axis.XZ,
            Color.ofHsb(0.5f, 0.8f, 0.8f), ParticleStyle.DUST, 0.25f)
        var total = group.size()

        // 外圈发光环
        for (i in 0 until 160) {
            val angle = 2.0 * PI * i / 160
            val handle = pm.create()
                .style(ParticleStyle.GLOW)
                .position(center.x + cos(angle) * 5.2, center.y, center.z + sin(angle) * 5.2)
                .scale(0.3f)
                .lifetime(-1)
                .group(group.id)
                .glowing(true)
                .spawn()
            group.add(handle)
            total++
        }

        group.addMotion(VortexAlgorithm.ID, doubleArrayOf(1.2, 0.25, 0.6, 2.5, -3.2, 0.55, 5.5, 0.5, 0.35))
        demoStates += DemoState(group, pm, DemoType.VORTEX, center)

        ctx.source.sendSuccess(
            { Component.literal("Vortex demo! $total particles, whirlpool with expanding waves") }, false)
        return total
    }

    /**
     * 爱心演示: 静态大爱心 (8.5 格高, 正对玩家视线) —
     * 双层轮廓心跳缩放、发光粒子雨循环上升、星光闪烁, 全部由服务端 tick 驱动。
     */
    fun startHeartDemo(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException
        val level = player.level()
        val pm = ParticleManager.of(level)

        val center = player.position().add(0.0, player.eyeHeight.toDouble(), 0.0)
            .add(player.lookAngle.scale(4.5)).add(0.0, 0.5, 0.0)
        val right = player.lookAngle.cross(Vec3(0.0, 1.0, 0.0)).normalize()
        val s = 5.0 / 17.0
        var total = 0

        fun heartPoint(t: Double, scale: Double): Vec3 {
            val x = 16.0 * sin(t) * sin(t) * sin(t) * scale
            val y = (13.0 * cos(t) - 5.0 * cos(2.0 * t) - 2.0 * cos(3.0 * t) - cos(4.0 * t)) * scale
            return center.add(right.scale(x)).add(0.0, y, 0.0)
        }

        // 外轮廓: 450 粒子, 下深红上亮粉的静态渐变
        val mainGroup = pm.createGroup(center)
        for (i in 0 until 450) {
            val pos = heartPoint(2.0 * PI * i / 450, s)
            val yy = ((pos.y - center.y + 5.0) / 10.0).coerceIn(0.0, 1.0)
            val handle = pm.create()
                .style(ParticleStyle.DUST)
                .position(pos)
                .color(Color.ofHsb(0.99f, (0.7 + 0.3 * yy).toFloat(), (0.55 + 0.45 * yy).toFloat()))
                .scale(0.3f)
                .lifetime(-1)
                .group(mainGroup.id)
                .spawn()
            mainGroup.add(handle)
        }
        demoStates += DemoState(mainGroup, pm, DemoType.HEART, center, extra = listOf(1.0, 0.3))
        total += 450

        // 内轮廓: 220 粒子, 亮粉
        val innerGroup = pm.createGroup(center)
        for (i in 0 until 220) {
            val pos = heartPoint(2.0 * PI * i / 220, s * 0.78)
            val handle = pm.create()
                .style(ParticleStyle.DUST)
                .position(pos)
                .color(Color.ofHsb(0.95f, 0.9f, 0.95f))
                .scale(0.22f)
                .lifetime(-1)
                .group(innerGroup.id)
                .spawn()
            innerGroup.add(handle)
        }
        demoStates += DemoState(innerGroup, pm, DemoType.HEART, center, extra = listOf(2.0, 0.22))
        total += 220

        // 粒子雨: 150 粒子沿心形曲线发光上升, 顶部重置
        val rainGroup = pm.createGroup(center)
        for (i in 0 until 150) {
            val pos = heartPoint(Math.random() * 2.0 * PI, s)
            val handle = pm.create()
                .style(ParticleStyle.DUST)
                .position(pos.x, pos.y + Math.random() * 6.0, pos.z)
                .color(Color.ofHsb(0.95f, 1.0f, 0.95f))
                .scale(0.26f)
                .lifetime(-1)
                .group(rainGroup.id)
                .glowing(true)
                .spawn()
            rainGroup.add(handle)
        }
        demoStates += DemoState(rainGroup, pm, DemoType.HEART, center, extra = listOf(3.0))
        total += 150

        // 星光: 70 粒子随机散布在心形表面闪烁
        val sparkGroup = pm.createGroup(center)
        for (i in 0 until 70) {
            val pos = heartPoint(Math.random() * 2.0 * PI, s * (0.9 + Math.random() * 0.2))
            val handle = pm.create()
                .style(ParticleStyle.SPARK)
                .position(pos)
                .scale(0.35f)
                .lifetime(-1)
                .group(sparkGroup.id)
                .glowing(true)
                .spawn()
            sparkGroup.add(handle)
        }
        demoStates += DemoState(sparkGroup, pm, DemoType.HEART, center, extra = listOf(4.0, 0.35))
        total += 70

        ctx.source.sendSuccess(
            { Component.literal("Heart demo! $total particles, beating heart + rising glow rain") }, false)
        return total
    }

    /**
     * DNA 双螺旋演示: 两股相位差 π 的细长螺旋 (7.5 格高) + 金色/青白交替碱基横档 + 发光中心轴,
     * 缓慢绕 Y 轴旋转便于观察结构, 由客户端 RotateAlgorithm 驱动。
     */
    fun startHelixDemo(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException
        val level = player.level()
        val pm = ParticleManager.of(level)

        val center = player.position().add(player.lookAngle.scale(5.0)).add(0.0, 0.5, 0.0)
        val group = pm.createGroup(center)
        val turns = 3.5
        val r = 1.6
        val h = 7.5
        val steps = 300
        var total = 0

        // 两股主链: 青色 / 品红, 沿 t 微调色相
        for (strand in 0..1) {
            val phase = strand * PI
            for (i in 0 until steps) {
                val t = i.toDouble() / (steps - 1)
                val ang = 2.0 * PI * turns * t + phase
                val hue = if (strand == 0) (0.55 + 0.06 * t).toFloat() else (0.95 - 0.06 * t).toFloat()
                val handle = pm.create()
                    .style(ParticleStyle.DUST)
                    .position(center.x + cos(ang) * r, center.y - h / 2 + t * h, center.z + sin(ang) * r)
                    .color(Color.ofHsb(hue, 1.0f, 0.85f))
                    .scale(0.34f)
                    .lifetime(-1)
                    .group(group.id)
                    .spawn()
                group.add(handle)
                total++
            }
        }

        // 碱基横档: 连接两股对应点, 金色/青白交替
        var pair = 0
        for (i in 0 until steps step 12) {
            val t = i.toDouble() / (steps - 1)
            val ang = 2.0 * PI * turns * t
            val hue = if (pair % 2 == 0) 0.12f else 0.52f
            for (j in 1..10) {
                val midAng = ang + j * PI / 11.0
                val handle = pm.create()
                    .style(ParticleStyle.DUST)
                    .position(center.x + cos(midAng) * r, center.y - h / 2 + t * h, center.z + sin(midAng) * r)
                    .color(Color.ofHsb(hue, 0.8f, 0.75f))
                    .scale(0.2f)
                    .lifetime(-1)
                    .group(group.id)
                    .spawn()
                group.add(handle)
                total++
            }
            pair++
        }

        // 发光中心轴
        for (i in 0 until 16) {
            val handle = pm.create()
                .style(ParticleStyle.GLOW)
                .position(center.x, center.y - h / 2 + i * (h / 15.0), center.z)
                .scale(0.3f)
                .lifetime(-1)
                .group(group.id)
                .glowing(true)
                .spawn()
            group.add(handle)
            total++
        }

        group.addMotion("rotate", doubleArrayOf(0.0, 1.0, 0.0, 0.9))
        demoStates += DemoState(group, pm, DemoType.HELIX, center)

        ctx.source.sendSuccess(
            { Component.literal("Helix demo! $total particles, DNA double helix with base pairs") }, false)
        return total
    }

    /**
     * 星系演示: 倾斜盘面的 4 条翘曲旋臂 (金色核心 → 蓝白尖端) + 发光核球 + 扁球星系晕
     * + 外层星尘, 整体绕 Y 轴旋转, 由客户端 MotionSystem 驱动。
     */
    fun startSpiralDemo(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException
        val level = player.level()
        val pm = ParticleManager.of(level)

        val center = player.position().add(player.lookAngle.scale(6.0)).add(0.0, 1.0, 0.0)
        val tilt = 0.38
        var total = 0

        fun tilted(x: Double, y: Double, z: Double): Vec3 {
            val y2 = y * cos(tilt) - z * sin(tilt)
            val z2 = y * sin(tilt) + z * cos(tilt)
            return Vec3(center.x + x, center.y + y2, center.z + z2)
        }

        // 4 条旋臂, 带垂直翘曲
        val arms = pm.createGroup(center)
        for (arm in 0..3) {
            val offset = arm * PI / 2.0
            for (i in 0 until 400) {
                val t = i.toDouble() / 399.0
                val theta = t * 3.5 * 2.0 * PI + offset
                val radius = 0.5 + t * 4.5
                val warp = sin(theta * 2.0) * 0.3 * t
                val pos = tilted(cos(theta) * radius, warp, sin(theta) * radius)
                val handle = pm.create()
                    .style(ParticleStyle.DUST)
                    .position(pos)
                    .color(Color.ofHsb(
                        (0.12 + 0.5 * t).toFloat(),
                        (0.75 - 0.3 * t).toFloat(),
                        (0.7 + 0.3 * t).toFloat()))
                    .scale(0.26f)
                    .lifetime(-1)
                    .group(arms.id)
                    .spawn()
                arms.add(handle)
                total++
            }
        }
        arms.addMotion("rotate", doubleArrayOf(0.0, 1.0, 0.0, 0.5))
        demoStates += DemoState(arms, pm, DemoType.SPIRAL, center)

        // 发光核球
        val coreInner = pm.createGroup(center)
        for (i in 0 until 120) {
            val y = 1.0 - (i.toDouble() / 119.0) * 2.0
            val rr = sqrt(1.0 - y * y)
            val theta = i * 2.399963229728653
            val pos = tilted(cos(theta) * rr * 0.8, y * 0.5, sin(theta) * rr * 0.8)
            val handle = pm.create()
                .style(ParticleStyle.GLOW)
                .position(pos)
                .scale(0.4f)
                .lifetime(-1)
                .group(coreInner.id)
                .glowing(true)
                .spawn()
            coreInner.add(handle)
        }
        coreInner.addMotion("rotate", doubleArrayOf(0.0, 1.0, 0.0, 0.5))
        demoStates += DemoState(coreInner, pm, DemoType.SPIRAL, center)
        total += 120

        // 扁球星系晕
        val halo = pm.createGroup(center)
        for (i in 0 until 600) {
            val theta = Math.random() * 2.0 * PI
            val rr = Math.random() * 4.2
            val y = (Math.random() - 0.5) * 1.2 * (1.0 - rr / 4.2)
            val pos = tilted(cos(theta) * rr, y, sin(theta) * rr)
            val handle = pm.create()
                .style(ParticleStyle.DUST)
                .position(pos)
                .color(Color.ofHsb(0.62f, 0.25f, (0.5 + 0.4 * (1.0 - rr / 4.2)).toFloat()))
                .scale(0.18f)
                .lifetime(-1)
                .group(halo.id)
                .spawn()
            halo.add(handle)
        }
        halo.addMotion("rotate", doubleArrayOf(0.0, 1.0, 0.0, 0.5))
        demoStates += DemoState(halo, pm, DemoType.SPIRAL, center)
        total += 600

        // 外层星尘
        val dust = pm.createGroup(center)
        for (i in 0 until 150) {
            val theta = Math.random() * 2.0 * PI
            val phi = acos(2.0 * Math.random() - 1.0)
            val radius = 5.0 + Math.random() * 1.5
            val pos = tilted(
                sin(phi) * cos(theta) * radius,
                cos(phi) * radius * 0.6,
                sin(phi) * sin(theta) * radius)
            val handle = pm.create()
                .style(ParticleStyle.GLOW)
                .position(pos)
                .scale((0.15 + Math.random() * 0.25).toFloat())
                .lifetime(-1)
                .group(dust.id)
                .glowing(true)
                .spawn()
            dust.add(handle)
        }
        dust.addMotion("rotate", doubleArrayOf(0.0, 1.0, 0.0, 0.35))
        demoStates += DemoState(dust, pm, DemoType.SPIRAL, center)
        total += 150

        ctx.source.sendSuccess(
            { Component.literal("Spiral galaxy demo! $total particles, tilted 3D galaxy") }, false)
        return total
    }

    /**
     * 雷达波演示: 8 环同心粒子持续外扩, 波前亮白放大、渐远衰减缩小,
     * 越界环回收至中心重新扩散; 中心白色核心做缩放呼吸。
     */
    fun startShockwaveDemo(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException
        val level = player.level()
        val pm = ParticleManager.of(level)

        val center = player.position().add(0.0, 1.2, 0.0)
        val group = pm.createGroup(center)
        var total = 0

        // 同心环: 每环 8 个发光高亮头粒子
        for (ring in 0 until 10) {
            val r0 = 0.35 + ring * 0.68
            for (i in 0 until 60) {
                val angle = 2.0 * PI * i / 60
                val highlight = i % 8 == 0
                val handle = pm.create()
                    .style(if (highlight) ParticleStyle.GLOW else ParticleStyle.DUST)
                    .position(center.x + cos(angle) * r0, center.y, center.z + sin(angle) * r0)
                    .color(Color.ofHsb(0.58f, 0.8f, 0.9f))
                    .scale(0.25f)
                    .lifetime(-1)
                    .group(group.id)
                    .glowing(highlight)
                    .spawn()
                group.add(handle)
                total++
            }
        }

        // 中心呼吸核心
        for (i in 0 until 4) {
            val handle = pm.create()
                .style(ParticleStyle.GLOW)
                .position(center)
                .scale(0.7f)
                .lifetime(-1)
                .group(group.id)
                .glowing(true)
                .spawn()
            group.add(handle)
            total++
        }

        demoStates += DemoState(group, pm, DemoType.SHOCKWAVE, center)
        ctx.source.sendSuccess(
            { Component.literal("Shockwave demo! $total particles, radar pulse rings") }, false)
        return total
    }

    /**
     * 清除所有演示和粒子。
     * @param ctx 命令上下文
     * @return 清除的粒子数量
     */
    private fun clearAll(ctx: CommandContext<CommandSourceStack>): Int {
        stopDemos()
        val player = ctx.source.playerOrException
        val level = player.level()
        val dimId = work.nekow.particledrawing.util.ParticleUtils.dimensionUUID(level)
        val engine = ServerParticleEngine.getOrCreate(dimId)
        val cleared = engine.clearAll(level.players())
        testGroup = null

        ctx.source.sendSuccess(
            { Component.literal("Cleared $cleared particles!") }, true)
        return cleared
    }
}
