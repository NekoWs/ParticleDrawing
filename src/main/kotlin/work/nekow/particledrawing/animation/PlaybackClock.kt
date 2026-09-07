package work.nekow.particledrawing.animation

// 特效播放时钟：替代旧「进度 = gameTime - startGameTick」固定映射，支持指定起点、暂停、变速与任意 seek。
// 服务端持参考时钟用于重发/清理；客户端每 game tick 推进并按 floor(position) 定位时间轴；两端同起点/速度/播放态确定性推进。
class PlaybackClock(
    /** 时间轴位置（tick，可小数）。 */
    var position: Double = 0.0,
    /** 是否在推进（false = 暂停）。 */
    var playing: Boolean = true,
    /** 倍速（每 tick 推进的 tick 数）。 */
    var speed: Double = 1.0,
) {
    fun advance() {
        if (playing) position += speed
    }
}