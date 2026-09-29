package work.nekow.particledrawing.api

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.animation.program.AnimInstruction
import work.nekow.particledrawing.animation.script.GetterRewriter
import work.nekow.particledrawing.animation.program.EntityBinding
import work.nekow.particledrawing.animation.program.PivotRef
import work.nekow.particledrawing.core.easing.EasingType
import work.nekow.particledrawing.core.network.AnimationProgramAppendPayload
import work.nekow.particledrawing.core.network.AnimationProgramPayload
import work.nekow.particledrawing.core.server.AnimationScheduler
import net.neoforged.neoforge.network.PacketDistributor
import java.util.UUID

/**
 * 编排式动画的粒子组：一组可同时变换的粒子（经 Draw 工具或 [ParticleManager.createGroup] 创建）。
 *
 * **适用边界**：成员基本固定、变换是**组级统一**的编排式动画（旋转/缩放/脉冲/路径/淡入淡出）用它——
 * 链式调用录制 [AnimInstruction] 指令流一次下发，客户端本地求值直写渲染，持续动画零带宽。
 * 反过来，**成员逐 tick 增删、每颗粒子各自受力/轨迹/寿命、按位置条件回收**的粒子流
 * （黑洞吸入、重力场下落）请用 [ParticleBatch]：本类的成员变更会重发全量受控清单，
 * 组级变换也表达不了「每粒子沿各自轨迹运动」。
 *
 * delay 推进时间线游标（累积、不清零）；defineEntity/expression 提供实体句柄与表达式能力。
 *
 * **轴心语义（组级变换都绕它算）**：轴心是**程序级状态**，由 [setPivot] / [followEntity] 绑定，
 * 绑定一次就对**其后**的 [rotate] / [spin] / [scale] / [pulse] 全部生效，直到下一次绑定。
 * 旋转/缩放类指令**不接受轴心参数**，所以**调用顺序就是语义**：
 *
 * ```
 * group.followEntity(uuid)        // 轴心 = 实体（每 tick 本地解析，零带宽）
 *     .spin(Vec3(0.0, 1.0, 0.0), 0.02)   // 绕实体转；反过来先 spin 就只绕当时的固定点转
 * ```
 *
 * 轴心绑定到实体后是**活的**（跟随位置，`local = true` 时连朝向一起），组内各粒子保持相对轴心的偏移。
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

    /** 实体注册表与初始变量。 */
    private val entityBindings = ArrayList<EntityBinding>()
    private val vars = LinkedHashMap<String, Double>()

    /** handle 合法性：公式标识符 + 不得与内建常量/属性寄存器撞名。 */
    private val HANDLE_REGEX = Regex("""^[A-Za-z_][A-Za-z0-9_]*$""")
    private val RESERVED_NAMES = setOf("i", "n", "t", "PI", "E") +
        setOf("x", "y", "z", "r", "g", "b", "a", "vx", "vy", "vz", "sc", "glow", "light")

    /** handle 在实体注册表中必须唯一，且不得与程序变量重名——同名会在公式环境里互相覆盖。 */
    private fun requireFreeHandle(handle: String) {
        require(entityBindings.none { it.handle == handle } && handle !in vars) { "实体句柄名 '$handle' 已被占用" }
    }

    /** 服务端 best-effort 预警：扫描代码里的 get_* 调用，报「确定错误」（未知名/形态误用）。 */
    private fun lintGetters(code: String) {
        for (problem in GetterRewriter.lint(code)) {
            LOGGER.warn("[ParticleDrawing] group {} 公式预警: {}", id, problem)
        }
    }

    // —— 基础 ——

    /**
     * 设置变换轴心（固定坐标）。**粘性**：一直生效到下一次 [setPivot] / [followEntity]，
     * 之后所有 [rotate] / [spin] / [scale] / [pulse] 都绕它算。
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
     * 轴心切换为跟随实体：组随实体位置移动（+偏移），由客户端本地解析，零逐 tick 带宽。
     *
     * 同样是**粘性**的：绑定之后的 [rotate] / [spin] / [scale] / [pulse] 都绕/相对它算，
     * 「跟随持有者 + 持续自转」就是本方法 + [spin] 的顺序（顺序反了只会绕固定点转）。
     *
     * @param uuid 目标实体 UUID
     * @param offset 相对实体位置（脚底）的偏移
     * @param local true = 整组连偏移一起随实体朝向旋转（贴在身前/身侧）；false = 只跟随位置（世界朝向）
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
     * 获取组内成员数量。
     * @return 粒子数量
     */
    fun size(): Int = manager.getEngine().getGroup(id)?.size() ?: 0

    // —— 时间线编排 ——

    /**
     * 把时间线游标向前推进 [ticks]：之后链式调用的动画方法都在新游标时刻触发。
     * 游标累积、不清零——连续两个动画共享同一时刻（如停转与淡出同刻）。
     */
    fun delay(ticks: Int): ParticleGroup {
        cursorMs += ticks.coerceAtLeast(0) * 50
        return this
    }

    private fun cursorNow(): Int = cursorMs

    /** 录制一条指令并触发下发。 */
    private fun emit(ins: AnimInstruction) {
        instructions.add(ins)
        flush()
    }

    /** 首次全量下发；其后增量追加。 */
    private fun flush() {
        val players = manager.getPlayers()
        // 锚点必须与 level.gameTime 同源：客户端用它对齐自己的 level.gameTime，
        // 消除双端时钟漂移（勿用进程级计数器——与存档 gameTime 不同源会让时间线整体错位）
        val anchor = manager.level.gameTime
        if (!armed) {
            val members = manager.getEngine().getGroup(id)?.memberIds()?.toList()
            if (members.isNullOrEmpty()) {
                LOGGER.warn("[ParticleDrawing] group {} has no members; animation program not sent", id)
                return
            }
            val batch = ArrayList(instructions)
            instructions.clear()
            for (player in players) {
                PacketDistributor.sendToPlayer(
                    player,
                    AnimationProgramPayload(id, members, anchor, pivot, entityBindings.toList(), vars.toMap(), batch),
                )
            }
            armed = true
        } else if (instructions.isNotEmpty()) {
            val batch = ArrayList(instructions)
            instructions.clear()
            for (player in players) {
                PacketDistributor.sendToPlayer(player, AnimationProgramAppendPayload(id, batch))
            }
        }
    }

    // —— 生命周期 ——

    /**
     * 淡入：整组透明度从 0 缓动到各自当前值。
     */
    fun fadeIn(durationTicks: Int, easing: EasingType = EasingType.EASE_OUT): ParticleGroup {
        emit(AnimInstruction.FadeIn(cursorMs, durationTicks * 50, easing))
        return this
    }

    /**
     * 淡出：整组透明度缓动到 0；结束后由服务端定时销毁整组。
     */
    fun fadeOut(durationTicks: Int, removeAfter: Boolean = true, easing: EasingType = EasingType.EASE_IN): ParticleGroup {
        emit(AnimInstruction.FadeOut(cursorMs, durationTicks * 50, easing))
        if (removeAfter) {
            AnimationScheduler.schedule(((cursorMs + durationTicks * 50 + 250) / 50).coerceAtLeast(1)) {
                manager.getEngine().destroyGroup(id, manager.getPlayers())
                stopProgramOnClient(destroyParticles = false) // 粒子已随 destroy 包移除，仅清程序
            }
        }
        return this
    }

    /**
     * 定时销毁整组（含所有粒子）。
     * @param ticks 从当前游标时刻起再等多少 tick 销毁
     */
    fun destroyAfter(ticks: Int): ParticleGroup {
        AnimationScheduler.schedule(((cursorMs + ticks * 50) / 50).coerceAtLeast(1)) {
            manager.getEngine().destroyGroup(id, manager.getPlayers())
            stopProgramOnClient(destroyParticles = false)
        }
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
        for (player in manager.getPlayers()) {
            PacketDistributor.sendToPlayer(player, work.nekow.particledrawing.core.network.StopAnimationProgramPayload(id, destroyParticles))
        }
    }

    // —— 一次性变换（有限时长指令） ——

    /** 组平移。 */
    fun move(delta: Vec3, durationTicks: Int, easing: EasingType = EasingType.LINEAR): ParticleGroup {
        pivot = pivot.add(delta)
        emit(AnimInstruction.Translate(cursorMs, delta, durationTicks * 50, easing))
        return this
    }

    /** [move] 的分量重载。 */
    fun move(x: Number, y: Number, z: Number, durationTicks: Int, easing: EasingType = EasingType.LINEAR): ParticleGroup {
        return move(Vec3(x.toDouble(), y.toDouble(), z.toDouble()), durationTicks, easing)
    }

    /**
     * 绕**当前轴心**一次性旋转（轴心见 [setPivot] / [followEntity]）。
     * 想绕实体转就先 `followEntity(...)` 再调本方法——**调用顺序就是语义**，本方法不接受轴心参数。
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
     * 相对**当前轴心**等比缩放：粒子到轴心的距离与视觉大小同乘 [ratio]（倍率语义，2f = 放大两倍）。
     * durationTicks=0 表示瞬时跳变。
     */
    fun scale(ratio: Float, durationTicks: Int, easing: EasingType = EasingType.LINEAR): ParticleGroup {
        emit(AnimInstruction.ScaleBy(cursorMs, ratio, durationTicks * 50, easing))
        return this
    }

    // —— 持续运动 ——

    /**
     * 无限匀速旋转（绕**当前轴心**，同 [rotate]）；用 [stopContinuous] 停止。
     *
     * 轴心是活的：先 [followEntity] 再本方法，就是「跟着实体转」——护盾一类「跟随持有者 + 自转」的写法。
     */
    fun spin(axis: Vec3, radiansPerTick: Double): ParticleGroup {
        emit(AnimInstruction.Spin(cursorMs, axis, radiansPerTick / 50))
        return this
    }

    /** 折线路径移动：从当前基准出发依次经过 [points]，[easing] 作用于全程进度。 */
    fun movePath(points: List<Vec3>, durationTicks: Int, easing: EasingType = EasingType.LINEAR): ParticleGroup {
        require(points.isNotEmpty()) { "movePath 至少需要一个途经点" }
        pivot = points.last()
        emit(AnimInstruction.MovePath(cursorMs, points, durationTicks * 50, easing))
        return this
    }

    /**
     * 呼吸脉冲：1× ↔ [peakRatio]× 往复（相对**当前轴心**缩放，同 [scale]）；[cycles] 负数无限。
     */
    fun pulse(peakRatio: Float, halfPeriodTicks: Int, cycles: Int = -1): ParticleGroup {
        emit(AnimInstruction.Pulse(cursorMs, peakRatio, halfPeriodTicks * 50, cycles))
        return this
    }

    // —— 实体句柄 + 表达式指令（上限能力） ——

    /**
     * 定义实体句柄：把 [uuid] 以 [handle] 名写进程序的实体注册表（下发顺序 = 句柄序号）。
     * 公式内通过 `get_entity_<prop>(<handle>)` 被动取值——用到什么取什么，
     * 属性表见 `EntityProp` / `WorldProp` 枚举；世界属性无需登记，直接 `get_world_<prop>()`。
     *
     * handle 必须是合法公式标识符，且不得与内建名（i/n/t/PI/E、x/y/z/r/g/b/a/vx/vy/vz/sc/glow/light）
     * 或已有变量重名；违反立即抛异常。
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
     * 表达式指令：每粒子每 tick 求值 [code]（专用标量公式：i/n/t、[x,y,z]=... 等旧式语法，
     * 与 .pdraw 函数对象的 this 脚本语言不同）。
     * 输出 [x,y,z] 为世界绝对坐标；可用 i/n/t、全套标量数学函数、get_* 被动输入、程序变量。
     * 一旦出现即接管位置/颜色/缩放的最终解释权；FADE 因子仍叠加其上。
     */
    fun expression(code: String): ParticleGroup {
        lintGetters(code)
        emit(AnimInstruction.Expression(cursorMs, code))
        return this
    }

    /**
     * 运行时热更程序变量（对已激活程序生效）：value 为标量公式字符串，
     * 可引用其它程序变量（如 `group.setVariableLive("rad", "speed * 2")`）或直接给常量。
     * 注：该路径不注入 t/i/n，公式不能引用它们。
     */
    fun setVariableLive(name: String, value: String) {
        lintGetters(value)
        for (player in manager.getPlayers()) {
            PacketDistributor.sendToPlayer(player, work.nekow.particledrawing.core.network.SetProgramVarPayload(id, name, value))
        }
    }

    override fun toString() = "ParticleGroup{$id size=${size()}}"

    companion object {
        private val LOGGER = org.apache.logging.log4j.LogManager.getLogger("ParticleDrawing")
    }
}
