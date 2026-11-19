package work.nekow.particledrawing.core.client

import net.minecraft.client.Minecraft
import work.nekow.particledrawing.animation.recipe.RecipeFrame
import work.nekow.particledrawing.api.RecipeAnchor
import work.nekow.particledrawing.core.recipeFrame

internal fun resolveRecipeAnchor(anchor: RecipeAnchor): RecipeFrame? {
    val entity = if (anchor.entity == null) null else {
        val level = Minecraft.getInstance().level ?: return null
        level.getEntity(anchor.entityId)?.takeIf { it.uuid == anchor.entity }
            ?: level.entitiesForRendering().firstOrNull { it.uuid == anchor.entity } ?: return null
    }
    return recipeFrame(anchor, entity)
}
