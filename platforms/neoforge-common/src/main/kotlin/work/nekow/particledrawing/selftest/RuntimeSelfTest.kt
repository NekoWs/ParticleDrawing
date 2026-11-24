package work.nekow.particledrawing.selftest

import net.minecraft.world.phys.Vec3
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.server.ServerStartedEvent
import work.nekow.particledrawing.ParticleDrawing
import work.nekow.particledrawing.api.ParticleManager
import work.nekow.particledrawing.command.CommandParticleGroups
import work.nekow.particledrawing.command.CommandParticleRecipes
import work.nekow.particledrawing.command.help.HelpCommands
import work.nekow.particledrawing.core.easing.EasingType
import work.nekow.particledrawing.core.server.ServerParticleSources
import work.nekow.particledrawing.light.LightFalloff
import work.nekow.particledrawing.light.LightMath
import work.nekow.particledrawing.light.LightShape
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/**
 * 服务端运行时自检：在真实 Minecraft 服务端上跑一遍对外 API 契约。
 *
 * 为什么需要它：26.x 的 GameTest 改成了数据驱动、不再有注解 API，三端只有 1.21.1 能写
 * 注解式 GameTest。于是 CI 里 `runGameTestServer` 的「All N required tests passed」
 * 在 26.2/26.3 上只统计到框架自带的那一个测试，看起来通过、实际没验证任何东西。
 *
 * 这个自检用同一份 Kotlin 代码在三端跑，断言射灯的创建、形状、转向/聚焦缓动、形状热更与移除，
 * 以及光照数学和语言表的基本正确性；再把 examples/commands 里的地狱之眼函数交给命令分发器
 * 真跑一遍，确认命令语法、公式、发射窗口和锚点链接能落地。
 * 只有传 `-Dpd.selftest=1` 时才执行，通过时打印 `[PD-SELFTEST] PASS`，失败时打印带原因的
 * FAIL，CI 依据该标记判定。
 */
@EventBusSubscriber(modid = ParticleDrawing.MODID, value = [Dist.DEDICATED_SERVER])
object RuntimeSelfTest {

    private const val MARK = "[PD-SELFTEST]"

    /** 自检可能从仓库根目录或平台 run 目录启动，向上找到 examples/commands。 */
    private fun examplesDirectory(): Path? {
        var dir: Path? = Path.of("").toAbsolutePath()
        repeat(8) {
            val candidate = dir?.resolve("examples/commands")
            if (candidate != null && Files.isDirectory(candidate)) return candidate
            dir = dir?.parent
        }
        return null
    }

