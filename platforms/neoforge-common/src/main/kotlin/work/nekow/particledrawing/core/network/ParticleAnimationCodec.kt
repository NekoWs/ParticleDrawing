package work.nekow.particledrawing.core.network

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.animation.AnimCamera
import work.nekow.particledrawing.animation.AnimKeyframe
import work.nekow.particledrawing.animation.AnimParticle
import work.nekow.particledrawing.animation.AnimTrack
import work.nekow.particledrawing.animation.AudioAsset
import work.nekow.particledrawing.animation.Entrance
import work.nekow.particledrawing.animation.FunctionObject
import work.nekow.particledrawing.animation.FunctionVar
import work.nekow.particledrawing.animation.ParticleAnimation
import work.nekow.particledrawing.animation.TextChar
import work.nekow.particledrawing.animation.TextObject
import work.nekow.particledrawing.animation.TrackPr
import work.nekow.particledrawing.animation.UvData
import work.nekow.particledrawing.animation.script.Keyframe
import work.nekow.particledrawing.api.Color
import work.nekow.particledrawing.api.ParticleRenderOptions
import work.nekow.particledrawing.i18n.LocalizedArgumentException
import work.nekow.particledrawing.i18n.translation
import work.nekow.particledrawing.platform.toCore

/**
 * 代码生成 [ParticleAnimation] 的网络编解码：手写二进制，与 .pdrawc 无关，不签名。
 *
 * 首字段为 version varint，客户端按同一版本号解析，版本不符抛异常拒绝播放。
 */
internal object ParticleAnimationCodec {

    const val VERSION = 11

    // 载荷来自对端，所有计数/字节长度先卡上限，避免被伪造的 varint 骗出大分配。
    private const val MAX_PARTICLES = 131_072
    private const val MAX_TRACKS = 65_536
    private const val MAX_GROUPS = 65_536
    private const val MAX_IDS = 262_144
    private const val MAX_FUNCTIONS = 16_384
    private const val MAX_TEXTURES = 4_096
    private const val MAX_CAMERAS = 4_096
    private const val MAX_TEXTS = 16_384
    private const val MAX_AUDIO = 256
    private const val MAX_KEYFRAMES = 262_144
    private const val MAX_BEATS = 262_144
    private const val MAX_CHARS = 262_144
    private const val MAX_HOPS = 2_000_000
    private const val MAX_BAND_BYTES = 32 shl 20
    private const val MAX_TEXTURE_BYTES = 8 shl 20
    private const val MAX_AUDIO_BYTES = 32 shl 20

    private fun count(buf: FriendlyByteBuf, max: Int): Int {
        val n = buf.readVarInt()
        if (n < 0 || n > max || n > buf.readableBytes()) {
            throw LocalizedArgumentException(translation("error.packet.animation_count", n, max))
        }
        return n
    }

    private fun bytes(buf: FriendlyByteBuf, max: Int): ByteArray {
        val n = buf.readVarInt()
        if (n < 0 || n > max || n > buf.readableBytes()) {
            throw LocalizedArgumentException(translation("error.packet.animation_bytes", n, max))
        }
        val out = ByteArray(n)
        buf.readBytes(out)
        return out
    }

    fun write(buf: FriendlyByteBuf, anim: ParticleAnimation) {
        buf.writeVarInt(VERSION)
        buf.writeBoolean(anim.loop)

        buf.writeVarInt(anim.particles.size)
        for (p in anim.particles) writeParticle(buf, p)

        buf.writeVarInt(anim.tracks.size)
        for (tr in anim.tracks) writeTrack(buf, tr)

        buf.writeVarInt(anim.groups.size)
        for ((name, members) in anim.groups) {
            buf.writeUtf(name)
            buf.writeVarInt(members.size)
            for (id in members) buf.writeUtf(id)
        }

        buf.writeVarInt(anim.functions.size)
        for (fx in anim.functions) writeFunction(buf, fx)

        buf.writeVarInt(anim.textures.size)
        for (name in anim.textures) buf.writeUtf(name)

        buf.writeVarInt(anim.groupUV.size)
        for ((name, uv) in anim.groupUV) {
            buf.writeUtf(name)
            writeUV(buf, uv)
        }

        buf.writeVarInt(anim.texData.size)
        for ((name, bytes) in anim.texData) {
            buf.writeUtf(name)
            buf.writeVarInt(bytes.size)
            buf.writeBytes(bytes)
        }

        writeBoolMap(buf, anim.groupSpinSpace)
        writeBoolMap(buf, anim.groupRotSpace)

        buf.writeVarInt(anim.cameras.size)
        for (cam in anim.cameras) writeCamera(buf, cam)

        buf.writeVarInt(anim.texts.size)
        for (tx in anim.texts) writeText(buf, tx)

        buf.writeVarInt(anim.audioAssets.size)
        for (a in anim.audioAssets) writeAudio(buf, a)
    }

