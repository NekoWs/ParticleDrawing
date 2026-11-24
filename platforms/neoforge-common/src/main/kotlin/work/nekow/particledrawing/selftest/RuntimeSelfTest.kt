package work.nekow.particledrawing.selftest

import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.server.ServerStartedEvent
import work.nekow.particledrawing.ParticleDrawing
import work.nekow.particledrawing.api.ParticleManager
import work.nekow.particledrawing.command.CommandParticleGroups
import work.nekow.particledrawing.command.CommandParticleRecipes
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
                    server.commands.dispatcher.execute("pdraw particle emitter hell_eye_hold_ring emit_from 20", source)
                    val changed = draft.recipe.emitFromTick
                    server.commands.dispatcher.execute("pdraw particle emitter hell_eye_hold_ring emit_from $before", source)
                    changed == 20 && draft.recipe.emitFromTick == before
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

                // 批量命令：dryrun 不落地、原子校验、三种 kind 的落地与补全表。
                fun run(command: String): Int = try {
                    server.commands.dispatcher.execute(command, source)
                } catch (error: Exception) {
                    failures += "批量命令抛异常：$command（${error.javaClass.simpleName}: ${error.message}）"
                    org.apache.logging.log4j.LogManager.getLogger("ParticleDrawing").error("selftest command failed: {}", command, error)
                    -1
                }
                val batchName = "selftest_batch"
                run(
                    "pdraw particle batch $batchName kind=emitter dryrun=true anchor=fixed anchor.pos=0 60 0 " +
                        "sample.count=3 appearance.style=soft_dot appearance.color=#ff0000 rate=4 emitter.lifetime=20 mode=config",
                )
                check("批量 dryrun 不落地") { batchName !in CommandParticleRecipes.names(dimension) }
                run(
                    "pdraw particle batch $batchName kind=emitter anchor=fixed anchor.pos=0 60 0 " +
                        "sample.count=3 appearance.style=soft_dot appearance.color=#ff0000 rate=4 emitter.lifetime=20 mode=config",
                )
                check("批量发射器落地") {
                    val draft = CommandParticleRecipes.get(dimension, batchName)
                    draft.recipe.seeds.size == 3 && draft.recipe.rate == 4 && draft.recipe.lifetime == 20 &&
                        draft.recipe.visual.texture == work.nekow.particledrawing.api.ParticleStyle.SOFT_DOT.textureName &&
                        draft.recipe.birth["r"] == "1.0"
                }
                val atomicResult = run("pdraw particle batch $batchName kind=emitter appearance.color=zz")
                check("批量失败不改旧配置") {
                    atomicResult == 0 && CommandParticleRecipes.get(dimension, batchName).recipe.rate == 4
                }
                run("pdraw particle emitter create selftest_text")
                run("pdraw particle emitter selftest_text sample text SELFTEST")
                check("文字采样命令落地") {
                    val draft = if ("selftest_text" in CommandParticleRecipes.names(dimension)) {
                        CommandParticleRecipes.get(dimension, "selftest_text")
                    } else {
                        null
                    }
                    draft?.sampling == CommandParticleRecipes.Sampling.TEXT &&
                        draft.text == "SELFTEST" && draft.samples(level).size == 1024
                }
                CommandParticleRecipes.remove(dimension, "selftest_text")
                run(
                    "pdraw particle batch selftest_text_batch kind=emitter anchor=fixed anchor.pos=0 60 0 " +
                        "sample.text=SELFTEST|BATCH sample.text_count=512 sample.text_height=2 " +
                        "appearance.style=soft_dot emitter.lifetime=20 mode=config",
                )
                check("批量文字采样落地") {
                    val draft = if ("selftest_text_batch" in CommandParticleRecipes.names(dimension)) {
                        CommandParticleRecipes.get(dimension, "selftest_text_batch")
                    } else {
                        null
                    }
                    draft?.sampling == CommandParticleRecipes.Sampling.TEXT &&
                        draft.text == "SELFTEST\nBATCH" && draft.recipe.seeds.size == 512
                }
                CommandParticleRecipes.remove(dimension, "selftest_text_batch")
                run(
                    "pdraw particle batch selftest_inst kind=emitter anchor=fixed anchor.pos=0 60 0 " +
                        "sample.count=8 appearance.style=soft_dot emitter.lifetime=10 mode=start_instanced",
                )
                check("批量实例模式启动") {
                    CommandParticleRecipes.get(dimension, "selftest_inst")?.handle?.isActive() == true
                }
                CommandParticleRecipes.remove(dimension, "selftest_inst")
                run(
                    "pdraw particle batch selftest_gpu kind=emitter anchor=fixed anchor.pos=0 60 0 " +
                        "sample.count=64 appearance.style=soft_dot appearance.additive=true appearance.glowing=true appearance.light=0 " +
                        "emitter.lifetime=10 mode=burst_gpu",
                )
                check("批量 GPU 模式启动") {
                    CommandParticleRecipes.get(dimension, "selftest_gpu")?.handle?.isActive() == true
                }
                CommandParticleRecipes.remove(dimension, "selftest_gpu")
                run(
                    "pdraw particle batch selftest_text_shape kind=text center=0 70 0 text=SELFTEST|MORPH " +
                        "count=256 height=3 appearance.style=soft_dot",
                )
                check("批量文字组落地") {
                    val entry = CommandParticleGroups.get(dimension, "selftest_text_shape")
                    entry != null && entry.group.size() == 256
                }
                val textMorph = run(
                    "pdraw particle batch selftest_text_shape kind=program keep=true " +
                        "program.1=delay,5 program.2=morph.start " +
                        "morph.slots=64,\"vec3(cos(i*0.1)*4,0,sin(i*0.1)*4)\" morph.ticks=20",
                )
                check("文字组 morph 落地") {
                    textMorph > 0 && CommandParticleGroups.get(dimension, "selftest_text_shape")?.programmed == true
                }
                CommandParticleGroups.remove(dimension, "selftest_text_shape")
                run(
                    "pdraw particle batch selftest_text_big kind=text center=0 70 0 text=星 count=6000 " +
                        "height=2 appearance.style=soft_dot",
                )
                check("文字组支持超过 4096 颗") {
                    CommandParticleGroups.get(dimension, "selftest_text_big")?.group?.size() == 6000
                }
                check("文字组上限仍是 8192") {
                    run("pdraw particle batch selftest_text_big kind=text center=0 70 0 text=星 count=8193") == 0
                }
                CommandParticleGroups.remove(dimension, "selftest_text_big")
                check("自由镜头需要玩家") { run("pdraw camera pos 0 60 0 0 0 70 0") == 0 }
                val groupName = "selftest_batch_group"
                run(
                    "pdraw particle batch $groupName kind=sphere center=0 70 0 radius=1 count=8 " +
                        "appearance.color=#00ff00 particle.lifetime=10",
                )
                check("批量一次性粒子落地") {
                    val entry = CommandParticleGroups.get(dimension, groupName)
                    entry != null && entry.group.size() == 8
                }
                val shapeAtomic = run(
                    "pdraw particle batch $groupName kind=sphere center=0 70 0 radius=-1 count=8 appearance.color=#00ff00",
                )
                check("批量一次性粒子原子校验") {
                    shapeAtomic == 0 && CommandParticleGroups.get(dimension, groupName)?.group?.size() == 8
                }
                run("pdraw particle batch $groupName kind=program program.1=delay,5 program.2=spin,0,1,0,3")
                check("批量时间线落地") { CommandParticleGroups.get(dimension, groupName)?.programmed == true }
                val replaceResult = run("pdraw particle batch $groupName kind=program program.1=delay,1")
                check("已下发时间线不允许原地清空") { replaceResult == 0 }
                val appendResult = run("pdraw particle batch $groupName kind=program keep=true program.1=delay,3")
                check("keep=true 追加时间线") { appendResult == 1 }
                val morphResult = run(
                    "pdraw particle batch $groupName kind=program keep=true " +
                        "program.1=morph.start morph.slots=4,\"vec3(i,0,0)\" morph.ticks=10",
                )
                check("批量形变落地") { morphResult == 1 }
                check("批量命令 help 有键表") { run("pdraw particle batch help") > 0 }
                check("交互式帮助索引可打开") { run("pdraw help") > 0 }
                check("交互式帮助主题可打开") { run("pdraw help batch") > 0 }
                check("交互式帮助索引可翻页") { run("pdraw help page 2") > 0 }
                check("批量键目录可翻页") { run("pdraw particle batch help 2") > 0 }
                check("批量键详情可打开") { run("pdraw particle batch help key rate") > 0 }
                check("批量分区页可打开") { run("pdraw particle batch help group 外观") > 0 }
                CommandParticleGroups.clear(dimension)

                // 批量版地狱之眼：同样的效果改用 batch 行表达，验证键映射与三种模式。
                runLines("hell-eye-batch.mcfunction")
                check("批量版地狱之眼落地") {
                    val root = CommandParticleRecipes.get(dimension, "hell_eye_root")
                    val intro = CommandParticleRecipes.get(dimension, "hell_eye_intro_outer")
                    val hold = CommandParticleRecipes.get(dimension, "hell_eye_hold_outer")
                    val inner = CommandParticleRecipes.get(dimension, "hell_eye_hold_inner")
                    intro.anchor == root.anchor && hold.anchor == root.anchor &&
                        intro.recipe.seeds.size == 1001 && intro.recipe.lifetime == 10 &&
                        hold.recipe.emitFromTick == 10 && hold.recipe.maxAlive == 8192 && hold.recipe.lifetime == 8 &&
                        inner.recipe.lifetime == -1 && inner.recipe.seeds.size == 1001
                }
                check("批量版地狱之眼在运行") {
                    listOf("hell_eye_intro_outer", "hell_eye_intro_motes", "hell_eye_hold_outer", "hell_eye_hold_inner")
                        .all { CommandParticleRecipes.get(dimension, it).handle?.isActive() == true }
                }
                runLines("hell-eye-batch-end.mcfunction")
                check("批量版收场替换持续状态") {
                    val outer = CommandParticleRecipes.get(dimension, "hell_eye_end_outer")
                    val hold = CommandParticleRecipes.get(dimension, "hell_eye_hold_outer")
                    outer.recipe.seeds.size == 1001 && outer.recipe.lifetime == 5 && outer.handle?.isActive() == true &&
                        hold.handle == null
                }
                runLines("hell-eye-batch-clear.mcfunction")
                check("批量版清空后没有配置") {
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
