package work.nekow.particledrawing.api

import net.minecraft.world.phys.Vec3

/**
 * 组形变槽位。位置使用世界坐标，yaw 和 pitch 使用度，size 使用粒子 scale 的单位。
 * 未指定的属性沿用粒子当前值。
 */
data class ParticleMorphSlot @JvmOverloads constructor(
    val position: Vec3,
    val yaw: Double? = null,
    val pitch: Double? = null,
    val size: Double? = null,
) {
    init {
        require(position.x.isFinite() && position.y.isFinite() && position.z.isFinite()) { "槽位坐标必须是有限数" }
        require(yaw?.isFinite() != false && pitch?.isFinite() != false) { "槽位朝向必须是有限数" }
        require(size == null || size in 0.0..Float.MAX_VALUE.toDouble()) { "槽位 size 必须是非负的有效尺寸" }
    }
}
