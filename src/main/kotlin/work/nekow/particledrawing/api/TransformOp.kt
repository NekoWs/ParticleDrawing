package work.nekow.particledrawing.api

import net.minecraft.world.phys.Vec3

/**
 * 描述应用于 [ParticleGroup] 的变换操作。
 * 不可变，为网络序列化而设计。
 */
@Suppress("unused")
class TransformOp private constructor(
    val type: Type,
    val delta: Vec3?,
    val axis: Vec3?,
    val radians: Double,
    val pivot: Vec3?,
    val targetColor: Color?,
    val targetScale: Float
) {
    enum class Type {
        /** 平移 */
        TRANSLATE,
        /** 绕轴旋转 */
        ROTATE,
        /** 重着色 */
        RECOLOR,
        /** 缩放 */
        SCALE
    }

    override fun toString() = "TransformOp{$type}"

    companion object {
        fun translate(delta: Vec3, pivot: Vec3?): TransformOp {
            return TransformOp(Type.TRANSLATE, delta, null, 0.0, pivot, null, 0f)
        }

        fun rotate(axis: Vec3, radians: Double, pivot: Vec3?): TransformOp {
            return TransformOp(Type.ROTATE, null, axis.normalize(), radians, pivot, null, 0f)
        }

        fun recolor(targetColor: Color): TransformOp {
            return TransformOp(Type.RECOLOR, null, null, 0.0, null, targetColor, 0f)
        }

        fun scale(targetScale: Float, pivot: Vec3?): TransformOp {
            return TransformOp(Type.SCALE, null, null, 0.0, pivot, null, targetScale)
        }
    }
}
