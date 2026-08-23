package work.nekow.particledrawing.api

/**
 * 被动输入 getter 的属性名常量与校验表。
 *
 * 公式内按需取值，无需预先声明：
 * - 实体：`get_entity_<prop>(<句柄>)`，句柄为 [work.nekow.particledrawing.api.ParticleGroup.bindInput]
 *   登记的名字（或注册序号数字）；属性表 [ENTITY_PROPS]；
 * - 世界：`get_world_<prop>()`；属性表 [WORLD_PROPS]。
 *
 * 未知名在客户端编译期 fail-fast（动画程序不生效并报错），服务端下发处另有 best-effort 预警日志。
 */
object ProgramInputs {
    // ---- 实体属性（getter = get_entity_<名>(句柄)）----
    /** 水平朝向角（MC yaw 原始度数，-180~180，0=+Z 南）。 */
    const val YAW = "yaw"
    /** 俯仰角（MC pitch 度数，-90~90）。 */
    const val PITCH = "pitch"
    /** 单位视线向量分量（由视角计算，与渲染一致）。 */
    const val DIR_X = "dirx"
    const val DIR_Y = "diry"
    const val DIR_Z = "dirz"
    /** 速度分量（block/tick）：客户端按相邻 tick 位置差分计算，首 tick 为 0。 */
    const val VEL_X = "vx"
    const val VEL_Y = "vy"
    const val VEL_Z = "vz"
    /** 当前生命值（仅 LivingEntity，其他实体恒 0）。 */
    const val HP = "hp"
    /** 最大生命值（仅 LivingEntity，其他实体恒 0）。 */
    const val HP_MAX = "hp_max"
    /** 着地（0/1）。 */
    const val GROUND = "ground"
    /** 潜行按键按下（0/1，shift 输入位而非姿态）。 */
    const val SNEAKING = "sneaking"
    /** 着火（0/1）。 */
    const val ON_FIRE = "on_fire"
    /** 游泳中（0/1）。 */
    const val SWIMMING = "swimming"
    /** 疾跑中（0/1）。 */
    const val SPRINTING = "sprinting"

    /** get_entity_* 允许的全部属性名（另含 x/y/z/pos/exists，见 expr 层词表）。 */
    val ENTITY_PROPS: List<String> = listOf(
        YAW, PITCH, DIR_X, DIR_Y, DIR_Z,
        VEL_X, VEL_Y, VEL_Z, HP, HP_MAX,
        GROUND, SNEAKING, ON_FIRE, SWIMMING, SPRINTING,
    )

    // ---- 世界属性（getter = get_world_<名>()）----
    /** 主世界时钟当日刻（0~23999）。 */
    const val DAY_TIME = "day_time"
    /** 主世界时钟总刻。 */
    const val GAME_TIME = "game_time"
    /** 降雨强度（0~1，含平滑过渡）。 */
    const val RAIN = "rain"
    /** 雷暴强度（0~1，含平滑过渡）。 */
    const val THUNDER = "thunder"
    /** 月相序号 0~7，按主世界时钟推算（每 24000 刻推进一相）。 */
    const val MOON_PHASE = "moon_phase"

    /** get_world_* 的全部属性名。 */
    val WORLD_PROPS: List<String> = listOf(DAY_TIME, GAME_TIME, RAIN, THUNDER, MOON_PHASE)
}