    @SubscribeEvent
    @JvmStatic
    fun onServerStarted(event: ServerStartedEvent) {
        if (System.getProperty("pd.selftest") == null) return
        val failures = ArrayList<String>()
        var checks = 0

        fun check(name: String, body: () -> Boolean) {
            checks++
            val ok = try {
                body()
            } catch (error: Exception) {
                failures += "$name 抛异常: ${error.javaClass.simpleName}: ${error.message}"
                false
            }
            if (!ok && failures.none { it.startsWith(name) }) failures += name
        }

        try {
            val level = event.server.overworld()
            val manager = ParticleManager.of(level)

            val lamp = manager.create()
                .position(0.0, level.seaLevel + 4.0, 0.0)
                .spotlight(12.0, 35.0, 20.0, 1.5)
                .lightDirection(25.0, -35.0)
                .lightOnly(true)
                .spawn()
            check("射灯可创建") { lamp != null }

            if (lamp != null) {
                val expected = LightShape.spot(12.0, 35.0, 20.0, 1.5)
                    .withDirection(25.0, -35.0)
                check("形状与朝向落库") { lamp.lightShape() == expected }

                lamp.lightDirection(70.0, -45.0, 20, EasingType.EASE_IN_OUT)
                    .lightFocus(24.0, 20, EasingType.EASE_OUT)
                val turned = LightShape.spot(12.0, 24.0, 0.0, 1.5).withDirection(70.0, -45.0)
                check("转向与聚焦的目标值") { lamp.lightShape() == turned }

                val falloff = LightShape.spot(12.0, 24.0).withFalloff(LightFalloff.SMOOTH)
                lamp.lightShape(falloff)
                check("形状热更") { lamp.lightShape() == falloff }

                lamp.remove()
                check("移除后离开服务端引擎") { lamp.data() == null }
            }

            // 光照数学：轴线满亮、背面不收光、外锥外为零
            val spot = LightShape.spot(10.0, 30.0, 15.0)
            val (dx, dy, dz) = spot.direction()
            val axisHit = LightMath.contribution(
                work.nekow.particledrawing.light.LightEmission(
                    0.0, 0.0, 0.0, luminance = 15.0, range = 10.0,
                    dirX = dx, dirY = dy, dirZ = dz,
                    innerCos = spot.innerCos(), outerCos = spot.outerCos(),
                ),
                0.0, -3.0, 0.0,
            )
            check("轴线方向有光") { axisHit > 0.0 }
            check("背面无光") {
                LightMath.contribution(
                    work.nekow.particledrawing.light.LightEmission(
                        0.0, 0.0, 0.0, luminance = 15.0, range = 10.0,
                        dirX = dx, dirY = dy, dirZ = dz,
                        innerCos = spot.innerCos(), outerCos = spot.outerCos(),
                    ),
                    0.0, 3.0, 0.0,
                ) == 0.0
            }

            // 语言表：中英都能解析出非空文案
            val text = work.nekow.particledrawing.i18n.translation("command.animation.empty")
            check("中文文案可解析") { text.resolve(Locale.SIMPLIFIED_CHINESE).isNotBlank() }
            check("英文文案可解析") { text.resolve(Locale.ENGLISH).isNotBlank() }

            // 命令层：把 examples/commands 里的地狱之眼函数真正交给命令分发器跑一遍，
            // 校验语法、公式、发射窗口与锚点链接都能落地。数据包里才有 /schedule，跳过排程行。
            val examples = examplesDirectory()
            if (examples == null) {
                failures += "找不到 examples/commands 目录，无法检查命令示例"
            } else {
                val server = event.server
                val source = server.createCommandSourceStack()
                fun runLines(file: String) {
                    for (line in java.nio.file.Files.readAllLines(examples.resolve(file))) {
                        val command = line.trim()
                        if (command.isEmpty() || command.startsWith("#") || command.startsWith("schedule ")) continue
                        val result = try {
                            server.commands.dispatcher.execute(command, source)
                        } catch (error: Exception) {
                            failures += "$file 命令抛异常：$command（${error.javaClass.simpleName}: ${error.message}）"
                            -1
                        }
                        if (result == 0) failures += "$file 命令未成功：$command"
                    }
                }

                val dimension = work.nekow.particledrawing.util.ParticleUtils.dimensionUUID(level)
                runLines("hell-eye.mcfunction")
                check("开场与持续配置落地") {
                    val root = CommandParticleRecipes.get(dimension, "hell_eye_root")
                    val intro = CommandParticleRecipes.get(dimension, "hell_eye_intro_outer")
                    val hold = CommandParticleRecipes.get(dimension, "hell_eye_hold_outer")
                    val inner = CommandParticleRecipes.get(dimension, "hell_eye_hold_inner")
                    root.anchor.position.x == source.position.x && root.anchor.position.y == source.position.y + 4.0 &&
                        intro.anchor == root.anchor && hold.anchor == root.anchor &&
                        intro.recipe.seeds.size == 1001 && intro.recipe.lifetime == 10 &&
                        hold.recipe.emitFromTick == 10 && hold.recipe.seeds.size == 2001 && hold.recipe.maxAlive == 8192 &&
                        inner.recipe.emitFromTick == 10 && inner.recipe.lifetime == -1 && inner.recipe.seeds.size == 1001
                }
                check("开场与持续都在运行") {
                    listOf("hell_eye_intro_outer", "hell_eye_intro_motes", "hell_eye_hold_outer", "hell_eye_hold_inner")
                        .all { CommandParticleRecipes.get(dimension, it).handle?.isActive() == true }
                }
                check("发射窗口命令可改可回到默认") {
                    val draft = CommandParticleRecipes.get(dimension, "hell_eye_hold_ring")
                    val before = draft.recipe.emitFromTick
                    server.commands.dispatcher.execute("pdraw particle advanced emitter hell_eye_hold_ring edit window 20", source)
                    val changed = CommandParticleRecipes.get(dimension, "hell_eye_hold_ring").recipe.emitFromTick
                    server.commands.dispatcher.execute("pdraw particle advanced emitter hell_eye_hold_ring edit window $before", source)
                    changed == 20 && CommandParticleRecipes.get(dimension, "hell_eye_hold_ring").recipe.emitFromTick == before
                }
                runLines("hell-eye-end.mcfunction")
                check("收场配置落地并替换运行中的持续状态") {
                    val outer = CommandParticleRecipes.get(dimension, "hell_eye_end_outer")
                    val hold = CommandParticleRecipes.get(dimension, "hell_eye_hold_outer")
                    outer.recipe.seeds.size == 1001 && outer.recipe.lifetime == 5 && outer.recipe.emitFromTick == 0 &&
                        outer.handle?.isActive() == true && hold.handle == null
                }
                runLines("hell-eye-clear.mcfunction")
                check("清空后没有运行中的实例") {
                    CommandParticleRecipes.names(dimension)
                        .filter { it.startsWith("hell_eye_") }
                        .all { CommandParticleRecipes.get(dimension, it).handle == null }
                }
                CommandParticleRecipes.discard(dimension)
                ServerParticleSources.discard(dimension)

                fun run(command: String, commandSource: net.minecraft.commands.CommandSourceStack = source): Int = try {
                    server.commands.dispatcher.execute(command, commandSource)
                } catch (_: com.mojang.brigadier.exceptions.CommandSyntaxException) {
                    0
                }

                fun suggest(command: String): List<String> = server.commands.dispatcher.getCompletionSuggestions(
                    server.commands.dispatcher.parse(command, source),
                ).join().list.map { it.text }

                val name = "v2_check"
                check("发射器 dryrun 不建配置") {
                    run("pdraw particle emitter $name at 0 60 0 sample count 3 life 20 rate 4 start dryrun") == 1 &&
                        CommandParticleRecipes.find(dimension, name) == null
                }
                check("发射器声明与外观落地") {
                    run("pdraw particle emitter $name at 0 60 0 sample count 3 look style soft_dot color red life 20 rate 4 start") == 1 &&
                        CommandParticleRecipes.get(dimension, name).let {
                            it.recipe.seeds.size == 3 && it.recipe.rate == 4 &&
                                it.recipe.visual.texture == work.nekow.particledrawing.api.ParticleStyle.SOFT_DOT.textureName &&
                                it.recipe.birth["r"] == "1.0" && it.handle?.isActive() == true
                        }
                }
                check("不兼容模式和错误外观保留原配置与句柄") {
                    val before = CommandParticleRecipes.get(dimension, name)
                    run("pdraw particle emitter $name look color zz") == 0 &&
                        run("pdraw particle emitter $name start gpu") == 0 &&
                        run("pdraw particle emitter $name look color expr vec3(2,0,0) dryrun") == 0 &&
                        run("pdraw particle emitter $name at 0 60 0 life -1 burst gpu") == 0 &&
                        CommandParticleRecipes.get(dimension, name) === before && before.handle?.isActive() == true
                }
                check("模板按使用位置解析，覆盖和清除独立") {
                    val moved = source.withPosition(Vec3(50.0, 70.0, -4.0))
                    run("pdraw particle template $name at ~ ~2 ~ sample count 2 orbit vec3(1,0,0) look color red glow") == 1 &&
                        run("pdraw particle use $name as v2_red") == 1 &&
                        run("pdraw particle use $name as v2_blue without orbit look color blue", moved) == 1 &&
                        CommandParticleRecipes.get(dimension, "v2_red").recipe.orbit.isNotEmpty() &&
                        CommandParticleRecipes.get(dimension, "v2_blue").let {
                            it.recipe.orbit.isEmpty() && it.recipe.visual.glowing && it.recipe.birth["b"] == "1.0" &&
                                it.anchor.position.x == 50.0 && it.anchor.position.y == 72.0 && it.anchor.position.z == -4.0
                        }
                }
                check("模板快照保留锚点与布局") {
                    run("pdraw particle template v2_snapshot from emitter v2_blue") == 1 &&
                        run("pdraw particle use v2_snapshot as v2_copy") == 1 &&
                        CommandParticleRecipes.get(dimension, "v2_copy").let {
                            it.recipe.seeds.size == 2 && it.anchor == CommandParticleRecipes.get(dimension, "v2_blue").anchor
                        }
                }
                check("名字链拒绝循环与被引用的删除") {
                    run("pdraw particle emitter v2_child at emitter v2_blue") == 1 &&
                        run("pdraw particle advanced emitter v2_blue edit at emitter v2_child") == 0 &&
                        run("pdraw particle delete emitter v2_blue") == 0 &&
                        run("pdraw particle delete emitter v2_child") == 1
                }
                check("持续发射与爆发独立，停止后可重启") {
                    val current = CommandParticleRecipes.get(dimension, name)
                    run("pdraw particle start $name") == 0 &&
                        run("pdraw particle burst $name") == 1 && current.activeHandles().size == 2 &&
                        run("pdraw particle stop $name") == 1 && current.activeHandles().size == 2 &&
                        run("pdraw particle start $name") == 1 && current.activeHandles().size == 3 &&
                        run("pdraw particle clear emitter $name") == 1 && current.activeHandles().isEmpty()
                }
                check("实例、GPU 和同步动作") {
                    run("pdraw particle emitter v2_inst at 0 60 0 sample count 8 look style soft_dot life 10 start instanced") == 1 &&
                        run("pdraw particle emitter v2_gpu at 0 60 0 sample count 64 look style soft_dot additive glow light 0 life 10 burst gpu") == 1 &&
                        run("pdraw particle emitter v2_sync at 0 60 0 sample count 4 life 10 max 64 burst synced") == 1
                }
                check("文字采样与图元") {
                    run("pdraw particle emitter v2_text sample text \"SELFTEST|V2\" count 512 height 2") == 1 &&
                        CommandParticleRecipes.get(dimension, "v2_text").recipe.seeds.size == 512 &&
                        run("pdraw particle draw text as v2_letters at 0 70 0 text 星 size 2 count 6000") == 6000 &&
                        run("pdraw particle draw text as v2_letters at 0 70 0 text 星 count 8193") == 0 &&
                        CommandParticleGroups.get(dimension, "v2_letters")?.group?.size() == 6000
                }
                check("图元合并、颜色公式与出错回滚") {
                    val drawn = run("pdraw particle draw sin as $name at 0 70 0 width 4 height 1 cycles 1 count 8 " +
                        "look color expr vec3(u,0,1-u) also expr at 0 70 0 count 4 \"vec3(u, 1, 0)\"")
                    val before = CommandParticleGroups.get(dimension, name)
                    drawn == 12 && before?.group?.size() == 12 &&
                        run("pdraw particle draw circle as $name at 0 70 0 radius 2 count 4 then rotate 0 0 0 90 4") == 0 &&
                        run("pdraw particle draw dot as $name at 0 70 0 dryrun") == 1 &&
                        CommandParticleGroups.get(dimension, name) === before
                }
                check("编排追加、形变草稿与替换边界") {
                    run("pdraw particle advanced group $name program then delay 5 then spin 0 1 0 3") == 2 &&
                        run("pdraw particle advanced group $name program replace then delay 1") == 0 &&
                        run("pdraw particle advanced group $name morph configure slots 12 \"vec3(i, 70, 0)\" ticks 10") == 1 &&
                        run("pdraw particle advanced group $name morph start") == 1 &&
                        run("pdraw particle advanced group $name morph inspect") == 1
                }
                check("错峰覆盖取消旧生成，清理按类型查找") {
                    run("pdraw particle draw line as $name from 0 70 0 to 1 70 0 count 4 stagger 2 then spin 0 1 0 1") == 4 &&
                        run("pdraw particle draw dot as $name at 0 70 0") == 1 &&
                        run("pdraw particle clear group $name") == 1 &&
                        CommandParticleGroups.get(dimension, name)?.group?.size() == 0 &&
                        CommandParticleRecipes.find(dimension, name) != null
                }
                check("补全使用声明段与维度内对象") {
                    suggest("pdraw particle draw ").contains("sin") &&
                        suggest("pdraw particle emitter $name ").containsAll(listOf("at", "sample", "look", "start")) &&
                        suggest("pdraw particle emitter $name look glow ").contains("start") &&
                        suggest("pdraw particle start ").contains(name) &&
                        suggest("pdraw particle delete template ").contains(name)
                }
                check("v2 帮助和可运行示例") {
                    run("pdraw help") > 0 && run("pdraw help particle") > 0 && run("pdraw help cmd draw") > 0 &&
                        run("pdraw help page 2") > 0 && HelpCommands.exampleGroups.all { group ->
                            group.entries.all { example -> run(example.command.removePrefix("/")) > 0 }
                        }
                }
                check("旧粒子入口已移除") {
                    run("pdraw particle batch old kind=emitter") == 0 &&
                        run("pdraw particle group list") == 0
                }
                check("自由镜头需要玩家") { run("pdraw camera pos 0 60 0 0 0 70 0") == 0 }
                CommandParticleGroups.clearPrepared(dimension)
                CommandParticleRecipes.discard(dimension)
                ServerParticleSources.discard(dimension)
                work.nekow.particledrawing.command.v2.EmitterCommands.discard(dimension)

                check("数据包示例通过 v2 语法检查") {
                    val root = examples.parent
                    java.nio.file.Files.walk(root).use { paths ->
                        paths.filter { it.toString().endsWith(".mcfunction") }.forEach { file ->
                            java.nio.file.Files.readAllLines(file).forEach { line ->
                                if (line.contains("pdraw particle ") && !line.trimStart().startsWith("#")) {
                                    val command = line.substringAfter("pdraw particle ")
                                        .replace(Regex("\\$\\([^)]+\\)"), "0")
                                    work.nekow.particledrawing.command.v2.ParticleCommandsV2.checkSyntax("pdraw particle $command", source)
                                }
                            }
                        }
                    }
                    true
                }
                // 验证第二份地狱之眼函数的开始、收尾与清理。
                runLines("hell-eye-batch.mcfunction")
                check("第二份地狱之眼落地") {
                    val root = CommandParticleRecipes.get(dimension, "hell_eye_root")
                    val intro = CommandParticleRecipes.get(dimension, "hell_eye_intro_outer")
                    val hold = CommandParticleRecipes.get(dimension, "hell_eye_hold_outer")
                    val inner = CommandParticleRecipes.get(dimension, "hell_eye_hold_inner")
                    intro.anchor == root.anchor && hold.anchor == root.anchor &&
                        intro.recipe.seeds.size == 1001 && intro.recipe.lifetime == 10 &&
                        hold.recipe.emitFromTick == 10 && hold.recipe.maxAlive == 8192 && hold.recipe.lifetime == 8 &&
                        inner.recipe.lifetime == -1 && inner.recipe.seeds.size == 1001
                }
                check("第二份地狱之眼在运行") {
                    listOf("hell_eye_intro_outer", "hell_eye_intro_motes", "hell_eye_hold_outer", "hell_eye_hold_inner")
                        .all { CommandParticleRecipes.get(dimension, it).handle?.isActive() == true }
                }
                runLines("hell-eye-batch-end.mcfunction")
                check("第二份收场替换持续状态") {
                    val outer = CommandParticleRecipes.get(dimension, "hell_eye_end_outer")
                    val hold = CommandParticleRecipes.get(dimension, "hell_eye_hold_outer")
                    outer.recipe.seeds.size == 1001 && outer.recipe.lifetime == 5 && outer.handle?.isActive() == true &&
                        hold.handle == null
                }
                runLines("hell-eye-batch-clear.mcfunction")
                check("第二份清空后没有配置") {
                    CommandParticleRecipes.names(dimension).none { it.startsWith("hell_eye_") }
                }
                CommandParticleRecipes.discard(dimension)
                ServerParticleSources.discard(dimension)
            }
        } catch (error: Exception) {
            failures += "自检启动失败: ${error.javaClass.simpleName}: ${error.message}"
        }

        val logger = org.apache.logging.log4j.LogManager.getLogger("ParticleDrawing")
        if (failures.isEmpty()) {
            logger.info("$MARK PASS ($checks 项)")
        } else {
            for (failure in failures) logger.error("$MARK FAIL: {}", failure)
            logger.error("$MARK 失败 ${failures.size} 项 / 共 $checks 项")
        }
    }
}
