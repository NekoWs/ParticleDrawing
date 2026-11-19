package work.nekow.particledrawing.core

import net.minecraft.world.entity.Entity
import work.nekow.particledrawing.animation.recipe.RecipeFrame
import work.nekow.particledrawing.api.RecipeAnchor
import work.nekow.particledrawing.platform.toCore
import work.nekow.particledrawing.platform.toMinecraft
import work.nekow.particledrawing.util.AttachMath

internal fun recipeFrame(anchor: RecipeAnchor, entity: Entity?): RecipeFrame {
    val origin = if (entity == null) anchor.position.add(anchor.offset) else {
        AttachMath.resolve(entity.position(), entity.yRot, entity.xRot, anchor.offset.toMinecraft(), anchor.local).toCore()
    }
    return RecipeFrame(origin, entity?.yRot ?: 0f, entity?.xRot ?: 0f, entity != null && anchor.local)
}
