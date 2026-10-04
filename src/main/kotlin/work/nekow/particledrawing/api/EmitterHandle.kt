package work.nekow.particledrawing.api

import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * 已声明发射器的句柄。发射点在客户端，服务端只维护这份声明：
 * [updateAnchor] / [spacing] / [interval] 走小包更新，[stop] 让客户端停止发射
 * （已经生成的粒子不受影响，各自走完寿命）。
 *
 * 声明链路的补发由 PD 负责：后进服、切维度、走进范围的玩家会自动收到声明，
 * 不会因为「发射器是在他来之前声明的」而看不到尾迹。
 */
class EmitterHandle internal constructor(
    /** 发射器 id（服务端与客户端一致）。 */
    val id: UUID,
    private val manager: ParticleManager,
) {

    private var currentAnchor: Anchor = Anchor.Fixed(Vec3.ZERO)
    private var mode: EmitMode = EmitMode.DISTANCE
    private var spacing: Double = 0.25
    private var intervalMs: Int = 50

    internal fun init(anchor: Anchor, mode: EmitMode, spacing: Double, intervalMs: Int): EmitterHandle {
        currentAnchor = anchor
        this.mode = mode
        this.spacing = spacing
        this.intervalMs = intervalMs
        return this
    }

    /** 当前锚点。 */
    fun anchor(): Anchor = currentAnchor

    /**
     * 挪动锚点（投射物每 tick 更新一次即可）。变更时才发包，客户端用插值后的位置推进里程，
     * 所以「服务端 20Hz 报位置、客户端 120Hz 铺粒子」是对的用法。
     */
    fun updateAnchor(anchor: Anchor): EmitterHandle {
        currentAnchor = anchor
        manager.updateEmitterAnchor(id, anchor)
        return this
    }

    /** [updateAnchor] 的分量重载（固定点锚点）。 */
    fun updateAnchor(x: Number, y: Number, z: Number): EmitterHandle =
        updateAnchor(Anchor.Fixed(Vec3(x.toDouble(), y.toDouble(), z.toDouble())))

    /** 改成按里程发射（每 [blocks] 格一颗）。 */
    fun spacing(blocks: Double): EmitterHandle {
        require(blocks > 0.0) { "spacing 必须为正（每多少格一颗）" }
        mode = EmitMode.DISTANCE
        spacing = blocks
        manager.updateEmitterParams(id, mode, spacing, intervalMs)
        return this
    }

    /** 改成按时间发射（每 [ticks] tick 一颗）。 */
    fun interval(ticks: Int): EmitterHandle {
        require(ticks > 0) { "interval 必须为正（每多少 tick 一颗）" }
        mode = EmitMode.TIME
        intervalMs = ticks * 50
        manager.updateEmitterParams(id, mode, spacing, intervalMs)
        return this
    }

    /** 改成按时间发射（每 [ms] 毫秒一颗）。 */
    fun intervalMs(ms: Int): EmitterHandle {
        require(ms > 0) { "intervalMs 必须为正（每多少毫秒一颗）" }
        mode = EmitMode.TIME
        intervalMs = ms
        manager.updateEmitterParams(id, mode, spacing, intervalMs)
        return this
    }

    /** 停止发射（已生成的粒子各自走完寿命）。 */
    fun stop() {
        manager.stopEmitter(id)
    }

    /** 这个发射器是否还在服务端登记着。 */
    fun isActive(): Boolean = manager.isEmitterActive(id)
}
