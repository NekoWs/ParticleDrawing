package work.nekow.particledrawing.command.help

import work.nekow.particledrawing.command.v2.ParticleCommandsV2

/**
 * 帮助里的一条命令：单独一页，参数逐条解释。
 *
 * `id` 同时用作页面地址与语言键后缀；`usage` 是纯命令文本，点击可填进聊天框；
 * 参数名为展示文本（如 `<名称>`），效果说明与标题、简介一起放在语言文件里。
 */
internal data class HelpParam(val name: String, val effectKey: String)

internal data class HelpExample(val noteKey: String, val command: String)

internal data class HelpExampleGroup(val titleKey: String, val entries: List<HelpExample>)

internal data class HelpCommand(
    val id: String,
    val topic: String,
    val usage: String,
    val params: List<HelpParam> = emptyList(),
) {
    val titleKey: String get() = "command.help.cmd.$id.title"
    val summaryKey: String get() = "command.help.cmd.$id.summary"
    val topicTitleKey: String get() = "command.help.$topic.title"
}

internal object HelpCommands {
    private fun command(id: String, topic: String, usage: String, vararg params: HelpParam) =
        HelpCommand(id, topic, usage, params.toList())

    private val particleHelp = mapOf(
        "draw" to ("particle" to listOf(
            HelpParam("<shape>", "command.help.cmd.draw.p1"),
            HelpParam("as", "command.help.cmd.draw.p2"),
            HelpParam("also", "command.help.cmd.draw.p3"),
            HelpParam("then", "command.help.cmd.draw.p4"),
            HelpParam("stagger", "command.help.cmd.draw.stagger"),
            HelpParam("enter", "command.help.cmd.draw.enter"),
            HelpParam("edges", "command.help.cmd.draw.edges"),
        )),
        "emitter" to ("emitter" to listOf(
            HelpParam("at", "command.help.cmd.emitter.p1"),
            HelpParam("sample", "command.help.cmd.emitter.p2"),
            HelpParam("spawn", "command.help.cmd.emitter.p3"),
            HelpParam("move", "command.help.cmd.emitter.p4"),
            HelpParam("look", "command.help.cmd.emitter.p5"),
        )),
        "template" to ("emitter" to listOf(
            HelpParam("from emitter", "command.help.cmd.template.p1"),
        )),
        "use" to ("emitter" to listOf(
            HelpParam("as", "command.help.cmd.use.p1"),
            HelpParam("without", "command.help.cmd.use.p2"),
        )),
        "start" to ("emitter" to listOf(
            HelpParam("instanced", "command.help.cmd.start.p1"),
            HelpParam("synced", "command.help.cmd.start.p2"),
        )),
        "burst" to ("emitter" to listOf(
            HelpParam("gpu", "command.help.cmd.burst.p1"),
        )),
        "projectile" to ("emitter" to listOf()),
        "stop" to ("emitter" to listOf()),
        "clear" to ("particle" to listOf(
            HelpParam("emitter", "command.help.cmd.clear.p1"),
            HelpParam("group", "command.help.cmd.clear.p2"),
        )),
        "delete" to ("particle" to listOf(
            HelpParam("emitter", "command.help.cmd.delete.p1"),
            HelpParam("group", "command.help.cmd.delete.p2"),
            HelpParam("template", "command.help.cmd.delete.p3"),
        )),
        "advanced" to ("particle" to listOf(
            HelpParam("emitter", "command.help.cmd.advanced.p1"),
            HelpParam("group", "command.help.cmd.advanced.p2"),
        )),
    )

    private val entries = ParticleCommandsV2.usages.map { (id, usage) ->
        val (topic, params) = particleHelp.getValue(id)
        HelpCommand(id, topic, "/pdraw particle $usage", params)
    } + listOf(
        command(
            "animation.list", "animation",
            "/pdraw animation list",
        ),
        command(
            "animation.play", "animation",
            "/pdraw animation play <name> [pos]",
            HelpParam("<name>", "command.help.cmd.animation.play.p1"),
            HelpParam("[pos]", "command.help.cmd.animation.play.p2"),
        ),
        command(
            "animation.stop", "animation",
            "/pdraw animation stop",
        ),
        command(
            "camera.pos", "camera",
            "/pdraw camera pos <x> <y> <z> <yaw> <pitch> [fov] [smooth]",
            HelpParam("<x> <y> <z>", "command.help.cmd.camera.pos.p1"),
            HelpParam("<yaw> <pitch>", "command.help.cmd.camera.pos.p2"),
            HelpParam("[fov]", "command.help.cmd.camera.pos.p3"),
            HelpParam("[smooth]", "command.help.cmd.camera.pos.p4"),
        ),
        command(
            "camera.look", "camera",
            "/pdraw camera look <x> <y> <z> <target x> <target y> <target z> [fov] [smooth]",
            HelpParam("<x> <y> <z>", "command.help.cmd.camera.look.p1"),
            HelpParam("<target>", "command.help.cmd.camera.look.p2"),
            HelpParam("[fov] [smooth]", "command.help.cmd.camera.look.p3"),
        ),
        command(
            "camera.stop", "camera",
            "/pdraw camera stop",
        ),
        command(
            "texture.reload", "texture",
            "/pdraw texture reload",
        ),
        command(
            "light.probe", "light",
            "/pdraw light",
        ),
    )

    val commands: List<HelpCommand> = entries

    private val byId = entries.associateBy(HelpCommand::id)

    fun get(id: String): HelpCommand? = byId[id.lowercase()]

    fun ofTopic(topic: String): List<HelpCommand> = entries.filter { it.topic == topic }

    fun ids(): List<String> = entries.map(HelpCommand::id)

    val exampleGroups: List<HelpExampleGroup> = listOf(
        HelpExampleGroup(
            "command.help.examples.section.instant",
            listOf(
                HelpExample("command.help.examples.sin",
                    "pdraw particle draw sin at ~ ~1 ~ width 8 height 1 cycles 1 count 32 life 100"),
                HelpExample("command.help.examples.cube",
                    "pdraw particle draw cuboid at ~ ~1.5 ~ size 2 density 7 edges life 200"),
            ),
        ),
        HelpExampleGroup(
            "command.help.examples.section.follow",
            listOf(
                HelpExample("command.help.examples.follow_ring",
                    "pdraw particle draw circle as ex_ring at ~ ~1 ~ radius 3 count 64 then spin 1 1 0 2"),
                HelpExample("command.help.examples.follow_step",
                    "pdraw particle emitter ex_emitter at ~ ~1 ~ move vec3(0,0.04,0) life 20 rate 1 start"),
            ),
        ),
        HelpExampleGroup(
            "command.help.examples.section.cleanup",
            listOf(
                HelpExample("command.help.examples.cleanup_emitter", "pdraw particle delete emitter ex_emitter"),
                HelpExample("command.help.examples.cleanup_group", "pdraw particle delete group ex_ring"),
            ),
        ),
    )
}
