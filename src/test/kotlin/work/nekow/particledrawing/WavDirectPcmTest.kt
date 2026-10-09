package work.nekow.particledrawing

import work.nekow.particledrawing.animation.AudioAsset
import work.nekow.particledrawing.animation.script.AudioValue
import work.nekow.particledrawing.animation.script.ScriptException
import work.nekow.particledrawing.animation.script.ScriptRuntime
import work.nekow.particledrawing.animation.script.ScriptWavePcm
import work.nekow.particledrawing.animation.script.parseProgram
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 脚本采样级 PCM：WAV 按字节直读、OGG 按窗口解码，插值与窗口峰值口径与编辑器一致。
 *
 * WAV 用例自己拼 RIFF 字节，OGG 用例大多注入合成样本；真实 OGG 用例缺原生库或夹具时跳过。
 */
class WavDirectPcmTest {

    // WAV 直读

    @Test
    fun `96kHz stereo wav reads header and both channels`() {
        // 96kHz/16bit/立体声：L = 0 / 0.25 / -1 / 0.125，R = 0.5 / -0.5 / 32767/32768 / 0
        val a = wavAsset(intArrayOf(0, 16384, 8192, -16384, -32768, 32767, 4096, 0), rate = 96000, channels = 2)

        assertEquals(96000, ScriptWavePcm.rate(a))
        assertEquals(2, ScriptWavePcm.channels(a))
        assertTrue(ScriptWavePcm.ready(a))

        assertEquals(0.0, ScriptWavePcm.sampleAt(a, msAt(0.0, 96000), 0), 1e-9)
        assertEquals(0.5, ScriptWavePcm.sampleAt(a, msAt(0.0, 96000), 1), 1e-9)
        assertEquals(0.25, ScriptWavePcm.sampleAt(a, msAt(1.0, 96000), 0), 1e-9)
        assertEquals(-0.5, ScriptWavePcm.sampleAt(a, msAt(1.0, 96000), 1), 1e-9)
        // 末帧前 1/4 帧处：在 -1（第 2 帧）与 0.125（第 3 帧）之间插值
        assertEquals(-0.15625, ScriptWavePcm.sampleAt(a, msAt(2.75, 96000), 0), 1e-9)
    }

    @Test
    fun `samples interpolate linearly between two frames`() {
        val a = wavAsset(intArrayOf(0, 16384), rate = 1000, channels = 1)   // 1 帧 = 1ms；0 → 0.5

        assertEquals(0.0, ScriptWavePcm.sampleAt(a, 0.0, 0), 1e-12)
        assertEquals(0.125, ScriptWavePcm.sampleAt(a, 0.25, 0), 1e-12)
        assertEquals(0.25, ScriptWavePcm.sampleAt(a, 0.5, 0), 1e-12)
        assertEquals(0.5, ScriptWavePcm.sampleAt(a, 1.0, 0), 1e-12)   // 末帧本身仍可读
    }

    @Test
    fun `time and channel out of range are silent`() {
        val a = wavAsset(intArrayOf(0, 16384, -32768, 4096), rate = 1000, channels = 1)

        assertEquals(0.0, ScriptWavePcm.sampleAt(a, -0.001, 0), 0.0)
        assertEquals(0.0, ScriptWavePcm.sampleAt(a, -1000.0, 0), 0.0)
        // 采样下标超出末帧下标一律回 0
        assertEquals(0.0, ScriptWavePcm.sampleAt(a, msAt(3.5, 1000), 0), 0.0)
        assertEquals(0.0, ScriptWavePcm.sampleAt(a, msAt(4.0, 1000), 0), 0.0)
        assertEquals(0.0, ScriptWavePcm.sampleAt(a, Double.NaN, 0), 0.0)
        assertEquals(0.0, ScriptWavePcm.sampleAt(a, Double.POSITIVE_INFINITY, 0), 0.0)
        assertEquals(0.0, ScriptWavePcm.sampleAt(a, 0.0, 1), 0.0)    // 单声道文件没有 1 号声道
        assertEquals(0.0, ScriptWavePcm.sampleAt(a, 0.0, -1), 0.0)
    }

