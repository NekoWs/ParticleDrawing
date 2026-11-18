package work.nekow.particledrawing.command

import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.api.*
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import work.nekow.particledrawing.i18n.LocalizedArgumentException
import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.i18n.requireNotNullLocalized
import work.nekow.particledrawing.i18n.translation

/** 维度内命名的配置草稿；运行句柄与草稿分开，复制配置不会复制正在播放的粒子。 */
internal object CommandParticleRecipes {
    enum class Sampling { PARAMETER, GRID, IMAGE, MESH }

    data class SampleAsset(val name: String, val resource: Boolean) {
        fun open(level: ServerLevel, directory: String, missing: String): InputStream {
            if (resource) {
                return level.server.resourceManager.getResource(Identifier.parse(name))
                    .orElseThrow { LocalizedArgumentException(translation(missing, name)) }.open()
            }
            val root = Path.of(directory).toAbsolutePath().normalize()
            val target = safeAssetPath(root, name)
            requireLocalized(Files.isRegularFile(target)) { translation(missing, target) }
            return Files.newInputStream(target)
        }
    }
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
        var recipe: ParticleRecipe = ParticleRecipe(ParticleSampling.count(1)),
        var selector: String = "true",
        val changes: MutableMap<String, String> = linkedMapOf(),
        val appearance: MutableMap<String, String> = linkedMapOf(),
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
                val triangles = source.open(level, "particleMeshes", "error.mesh.missing").use(ParticleMeshSampler::readObj)
                ParticleMeshSampler.surface(triangles, meshCount, meshSeed)
            }
        }

        fun start(level: ServerLevel, burst: Boolean) {
            requireLocalized(burst || handle == null) { translation("error.recipe.running") }
            handles()
            val resolved = recipe.copy(seeds = samples(level))
            resolved.validate()
            val next = ParticleSources.start(level, resolved, anchor, burst)
            handle?.takeIf { it.isActive() }?.let(previousBursts::add)
            handle = next
            recipe = resolved
        }

        fun update(next: ParticleRecipe) {
            next.validate()
            handles().forEach { it.update(next) }
            recipe = next
        }

        fun variable(name: String, value: Double) {
            val next = recipe.copy(variables = recipe.variables + (name to value))
            next.validate()
            handles().forEach { it.variable(name, value) }
            recipe = next
        }

        fun handles(): List<ParticleSourceHandle> {
            previousBursts.removeAll { !it.isActive() }
            return previousBursts + listOfNotNull(handle?.takeIf { it.isActive() })
        }

        fun removeParticles() { handles().forEach { it.remove() }; previousBursts.clear(); handle = null }
        fun copyDraft() = copy(recipe = recipe.copy(visual = recipe.visual.copy()), changes = LinkedHashMap(changes), appearance = LinkedHashMap(appearance), handle = null, previousBursts = arrayListOf())
    }

    private val dimensions = hashMapOf<UUID, LinkedHashMap<String, Draft>>()
    fun create(dimension: UUID, name: String, anchor: RecipeAnchor): Draft {
        validName(name)
        val map = dimensions.getOrPut(dimension) { linkedMapOf() }
        requireLocalized(map.size < 256) { translation("error.recipe.config_limit") }
        requireLocalized(name !in map) { translation("error.recipe.exists", name) }
        return Draft(anchor).also { map[name] = it }
    }
    fun reset(dimension: UUID, name: String, anchor: RecipeAnchor): Draft {
        validName(name)
        val map = dimensions.getOrPut(dimension) { linkedMapOf() }
        requireLocalized(name in map || map.size < 256) { translation("error.recipe.config_limit") }
        map[name]?.removeParticles()
        val next = Draft(anchor)
        map[name] = next
        refreshAnchors(dimension)
        return next
    }
    fun copy(dimension: UUID, from: String, to: String) {
        validName(to)
        val source = get(dimension, from)
        val map = dimensions.getValue(dimension)
        requireLocalized(to !in map && map.size < 256) { translation("error.recipe.copy_target") }
        map[to] = source.copyDraft()
    }
    fun get(dimension: UUID, name: String) = dimensions[dimension]?.get(name) ?: throw LocalizedArgumentException(translation("error.recipe.not_found", name))
    fun names(dimension: UUID) = dimensions[dimension]?.keys?.toList() ?: emptyList()
    fun remove(dimension: UUID, name: String) {
        requireLocalized(dimensions[dimension]?.values?.none { it.anchorLink == name } != false) { translation("error.recipe.anchor_in_use") }
        get(dimension, name).removeParticles(); dimensions[dimension]?.remove(name)
    }
    fun anchor(dimension: UUID, name: String, value: RecipeAnchor) {
        val d = get(dimension, name)
        d.anchor = value; d.anchorLink = null
        refreshAnchors(dimension)
    }
    fun linkAnchor(dimension: UUID, name: String, target: String) {
        val seen = hashSetOf(name)
        var current: String? = target
        while (current != null) {
            requireLocalized(seen.add(current)) { translation("error.recipe.anchor_cycle") }
            current = get(dimension, current).anchorLink
        }
        get(dimension, name).anchorLink = target
        refreshAnchors(dimension)
    }
    private fun refreshAnchors(dimension: UUID) {
        val drafts = dimensions[dimension] ?: return
        fun resolve(name: String): RecipeAnchor {
            val d = get(dimension, name)
            return d.anchorLink?.let(::resolve) ?: d.anchor
        }
        for ((name, d) in drafts) {
            val value = resolve(name)
            d.handles().forEach { it.anchor(value) }
            d.anchor = value
        }
    }
    fun discard(dimension: UUID) { dimensions.remove(dimension) }
    fun clear() = dimensions.clear()
    private fun validName(name: String) {
        requireLocalized(Regex("[A-Za-z0-9_.:-]{1,64}").matches(name)) { translation("error.recipe.name") }
        requireLocalized(name !in setOf("create", "reset", "copy", "list", "help")) { translation("error.recipe.reserved_name", name) }
    }

    internal fun safeAssetPath(root: Path, name: String): Path {
        requireLocalized(!Path.of(name).isAbsolute) { translation("error.asset.relative_path", root.fileName) }
        val base = root.toAbsolutePath().normalize()
        val target = base.resolve(name).normalize()
        requireLocalized(target.startsWith(base) && target != base) { translation("error.asset.outside_directory", root.fileName) }
        if (Files.exists(target)) requireLocalized(target.toRealPath().startsWith(base.toRealPath())) { translation("error.asset.outside_directory", root.fileName) }
        return target
    }
}
