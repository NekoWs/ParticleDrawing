package work.nekow.particledrawing

import work.nekow.particledrawing.animation.AudioAsset
import work.nekow.particledrawing.core.client.AudioSink
import work.nekow.particledrawing.core.client.AudioStreamPlayer
import work.nekow.particledrawing.core.client.WavDecoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 播放管线的逐采样回归：合成 WAV 走 AudioStreamPlayer 的分块链路（解码 → 分块 → 排队 → 出队），
 * 假 sink 记下真正被消费掉的样本流，与源逐采样比对（位精确）。跳过/重复/错位一个采样、
 * 采样率被写成别的值、按毫秒逐块取整造成的累积漂移，都会立刻失败。
 *
 * 假 sink 按 OpenAL 的实测语义记账：AL_SAMPLE_OFFSET 相对「当前队列」计数（取回已播块后会减小），
 * alSourceStop 把采样偏移归零、再 play 从队列头开始。
 */
class AudioStreamPipelineTest {

    // —— 假 sink ——

    private class FakeSink(private val channels: Int) : AudioSink {

        val uploaded = ArrayList<ShortArray>()
        val uploadedRates = ArrayList<Int>()
        val uploadedFrames = ArrayList<Int>()
        val uploadedStereo = ArrayList<Boolean>()
        val preparedRates = ArrayList<Int>()
        /** 真正被消费掉的样本流（按队列顺序出）。 */
        val played = ArrayList<Short>()
        /** 队列空了但还有块没上传（生产跟不上）的时刻，记当时的已播帧数。 */
        val underruns = ArrayList<Int>()
        /** 每次 refill 之后队列里待播的帧数。 */
        val queuedFramesLog = ArrayList<Int>()

        private val data = HashMap<Int, ShortArray>()
        private val state = HashMap<Int, Int>()
        private val queue = HashMap<Int, ArrayDeque<Int>>()
        private val done = HashMap<Int, ArrayDeque<Int>>()
        private val inHead = HashMap<Int, Int>()
        private var next = 1
        private var totalUploaded = 0

        override fun hasContext(): Boolean = true

        override fun createSource(): Int = next++

        override fun prepareSource(source: Int, sampleRate: Int) {
            preparedRates.add(sampleRate)
        }

        override fun deleteSource(source: Int) {
            state.remove(source)
            queue.remove(source)
            done.remove(source)
        }

        override fun createBuffer(maxFrames: Int, channels: Int): Int = next++

        override fun deleteBuffer(buffer: Int) {
            data.remove(buffer)
        }

        override fun upload(buffer: Int, pcm: ShortArray, frames: Int, sampleRate: Int, stereo: Boolean) {
            val ch = if (stereo) 2 else 1
            data[buffer] = pcm.copyOf(frames * ch)
            uploaded.add(data.getValue(buffer))
            uploadedRates.add(sampleRate)
            uploadedFrames.add(frames)
            uploadedStereo.add(stereo)
            totalUploaded += frames
        }

        override fun queueBuffer(source: Int, buffer: Int) {
            queue.getOrPut(source) { ArrayDeque() }.addLast(buffer)
        }

        override fun unqueueBuffers(source: Int, count: Int): IntArray {
            val d = done.getOrPut(source) { ArrayDeque() }
            val out = ArrayList<Int>(count)
            repeat(count) { if (d.isNotEmpty()) out.add(d.removeFirst()) }
            return out.toIntArray()
        }

        override fun processedBuffers(source: Int): Int = done.getOrPut(source) { ArrayDeque() }.size

        override fun queuedBuffers(source: Int): Int = queue.getOrPut(source) { ArrayDeque() }.size

        override fun sampleOffset(source: Int): Int = inHead[source] ?: 0

        override fun isPlaying(source: Int): Boolean = state[source] == PLAYING

        override fun play(source: Int) {
            state[source] = PLAYING
        }

        override fun pause(source: Int) {
            state[source] = PAUSED
        }

        /** 与实测一致：stop 把采样偏移归零，再 play 从队列头开始。 */
        override fun stop(source: Int) {
            state[source] = STOPPED
            inHead[source] = 0
        }

        override fun setGain(source: Int, gain: Float) = Unit

        override fun setPitch(source: Int, pitch: Float) = Unit

        override fun setPan(source: Int, pan: Float) = Unit

