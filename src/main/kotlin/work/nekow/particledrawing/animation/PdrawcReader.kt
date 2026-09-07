package work.nekow.particledrawing.animation

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.animation.script.Keyframe
import work.nekow.particledrawing.api.Color
import work.nekow.particledrawing.core.easing.EasingType
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.EdECPoint
import java.security.spec.EdECPublicKeySpec
import java.security.spec.NamedParameterSpec
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * .pdrawc 二进制播放文件读取器 + Ed25519 验签。
 *
 * 规范见编辑器 docs/pdrawc-format.md。布局：magic "PDC1" + version varint + 32 字节公钥
 * + body + 64 字节签名；签名为 Ed25519，覆盖除末尾 64 字节外的全部内容。
 */
object PdrawcReader {

    private val MAGIC = byteArrayOf(0x50, 0x44, 0x43, 0x31) // "PDC1"
    private const val VERSION = 11    // v11：函数对象 spawn 模型（tick / processParam，移除 count 编码）
    private const val PUB_LEN = 32
    private const val SIG_LEN = 64

    private val UV_MODES = arrayOf(UvData.Mode.STATIC, UvData.Mode.FILL, UvData.Mode.ANIMATED)

    /** 验证完整 .pdrawc 文件的 Ed25519 签名（用文件内嵌公钥）。 */
    @JvmStatic
    fun verify(bytes: ByteArray): Boolean {
        if (bytes.size < 4 + 1 + PUB_LEN + SIG_LEN) return false
        val unsigned = bytes.copyOfRange(0, bytes.size - SIG_LEN)
        val signature = bytes.copyOfRange(bytes.size - SIG_LEN, bytes.size)
        val publicKey = readPublicKey(unsigned) ?: return false
        return try {
            val keyFactory = KeyFactory.getInstance("Ed25519")
            val key = keyFactory.generatePublic(
                EdECPublicKeySpec(NamedParameterSpec.ED25519, decodeEd25519Point(publicKey))
            )
            val verifier = Signature.getInstance("Ed25519")
            verifier.initVerify(key)
            verifier.update(unsigned)
            verifier.verify(signature)
        } catch (_: Exception) {
            false
        }
    }

