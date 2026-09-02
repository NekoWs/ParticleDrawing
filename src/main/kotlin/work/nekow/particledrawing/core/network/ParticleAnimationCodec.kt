package work.nekow.particledrawing.core.network

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.animation.AnimCamera
import work.nekow.particledrawing.animation.AnimKeyframe
import work.nekow.particledrawing.animation.AnimParticle
import work.nekow.particledrawing.animation.AnimTrack
import work.nekow.particledrawing.animation.Entrance
import work.nekow.particledrawing.animation.FunctionObject
import work.nekow.particledrawing.animation.FunctionVar
import work.nekow.particledrawing.animation.ParticleAnimation
import work.nekow.particledrawing.animation.UvData
import work.nekow.particledrawing.animation.script.Keyframe
import work.nekow.particledrawing.api.Color
import work.nekow.particledrawing.core.easing.EasingType

/**
 * 代码生成 [ParticleAnimation] 的网络编解码（手写二进制，与 .pdrawc 无关，不签名）。
 *
 * 布局：version varint + loop + particles + tracks + groups + functions + textures +
 * groupUV + texData + groupSpinSpace + groupRotSpace + cameras。
 * 客户端按同一版本号解析；版本不符抛异常拒绝播放。
 */
internal object ParticleAnimationCodec {