    @Test
    fun `peakAt uses half-open window and single point for short windows`() {
        // 1 帧 = 1ms：[0.25, -0.5, 0.125, 0.75]
        val a = wavAsset(intArrayOf(8192, -16384, 4096, 24576), rate = 1000, channels = 1)

        assertEquals(0.25, ScriptWavePcm.peakAt(a, 0.0, 1.0, 0), 1e-12)      // [0,1) 不含第 1 帧
        assertEquals(0.5, ScriptWavePcm.peakAt(a, 0.0, 2.0, 0), 1e-12)
        assertEquals(0.5, ScriptWavePcm.peakAt(a, 0.0, 3.0, 0), 1e-12)       // 右端开区间：第 3 帧不算
        assertEquals(0.75, ScriptWavePcm.peakAt(a, 0.0, 4.0, 0), 1e-12)
        assertEquals(0.75, ScriptWavePcm.peakAt(a, 3.0, 1.0, 0), 1e-12)
        assertEquals(0.5, ScriptWavePcm.peakAt(a, 1.5, 0.4, 0), 1e-12)       // 窗口不足一个采样：取该点
        assertEquals(0.5, ScriptWavePcm.peakAt(a, 1.0, 0.0, 0), 1e-12)
        assertEquals(0.25, ScriptWavePcm.peakAt(a, 0.0, -1.0, 0), 1e-12)      // 负窗口按 0 长处理
        assertEquals(0.25, ScriptWavePcm.peakAt(a, -0.5, 1.0, 0), 1e-12)     // 窗口一半在音频外：只算重叠部分
        assertEquals(0.0, ScriptWavePcm.peakAt(a, -5.0, 1.0, 0), 0.0)        // 整个窗口在音频之外
        assertEquals(0.0, ScriptWavePcm.peakAt(a, 10.0, 1.0, 0), 0.0)
        assertEquals(0.0, ScriptWavePcm.peakAt(a, Double.NaN, 1.0, 0), 0.0)
        assertEquals(0.0, ScriptWavePcm.peakAt(a, 0.0, Double.NaN, 0), 0.0)
        assertEquals(0.0, ScriptWavePcm.peakAt(a, 0.0, 1.0, 2), 0.0)         // 声道越界
    }

    @Test
    fun `peakAt reads the requested channel`() {
        val a = wavAsset(intArrayOf(8192, -24576), rate = 1000, channels = 2)   // L=0.25，R=-0.75

        assertEquals(0.25, ScriptWavePcm.peakAt(a, 0.0, 1.0, 0), 1e-12)
        assertEquals(0.75, ScriptWavePcm.peakAt(a, 0.0, 1.0, 1), 1e-12)
    }

    @Test
    fun `sample and peak match the editor audio-pcm dataset`() {
        // 与编辑器同一组数据（1kHz，1 采样 = 1ms）：
        // L = 0 / 0.5 / -0.5 / 1 / -1，R = 0 / -1 / 0.25 / 0 / 0.75（±1 用 16bit 边界近似）
        val a = wavAsset(
            intArrayOf(0, 0, 16384, -32768, -16384, 8192, 32767, 0, -32768, 24576),
            rate = 1000,
            channels = 2,
        )

        assertEquals(0.25, ScriptWavePcm.sampleAt(a, 0.5, 0), 1e-12)
        assertEquals(0.0, ScriptWavePcm.sampleAt(a, 1.5, 0), 1e-12)
        assertEquals(-1.0, ScriptWavePcm.sampleAt(a, 1.0, 1), 1e-12)
        assertEquals(0.25, ScriptWavePcm.sampleAt(a, 2.0, 1), 1e-12)
        assertEquals(0.375, ScriptWavePcm.sampleAt(a, 3.5, 1), 1e-12)
        assertEquals(0.0, ScriptWavePcm.sampleAt(a, 99.0, 0), 0.0)
        assertEquals(1.0, ScriptWavePcm.peakAt(a, 0.0, 5.0, 0), 1e-4)
        assertEquals(0.5, ScriptWavePcm.peakAt(a, 1.0, 1.0, 0), 1e-12)
        assertEquals(0.25, ScriptWavePcm.peakAt(a, 2.0, 2.0, 1), 1e-12)
        assertEquals(0.5, ScriptWavePcm.peakAt(a, -0.5, 2.0, 0), 1e-12)
        assertEquals(0.0, ScriptWavePcm.peakAt(a, 0.0, 5.0, 9), 0.0)
    }

