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
 * 一组可同时变换的粒子集合，也是编排式动画的基本单位。
 * 通过 [Draw] 工具或 [ParticleManager.createGroup] 创建。
 *
 * 动画执行模型：**客户端自驱程序**。所有动画方法把意图录制为 [AnimInstruction]
 * 指令流，随首个方法调用一次性下发到客户端；此后每 client tick 由客户端本地求值
 * 并直写渲染——持续动画（spin/pulse）运行期零带宽、帧率级平滑。
 * [delay] 推进时间线游标（累积、不清零），之后的每个动画方法都在各自游标时刻触发。
 *
 * ```kotlin
 * Draw.circle(manager, center, 3.0, 60)
 *     .fadeIn(10)                          // t=0    渐显
 *     .spin(Vec3(0, 1, 0), Math.PI / 40)   // t=0    开始持续旋转
 *     .delay(100)                          // 游标 → 100
 *     .stopContinuous()                    // t=100  停转
 *     .fadeOut(20)                         // t=100  渐隐销毁
 * ```
 *
 * 高级能力：实体句柄 + 被动输入 getter + 表达式指令——
 * ```kotlin
 * group.defineEntity("e", entityUUID)
 *      .expression("""
 *          th = i / n * 2 * pi;
 *          [x,y,z] = [get_entity_x(e) + cos(th) * 2, get_entity_y(e) + 1 + sin(t * 0.1), get_entity_z(e) + sin(th) * 2]
 *      """)
 * ```
 */
