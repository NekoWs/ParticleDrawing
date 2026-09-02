package work.nekow.particledrawing

import io.netty.buffer.Unpooled
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
import work.nekow.particledrawing.core.network.ParticleAnimationCodec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 代码生成动画网络载荷编解码回归测试：完整模型往返后逐字段一致。
 */
class ParticleAnimationCodecTest {

    private fun sample(): ParticleAnimation {
        val particle = AnimParticle(
            id = "p0",
            color = Color.of(0.1f, 0.2f, 0.3f, 0.4f),
            scale = floatArrayOf(1f, 2f, 3f),
            glowing = true,
            lightLevel = 12,
            pos = Vec3(1.0, 2.0, 3.0),
            vel = Vec3(0.1, 0.2, 0.3),
            uv = UvData(
                "tex", UvData.Mode.ANIMATED,
                intArrayOf(16, 16), intArrayOf(0, 0), intArrayOf(8, 8), intArrayOf(8, 8),
                4f, 2, true,
            ),
            st = 3,
            ent = Entrance("fade", 5),
            life = 20,
        )
        val track = AnimTrack(
            pr = "pos.x", ids = listOf("p0"),
            keyframes = listOf(
                AnimKeyframe(0, 0.0, EasingType.LINEAR),
                AnimKeyframe(10, 5.0, EasingType.custom(0.1, 0.2, 0.3, 0.4)),
                AnimKeyframe(20, 10.0, EasingType.NONE),
            ),
            mode = AnimTrack.Mode.SET,
        )
        val fx = FunctionObject(
            id = "fx0", name = "fx0",
            center = doubleArrayOf(0.0, 1.0, 2.0),
            count = 4,
            setup = "global a = [];",
            process = "this.position = vec3(0,0,0);",
            funcs = "func f(n) { return n; }",
            seed = 7,
            vars = linkedMapOf("rad" to FunctionVar(3.0, listOf(Keyframe(0.0, 1.0, EasingType.LINEAR)))),
            duration = 100,
            step = 1,
            uv = UvData(
                "tex", UvData.Mode.FILL,
                intArrayOf(4, 4), intArrayOf(0, 0), intArrayOf(4, 4), intArrayOf(0, 0),
                1f, 1, false,
            ),
            st = 5,
            ent = Entrance("fade", 2),
            fastMath = true,
            spinLocal = true,
            rotLocal = false,
        )
        val cam = AnimCamera("cam1", "Cam", doubleArrayOf(0.0, 10.0, 0.0), doubleArrayOf(0.0, 0.0, 0.0), 0.0, 70.0, true)
        return ParticleAnimation(
            loop = true,
            particles = listOf(particle),
            tracks = listOf(track),
            groups = linkedMapOf("g0" to listOf("p0")),
            functions = listOf(fx),
            textures = listOf("tex"),
            groupUV = linkedMapOf(
                "g0" to UvData("tex", UvData.Mode.STATIC, intArrayOf(8, 8), intArrayOf(0, 0), intArrayOf(8, 8), intArrayOf(0, 0), 1f, 1, false),
            ),
            texData = linkedMapOf("tex" to byteArrayOf(1, 2, 3, 4)),
            groupSpinSpace = linkedMapOf("g0" to true),
            groupRotSpace = linkedMapOf("g0" to false),
            cameras = listOf(cam),
        )
    }