    const val VERSION = 1

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
    }

    fun read(buf: FriendlyByteBuf): ParticleAnimation {
        val version = buf.readVarInt()
        if (version != VERSION) throw IllegalArgumentException("ParticleAnimation 载荷版本不支持: $version")

        val loop = buf.readBoolean()

        val particleCount = buf.readVarInt()
        val particles = ArrayList<AnimParticle>(particleCount)
        repeat(particleCount) { particles.add(readParticle(buf)) }

        val trackCount = buf.readVarInt()
        val tracks = ArrayList<AnimTrack>(trackCount)
        repeat(trackCount) { tracks.add(readTrack(buf)) }

        val groupCount = buf.readVarInt()
        val groups = LinkedHashMap<String, List<String>>(groupCount)
        repeat(groupCount) {
            val name = buf.readUtf()
            val memberCount = buf.readVarInt()
            val members = ArrayList<String>(memberCount)
            repeat(memberCount) { members.add(buf.readUtf()) }
            groups[name] = members
        }

        val fxCount = buf.readVarInt()
        val functions = ArrayList<FunctionObject>(fxCount)
        repeat(fxCount) { functions.add(readFunction(buf)) }

        val texCount = buf.readVarInt()
        val textures = ArrayList<String>(texCount)
        repeat(texCount) { textures.add(buf.readUtf()) }

        val groupUVCount = buf.readVarInt()
        val groupUV = LinkedHashMap<String, UvData>(groupUVCount)
        repeat(groupUVCount) { groupUV[buf.readUtf()] = readUV(buf) }

        val texDataCount = buf.readVarInt()
        val texData = LinkedHashMap<String, ByteArray>(texDataCount)
        repeat(texDataCount) {
            val name = buf.readUtf()
            val len = buf.readVarInt()
            val bytes = ByteArray(len)
            buf.readBytes(bytes)
            texData[name] = bytes
        }

        val groupSpinSpace = readBoolMap(buf)
        val groupRotSpace = readBoolMap(buf)

        val camCount = buf.readVarInt()
        val cameras = ArrayList<AnimCamera>(camCount)
        repeat(camCount) { cameras.add(readCamera(buf)) }

        return ParticleAnimation(
            loop, particles, tracks, groups, functions,
            textures, groupUV, texData, groupSpinSpace, groupRotSpace, cameras,
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
        return AnimParticle(id, color, scale, glowing, lightLevel, pos, vel, uv, st, ent, life)
    }

    private fun writeTrack(buf: FriendlyByteBuf, tr: AnimTrack) {
        buf.writeUtf(tr.pr)
        buf.writeByte(if (tr.mode == AnimTrack.Mode.OP) 1 else 0)
        buf.writeVarInt(tr.ids.size)
        for (id in tr.ids) buf.writeUtf(id)
        buf.writeVarInt(tr.keyframes.size)
        for (kf in tr.keyframes) {
            buf.writeVarInt(kf.tick)
            buf.writeDouble(kf.value)
            writeEasing(buf, kf.easing)
        }
    }

    private fun readTrack(buf: FriendlyByteBuf): AnimTrack {
        val pr = buf.readUtf()
        val mode = if (buf.readByte().toInt() == 1) AnimTrack.Mode.OP else AnimTrack.Mode.SET
        val idCount = buf.readVarInt()
        val ids = ArrayList<String>(idCount)
        repeat(idCount) { ids.add(buf.readUtf()) }
        val kfCount = buf.readVarInt()
        val keyframes = ArrayList<AnimKeyframe>(kfCount)
        repeat(kfCount) {
            keyframes.add(AnimKeyframe(buf.readVarInt(), buf.readDouble(), readEasing(buf)))
        }
        return AnimTrack(pr, ids, keyframes, mode)
    }

    private fun writeFunction(buf: FriendlyByteBuf, fx: FunctionObject) {
        buf.writeUtf(fx.id)
        buf.writeUtf(fx.name)
        buf.writeDouble(fx.center[0])
        buf.writeDouble(fx.center[1])
        buf.writeDouble(fx.center[2])
        buf.writeVarInt(fx.count)
        buf.writeUtf(fx.setup)
        buf.writeUtf(fx.process)
        buf.writeUtf(fx.funcs)
        buf.writeVarInt(fx.seed)
        buf.writeVarInt(fx.vars.size)
        for ((name, v) in fx.vars) {
            buf.writeUtf(name)
            buf.writeDouble(v.base)
            buf.writeVarInt(v.kf.size)
            for (kf in v.kf) {
                buf.writeDouble(kf.tick)
                buf.writeDouble(kf.value)
                writeEasing(buf, kf.easing)
            }
        }
        buf.writeVarInt(fx.duration)
        buf.writeVarInt(fx.step)
        writeNullableUV(buf, fx.uv)
        buf.writeVarInt(fx.st)
        writeNullableEnt(buf, fx.ent)
        buf.writeBoolean(fx.fastMath)
        buf.writeBoolean(fx.spinLocal)
        buf.writeBoolean(fx.rotLocal)
    }

    private fun readFunction(buf: FriendlyByteBuf): FunctionObject {
        val id = buf.readUtf()
        val name = buf.readUtf()
        val center = doubleArrayOf(buf.readDouble(), buf.readDouble(), buf.readDouble())
        val count = buf.readVarInt()
        val setup = buf.readUtf()
        val process = buf.readUtf()
        val funcs = buf.readUtf()
        val seed = buf.readVarInt()
        val varCount = buf.readVarInt()
        val vars = LinkedHashMap<String, FunctionVar>(varCount)
        repeat(varCount) {
            val varName = buf.readUtf()
            val base = buf.readDouble()
            val kfCount = buf.readVarInt()
            val kf = ArrayList<Keyframe>(kfCount)
            repeat(kfCount) {
                kf.add(Keyframe(buf.readDouble(), buf.readDouble(), readEasing(buf)))
            }
            vars[varName] = FunctionVar(base, kf)
        }
        val duration = buf.readVarInt()
        val step = buf.readVarInt()
        val uv = readNullableUV(buf)
        val st = buf.readVarInt()
        val ent = readNullableEnt(buf)
        val fastMath = buf.readBoolean()
        val spinLocal = buf.readBoolean()
        val rotLocal = buf.readBoolean()
        return FunctionObject(id, name, center, count, setup, process, funcs, seed, vars, duration, step, uv, st, ent, fastMath, spinLocal, rotLocal)
    }

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
    }

    private fun readUV(buf: FriendlyByteBuf): UvData {
        val texture = readNullableString(buf)
        val mode = UvData.Mode.entries[buf.readByte().toInt()]
        val texSize = intArrayOf(buf.readVarInt(), buf.readVarInt())
        val uvStart = intArrayOf(buf.readVarInt(), buf.readVarInt())
        val uvSize = intArrayOf(buf.readVarInt(), buf.readVarInt())
        val uvStep = intArrayOf(buf.readVarInt(), buf.readVarInt())
        val fps = buf.readFloat()
        val maxFrame = buf.readVarInt()
        val loop = buf.readBoolean()
        return UvData(texture, mode, texSize, uvStart, uvSize, uvStep, fps, maxFrame, loop)
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

    private fun writeEasing(buf: FriendlyByteBuf, easing: EasingType) {
        when {
            easing.isStep() -> buf.writeByte(2)
            easing.isPreset() -> {
                buf.writeByte(0)
                buf.writeVarInt(easing.ordinal)
            }
            else -> {
                buf.writeByte(1)
                buf.writeDouble(easing.curve.x1)
                buf.writeDouble(easing.curve.y1)
                buf.writeDouble(easing.curve.x2)
                buf.writeDouble(easing.curve.y2)
            }
        }
    }

    private fun readEasing(buf: FriendlyByteBuf): EasingType = when (buf.readByte().toInt()) {
        0 -> {
            val idx = buf.readVarInt()
            if (idx !in EasingType.PRESETS.indices) throw IllegalArgumentException("缓动预设越界: $idx")
            EasingType.PRESETS[idx]
        }
        1 -> EasingType.custom(buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble())
        2 -> EasingType.NONE
        else -> throw IllegalArgumentException("未知缓动类型")
    }

    private fun writeBoolMap(buf: FriendlyByteBuf, map: Map<String, Boolean>) {
        buf.writeVarInt(map.size)
        for ((k, v) in map) {
            buf.writeUtf(k)
            buf.writeBoolean(v)
        }
    }

    private fun readBoolMap(buf: FriendlyByteBuf): Map<String, Boolean> {
        val n = buf.readVarInt()
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