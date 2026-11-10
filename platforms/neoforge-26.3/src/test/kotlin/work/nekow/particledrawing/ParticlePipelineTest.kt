package work.nekow.particledrawing

import com.mojang.renderpearl.api.pipeline.BlendFunction
import net.minecraft.client.renderer.oit.OitStage
import work.nekow.particledrawing.core.client.ADDITIVE_OIT_PARTICLE
import work.nekow.particledrawing.core.client.ADDITIVE_PARTICLE
import kotlin.test.*

class ParticlePipelineTest {
    @Test fun `常规加色管线仍按源 alpha 加权颜色`() {
        val target = assertNotNull(ADDITIVE_PARTICLE.colorTargetStates.single())
        assertEquals(BlendFunction.LIGHTNING, target.blendFunction.orElseThrow())
    }

    @Test fun `加色粒子为 OIT 三个阶段提供独立管线且不写入深度`() {
        val stages = linkedMapOf(
            OitStage.DEPTH_BOUNDS to "OIT_DEPTH_BOUNDS",
            OitStage.TRANSMITTANCE to "OIT_TRANSMITTANCE",
            OitStage.ACCUMULATE to "OIT_ACCUMULATE",
        )
        val locations = stages.map { (stage, define) ->
            val pipeline = ADDITIVE_OIT_PARTICLE.getPipeline(stage)
            val flags = pipeline.shaderDefines.flags
            assertTrue(define in flags)
            assertTrue("OIT_ADDITIVE" in flags)
            assertTrue("OIT" in flags)
            assertFalse(assertNotNull(pipeline.depthStencilState).writeDepth)
            if (stage == OitStage.ACCUMULATE) {
                val target = assertNotNull(pipeline.colorTargetStates.single())
                assertEquals(BlendFunction.ADDITIVE, target.blendFunction.orElseThrow())
                assertFalse("OIT_ALPHA_ONLY" in flags)
            } else {
                assertTrue("OIT_ALPHA_ONLY" in flags)
            }
            pipeline.location
        }
        assertEquals(3, locations.toSet().size)
        assertTrue(locations.all { it.namespace == "particledrawing" })
    }
}
