package work.nekow.particledrawing.render

/** 死亡时固定路径，剩余寿命只改变透明度。 */
internal class TrailAfterlife(private val lifetime: Int) {
    var age = 0
    var paused = false

    fun advance(): Boolean {
        if (!paused) age++
        return age < lifetime
    }

    fun opacity(partialTick: Float): Float =
        (1f - (age + partialTick.coerceIn(0f, 1f)) / lifetime).coerceIn(0f, 1f)
}
