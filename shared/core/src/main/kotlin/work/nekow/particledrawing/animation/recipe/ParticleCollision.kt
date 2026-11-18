package work.nekow.particledrawing.animation.recipe

/**
 * 粒子与世界的碰撞方式。
 *
 * 公式能做任意数学，但拿不到方块信息；碰撞因此单独成一项配置，由平台层提供世界查询。
 */
enum class RecipeCollision {
    /** 不碰撞，粒子按速度自由穿过。 */
    NONE,

    /** 与实体方块碰撞：落地、撞墙、弹回。 */
    SOLID,

    /** 与液体碰撞：雨水落进水面后停下或溅起。 */
    LIQUID,
}

/** 世界查询；由平台层按当前维度实现，核心层只声明需要什么。 */
interface ParticleWorld {
    fun isSolid(x: Double, y: Double, z: Double): Boolean

    fun isLiquid(x: Double, y: Double, z: Double): Boolean
}

/**
 * 碰撞判定与响应。纯数值运算，不依赖任何游戏类型，便于逐条断言。
 */
object CollisionMath {

    /** 速度低于这个值视为停下，避免在地面上无限抖动。 */
    const val REST_VELOCITY = 0.02

    /** 位置缓冲的下标：x/y/z 位置在前，vx/vy/vz 速度在后。 */
    const val POS_X = 0
    const val POS_Y = 1
    const val POS_Z = 2
    const val VEL_X = 3
    const val VEL_Y = 4
    const val VEL_Z = 5

    fun blocks(mode: RecipeCollision, world: ParticleWorld, x: Double, y: Double, z: Double): Boolean =
        when (mode) {
            RecipeCollision.NONE -> false
            RecipeCollision.SOLID -> world.isSolid(x, y, z)
            RecipeCollision.LIQUID -> world.isLiquid(x, y, z)
        }

    /**
     * 若本步走进了方块，把位置回退到能站住的那个轴并反射该轴速度。
     *
     * 优先级是竖直优先：先试「只回退 y」，能站住就按地面处理，水平速度乘以摩擦；
     * 站不住再依次试 x、z，对应撞墙；三轴都不行就整体回退并整体反弹。
     *
     * [state] 依次是 x、y、z、vx、vy、vz，原地修改。[oldX]/[oldY]/[oldZ] 是本步之前的位置。
     * 返回是否真的发生了碰撞响应。
     */
    fun resolve(
        mode: RecipeCollision,
        world: ParticleWorld,
        oldX: Double,
        oldY: Double,
        oldZ: Double,
        state: DoubleArray,
        bounce: Double,
        friction: Double,
    ): Boolean {
        if (mode == RecipeCollision.NONE) return false
        val x = state[POS_X]
        val y = state[POS_Y]
        val z = state[POS_Z]
        if (!blocks(mode, world, x, y, z)) return false

        val keepBounce = bounce.coerceIn(0.0, 1.0)
        val keepFriction = friction.coerceIn(0.0, 1.0)

        if (!blocks(mode, world, x, oldY, z)) {
            state[POS_Y] = oldY
            reflect(state, VEL_Y, keepBounce)
            state[VEL_X] *= keepFriction
            state[VEL_Z] *= keepFriction
            return true
        }
        if (!blocks(mode, world, oldX, y, z)) {
            state[POS_X] = oldX
            reflect(state, VEL_X, keepBounce)
            state[VEL_Y] *= keepFriction
            state[VEL_Z] *= keepFriction
            return true
        }
        if (!blocks(mode, world, oldX, oldY, z)) {
            state[POS_Z] = oldZ
            reflect(state, VEL_Z, keepBounce)
            state[VEL_X] *= keepFriction
            state[VEL_Y] *= keepFriction
            return true
        }

        state[POS_X] = oldX
        state[POS_Y] = oldY
        state[POS_Z] = oldZ
        reflect(state, VEL_X, keepBounce)
        reflect(state, VEL_Y, keepBounce)
        reflect(state, VEL_Z, keepBounce)
        return true
    }

    private fun reflect(state: DoubleArray, axis: Int, bounce: Double) {
        val speed = state[axis]
        state[axis] = if (kotlin.math.abs(speed) < REST_VELOCITY) 0.0 else -speed * bounce
    }
}