    @Test
    fun roundTripPreservesFullModel() {
        val original = sample()
        val buf = FriendlyByteBuf(Unpooled.buffer())
        ParticleAnimationCodec.write(buf, original)
        val decoded = ParticleAnimationCodec.read(buf)

        assertTrue(decoded.loop)
        assertEquals(1, decoded.particles.size)
        assertEquals(1, decoded.tracks.size)
        assertEquals(linkedMapOf("g0" to listOf("p0")), decoded.groups)
        assertEquals(1, decoded.functions.size)
        assertEquals(listOf("tex"), decoded.textures)
        assertEquals(1, decoded.groupUV.size)
        assertEquals(1, decoded.texData.size)
        assertEquals(linkedMapOf("g0" to true), decoded.groupSpinSpace)
        assertEquals(linkedMapOf("g0" to false), decoded.groupRotSpace)
        assertEquals(1, decoded.cameras.size)

        val p = decoded.particles[0]
        assertEquals("p0", p.id)
        assertEquals(0.1f, p.color.r, 1e-6f)
        assertEquals(0.2f, p.color.g, 1e-6f)
        assertEquals(0.3f, p.color.b, 1e-6f)
        assertEquals(0.4f, p.color.a, 1e-6f)
        assertEquals(listOf(1f, 2f, 3f), p.scale.toList())
        assertTrue(p.glowing)
        assertEquals(12, p.lightLevel)
        assertEquals(Vec3(1.0, 2.0, 3.0), p.pos)
        assertEquals(Vec3(0.1, 0.2, 0.3), p.vel)
        assertEquals("tex", p.uv?.texture)
        assertEquals(UvData.Mode.ANIMATED, p.uv?.mode)
        assertEquals(3, p.st)
        assertEquals(Entrance("fade", 5), p.ent)
        assertEquals(20, p.life)

        val tr = decoded.tracks[0]
        assertEquals("pos.x", tr.pr)
        assertEquals(AnimTrack.Mode.SET, tr.mode)
        assertEquals(listOf("p0"), tr.ids)
        assertEquals(3, tr.keyframes.size)
        assertEquals(EasingType.LINEAR, tr.keyframes[0].easing)
        assertEquals(EasingType.custom(0.1, 0.2, 0.3, 0.4), tr.keyframes[1].easing)
        assertEquals(EasingType.NONE, tr.keyframes[2].easing)

        val fx = decoded.functions[0]
        assertEquals("fx0", fx.id)
        assertEquals(listOf(0.0, 1.0, 2.0), fx.center.toList())
        assertEquals(4, fx.count)
        assertEquals("global a = [];", fx.setup)
        assertEquals("this.position = vec3(0,0,0);", fx.process)
        assertEquals("func f(n) { return n; }", fx.funcs)
        assertEquals(7, fx.seed)
        assertEquals(3.0, fx.vars["rad"]?.base ?: -1.0)
        assertEquals(1, fx.vars["rad"]?.kf?.size)
        assertEquals(100, fx.duration)
        assertEquals(1, fx.step)
        assertEquals("tex", fx.uv?.texture)
        assertEquals(5, fx.st)
        assertEquals(Entrance("fade", 2), fx.ent)
        assertTrue(fx.fastMath)
        assertTrue(fx.spinLocal)
        assertFalse(fx.rotLocal)

        val cam = decoded.cameras[0]
        assertEquals("cam1", cam.id)
        assertEquals("Cam", cam.name)
        assertEquals(listOf(0.0, 10.0, 0.0), cam.pos.toList())
        assertEquals(listOf(0.0, 0.0, 0.0), cam.target.toList())
        assertEquals(0.0, cam.roll)
        assertEquals(70.0, cam.fov)
        assertTrue(cam.rotLocal)

        assertContentEquals(byteArrayOf(1, 2, 3, 4), decoded.texData["tex"] ?: ByteArray(0))
    }

    @Test
    fun nullFieldsRoundTrip() {
        val original = ParticleAnimation(
            loop = false,
            particles = listOf(
                AnimParticle("p0", Color.WHITE, floatArrayOf(1f, 1f, 1f), false, 0, Vec3.ZERO, Vec3.ZERO),
            ),
            tracks = emptyList(),
            groups = emptyMap(),
            functions = emptyList(),
            textures = emptyList(),
            groupUV = emptyMap(),
            texData = emptyMap(),
            groupSpinSpace = emptyMap(),
            groupRotSpace = emptyMap(),
            cameras = emptyList(),
        )
        val buf = FriendlyByteBuf(Unpooled.buffer())
        ParticleAnimationCodec.write(buf, original)
        val decoded = ParticleAnimationCodec.read(buf)

        assertFalse(decoded.loop)
        assertEquals(1, decoded.particles.size)
        assertNull(decoded.particles[0].uv)
        assertNull(decoded.particles[0].ent)
        assertTrue(decoded.tracks.isEmpty())
        assertTrue(decoded.functions.isEmpty())
        assertTrue(decoded.cameras.isEmpty())
    }

    @Test
    fun versionMismatchRejected() {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeVarInt(ParticleAnimationCodec.VERSION + 999)
        assertFailsWith<IllegalArgumentException> {
            ParticleAnimationCodec.read(buf)
        }
    }
}