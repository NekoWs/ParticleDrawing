package work.nekow.particledrawing.command

import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.api.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** 维度内命名的配置草稿；运行句柄与草稿分开，复制配置不会复制正在播放的粒子。 */
internal object CommandParticleRecipes {
    enum class Sampling { PARAMETER, GRID, IMAGE }
    data class Draft(
        var anchor: RecipeAnchor,
        var anchorLink: String? = null,
        var sampling: Sampling = Sampling.PARAMETER,
        var count: Int = 1,
        var range: Triple<Double, Double, Double>? = null,
        var min: Vec3 = Vec3(-1.0, -1.0, -1.0),
        var max: Vec3 = Vec3(1.0, 1.0, 1.0),
        var step: Double = 0.25,
        var image: String? = null,
        var imageResource: Boolean = false,
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
                val name = requireNotNull(image) { "请先设置 image file 或 image resource" }
                val input = if (imageResource) {
                    val resource = level.server.resourceManager.getResource(Identifier.parse(name)).orElseThrow { IllegalArgumentException("数据包图片不存在: $name") }
                    resource.open()
                } else {
                    val root = Path.of("particleImages").toAbsolutePath().normalize()
                    val target = safeImagePath(root, name)
                    require(Files.isRegularFile(target)) { "图片不存在: $target" }
                    Files.newInputStream(target)
                }
                input.use { ParticleImageSampler.sample(it, imageOptions) }
            }
        }

        fun start(level: ServerLevel, burst: Boolean) {
            require(burst || handle == null) { "生成器已有运行实例；请先 remove，再 start；stop 只停止出生" }
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
        require(map.size < 256) { "当前维度最多 256 个命名配置" }
        require(name !in map) { "生成器 '$name' 已存在" }
        return Draft(anchor).also { map[name] = it }
    }
    fun reset(dimension: UUID, name: String, anchor: RecipeAnchor): Draft {
        validName(name)
        val map = dimensions.getOrPut(dimension) { linkedMapOf() }
        require(name in map || map.size < 256) { "当前维度最多 256 个命名配置" }
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
        require(to !in map && map.size < 256) { "目标名称已存在或配置数量超限" }
        map[to] = source.copyDraft()
    }
    fun get(dimension: UUID, name: String) = dimensions[dimension]?.get(name) ?: throw IllegalArgumentException("当前维度没有生成器 '$name'")
    fun names(dimension: UUID) = dimensions[dimension]?.keys?.toList() ?: emptyList()
    fun remove(dimension: UUID, name: String) {
        require(dimensions[dimension]?.values?.none { it.anchorLink == name } != false) { "该锚点正被其它生成器引用，请先修改它们的 anchor" }
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
            require(seen.add(current)) { "锚点引用不能成环" }
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
        require(Regex("[A-Za-z0-9_.:-]{1,64}").matches(name)) { "名称只能含字母、数字、下划线、短横线、点和冒号，最多 64 字符" }
        require(name !in setOf("create", "reset", "copy", "list", "help")) { "'$name' 是子命令，不能用作生成器名称" }
    }

    internal fun safeImagePath(root: Path, name: String): Path {
        require(!Path.of(name).isAbsolute) { "image file 需要 particleImages 内的相对路径" }
        val base = root.toAbsolutePath().normalize()
        val target = base.resolve(name).normalize()
        require(target.startsWith(base) && target != base) { "图片路径不能越过 particleImages" }
        if (Files.exists(target)) require(target.toRealPath().startsWith(base.toRealPath())) { "图片符号链接不能越过 particleImages" }
        return target
    }
}
