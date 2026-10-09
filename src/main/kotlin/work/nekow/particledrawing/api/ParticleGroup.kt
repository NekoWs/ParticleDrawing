package work.nekow.particledrawing.api

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.animation.program.AnimInstruction
import work.nekow.particledrawing.animation.script.GetterRewriter
import work.nekow.particledrawing.animation.program.EntityBinding
import work.nekow.particledrawing.animation.program.PivotRef
import work.nekow.particledrawing.core.easing.EasingType
import work.nekow.particledrawing.core.network.AnimationProgramAppendPayload
import work.nekow.particledrawing.core.network.AnimationProgramCodecs
import work.nekow.particledrawing.core.network.BatchChunking
import work.nekow.particledrawing.core.network.AnimationProgramPayload
import work.nekow.particledrawing.core.network.ProgramAnchorPayload
import work.nekow.particledrawing.core.server.ServerProgramCompletion
import work.nekow.particledrawing.animation.program.finiteDurationMs
import work.nekow.particledrawing.core.server.AnimationScheduler
import net.neoforged.neoforge.network.PacketDistributor
import java.util.UUID

/**
 * 编排式动画的粒子组：一组可同时变换的粒子（由 Draw 工具或 [ParticleManager.createGroup] 创建）。
 *
 * 成员基本固定、变换为组级统一（旋转/缩放/脉冲/路径/淡入淡出）时用它：链式调用录制 [AnimInstruction]
 * 指令流一次下发，客户端本地求值；成员逐 tick 增删、每颗粒子各自受力或按位置回收的粒子流用 [ParticleBatch]。
 *
 * 轴心是程序级状态，由 [setPivot] / [followEntity] 绑定，绑定一次对其后的 [rotate] / [spin] / [scale] /
 * [pulse] 生效；旋转与缩放类指令只认当前轴心，调用顺序即语义。轴心同时是 arm 基准，客户端按
 * 「成员生成位置 − 轴心」反算相对偏移，[move] / [movePath] 不改它。
 *
 * delay 推进时间线游标（累积、不清零），程序下发之后录制的指令按「从现在起」读（见 [delay]）；
 * 多条 [rotate] / [spin] 的角度累加，后一条不覆盖前一条的相位。
 */
