package work.nekow.particledrawing.core.client

import net.minecraft.resources.Identifier
import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.animation.ParticleUvRegion
import work.nekow.particledrawing.animation.UvData
import work.nekow.particledrawing.animation.recipe.ParticleRecipeExecution
import work.nekow.particledrawing.api.ParticleRecipe
import work.nekow.particledrawing.api.ParticleVisual
import work.nekow.particledrawing.api.RecipeAnchor
import work.nekow.particledrawing.config.ParticleDrawingConfig
import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.i18n.requireNotNullLocalized
import work.nekow.particledrawing.i18n.translation
import work.nekow.particledrawing.platform.toMinecraft
import work.nekow.particledrawing.util.VisualMath
import java.util.UUID

/** 一轮出生样本只上传一次，后续每个视图仅更新光标和相机偏移。 */
internal object ClientGpuParticles {
    private class Burst(
        val texture: Identifier, val origin: Vec3, val lifetime: Int, var lastBirth: Long,
        val blocks: List<ParticleInstanceDraws.Block>, val count: Int,
    ) {
        var tick = -1L
    }

    private val bursts = linkedMapOf<UUID, Burst>()
    var reserved = 0
        private set
    var partialTick = 0f

    fun start(id: UUID, recipe: ParticleRecipe, anchor: RecipeAnchor) {
        val shots = ParticleRecipeExecution.bake(recipe, anchor)
        if (shots.isEmpty()) return
        val engine = ClientParticleEngine.instance()
        requireLocalized((engine?.activeCount() ?: reserved) + shots.size <= ParticleDrawingConfig.CLIENT.maxRenderParticles.get()) {
            translation("error.recipe.client_limit")
        }
        val texture = recipe.visual.texture?.let { name ->
            requireNotNullLocalized(TextureCache.get(name)) { translation("error.recipe.gpu_texture") }
        } ?: TextureCache.defaultWhite()
        val look = ResolvedVisual.of(recipe.visual, 1f)
        requireLocalized(look.uv?.mode != UvData.Mode.ANIMATED && texture.animation == null) { translation("error.recipe.gpu_texture") }
        val uv = ParticleUvRegion().apply { update(look.uv, texture.width, texture.height, 0.0, null, 0.0, recipe.lifetime) }
        val size = ParticleVisual.texScaleOf(look.uv, texture.width, texture.height) * VisualMath.EDITOR_TO_MC_SCALE
        val width = (look.scaleArray?.get(0) ?: 1f) * size
        val height = (look.scaleArray?.get(1) ?: 1f) * size
        val blocks = shots.chunked(ParticleInstanceDraws.CAPACITY).map { batch ->
            ParticleInstanceDraws.Block().also { block ->
                for (shot in batch) {
                    val (p, v, a, color, scale, birth) = shot
                    block.position(p.x.toFloat(), p.y.toFloat(), p.z.toFloat(), width * scale)
                    block.motion(v.x.toFloat(), v.y.toFloat(), v.z.toFloat(), height * scale,
                        a.x.toFloat(), a.y.toFloat(), a.z.toFloat(), birth.toFloat())
                    block.appearance(color, uv.u0, uv.v0, uv.u1, uv.v1)
                }
            }
        }
        bursts[id] = Burst(texture.id, anchor.position.add(anchor.offset).toMinecraft(), recipe.lifetime,
            shots.last().birthTick, blocks, shots.size)
        reserved += shots.size
    }

    fun tick() {
        val iterator = bursts.values.iterator()
        while (iterator.hasNext()) {
            val effect = iterator.next()
            if (++effect.tick < effect.lastBirth + effect.lifetime) continue
            iterator.remove()
            release(effect)
        }
    }

    fun stop(id: UUID) { bursts[id]?.let { it.lastBirth = minOf(it.lastBirth, it.tick) } }

    fun remove(id: UUID) { bursts.remove(id)?.let(::release) }

    fun clear() {
        for (burst in bursts.values) release(burst)
        bursts.clear()
    }

    private fun release(burst: Burst) {
        reserved -= burst.count
        for (block in burst.blocks) block.close()
    }

    fun hasDraws() = bursts.isNotEmpty()

    fun forEach(camera: Vec3, draw: (Identifier, List<ParticleInstanceDraws.Block>, ParticleInstanceDraws.Frame) -> Unit) {
        for (burst in bursts.values) {
            if (burst.tick < 0) continue
            draw(burst.texture, burst.blocks, ParticleInstanceDraws.Frame(burst.origin.subtract(camera),
                burst.tick.toFloat(), burst.lifetime.toFloat(), burst.lastBirth.toFloat(), partialTick))
        }
    }
}