    /** 验签并解析为 [ParticleAnimation]；验签失败/格式损坏抛异常（拒绝播放）。 */
    @JvmStatic
    fun parse(bytes: ByteArray): ParticleAnimation {
        if (!verify(bytes)) throw IllegalArgumentException("pdrawc 签名验证失败")
        val unsigned = bytes.copyOfRange(0, bytes.size - SIG_LEN)
        val r = Reader(ByteBuffer.wrap(unsigned).order(ByteOrder.LITTLE_ENDIAN))

        if (!r.bytes(4).contentEquals(MAGIC)) throw IllegalArgumentException("pdrawc 魔数错误")
        val version = r.varint()
        if (version != VERSION) throw IllegalArgumentException("pdrawc 版本不支持: $version")
        r.bytes(PUB_LEN) // 公钥已用于验签，跳过

        val bodyBytes = inflateRaw(r.remainingBytes())
        val br = Reader(ByteBuffer.wrap(bodyBytes).order(ByteOrder.LITTLE_ENDIAN))

        val loop = br.u8() != 0

        // 贴图表：name + PNG 字节；UV 通过索引引用
        val texCount = br.varint()
        val texNames = ArrayList<String>(texCount)
        val texData = LinkedHashMap<String, ByteArray>()
        for (i in 0 until texCount) {
            val name = br.str()
            val pngLen = br.varint()
            val png = br.bytes(pngLen)
            texNames.add(name)
            texData[name] = png
        }

        // 粒子（合成 id p0..pN）
        val particleCount = br.varint()
        val particles = ArrayList<AnimParticle>(particleCount)
        for (i in 0 until particleCount) {
            val color = Color.of(
                br.u8() / 255f, br.u8() / 255f, br.u8() / 255f, br.u8() / 255f
            )
            val scale = floatArrayOf(br.f32(), br.f32(), br.f32())
            val flags = br.u8()
            val lightLevel = br.u8()
            val pos = Vec3(br.f32().toDouble(), br.f32().toDouble(), br.f32().toDouble())
            val vel = Vec3(br.f32().toDouble(), br.f32().toDouble(), br.f32().toDouble())
            val st = br.varint()
            val life = if (flags and 8 != 0) br.varint() else -1
            val ent = if (flags and 4 != 0) readEnt(br) else null
            val uv = if (flags and 2 != 0) readUV(br, texNames) else null
            particles.add(AnimParticle("p$i", color, scale, flags and 1 != 0, lightLevel, pos, vel, uv, st, ent, life))
        }

        // 组（合成名 g0..gN，成员用粒子索引；v5 增加组级自转空间）
        val groupCount = br.varint()
        val groups = LinkedHashMap<String, List<String>>()
        val groupNames = ArrayList<String>(groupCount)
        val groupSpinSpace = LinkedHashMap<String, Boolean>()
        val groupRotSpace = LinkedHashMap<String, Boolean>()
        for (gi in 0 until groupCount) {
            val gflags = br.u8()
            val spinLocal = (gflags and 1) != 0
            val rotLocal = (gflags and 2) != 0
            val memberCount = br.varint()
            val members = ArrayList<String>(memberCount)
            for (j in 0 until memberCount) members.add("p" + br.varint())
            val gname = "g$gi"
            groupNames.add(gname)
            groups[gname] = members
            groupSpinSpace[gname] = spinLocal
            groupRotSpace[gname] = rotLocal
        }

        // 组级 UV
        val groupUVCount = br.varint()
        val groupUV = LinkedHashMap<String, UvData>()
        for (i in 0 until groupUVCount) {
            val gi = br.varint()
            if (gi !in groupNames.indices) throw IllegalArgumentException("pdrawc 组 UV 索引越界")
            groupUV[groupNames[gi]] = readUV(br, texNames)
        }

        // 函数对象（合成 id fx0..fxN）
        val fxCount = br.varint()
        val functions = ArrayList<FunctionObject>(fxCount)
        for (fi in 0 until fxCount) {
            val center = doubleArrayOf(br.f32().toDouble(), br.f32().toDouble(), br.f32().toDouble())
            val source = br.str()
            val seed = br.varint()
            val duration = br.varint()
            val st = br.varint()
            val flags = br.u8()
            val ent = if (flags and 1 != 0) readEnt(br) else null
            val uv = if (flags and 2 != 0) readUV(br, texNames) else null
            val fastMath = (flags and 4) != 0
            // v12 起 funcs 已并入 source；flags bit3 为旧 funcs 标志，按编辑器读取端一致地忽略（不再消费字节）。
            val spinLocal = (flags and 16) != 0
            val rotLocal = (flags and 32) != 0
            val varCount = br.varint()
            val vars = LinkedHashMap<String, FunctionVar>()
            for (j in 0 until varCount) {
                val name = br.str()
                val base = br.f32().toDouble()
                val kf = readVarKeyframes(br)
                vars[name] = FunctionVar(base, kf)
            }
            functions.add(FunctionObject("fx$fi", "fx$fi", center, source, seed, vars, duration, uv, st, ent, fastMath, spinLocal, rotLocal))
        }

        // 摄像机对象（v6 新增；v7 起朝向 = target 目标点 + roll 翻滚角；v8 起旋转空间 flags：
        // 关键帧走轨道 id "c:<id>"，rot 轨道 = 摄像机位置绕 target 公转）
        val camCount = br.varint()
        val cameras = ArrayList<AnimCamera>(camCount)
        for (i in 0 until camCount) {
            val id = br.str()
            val name = br.str()
            val pos = doubleArrayOf(br.f32().toDouble(), br.f32().toDouble(), br.f32().toDouble())
            val target = doubleArrayOf(br.f32().toDouble(), br.f32().toDouble(), br.f32().toDouble())
            val roll = br.f32().toDouble()
            val fov = br.f32().toDouble()
            val flags = br.u8()
            cameras.add(AnimCamera(id, name, pos, target, roll, fov, (flags and 1) != 0))
        }

        // 轨道
        val trackCount = br.varint()
        val tracks = ArrayList<AnimTrack>(trackCount)
        for (i in 0 until trackCount) {
            val prIdx = br.u8()
            val pr = TrackPr.fromOrdinal(prIdx)
                ?: throw IllegalArgumentException("pdrawc 未知 pr 枚举: $prIdx")
            val mode = if (br.u8() == 1) AnimTrack.Mode.OP else AnimTrack.Mode.SET
            val idCount = br.varint()
            val ids = ArrayList<String>(idCount)
            for (j in 0 until idCount) {
                val kind = br.u8()
                val idx = br.varint()
                ids.add(
                    when (kind) {
                        0 -> "p$idx"
                        1 -> "g:g$idx"
                        2 -> "f:fx$idx"
                        3 -> if (idx in cameras.indices) "c:${cameras[idx].id}" else throw IllegalArgumentException("pdrawc 摄像机索引越界: $idx")
                        else -> throw IllegalArgumentException("pdrawc 未知轨道引用类型: $kind")
                    }
                )
            }
            val kf = readTrackKeyframes(br)
            tracks.add(AnimTrack(pr, ids, kf, mode))
        }

        if (br.remaining() != 0) throw IllegalArgumentException("pdrawc 存在未解析的尾随字节")

        return ParticleAnimation(loop, particles, tracks, groups, functions, texNames, groupUV, texData, groupSpinSpace, groupRotSpace, cameras)
    }

