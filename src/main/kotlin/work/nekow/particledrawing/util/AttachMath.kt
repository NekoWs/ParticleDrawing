package work.nekow.particledrawing.util

import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf
import org.joml.Vector3f

/**
 * 实体锚点的位置求解。服务端（权威位置、可见性判定）与客户端（本地解析渲染位置）
 * 必须用同一约定，两处都调这里。
 *
 * 朝向约定与 [work.nekow.particledrawing.core.client.EffectAnchorResolver] 一致：
 * look = RotY(-yaw)·RotX(pitch)·(0,0,1)，即 MC 视角朝向。
 */
object AttachMath {

    private const val NO_ENTITY = -1

    /** 「未挂载」的实体 id 哨兵值。 */
    @JvmStatic
    fun noEntity(): Int = NO_ENTITY

    /**
     * 求锚点世界坐标。
     * @param entityPos 实体位置
     * @param yawDeg 实体 yaw（度，MC 原始值）
     * @param pitchDeg 实体 pitch（度，MC 原始值）
     * @param offset 相对偏移
     * @param local true = 偏移按实体朝向旋转后再叠加；false = 世界空间直接叠加
     */
    @JvmStatic
    fun resolve(entityPos: Vec3, yawDeg: Float, pitchDeg: Float, offset: Vec3, local: Boolean): Vec3 {
        if (!local) return entityPos.add(offset)
        val q = Quaternionf().rotationYXZ(
            -Math.toRadians(yawDeg.toDouble()).toFloat(),
            Math.toRadians(pitchDeg.toDouble()).toFloat(),
            0f,
        )
        val v = Vector3f(offset.x.toFloat(), offset.y.toFloat(), offset.z.toFloat()).rotate(q)
        return Vec3(entityPos.x + v.x, entityPos.y + v.y, entityPos.z + v.z)
    }
}
