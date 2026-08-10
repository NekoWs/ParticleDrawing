package work.nekow.particledrawing.api

import net.minecraft.world.phys.Vec3

/**
 * Describes a transform operation applied to a [ParticleGroup].
 * Immutable, designed for network serialization.
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
        TRANSLATE,
        ROTATE,
        RECOLOR,
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
