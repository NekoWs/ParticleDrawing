package work.nekow.particledrawing

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.animation.AnimTrack
import work.nekow.particledrawing.animation.UvData
import work.nekow.particledrawing.api.Animation
import work.nekow.particledrawing.api.Color
import work.nekow.particledrawing.core.easing.EasingType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Animation 构建器（Kotlin DSL 与 Java Builder 双入口）回归测试。
 */
class AnimationBuilderTest {

    @Test
    fun kotlinDslBuildsExpectedModel() {
        val anim = Animation.create {
            loop = true
            particle {
                id = "p0"
                pos = Vec3(0.0, 10.0, 0.0)
                color = Color.CYAN
                scale = 1f
                life = -1
            }
            track {
                pr = "pos.x"
                ids = listOf("p0")
                keyframe(0, 10.0, EasingType.LINEAR)
            }
            function {
                id = "fx0"
                count = 8
                center = Vec3(0.0, 10.0, 0.0)
                duration = 100
                seed = 3
                variable("rad", 4.0)
                setup("global a = [];")
                process("this.position = vec3(0,0,0);")
            }
        }

        val model = anim.animationModel
        assertTrue(model.loop)
        assertEquals(1, model.particles.size)
        assertEquals("p0", model.particles[0].id)
        assertEquals(Vec3(0.0, 10.0, 0.0), model.particles[0].pos)
        assertEquals(Color.CYAN, model.particles[0].color)
        assertEquals(-1, model.particles[0].life)

        assertEquals(1, model.tracks.size)
        val track = model.tracks[0]
        assertEquals("pos.x", track.pr)
        assertEquals(AnimTrack.Mode.SET, track.mode)
        assertEquals(listOf("p0"), track.ids)
        assertEquals(1, track.keyframes.size)
        assertEquals(10.0, track.keyframes[0].value)

        assertEquals(1, model.functions.size)
        val fx = model.functions[0]
        assertEquals("fx0", fx.id)
        assertEquals(8, fx.count)
        assertEquals(100, fx.duration)
        assertEquals(3, fx.seed)
        assertEquals(4.0, fx.vars["rad"]?.base ?: -1.0)
    }

    @Test
    fun scriptDslInFunctionGeneratesProcessText() {
        val anim = Animation.create {
            function {
                id = "fx0"
                count = 8
                variable("rad", 4.0)
                process {
                    var th by numVar()
                    th = index / count * 2 * pi
                    position = vec3(cos(th) * v("rad"), 0, sin(th) * v("rad"))
                }
            }
        }

        val fx = anim.animationModel.functions[0]
        assertEquals(
            listOf(
                "th = this.index / this.count * 2 * pi;",
                "this.position = vec3(cos(th) * rad, 0, sin(th) * rad);",
            ).joinToString("\n"),
            fx.process,
        )
    }

    @Test
    fun javaStyleBuilderBuildsExpectedModel() {
        val anim = Animation.builder()
            .loop(false)
            .particle { p ->
                p.id("p0").pos(0, 10, 0).color(Color.CYAN).scale(1f).life(-1)
            }
            .track { t ->
                t.pr("pos.x").mode(AnimTrack.Mode.SET).ids("p0").keyframe(0, 0.0, EasingType.LINEAR)
            }
            .function { f ->
                f.id("fx0").count(8).center(0, 10, 0).duration(100).seed(3)
                    .variable("rad", 4.0)
                    .setup("global a = [];")
                    .process("this.position = vec3(0,0,0);")
            }
            .group("g0", "p0")
            .groupSpinSpace("g0", true)
            .texture("tex", byteArrayOf(1, 2, 3))
            .groupUV(
                "g0",
                UvData("tex", UvData.Mode.STATIC, intArrayOf(8, 8), intArrayOf(0, 0), intArrayOf(8, 8), intArrayOf(0, 0), 1f, 1, false),
            )
            .build()

        val model = anim.animationModel
        assertEquals(false, model.loop)
        assertEquals("p0", model.particles[0].id)
        assertEquals("fx0", model.functions[0].id)
        assertEquals(mapOf("g0" to listOf("p0")), model.groups)
        assertEquals(mapOf("g0" to true), model.groupSpinSpace)
        assertEquals(listOf("tex"), model.textures)
        assertEquals(true, model.texData["tex"]?.contentEquals(byteArrayOf(1, 2, 3)))
        assertEquals("tex", model.groupUV["g0"]?.texture)
    }
}