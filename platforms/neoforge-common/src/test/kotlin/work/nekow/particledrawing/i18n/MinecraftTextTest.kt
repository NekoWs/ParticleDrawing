package work.nekow.particledrawing.i18n

import net.minecraft.locale.Language
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FormattedText
import net.minecraft.network.chat.contents.TranslatableContents
import net.minecraft.util.FormattedCharSequence
import net.minecraft.world.phys.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MinecraftTextTest {
    @Test
    fun repliesFollowTheReceivingLanguage() {
        val pos = Vec3(0.0, 64.0, 0.0)
        val group = translation("command.group.create", "cube", pos).component()
        assertEquals(pos.toString(), assertIs<TranslatableContents>(group.contents).args[1])
        val error = LocalizedArgumentException(
            translation("error.command.identifier", translation("field.variable_name"), "3x"),
        )
        val failure = error.failureComponent("error.command.particle_parameters")
        val contents = assertIs<TranslatableContents>(failure.contents)
        assertEquals("particledrawing.error.command.identifier", contents.key)
        assertIs<TranslatableContents>(assertIs<Component>(contents.args[0]).contents)

        val original = Language.getInstance()
        try {
            Language.inject(language("en_us"))
            assertEquals("Created group cube at $pos", group.string)
            assertEquals("Invalid variable name: 3x", failure.string)
            Language.inject(language("zh_cn"))
            assertEquals("已创建组 cube，轴心 $pos", group.string)
            assertEquals("变量名无效：3x", failure.string)
            Language.inject(language())
            assertEquals("Created group cube at $pos", group.string)
        } finally {
            Language.inject(original)
        }
    }

    private fun language(locale: String? = null): Language {
        val entries = mutableMapOf<String, String>()
        if (locale != null) {
            checkNotNull(javaClass.getResourceAsStream("/assets/particledrawing/lang/$locale.json"))
                .use { Language.loadFromJson(it, entries::put) }
        }
        return object : Language() {
            override fun getOrDefault(key: String, fallback: String): String = entries[key] ?: fallback
            override fun has(key: String): Boolean = key in entries
            override fun isDefaultRightToLeft(): Boolean = false
            override fun getVisualOrder(text: FormattedText): FormattedCharSequence = FormattedCharSequence.EMPTY
        }
    }
}