@Suppress("unused")
class ParticleGroup(
    val id: UUID,
    var pivot: Vec3,
    internal val manager: ParticleManager
) {

    /** 时间线游标（tick）：delay 累积推进。 */
    private var cursorTicks = 0

    /** 已录制的指令流（未下发部分）。 */
    private val instructions = ArrayList<AnimInstruction>()

    /** 程序是否已随粒子清单下发（后续走增量 append）。 */
    private var armed = false

    /** 实体注册表与初始变量。 */
    private val entityBindings = ArrayList<EntityBinding>()
    private val vars = LinkedHashMap<String, Double>()

    /** handle 合法性：公式标识符 + 不得与内建常量/属性寄存器撞名。 */
    private val HANDLE_REGEX = Regex("""^[A-Za-z_][A-Za-z0-9_]*$""")
    private val RESERVED_NAMES = setOf("i", "n", "t", "pi", "e") +
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

    /* =====================================================================
     * 基础
     * ===================================================================== */

    /**
     * 设置变换基准点（固定坐标）。影响后续旋转/缩放类指令。
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

    /** 轴心切换为跟随实体：组随实体位置移动（+偏移），由客户端本地解析。 */
    fun followEntity(uuid: UUID, offset: Vec3 = Vec3.ZERO): ParticleGroup {
        pivot = offset.add(pivot)
        emit(AnimInstruction.BindPivot(cursorNow(), PivotRef.FollowEntity(uuid, offset)))
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

    /* =====================================================================
     * 时间线编排
     * ===================================================================== */

    /**
     * 把时间线游标向前推进 [ticks]：之后链式调用的动画方法都在新游标时刻触发。
     * 游标累积、不清零——连续两个动画共享同一时刻（如停转与淡出同刻）。
     */
    fun delay(ticks: Int): ParticleGroup {
        cursorTicks += ticks.coerceAtLeast(0)
        return this
    }

    private fun cursorNow(): Int = cursorTicks

    /** 录制一条指令并确保程序已下发。 */
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

    /* =====================================================================
     * 生命周期
     * ===================================================================== */

    /**
     * 淡入：整组透明度从 0 缓动到各自当前值。
     */
    fun fadeIn(durationTicks: Int, easing: EasingType = EasingType.EASE_OUT): ParticleGroup {
        emit(AnimInstruction.FadeIn(cursorTicks, durationTicks, easing))
        return this
    }

    /**
     * 淡出：整组透明度缓动到 0；结束后由服务端定时销毁整组。
     */
    fun fadeOut(durationTicks: Int, removeAfter: Boolean = true, easing: EasingType = EasingType.EASE_IN): ParticleGroup {
        emit(AnimInstruction.FadeOut(cursorTicks, durationTicks, easing))
        if (removeAfter) {
            AnimationScheduler.schedule((cursorTicks + durationTicks + 5).coerceAtLeast(1)) {
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
        AnimationScheduler.schedule((cursorTicks + ticks).coerceAtLeast(1)) {
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
        emit(AnimInstruction.StopContinuous(cursorTicks))
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

    /* =====================================================================
     * 一次性变换（有限时长指令）
     * ===================================================================== */

    /** 组平移。 */
    fun move(delta: Vec3, durationTicks: Int, easing: EasingType = EasingType.LINEAR): ParticleGroup {
        pivot = pivot.add(delta)
        emit(AnimInstruction.Translate(cursorTicks, delta, durationTicks, easing))
        return this
    }

    /** [move] 的分量重载。 */
    fun move(x: Number, y: Number, z: Number, durationTicks: Int, easing: EasingType = EasingType.LINEAR): ParticleGroup {
        return move(Vec3(x.toDouble(), y.toDouble(), z.toDouble()), durationTicks, easing)
    }

    /** 绕基准点一次性旋转。 */
    fun rotate(axis: Vec3, radians: Double, durationTicks: Int, easing: EasingType = EasingType.LINEAR): ParticleGroup {
        emit(AnimInstruction.RotateOnce(cursorTicks, PivotRef.Fixed(pivot), axis, radians, durationTicks, easing))
        return this
    }

    /** [rotate] 的分量重载。 */
    fun rotate(x: Number, y: Number, z: Number, radians: Double, durationTicks: Int, easing: EasingType = EasingType.LINEAR): ParticleGroup {
        return rotate(Vec3(x.toDouble(), y.toDouble(), z.toDouble()), radians, durationTicks, easing)
    }

    /** 重着色到目标颜色。 */
    fun recolor(targetColor: Color, durationTicks: Int, easing: EasingType = EasingType.LINEAR): ParticleGroup {
        emit(AnimInstruction.Recolor(cursorTicks, targetColor.r, targetColor.g, targetColor.b, targetColor.a, durationTicks, easing))
        return this
    }

    /**
     * 相对基准点等比缩放：半径与视觉大小同乘 [ratio]（倍率语义，2f = 放大两倍）。
     * durationTicks=0 表示瞬时跳变。
     */
    fun scale(ratio: Float, durationTicks: Int, easing: EasingType = EasingType.LINEAR): ParticleGroup {
        emit(AnimInstruction.ScaleBy(cursorTicks, ratio, durationTicks, easing))
        return this
    }

    /* =====================================================================
     * 持续运动
     * ===================================================================== */

    /** 无限匀速旋转；用 [stopContinuous] 停止。 */
    fun spin(axis: Vec3, radiansPerTick: Double): ParticleGroup {
        emit(AnimInstruction.Spin(cursorTicks, PivotRef.Fixed(pivot), axis, radiansPerTick))
        return this
    }

    /** 折线路径移动：从当前基准出发依次经过 [points]，[easing] 作用于全程进度。 */
    fun movePath(points: List<Vec3>, durationTicks: Int, easing: EasingType = EasingType.LINEAR): ParticleGroup {
        require(points.isNotEmpty()) { "movePath 至少需要一个途经点" }
        pivot = points.last()
        emit(AnimInstruction.MovePath(cursorTicks, points, durationTicks, easing))
        return this
    }

    /** 呼吸脉冲：1× ↔ [peakRatio]× 往复；[cycles] 负数无限。 */
    fun pulse(peakRatio: Float, halfPeriodTicks: Int, cycles: Int = -1): ParticleGroup {
        emit(AnimInstruction.Pulse(cursorTicks, peakRatio, halfPeriodTicks, cycles))
        return this
    }

    /* =====================================================================
     * 实体句柄 + 表达式指令（上限能力）
     * ===================================================================== */

    /**
     * 定义实体句柄：把 [uuid] 以 [handle] 名写进程序的实体注册表（下发顺序 = 句柄序号）。
     * 公式内通过 `get_entity_<prop>(<handle>)` 被动取值——用到什么取什么，
     * 属性表见 `EntityProp` / `WorldProp` 枚举；世界属性无需登记，直接 `get_world_<prop>()`。
     *
     * handle 必须是合法公式标识符，且不得与内建名（i/n/t/pi/e、x/y/z/r/g/b/a/vx/vy/vz/sc/glow/light）
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
     * 表达式指令：每粒子每 tick 求值 [code]（编辑器函数对象同款语法），
     * 输出 [x,y,z] 为世界绝对坐标；可用 i/n/t、全套数学函数、get_* 被动输入、程序变量。
     * 一旦出现即接管位置/颜色/缩放的最终解释权；FADE 因子仍叠加其上。
     */
    fun expression(code: String): ParticleGroup {
        lintGetters(code)
        emit(AnimInstruction.Expression(cursorTicks, code))
        return this
    }

    /**
     * 运行时热更程序变量（对已激活程序生效）：value 为公式字符串。
     * 例：`group.setVariableLive("rad", "3 + sin(t * 0.2)")`
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
