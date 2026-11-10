package work.nekow.particledrawing.animation

// 特效播放时钟：支持指定起点、暂停、变速与任意 seek。
// 服务端持参考时钟用于重发与清理；客户端每 game tick 推进并按 position 定位时间轴（1 tick = 50ms），两端同起点、同速度、同播放态。
class PlaybackClock(
    /** 时间轴位置（毫秒，可小数）。 */
    var position: Double = 0.0,
    /** 是否在推进（false = 暂停）。 */
    var playing: Boolean = true,
    /** 倍速（每 game tick 推进的毫秒数）。 */
    var speed: Double = 50.0,
) {
    fun advance() {
        if (playing) position += speed
    }
}