        /** 时间前进 [frames] 帧：源在播就逐帧从队列头取，取空且还有块没上传才算欠载。 */
        fun advance(source: Int, frames: Int) {
            if (state[source] != PLAYING) return
            var left = frames
            val q = queue.getOrPut(source) { ArrayDeque() }
            val d = done.getOrPut(source) { ArrayDeque() }
            while (left > 0) {
                val head = q.firstOrNull()
                val playedFrames = played.size / channels
                if (head == null) {
                    state[source] = STOPPED
                    if (playedFrames < totalUploaded) underruns.add(playedFrames)
                    return
                }
                val samples = data[head] ?: ShortArray(0)
                val total = samples.size / channels
                val at = inHead[source] ?: 0
                val take = minOf(left, total - at)
                for (i in 0 until take * channels) played.add(samples[at * channels + i])
                left -= take
                if (at + take == total) {
                    inHead[source] = 0
                    q.removeFirst()
                    d.addLast(head)
                } else {
                    inHead[source] = at + take
                }
            }
        }

        fun queuedFrames(source: Int): Int {
            val q = queue.getOrPut(source) { ArrayDeque() }
            var n = 0
            for ((i, b) in q.withIndex()) {
                val total = (data[b]?.size ?: 0) / channels
                n += if (i == 0) total - (inHead[source] ?: 0) else total
            }
            return n
        }

        fun playedSamples(): ShortArray = ShortArray(played.size) { played[it] }

        companion object {
            const val PLAYING = 0
            const val PAUSED = 1
            const val STOPPED = 2
        }
    }

    private class Harness(private val rate: Int, seconds: Double, chunkMs: Int, channels: Int = 2) {
        val frames = (rate * seconds).toInt()
        val source = sourceFrames(rate, frames, channels)
        val sink = FakeSink(channels)
        val player = AudioStreamPlayer(asset("probe", extensibleWav(rate, channels, source)), chunkMs, sink)

        /** 按 20Hz（50ms 一 tick）驱动生产更新，中间让 sink 消费掉这 50ms 的音频。 */
        fun run(ticks: Int, seekAtTick: Int = -1, seekMs: Double = 0.0): Harness {
            for (t in 0 until ticks) {
                player.update(true, if (t == seekAtTick) seekMs else null, 1f, 0f, 1f)
                sink.queuedFramesLog.add(sink.queuedFrames(1))
                sink.advance(1, rate * 50 / 1000)
            }
            return this
        }
    }

    private fun sameSamples(expected: ShortArray, actual: ShortArray, what: String) {
        assertEquals(expected.size, actual.size, "$what：样本数不一致")
        for (i in expected.indices) {
            if (expected[i] != actual[i]) {
                throw AssertionError("$what：第 $i 个样本不一致，期望 ${expected[i]} 实际 ${actual[i]}")
            }
        }
    }

    // —— H1：采样率 ——

    @Test
    fun `sample rate from the file header reaches OpenAL verbatim`() {
        for (rate in listOf(192_000, 96_000, 48_000, 44_100)) {
            val h = Harness(rate, 1.07, 250).run(30)
            assertTrue(h.sink.uploadedRates.isNotEmpty(), "rate=$rate 没上传任何块")
            assertTrue(
                h.sink.uploadedRates.all { it == rate },
                "rate=$rate 被改成了 ${h.sink.uploadedRates}",
            )
            assertTrue(h.sink.preparedRates.all { it == rate })
            sameSamples(h.source, h.sink.playedSamples(), "rate=$rate 的播放流")
        }
    }

    @Test
    fun `mono assets are uploaded as mono and stay sample exact`() {
        val h = Harness(44_100, 1.07, 250, channels = 1).run(30)
        assertTrue(h.sink.uploadedStereo.all { !it }, "单声道被当成双声道上传")
        sameSamples(h.source, h.sink.playedSamples(), "单声道播放流")
    }

    // —— H2：分块边界 ——

    @Test
    fun `192k chunk stream is reproduced sample for sample`() {
        val h = Harness(192_000, 2.9, 250).run(70)
        sameSamples(h.source, h.sink.playedSamples(), "192k/250ms 播放流")
        assertTrue(h.sink.underruns.isEmpty(), "队列欠载：${h.sink.underruns}")
    }

    @Test
    fun `chunk size that does not divide the length stays sample exact`() {
        val h = Harness(192_000, 2.9, 137).run(70)
        sameSamples(h.source, h.sink.playedSamples(), "192k/137ms 播放流")
        assertTrue(h.sink.underruns.isEmpty(), "队列欠载：${h.sink.underruns}")
        // 每块按帧对齐（除末块），不是各自把毫秒取整后累积漂移
        val chunkFrames = 192_000 * 137 / 1000
        val full = h.sink.uploadedFrames.count { it == chunkFrames }
        val partial = h.sink.uploadedFrames.filter { it != chunkFrames }
        assertEquals(1, partial.size, "除了末块还有非整块：$partial")
        assertEquals(h.sink.uploadedFrames.size - 1, full)
        assertEquals(h.frames % chunkFrames, partial[0])
    }

