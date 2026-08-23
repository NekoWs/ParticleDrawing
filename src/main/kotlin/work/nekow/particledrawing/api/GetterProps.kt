package work.nekow.particledrawing.api

/**
 * 实体属性枚举：`get_entity_<wire>(<句柄>)` 的封闭词表。
 *
 * - [wire] 是线上/公式里的实际名字（重写器按它识别，客户端按它采样）；
 * - [X]/[Y]/[Z]/[POS]/[EXISTS] 为基础项；[POS] 仅支持
 *   `[x,y,z] = get_entity_pos(h)` 独占赋值形态（编译期展开为三分量）；
 * - 实体缺失时所有属性读 0（[EXISTS] 同样为 0）。
 */
enum class EntityProp(val wire: String) {
    X("x"), Y("y"), Z("z"),
    POS("pos"),
    EXISTS("exists"),
    /** 水平朝向角（MC yaw 原始度数，-180~180，0=+Z 南）。 */
    YAW("yaw"),
    /** 俯仰角（MC pitch 度数，-90~90）。 */
    PITCH("pitch"),
    /** 单位视线向量分量（由视角计算，与渲染一致）。 */
    DIR_X("dirx"), DIR_Y("diry"), DIR_Z("dirz"),
    /** 速度分量（block/tick）：客户端按相邻 tick 位置差分计算，首 tick 为 0。 */
    VEL_X("vx"), VEL_Y("vy"), VEL_Z("vz"),
    /** 当前生命值（仅 LivingEntity，其他实体恒 0）。 */
    HP("hp"),
    /** 最大生命值（仅 LivingEntity，其他实体恒 0）。 */
    HP_MAX("hp_max"),
    /** 着地（0/1）。 */
    GROUND("ground"),
    /** 潜行按键按下（0/1，shift 输入位而非姿态）。 */
    SNEAKING("sneaking"),
    /** 着火（0/1）。 */
    ON_FIRE("on_fire"),
    /** 游泳中（0/1）。 */
    SWIMMING("swimming"),
    /** 疾跑中（0/1）。 */
    SPRINTING("sprinting");

    /** 生成可直接内插进公式的 getter 调用文本：`EntityProp.YAW.call("p") == "get_entity_yaw(p)"`。 */
    fun call(handle: String): String = "get_entity_$wire($handle)"

    companion object {
        private val BY_WIRE = entries.associateBy { it.wire }

        /** 按线上名反查；未知名返回 null（调用方 fail-fast）。 */
        fun fromWire(wire: String): EntityProp? = BY_WIRE[wire]
    }
}

/**
 * 世界环境属性枚举：`get_world_<wire>()` 的封闭词表。世界输入无需登记句柄。
 */
enum class WorldProp(val wire: String) {
    /** 主世界时钟当日刻（0~23999）。 */
    DAY_TIME("day_time"),
    /** 主世界时钟总刻。 */
    GAME_TIME("game_time"),
    /** 降雨强度（0~1，含平滑过渡）。 */
    RAIN("rain"),
    /** 雷暴强度（0~1，含平滑过渡）。 */
    THUNDER("thunder"),
    /** 月相序号 0~7，按主世界时钟推算（每 24000 刻推进一相）。 */
    MOON_PHASE("moon_phase");

    /** 生成可直接内插进公式的 getter 调用文本：`WorldProp.RAIN.call() == "get_world_rain()"`。 */
    fun call(): String = "get_world_$wire()"

    companion object {
        private val BY_WIRE = entries.associateBy { it.wire }

        /** 按线上名反查；未知名返回 null（调用方 fail-fast）。 */
        fun fromWire(wire: String): WorldProp? = BY_WIRE[wire]
    }
}
