package work.nekow.particledrawing

import org.lwjgl.system.MemoryStack
import work.nekow.particledrawing.core.client.OggDecoder
import work.nekow.particledrawing.core.client.WavDecoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 播放侧解码器（AudioStreamPlayer 里除 OpenAL 之外的那半，不需要声音设备）：
 * OGG 按采样帧精确 seek、解码器持有的输入内存在句柄存活期间不能被复用，WAV 直接读资产字节。
 * OGG 断言用夹具（10 秒 44.1kHz 立体声，左 440Hz/0.8、右 1000Hz/0.3）的解析正弦当基准。
 */
class AudioDecoderTest {

    @Test
    fun `ogg seek starts exactly at the requested frame`() {
        val dec = openOgg() ?: return
        dec.use {
            assertEquals(44100, it.sampleRate)
            assertEquals(2, it.channels)

            // 块边界与中间位置都取：seek_frame 只保证下一帧「包含」目标采样，会整体前移一个块
            for (baseFrame in listOf(0, 220_500, 396_900, 440_700)) {
                it.seekFrame(baseFrame.toLong())
                val dst = ShortArray(256 * 2)
                val frames = it.read(dst)
                assertTrue(frames > 0, "frame=$baseFrame 读不到样本")
                var left = 0.0
                var right = 0.0
                for (i in 0 until frames) {
                    val ms = (baseFrame + i) * 1000.0 / 44100.0
                    left += abs(dst[i * 2] / 32768.0 - sineAt(ms, 0))
                    right += abs(dst[i * 2 + 1] / 32768.0 - sineAt(ms, 1))
                }
                assertTrue(left / frames < 0.02, "frame=$baseFrame 左声道平均误差 ${left / frames}")
                assertTrue(right / frames < 0.02, "frame=$baseFrame 右声道平均误差 ${right / frames}")
            }
        }
    }

    @Test
    fun `ogg input buffer survives memory stack reuse`() {
        val dec = openOgg() ?: return
        dec.use {
            // 解码器若把输入内存放在 MemoryStack 上，pop 之后这些分配就会把它覆盖掉；
            // 堆分配（memAlloc）则完全不受影响。这里从 0 帧开始（seek 0 两种实现都精确），
            // 所以解码不一致只可能是输入内存被踩了。
            val junk = ByteArray(32 * 1024) { 0x7F }
            repeat(4) {
                MemoryStack.stackPush().use { stack -> stack.malloc(junk.size).put(junk) }
            }

            it.seekFrame(0)
            val dst = ShortArray(256 * 2)
            assertEquals(256, it.read(dst))
            var err = 0.0
            for (i in 0 until 256) {
                val ms = i * 1000.0 / 44100.0
                err += abs(dst[i * 2 + 1] / 32768.0 - sineAt(ms, 1))
            }
            assertTrue(err / 256 < 0.02, "栈被复用后解码结果不对，平均误差 ${err / 256}")
        }
    }

    @Test
    fun `wav decoder reads interleaved pcm and seeks exactly`() {
        // 96kHz 立体声 4 帧，样本取 int16 边界值，便于逐点核对
        val data = wav16(96000, intArrayOf(0, 32767, 8192, -16384, -32768, 16384, 4096, 0))
        WavDecoder(data).use { dec ->
            assertEquals(96000, dec.sampleRate)
            assertEquals(2, dec.channels)

            val all = ShortArray(8)
            assertEquals(4, dec.read(all))
            assertEquals(listOf(0, 32767, 8192, -16384, -32768, 16384, 4096, 0), all.map { it.toInt() })

            dec.seekFrame(2)
            val tail = ShortArray(4)
            assertEquals(2, dec.read(tail))
            assertEquals(listOf(-32768, 16384, 4096, 0), tail.map { it.toInt() })

            // 远超出末尾的 seek 只停到 data 末尾（不会因乘法溢出跑到 data 之前），读回 0 帧
            dec.seekFrame(1_000_000_000L)
            assertEquals(0, dec.read(ShortArray(8)))
        }
    }

    /** 夹具正弦的解析值：左 440Hz/0.8，右 1000Hz/0.3。 */
    private fun sineAt(ms: Double, ch: Int): Double =
        if (ch == 0) 0.8 * sin(2 * Math.PI * 440.0 * ms / 1000.0)
        else 0.3 * sin(2 * Math.PI * 1000.0 * ms / 1000.0)

    /** 打开夹具；缺原生库或夹具时跳过而不是失败。 */
    private fun openOgg(): OggDecoder? {
        val data = javaClass.getResourceAsStream("/audio/sine-10s.ogg")?.readBytes()
        org.junit.Assume.assumeTrue("缺少 OGG 夹具", data != null)
        return try {
            OggDecoder(data!!)
        } catch (e: LinkageError) {
            org.junit.Assume.assumeNoException("没有可用的 STB 原生库", e)
            null
        }
    }

    private fun wav16(rate: Int, interleaved: IntArray): ByteArray {
        val payload = ByteBuffer.allocate(interleaved.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (v in interleaved) payload.putShort(v.toShort())
        val body = payload.array()
        val head = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        head.put("RIFF".toByteArray(Charsets.US_ASCII))
        head.putInt(36 + body.size)
        head.put("WAVE".toByteArray(Charsets.US_ASCII))
        head.put("fmt ".toByteArray(Charsets.US_ASCII))
        head.putInt(16)
        head.putShort(1)          // PCM
        head.putShort(2)          // 声道
        head.putInt(rate)
        head.putInt(rate * 4)     // byteRate
        head.putShort(4)          // blockAlign
        head.putShort(16)         // bits
        head.put("data".toByteArray(Charsets.US_ASCII))
        head.putInt(body.size)
        return head.array() + body
    }
}
