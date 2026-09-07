package work.nekow.particledrawing.api

import net.minecraft.world.phys.Vec3

// 特效播放锚点：决定 .pdrawc 动画播放期间「原点 + 朝向」如何随时间变化。
// 客户端播放器输出相对锚点的本地坐标，渲染层每 game tick 用当前锚点变换映射到世界坐标，再按 partialTick 插值。
// Fixed=固定坐标；Entity=跟随实体（朝向 = yaw/pitch）；Movable=服务端每 tick 更新，朝向由速度推导。
sealed class Anchor {
    /** 固定世界坐标锚点（一次性命中/施法特效）。 */
    class Fixed(val pos: Vec3) : Anchor()

    /** 实体引用锚点：客户端按实体 id 本地解析位置与朝向（玩家/法杖等真实实体）。 */
    class Entity(val entityId: Int, val offset: Vec3 = Vec3.ZERO) : Anchor()

    /**
     * 可移动锚点：服务端每 tick 下发位置 + 速度，客户端按速度方向推导朝向。
     * 用于「不是实体、只有 pos+velocity」的投射物（如魔法模组的追踪弹）。
     */
    class Movable(
        val pos: Vec3,
        val velocity: Vec3,
        val orient: Orient = Orient.VELOCITY,
    ) : Anchor()
}

/** 可移动锚点的朝向模式。 */
enum class Orient {
    /** 朝向 = 速度方向（由 velocity 推 yaw/pitch，追踪转向时跟随运动方向）。 */
    VELOCITY,

    /** 保持世界朝向（不随运动转向，如竖直魔法阵/光圈）。 */
    WORLD,
}