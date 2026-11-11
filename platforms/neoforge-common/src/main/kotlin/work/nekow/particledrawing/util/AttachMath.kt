package work.nekow.particledrawing.util

import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf
import org.joml.Vector3f

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
        val q = Quaternionf().rotationYXZ(
            -Math.toRadians(yawDeg.toDouble()).toFloat(),
            Math.toRadians(pitchDeg.toDouble()).toFloat(),
            0f,
        )
        val v = Vector3f(offset.x.toFloat(), offset.y.toFloat(), offset.z.toFloat()).rotate(q)
        return Vec3(entityPos.x + v.x, entityPos.y + v.y, entityPos.z + v.z)
    }

    /**
     * 逐粒子同步用的原地版本：不建 Vec3/Quaternionf，结果写进 [out] 的前三个元素。
     * 与 [resolve] 同一套朝向约定，只是把三角函数展开，省掉每颗每刻的对象分配。
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
        val yaw = -Math.toRadians(yawDeg.toDouble()).toFloat()
        val pitch = Math.toRadians(pitchDeg.toDouble()).toFloat()
        val cy = kotlin.math.cos(yaw); val sy = kotlin.math.sin(yaw)
        val cx = kotlin.math.cos(pitch); val sx = kotlin.math.sin(pitch)
        val fx = ox.toFloat(); val fy = oy.toFloat(); val fz = oz.toFloat()
        // 先 RotX(pitch) 再 RotY(yaw)，与 rotationYXZ 的乘法顺序一致
        val y1 = fy * cx - fz * sx
        val z1 = fy * sx + fz * cx
        out[0] = ex + (fx * cy + z1 * sy)
        out[1] = ey + y1
        out[2] = ez + (-fx * sy + z1 * cy)
    }
}