    fun read(buf: FriendlyByteBuf): ParticleAnimation {
        val version = buf.readVarInt()
        if (version != VERSION) throw LocalizedArgumentException(translation("error.packet.animation_version", version))

        val loop = buf.readBoolean()

        val particleCount = count(buf, MAX_PARTICLES)
        val particles = ArrayList<AnimParticle>(particleCount)
        repeat(particleCount) { particles.add(readParticle(buf)) }

        val trackCount = count(buf, MAX_TRACKS)
        val tracks = ArrayList<AnimTrack>(trackCount)
        repeat(trackCount) { tracks.add(readTrack(buf)) }

        val groupCount = count(buf, MAX_GROUPS)
        val groups = LinkedHashMap<String, List<String>>(groupCount)
        repeat(groupCount) {
            val name = buf.readUtf()
            val memberCount = count(buf, MAX_IDS)
            val members = ArrayList<String>(memberCount)
            repeat(memberCount) { members.add(buf.readUtf()) }
            groups[name] = members
        }

        val fxCount = count(buf, MAX_FUNCTIONS)
        val functions = ArrayList<FunctionObject>(fxCount)
        repeat(fxCount) { functions.add(readFunction(buf)) }

        val texCount = count(buf, MAX_TEXTURES)
        val textures = ArrayList<String>(texCount)
        repeat(texCount) { textures.add(buf.readUtf()) }

        val groupUVCount = count(buf, MAX_GROUPS)
        val groupUV = LinkedHashMap<String, UvData>(groupUVCount)
        repeat(groupUVCount) { groupUV[buf.readUtf()] = readUV(buf) }

        val texDataCount = count(buf, MAX_TEXTURES)
        val texData = LinkedHashMap<String, ByteArray>(texDataCount)
        repeat(texDataCount) {
            val name = buf.readUtf()
            texData[name] = bytes(buf, MAX_TEXTURE_BYTES)
        }

        val groupSpinSpace = readBoolMap(buf)
        val groupRotSpace = readBoolMap(buf)

        val camCount = count(buf, MAX_CAMERAS)
        val cameras = ArrayList<AnimCamera>(camCount)
        repeat(camCount) { cameras.add(readCamera(buf)) }

        val textCount = count(buf, MAX_TEXTS)
        val texts = ArrayList<TextObject>(textCount)
        repeat(textCount) { texts.add(readText(buf)) }

        val audioCount = count(buf, MAX_AUDIO)
        val audioAssets = ArrayList<AudioAsset>(audioCount)
        repeat(audioCount) { audioAssets.add(readAudio(buf)) }

        return ParticleAnimation(
            loop, particles, tracks, groups, functions,
            textures, groupUV, texData, groupSpinSpace, groupRotSpace, cameras, texts, audioAssets,
        )
    }

