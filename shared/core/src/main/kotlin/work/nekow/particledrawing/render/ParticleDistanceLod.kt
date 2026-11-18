package work.nekow.particledrawing.render

internal object ParticleDistanceLod {
    /** 同一颗粒子固定排序；画面每帧重算距离时不会随机闪烁。 */
    fun visible(distanceSquared: Double, fullDistance: Double, rank: Double): Boolean {
        if (fullDistance <= 0.0) return true
        val fullSquared = fullDistance * fullDistance
        if (distanceSquared <= fullSquared) return true
        return distanceSquared < 16.0 * fullSquared && rank < fullSquared / distanceSquared
    }
}
