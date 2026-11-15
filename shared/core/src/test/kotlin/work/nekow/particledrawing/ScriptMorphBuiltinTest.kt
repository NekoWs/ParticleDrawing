package work.nekow.particledrawing

import work.nekow.particledrawing.animation.script.ParticleHost
import work.nekow.particledrawing.animation.script.ScriptException
import work.nekow.particledrawing.animation.script.ScriptRuntime
import work.nekow.particledrawing.animation.script.parseProgram
import kotlin.test.*

/** 脚本内建 `morph(slots, options)` 的行为契约。 */
class ScriptMorphBuiltinTest {

    private class Host(override val index: Int, x: Double, y: Double = 0.0) : ParticleHost {
        override val pos = doubleArrayOf(x, y, 0.0)
        override val color = doubleArrayOf(1.0, 1.0, 1.0, 1.0)
        override val vel = DoubleArray(3)
        override val scale = DoubleArray(3) { 1.0 }
        override var scaleDim = 0
        override var glow = false
        override var light = 0.0
        override var life = -1.0
        override val rotation = DoubleArray(3)
        override var billboard = true
        override var spinLocal = true
        override val fields = HashMap<String, Any?>()
        override fun kill() = Unit
    }

    private fun run(source: String, hosts: List<Host>, frames: Int): ScriptRuntime.ObjectState {
        val program = parseProgram(source)
        val obj = ScriptRuntime.createObjectState(0)
        val ctx = ScriptRuntime.ScriptCtx(
            0.0, 100.0, emptyMap(), hosts.toMutableList<work.nekow.particledrawing.animation.script.ParticleHost>(),
            { hosts.first() }, 0.0, false, {},
        )
        ScriptRuntime.runSpawnSetup(program, obj, ctx)
        obj.morph?.apply(frames * 50.0, ctx.particles)
        return obj
    }

    @Test
    fun `morph 把粒子拉到目标槽位`() {
        val hosts = (0 until 6).map { Host(it, it * 1.0) }
        val obj = run(
            """
            func setup() {
                morph([
                    vec3(0, 5, 0), vec3(1, 5, 0), vec3(2, 5, 0),
                    vec3(3, 5, 0), vec3(4, 5, 0), vec3(5, 5, 0)
                ], { ticks: 10, easing: "linear" })
            }
            """.trimIndent(),
            hosts,
            frames = 14,
        )
        assertNotNull(obj.morph)
        for (h in hosts) {
            assertEquals(5.0, h.pos[1], 1e-6, "粒子 ${h.index} 没有到达目标高度")
        }
    }

    @Test
    fun `morph 的单帧位移受 max_speed 约束`() {
        val hosts = listOf(Host(0, 0.0))
        val program = parseProgram(
            """
            func setup() {
                morph([vec3(20, 0, 0)], { ticks: 2, max_speed: 0.5 })
            }
            """.trimIndent(),
        )
        val obj = ScriptRuntime.createObjectState(0)
        val ctx = ScriptRuntime.ScriptCtx(
            0.0, 100.0, emptyMap(), hosts.toMutableList<work.nekow.particledrawing.animation.script.ParticleHost>(),
            { hosts.first() }, 0.0, false, {},
        )
        ScriptRuntime.runSpawnSetup(program, obj, ctx)
        var last = hosts[0].pos[0]
        repeat(60) { frame ->
            obj.morph?.apply((frame + 1) * 50.0, ctx.particles)
            val now = hosts[0].pos[0]
            assertTrue(now - last <= 0.5 + 1e-6, "单帧位移 ${now - last} 超过限速")
            last = now
        }
    }

    @Test
    fun `morph 选项键写错直接报错`() {
        val hosts = listOf(Host(0, 0.0))
        val ex = assertFailsWith<ScriptException> {
            run(
                """
                func setup() {
                    morph([vec3(1, 0, 0)], { tick: 10 })
                }
                """.trimIndent(),
                hosts,
                frames = 0,
            )
        }
        assertTrue(ex.message!!.contains("tick"), "错误信息应当指出写错的键")
    }

    @Test
    fun `morph 的槽位必须是向量数组`() {
        val hosts = listOf(Host(0, 0.0))
        val ex = assertFailsWith<ScriptException> {
            run(
                """
                func setup() {
                    morph([1, 2, 3], { ticks: 5 })
                }
                """.trimIndent(),
                hosts,
                frames = 0,
            )
        }
        assertTrue(ex.message!!.contains("vec3"))
    }
}