    @Test
    fun `float32 wav samples are read as-is`() {
        val payload = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putFloat(0.75f).putFloat(-0.25f).array()
        val a = audioAsset("f32", fmt = 1, data = wav(1000, 1, 32, true, payload))

        assertTrue(ScriptWavePcm.ready(a))
        assertEquals(0.75, ScriptWavePcm.sampleAt(a, 0.0, 0), 1e-9)
        assertEquals(0.25, ScriptWavePcm.sampleAt(a, 0.5, 0), 1e-9)
        assertEquals(-0.25, ScriptWavePcm.sampleAt(a, 1.0, 0), 1e-9)
    }

    @Test
    fun `wav without readable pcm is not ready`() {
        // 24bit 整数与 8bit 不在直读口径内
        val pcm24 = wav(1000, 1, 24, false, ByteArray(3))
        val pcm8 = wav(1000, 1, 8, false, ByteArray(1))
        val notRiff = ByteArray(64) { 7 }

        for (data in listOf(pcm24, pcm8, notRiff, ByteArray(4))) {
            val a = audioAsset("bad", fmt = 1, data = data)
            assertFalse(ScriptWavePcm.ready(a))
            assertEquals(0, ScriptWavePcm.rate(a))
            assertEquals(0, ScriptWavePcm.channels(a))
            assertEquals(0.0, ScriptWavePcm.sampleAt(a, 0.0, 0), 0.0)
            assertEquals(0.0, ScriptWavePcm.peakAt(a, 0.0, 1.0, 0), 0.0)
        }
    }

    // OGG 窗口解码

    @Test
    fun `ogg windows serve samples and reuse decoded windows`() {
        val frames = 25_000                       // 1 帧 = 1ms，窗口 8 秒 = 8000 帧
        val samples = FloatArray(frames) { ((it % 100) - 50) / 100f }
        val fake = FakeOgg(rate = 1000, channels = 1, samples = samples)
        val a = audioAsset("ogg1", fmt = 0, data = ByteArray(8) { 1 })

        withOggReader(fake) {
            assertEquals(1000, ScriptWavePcm.rate(a))
            assertEquals(1, ScriptWavePcm.channels(a))
            assertTrue(ScriptWavePcm.ready(a))

            assertEquals(samples[100].toDouble(), ScriptWavePcm.sampleAt(a, 100.0, 0), 1e-6)
            assertEquals(listOf(0), fake.reads)
            // 同一窗口内再取：不再解码
            ScriptWavePcm.sampleAt(a, 7399.0, 0)
            assertEquals(listOf(0), fake.reads)
            // 窗口末帧要插值到下一帧，顺带把第二个窗口也解出来
            ScriptWavePcm.sampleAt(a, 7999.0, 0)
            assertEquals(listOf(0, 8000), fake.reads)
            // 第二个窗口已就位：不再解码
            ScriptWavePcm.sampleAt(a, 8100.0, 0)
            assertEquals(listOf(0, 8000), fake.reads)
            // 回到第一个窗口：两个槽都还在，不重解
            ScriptWavePcm.sampleAt(a, 100.0, 0)
            assertEquals(listOf(0, 8000), fake.reads)
            // 第三个窗口：淘汰最久未用的
            ScriptWavePcm.sampleAt(a, 17_000.0, 0)
            assertEquals(listOf(0, 8000, 16_000), fake.reads)
            // 越界与非法声道回 0，也不触发新的解码
            assertEquals(0.0, ScriptWavePcm.sampleAt(a, 30_000.0, 0), 0.0)
            assertEquals(0.0, ScriptWavePcm.sampleAt(a, -1.0, 0), 0.0)
            assertEquals(0.0, ScriptWavePcm.sampleAt(a, 100.0, 1), 0.0)
            assertEquals(0.0, ScriptWavePcm.peakAt(a, 100.0, 1.0, 1), 0.0)
            assertEquals(listOf(0, 8000, 16_000), fake.reads)
        }
    }

