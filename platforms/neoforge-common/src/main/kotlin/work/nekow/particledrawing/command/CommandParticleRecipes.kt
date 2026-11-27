package work.nekow.particledrawing.command

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.api.*
import java.util.UUID
import work.nekow.particledrawing.i18n.LocalizedArgumentException
import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.i18n.requireNotNullLocalized
import work.nekow.particledrawing.i18n.translation

/** 维度内命名的配置草稿；运行句柄与草稿分开，复制配置不会复制正在播放的粒子。 */
internal object CommandParticleRecipes {
    enum class Sampling { PARAMETER, GRID, IMAGE, MESH, TEXT }

    data class Draft(
        var anchor: RecipeAnchor,
        var anchorLink: String? = null,
        var sampling: Sampling = Sampling.PARAMETER,
        var count: Int = 1,
        var range: Triple<Double, Double, Double>? = null,
        var min: Vec3 = Vec3(-1.0, -1.0, -1.0),
        var max: Vec3 = Vec3(1.0, 1.0, 1.0),
        var step: Double = 0.25,
        var image: SampleAsset? = null,
        var mesh: SampleAsset? = null,
        var meshCount: Int = 256,
        var meshSeed: Int = 0,
        var imageOptions: ParticleImageSampler.Options = ParticleImageSampler.Options(),
        var text: String? = null,
        var textCount: Int = 1024,
        var textHeight: Double = 2.0,
        var recipe: ParticleRecipe = ParticleRecipe(ParticleSampling.count(1)),
        var selector: String = "true",
        val changes: MutableMap<String, String> = linkedMapOf(),
        val appearance: MutableMap<String, String> = linkedMapOf(),
        var rawBirth: Map<String, String>? = null,
        var handle: ParticleSourceHandle? = null,
        val previousBursts: MutableList<ParticleSourceHandle> = arrayListOf(),
    ) {
        fun samples(level: ServerLevel): List<ParticleSeed> = when (sampling) {
            Sampling.PARAMETER -> range?.let { ParticleSampling.range(it.first, it.second, it.third) } ?: ParticleSampling.count(count)
            Sampling.GRID -> ParticleSampling.grid(min, max, step)
            Sampling.IMAGE -> {
                val source = requireNotNullLocalized(image) { translation("error.recipe.image_source") }
                source.open(level, "particleImages", "error.image.missing").use { ParticleImageSampler.sample(it, imageOptions) }
            }
            Sampling.MESH -> {
                val source = requireNotNullLocalized(mesh) { translation("error.mesh.source") }
                val provider = source.open(level, "particleMeshes", "error.mesh.missing").use(ParticleMeshSampler::objProvider)
                ParticleMeshSampler.surface(provider, meshCount, meshSeed)
            }
            Sampling.TEXT -> {
                val value = requireNotNullLocalized(text) { translation("error.recipe.text_source") }
                ParticleTextSampler.sample(value, textCount, textHeight).map(::ParticleSeed)
            }
        }

        fun activeHandles(): List<ParticleSourceHandle> =
            (previousBursts + listOfNotNull(handle)).filter { it.isActive() }

        fun record(next: ParticleSourceHandle) {
            previousBursts.removeAll { !it.isActive() }
            handle?.takeIf { it.isActive() }?.let(previousBursts::add)
            handle = next
        }

        fun removeParticles() { activeHandles().forEach { it.remove() }; previousBursts.clear(); handle = null }
        fun copyDraft() = copy(recipe = recipe.copy(visual = recipe.visual.copy()), changes = LinkedHashMap(changes), appearance = LinkedHashMap(appearance), handle = null, previousBursts = arrayListOf())
    }

    private val dimensions = hashMapOf<UUID, LinkedHashMap<String, Draft>>()
    fun get(dimension: UUID, name: String) = dimensions[dimension]?.get(name) ?: throw LocalizedArgumentException(translation("error.recipe.not_found", name))
    fun names(dimension: UUID) = dimensions[dimension]?.keys?.toList() ?: emptyList()

    fun find(dimension: UUID, name: String): Draft? = dimensions[dimension]?.get(name)

    fun snapshot(dimension: UUID): Map<String, Draft> = dimensions[dimension].orEmpty()

    fun checkCapacity(dimension: UUID, name: String) {
        val drafts = snapshot(dimension)
        requireLocalized(name in drafts || drafts.size < 256) { translation("error.recipe.config_limit") }
    }

    fun install(dimension: UUID, name: String, draft: Draft) {
        dimensions.getOrPut(dimension) { linkedMapOf() }[name] = draft
    }
    fun remove(dimension: UUID, name: String) = remove(dimension, listOf(name))

    fun remove(dimension: UUID, names: List<String>) {
        val targets = names.toSet()
        val drafts = targets.map { get(dimension, it) }
        val entries = dimensions[dimension] ?: return
        requireLocalized(entries.none { (name, draft) -> name !in targets && draft.anchorLink in targets }) {
            translation("error.recipe.anchor_in_use")
        }
        drafts.forEach(Draft::removeParticles)
        entries.keys.removeAll(targets)
    }
    fun resolveAnchors(dimension: UUID, name: String, candidate: Draft): Map<String, RecipeAnchor> {
        val drafts = snapshot(dimension) + (name to candidate)
        val resolved = linkedMapOf<String, RecipeAnchor>()
        val resolving = hashSetOf<String>()
        fun resolve(target: String): RecipeAnchor {
            resolved[target]?.let { return it }
            requireLocalized(resolving.add(target)) { translation("error.recipe.anchor_cycle") }
            val draft = requireNotNullLocalized(drafts[target]) { translation("error.recipe.not_found", target) }
            val anchor = draft.anchorLink?.let(::resolve) ?: draft.anchor
            resolving.remove(target)
            resolved[target] = anchor
            return anchor
        }
        drafts.keys.forEach(::resolve)
        return resolved
    }

    fun discard(dimension: UUID) { dimensions.remove(dimension) }
    fun clear() = dimensions.clear()
}
