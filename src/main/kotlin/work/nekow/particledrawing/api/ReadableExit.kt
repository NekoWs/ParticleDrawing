package work.nekow.particledrawing.api

import work.nekow.particledrawing.core.server.AnimationScheduler

// 语义化退场：逐个成员做片元尺寸收束，不缩组、不改变运动；可选完成后清理。

/** 退场状态，供调试/状态门面使用。 */
enum class ExitState {
    NOT_STARTED,
    RUNNING,
    COMPLETED,
}

/** 粒子尺寸通道。 */
class ParticleSizeExit internal constructor(private val exit: ExitSpec) {

    /**
     * 在 [over] 内把每个成员的片元尺寸缓动到 [value]（编辑器单位；0 = 收束）。
     *
     * 目标在缓动结束时落地；只作用于成员自身，不改变组轴心、速度或位置。
     */
    fun to(value: Double, over: Time, easing: Ease = Ease.LINEAR): ExitSpec {
        require(value.isFinite() && value >= 0.0) { "退场尺寸必须是非负有限值" }
        over.requirePositive("退场时长")
        val ticks = over.requireWholeTicks("退场时长")
        for (handle in exit.handles) {
            handle.resize(value.toFloat(), ticks, easing.toEasingType())
        }
        exit.noteDuration(ticks)
        return exit
    }
}

/**
 * 批量/组退场声明。
 *
 * 同一入口只改片元尺寸；`preserveMotion` 是显式意图（resize 本身不动速度/位置），
 * `removeWhenComplete` 才在退场时长结束后清理成员。
 */
class ExitSpec internal constructor(
    private val manager: ParticleManager,
    internal val handles: List<ParticleHandle>,
    private val group: ParticleGroup?,
) {

    val particleSize: ParticleSizeExit = ParticleSizeExit(this)

    private var maxTicks: Int = 0
    private var removeAfter = false
    private var preserve = false
    private var applied = false
    private var completed = false

    /** 保留成员当前的运动（位置、速度、力）；只缩片元。 */
    fun preserveMotion(): ExitSpec = apply { preserve = true }

    /** 退场结束后移除成员；不调用则只做尺寸收束。 */
    fun removeWhenComplete(): ExitSpec = apply { removeAfter = true }

    val state: ExitState
        get() = when {
            !applied -> ExitState.NOT_STARTED
            completed -> ExitState.COMPLETED
            else -> ExitState.RUNNING
        }

    val preservesMotion: Boolean get() = preserve

    internal fun noteDuration(ticks: Int) {
        if (ticks > maxTicks) maxTicks = ticks
    }

    internal fun apply() {
        if (applied) return
        applied = true
        if (maxTicks <= 0) {
            completed = true
            if (removeAfter) removeNow()
            return
        }
        AnimationScheduler.schedule(manager.dimensionId, maxTicks) {
            completed = true
            if (removeAfter) removeNow()
        }
    }

    private fun removeNow() {
        val target = group
        if (target != null) {
            target.remove()
        } else {
            for (handle in handles) handle.remove()
        }
    }
}

/** Java 静态入口。 */
object Exits {
    @JvmStatic
    fun group(group: ParticleGroup): ExitSpec {
        val engine = group.manager.getEngine()
        val ids = engine.getGroup(group.id)?.memberIds() ?: emptyList()
        return ExitSpec(group.manager, ids.map { ParticleHandle(it, group.manager) }, group)
    }

    @JvmStatic
    fun batch(batch: ParticleBatch): ExitSpec =
        ExitSpec(batch.manager, batch.handles(), null)

    @JvmStatic
    fun apply(spec: ExitSpec) {
        spec.apply()
    }
}

/** 组退场；链式返回组本身，方便继续 `retire`/`remove`。 */
fun ParticleGroup.exit(block: ExitSpec.() -> Unit): ParticleGroup {
    val spec = Exits.group(this)
    spec.block()
    spec.apply()
    return this
}

/** 批量退场；链式返回批量本身。 */
fun ParticleBatch.exit(block: ExitSpec.() -> Unit): ParticleBatch {
    val spec = Exits.batch(this)
    spec.block()
    spec.apply()
    return this
}
