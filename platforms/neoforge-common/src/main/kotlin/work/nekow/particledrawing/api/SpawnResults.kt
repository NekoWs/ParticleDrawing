package work.nekow.particledrawing.api

/** 批量生成的单条结果；[index] 是原始输入下标，不是成功项压缩后的位置。 */
data class SpawnResult(
    val index: Int,
    val handle: ParticleHandle?,
    val rejection: SpawnRejection?,
) {
    val success: Boolean get() = handle != null
}

/** 容量拒绝原因；后续新增原因时保持枚举追加。 */
enum class SpawnRejection {
    /** 超过维度/每玩家粒子上限。 */
    CAPACITY,
}
