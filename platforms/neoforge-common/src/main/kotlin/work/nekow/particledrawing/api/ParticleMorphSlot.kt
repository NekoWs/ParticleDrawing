package work.nekow.particledrawing.api

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.i18n.translation

/**
 * 组形变槽位。默认使用世界坐标；[MorphOptions.space] 为 [work.nekow.particledrawing.animation.morph.MorphSpace.LOCAL]
 * 时坐标是相对组锚点的偏移。yaw 和 pitch 使用度，size 使用粒子 scale 的单位。
 * 未指定的属性沿用粒子当前值。
 */
data class ParticleMorphSlot @JvmOverloads constructor(
    val position: Vec3,
    val yaw: Double? = null,
    val pitch: Double? = null,
    val size: Double? = null,
) {
    init {
        requireLocalized(position.x.isFinite() && position.y.isFinite() && position.z.isFinite()) { translation("error.morph.slot_position") }
        requireLocalized(yaw?.isFinite() != false && pitch?.isFinite() != false) { translation("error.morph.slot_orientation") }
        requireLocalized(size == null || size in 0.0..Float.MAX_VALUE.toDouble()) { translation("error.morph.slot_size") }
    }
}