    @Test
    fun `ogg peak spans two windows with one decode each`() {
        val frames = 25_000
        val samples = FloatArray(frames) { if (it == 9000) 0.9f else 0.1f }
        val fake = FakeOgg(rate = 1000, channels = 1, samples = samples)
        val a = audioAsset("ogg2", fmt = 0, data = ByteArray(8) { 1 })

        withOggReader(fake) {
            // 窗口 [7999, 9999) 跨第 0/1 两个解码窗口，各解一次
            assertEquals(0.9, ScriptWavePcm.peakAt(a, 7999.0, 2000.0, 0), 1e-6)
            assertEquals(listOf(0, 8000), fake.reads)
        }
    }

    @Test
    fun `ogg that cannot be opened stays not ready`() {
        val a = audioAsset("ogg3", fmt = 0, data = ByteArray(4))

        withOggReader(NullOgg) {
            assertFalse(ScriptWavePcm.ready(a))
            assertEquals(0, ScriptWavePcm.rate(a))
            assertEquals(0, ScriptWavePcm.channels(a))
            assertEquals(0.0, ScriptWavePcm.sampleAt(a, 0.0, 0), 0.0)
            assertEquals(0.0, ScriptWavePcm.peakAt(a, 0.0, 1.0, 0), 0.0)
        }
    }

    // 脚本句柄

    @Test
    fun `script audio handle exposes wave members`() {
        // 96kHz 立体声 2 帧：L = 0.125 / 0.25，R = 0.5 / -0.5
        val a = wavAsset(intArrayOf(4096, 16384, 8192, -16384), rate = 96000, channels = 2, name = "bgm")

        val out = runAudioScript(
            "func process() { let a = this.get(\"bgm\"); " +
                "out = [a.sampleRate, a.channels, a.waveReady, a.sampleAt(0), a.sampleAt(0, 1), " +
                "a.peakAt(0, 0.005), a.peakAt(0, 0.005, 1)]; }",
            a,
        )
        assertEquals(96000.0, out[0])
        assertEquals(2.0, out[1])
        assertEquals(true, out[2])
        assertEquals(0.125, out[3])
        assertEquals(0.5, out[4])
        assertEquals(0.125, out[5])                 // [0, 0.005ms) 只覆盖第 0 帧，取左声道
        assertEquals(0.5, out[6])
    }

    @Test
    fun `script audio handle reports unready ogg as silent`() {
        val a = audioAsset("bgm", fmt = 0, data = ByteArray(4), name = "bgm")

        withOggReader(NullOgg) {
            // 未就绪时按静音回 0、不报错，声道号不做范围校验
            val out = runAudioScript(
                "func process() { let a = this.get(\"bgm\"); " +
                    "out = [a.sampleRate, a.channels, a.waveReady, a.sampleAt(0), a.sampleAt(0, 5), a.peakAt(0, 10)]; }",
                a,
            )
            assertEquals(0.0, out[0])
            assertEquals(0.0, out[1])
            assertEquals(false, out[2])
            assertEquals(0.0, out[3])
            assertEquals(0.0, out[4])
            assertEquals(0.0, out[5])
        }
    }

