package work.nekow.particledrawing

import work.nekow.particledrawing.animation.PdrawcReader
import work.nekow.particledrawing.animation.TrackPr
import java.io.ByteArrayOutputStream
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.NamedParameterSpec
import java.util.zip.Deflater
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * v18 读取端回归：v17 及更早的音频夹具已随版本升级失效（旧包不再可读），
 * 这里就地造一份最小 v18 字节流，把「音频 5 个播放属性 → f32/varint 顺序」和
 * 「轨道引用 kind=5 → a:<资产id>」这两条二进制约定钉死在读取端。
 */
class PdrawcReaderV18Test {

    @Test
    fun `reads v18 audio playback props and audio track ref`() {
        val anim = PdrawcReader.parse(buildV18(vol = 1.75f, speed = 2.5f, pan = -0.5f, fadeIn = 300, fadeOut = 450))

        assertEquals(1, anim.audioAssets.size)
        val a = anim.audioAssets[0]
        assertEquals("aud1", a.id)
        assertEquals("bgm", a.name)
        assertEquals(0, a.fmt)
        assertEquals(100, a.st)
        assertEquals(4000, a.durMs)
        assertEquals(1.75, a.vol, 1e-6)
        assertEquals(2.5, a.speed, 1e-6)
        assertEquals(-0.5, a.pan, 1e-6)
        assertEquals(300, a.fadeIn)
        assertEquals(450, a.fadeOut)
        // 新字段之后的旧字段不能错位
        assertEquals(4, a.hopCount)
        assertEquals(128.0, a.bpm, 1e-6)
        assertEquals(0.5, a.onsetMax, 1e-6)
        assertEquals(listOf(0, 469), a.beats)
        assertContentEquals(byteArrayOf(1, 2, 3, 4), a.data)
    }

    @Test
    fun `kind 5 resolves to audio owner id and the track keeps its pr`() {
        val anim = PdrawcReader.parse(buildV18(vol = 1f, speed = 1f, pan = 0f, fadeIn = 0, fadeOut = 0))

        assertEquals(1, anim.tracks.size)
        val tr = anim.tracks[0]
        assertEquals(TrackPr.VOL, tr.pr)
        assertEquals(listOf("a:aud1"), tr.ids)
        assertEquals(1, tr.keyframes.size)
        assertEquals(500, tr.keyframes[0].ms)
        assertEquals(1.5, tr.keyframes[0].value, 1e-6)
    }

    @Test
    fun `audio track ref out of range is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            PdrawcReader.parse(buildV18(vol = 1f, speed = 1f, pan = 0f, fadeIn = 0, fadeOut = 0, audioRef = 3))
        }
    }

    /** 最小 v18 .pdrawc：无贴图/粒子/组/函数对象，一条音频 + 一条指向它的 VOL 轨道。 */
    private fun buildV18(
        vol: Float,
        speed: Float,
        pan: Float,
        fadeIn: Int,
        fadeOut: Int,
        audioRef: Int = 0,
    ): ByteArray {
        val body = BodyWriter()
        body.u8(0)                    // loop = false
        body.varint(0)                // 贴图表
        body.varint(0)                // 粒子
        body.varint(0)                // 组
        body.varint(0)                // 组 UV
        body.varint(0)                // 函数对象
        body.varint(0)                // 摄像机
        body.varint(0)                // 文字对象

        body.varint(1)                // 音频资产
        body.str("aud1")
        body.str("bgm")
        body.u8(0)                    // fmt = ogg
        body.varint(100)              // st
        body.varint(4000)             // durMs
        body.f32(vol)                 // v18
        body.f32(speed)
        body.f32(pan)
        body.varint(fadeIn)
        body.varint(fadeOut)
        body.varint(4)                // hopCount
        body.f32(128f)                // bpm
        body.f32(0f)                  // beatOffsetMs
        body.f32(0.5f)                // onsetMax
        body.varint(2)                // beats
        body.varint(0)
        body.varint(469)
        body.varint(4)                // dataLen
        body.bytes(byteArrayOf(1, 2, 3, 4))
        body.shorts(ShortArray(4) { 0 })              // rms
        body.shorts(ShortArray(4) { 0 })              // peak
        body.shorts(ShortArray(4) { 0 })              // centroid
        body.bytes(ByteArray(4))                      // onset
        body.bytes(ByteArray(4))                      // rolloff
        body.bytes(ByteArray(4 * 16))                 // bands

        body.varint(1)                // 轨道
        body.u8(TrackPr.VOL.ordinal)
        body.u8(0)                    // SET
        body.varint(1)                // 引用数
        body.u8(5)                    // kind = 音频对象
        body.varint(audioRef)         // 资产索引
        body.varint(1)                // 关键帧数
        body.varint(500)
        body.f32(1.5f)
        body.u8(2)                    // 缓动 = none

        val (pub, priv) = keyPair()
        val unsigned = BodyWriter().apply {
            bytes(byteArrayOf(0x50, 0x44, 0x43, 0x31))
            varint(18)
            bytes(pub)
            bytes(deflate(body.toByteArray()))
        }.toByteArray()

        val signer = Signature.getInstance("Ed25519")
        signer.initSign(priv)
        signer.update(unsigned)
        return unsigned + signer.sign()
    }

    /** 公钥取 RFC 8032 压缩点（xy 拼成 little-endian）+ 私钥种子，供 Ed25519 签名。 */
    private fun keyPair(): Pair<ByteArray, java.security.PrivateKey> {
        val gen = KeyPairGenerator.getInstance("Ed25519")
        gen.initialize(NamedParameterSpec.ED25519)
        val kp = gen.generateKeyPair()
        val y = (kp.public as java.security.interfaces.EdECPublicKey).point.y
        var raw = y.toByteArray().reversedArray()      // BigInteger 大端 → 公钥 little-endian
        if (raw.size > 32) raw = raw.copyOfRange(raw.size - 32, raw.size)
        val pub = ByteArray(32)
        raw.copyInto(pub, 32 - raw.size)               // 左侧补零对齐到 32 字节
        if ((kp.public as java.security.interfaces.EdECPublicKey).point.isXOdd) pub[31] = (pub[31].toInt() or 0x80).toByte()
        return pub to kp.private
    }

    private fun deflate(bytes: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        deflater.setInput(bytes)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
        deflater.end()
        return out.toByteArray()
    }

    /** 只写了读取端实际会消费的字节写入器。 */
    private class BodyWriter {
        private val out = ByteArrayOutputStream()

        fun u8(v: Int) { out.write(v and 0xff) }
        fun f32(v: Float) { bytes(java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putFloat(v).array()) }
        fun bytes(v: ByteArray) { out.write(v) }
        fun shorts(v: ShortArray) { bytes(java.nio.ByteBuffer.allocate(v.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN).also { b -> for (s in v) b.putShort(s) }.array()) }
        fun varint(value: Int) {
            var v = value
            while (true) {
                if (v and 0x7f.inv() == 0) { u8(v); return }
                u8((v and 0x7f) or 0x80)
                v = v ushr 7
            }
        }
        fun str(s: String) { val b = s.toByteArray(Charsets.UTF_8); varint(b.size); bytes(b) }
        fun toByteArray(): ByteArray = out.toByteArray()
    }
}
