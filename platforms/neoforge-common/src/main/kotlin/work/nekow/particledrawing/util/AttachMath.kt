package work.nekow.particledrawing.util

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.math.rotateEntityOffset

/**
 * 实体锚点的位置求解。服务端算权威位置与可见性、客户端算本地渲染位置，两端共用这一份约定。
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
     *
     * @param entityPos 实体位置
     * @param yawDeg 实体 yaw（度，MC 原始值）
     * @param pitchDeg 实体 pitch（度，MC 原始值）
     * @param offset 相对偏移
     * @param local true 时偏移按实体朝向旋转后叠加，false 时直接在世界空间叠加
     */
    @JvmStatic
    fun resolve(entityPos: Vec3, yawDeg: Float, pitchDeg: Float, offset: Vec3, local: Boolean): Vec3 {
        if (!local) return entityPos.add(offset)
        return rotateEntityOffset(yawDeg, pitchDeg, offset.x, offset.y, offset.z) { x, y, z ->
            entityPos.add(x.toDouble(), y.toDouble(), z.toDouble())
        }
    }

    /**
     * 逐粒子同步用的原地版本，结果写进 [out] 的前三个元素。
     */
    @JvmStatic
    fun resolveInto(
        ex: Double, ey: Double, ez: Double,
        yawDeg: Float, pitchDeg: Float,
        ox: Double, oy: Double, oz: Double,
        local: Boolean,
        out: DoubleArray,
    ) {
        if (!local) {
            out[0] = ex + ox; out[1] = ey + oy; out[2] = ez + oz
            return
        }
        rotateEntityOffset(yawDeg, pitchDeg, ox, oy, oz) { x, y, z ->
            out[0] = ex + x
            out[1] = ey + y
            out[2] = ez + z
        }
    }
}