    /** RFC 8032 Ed25519 公钥（32 字节压缩点）→ JDK [EdECPoint]。 */
    private fun decodeEd25519Point(raw: ByteArray): EdECPoint {
        val xOdd = (raw[31].toInt() and 0x80) != 0
        val yBytes = raw.copyOf()
        yBytes[31] = (yBytes[31].toInt() and 0x7f).toByte()
        // RFC 8032 是 little-endian；BigInteger 需要 big-endian
        val y = BigInteger(1, yBytes.reversedArray())
        return EdECPoint(xOdd, y)
    }

    private fun readPublicKey(unsigned: ByteArray): ByteArray? {
        val r = Reader(ByteBuffer.wrap(unsigned).order(ByteOrder.LITTLE_ENDIAN))
        return try {
            if (!r.bytes(4).contentEquals(MAGIC)) return null
            if (r.varint() != VERSION) return null
            r.bytes(PUB_LEN)
        } catch (_: Exception) {
            null
        }
    }

    /** raw DEFLATE 解压。 */
    private fun inflateRaw(data: ByteArray): ByteArray =
        InflaterInputStream(ByteArrayInputStream(data), Inflater(true)).use { it.readBytes() }

    private fun readUV(r: Reader, texNames: List<String>): UvData {
        val texIdx = r.varint()
        if (texIdx !in texNames.indices) throw IllegalArgumentException("pdrawc 贴图索引越界: $texIdx")
        val modeIdx = r.u8()
        if (modeIdx !in UV_MODES.indices) throw IllegalArgumentException("pdrawc 未知 UV 模式: $modeIdx")
        val texSize = intArrayOf(r.varint(), r.varint())
        val uvStart = intArrayOf(r.varint(), r.varint())
        val uvSize = intArrayOf(r.varint(), r.varint())
        val uvStep = intArrayOf(r.varint(), r.varint())
        val fps = r.f32()
        val maxFrame = r.varint()
        val loop = r.u8() != 0
        // v10：loop 后 1 字节 exprFlags，按位序读存在的表达式字符串（null=用数值字段）
        val exprFlags = r.u8()
        val uvStartExpr = arrayOfNulls<String>(2)
        val uvSizeExpr = arrayOfNulls<String>(2)
        val uvStepExpr = arrayOfNulls<String>(2)
        if (exprFlags and (1 shl 0) != 0) uvStartExpr[0] = r.str()
        if (exprFlags and (1 shl 1) != 0) uvStartExpr[1] = r.str()
        if (exprFlags and (1 shl 2) != 0) uvSizeExpr[0] = r.str()
        if (exprFlags and (1 shl 3) != 0) uvSizeExpr[1] = r.str()
        if (exprFlags and (1 shl 4) != 0) uvStepExpr[0] = r.str()
        if (exprFlags and (1 shl 5) != 0) uvStepExpr[1] = r.str()
        val fpsExpr = if (exprFlags and (1 shl 6) != 0) r.str() else null
        val maxFrameExpr = if (exprFlags and (1 shl 7) != 0) r.str() else null
        return UvData(
            texture = texNames[texIdx],
            mode = UV_MODES[modeIdx],
            texSize = texSize,
            uvStart = uvStart,
            uvSize = uvSize,
            uvStep = uvStep,
            fps = fps,
            maxFrame = maxFrame,
            loop = loop,
            uvStartExpr = uvStartExpr,
            uvSizeExpr = uvSizeExpr,
            uvStepExpr = uvStepExpr,
            fpsExpr = fpsExpr,
            maxFrameExpr = maxFrameExpr,
        )
    }

