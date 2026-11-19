package work.nekow.particledrawing.core.client

import com.mojang.blaze3d.vertex.VertexConsumer
import work.nekow.particledrawing.api.ParticleSceneMaterial
import kotlin.math.roundToInt

/** UV1 的两项分别写入淡出距离和折射强度，精度为 1/256。 */
internal class SceneParticleVertices(
    private val consumer: VertexConsumer, material: ParticleSceneMaterial,
) : VertexConsumer by consumer {
    private val fade = (material.depthFade * 256f).roundToInt()
    private val distortion = (material.distortion * 256f).roundToInt()

    override fun addVertex(x: Float, y: Float, z: Float): VertexConsumer =
        consumer.addVertex(x, y, z).setUv1(fade, distortion)
}