    private fun writeParticle(buf: FriendlyByteBuf, p: AnimParticle) {
        buf.writeUtf(p.id)
        buf.writeFloat(p.color.r)
        buf.writeFloat(p.color.g)
        buf.writeFloat(p.color.b)
        buf.writeFloat(p.color.a)
        buf.writeFloat(p.scale[0])
        buf.writeFloat(p.scale[1])
        buf.writeFloat(p.scale[2])
        buf.writeBoolean(p.glowing)
        buf.writeByte(p.lightLevel.coerceIn(0, 255))
        buf.writeDouble(p.pos.x)
        buf.writeDouble(p.pos.y)
        buf.writeDouble(p.pos.z)
        buf.writeDouble(p.vel.x)
        buf.writeDouble(p.vel.y)
        buf.writeDouble(p.vel.z)
        writeNullableUV(buf, p.uv)
        buf.writeVarInt(p.st)
        writeNullableEnt(buf, p.ent)
        buf.writeVarInt(p.life)
        buf.writeBoolean(p.billboard)   // v8
        buf.writeBoolean(p.spinLocal)
        writeRenderOptions(buf, p.renderOptions)
    }

    private fun readParticle(buf: FriendlyByteBuf): AnimParticle {
        val id = buf.readUtf()
        val color = Color.of(buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat())
        val scale = floatArrayOf(buf.readFloat(), buf.readFloat(), buf.readFloat())
        val glowing = buf.readBoolean()
        val lightLevel = buf.readByte().toInt()
        val pos = Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble())
        val vel = Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble())
        val uv = readNullableUV(buf)
        val st = buf.readVarInt()
        val ent = readNullableEnt(buf)
        val life = buf.readVarInt()
        val billboard = buf.readBoolean()   // v8
        val spinLocal = buf.readBoolean()   // v8
        return AnimParticle(id, color, scale, glowing, lightLevel, pos.toCore(), vel.toCore(), uv, st, ent, life, billboard, spinLocal, readRenderOptions(buf))
    }

    private fun writeTrack(buf: FriendlyByteBuf, tr: AnimTrack) {
        buf.writeByte(tr.pr.ordinal)
        buf.writeByte(if (tr.mode == AnimTrack.Mode.OP) 1 else 0)
        buf.writeVarInt(tr.ids.size)
        for (id in tr.ids) buf.writeUtf(id)
        buf.writeVarInt(tr.keyframes.size)
        for (kf in tr.keyframes) {
            buf.writeVarInt(kf.ms)
            buf.writeDouble(kf.value)
            writeEasingCompact(buf, kf.easing)
        }
    }

    private fun readTrack(buf: FriendlyByteBuf): AnimTrack {
        val prOrdinal = buf.readByte().toInt()
        val pr = TrackPr.fromOrdinal(prOrdinal)
            ?: throw LocalizedArgumentException(translation("error.animation.track_property", prOrdinal))
        val mode = if (buf.readByte().toInt() == 1) AnimTrack.Mode.OP else AnimTrack.Mode.SET
        val idCount = count(buf, MAX_IDS)
        val ids = ArrayList<String>(idCount)
        repeat(idCount) { ids.add(buf.readUtf()) }
        val kfCount = count(buf, MAX_KEYFRAMES)
        val keyframes = ArrayList<AnimKeyframe>(kfCount)
        repeat(kfCount) {
            keyframes.add(AnimKeyframe(buf.readVarInt(), buf.readDouble(), readEasingCompact(buf, strict = true)))
        }
        return AnimTrack(pr, ids, keyframes, mode)
    }

    private fun writeFunction(buf: FriendlyByteBuf, fx: FunctionObject) {
        buf.writeUtf(fx.id)
        buf.writeUtf(fx.name)
        buf.writeDouble(fx.center[0])
        buf.writeDouble(fx.center[1])
        buf.writeDouble(fx.center[2])
        buf.writeUtf(fx.source)
        buf.writeVarInt(fx.seed)
        buf.writeVarInt(fx.vars.size)
        for ((name, v) in fx.vars) {
            buf.writeUtf(name)
            buf.writeDouble(v.base)
            buf.writeVarInt(v.kf.size)
            for (kf in v.kf) {
                buf.writeDouble(kf.ms)
                buf.writeDouble(kf.value)
                writeEasingCompact(buf, kf.easing)
            }
        }
        buf.writeVarInt(fx.duration)
        writeNullableUV(buf, fx.uv)
        buf.writeVarInt(fx.st)
        writeNullableEnt(buf, fx.ent)
        buf.writeBoolean(fx.fastMath)
        buf.writeBoolean(fx.spinLocal)
        buf.writeBoolean(fx.rotLocal)
        buf.writeBoolean(fx.frameSync)
        buf.writeBoolean(fx.additive)
        writeRenderOptions(buf, fx.renderOptions)
    }

    private fun readFunction(buf: FriendlyByteBuf): FunctionObject {
        val id = buf.readUtf()
        val name = buf.readUtf()
        val center = doubleArrayOf(buf.readDouble(), buf.readDouble(), buf.readDouble())
        val source = buf.readUtf()
        val seed = buf.readVarInt()
        val varCount = count(buf, MAX_IDS)
        val vars = LinkedHashMap<String, FunctionVar>(varCount)
        repeat(varCount) {
            val varName = buf.readUtf()
            val base = buf.readDouble()
            val kfCount = count(buf, MAX_KEYFRAMES)
            val kf = ArrayList<Keyframe>(kfCount)
            repeat(kfCount) {
                kf.add(Keyframe(buf.readDouble(), buf.readDouble(), readEasingCompact(buf, strict = true)))
            }
            vars[varName] = FunctionVar(base, kf)
        }
        val duration = buf.readVarInt()
        val uv = readNullableUV(buf)
        val st = buf.readVarInt()
        val ent = readNullableEnt(buf)
        val fastMath = buf.readBoolean()
        val spinLocal = buf.readBoolean()
        val rotLocal = buf.readBoolean()
        val frameSync = buf.readBoolean()
        val additive = buf.readBoolean()
        return FunctionObject(id, name, center, source, seed, vars, duration, uv, st, ent, fastMath, spinLocal, rotLocal, frameSync, additive, readRenderOptions(buf))
    }

    private fun writeRenderOptions(buf: FriendlyByteBuf, options: ParticleRenderOptions) {
        buf.writeBoolean(options.active)
        if (!options.active) return
        buf.writeBoolean(options.alignVelocity)
        buf.writeDouble(options.stretch)
        buf.writeDouble(options.softFade)
        buf.writeByte(options.trailTicks)
        buf.writeByte(options.trailSides)
    }

    private fun readRenderOptions(buf: FriendlyByteBuf): ParticleRenderOptions =
        if (buf.readBoolean()) ParticleRenderOptions(buf.readBoolean(), buf.readDouble(), buf.readDouble(), buf.readUnsignedByte().toInt(), buf.readUnsignedByte().toInt())
        else ParticleRenderOptions.DEFAULT

    private fun writeCamera(buf: FriendlyByteBuf, cam: AnimCamera) {
        buf.writeUtf(cam.id)
        buf.writeUtf(cam.name)
        buf.writeDouble(cam.pos[0])
        buf.writeDouble(cam.pos[1])
        buf.writeDouble(cam.pos[2])
        buf.writeDouble(cam.target[0])
        buf.writeDouble(cam.target[1])
        buf.writeDouble(cam.target[2])
        buf.writeDouble(cam.roll)
        buf.writeDouble(cam.fov)
        buf.writeBoolean(cam.rotLocal)
    }

    private fun readCamera(buf: FriendlyByteBuf): AnimCamera {
        val id = buf.readUtf()
        val name = buf.readUtf()
        val pos = doubleArrayOf(buf.readDouble(), buf.readDouble(), buf.readDouble())
        val target = doubleArrayOf(buf.readDouble(), buf.readDouble(), buf.readDouble())
        val roll = buf.readDouble()
        val fov = buf.readDouble()
        val rotLocal = buf.readBoolean()
        return AnimCamera(id, name, pos, target, roll, fov, rotLocal)
    }

    private fun writeText(buf: FriendlyByteBuf, tx: TextObject) {
        buf.writeUtf(tx.id)
        buf.writeUtf(tx.name)
        buf.writeUtf(tx.text)
        buf.writeUtf(tx.font)
        buf.writeVarInt(tx.fontSize)
        buf.writeUtf(tx.weight)
        buf.writeBoolean(tx.italic)
        buf.writeFloat(tx.color.r)
        buf.writeFloat(tx.color.g)
        buf.writeFloat(tx.color.b)
        buf.writeFloat(tx.color.a)
        writeNullableColor(buf, tx.strokeColor)
        buf.writeVarInt(tx.strokeWidth)
        buf.writeUtf(tx.align)
        buf.writeDouble(tx.lineHeight)
        buf.writeDouble(tx.letterSpacing)
        buf.writeVarInt(tx.st)
        buf.writeVarInt(tx.life)
        buf.writeBoolean(tx.spinLocal)   // v8
        buf.writeBoolean(tx.rotLocal)    // v8
        buf.writeBoolean(tx.billboard)   // v8
        buf.writeVarInt(tx.chars.size)
        for (c in tx.chars) {
            buf.writeVarInt(c.index)
            buf.writeVarInt(c.code)
            buf.writeDouble(c.pos.x)
            buf.writeDouble(c.pos.y)
            buf.writeDouble(c.pos.z)
            buf.writeDouble(c.size[0])
            buf.writeDouble(c.size[1])
            buf.writeVarInt(c.particles.size)
            for (id in c.particles) buf.writeUtf(id)
        }
    }

    private fun readText(buf: FriendlyByteBuf): TextObject {
        val id = buf.readUtf()
        val name = buf.readUtf()
        val text = buf.readUtf()
        val font = buf.readUtf()
        val fontSize = buf.readVarInt()
        val weight = buf.readUtf()
        val italic = buf.readBoolean()
        val color = Color.of(buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat())
        val strokeColor = readNullableColor(buf)
        val strokeWidth = buf.readVarInt()
        val align = buf.readUtf()
        val lineHeight = buf.readDouble()
        val letterSpacing = buf.readDouble()
        val st = buf.readVarInt()
        val life = buf.readVarInt()
        val spinLocal = buf.readBoolean()   // v8
        val rotLocal = buf.readBoolean()    // v8
        val billboard = buf.readBoolean()   // v8
        val charCount = count(buf, MAX_CHARS)
        val chars = ArrayList<TextChar>(charCount)
        repeat(charCount) {
            val index = buf.readVarInt()
            val code = buf.readVarInt()
            val pos = Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble())
            val size = doubleArrayOf(buf.readDouble(), buf.readDouble())
            val pn = count(buf, MAX_IDS)
            val ids = ArrayList<String>(pn)
            repeat(pn) { ids.add(buf.readUtf()) }
            chars.add(TextChar(index, code, pos, size, ids))
        }
        return TextObject(id, name, text, font, fontSize, weight, italic, color, strokeColor, strokeWidth, align, lineHeight, letterSpacing, st, life, chars, spinLocal, rotLocal, billboard)
    }

    private fun writeNullableColor(buf: FriendlyByteBuf, c: Color?) {
        buf.writeBoolean(c != null)
        if (c != null) {
            buf.writeFloat(c.r)
            buf.writeFloat(c.g)
            buf.writeFloat(c.b)
            buf.writeFloat(c.a)
        }
    }

    private fun readNullableColor(buf: FriendlyByteBuf): Color? =
        if (buf.readBoolean()) Color.of(buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat()) else null

    private fun writeAudio(buf: FriendlyByteBuf, a: AudioAsset) {
        buf.writeUtf(a.id)
        buf.writeUtf(a.name)
        buf.writeVarInt(a.fmt)
        buf.writeVarInt(a.data.size)
        buf.writeBytes(a.data)
        buf.writeVarInt(a.st)
        buf.writeVarInt(a.durMs)
        buf.writeDouble(a.vol)      // v9
        buf.writeDouble(a.speed)    // v9
        buf.writeDouble(a.pan)      // v9
        buf.writeVarInt(a.fadeIn)   // v9
        buf.writeVarInt(a.fadeOut)  // v9
        buf.writeVarInt(a.hopCount)
        buf.writeDouble(a.bpm)
        buf.writeDouble(a.beatOffsetMs)
        buf.writeDouble(a.onsetMax)
        buf.writeVarInt(a.beats.size)
        for (b in a.beats) buf.writeVarInt(b)
        buf.writeVarInt(a.rms.size)
        for (v in a.rms) buf.writeShort(v.toInt())
        buf.writeVarInt(a.peak.size)
        for (v in a.peak) buf.writeShort(v.toInt())
        buf.writeVarInt(a.centroid.size)
        for (v in a.centroid) buf.writeShort(v.toInt())
        buf.writeVarInt(a.onset.size)
        buf.writeBytes(a.onset)
        buf.writeVarInt(a.rolloff.size)
        buf.writeBytes(a.rolloff)
        buf.writeVarInt(a.bands.size)
        buf.writeBytes(a.bands)
    }

    private fun readAudio(buf: FriendlyByteBuf): AudioAsset {
        val id = buf.readUtf()
        val name = buf.readUtf()
        val fmt = buf.readVarInt()
        val data = bytes(buf, MAX_AUDIO_BYTES)
        val st = buf.readVarInt()
        val durMs = buf.readVarInt()
        val vol = buf.readDouble()      // v9
        val speed = buf.readDouble()    // v9
        val pan = buf.readDouble()      // v9
        val fadeIn = buf.readVarInt()   // v9
        val fadeOut = buf.readVarInt()  // v9
        val hopCount = count(buf, MAX_HOPS)
        val bpm = buf.readDouble()
        val beatOffsetMs = buf.readDouble()
        val onsetMax = buf.readDouble()
        val beatCount = count(buf, MAX_BEATS)
        val beats = ArrayList<Int>(beatCount)
        repeat(beatCount) { beats.add(buf.readVarInt()) }
        val rms = ShortArray(count(buf, MAX_HOPS)) { buf.readShort() }
        val peak = ShortArray(count(buf, MAX_HOPS)) { buf.readShort() }
        val centroid = ShortArray(count(buf, MAX_HOPS)) { buf.readShort() }
        val onset = bytes(buf, MAX_HOPS)
        val rolloff = bytes(buf, MAX_HOPS)
        val bands = bytes(buf, MAX_BAND_BYTES)
        return AudioAsset(
            id, name, fmt, data, st, durMs, hopCount, bpm, beatOffsetMs, onsetMax, beats,
            rms, peak, centroid, onset, rolloff, bands,
            vol = vol, speed = speed, pan = pan, fadeIn = fadeIn, fadeOut = fadeOut,
        )
    }

    private fun writeUV(buf: FriendlyByteBuf, uv: UvData) {
        writeNullableString(buf, uv.texture)
        buf.writeByte(uv.mode.ordinal)
        buf.writeVarInt(uv.texSize[0])
        buf.writeVarInt(uv.texSize[1])
        buf.writeVarInt(uv.uvStart[0])
        buf.writeVarInt(uv.uvStart[1])
        buf.writeVarInt(uv.uvSize[0])
        buf.writeVarInt(uv.uvSize[1])
        buf.writeVarInt(uv.uvStep[0])
        buf.writeVarInt(uv.uvStep[1])
        buf.writeFloat(uv.fps)
        buf.writeVarInt(uv.maxFrame)
        buf.writeBoolean(uv.loop)
        // v2：loop 后 1 字节 exprFlags，按位序写存在的表达式字符串（null = 用数值字段）
        var exprFlags = 0
        if (uv.uvStartExpr[0] != null) exprFlags = exprFlags or (1 shl 0)
        if (uv.uvStartExpr[1] != null) exprFlags = exprFlags or (1 shl 1)
        if (uv.uvSizeExpr[0] != null) exprFlags = exprFlags or (1 shl 2)
        if (uv.uvSizeExpr[1] != null) exprFlags = exprFlags or (1 shl 3)
        if (uv.uvStepExpr[0] != null) exprFlags = exprFlags or (1 shl 4)
        if (uv.uvStepExpr[1] != null) exprFlags = exprFlags or (1 shl 5)
        if (uv.fpsExpr != null) exprFlags = exprFlags or (1 shl 6)
        if (uv.maxFrameExpr != null) exprFlags = exprFlags or (1 shl 7)
        buf.writeByte(exprFlags)
        uv.uvStartExpr[0]?.let { buf.writeUtf(it) }
        uv.uvStartExpr[1]?.let { buf.writeUtf(it) }
        uv.uvSizeExpr[0]?.let { buf.writeUtf(it) }
        uv.uvSizeExpr[1]?.let { buf.writeUtf(it) }
        uv.uvStepExpr[0]?.let { buf.writeUtf(it) }
        uv.uvStepExpr[1]?.let { buf.writeUtf(it) }
        uv.fpsExpr?.let { buf.writeUtf(it) }
        uv.maxFrameExpr?.let { buf.writeUtf(it) }
    }

    private fun readUV(buf: FriendlyByteBuf): UvData {
        val texture = readNullableString(buf)
        val modeIndex = buf.readByte().toInt()
        val mode = UvData.Mode.entries.getOrNull(modeIndex)
            ?: throw LocalizedArgumentException(translation("error.packet.animation_mode", modeIndex))
        val texSize = intArrayOf(buf.readVarInt(), buf.readVarInt())
        val uvStart = intArrayOf(buf.readVarInt(), buf.readVarInt())
        val uvSize = intArrayOf(buf.readVarInt(), buf.readVarInt())
        val uvStep = intArrayOf(buf.readVarInt(), buf.readVarInt())
        val fps = buf.readFloat()
        val maxFrame = buf.readVarInt()
        val loop = buf.readBoolean()
        val exprFlags = buf.readByte().toInt()
        val uvStartExpr = arrayOfNulls<String>(2)
        val uvSizeExpr = arrayOfNulls<String>(2)
        val uvStepExpr = arrayOfNulls<String>(2)
        if (exprFlags and (1 shl 0) != 0) uvStartExpr[0] = buf.readUtf()
        if (exprFlags and (1 shl 1) != 0) uvStartExpr[1] = buf.readUtf()
        if (exprFlags and (1 shl 2) != 0) uvSizeExpr[0] = buf.readUtf()
        if (exprFlags and (1 shl 3) != 0) uvSizeExpr[1] = buf.readUtf()
        if (exprFlags and (1 shl 4) != 0) uvStepExpr[0] = buf.readUtf()
        if (exprFlags and (1 shl 5) != 0) uvStepExpr[1] = buf.readUtf()
        val fpsExpr = if (exprFlags and (1 shl 6) != 0) buf.readUtf() else null
        val maxFrameExpr = if (exprFlags and (1 shl 7) != 0) buf.readUtf() else null
        return UvData(
            texture, mode, texSize, uvStart, uvSize, uvStep, fps, maxFrame, loop,
            uvStartExpr, uvSizeExpr, uvStepExpr, fpsExpr, maxFrameExpr,
        )
    }

    private fun writeNullableUV(buf: FriendlyByteBuf, uv: UvData?) {
        buf.writeBoolean(uv != null)
        if (uv != null) writeUV(buf, uv)
    }

    private fun readNullableUV(buf: FriendlyByteBuf): UvData? =
        if (buf.readBoolean()) readUV(buf) else null

    private fun writeNullableEnt(buf: FriendlyByteBuf, ent: Entrance?) {
        buf.writeBoolean(ent != null)
        if (ent != null) {
            buf.writeUtf(ent.preset)
            buf.writeVarInt(ent.dur)
        }
    }

    private fun readNullableEnt(buf: FriendlyByteBuf): Entrance? =
        if (buf.readBoolean()) Entrance(buf.readUtf(), buf.readVarInt()) else null

    private fun writeBoolMap(buf: FriendlyByteBuf, map: Map<String, Boolean>) {
        buf.writeVarInt(map.size)
        for ((k, v) in map) {
            buf.writeUtf(k)
            buf.writeBoolean(v)
        }
    }

    private fun readBoolMap(buf: FriendlyByteBuf): Map<String, Boolean> {
        val n = count(buf, MAX_GROUPS)
        val out = LinkedHashMap<String, Boolean>(n)
        repeat(n) { out[buf.readUtf()] = buf.readBoolean() }
        return out
    }

    private fun writeNullableString(buf: FriendlyByteBuf, value: String?) {
        buf.writeBoolean(value != null)
        if (value != null) buf.writeUtf(value)
    }

    private fun readNullableString(buf: FriendlyByteBuf): String? =
        if (buf.readBoolean()) buf.readUtf() else null
}