    @Test
    fun `odd sample rates still divide into whole frames`() {
        // 22050Hz 的 250ms 是 5512.5 帧：只能落到 5512 帧（整帧），不能出现半帧
        val h = Harness(22_050, 2.03, 250).run(60)
        sameSamples(h.source, h.sink.playedSamples(), "22050Hz 播放流")
        val chunkFrames = 22_050 * 250 / 1000
        assertTrue(h.sink.uploadedFrames.dropLast(1).all { it == chunkFrames })
    }

    // —— H4：seek 之后不双重前进 ——

    @Test
    fun `seek restarts exactly at the requested frame and keeps advancing once`() {
        val h = Harness(192_000, 2.9, 250)
        h.run(30)
        val before = h.sink.uploaded.size
        val seekMs = 1537.0                      // 故意落在块中间
        h.run(40, seekAtTick = 0, seekMs = seekMs)
        val startFrame = (seekMs / 1000.0 * 192_000).toInt()
        val chunkFrames = 48_000
        val after = h.sink.uploaded.size - before
        assertTrue(after >= 4, "seek 后只上传了 $after 块")
        // seek 后重灌的每一块都必须从期望帧开始逐采样连续（跳过一个采样就会错位）
        for (bi in 0 until minOf(after, 6)) {
            val got = h.sink.uploaded[before + bi]
            val from = startFrame + bi * chunkFrames
            val n = minOf(got.size, (h.frames - from) * 2)
            for (k in 0 until n) {
                val want = h.source[from * 2 + k]
                if (got[k] != want) {
                    throw AssertionError("seek 后第 $bi 块第 ${k / 2} 帧不一致，期望 $want 实际 ${got[k]}")
                }
            }
        }
    }

    @Test
    fun `playback position after a seek counts from the seek point once`() {
        val h = Harness(192_000, 2.9, 250)
        val seekMs = 1537.0
        val ticksAfterSeek = 10
        h.run(1, seekAtTick = 0, seekMs = seekMs)
        h.run(ticksAfterSeek)
        // seek 那一 tick 与之后每 tick 都消费 50ms
        val expected = seekMs + (1 + ticksAfterSeek) * 50.0
        val got = h.player.positionMs()
        assertTrue(
            abs(got - expected) < 1.0,
            "seek 后播放位置记成了 ${got}ms，期望 ${expected}ms（seek/推进双重前进会翻倍）",
        )
    }

    // —— H5：供给 ——

    @Test
    fun `the queue never runs dry at 192k under a 20Hz tick loop`() {
        val h = Harness(192_000, 2.9, 250).run(70)
        assertTrue(h.sink.underruns.isEmpty(), "队列欠载：${h.sink.underruns}")
        // 只看还在产出的前 30 tick（30×9600帧 = 288000 帧 < 总长）：稳态下至少留 2 块余量。
        // 末尾那段是流结束后的正常排空，不参与这个判据。
        val minQueued = h.sink.queuedFramesLog.take(30).min()
        assertTrue(minQueued >= 2 * 48_000, "稳态队列余量掉到 $minQueued 帧（<2 块）")
    }

    @Test
    fun `decoding one 250ms chunk of 192k stereo is far below a tick`() {
        val rate = 192_000
        val frames = rate * 250 / 1000
        val wav = extensibleWav(rate, 2, sourceFrames(rate, frames * 40))
        WavDecoder(wav).use { dec ->
            val dst = ShortArray(frames * 2)
            repeat(3) { dec.read(dst) }
            dec.seekFrame(0)
            val t0 = System.nanoTime()
            var read = 0L
            repeat(40) { read += dec.read(dst) }
            val msPerChunk = (System.nanoTime() - t0) / 1e6 / 40
            println("[bench] 192kHz 立体声 250ms 块解码耗时 %.3f ms/块".format(msPerChunk))
            assertEquals(40L * frames, read)
            assertTrue(msPerChunk < 15.0, "单块解码 ${msPerChunk}ms，相对 50ms tick 太重")
        }
    }

    // —— H3：重采样器选择 ——

    @Test
    fun `anti aliased resampler is picked from the real OpenAL Soft name list`() {
        val real = listOf(
            "Nearest", "Linear", "Cubic Spline", "4-point Gaussian",
            "11th order Sinc (fast)", "11th order Sinc", "23rd order Sinc (fast)",
            "23rd order Sinc", "47th order Sinc (fast)", "47th order Sinc",
        )
        val picked = AudioStreamPlayer.pickAntiAliasedResampler(real)
        assertNotNull(picked)
        assertEquals("23rd order Sinc (fast)", real[picked])
    }