    private fun readEnt(r: Reader): Entrance {
        val preset = r.str()
        val d = r.varint()
        return Entrance(preset, d)
    }

    private fun readEasing(r: Reader): EasingType {
        return when (r.u8()) {
            0 -> {
                val idx = r.varint()
                if (idx !in EasingType.PRESETS.indices) throw IllegalArgumentException("pdrawc 缓动预设越界: $idx")
                EasingType.PRESETS[idx]
            }
            1 -> EasingType.custom(
                r.f32().toDouble(), r.f32().toDouble(), r.f32().toDouble(), r.f32().toDouble()
            )
            2 -> EasingType.NONE
            else -> throw IllegalArgumentException("pdrawc 未知缓动类型")
        }
    }

    private fun readVarKeyframes(r: Reader): List<Keyframe> {
        val n = r.varint()
        val kf = ArrayList<Keyframe>(n)
        for (i in 0 until n) {
            kf.add(Keyframe(r.varint().toDouble(), r.f32().toDouble(), readEasing(r)))
        }
        return kf
    }

    private fun readTrackKeyframes(r: Reader): List<AnimKeyframe> {
        val n = r.varint()
        val kf = ArrayList<AnimKeyframe>(n)
        for (i in 0 until n) {
            kf.add(AnimKeyframe(r.varint(), r.f32().toDouble(), readEasing(r)))
        }
        return kf
    }

    private class Reader(private val buf: ByteBuffer) {
        fun remaining(): Int = buf.remaining()

        fun remainingBytes(): ByteArray = bytes(buf.remaining())

        fun u8(): Int = buf.get().toInt() and 0xff

        fun bytes(n: Int): ByteArray {
            if (n < 0 || n > buf.remaining()) throw IllegalArgumentException("pdrawc 数据截断")
            val out = ByteArray(n)
            buf.get(out)
            return out
        }

        fun f32(): Float = buf.float

        fun varint(): Int {
            var result = 0L
            var shift = 0
            while (true) {
                val b = u8()
                result = result or ((b.toLong() and 0x7f) shl shift)
                if (b and 0x80 == 0) break
                shift += 7
                if (shift > 35) throw IllegalArgumentException("pdrawc varint 溢出")
            }
            if (result > Int.MAX_VALUE) throw IllegalArgumentException("pdrawc varint 超出 Int 范围")
            return result.toInt()
        }

        fun str(): String {
            val n = varint()
            return String(bytes(n), Charsets.UTF_8)
        }
    }
}