@Suppress("unused")
class ParticleGroup(
    val id: UUID,
    var pivot: Vec3,
    internal val manager: ParticleManager
) {

    /** 时间线游标（毫秒）：delay 累积推进（1 game tick = 50ms）。 */
    private var cursorMs = 0

    /** 已录制的指令流（未下发部分）。 */
    private val instructions = ArrayList<AnimInstruction>()

    /** 程序是否已随粒子清单下发（后续走增量 append）。 */
    private var armed = false

    /** 是否已经绑定过可移动轴心（[updateAnchor] 的前置条件）。 */
    private var movableAnchor = false

    /** 时间轴里最晚的「有限指令结束时刻」（程序相对毫秒）；-1 = 没有有限指令。 */
    private var timelineEndMs: Long = -1L

    /** 变量缓动的截止 tick（服务端 tick，名字 → 到点时刻）；重定向替换同名旧终点。 */
    private val varEaseEndTicks = HashMap<String, Long>()

    /** 是否已经出现表达式指令：糖指令一条都不执行，完成账本只认表达式的有限时长与变量缓动。 */
    private var expressionMode = false

    /** 表达式自己的到期时刻（程序相对毫秒）；-1 = 一直求值。 */
    private var expressionEndMs: Long = -1L

    /** 游标 → 程序时刻的换算（增量追加按「从现在起」读）。 */
    private val clock = GroupClock()

    /** 实体注册表与初始变量。 */
    private val entityBindings = ArrayList<EntityBinding>()
    private val vars = LinkedHashMap<String, Double>()

    /** handle 合法性：公式标识符 + 不得与内建常量/属性寄存器撞名。 */
    private val HANDLE_REGEX = Regex("""^[A-Za-z_][A-Za-z0-9_]*$""")
    private val RESERVED_NAMES = setOf("i", "n", "t", "PI", "E") +
        setOf("x", "y", "z", "r", "g", "b", "a", "vx", "vy", "vz", "sc", "glow", "light")

    /** handle 在实体注册表中必须唯一，也不得与程序变量重名，同名会在公式环境里互相覆盖。 */
    private fun requireFreeHandle(handle: String) {
        require(entityBindings.none { it.handle == handle } && handle !in vars) { "实体句柄名 '$handle' 已被占用" }
    }

    /** 服务端预警：扫描代码里的 get_* 调用，报出未知名或形态误用一类确定错误。 */
    private fun lintGetters(code: String) {
        for (problem in GetterRewriter.lint(code)) {
            LOGGER.warn("[ParticleDrawing] group {} 公式预警: {}", id, problem)
        }
    }

    // 轴心与成员

    /**
     * 设置变换轴心（固定坐标），同时作为 arm 基准（客户端按「成员生成位置 − 轴心」反算相对偏移）。
     * 一直生效到下一次 [setPivot] / [followEntity]，其后的 [rotate] / [spin] / [scale] / [pulse] 都绕它算。
     */
    fun setPivot(pivot: Vec3): ParticleGroup {
        this.pivot = pivot
        emit(AnimInstruction.BindPivot(cursorNow(), PivotRef.Fixed(pivot)))
        return this
    }

    /** [setPivot] 的分量重载。 */
    fun setPivot(x: Number, y: Number, z: Number): ParticleGroup {
        return setPivot(Vec3(x.toDouble(), y.toDouble(), z.toDouble()))
    }

    /**
     * 轴心切换为跟随实体：组随实体位置移动（+偏移），由客户端本地解析。
     *
     * 绑定后对其后的 [rotate] / [spin] / [scale] / [pulse] 生效；绑定即改 arm 基准，
     * 铺成员与绑定的先后决定相对偏移的基准，先绑定再铺成员最直观。
     *
     * @param uuid 目标实体 UUID
     * @param offset 相对实体位置（脚底）的偏移
     * @param local true = 整组连偏移一起随实体朝向旋转；false = 只跟随位置（世界朝向）
     */
    fun followEntity(uuid: UUID, offset: Vec3 = Vec3.ZERO, local: Boolean = false): ParticleGroup {
        pivot = offset.add(pivot)
        emit(AnimInstruction.BindPivot(cursorNow(), PivotRef.FollowEntity(uuid, offset, local)))
        return this
    }

    /**
     * 向该组添加一个粒子。
     * @param handle 粒子的句柄，可为 null（粒子因达到上限被拒绝时）
     */
    fun add(handle: ParticleHandle?) {
        if (handle == null) return
        manager.getEngine().getGroup(id)?.addMember(handle.id)
        armed = false // 成员变化后重发全量以刷新受控清单
    }

    /**
     * 绑定一个可移动轴心（黑洞中心、重力场中心、跟随投射物的法阵）：绑定一次即可，
     * 位置用 [updateAnchor] 逐 tick 更新，客户端按相邻两个样本插值渲染。
     *
     * 固定轴心要挪得每 tick 再 `setPivot` 一次（每 tick 一条绑定指令，帧间硬跳），本方法只发位置。
     * `Anchor.Movable` 的 [Orient.VELOCITY] 让整组随运动方向转向，[Orient.WORLD] 只跟位置；
     * 实体锚点用 [followEntity]，由客户端按 UUID 本地解析。
     *
     * @param anchor 只接受 `Anchor.Fixed`（等价 [setPivot]）与 `Anchor.Movable`
     */
    fun anchor(anchor: Anchor): ParticleGroup {
        when (anchor) {
            is Anchor.Fixed -> {
                pivot = anchor.pos
                emit(AnimInstruction.BindPivot(cursorNow(), PivotRef.Fixed(anchor.pos)))
            }
            is Anchor.Movable -> {
                pivot = anchor.pos
                movableAnchor = true
                emit(AnimInstruction.BindPivot(cursorNow(), PivotRef.Movable(anchor.pos, anchor.velocity, anchor.orient)))
            }
            is Anchor.Entity -> throw IllegalArgumentException(
                "ParticleGroup.anchor 不接受实体锚点：实体锚点用 followEntity(uuid, offset, local)"
            )
        }
        return this
    }

    /**
     * 更新可移动轴心的位置（配合 [anchor] 使用）：[previous] / [current] 是服务端相邻的两个样本。
     *
     * 客户端按这对样本插值渲染，相位与 `track` 粒子一致；样本间距过大（瞬移）或断流后的
     * 第一条样本都按跳变处理。
     *
     * @param previous 上一条样本（上一 tick 的位置）
     * @param current 本条样本（本 tick 的位置）
     * @param velocity 本 tick 的速度，只在轴心朝向为 `Orient.VELOCITY` 时用于整组转向
     */
    @JvmOverloads
    fun updateAnchor(previous: Vec3, current: Vec3, velocity: Vec3 = current.subtract(previous)): ParticleGroup {
        require(movableAnchor) {
            "updateAnchor 需要先用 anchor(Anchor.Movable(...)) 绑定可移动轴心"
        }
        pivot = current
        for (player in manager.getPlayers()) {
            PacketDistributor.sendToPlayer(player, ProgramAnchorPayload(
                id,
                previous.x, previous.y, previous.z,
                current.x, current.y, current.z,
                velocity.x, velocity.y, velocity.z,
            ))
        }
        return this
    }

    /** [updateAnchor] 的分量重载。 */
    fun updateAnchor(
        prevX: Number, prevY: Number, prevZ: Number,
        x: Number, y: Number, z: Number,
        vx: Number, vy: Number, vz: Number,
    ): ParticleGroup = updateAnchor(
        Vec3(prevX.toDouble(), prevY.toDouble(), prevZ.toDouble()),
        Vec3(x.toDouble(), y.toDouble(), z.toDouble()),
        Vec3(vx.toDouble(), vy.toDouble(), vz.toDouble()),
    )

    /**
     * 获取组内成员数量。
     * @return 粒子数量
     */
    fun size(): Int = manager.getEngine().getGroup(id)?.size() ?: 0

    // 完成账本与销毁

    /**
     * 这段编排的账本走完时回调（服务端主线程，误差一两个 tick）。
     *
     * 账本 = 有限时长指令的终点 ∪ 变量缓动的终点（[setVariableInterpolated]）∪ 表达式自己的有限时长。
     * `spin`、无限 `pulse`、无限时长的表达式、`setVariableLive` 立即赋值都没有终点，不参与；
     * 重定向替换同名旧终点，每 tick 重设新目标期间不会提前触发旧回调。
     * 账本上没有终点时不触发，只在控制台告警。
     */
    fun onAnimationComplete(action: (ParticleGroup) -> Unit): ParticleGroup {
        if (!hasCompletionSource()) {
            LOGGER.warn("[ParticleDrawing] group {} 账本上没有有限终点，onAnimationComplete 不会触发", id)
            return this
        }
        ServerProgramCompletion.onComplete(id, manager.dimensionId, fallbackTicks(), action = { action(this) })
        return this
    }

    /**
     * 客户端把这段编排跑完之后（+[graceTicks]，默认 2 tick）销毁整组。
     *
     * 与 [destroyAfter] 不同：本方法等客户端的完成信号，销毁时刻对齐视觉到零；判定口径与
     * [onAnimationComplete] 相同。客户端不在场或一直不上报时，按账本末端 + 1 秒兜底销毁。
     */
    @JvmOverloads
    fun retire(graceTicks: Int = 2): ParticleGroup {
        require(graceTicks >= 0) { "retire 的余量不能为负" }
        if (!hasCompletionSource()) {
            LOGGER.warn("[ParticleDrawing] group {} 账本上没有有限终点，retire 不会触发（改用 destroyAfter）", id)
            return this
        }
        ServerProgramCompletion.retire(id, manager.dimensionId, graceTicks, fallbackTicks())
        return this
    }

    /** 账本变动（追加有限指令、变量缓动重定向、立即赋值取消缓动）后重排兜底时刻，旧兜底按版本失效。 */
    private fun ledgerChanged() {
        ServerProgramCompletion.rescheduleFallback(id, fallbackTicks())
    }

    /** 账本上还有没有「会完成的」事：指令侧（表达式看自己的时长）或未到点的变量缓动。 */
    private fun hasCompletionSource(): Boolean {
        pruneVarEaseEnds()
        if (instructionLedgerEndMs() >= 0L) return true
        return varEaseEndTicks.isNotEmpty()
    }

    /** 指令侧的账本终点：表达式模式看表达式自己的到期时刻，否则看有限糖指令。 */
    private fun instructionLedgerEndMs(): Long =
        if (expressionMode) expressionEndMs else timelineEndMs

    /** 丢掉已经到点的变量缓动（它们不再是「待完成」的事）。 */
    private fun pruneVarEaseEnds() {
        val now = manager.level.gameTime
        varEaseEndTicks.entries.removeIf { it.value <= now }
    }

    /**
     * 从此刻起多少 tick 后走到账本末端（兜底用）；账本为空时给 -1（不排兜底）。
     *
     * 账本末端落在过去时钳到 0：此时完成信号已经发过，兜底仍要排，否则这个组等不到销毁。
     */
    private fun fallbackTicks(): Int {
        val now = manager.level.gameTime
        pruneVarEaseEnds()
        var ticks = -1
        val instructionEnd = instructionLedgerEndMs()
        if (instructionEnd >= 0L) {
            ticks = clock.ticksFromNow(instructionEnd.toInt(), now).coerceAtLeast(0)
        }
        for (deadline in varEaseEndTicks.values) {
            val remaining = (deadline - now).toInt()
            if (remaining > ticks) ticks = remaining
        }
        return ticks
    }

    // 时间线编排

    /**
     * 把时间线游标向前推进 [ticks]，之后链式调用的动画方法都在新游标时刻触发。
     * 游标累积、不清零，连续两个动画可以共享同一时刻（如停转与淡出同刻）。
     *
     * 增量追加时读作「从现在起」：程序已下发之后再录制的指令，时刻按录制那一刻 + 本会话已 delay
     * 的时长换算（见 [GroupClock]），不会因为游标落在过去而瞬间完成。全量重发会把时间轴重新起算。
     */
    fun delay(ticks: Int): ParticleGroup {
        cursorMs += ticks.coerceAtLeast(0) * 50
        return this
    }

    private fun cursorNow(): Int = cursorMs

    /**
     * 录制一条指令并立即下发，返回它在程序时刻轴上的位置（供定时销毁对齐）。
     * 时刻在发送时才按当前会话换算，尚未发送的指令不会被提前换算。
     */
    private fun emit(ins: AnimInstruction): Int {
        val now = manager.level.gameTime
        if (!armed) clock.onProgramStart(now)
        val at = clock.programTime(ins.startMs, now)
        // 记下账本末端（完成信号与 retire 的兜底时刻靠它）：
        // 表达式走自己的到期时刻，其它有限指令记进 timelineEndMs
        if (ins is AnimInstruction.Expression) {
            expressionEndMs = if (ins.durationMs > 0) at.toLong() + ins.durationMs else -1L
        } else {
            ins.finiteDurationMs()?.let { duration ->
                val end = at.toLong() + duration
                if (end > timelineEndMs) {
                    timelineEndMs = end
                    ledgerChanged()
                }
            }
        }
        instructions.add(ins)
        flush(now)
        return at
    }

    /** 首次全量下发，其后增量追加。指令按单包上限拆段，成员数超限直接报错。 */
    private fun flush(nowTick: Long) {
        val players = manager.getPlayers()
        // 锚点必须与 level.gameTime 同源：客户端用它对齐自己的 level.gameTime，消除双端时钟漂移。
        // 进程级计数器与存档 gameTime 不同源，会让时间线整体错位
        if (!armed) {
            val members = manager.getEngine().getGroup(id)?.memberIds()?.toList()
            if (members.isNullOrEmpty()) {
                LOGGER.warn("[ParticleDrawing] group {} has no members; animation program not sent", id)
                // 没有成员时指令发不出去：留一小段等成员到位，超过上限就丢弃，避免无限增长
                if (instructions.size > MAX_PENDING_INSTRUCTIONS) {
                    LOGGER.warn(
                        "[ParticleDrawing] group {} 待发指令过多（{} 条）且没有成员，已丢弃；补上成员后请重录动画",
                        id, instructions.size,
                    )
                    instructions.clear()
                }
                return
            }
            require(members.size <= MAX_MEMBERS_PER_PROGRAM) {
                "group $id 成员过多（${members.size} > $MAX_MEMBERS_PER_PROGRAM）：受控清单要一次发完，" +
                    "请拆成多个组（一个组是「一段编排」，不是粒子场）"
            }
            val batch = instructions.map { it.shiftStartMs(clock.shiftMs) }
            instructions.clear()
            val head = batch.take(MAX_INSTRUCTIONS_PER_PAYLOAD)
            for (player in players) {
                PacketDistributor.sendToPlayer(
                    player,
                    AnimationProgramPayload(id, members, nowTick, pivot, entityBindings.toList(), vars.toMap(), head),
                )
            }
            armed = true
            // 一个包放不下全部指令：剩下的按序补追加包（客户端按顺序应用）
            val rest = batch.drop(MAX_INSTRUCTIONS_PER_PAYLOAD)
            if (rest.isNotEmpty()) sendAppends(rest, players)
        } else if (instructions.isNotEmpty()) {
            val batch = instructions.map { it.shiftStartMs(clock.shiftMs) }
            instructions.clear()
            sendAppends(batch, players)
        }
        clock.onEmit(nowTick, cursorMs)
    }

    /** 追加指令：按单包上限拆段后下发。 */
    private fun sendAppends(batch: List<AnimInstruction>, players: Collection<net.minecraft.server.level.ServerPlayer>) {
        for (chunk in BatchChunking.chunks(batch, MAX_INSTRUCTIONS_PER_PAYLOAD)) {
            for (player in players) {
                PacketDistributor.sendToPlayer(player, AnimationProgramAppendPayload(id, chunk))
            }
        }
    }

    /** 排一次组销毁：把程序时刻换算成「从此刻起」的 tick 数，避免把销毁推后一整段已运行时长。 */
    private fun scheduleDestroy(programTimeMs: Int) {
        AnimationScheduler.schedule(clock.ticksFromNow(programTimeMs, manager.level.gameTime)) {
            manager.getEngine().destroyGroup(id, manager.getPlayers())
            stopProgramOnClient(destroyParticles = false)
        }
    }
    // 生命周期

    /**
     * 淡入：整组透明度从 0 缓动到各自当前值。
     */
    fun fadeIn(durationTicks: Int, easing: EasingType = EasingType.EASE_OUT): ParticleGroup {
        emit(AnimInstruction.FadeIn(cursorMs, durationTicks * 50, easing))
        return this
    }

    /**
     * 淡出：整组透明度缓动到 0；[removeAfter] 为 true 时淡出结束（+250ms 余量）由服务端销毁整组，
     * 销毁时刻按该指令的程序时刻 + 时长从当下起算，与客户端口径一致。
     */
    fun fadeOut(durationTicks: Int, removeAfter: Boolean = true, easing: EasingType = EasingType.EASE_IN): ParticleGroup {
        val at = emit(AnimInstruction.FadeOut(cursorMs, durationTicks * 50, easing))
        if (removeAfter) scheduleDestroy(at + durationTicks * 50 + 250)
        return this
    }

    /**
     * 定时销毁整组（含所有粒子）。
     * @param ticks 从当前时刻起再等多少 tick 销毁（增量追加时同 [delay]，读作「从现在起」）
     */
    fun destroyAfter(ticks: Int): ParticleGroup {
        scheduleDestroy(clock.programTime(cursorMs, manager.level.gameTime) + ticks * 50)
        return this
    }

    /**
     * 停止本组全部持续型动画（无限模式的 spin / pulse）。
     * 受 delay 游标控制：`.spin(...).delay(100).stopContinuous()` 表示转 100 tick 后停。
     */
    fun stopContinuous(): ParticleGroup {
        emit(AnimInstruction.StopContinuous(cursorMs))
        return this
    }

    /**
     * 销毁整个粒子组及其所有粒子。
     */
    fun remove() {
        manager.getEngine().destroyGroup(id, manager.getPlayers())
        stopProgramOnClient(destroyParticles = false)
    }

    private fun stopProgramOnClient(destroyParticles: Boolean) {
        // 组已经收尾：完成信号/兜底销毁的登记一并注销
        ServerProgramCompletion.cancel(id)
        for (player in manager.getPlayers()) {
            PacketDistributor.sendToPlayer(player, work.nekow.particledrawing.core.network.StopAnimationProgramPayload(id, destroyParticles))
        }
    }

    // 一次性变换（有限时长指令）

    /**
     * 组平移。[delta] 只影响渲染位置（客户端 `pathOffset`），不改轴心绑定；轴心只由
     * [setPivot] / [followEntity] 决定，铺完成员后再 move 不会改变成员的相对偏移基准。
     */
    fun move(delta: Vec3, durationTicks: Int, easing: EasingType = EasingType.LINEAR): ParticleGroup {
        emit(AnimInstruction.Translate(cursorMs, delta, durationTicks * 50, easing))
        return this
    }

    /** [move] 的分量重载。 */
    fun move(x: Number, y: Number, z: Number, durationTicks: Int, easing: EasingType = EasingType.LINEAR): ParticleGroup {
        return move(Vec3(x.toDouble(), y.toDouble(), z.toDouble()), durationTicks, easing)
    }

    /**
     * 逐成员各自方向的平移：每颗粒子沿自己相对轴心的偏移方向外移 `scale × |偏移|`。
     * 用于球面或多面体碎裂后碎片各自沿法线向外飞，这种效果组级 [move] 表达不了。
     *
     * 方向取当前偏移（跟着 [spin] / [rotate] 一起转），所以边转边炸开也是对的；
     * `scale = 1` 表示每颗沿自己的方向移到「偏移长度翻倍」的位置。
     *
     * @param scale 沿各自偏移方向的位移倍率（1 = 移到 2 倍偏移处）
     */
    fun moveAlongOffset(scale: Float, durationTicks: Int, easing: EasingType = EasingType.LINEAR): ParticleGroup {
        emit(AnimInstruction.MoveEach(cursorMs, scale, durationTicks * 50, easing))
        return this
    }

    /**
     * 绕当前轴心一次性旋转（轴心见 [setPivot] / [followEntity]）；本方法不接受轴心参数，
     * 想绕实体转要先 `followEntity(...)` 再调本方法。
     *
     * 与 [spin] 叠加：多条旋转指令的角度累加，后一条不覆盖前一条的相位；
     * `durationTicks = 0` 即瞬时补相位。
     */
    fun rotate(axis: Vec3, radians: Double, durationTicks: Int, easing: EasingType = EasingType.LINEAR): ParticleGroup {
        emit(AnimInstruction.RotateOnce(cursorMs, axis, radians, durationTicks * 50, easing))
        return this
    }

    /** [rotate] 的分量重载。 */
    fun rotate(x: Number, y: Number, z: Number, radians: Double, durationTicks: Int, easing: EasingType = EasingType.LINEAR): ParticleGroup {
        return rotate(Vec3(x.toDouble(), y.toDouble(), z.toDouble()), radians, durationTicks, easing)
    }

    /** 重着色到目标颜色。 */
    fun recolor(targetColor: Color, durationTicks: Int, easing: EasingType = EasingType.LINEAR): ParticleGroup {
        emit(AnimInstruction.Recolor(cursorMs, targetColor.r, targetColor.g, targetColor.b, targetColor.a, durationTicks * 50, easing))
        return this
    }

    /**
     * 相对当前轴心等比缩放：粒子到轴心的距离与视觉大小同乘 [ratio]（2f = 放大两倍），
     * `durationTicks = 0` 表示瞬时跳变。
     *
     * 倍率累积：起点是执行那一刻的当前倍率而非 1，所以 `.scale(0.01f, 0).scale(100f, 3)`
     * 会从 0.01 倍长回 1 倍，中途不会重置回 1。要直接给绝对目标用 [scaleTo]。
     */
    fun scale(ratio: Float, durationTicks: Int, easing: EasingType = EasingType.LINEAR): ParticleGroup {
        emit(AnimInstruction.ScaleBy(cursorMs, ratio, durationTicks * 50, easing))
        return this
    }

    /** [scale] 的显式名字：在当前倍率之上相乘（`scaleBy(0.5f)` = 缩到一半，再来一次还是减半）。 */
    fun scaleBy(ratio: Float, durationTicks: Int, easing: EasingType = EasingType.LINEAR): ParticleGroup =
        scale(ratio, durationTicks, easing)

    /**
     * 把组级倍率缓动到绝对目标 [target]（1 = 原始尺寸）：终点是写死的 [target]，
     * 起点同样是执行那一刻的当前倍率。
     *
     * 从零尺寸展开可先 `scaleTo(0f, 0)` 铺成员再 `scaleTo(1f, 6)` 长开；`target = 0`
     * 表示完全收起，客户端不绘制。
     */
    fun scaleTo(target: Float, durationTicks: Int, easing: EasingType = EasingType.LINEAR): ParticleGroup {
        require(target >= 0f) { "scaleTo 的目标倍率不能为负" }
        emit(AnimInstruction.ScaleTo(cursorMs, target, durationTicks * 50, easing))
        return this
    }

    // 持续运动

    /**
     * 无限匀速旋转（绕当前轴心，同 [rotate]，与其它旋转指令叠加）；用 [stopContinuous] 停止。
     * 先 [followEntity] 再调本方法即为跟随实体转向。
     */
    fun spin(axis: Vec3, radiansPerTick: Double): ParticleGroup {
        emit(AnimInstruction.Spin(cursorMs, axis, radiansPerTick / 50))
        return this
    }

    /** 折线路径移动：从当前基准出发依次经过 [points]，[easing] 作用于全程进度。同 [move]，不改轴心绑定。 */
    fun movePath(points: List<Vec3>, durationTicks: Int, easing: EasingType = EasingType.LINEAR): ParticleGroup {
        require(points.isNotEmpty()) { "movePath 至少需要一个途经点" }
        emit(AnimInstruction.MovePath(cursorMs, points, durationTicks * 50, easing))
        return this
    }

    /**
     * 呼吸脉冲：1× ↔ [peakRatio]× 往复（同样作用于粒子到轴心的距离与视觉大小）；[cycles] 负数无限。
     */
    fun pulse(peakRatio: Float, halfPeriodTicks: Int, cycles: Int = -1): ParticleGroup {
        emit(AnimInstruction.Pulse(cursorMs, peakRatio, halfPeriodTicks * 50, cycles))
        return this
    }

    // 实体句柄与表达式指令

    /**
     * 定义实体句柄：把 [uuid] 以 [handle] 名写进程序的实体注册表（下发顺序 = 句柄序号）。
     * 公式内用 `get_entity_<prop>(<handle>)` 被动取值，属性表见 `EntityProp` / `WorldProp` 枚举；
     * 世界属性无需登记，直接 `get_world_<prop>()`。
     *
     * handle 必须是合法公式标识符，且不得与内建名（i/n/t/PI/E、x/y/z/r/g/b/a/vx/vy/vz/sc/glow/light）
     * 或已有变量重名，违反立即抛异常。
     */
    fun defineEntity(handle: String, uuid: UUID): ParticleGroup {
        require(HANDLE_REGEX.matches(handle)) { "实体句柄名 '$handle' 不是合法标识符" }
        require(handle !in RESERVED_NAMES) { "实体句柄名 '$handle' 与内建名冲突" }
        requireFreeHandle(handle)
        entityBindings.add(EntityBinding(handle, uuid))
        armed = false // 注册表变化需重发全量
        return this
    }

    /** 设置程序静态变量（编译进公式环境的常量）。 */
    fun setVariable(name: String, value: Double): ParticleGroup {
        require(entityBindings.none { it.handle == name }) { "变量名 '$name' 与实体句柄冲突" }
        vars[name] = value
        return this
    }

    /**
     * 表达式指令：每粒子每 tick 求值 [code]（专用标量公式，与 .pdraw 函数对象的 this 脚本语言不同）。
     * 输出 [x,y,z] 为世界绝对坐标，可用 i/n/t、标量数学函数、get_* 被动输入与程序变量；
     * 一旦出现即接管位置/颜色/缩放的最终解释权，FADE 因子仍叠加其上。
     *
     * @param durationTicks >0 表示求值这么多 tick 后停止求值（粒子停在最后一帧的状态），
     *   到期时刻进完成账本（[onAnimationComplete] / [retire] 认它）；0（默认）= 一直求值。
     */
    @JvmOverloads
    fun expression(code: String, durationTicks: Int = 0): ParticleGroup {
        require(durationTicks >= 0) { "expression 的时长不能为负" }
        lintGetters(code)
        // 出现表达式即进入表达式模式：糖指令不执行，完成账本改看表达式与变量缓动
        expressionMode = true
        emit(AnimInstruction.Expression(cursorMs, code, durationTicks * 50))
        return this
    }

    /**
     * 运行时热更程序变量（对已激活程序生效）：value 为标量公式字符串，可引用其它程序变量
     * 或直接给常量；该路径不注入 t/i/n，公式不能引用它们。
     *
     * 立即赋值会取消该变量正在进行的缓动（连同它在完成账本上的终点）。
     */
    fun setVariableLive(name: String, value: String) {
        lintGetters(value)
        varEaseEndTicks.remove(name)
        ledgerChanged()
        for (player in manager.getPlayers()) {
            PacketDistributor.sendToPlayer(player, work.nekow.particledrawing.core.network.SetProgramVarPayload(id, name, value))
        }
    }

    /**
     * 运行时热更程序变量，并在 [ticks] tick 内缓动到目标值。
     *
     * 变量常被当作空间端点（光束末端、场中心）：立即赋值会让整段几何瞬移，本方法让客户端从
     * 当前值缓动过去。这条缓动同时进完成账本，`setVariableInterpolated("presence", "0", 7).retire()`
     * 即「7 tick 后归零、然后销毁」；重定向替换同名旧终点，每 tick 重设目标不会提前触发旧回调。
     *
     * [value] 是标量公式字符串（与 [setVariableLive] 同一套求值环境，不注入 t/i/n），
     * 在收到那一刻求出目标值；`ticks = 0` 等价于立即赋值（不进账本）。
     */
    @JvmOverloads
    fun setVariableInterpolated(
        name: String,
        value: String,
        ticks: Int,
        easing: EasingType = EasingType.LINEAR,
    ): ParticleGroup {
        require(ticks >= 0) { "setVariableInterpolated 的时长不能为负" }
        lintGetters(value)
        // 账本：同名重定向直接替换旧终点；0 tick 等于立即赋值，不留终点
        if (ticks > 0) {
            varEaseEndTicks[name] = manager.level.gameTime + ticks
        } else {
            varEaseEndTicks.remove(name)
        }
        // 重定向与取消都要重排兜底，旧任务按版本失效，否则会按老时刻提前收走这个组
        ledgerChanged()
        val payload = work.nekow.particledrawing.core.network.SetProgramVarEasePayload(
            id, name, value, ticks * 50, easing,
        )
        for (player in manager.getPlayers()) {
            PacketDistributor.sendToPlayer(player, payload)
        }
        return this
    }

    override fun toString() = "ParticleGroup{$id size=${size()}}"

    companion object {
        /** 单包指令条数上限：超出就拆成多个追加包（与载荷里的常量同源）。 */
        private const val MAX_INSTRUCTIONS_PER_PAYLOAD = AnimationProgramCodecs.MAX_INSTRUCTIONS_PER_PAYLOAD

        /** 一个组的成员上限：受控清单要一次发完，超出会被拒绝。 */
        private const val MAX_MEMBERS_PER_PROGRAM = AnimationProgramCodecs.MAX_PROGRAM_MEMBERS

        /** 没有成员时最多缓存多少条待发指令（之后丢弃并告警，避免无限增长）。 */
        private const val MAX_PENDING_INSTRUCTIONS = 4096

        private val LOGGER = org.apache.logging.log4j.LogManager.getLogger("ParticleDrawing")
    }
}