    @Test
    fun `script audio wave member errors match editor`() {
        val a = wavAsset(intArrayOf(0, 16384), rate = 96000, channels = 2, name = "bgm")

        val channel = assertFailsWith<ScriptException> {
            runAudioScript("func process() { let a = this.get(\"bgm\"); out = a.sampleAt(0, 5); }", a)
        }
        assertTrue(channel.message!!.contains("channel 5 out of range (this asset has 2)"), channel.message!!)

        val negative = assertFailsWith<ScriptException> {
            runAudioScript("func process() { let a = this.get(\"bgm\"); out = a.sampleAt(0, -1); }", a)
        }
        assertTrue(negative.message!!.contains("requires a channel index >= 0"), negative.message!!)

        val fractional = assertFailsWith<ScriptException> {
            runAudioScript("func process() { let a = this.get(\"bgm\"); out = a.sampleAt(0, 1.5); }", a)
        }
        assertTrue(fractional.message!!.contains("requires a channel index >= 0"), fractional.message!!)

        val notNum = assertFailsWith<ScriptException> {
            runAudioScript("func process() { let a = this.get(\"bgm\"); out = a.sampleAt(\"x\"); }", a)
        }
        assertTrue(notNum.message!!.contains("sampleAt requires a num, got string"), notNum.message!!)

        val zeroWindow = assertFailsWith<ScriptException> {
            runAudioScript("func process() { let a = this.get(\"bgm\"); out = a.peakAt(0, 0); }", a)
        }
        assertTrue(zeroWindow.message!!.contains("peakAt requires windowMs > 0"), zeroWindow.message!!)

        val windowNotNum = assertFailsWith<ScriptException> {
            runAudioScript("func process() { let a = this.get(\"bgm\"); out = a.peakAt(0, \"x\"); }", a)
        }
        assertTrue(windowNotNum.message!!.contains("peakAt requires a num for windowMs"), windowNotNum.message!!)

        val argCount = assertFailsWith<ScriptException> {
            runAudioScript("func process() { let a = this.get(\"bgm\"); out = a.sampleAt(); }", a)
        }
        assertTrue(argCount.message!!.contains("'sampleAt' expects 1 or 2 arguments"), argCount.message!!)

        val peakArgCount = assertFailsWith<ScriptException> {
            runAudioScript("func process() { let a = this.get(\"bgm\"); out = a.peakAt(0); }", a)
        }
        assertTrue(peakArgCount.message!!.contains("'peakAt' expects 2 or 3 arguments"), peakArgCount.message!!)
    }

