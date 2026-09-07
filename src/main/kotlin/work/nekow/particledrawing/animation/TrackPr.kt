package work.nekow.particledrawing.animation

/**
 * 分量轨道标识（对应网页编辑器轨道的 `pr` 字符串与 `.pdrawc` 里的分量枚举序号）。
 *
 * 声明顺序即 `.pdrawc` 二进制里的枚举序号（0..25），**只能追加、不能重排**；
 * 新增分量需与编辑器 `src/core/pdrawc.js` 的 `PR_ENUM` 保持逐位一致。
 *
 * @param key 规范化字符串形式（如 "pos.x" / "col.a" / "fov"）
 */
enum class TrackPr(val key: String) {
    POS_X("pos.x"), POS_Y("pos.y"), POS_Z("pos.z"),
    VEL_X("vel.x"), VEL_Y("vel.y"), VEL_Z("vel.z"),
    COL_R("col.r"), COL_G("col.g"), COL_B("col.b"), COL_A("col.a"),
    SCL_X("scl.x"), SCL_Y("scl.y"), SCL_Z("scl.z"),
    ROT_X("rot.x"), ROT_Y("rot.y"), ROT_Z("rot.z"),
    SPIN_X("spin.x"), SPIN_Y("spin.y"), SPIN_Z("spin.z"),
    CENTER_X("center.x"), CENTER_Y("center.y"), CENTER_Z("center.z"),
    FOV("fov"),
    TARGET_X("target.x"), TARGET_Y("target.y"), TARGET_Z("target.z");

    /** 属性名（"pos" / "col" / "fov" ...）。 */
    val prop: String get() = key.substringBefore('.')

    /** 分量键（"x" / "y" / "z" / "r" ...；标量 [FOV] 为 null）。 */
    val comp: String? get() = key.substringAfter('.', "").ifEmpty { null }

    companion object {
        /** 按 `.pdrawc` 枚举序号取分量；越界返回 null。 */
        fun fromOrdinal(ordinal: Int): TrackPr? = TrackPr.entries.getOrNull(ordinal)

        /** 按 (属性, 分量) 取分量；找不到返回 null（[comp] 为空时按标量属性，如 "fov"）。 */
        fun of(prop: String, comp: String?): TrackPr? {
            val key = if (comp.isNullOrEmpty()) prop else "$prop.$comp"
            return TrackPr.entries.find { it.key == key }
        }
    }
}