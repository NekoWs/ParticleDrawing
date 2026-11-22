package work.nekow.particledrawing.animation

import work.nekow.particledrawing.math.Vec3
import work.nekow.particledrawing.animation.script.Keyframe
import work.nekow.particledrawing.api.Color
import work.nekow.particledrawing.api.ParticleRenderOptions
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
import work.nekow.particledrawing.i18n.LocalizedArgumentException
import work.nekow.particledrawing.i18n.translation

/**
 * .pdrawc 二进制播放文件读取器 + Ed25519 验签。
 *
 * 布局：magic "PDC1" + version varint + 32 字节公钥 + body + 64 字节签名；
 * 签名为 Ed25519，覆盖除末尾 64 字节外的全部内容。
 *
 * 公钥保存在文件里，所以 [verify] 只能证明「内容与文件自带公钥匹配」：它能发现传输损坏和
 * 随手改动，但不能证明来源（任何人都能生成自己的密钥对重新签名）。安全相关的上限校验
 * （计数、解压大小、索引）必须独立于验签生效，不能把验签当信任边界。
 */
object PdrawcReader {

    private val MAGIC = byteArrayOf(0x50, 0x44, 0x43, 0x31) // "PDC1"
    private const val MIN_VERSION = 18
    private const val VERSION = 20
    private const val PUB_LEN = 32
    private const val SIG_LEN = 64

    /** 解压结果上限：压缩炸弹在验签之后、解析之前就会被这里拦下。 */
    private const val MAX_INFLATED_BYTES = 64 shl 20
    private const val MAX_TEXTURES = 4096
    private const val MAX_PARTICLES = 131_072
    private const val MAX_GROUPS = 65_536
    private const val MAX_FUNCTIONS = 16_384
    private const val MAX_CAMERAS = 4_096
    private const val MAX_TEXTS = 16_384
    private const val MAX_AUDIO = 256
    private const val MAX_TRACKS = 65_536
    private const val MAX_KEYFRAMES = 262_144
    private const val MAX_IDS = 262_144
    private const val MAX_CHARS = 262_144
    private const val MAX_BEATS = 262_144

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
        if (!verify(bytes)) throw LocalizedArgumentException(translation("error.file.signature"))
        val unsigned = bytes.copyOfRange(0, bytes.size - SIG_LEN)
        val r = Reader(ByteBuffer.wrap(unsigned).order(ByteOrder.LITTLE_ENDIAN))

        if (!r.bytes(4).contentEquals(MAGIC)) throw LocalizedArgumentException(translation("error.file.header"))
        val version = r.varint()
        if (version !in MIN_VERSION..VERSION) throw LocalizedArgumentException(translation("error.file.version", version))
        r.bytes(PUB_LEN) // 公钥已用于验签，跳过

        val bodyBytes = inflateRaw(r.remainingBytes())
        val br = Reader(ByteBuffer.wrap(bodyBytes).order(ByteOrder.LITTLE_ENDIAN))

        val loop = br.u8() != 0

