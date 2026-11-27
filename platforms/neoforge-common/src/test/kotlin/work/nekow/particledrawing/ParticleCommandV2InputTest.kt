package work.nekow.particledrawing

import com.mojang.brigadier.exceptions.CommandSyntaxException
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import com.mojang.brigadier.suggestion.Suggestions
import work.nekow.particledrawing.animation.morph.MorphSpace
import work.nekow.particledrawing.api.RecipeAnchor
import work.nekow.particledrawing.command.CommandParticleRecipes.Draft
import work.nekow.particledrawing.command.CommandSuggestionCatalog
import work.nekow.particledrawing.command.v2.CommandInput
import work.nekow.particledrawing.command.v2.CommandLook
import work.nekow.particledrawing.command.v2.EmitterDeclaration
import work.nekow.particledrawing.command.v2.EmitterDeclaration.Companion.prepareRecipe
import work.nekow.particledrawing.command.v2.GroupActions
import work.nekow.particledrawing.command.v2.PointSource
import work.nekow.particledrawing.command.v2.PointSources
import work.nekow.particledrawing.command.v2.ProgramCommands
import work.nekow.particledrawing.math.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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

    @Test fun completedTokensDoNotOfferJoinedKeywords() {
        val raw = "name sample"
        val input = CommandInput(raw, suggesting = true)
        input.name()
        input.expect("sample")
        input.take("count")
        assertTrue(input.suggestions(SuggestionsBuilder(raw, 0)).build().isEmpty)
    }

    @Test fun clientResourceSuggestionsPreserveTheArgumentRangeAndQuotes() {
        val head = "/pdraw particle draw dot at ~ ~ ~ look texture "
        val raw = head + "\"end"
        val marker = CommandSuggestionCatalog.TEXTURE.marker(raw, head.length)
        val result = CommandSuggestionCatalog.expand(raw, Suggestions.create(raw, listOf(marker))) { kind, prefix ->
            assertEquals(CommandSuggestionCatalog.TEXTURE, kind)
            assertEquals("\"end", prefix)
            listOf("particle:minecraft:end_rod", "end glow", "sheet:show:smoke")
        }
        assertEquals(
            setOf(head + "\"particle:minecraft:end_rod\"", head + "\"end glow\""),
            result.list.map { it.apply(raw) }.toSet(),
        )
    }

    @Test fun texturePathPrefixCompletesTheWholeReference() {
        val head = "/pdraw particle draw dot at ~ ~ ~ look texture "
        val raw = head + "end"
        val marker = CommandSuggestionCatalog.TEXTURE.marker(raw, head.length)
        val result = CommandSuggestionCatalog.expand(raw, Suggestions.create(raw, listOf(marker))) { _, prefix ->
            assertEquals("end", prefix)
            listOf("particle:minecraft:end_rod", "particle:minecraft:flame", "sheet:show:smoke")
        }
        assertEquals(listOf(head + "particle:minecraft:end_rod"), result.list.map { it.apply(raw) })
    }

    @Test fun nativeStringSuggestionsQuoteIdsAndNamesWithSpaces() {
        val prefix = "pdraw particle field anchor "
        val raw = prefix + "\"test:"
        val suggestions = CommandSuggestionCatalog.suggestBrigadierStrings(
            SuggestionsBuilder(raw, prefix.length), listOf("test:trail one", "other:field"),
        ).build()
        assertEquals(prefix + "\"test:trail one\"", suggestions.list.single().apply(raw))
    }

    @Test fun quotedTextDecodesEscapedNewlinesAndKeepsPipes() {
        val input = CommandInput("\"first\\nsecond\"")
        assertEquals("first\nsecond", input.value("text"))
        input.finish()
        val pipe = CommandInput("\"a|b\"")
        assertEquals("a|b", pipe.value("text"))
        pipe.finish()
        assertEquals("\"a\\nb\"", CommandInput.quote("a\nb"))
    }

    @Test fun morphAcceptsLocalSpace() {
        val input = CommandInput("slots 12 \"vec3(cos(u*2*PI)*3, sin(u*2*PI)*3, 0)\" ticks 40 space local")
        val config = ProgramCommands.morph(input)
        input.finish()
        assertEquals(12, config.slotCount)
        assertEquals(MorphSpace.LOCAL, config.space)
        assertEquals(MorphSpace.WORLD, ProgramCommands.morph(CommandInput("slots 1 \"vec3(0,0,0)\"")).space)
    }

    @Test fun morphSlotsAllFollowMemberCount() {
        val input = CommandInput("slots all \"vec3(cos(u*2*PI), sin(u*2*PI), 0)\" ticks 20")
        val config = ProgramCommands.morph(input)
        input.finish()
        assertTrue(config.autoSlots)
        val step = ProgramCommands.Step(GroupActions.Action.MorphStart, config)
        val resolved = checkNotNull(ProgramCommands.resolveAutoSlots(listOf(step), 4096).single().morph)
        assertEquals(4096, resolved.slotCount)
        assertFalse(resolved.autoSlots)
        // 草稿本身不被改写，之后还能换一个成员数再解析。
        assertTrue(config.autoSlots)
        assertEquals(12, checkNotNull(ProgramCommands.resolveAutoSlots(listOf(step), 12).single().morph).slotCount)
        val explicit = ProgramCommands.morph(CommandInput("slots 12 \"vec3(0,0,0)\""))
        assertFalse(explicit.autoSlots)
    }

    @Test fun morphTargetSourcesParse() {
        val text = ProgramCommands.morph(CommandInput("target text \"WORLD\" size 2 count 64 space local"))
        assertTrue(text.target is PointSource.Text)
        assertEquals(64, text.target!!.count)
        assertEquals(MorphSpace.LOCAL, text.space)
        // 公式目标与旧 slots 等价：数量 all 走成员数，写数字则固定槽位数。
        val all = ProgramCommands.morph(CommandInput("target formula all \"vec3(u, 0, 0)\""))
        assertTrue(all.target == null && all.autoSlots)
        val fixed = ProgramCommands.morph(CommandInput("target formula 8 \"vec3(i, 0, 0)\""))
        assertTrue(fixed.target == null)
        assertEquals(8, fixed.slotCount)
        assertEquals("vec3(i, 0, 0)", fixed.position)
        val group = ProgramCommands.morph(CommandInput("target group v2_auto"))
        assertTrue(group.target is PointSource.GroupRef)
        val pointSet = ProgramCommands.morph(CommandInput("target pointset word_b"))
        assertTrue(pointSet.target is PointSource.PointSetRef)
        assertEquals("pointset word_b", PointSources.label(pointSet.target!!))
    }

    @Test fun pointSetCommandsParse() {
        val define = CommandInput("define word_b text \"WORLD\" size 2 count 64")
        // 只验证语法层能消费；真正的采样在服务端执行。
        define.expect("define")
        val name = define.name()
        val spec = PointSources.parse(define, allowAll = false)
        define.finish()
        assertEquals("word_b", name)
        assertTrue(spec is PointSource.Text)
        assertEquals("text \"WORLD\"", PointSources.label(spec))
        assertFailsWith<CommandSyntaxException> { PointSources.parse(CommandInput("formula all \"vec3(0,0,0)\""), allowAll = false) }
    }
}
