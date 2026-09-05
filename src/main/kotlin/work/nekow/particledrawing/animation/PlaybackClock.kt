package work.nekow.particledrawing.animation

/**
 * 特效播放时钟：替代旧「进度 = gameTime - startGameTick」的固定映射，
 * 支持从指定 tick 开始、暂停、变速与任意 seek。
 *
 * - 服务端持有一份「参考时钟」用于重发/清理（每个 server tick 调用 [advance]）。
 * - 客户端每个 game tick 调用 [advance]，再按 `floor(position)` 推进播放器时间轴。
 * - 双方以相同起点/速度/播放态确定性推进，因此不逐 tick 同步也保持一致；
 *   服务端权威模式下控制命令经 ClockSync 包广播，客户端本地模式则各自独立。
 */
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