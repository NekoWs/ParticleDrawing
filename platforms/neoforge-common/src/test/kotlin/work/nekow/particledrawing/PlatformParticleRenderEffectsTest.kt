package work.nekow.particledrawing

import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.Shapes
import org.joml.Vector3f
import work.nekow.particledrawing.api.ParticleRenderOptions
import work.nekow.particledrawing.core.client.ClientParticleWorld
import work.nekow.particledrawing.core.client.ParticleRenderEffects
import kotlin.test.Test
import work.nekow.particledrawing.math.Vec3 as CoreVec3
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PlatformParticleRenderEffectsTest {
    @Test
    fun `同刻重复同步仍按整刻位移定向拉长且瞬移重置`() {
        val options = ParticleRenderOptions(alignVelocity = true, stretch = 1.0, trailTicks = 4)
        val effects = ParticleRenderEffects(options, Vec3.ZERO)
        effects.syncPosition(0.0, 0.0, 0.0, 0.0, 2.0, 0.0, snap = false)
        effects.syncPosition(0.0, 2.0, 0.0, 0.0, 2.0, 0.0, snap = false)
        assertEquals(3f, effects.stretchFactor)
        effects.record(0.0, 2.0, 0.0)
        assertEquals(2, assertNotNull(effects.trail).size)
        val axis = assertNotNull(effects.orientation()).transform(Vector3f(1f, 0f, 0f))
        assertEquals(1f, axis.y, 1e-6f)
        effects.syncPosition(0.0, 2.0, 0.0, 20.0, 0.0, 0.0, snap = true)
        assertEquals(1f, effects.stretchFactor)
        assertNull(effects.orientation())
        assertEquals(1, assertNotNull(effects.trail).size)
    }

    @Test
    fun `实际台阶只挡下半格且包括线段端点`() {
        val slab = Shapes.box(0.0, 0.0, 0.0, 1.0, 0.5, 1.0)
        fun sweep(from: CoreVec3, to: CoreVec3) = ClientParticleWorld.trace(from, to) {
            if (it == BlockPos.ZERO) slab else Shapes.empty()
        }
        assertNull(sweep(CoreVec3(-1.0, 0.75, 0.5), CoreVec3(2.0, 0.75, 0.5)))
        val start = CoreVec3(0.5, 1.0, 0.5)
        val surface = CoreVec3(0.5, 0.5, 0.5)
        val hitAtEnd = assertNotNull(sweep(start, surface))
        assertEquals(surface.y, hitAtEnd.position.y, 1e-9)
        assertEquals(CoreVec3(0.0, 1.0, 0.0), hitAtEnd.normal)
        val hitAtStart = assertNotNull(sweep(surface, CoreVec3(0.5, 0.2, 0.5)))
        assertFalse(hitAtStart.inside)
        assertEquals(surface.y, hitAtStart.position.y, 1e-9)
        assertNull(sweep(surface, start))
    }
}
