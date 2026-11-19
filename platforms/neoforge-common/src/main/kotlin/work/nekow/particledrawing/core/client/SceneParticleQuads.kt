package work.nekow.particledrawing.core.client

import work.nekow.particledrawing.api.ParticleSceneMaterial

/** 场景材质沿用同一模型、四边形与拖尾顶点生成。 */
internal object SceneParticleQuads {
    val batches = linkedMapOf<ParticleSceneMaterial, OrientedQuadRenderState>()
    private val spare = ArrayDeque<OrientedQuadRenderState>()

    fun forMaterial(material: ParticleSceneMaterial): OrientedQuadRenderState =
        batches.getOrPut(material) { spare.removeFirstOrNull() ?: OrientedQuadRenderState() }

    fun reset() {
        for (state in batches.values) {
            state.clear()
            if (spare.size < 16) spare.addLast(state)
        }
        batches.clear()
    }
}
