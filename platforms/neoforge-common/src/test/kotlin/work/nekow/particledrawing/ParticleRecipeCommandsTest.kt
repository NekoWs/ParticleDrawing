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
            CommandParticleRecipes.create(dim, "origin", RecipeAnchor(Vec3(1.0, 2.0, 3.0)))
            CommandParticleRecipes.create(dim, "layer", RecipeAnchor(Vec3.ZERO))
            CommandParticleRecipes.create(dim, "tail", RecipeAnchor(Vec3.ZERO))
            CommandParticleRecipes.linkAnchor(dim, "layer", "origin")
            CommandParticleRecipes.linkAnchor(dim, "tail", "layer")
            CommandParticleRecipes.anchor(dim, "origin", RecipeAnchor(Vec3(4.0, 5.0, 6.0)))
            assertEquals(Vec3(4.0, 5.0, 6.0), CommandParticleRecipes.get(dim, "tail").anchor.position)
            assertFailsWith<IllegalArgumentException> { CommandParticleRecipes.linkAnchor(dim, "origin", "tail") }
            assertFailsWith<IllegalArgumentException> { CommandParticleRecipes.remove(dim, "origin") }
            assertNull(CommandParticleRecipes.get(dim, "origin").anchorLink)
            CommandParticleRecipes.reset(dim, "origin", RecipeAnchor(Vec3(7.0, 8.0, 9.0)))
            assertEquals(Vec3(7.0, 8.0, 9.0), CommandParticleRecipes.get(dim, "tail").anchor.position)
        } finally { CommandParticleRecipes.discard(dim) }
    }

    @Test fun `复制草稿不会共享可变外观和修改计划`() {
        val dim = UUID.randomUUID()
        try {
            val a = CommandParticleRecipes.create(dim, "a", RecipeAnchor(Vec3.ZERO))
            a.changes["vx"] = "1"; a.appearance["glowing"] = "true"
            CommandParticleRecipes.copy(dim, "a", "b")
            val b = CommandParticleRecipes.get(dim, "b")
            b.changes["vx"] = "2"; b.appearance["glowing"] = "false"; b.recipe.visual.glowing(true)
            assertEquals("1", a.changes["vx"]); assertEquals("true", a.appearance["glowing"])
            assertFalse(a.recipe.visual.glowing)
        } finally { CommandParticleRecipes.discard(dim) }
    }

    @Test fun `组公式不会默默接受未应用的输出`() {
        GroupExpressionOutputs.validate("x=sin(t);y=0;z=0;")
        assertFailsWith<IllegalArgumentException> { GroupExpressionOutputs.validate("vx=1;") }
        assertFailsWith<IllegalArgumentException> { GroupExpressionOutputs.validate("[x,light]=[1,15];") }
    }
}
