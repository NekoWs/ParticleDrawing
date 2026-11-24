package work.nekow.particledrawing

import com.mojang.brigadier.exceptions.CommandSyntaxException
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import work.nekow.particledrawing.api.RecipeAnchor
import work.nekow.particledrawing.command.CommandParticleRecipes.Draft
import work.nekow.particledrawing.command.v2.CommandInput
import work.nekow.particledrawing.command.v2.CommandLook
import work.nekow.particledrawing.command.v2.EmitterDeclaration
import work.nekow.particledrawing.command.v2.EmitterDeclaration.Companion.prepareRecipe
import work.nekow.particledrawing.math.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ParticleCommandV2InputTest {
    @Test fun bareAndQuotedFormulasAreSingleArguments() {
        val input = CommandInput("""spawn vec3(u,0,0) filter "u > 0.2" """)
        input.expect("spawn")
        assertEquals("vec3(u,0,0)", input.formula("position"))
        input.expect("filter")
        assertEquals("u > 0.2", input.formula("condition"))
        input.finish()
    }

    @Test fun quotesAndBackslashesRoundTrip() {
        val value = """模型 "a\b" """
        val quoted = CommandInput.quote(value)
        val input = CommandInput(quoted)
        assertEquals(value, input.value("text"))
        input.finish()
        assertFailsWith<CommandSyntaxException> { CommandInput(quoted + "tail").value("text") }
    }

    @Test fun imageSpacingAndEmissionSpacingAreIndependent() {
        val binding = EmitterDeclaration.Binding(null, "sample")
        val draft = Draft(RecipeAnchor(Vec3.ZERO))
        val input = CommandInput("sample image file sprite.png pixel_spacing 0.125 spacing 2")
        EmitterDeclaration.parse(input, emptyList()).apply(draft, binding)
        input.finish()
        assertEquals(0.125, draft.imageOptions.spacing)
        assertEquals(2.0, draft.recipe.spacing)
    }

    @Test fun overridesRetainLetOrderAndUnchangedAppearance() {
        val binding = EmitterDeclaration.Binding(null, "sample")
        val draft = Draft(RecipeAnchor(Vec3.ZERO))
        val base = CommandInput("let a1 1 let a2 a1 spawn vec3(a2,0,0) orbit vec3(1,0,0) look color red glow")
        EmitterDeclaration.parse(base, emptyList()).apply(draft, binding)
        draft.prepareRecipe()
        val override = CommandInput("without orbit let a1 2 look color expr vec3(0,0,1)")
        EmitterDeclaration.without(override).apply(draft, binding)
        EmitterDeclaration.parse(override, emptyList()).apply(draft, binding)
        override.finish()
        draft.prepareRecipe()
        assertEquals(listOf("a1", "a2"), draft.recipe.lets.keys.toList())
        assertEquals("2", draft.recipe.lets["a1"])
        assertEquals("", draft.recipe.orbit)
        assertEquals("vec3(0,0,1)", draft.recipe.birth["color"])
        assertTrue(draft.recipe.visual.glowing)
    }

    @Test fun appearanceAliasesAndSizeModesCannotBeRepeated() {
        assertFailsWith<CommandSyntaxException> { CommandLook.parse(CommandInput("glow glowing")) }
        assertFailsWith<CommandSyntaxException> { CommandLook.parse(CommandInput("size 0.2 size_relative 1 2")) }
    }

    @Test fun suggestionsReplaceOnlyTheCurrentArgument() {
        val input = CommandInput("name sam", suggesting = true)
        assertEquals("name", input.name())
        assertTrue(!input.take("sample"))
        val prefix = "pdraw particle emitter "
        val builder = input.suggestions(SuggestionsBuilder(prefix + "name sam", prefix.length))
        assertEquals(prefix + "name sample", builder.build().list.single().apply(builder.input))
    }
}