    @Test
    fun `real ogg decodes through stb windows`() {
        // 夹具是 10 秒 44.1kHz 立体声正弦（左 440Hz/0.8，右 1000Hz/0.3），逐采样与解析值比对
        val data = javaClass.getResourceAsStream("/audio/sine-10s.ogg")?.readBytes()
        org.junit.Assume.assumeTrue("缺少 OGG 夹具", data != null)
        val a = audioAsset("ogg-real", fmt = 0, data = data!!, name = "ogg-real")

        // 没有 STB 原生库时跳过
        try {
            ScriptWavePcm.oggReader.info(a.data)
        } catch (e: LinkageError) {
            org.junit.Assume.assumeNoException("没有可用的 STB 原生库", e)
            return
        }

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

    // 造数据与运行脚本的脚手架

    /** 帧号换算成本地毫秒（与脚本 a.progress 同一坐标）。 */
    private fun msAt(frame: Double, rate: Int) = frame * 1000.0 / rate

    private fun wavAsset(
        interleaved: IntArray,
        rate: Int,
        channels: Int,
        name: String = "bgm",
    ) = audioAsset("wav1", fmt = 1, data = wav16(rate, channels, interleaved), name = name)

    private fun wav16(rate: Int, channels: Int, interleaved: IntArray): ByteArray {
        val payload = ByteBuffer.allocate(interleaved.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (v in interleaved) payload.putShort(v.toShort())
        return wav(rate, channels, 16, false, payload.array())
    }

    /** 拼一个最小 RIFF/WAVE：标准 16 字节 fmt 块 + data 块。 */
    private fun wav(rate: Int, channels: Int, bits: Int, float: Boolean, payload: ByteArray): ByteArray {
        val head = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        head.put("RIFF".toByteArray(Charsets.US_ASCII))
        head.putInt(36 + payload.size)
        head.put("WAVE".toByteArray(Charsets.US_ASCII))
        head.put("fmt ".toByteArray(Charsets.US_ASCII))
        head.putInt(16)
        head.putShort((if (float) 3 else 1).toShort())
        head.putShort(channels.toShort())
        head.putInt(rate)
        head.putInt(rate * channels * bits / 8)
        head.putShort((channels * bits / 8).toShort())
        head.putShort(bits.toShort())
        head.put("data".toByteArray(Charsets.US_ASCII))
        head.putInt(payload.size)
        return head.array() + payload
    }

    private fun audioAsset(id: String, fmt: Int, data: ByteArray, name: String = id) = AudioAsset(
        id = id, name = name, fmt = fmt, data = data,
        st = 0, durMs = 1000, hopCount = 1, bpm = 0.0, beatOffsetMs = 0.0, onsetMax = 1.0,
        beats = emptyList(),
        rms = shortArrayOf(0), peak = shortArrayOf(0), centroid = shortArrayOf(0),
        onset = byteArrayOf(0), rolloff = byteArrayOf(0), bands = ByteArray(16),
    )

    /** 合成 OGG 采样源：按请求窗口从整段样本里切，记录每次解码的窗口起点。 */
    private class FakeOgg(
        private val rate: Int,
        private val channels: Int,
        val samples: FloatArray,
    ) : ScriptWavePcm.OggReader {

        val reads = ArrayList<Int>()

        override fun info(data: ByteArray) = ScriptWavePcm.OggInfo(rate, channels, samples.size / channels)

        override fun read(data: ByteArray, startFrame: Int, frameCount: Int, channels: Int): FloatArray? {
            reads.add(startFrame)
            val from = startFrame * channels
            if (from >= samples.size) return null
            return samples.copyOfRange(from, minOf(samples.size, from + frameCount * channels))
        }
    }

    /** 打不开的 OGG 源。 */
    private object NullOgg : ScriptWavePcm.OggReader {
        override fun info(data: ByteArray): ScriptWavePcm.OggInfo? = null
        override fun read(data: ByteArray, startFrame: Int, frameCount: Int, channels: Int): FloatArray? = null
    }

    private fun <T> withOggReader(reader: ScriptWavePcm.OggReader, body: () -> T): T {
        val old = ScriptWavePcm.oggReader
        ScriptWavePcm.oggReader = reader
        try {
            return body()
        } finally {
            ScriptWavePcm.oggReader = old
        }
    }

    /** 跑一段只读音频句柄的 process，返回脚本里的全局 out。 */
    private fun runAudioScript(source: String, asset: AudioAsset): MutableList<*> {
        val program = parseProgram("let out = 0\n$source")
        val obj = ScriptRuntime.createObjectState(0)
        val ctx = ScriptRuntime.ScriptCtx(
            t = 0.0, duration = 100.0, vars = emptyMap(), particles = ArrayList(),
            spawn = { error("no spawn") },
            get = { name -> if (name == asset.name) AudioValue(asset, 0.0, true) else throw ScriptException("unknown asset") },
        )
        ScriptRuntime.runTopLevel(program, obj, ctx)
        ScriptRuntime.runProcessFrame(program, obj, ctx)
        return obj.globals["out"] as MutableList<*>
    }
}
