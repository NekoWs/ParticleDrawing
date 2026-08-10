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
import work.nekow.particledrawing.core.server.ServerParticleEngine
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

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
                .then(Commands.literal("demo")
                    .executes(::startDemo)
                    .then(Commands.literal("wave")
                        .executes(::startWaveDemo))
                    .then(Commands.literal("rain")
                        .executes(::startRainDemo))
                    .then(Commands.literal("sphere")
                        .executes(::startSphereDemo))
                    .then(Commands.literal("magic")
                        .executes(::startMagicCircleDemo)))
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
     * 演示运行状态。
     * 记录每个演示的粒子组、管理器、动画类型和原点。
     * tickCounter 由 [tickDemos] 每 tick 递增，用于波动画的时间变量。
     */
    data class DemoState(
        val group: ParticleGroup,
        val manager: ParticleManager,
        val type: DemoType,
        val origin: Vec3,
        var tickCounter: Int = 0
    )

    /** 演示类型枚举 */
    enum class DemoType { CIRCLE, WAVE, RAIN, SPHERE, MAGIC_CIRCLE }

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
        group.colorByYMotion()                     // 固定表面纹理

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
