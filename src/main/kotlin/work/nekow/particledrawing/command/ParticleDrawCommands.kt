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
import kotlin.math.cos
import kotlin.math.sin

@EventBusSubscriber(modid = ParticleDrawing.MODID)
@Suppress("unused")
object ParticleDrawCommands {

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
                    .then(Commands.literal("ring")
                        .executes(::startRingDemo))
                    .then(Commands.literal("wave")
                        .executes(::startWaveDemo))
                    .then(Commands.literal("rain")
                        .executes(::startRainDemo)))
                .then(Commands.literal("clear")
                    .executes(::clearAll))
        )
    }

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

    private fun spawnGlow(ctx: CommandContext<CommandSourceStack>, count: Int): Int {
        val player = ctx.source.playerOrException
        val level = player.level()
        val pm = ParticleManager.of(level)

        val center = player.position().add(player.lookAngle.scale(3.0))
        for (i in 0 until count) {
            val angle = 2.0 * Math.PI * i / count
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
                val angle = (batch + i).toDouble() / count * 2.0 * Math.PI
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

    private var testGroup: ParticleGroup? = null

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

    private fun rotateGroup(ctx: CommandContext<CommandSourceStack>): Int {
        if (testGroup == null) {
            ctx.source.sendFailure(Component.literal("No test group! Run /particledraw group first."))
            return 0
        }
        testGroup!!.rotate(Vec3(0.0, 1.0, 0.0), Math.PI * 2, 80, EasingType.EASE_IN_OUT)
        ctx.source.sendSuccess(
            { Component.literal("Rotating group 360 deg over 80 ticks") }, false)
        return 1
    }

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

    // ===================================================================
    // Demo System — 展示 ParticleDrawing API 的完整用法
    // ===================================================================
    //
    // 核心概念:
    // ┌─────────────────────────────────────────────────────────────┐
    // │ ParticleManager  ── 入口门面，每个维度一个实例               │
    // │   ├── .create() → ParticleHandle.Builder → .spawn()        │
    // │   │   单粒子: style / position / color / scale / lifetime  │
    // │   ├── .createGroup(pivot) → ParticleGroup                 │
    // │   │   粒子组: move / rotate / recolor / remove             │
    // │   └── Draw.line/circle/disc/curve(...) → ParticleGroup    │
    // │       快捷绘图: 自动创建粒子并加入组                        │
    // └─────────────────────────────────────────────────────────────┘
    //
    // ┌─────────────────────────────────────────────────────────────┐
    // │ EasingType ── 缓动曲线                                      │
    // │   LINEAR / EASE_IN / EASE_OUT / EASE_IN_OUT / ...          │
    // │   EasingType.custom(x1, y1, x2, y2)  // CSS cubic-bezier   │
    // └─────────────────────────────────────────────────────────────┘
    //
    // ┌─────────────────────────────────────────────────────────────┐
    // │ ParticleGroup ── 组操作                                     │
    // │   g.rotate(axis, radians, durationTicks, easing)           │
    // │   g.move(delta, durationTicks, easing)                     │
    // │   g.recolor(color, durationTicks, easing)                   │
    // │   durationTicks=0 → 瞬移（无动画）                          │
    // │   axis: Vec3(0,1,0)=Y轴, Vec3(1,0,0)=X轴, Vec3(0,0,1)=Z轴 │
    // └─────────────────────────────────────────────────────────────┘
    //
    // ┌─────────────────────────────────────────────────────────────┐
    // │ ServerParticleEngine ── 底层引擎                            │
    // │   engine.update(id)             ← 返回 UpdateBuilder        │
    // │       .position(x, y, z)        ← 更新位置                  │
    // │       .color(c)                 ← 更新颜色                  │
    // │       .scale(s)                 ← 更新缩放                  │
    // │       .easing(type, ticks)       ← 缓动 + 时长              │
    // │       .send(players)            ← 发送到客户端              │
    // │   engine.destroyGroup(id, players)                         │
    // │   engine.clearAll(players)                                 │
    // └─────────────────────────────────────────────────────────────┘
    //
    // 数据流: 服务端计算 → 网络发送 → 客户端缓动插值 → 渲染
    //
    // ===================================================================

    /**
     * Demo 运行状态。track 每个演示的粒子组、管理器、动画类型和原点。
     * tickCounter 由 [tickDemos] 每 tick 递增，用于波动画的时间变量。
     */
    data class DemoState(
        val group: ParticleGroup,
        val manager: ParticleManager,
        val type: DemoType,
        val origin: Vec3,
        var tickCounter: Int = 0
    )

    enum class DemoType { CIRCLE, RING, WAVE, RAIN }

    @JvmField
    var demoStates: MutableList<DemoState> = mutableListOf()

    /**
     * 由 [ServerParticleHandler.onServerTick] 每 tick 调用。
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
                    // --------------------------------------------------
                    // CIRCLE: 水平圆环绕 Y 轴缓慢旋转
                    // 使用 ParticleGroup.rotate() 一次性旋转所有粒子
                    // 旋转在服务端计算(绕pivot旋转各个粒子坐标),客户端缓动插值
                    // --------------------------------------------------
                    DemoType.CIRCLE -> {
                        // axis=Y轴(0,1,0), 3°/tick, 无额外缓动
                        g.rotate(
                            Vec3(0.0, 1.0, 0.0),
                            Math.toRadians(3.0),
                            0,
                            EasingType.LINEAR
                        )
                    }

                    // --------------------------------------------------
                    // RING: 大圆环绕 X 轴翻滚 (垂直方向旋转)
                    // 6°/tick × 500 粒子 = 压力测试大幅移动是否掉帧
                    // --------------------------------------------------
                    DemoType.RING -> {
                        g.rotate(
                            Vec3(1.0, 0.0, 0.0),
                            Math.toRadians(6.0),
                            0,
                            EasingType.LINEAR
                        )
                    }

                    // --------------------------------------------------
                    // WAVE: 每个粒子独立 Y 轴正弦波动 + 绕中心旋转
                    // 使用 engine.updateParticle() 逐粒子更新位置
                    // durationTicks=2 让 Y 变化缓动平滑
                    // --------------------------------------------------
                    DemoType.WAVE -> {
                        val tick = state.tickCounter.toDouble()
                        val engine = state.manager.getEngine()
                        val groupData = engine.getGroup(g.id) ?: continue
                        val players = state.manager.getPlayers()

                        var idx = 0
                        for (memberId in groupData.memberIds()) {
                            val data = engine.getParticle(memberId) ?: continue
                            val angle = 2.0 * Math.PI * idx / groupData.size()
                            val newY = state.origin.y + sin(tick * 0.5 + angle * 4.0) * 0.5

                            // chainable: position + easing + send
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
                                // 复位: no easing → snap instant
                                val angle = Math.random() * 2.0 * Math.PI
                                val dist = Math.random() * 3.0
                                engine.update(memberId)
                                    .position(
                                        state.origin.x + cos(angle) * dist,
                                        state.origin.y + Math.random() * 2.5,
                                        state.origin.z + sin(angle) * dist
                                    )
                                    .send(players)
                            } else {
                                // 下落: 2-tick easing → smooth slide
                                engine.update(memberId)
                                    .position(data.position().x, newY, data.position().z)
                                    .easing(EasingType.EASE_IN_OUT, 2)
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

    /** 停止所有演示, 销毁全部粒子组 */
    @JvmStatic
    fun stopDemos() {
        for (state in demoStates) {
            try { state.manager.getEngine().destroyGroup(state.group.id, state.manager.getPlayers()) }
            catch (_: Exception) {}
        }
        demoStates.clear()
    }

    // ===================================================================
    // 演示创建方法 — 每个方法展示不同的 API 用法
    // ===================================================================

    /**
     * CIRCLE Demo: XZ 平面水平圆环, 绕 Y 轴旋转.
     *
     * API 流程:
     * 1. ParticleManager.of(ServerLevel) ── 获取维度入口
     * 2. Draw.circle(manager, center, radius, count, Axis, color, style, scale)
     *    ── 在指定平面等间距排列 count 个粒子, 返回 ParticleGroup
     * 3. 将 group 存入 DemoState, 由 tickDemos() 驱动旋转
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
     * RING Demo: 超大水平环 500 粒子, 绕 X 轴翻滚.
     *
     * 对比 CIRCLE:
     * - 半径大 2 倍 (6 格) + 粒子多 4 倍 (500) → 测试大幅移动是否掉帧
     * - 旋转轴为 X 轴 (1,0,0) → 垂直翻滚
     */
    fun startRingDemo(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException
        val level = player.level()
        val pm = ParticleManager.of(level)

        val center = player.position().add(player.lookAngle.scale(7.0))
        val group = Draw.circle(pm, center, 6.0, 500, Draw.Axis.XZ,
            Color.ofHsb(0.08f, 1.0f, 1.0f), ParticleStyle.DUST, 0.25f)

        demoStates += DemoState(group, pm, DemoType.RING, center)
        ctx.source.sendSuccess(
            { Component.literal("Orbit demo! ${group.size()} particles, large ring orbiting") }, false)
        return group.size()
    }

    /**
     * WAVE Demo: 80 粒子圆环, 每个粒子独立 Y 轴正弦波动 + 绕中心旋转.
     *
     * API 流程:
     * 1. Draw.circle() → 创建圆环, 返回 ParticleGroup
     * 2. tickDemos() 通过 groupData.memberIds() 遍历每个粒子
     * 3. engine.updateParticle() 只更新 Y 坐标 (updatePos=true)
     *    不影响颜色 (updateColor=false) 和缩放 (updateScale=false)
     * 4. durationTicks=2 → Y 变化 2 tick 缓动, 丝滑
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
     * RAIN Demo: 玩家头顶 2.5 格处 80 个蓝色粒子持续下落.
     *
     * API 流程:
     * 1. ParticleManager.createGroup(center)
     *    ── 创建空粒子组(pivot=center)
     * 2. ParticleManager.create()     ← Builder 模式
     *        .style(ParticleStyle.DUST)
     *        .position(x, y, z)        ← 必填
     *        .color(Color.ofHsb(...))  ← 可选, 默认白色
     *        .scale(0.15f)             ← 可选, 默认 1.0
     *        .lifetime(-1)             ← -1 = 永生 (不自动过期)
     *        .group(group.id)          ← 关联到组
     *        .spawn()                  ← 实际创建粒子, 返回 ParticleHandle
     * 3. group.add(handle)            ← 将粒子加入组 (可用组操作统一管理)
     * 4. tickDemos() 每 tick 更新 Y:
     *    - 下落: durationTicks=2 → 平滑
     *    - 触底: durationTicks=0 → 瞬移复位, 无动画
     */
    fun startRainDemo(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException
        val level = player.level()
        val pm = ParticleManager.of(level)

        // 云层中心在玩家头顶 2.5 格
        val center = player.position().add(0.0, 2.5, 0.0)
        val group = pm.createGroup(center)

        // 创建 80 个粒子, 随机散布在云层区域
        for (i in 0 until 80) {
            val angle = Math.random() * 2.0 * Math.PI
            val dist = Math.random() * 3.0
            val x = center.x + cos(angle) * dist
            // Y 散布在云层下 3 格范围内, 避免所有粒子同时触底
            val y = center.y - 3.0 + Math.random() * 3.5
            val z = center.z + sin(angle) * dist

            val handle = pm.create()
                .style(ParticleStyle.DUST)
                .position(x, y, z)
                .color(Color.ofHsb(0.55f, 0.8f, 0.9f))    // 淡蓝色
                .scale(0.15f)
                .lifetime(-1)                               // 永生, 不自动过期
                .group(group.id)                            // 关联到组
                .spawn()
            group.add(handle)
        }

        demoStates += DemoState(group, pm, DemoType.RAIN, center)
        ctx.source.sendSuccess(
            { Component.literal("Rain demo! ${group.size()} particles, cloud with falling rain") }, false)
        return group.size() ?: 0
    }

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
