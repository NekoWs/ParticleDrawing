package work.nekow.particledrawing.api

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.core.easing.EasingType
import work.nekow.particledrawing.core.server.AnimationScheduler
import java.util.UUID

/**
 * 一组可同时变换的粒子集合，也是编排式动画的基本单位。
 * 通过 [Draw] 工具或 [ParticleManager.createGroup] 创建。
 *
 * 所有动画方法支持链式时间线：[delay] 推进游标（累积、不清零），
 * 之后的每个动画方法都在各自游标时刻触发。
 *
 * ```kotlin
 * Draw.circle(manager, center, 3.0, 60)
 *     .fadeIn(10)                          // t=0    渐显
 *     .spin(Vec3(0, 1, 0), Math.PI / 40)   // t=0    开始持续旋转
 *     .delay(100)                          // 游标 → 100
 *     .stopContinuous()                    // t=100  停转
 *     .fadeOut(20)                         // t=100  渐隐销毁
 * ```
 */
@Suppress("unused")
class ParticleGroup(
    val id: UUID,
    var pivot: Vec3,
    internal val manager: ParticleManager
) {

    /** 时间线游标（tick）：delay 累积推进，后续动画方法都在该时刻触发。 */
    private var cursorTicks = 0

    /** 本组持续型动画（spin/pulse 无限模式）的令牌，stopContinuous 全部取消。 */
    private class ContinuousToken {
        @Volatile
        var cancelled = false
    }

    private val continuousTokens = ArrayDeque<ContinuousToken>()

    /* =====================================================================
     * 基础
     * ===================================================================== */

    /**
     * 设置后续变换的基准点。
     * @param pivot 新的基准点
     * @return 自身，支持链式调用
     */
    fun setPivot(pivot: Vec3): ParticleGroup {
        this.pivot = pivot
        val group = manager.getEngine().getGroup(id)
        group?.setPivot(pivot)
        return this
    }

    /** [setPivot] 的分量重载。 */
    fun setPivot(x: Number, y: Number, z: Number): ParticleGroup {
        return setPivot(Vec3(x.toDouble(), y.toDouble(), z.toDouble()))
    }

    /**
     * 向该组添加一个粒子。
     * @param handle 粒子的句柄，可为 null（粒子因达到上限被拒绝时）
     */
    fun add(handle: ParticleHandle?) {
        if (handle == null) return
        manager.getEngine().getGroup(id)?.addMember(handle.id)
    }

    /**
     * 获取组内成员数量。
     * @return 粒子数量
     */
    fun size(): Int = manager.getEngine().getGroup(id)?.size() ?: 0

    /* =====================================================================
     * 时序编排
     * ===================================================================== */

    /**
     * 把时间线游标向前推进 [ticks]：之后链式调用的动画方法都在新游标时刻触发。
     * 游标累积、不清零——连续两个动画共享同一时刻（如停转与淡出同刻）。
     * @param ticks 从当前游标再等待的 tick 数
     */
    fun delay(ticks: Int): ParticleGroup {
        cursorTicks += ticks.coerceAtLeast(0)
        return this
    }

    /** 在时间线游标时刻运行 [block]；游标为 0 则立即执行。 */
    private fun atCursor(block: () -> Unit) {
        if (cursorTicks > 0) AnimationScheduler.schedule(cursorTicks, block) else block()
    }

    /**
     * 定时销毁整组（含所有粒子）。常与 fadeIn 组合实现「出现-存在-消失」生命周期。
     * @param ticks 从**当前游标时刻**起再等多少 tick 销毁
     */
    fun destroyAfter(ticks: Int): ParticleGroup {
        atCursor {
            AnimationScheduler.schedule(ticks.coerceAtLeast(1)) {
                manager.getEngine().destroyGroup(id, manager.getPlayers())
            }
        }
        return this
    }

    /**
     * 停止本组全部持续型动画（无限模式的 spin / pulse）。
     * 受 [delay] 时序游标控制：`.spin(...).delay(100).stopContinuous()` 表示转 100 tick 后停。
     */
    fun stopContinuous(): ParticleGroup {
        atCursor {
            for (t in continuousTokens) t.cancelled = true
            continuousTokens.clear()
        }
        return this
    }

    private fun newToken(): ContinuousToken {
        val t = ContinuousToken()
        continuousTokens.add(t)
        return t
    }

    /* =====================================================================
     * 一次性缓动变换（受 delay 游标控制）
     * ===================================================================== */

    /**
     * 使用缓动平移组内所有粒子。
     * @param delta 平移向量
     * @param durationTicks 缓动持续时间 (tick)
     * @param easing 缓动曲线类型
     * @return 自身，支持链式调用
     */
    fun move(delta: Vec3, durationTicks: Int, easing: EasingType = EasingType.LINEAR): ParticleGroup {
        atCursor {
            val newPivot = pivot.add(delta)
            manager.getEngine().applyGroupTransform(
                id, TransformOp.Type.TRANSLATE,
                delta, Vec3.ZERO, 0.0, Color.WHITE, 0f, pivot,
                durationTicks, easing, manager.getPlayers()
            )
            pivot = newPivot
            manager.getEngine().getGroup(id)?.setPivot(newPivot)
        }
        return this
    }

    /** [move] 的分量重载。 */
    fun move(x: Number, y: Number, z: Number, durationTicks: Int, easing: EasingType = EasingType.LINEAR): ParticleGroup {
        return move(Vec3(x.toDouble(), y.toDouble(), z.toDouble()), durationTicks, easing)
    }

    /**
     * 绕基准点旋转组内所有粒子。
     * @param axis 归一化的旋转轴 (如 Vec3(0,1,0) 为 Y 轴)
     * @param radians 旋转角度 (弧度)
     * @param durationTicks 缓动持续时间 (tick)
     * @param easing 缓动曲线类型
     */
    fun rotate(axis: Vec3, radians: Double, durationTicks: Int, easing: EasingType = EasingType.LINEAR): ParticleGroup {
        atCursor {
            manager.getEngine().applyGroupTransform(
                id, TransformOp.Type.ROTATE,
                Vec3.ZERO, axis, radians, Color.WHITE, 0f, pivot,
                durationTicks, easing, manager.getPlayers()
            )
        }
        return this
    }

    /** [rotate] 的分量重载。 */
    fun rotate(x: Number, y: Number, z: Number, radians: Double, durationTicks: Int, easing: EasingType = EasingType.LINEAR): ParticleGroup {
        return rotate(Vec3(x.toDouble(), y.toDouble(), z.toDouble()), radians, durationTicks, easing)
    }

    /**
     * 使用缓动重新着色组内所有粒子。
     * @param targetColor 目标颜色
     * @param durationTicks 缓动持续时间 (tick)
     * @param easing 缓动曲线类型
     */
    fun recolor(targetColor: Color, durationTicks: Int, easing: EasingType = EasingType.LINEAR): ParticleGroup {
        atCursor {
            manager.getEngine().applyGroupTransform(
                id, TransformOp.Type.RECOLOR,
                Vec3.ZERO, Vec3.ZERO, 0.0, targetColor, 0f, null,
                durationTicks, easing, manager.getPlayers()
            )
        }
        return this
    }

    /**
     * 相对基准点等比缩放组内所有粒子：位置偏移与粒子自身大小同乘 [ratio]，
     * 「放大 2 倍」时半径与视觉大小一致翻倍。
     * @param ratio 缩放倍率（2f = 放大两倍，0.5f = 缩小一半）
     * @param durationTicks 缓动持续时间 (tick)；0 表示瞬时跳变
     * @param easing 缓动曲线类型
     */
    fun scale(ratio: Float, durationTicks: Int, easing: EasingType = EasingType.LINEAR): ParticleGroup {
        atCursor {
            manager.getEngine().applyGroupTransform(
                id, TransformOp.Type.SCALE,
                Vec3.ZERO, Vec3.ZERO, 0.0, Color.WHITE, ratio, pivot,
                durationTicks, easing, manager.getPlayers()
            )
        }
        return this
    }

    /* =====================================================================
     * 动画新特性：淡入淡出 / 持续旋转 / 路径运动 / 脉冲
     * ===================================================================== */

    /**
     * 淡入：整组透明度从 0 缓动到各自当前值。
     * 内部先瞬时置全透明，再由缓动恢复；配合 [destroyAfter] 可实现完整生命周期。
     * @param durationTicks 淡入持续 tick 数
     * @param easing 缓动曲线类型
     */
    fun fadeIn(durationTicks: Int, easing: EasingType = EasingType.EASE_OUT): ParticleGroup {
        atCursor {
            val engine = manager.getEngine()
            val players = manager.getPlayers()
            val groupData = engine.getGroup(id) ?: return@atCursor
            val originals = HashMap<UUID, Color>()
            for (memberId in groupData.memberIds()) {
                val p = engine.getParticle(memberId) ?: continue
                originals[memberId] = p.color()
                engine.updateParticle(memberId, p.position(), p.color().withAlpha(0f), p.scale(),
                    updatePos = false, updateColor = true, updateScale = false,
                    0, EasingType.LINEAR, players)
            }
            AnimationScheduler.schedule(1) {
                for ((memberId, c) in originals) {
                    val p = engine.getParticle(memberId) ?: continue
                    engine.updateParticle(memberId, p.position(), c, p.scale(),
                        updatePos = false, updateColor = true, updateScale = false,
                        durationTicks, easing, players)
                }
            }
        }
        return this
    }

    /**
     * 淡出：整组透明度缓动到 0。
     * @param durationTicks 淡出持续 tick 数
     * @param removeAfter 淡出结束后销毁整组
     * @param easing 缓动曲线类型
     */
    fun fadeOut(durationTicks: Int, removeAfter: Boolean = true, easing: EasingType = EasingType.EASE_IN): ParticleGroup {
        atCursor {
            val engine = manager.getEngine()
            val players = manager.getPlayers()
            val groupData = engine.getGroup(id) ?: return@atCursor
            for (memberId in groupData.memberIds()) {
                val p = engine.getParticle(memberId) ?: continue
                engine.updateParticle(memberId, p.position(), p.color().withAlpha(0f), p.scale(),
                    updatePos = false, updateColor = true, updateScale = false,
                    durationTicks, easing, players)
            }
            if (removeAfter) {
                AnimationScheduler.schedule(durationTicks + 1) {
                    stopContinuousQuietly()
                    engine.destroyGroup(id, players)
                }
            }
        }
        return this
    }

    /**
     * 持续旋转：组绕轴心以固定角速度旋转（服务端逐步驱动，客户端平滑插值）。
     * 与 [rotate] 的区别：rotate 是一次缓动到目标角度；spin 是匀速连续转动。
     *
     * @param axis 归一化旋转轴
     * @param radiansPerTick 每 tick 旋转的弧度
     * @param durationTicks 总时长；负数表示无限循环（用 [stopContinuous] 停止）
     */
    fun spin(axis: Vec3, radiansPerTick: Double, durationTicks: Int = -1,
             broadcastInterval: Int = DEFAULT_BROADCAST_INTERVAL): ParticleGroup {
        atCursor {
            val engine = manager.getEngine()
            val players = manager.getPlayers()
            val center = pivot
            val token = newToken()
            var elapsed = 0
            fun step() {
                if (token.cancelled) return
                val remaining = if (durationTicks < 0) broadcastInterval else durationTicks - elapsed
                if (remaining <= 0) {
                    token.cancelled = true
                    return
                }
                val stepTicks = remaining.coerceAtMost(broadcastInterval)
                engine.stepRotate(id, center, axis, radiansPerTick * stepTicks, stepTicks, players)
                elapsed += stepTicks
                if (durationTicks !in 0..elapsed) {
                    AnimationScheduler.schedule(stepTicks) { step() }
                } else {
                    token.cancelled = true
                }
            }
            AnimationScheduler.schedule(broadcastInterval) { step() }
        }
        return this
    }

    /**
     * 沿折线路径移动：从当前基准点出发依次经过 [points] 各点。
     * 服务端按弧长均匀采样、逐段广播位置缓动，[easing] 作用于整条路径的进度。
     *
     * @param points 途经点列表（不含起点）
     * @param durationTicks 走完全程的 tick 数
     * @param easing 路径进度的缓动曲线
     */
    fun movePath(points: List<Vec3>, durationTicks: Int, easing: EasingType = EasingType.LINEAR,
                 broadcastInterval: Int = DEFAULT_BROADCAST_INTERVAL): ParticleGroup {
        require(points.isNotEmpty()) { "movePath 至少需要一个途经点" }
        atCursor {
            val engine = manager.getEngine()
            val players = manager.getPlayers()
            val nodes = ArrayList<Vec3>(points.size + 1)
            nodes.add(pivot)
            nodes.addAll(points)

            // 弧长表：segLens[i] = nodes[i] -> nodes[i+1] 的长度
            val segLens = DoubleArray(nodes.size - 1)
            var total = 0.0
            for (i in segLens.indices) {
                segLens[i] = nodes[i].subtract(nodes[i + 1]).length()
                total += segLens[i]
            }
            if (total < 1e-6) return@atCursor

            val steps = (durationTicks / broadcastInterval.coerceAtLeast(1)).coerceAtLeast(1)
            val stepTicks = durationTicks / steps

            // 沿折线按弧长 s∈[0,total] 采样坐标
            fun sampleAt(s: Double): Vec3 {
                var rest = s.coerceIn(0.0, total)
                for (i in segLens.indices) {
                    if (rest <= segLens[i] || i == segLens.lastIndex) {
                        val t = if (segLens[i] < 1e-9) 0.0 else rest / segLens[i]
                        return nodes[i].lerp(nodes[i + 1], t.coerceIn(0.0, 1.0))
                    }
                    rest -= segLens[i]
                }
                return nodes.last()
            }

            var prevProgress = 0.0
            var done = 0
            fun step() {
                done++
                val progress = easing.evaluate((done.toFloat() / steps).coerceIn(0f, 1f)).toDouble()
                val from = sampleAt(total * prevProgress)
                val to = sampleAt(total * progress)
                prevProgress = progress
                engine.stepTranslate(id, to.subtract(from), stepTicks, players)
                pivot = to
                if (done < steps) AnimationScheduler.schedule(stepTicks) { step() }
            }
            AnimationScheduler.schedule(stepTicks) { step() }
        }
        return this
    }

    /**
     * 缩放脉冲：整组以当前大小为基准，在 1× 与 [peakRatio]× 之间往复呼吸。
     *
     * @param peakRatio 脉冲峰值倍率（2f = 呼吸到两倍大）
     * @param halfPeriodTicks 单程（去或回）时长
     * @param cycles 往复次数；负数表示无限循环（用 [stopContinuous] 停止）
     */
    fun pulse(peakRatio: Float, halfPeriodTicks: Int, cycles: Int = -1,
              easing: EasingType = EasingType.EASE_IN_OUT): ParticleGroup {
        atCursor {
            val engine = manager.getEngine()
            val groupData = engine.getGroup(id) ?: return@atCursor
            if (groupData.memberIds().isEmpty()) return@atCursor
            val token = newToken()
            var goingUp = true
            var completed = 0
            fun half() {
                if (token.cancelled) return
                // 倍率往返：上行 ×peak，下行 ×1/peak 回到基准（浮点往返误差可忽略）
                scale(if (goingUp) peakRatio else 1f / peakRatio, halfPeriodTicks, easing)
                goingUp = !goingUp
                if (!goingUp) completed++ // 回程结束记一次完整循环
                if (cycles > 0 && completed >= cycles) {
                    token.cancelled = true
                    return
                }
                AnimationScheduler.schedule(halfPeriodTicks) { half() }
            }
            AnimationScheduler.schedule(halfPeriodTicks) { half() }
        }
        return this
    }

    /**
     * 销毁整个粒子组及其所有粒子（同时停止本组的持续动画）。
     */
    fun remove() {
        stopContinuousQuietly()
        manager.getEngine().destroyGroup(id, manager.getPlayers())
    }

    private fun stopContinuousQuietly() {
        for (t in continuousTokens) t.cancelled = true
        continuousTokens.clear()
    }

    override fun toString() = "ParticleGroup{$id size=${size()}}"

    companion object {
        /** 持续动画的位置广播间隔（tick）：客户端在该时长内线性插值到下一位置。 */
        const val DEFAULT_BROADCAST_INTERVAL = 2
    }
}