        // 贴图表：name + PNG 字节；UV 通过索引引用
        val texCount = br.count(MAX_TEXTURES)
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
        val particleCount = br.count(MAX_PARTICLES)
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
            val billboard = (flags and 16) == 0   // bit4=非广告牌
            val spinLocal = (flags and 32) == 0   // bit5=自转空间 world
            val renderOptions = if (version >= 19) readRenderOptions(br, version) else ParticleRenderOptions.DEFAULT
            particles.add(AnimParticle("p$i", color, scale, flags and 1 != 0, lightLevel, pos, vel, uv, st, ent, life,
                billboard, spinLocal, renderOptions))
        }

        // 组（合成名 g0..gN，成员用粒子索引）
        val groupCount = br.count(MAX_GROUPS)
        val groups = LinkedHashMap<String, List<String>>()
        val groupNames = ArrayList<String>(groupCount)
        val groupSpinSpace = LinkedHashMap<String, Boolean>()
        val groupRotSpace = LinkedHashMap<String, Boolean>()
        for (gi in 0 until groupCount) {
            val gflags = br.u8()
            val spinLocal = (gflags and 1) != 0
            val rotLocal = (gflags and 2) != 0
            val memberCount = br.count(MAX_IDS)
            val members = ArrayList<String>(memberCount)
            for (j in 0 until memberCount) {
                val pIdx = br.varint()
                if (pIdx !in particles.indices) throw LocalizedArgumentException(translation("error.file.particle_index", pIdx))
                members.add("p" + pIdx)
            }
            val gname = "g$gi"
            groupNames.add(gname)
            groups[gname] = members
            groupSpinSpace[gname] = spinLocal
            groupRotSpace[gname] = rotLocal
        }

        // 组级 UV
        val groupUVCount = br.count(MAX_GROUPS)
        val groupUV = LinkedHashMap<String, UvData>()
        for (i in 0 until groupUVCount) {
            val gi = br.varint()
            if (gi !in groupNames.indices) throw LocalizedArgumentException(translation("error.file.group_uv"))
            groupUV[groupNames[gi]] = readUV(br, texNames)
        }

        // 函数对象（合成 id fx0..fxN）
        val fxCount = br.count(MAX_FUNCTIONS)
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
            // flags bit3 为废弃的 funcs 标志，与编辑器读取端一致地忽略（不消费字节）。
            val spinLocal = (flags and 16) != 0
            val rotLocal = (flags and 32) != 0
            val frameSync = (flags and 64) != 0
            // 空闲位 bit7 用于加法混合；不识别该位的播放端按普通混合显示
            val additive = (flags and 128) != 0
            val varCount = br.count(MAX_IDS)
            val vars = LinkedHashMap<String, FunctionVar>()
            for (j in 0 until varCount) {
                val name = br.str()
                val base = br.f32().toDouble()
                val kf = readVarKeyframes(br)
                vars[name] = FunctionVar(base, kf)
            }
            val renderOptions = if (version >= 19) readRenderOptions(br, version) else ParticleRenderOptions.DEFAULT
            functions.add(FunctionObject("fx$fi", "fx$fi", center, source, seed, vars, duration, uv, st, ent, fastMath,
                spinLocal, rotLocal, frameSync, additive, renderOptions))
        }

        // 摄像机对象：朝向 = target 目标点 + roll 翻滚角，旋转空间见 flags；
        // 关键帧走轨道 id "c:<id>"，rot 轨道 = 摄像机位置绕 target 公转
        val camCount = br.count(MAX_CAMERAS)
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

        // 文字对象：生成器源记录，粒子本体已按普通粒子烘焙；chars 引用粒子索引。
        val TEXT_ALIGNS = arrayOf("left", "center", "right")
        val textCount = br.count(MAX_TEXTS)
        val texts = ArrayList<TextObject>(textCount)
        for (i in 0 until textCount) {
            val id = br.str()
            val name = br.str()
            val text = br.str()
            val font = br.str()
            val fontSize = br.varint()
            val weight = br.str()
            val color = Color.of(br.u8() / 255f, br.u8() / 255f, br.u8() / 255f, br.u8() / 255f)
            val flags = br.u8()
            val italic = (flags and 1) != 0
            var strokeColor: Color? = null
            var strokeWidth = 0
            if (flags and 2 != 0) {
                strokeColor = Color.of(br.u8() / 255f, br.u8() / 255f, br.u8() / 255f, br.u8() / 255f)
                strokeWidth = br.varint()
            }
            val alignIdx = br.u8()
            val align = if (alignIdx in TEXT_ALIGNS.indices) TEXT_ALIGNS[alignIdx] else "left"
            val lineHeight = br.f32().toDouble()
            val letterSpacing = br.f32().toDouble()
            val st = br.varint()
            val life = if (flags and 4 != 0) br.varint() else -1
            val charCount = br.count(MAX_CHARS)
            val chars = ArrayList<TextChar>(charCount)
            for (j in 0 until charCount) {
                val index = br.varint()
                val code = br.varint()
                val pos = Vec3(br.f32().toDouble(), br.f32().toDouble(), br.f32().toDouble())
                val size = doubleArrayOf(br.f32().toDouble(), br.f32().toDouble())
                val pn = br.count(MAX_IDS)
                val ids = ArrayList<String>(pn)
                for (k in 0 until pn) {
                    val pIdx = br.varint()
                    if (pIdx !in particles.indices) throw LocalizedArgumentException(translation("error.file.text_particle", pIdx))
                    ids.add("p$pIdx")
                }
                chars.add(TextChar(index, code, pos, size, ids))
            }
            texts.add(
                TextObject(
                    id, name, text, font, fontSize, weight, italic, color, strokeColor, strokeWidth,
                    align, lineHeight, letterSpacing, st, life, chars,
                    spinLocal = (flags and 8) == 0,   // bit3=自转空间 world
                    rotLocal = (flags and 16) == 0,   // bit4=公转空间 world
                    billboard = (flags and 32) == 0,  // bit5=非广告牌
                )
            )
        }

        // 音频资产：元数据 + 量化特征列（u16/u8 原始小端字节）+ durMs 后的 5 个播放属性基础值 + 原始音频字节。
        // 列布局与编辑器一致。
        val audioCount = br.count(MAX_AUDIO)
        val audioAssets = ArrayList<AudioAsset>(audioCount)
        for (i in 0 until audioCount) {
            val id = br.str()
            val name = br.str()
            val fmt = br.u8()
            val st = br.varint()
            val durMs = br.varint()
            val vol = br.f32().toDouble()
            val speed = br.f32().toDouble()
            val pan = br.f32().toDouble()
            val fadeIn = br.varint()
            val fadeOut = br.varint()
            val hopCount = br.varint()
            if (hopCount > 2000000) throw LocalizedArgumentException(translation("error.file.audio_hop_count", hopCount))
            val bpm = br.f32().toDouble()
            val beatOffsetMs = br.f32().toDouble()
            val onsetMax = br.f32().toDouble()
            val beatCount = br.count(MAX_BEATS)
            val beats = ArrayList<Int>(beatCount)
            for (b in 0 until beatCount) beats.add(br.varint())
            val dataLen = br.varint()
            val data = br.bytes(dataLen)
            var rms = ShortArray(0); var peak = ShortArray(0); var centroid = ShortArray(0)
            var onset = ByteArray(0); var rolloff = ByteArray(0); var bands = ByteArray(0)
            if (hopCount > 0) {
                rms = br.shorts(hopCount)
                peak = br.shorts(hopCount)
                centroid = br.shorts(hopCount)
                onset = br.bytes(hopCount)
                rolloff = br.bytes(hopCount)
                bands = br.bytes(hopCount, 16)
            }
            audioAssets.add(
                AudioAsset(
                    id, name, fmt, data, st, durMs, hopCount, bpm, beatOffsetMs, onsetMax, beats,
                    rms, peak, centroid, onset, rolloff, bands,
                    vol = vol, speed = speed, pan = pan, fadeIn = fadeIn, fadeOut = fadeOut,
                )
            )
        }

        // 轨道
        val trackCount = br.count(MAX_TRACKS)
        val tracks = ArrayList<AnimTrack>(trackCount)
        for (i in 0 until trackCount) {
            val prIdx = br.u8()
            val pr = TrackPr.fromOrdinal(prIdx)
                ?: throw LocalizedArgumentException(translation("error.animation.track_property", prIdx))
            val mode = if (br.u8() == 1) AnimTrack.Mode.OP else AnimTrack.Mode.SET
            val idCount = br.count(MAX_IDS)
            val ids = ArrayList<String>(idCount)
            for (j in 0 until idCount) {
                val kind = br.u8()
                val idx = br.varint()
                ids.add(
                    when (kind) {
                        0 -> {
                            if (idx !in particles.indices) throw LocalizedArgumentException(translation("error.file.particle_index", idx))
                            "p$idx"
                        }
                        1 -> {
                            if (idx !in groupNames.indices) throw LocalizedArgumentException(translation("error.file.group_index", idx))
                            "g:g$idx"
                        }
                        2 -> {
                            if (idx !in functions.indices) throw LocalizedArgumentException(translation("error.file.function_index", idx))
                            "f:fx$idx"
                        }
                        3 -> if (idx in cameras.indices) "c:${cameras[idx].id}" else throw LocalizedArgumentException(translation("error.file.camera_index", idx))
                        4 -> if (idx in texts.indices) "t:${texts[idx].id}" else throw LocalizedArgumentException(translation("error.file.text_index", idx))
                        5 -> if (idx in audioAssets.indices) "a:${audioAssets[idx].id}" else throw LocalizedArgumentException(translation("error.file.audio_index", idx))
                        else -> throw LocalizedArgumentException(translation("error.file.track_type", kind))
                    }
                )
            }
            val kf = readTrackKeyframes(br)
            tracks.add(AnimTrack(pr, ids, kf, mode))
        }

        if (br.remaining() != 0) throw LocalizedArgumentException(translation("error.file.trailing_bytes"))

        return ParticleAnimation(loop, particles, tracks, groups, functions, texNames, groupUV, texData, groupSpinSpace, groupRotSpace, cameras, texts, audioAssets)
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
            if (r.varint() !in MIN_VERSION..VERSION) return null
            r.bytes(PUB_LEN)
        } catch (_: Exception) {
            null
        }
    }

    /** raw DEFLATE 解压；输出超过上限立即失败，避免压缩炸弹撑爆内存。 */
    private fun inflateRaw(data: ByteArray): ByteArray =
        InflaterInputStream(ByteArrayInputStream(data), Inflater(true)).use { input ->
            val out = java.io.ByteArrayOutputStream(8192)
            val chunk = ByteArray(8192)
            var total = 0
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                total += n
                if (total > MAX_INFLATED_BYTES) {
                    throw LocalizedArgumentException(translation("error.file.too_large", MAX_INFLATED_BYTES))
                }
                out.write(chunk, 0, n)
            }
            out.toByteArray()
        }

    private fun readRenderOptions(r: Reader, version: Int): ParticleRenderOptions {
        val fields = r.u8()
        require(fields in 0..(if (version >= 20) 15 else 7))
        if (fields == 0) return ParticleRenderOptions.DEFAULT
        return ParticleRenderOptions(
            alignVelocity = fields and 1 != 0,
            stretch = if (fields and 1 != 0) r.f32().toDouble() else ParticleRenderOptions.DEFAULT.stretch,
            softFade = if (fields and 2 != 0) r.f32().toDouble() else 0.0,
            trailTicks = if (fields and 4 != 0) r.u8() else 0,
            trailSides = if (fields and 8 != 0) r.u8() else 0,
        )
    }

    private fun readUV(r: Reader, texNames: List<String>): UvData {
        val texIdx = r.varint()
        if (texIdx !in texNames.indices) throw LocalizedArgumentException(translation("error.file.texture_index", texIdx))
        val modeIdx = r.u8()
        if (modeIdx !in UV_MODES.indices) throw LocalizedArgumentException(translation("error.file.uv_mode", modeIdx))
        val texSize = intArrayOf(r.varint(), r.varint())
        val uvStart = intArrayOf(r.varint(), r.varint())
        val uvSize = intArrayOf(r.varint(), r.varint())
        val uvStep = intArrayOf(r.varint(), r.varint())
        val fps = r.f32()
        val maxFrame = r.varint()
        val loop = r.u8() != 0
        // loop 后 1 字节 exprFlags，按位序读存在的表达式字符串（null=用数值字段）
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
                if (idx !in EasingType.PRESETS.indices) throw LocalizedArgumentException(translation("error.easing.index", idx))
                EasingType.PRESETS[idx]
            }
            1 -> EasingType.custom(
                r.f32().toDouble(), r.f32().toDouble(), r.f32().toDouble(), r.f32().toDouble()
            )
            2 -> EasingType.NONE
            else -> throw LocalizedArgumentException(translation("error.easing.type"))
        }
    }

    private fun readVarKeyframes(r: Reader): List<Keyframe> {
        val n = r.count(MAX_KEYFRAMES)
        val kf = ArrayList<Keyframe>(n)
        for (i in 0 until n) {
            kf.add(Keyframe(r.varint().toDouble(), r.f32().toDouble(), readEasing(r)))
        }
        return kf
    }

    private fun readTrackKeyframes(r: Reader): List<AnimKeyframe> {
        val n = r.count(MAX_KEYFRAMES)
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

        /** 读取元素数量；同时要求剩余字节够每个元素至少 1 字节，避免伪造计数骗出大分配。 */
        fun count(max: Int): Int {
            val n = varint()
            if (n < 0 || n > max || n > buf.remaining()) {
                throw LocalizedArgumentException(translation("error.file.count_range", n, max))
            }
            return n
        }

        fun bytes(n: Int, stride: Int = 1): ByteArray {
            if (n < 0 || stride <= 0 || n.toLong() * stride > buf.remaining()) {
                throw LocalizedArgumentException(translation("error.file.truncated"))
            }
            val out = ByteArray(n * stride)
            buf.get(out)
            return out
        }

        fun f32(): Float = buf.float

        /** 读取 n 个 u16（小端）为 ShortArray（值语义无符号，读取侧按 0xFFFF 掩码解释）。 */
        fun shorts(n: Int): ShortArray {
            if (n < 0 || n.toLong() * 2L > buf.remaining()) {
                throw LocalizedArgumentException(translation("error.file.truncated"))
            }
            val out = ShortArray(n)
            for (i in 0 until n) out[i] = buf.short
            return out
        }

        fun varint(): Int {
            var result = 0L
            var shift = 0
            while (true) {
                val b = u8()
                result = result or ((b.toLong() and 0x7f) shl shift)
                if (b and 0x80 == 0) break
                shift += 7
                if (shift > 35) throw LocalizedArgumentException(translation("error.file.varint_overflow"))
            }
            if (result > Int.MAX_VALUE) throw LocalizedArgumentException(translation("error.file.varint_range"))
            return result.toInt()
        }

        fun str(): String {
            val n = varint()
            return String(bytes(n), Charsets.UTF_8)
        }
    }
}