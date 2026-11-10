package work.nekow.particledrawing

import work.nekow.particledrawing.animation.AudioAsset
import work.nekow.particledrawing.animation.script.ScriptWavePcm
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.*

class StbOggIntegrationTest {
    @Test
    fun `real ogg decodes through stb windows`() {
        // 夹具是 10 秒 44.1kHz 立体声正弦（左 440Hz/0.8，右 1000Hz/0.3），逐采样与解析值比对
        val data = javaClass.getResourceAsStream("/audio/sine-10s.ogg")?.readBytes()
        requireNotNull(data) { "缺少 OGG 夹具" }
        val a = audioAsset("ogg-real", fmt = 0, data = data, name = "ogg-real")

        requireNotNull(ScriptWavePcm.oggReader.info(a.data)) { "宿主未提供可用 OGG 解码器" }

        assertEquals(44100, ScriptWavePcm.rate(a))
        assertEquals(2, ScriptWavePcm.channels(a))
        assertTrue(ScriptWavePcm.ready(a))

        // 150.7 / 2000ms 落在第 0 个窗口，9000.3 / 9950ms 落在第二个窗口
        for (base in listOf(150.7, 2000.0, 9000.3, 9950.0)) {
            for (ch in 0..1) {
                var err = 0.0
                for (i in 0 until 256) {
                    val ms = base + i * 1000.0 / 44100.0
                    err += abs(ScriptWavePcm.sampleAt(a, ms, ch) - sineAt(ms, ch))
                }
                assertTrue(err / 256 < 0.02, "base=$base ch=$ch 平均误差 ${err / 256}（错位一个采样会到 0.02 以上）")
            }
        }

        // 对齐：误差最小的整体位移应为 0
        var best = 0
        var bestErr = Double.MAX_VALUE
        for (d in -2..2) {
            var err = 0.0
            for (i in 0 until 512) {
                val ms = 9000.3 + i * 1000.0 / 44100.0
                err += abs(ScriptWavePcm.sampleAt(a, ms, 1) - sineAt(ms + d * 1000.0 / 44100.0, 1))
            }
            if (err < bestErr) {
                bestErr = err
                best = d
            }
        }
        assertEquals(0, best, "OGG 窗口应当从请求的采样点开始解码")

        // 末尾与越界：10 秒的文件在 10.1 秒处回 0；峰值受编码过冲影响，取 0.8 附近
        assertEquals(0.0, ScriptWavePcm.sampleAt(a, 10_100.0, 0), 0.0)
        assertEquals(0.8, ScriptWavePcm.peakAt(a, 2000.0, 20.0, 0), 0.05)
    }

    /** 夹具正弦的解析值：左 440Hz/0.8，右 1000Hz/0.3。 */
    private fun sineAt(ms: Double, ch: Int): Double =
        if (ch == 0) 0.8 * sin(2 * Math.PI * 440.0 * ms / 1000.0)
        else 0.3 * sin(2 * Math.PI * 1000.0 * ms / 1000.0)

    private fun audioAsset(id: String, fmt: Int, data: ByteArray, name: String = id) = AudioAsset(
        id = id, name = name, fmt = fmt, data = data,
        st = 0, durMs = 1000, hopCount = 1, bpm = 0.0, beatOffsetMs = 0.0, onsetMax = 1.0,
        beats = emptyList(),
        rms = shortArrayOf(0), peak = shortArrayOf(0), centroid = shortArrayOf(0),
        onset = byteArrayOf(0), rolloff = byteArrayOf(0), bands = ByteArray(16),
    )

}
