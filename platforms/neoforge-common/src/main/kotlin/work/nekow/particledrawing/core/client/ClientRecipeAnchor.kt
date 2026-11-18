package work.nekow.particledrawing.core.client

import net.minecraft.client.Minecraft
import work.nekow.particledrawing.animation.recipe.RecipeFrame
import work.nekow.particledrawing.api.RecipeAnchor
import work.nekow.particledrawing.platform.toCore
import work.nekow.particledrawing.platform.toMinecraft
import work.nekow.particledrawing.util.AttachMath

internal fun resolveRecipeAnchor(anchor: RecipeAnchor): RecipeFrame? {
    val entity = if (anchor.entity == null) null else {
        val level = Minecraft.getInstance().level ?: return null
        level.getEntity(anchor.entityId)?.takeIf { it.uuid == anchor.entity }
            ?: level.entitiesForRendering().firstOrNull { it.uuid == anchor.entity } ?: return null
    }
    val origin = if (entity == null) anchor.position.add(anchor.offset) else {
        AttachMath.resolve(entity.position(), entity.yRot, entity.xRot, anchor.offset.toMinecraft(), anchor.local).toCore()
    }
    return RecipeFrame(origin, entity?.yRot ?: 0f, entity?.xRot ?: 0f, entity != null && anchor.local)
}
