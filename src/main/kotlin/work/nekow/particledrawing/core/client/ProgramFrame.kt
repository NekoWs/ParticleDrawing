package work.nekow.particledrawing.core.client

import net.minecraft.world.phys.Vec3

// 结构化糖指令的帧计算：组级倍率对「粒子到轴心的距离」的作用、旋转/缩放指令的记账。
// 都是纯函数/纯状态，与渲染引擎解耦，便于单测。

/**
 * 组内偏移经组级倍率缩放后的结果：`ScaleBy` / `Pulse` 的倍率同时作用于
 * 「粒子到轴心的距离」与视觉尺寸，于是 `scale(3f)` 让球壳半径也变成 3 倍。
 */
internal fun radialRel(rel: Vec3, scaleMul: Float, pulseMul: Float): Vec3 =
    rel.scale((scaleMul * pulseMul).toDouble())

/**
 * 一条旋转指令的角度账本。
 *
 * 每帧只取「本次应达到的总角度」与「已写入角度」之差并叠加到 `rel` 上：
 * 于是多条旋转指令（无限 `spin` 与一次性 `rotate`）**互相叠加**而不是各自从快照重写、后者覆盖前者，
 * 一次性旋转的缓动也自然按每帧增量落地。
 */
internal class RotationSlot {

    private var applied = 0.0

    /** 取本次应叠加的角度（总角度 − 已写入角度）并记账。 */
    fun take(totalRadians: Double): Double {
        val delta = totalRadians - applied
        applied = totalRadians
        return delta
    }
}

/**
 * 一条缩放指令的倍率账本。
 *
 * **执行起点**捕获当时的合成倍率，之后按这条指令自己的缓动推进到终点：
 * - `scaleBy(ratio)`：终点 = 起点 × [ratio]（在当前倍率之上相乘，不重置已有倍率）；
 * - `scaleTo(target)`：终点 = [target] 本身（绝对目标）。
 *
 * 由此「先缩到 0.01、再 ×100」能回到 1；「从 1.5 倍接退场」以 1.5 为起点而不是先跳回 1；
 * 运行中追加一条缩放，则从那**一帧**的倍率为起点（帧级重定向）。
 */
internal class ScaleLedger {

    private var from = 1f
    private var captured = false

    /** 捕获执行起点的合成倍率；返回本次的终点（相对倍率按起点相乘）。 */
    fun begin(current: Float, ratio: Float, absolute: Boolean): Float {
        if (!captured) {
            from = current
            captured = true
        }
        return if (absolute) ratio else from * ratio
    }

    /** 本次进度 [k] ∈ [0,1] 对应的倍率。 */
    fun valueAt(target: Float, k: Float): Float = from + (target - from) * k
}

/**
 * 完成账本的合成：把「指令的终点」与「各变量缓动的终点」并成**最晚**那个时刻（毫秒）。
 *
 * 两块都要算：组动画可以只用变量缓动（表达式组的 `presence` 归零）、也可以只用有限指令
 * （`scaleTo` 退场），两者都没有终点时才算「这段编排没有跑完可言」。
 *
 * **表达式模式下只认表达式自己的有限时长**：那时糖指令一条都不会执行（解释权在表达式手里），
 * 拿一条不生效的有限指令当完成标记是假的；而 `expression(code, durationTicks)` 给的到期时刻
 * 是真正会发生的终点。
 *
 * @param instructionEndMs 糖指令的最晚终点；-1 = 没有
 * @param expressionMode 是否表达式组（那时改看 [expressionEndMs]）
 * @param expressionEndMs 表达式自己的到期时刻（[expression] 的有限时长）；-1 = 一直求值
 * @param varEaseEnds 每个活跃变量缓动的终点（还没开始的按「本 tick 开始」算）
 * @return 最晚终点；-1 = 这本账上没有能完成的事
 */
internal fun completionLedgerEndMs(
    instructionEndMs: Long,
    expressionMode: Boolean,
    expressionEndMs: Long,
    varEaseEnds: Collection<Long>,
): Long {
    var end = if (expressionMode) expressionEndMs else instructionEndMs
    for (easeEnd in varEaseEnds) {
        if (easeEnd > end) end = easeEnd
    }
    return end
}
