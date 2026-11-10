package work.nekow.particledrawing.api

import net.minecraft.server.level.ServerLevel
import work.nekow.particledrawing.core.server.ServerParticleSources
import java.util.UUID

/** 采样声明的服务端入口；与命令共用同一套出生、运动和筛选行为。 */
object ParticleSources {
    @JvmStatic
    @JvmOverloads
    fun start(level: ServerLevel, recipe: ParticleRecipe, anchor: RecipeAnchor, burst: Boolean = false): ParticleSourceHandle =
        ParticleSourceHandle(ServerParticleSources.start(level, recipe, anchor, burst), level)
}

class ParticleSourceHandle internal constructor(val id: UUID, private val level: ServerLevel) {
    fun update(recipe: ParticleRecipe) = apply { ServerParticleSources.update(id, recipe, level) }
    fun anchor(anchor: RecipeAnchor) = apply { ServerParticleSources.anchor(id, anchor, level) }
    /** 单变量小包更新，不重新传输图片点阵与采样布局。 */
    fun variable(name: String, value: Double) = apply { ServerParticleSources.variable(id, name, value, level) }
    /** 停止出生，现有粒子继续运动并走完寿命。 */
    fun stop() = apply { ServerParticleSources.stop(id, level, false) }
    /** 停止并立即移除全部成员。 */
    fun remove() = apply { ServerParticleSources.stop(id, level, true) }
    fun removeWhere(predicate: String) = apply { ServerParticleSources.edit(id, predicate, emptyMap(), true, level) }
    fun changeWhere(predicate: String, changes: Map<String, String>) = apply { ServerParticleSources.edit(id, predicate, changes, false, level) }
    fun isActive() = ServerParticleSources.active(id)
}
