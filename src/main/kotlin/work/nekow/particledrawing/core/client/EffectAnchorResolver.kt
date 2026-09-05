package work.nekow.particledrawing.core.client

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf
import org.joml.Vector3f
import work.nekow.particledrawing.api.Anchor
import work.nekow.particledrawing.api.Orient
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * 客户端锚点解析器：维护「位置 + 旋转四元数 + 整体缩放」，把播放器输出的
 * 本地坐标映射为世界坐标。
 *
 * - [Anchor.Fixed]：恒定位置，单位旋转。
 * - [Anchor.Entity]：每个 game tick 调用 [resolveEntity] 从实体取位置与 yaw/pitch。
 * - [Anchor.Movable]：[updateMovable] 由锚点更新包驱动，朝向由速度方向推导（或世界朝向）。
 *
 * 旋转约定与 MC 视角一致：look 向量 = RotY(-yaw) · RotX(pitch) · (0,0,1)。
 */
internal class EffectAnchorResolver(
    private val anchorKind: Anchor,
    private val scale: Float,
) {
    private var pos = Vec3.ZERO
    private var quat = Quaternionf()
    private var orientation = Orient.VELOCITY
    private var velocity = Vec3.ZERO

    init {
        when (anchorKind) {
            is Anchor.Fixed -> {
                pos = anchorKind.pos
                quat.identity()
            }
            is Anchor.Entity -> {
                pos = Vec3.ZERO // 由 resolveEntity 在首个 tick 前填充
                quat.identity()
            }
            is Anchor.Movable -> {
                pos = anchorKind.pos
                velocity = anchorKind.velocity
                orientation = anchorKind.orient
                recomputeFromVelocity()
            }
        }
    }

    /** 更新可移动锚点（位置 + 速度 → 朝向）。 */
    fun updateMovable(newPos: Vec3, newVel: Vec3) {
        pos = newPos
        velocity = newVel
        recomputeFromVelocity()
    }

    /** 实体锚点：每 tick 从客户端世界解析实体位置与朝向；实体缺失时保持上次姿态。 */
    fun resolveEntity(level: ClientLevel) {
        val anchor = anchorKind as? Anchor.Entity ?: return
        val entity = level.getEntity(anchor.entityId) ?: return
        pos = entity.position().add(anchor.offset)
        quat = Quaternionf().rotationYXZ(
            -Math.toRadians(entity.yRot.toDouble()).toFloat(),
            Math.toRadians(entity.xRot.toDouble()).toFloat(),
            0f,
        )
    }

    /** 把本地坐标（相对锚点、编辑器单位）映射为世界坐标。 */
    fun apply(local: Vec3): Vec3 {
        val v = Vector3f(local.x.toFloat(), local.y.toFloat(), local.z.toFloat()).rotate(quat)
        return Vec3(pos.x + v.x * scale, pos.y + v.y * scale, pos.z + v.z * scale)
    }

    private fun recomputeFromVelocity() {
        if (orientation == Orient.WORLD) {
            quat.identity()
            return
        }
        val yp = yawPitchDegrees(velocity) ?: return // 速度过小：保持上次朝向
        quat = Quaternionf().rotationYXZ(-Math.toRadians(yp[0]).toFloat(), Math.toRadians(yp[1]).toFloat(), 0f)
    }

    companion object {
        /**
         * 速度向量 → [yawDeg, pitchDeg]（与 MC look 向量约定一致）。
         * 速度近似为零时返回 null（调用方保持原朝向，避免翻转）。
         */
        @JvmStatic
        fun yawPitchDegrees(velocity: Vec3): DoubleArray? {
            val horiz = sqrt(velocity.x * velocity.x + velocity.z * velocity.z)
            val lenSq = velocity.x * velocity.x + velocity.y * velocity.y + velocity.z * velocity.z
            if (lenSq < 1e-12) return null
            val yaw = Math.toDegrees(atan2(-velocity.x, velocity.z))
            val pitch = Math.toDegrees(atan2(-velocity.y, horiz))
            return doubleArrayOf(yaw, pitch)
        }
    }
}