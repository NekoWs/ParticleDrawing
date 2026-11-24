package work.nekow.particledrawing

import work.nekow.particledrawing.math.Vec3
import work.nekow.particledrawing.animation.recipe.GroupExpressionOutputs
import work.nekow.particledrawing.api.RecipeAnchor
import work.nekow.particledrawing.command.CommandParticleRecipes
import java.util.UUID
import kotlin.test.*

class ParticleRecipeCommandsTest {
    @Test fun `共享锚点能传递更新并拒绝循环和悬空删除`() {
        val dim = UUID.randomUUID()
        try {
            val origin = CommandParticleRecipes.Draft(RecipeAnchor(Vec3(1.0, 2.0, 3.0)))
            val layer = CommandParticleRecipes.Draft(RecipeAnchor(Vec3.ZERO), anchorLink = "origin")
            val tail = CommandParticleRecipes.Draft(RecipeAnchor(Vec3.ZERO), anchorLink = "layer")
            CommandParticleRecipes.install(dim, "origin", origin)
            CommandParticleRecipes.install(dim, "layer", layer)
            CommandParticleRecipes.install(dim, "tail", tail)
            val moved = origin.copy(anchor = RecipeAnchor(Vec3(4.0, 5.0, 6.0)))
            val resolved = CommandParticleRecipes.resolveAnchors(dim, "origin", moved)
            assertEquals(moved.anchor, resolved["tail"])
            assertFailsWith<IllegalArgumentException> {
                CommandParticleRecipes.resolveAnchors(dim, "origin", moved.copy(anchorLink = "tail"))
            }
            assertFailsWith<IllegalArgumentException> { CommandParticleRecipes.remove(dim, "origin") }
            assertEquals(origin.anchor, CommandParticleRecipes.get(dim, "origin").anchor)
        } finally { CommandParticleRecipes.discard(dim) }
    }

    @Test fun `复制草稿不会共享可变外观和修改计划`() {
        val dim = UUID.randomUUID()
        try {
            val a = CommandParticleRecipes.Draft(RecipeAnchor(Vec3.ZERO))
            a.changes["vx"] = "1"; a.appearance["glow"] = "true"
            val b = a.copyDraft()
            b.changes["vx"] = "2"; b.appearance["glow"] = "false"; b.recipe.visual.glowing(true)
            assertEquals("1", a.changes["vx"]); assertEquals("true", a.appearance["glow"])
            assertFalse(a.recipe.visual.glowing)
        } finally { CommandParticleRecipes.discard(dim) }
    }

    @Test fun `组公式不会默默接受未应用的输出`() {
        GroupExpressionOutputs.validate("x=sin(t);y=0;z=0;")
        assertFailsWith<IllegalArgumentException> { GroupExpressionOutputs.validate("vx=1;") }
        assertFailsWith<IllegalArgumentException> { GroupExpressionOutputs.validate("[x,light]=[1,15];") }
    }
}
