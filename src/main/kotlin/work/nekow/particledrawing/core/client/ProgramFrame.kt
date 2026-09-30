package work.nekow.particledrawing.core.client

import net.minecraft.world.phys.Vec3

// 结构化糖指令的帧计算：组级倍率对「粒子到轴心的距离」的作用，以及旋转指令的累加记账。
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