    @Test
    fun `resampler picking never falls back to an interpolation only resampler`() {
        // 只有纯插值档位时回 null：宁可不设，也不假装有抗混叠
        assertEquals(null, AudioStreamPlayer.pickAntiAliasedResampler(listOf("Nearest", "Linear", "Cubic Spline")))
        // 名字不认识时退到任意 sinc
        assertEquals(1, AudioStreamPlayer.pickAntiAliasedResampler(listOf("Point", "MySincThing")))
        // "23rd order Sinc" 不能抢先匹配上 "23rd order Sinc (fast)"
        assertEquals(0, AudioStreamPlayer.pickAntiAliasedResampler(listOf("23rd order Sinc (fast)", "47th order Sinc")))
        assertEquals(1, AudioStreamPlayer.pickAntiAliasedResampler(listOf("47th order Sinc", "23rd order Sinc")))
    }
}

// —— 合成素材（供测试类与其内部 Harness 共用） ——

/** 左声道确定性伪随机、右声道 1900Hz 正弦：左右各一份独立判据。 */
private fun sourceFrames(rate: Int, frames: Int, channels: Int = 2): ShortArray {
    val out = ShortArray(frames * channels)
    var lcg = 0x1234_5678
    for (i in 0 until frames) {
        lcg = lcg * 1103515245 + 12345
        out[i * channels] = (((lcg ushr 16) and 0xFFFF) - 32768).toShort()
        if (channels == 2) {
            out[i * 2 + 1] = (sin(2.0 * PI * 1900.0 * i / rate) * 0.6 * 32767.0).toInt().toShort()
        }
    }
    return out
}

/**
 * 按真实资产的头部形状造 WAV：WAVE_FORMAT_EXTENSIBLE（fmt 块 40 字节）+ fmt 与 data 之间
 * 夹一个 LIST 块。解码器的头部扫描路径因此也压进逐采样比对里。
 */
private fun extensibleWav(rate: Int, channels: Int, pcm: ShortArray): ByteArray {
    val bytesPerFrame = channels * 2
    val dataSize = pcm.size * 2
    val fmt = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN)
    fmt.putShort(0xFFFE.toShort())              // WAVE_FORMAT_EXTENSIBLE
    fmt.putShort(channels.toShort())
    fmt.putInt(rate)
    fmt.putInt(rate * bytesPerFrame)
    fmt.putShort(bytesPerFrame.toShort())
    fmt.putShort(16)                            // bits
    fmt.putShort(22)                            // cbSize
    fmt.putShort(16)                            // validBits
    fmt.putInt(if (channels == 2) 3 else 4)     // channelMask
    fmt.put(
        byteArrayOf(
            1, 0, 0, 0, 0, 0, 0x10, 0,
            0x80.toByte(), 0, 0, 0xAA.toByte(), 0, 0x38, 0x9B.toByte(), 0x71,
        )
    )                                           // KSDATAFORMAT_SUBTYPE_PCM
    val listBody = ByteBuffer.allocate(26).order(ByteOrder.LITTLE_ENDIAN)
    listBody.put("INFO".toByteArray(Charsets.US_ASCII))
    listBody.put("ISFT".toByteArray(Charsets.US_ASCII))
    listBody.putInt(14)
    listBody.put("pdraw probe\u0000\u0000\u0000".toByteArray(Charsets.US_ASCII))

    val pcmBytes = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
    pcmBytes.asShortBuffer().put(pcm)

    val out = ByteBuffer.allocate(12 + 48 + 34 + 8 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
    out.put("RIFF".toByteArray(Charsets.US_ASCII))
    out.putInt(out.capacity() - 8)
    out.put("WAVE".toByteArray(Charsets.US_ASCII))
    out.put("fmt ".toByteArray(Charsets.US_ASCII))
    out.putInt(40)
    out.put(fmt.array())
    out.put("LIST".toByteArray(Charsets.US_ASCII))
    out.putInt(26)
    out.put(listBody.array())
    out.put("data".toByteArray(Charsets.US_ASCII))
    out.putInt(dataSize)
    out.put(pcmBytes.array())
    return out.array()
}

private fun asset(name: String, wav: ByteArray) = AudioAsset(
    name, name, 1, wav, 0, 0, 0, 0.0, 0.0, 0.0, emptyList(),
    ShortArray(0), ShortArray(0), ShortArray(0), ByteArray(0), ByteArray(0), ByteArray(0),
)
