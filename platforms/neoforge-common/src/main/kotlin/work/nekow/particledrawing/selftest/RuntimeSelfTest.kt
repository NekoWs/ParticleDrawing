package work.nekow.particledrawing.selftest

import com.mojang.brigadier.StringReader
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.Vec3
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.server.ServerStartedEvent
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent
import work.nekow.particledrawing.ParticleDrawing
import work.nekow.particledrawing.api.CurveChannel
import work.nekow.particledrawing.api.ParticleManager
import work.nekow.particledrawing.command.CommandParticleGroups
import work.nekow.particledrawing.command.CommandParticleRecipes
import work.nekow.particledrawing.command.help.HelpCommands
import work.nekow.particledrawing.core.easing.EasingType
import work.nekow.particledrawing.core.server.AnimationScheduler
import work.nekow.particledrawing.core.server.ServerParticleSources
import work.nekow.particledrawing.core.server.ServerEntityParticleEffects
import work.nekow.particledrawing.api.EntityParticleEffects
import work.nekow.particledrawing.math.ResourceId
import work.nekow.particledrawing.light.LightFalloff
import work.nekow.particledrawing.light.LightMath
import work.nekow.particledrawing.light.LightShape
import java.util.Locale

/**
 * 服务端运行时自检：在真实 Minecraft 服务端上跑一遍对外 API 契约。
 *
 * 为什么需要它：26.x 的 GameTest 改成了数据驱动、不再有注解 API，三端只有 1.21.1 能写
 * 注解式 GameTest。于是 CI 里 `runGameTestServer` 的「All N required tests passed」
 * 在 26.2/26.3 上只统计到框架自带的那一个测试，看起来通过、实际没验证任何东西。
 *
 * 这个自检用同一份 Kotlin 代码在三端跑，断言射灯的创建、形状、转向/聚焦缓动、形状热更与移除，
 * 以及光照数学和语言表。命令用例直接交给游戏的命令分发器，覆盖绘制、编排、发射器与模板。
 * 只有传 `-Dpd.selftest=1` 时才执行，通过时打印 `[PD-SELFTEST] PASS`，失败时打印带原因的
 * FAIL，CI 依据该标记判定。
 */
@EventBusSubscriber(modid = ParticleDrawing.MODID, value = [Dist.DEDICATED_SERVER])
object RuntimeSelfTest {

    private const val MARK = "[PD-SELFTEST]"

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

            val server = event.server
            val source = server.createCommandSourceStack()
            val dimension = work.nekow.particledrawing.util.ParticleUtils.dimensionUUID(level)

            fun run(command: String, commandSource: net.minecraft.commands.CommandSourceStack = source): Int = try {
                server.commands.dispatcher.execute(command, commandSource)
            } catch (_: com.mojang.brigadier.exceptions.CommandSyntaxException) {
                0
            }

            fun suggest(command: String): List<String> = server.commands.dispatcher.getCompletionSuggestions(
                server.commands.dispatcher.parse(command, source),
            ).join().list.map { it.text }

            val anchorName = "v2_anchor"
            val linkedName = "v2_linked"
            check("发射器锚点链接与发射窗口") {
                if (run("pdraw particle emitter $anchorName at 0 65 0") != 1 ||
                    run("pdraw particle emitter $linkedName at emitter $anchorName sample count 2 life 8 rate 2 window 10 start") != 1
                ) return@check false
                val anchor = CommandParticleRecipes.get(dimension, anchorName)
                val linked = CommandParticleRecipes.get(dimension, linkedName)
                linked.anchor == anchor.anchor && linked.recipe.emitFromTick == 10 &&
                    linked.handle?.isActive() == true
            }
            check("发射窗口可原位更新和恢复") {
                val handle = CommandParticleRecipes.get(dimension, linkedName).handle ?: return@check false
                if (run("pdraw particle advanced emitter $linkedName edit window 20") != 1) return@check false
                val updated = CommandParticleRecipes.get(dimension, linkedName)
                updated.handle === handle && updated.recipe.emitFromTick == 20 &&
                    run("pdraw particle advanced emitter $linkedName edit window 0") == 1 &&
                    CommandParticleRecipes.get(dimension, linkedName).recipe.emitFromTick == 0 &&
                    run("pdraw particle delete emitter $linkedName") == 1 &&
                    run("pdraw particle delete emitter $anchorName") == 1
            }

            val name = "v2_check"
            check("立方体按棱逐颗入场、角点去重") {
                val draw = "pdraw particle draw cuboid as cube at 0 60 0 size 2 density 7 edges"
                if (run("$draw edge_gap 2 stagger 1 enter fade_grow 8") != 68) return@check false
                val group = requireNotNull(CommandParticleGroups.get(dimension, "cube")).group
                val engine = manager.getEngine()
                val data = requireNotNull(engine.getGroup(group.id))
                val first = engine.getParticle(data.memberIds().single()) ?: return@check false
                val curve = first.lifeCurve() ?: return@check false
                val validStart = first.offsetFromPivot() == Vec3(-1.0, -1.0, -1.0) &&
                    engine.pendingDrawCount(group.id) == 67 &&
                    listOf(CurveChannel.ALPHA, CurveChannel.SCALE).all {
                        curve.valueAt(it, 0f) == 0f && curve.valueAt(it, 4f) in 0.6f..0.8f &&
                            curve.valueAt(it, 8f) == 1f && curve.valueAt(it, 40f) == 1f
                    }
                AnimationScheduler.tick()
                val validStagger = group.size() == 2
                repeat(7) { AnimationScheduler.tick() }
                val validGap = group.size() == 7
                AnimationScheduler.tick()
                val secondEdge = engine.getParticle(data.memberIds()[7])?.offsetFromPivot()
                val validEdge = group.size() == 8 && secondEdge != null &&
                    secondEdge.x == 1.0 && secondEdge.y == -1.0 && secondEdge.z in -0.7..-0.6
                repeat(80) { AnimationScheduler.tick() }
                val offsets = data.memberIds().mapNotNull { engine.getParticle(it)?.offsetFromPivot() }
                val validGeometry = offsets.size == 68 && offsets.toSet().size == 68 &&
                    offsets.all { point -> listOf(point.x, point.y, point.z).count { kotlin.math.abs(it) == 1.0 } >= 2 }
                validStart && validStagger && validGap && validEdge && validGeometry && engine.pendingDrawCount(group.id) == 0
            }
            check("方体边长简写与三轴尺寸等价") {
                val short = "pdraw particle draw cuboid as short_cube at 0 60 0 size 2 density 3 edges"
                val long = "pdraw particle draw cuboid as long_cube at 0 60 0 width 2 height 2 depth 2 density 3 edges"
                if (run(short) != 20 || run(long) != 20) return@check false
                val engine = manager.getEngine()
                fun offsets(name: String): Set<Vec3> {
                    val id = requireNotNull(CommandParticleGroups.get(dimension, name)).group.id
                    val members = requireNotNull(engine.getGroup(id)).memberIds()
                    return members.map { requireNotNull(engine.getParticle(it)).offsetFromPivot() }.toSet()
                }
                val actual = offsets("short_cube")
                val expected = offsets("long_cube")
                run("pdraw particle delete group short_cube")
                run("pdraw particle delete group long_cube")
                actual.size == 20 && actual == expected &&
                    run(short.replace("size 2", "size 0") + " dryrun") == 0 &&
                    run(short.replace("size 2", "size -1") + " dryrun") == 0
            }
            check("入场曲线叠加，错峰溢出拒绝覆盖，清理取消剩余入场") {
                val existing = CommandParticleGroups.get(dimension, "cube") ?: return@check false
                val start = "pdraw particle draw cuboid as cube at 0 60 0 width 2 height 2 depth 2 density 3 edges"
                if (run("$start edge_gap 2147483647") != 0 ||
                    CommandParticleGroups.get(dimension, "cube") !== existing ||
                    run("$start look alpha_curve 0:0.5 stagger 1 enter fade 8 ease linear") != 20) return@check false
                val group = requireNotNull(CommandParticleGroups.get(dimension, "cube")).group
                val engine = manager.getEngine()
                val id = engine.getGroup(group.id)?.memberIds()?.singleOrNull() ?: return@check false
                val curve = engine.getParticle(id)?.lifeCurve() ?: return@check false
                curve.valueAt(CurveChannel.ALPHA, 4f) == 0.25f &&
                    curve.valueAt(CurveChannel.SCALE, 4f) == 1f &&
                    run("pdraw particle clear group cube") == 1 &&
                    engine.getParticle(id) == null && engine.pendingDrawCount(group.id) == 0
            }
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
            check("发射参数只保留安全上限") {
                run("pdraw particle template v2_limits sample count 1 look style soft_dot color aqua life 100000 " +
                    "rate 4096 interval 100 max 10000 spacing 0.001 subemit count 512 life 900 speed 48 scale 32") == 1 &&
                    run("pdraw particle template v2_limits sample count 1 max 1000001") == 0 &&
                    run("pdraw particle delete template v2_limits") == 1
            }
            check("多支投射物独立绑定，首次碰撞结束拖尾") {
                val block = BlockPos(0, 200, 0)
                val saved = level.getBlockState(block)
                val attach = "pdraw particle bind arrow_fx emit v2_trail on impact burst v2_hit stop"
                val selector = "@e[type=minecraft:arrow,tag=pd_selftest_arrow,distance=..8]"
                val unbound = "@e[type=minecraft:arrow,tag=pd_selftest_arrow,tag=!pd_selftest_bound,distance=..8,nbt={inGround:0b}]"
                val bind = "execute positioned 0 201 0 as $unbound at @s store success score @s pd_test_arrow_fx run $attach"
                val mark = "tag @e[type=minecraft:arrow,tag=pd_selftest_arrow,scores={pd_test_arrow_fx=1}] add pd_selftest_bound"
                try {
                    level.setBlockAndUpdate(block, Blocks.STONE.defaultBlockState())
                    if (run("scoreboard objectives add pd_test_arrow_fx dummy") <= 0 ||
                        run("pdraw particle template v2_trail sample count 1 look style soft_dot color aqua size 0.10 glow light 0 life 9 max 160 spacing 0.20") != 1 ||
                        run("pdraw particle template v2_hit sample count 16 spawn set velocity vec3(0.14*cos(2*PI*u),0.09,0.14*sin(2*PI*u)) move set ay -0.016 look style soft_dot color gold size 0.15 glow light 0 life 12 rate 16") != 1 ||
                        run(attach) != 0 ||
                        run("""summon minecraft:arrow 0.5 201.5 0.5 {Tags:["pd_selftest_arrow"],Motion:[0.0d,-1.0d,0.0d]}""") != 1 ||
                        run("""summon minecraft:arrow 2.5 201.5 0.5 {Tags:["pd_selftest_arrow"]}""") != 1 ||
                        run(bind) != 2 || run(bind) != 2 || run(mark) != 2 || run(bind) != 0
                    ) return@check false
                    val arrows = EntityArgument.entities().parse(StringReader(selector))
                        .findEntities(source.withPosition(Vec3(0.0, 201.0, 0.0))).filterIsInstance<Projectile>()
                        .sortedBy { it.x }
                    if (arrows.size != 2 || !arrows.all { EntityParticleEffects.isBound(it, "arrow_fx") }) return@check false
                    val (falling, other) = arrows
                    val hit = ProjectileImpactEvent(other, EntityHitResult(falling))
                    hit.isCanceled = true
                    ServerEntityParticleEffects.onImpact(hit)
                    falling.tick()
                    if (EntityParticleEffects.trigger(falling, EntityParticleEffects.IMPACT, falling.position()) != 0 ||
                        EntityParticleEffects.trigger(other, EntityParticleEffects.IMPACT, other.position()) != 1 ||
                        EntityParticleEffects.trigger(other, EntityParticleEffects.IMPACT, other.position()) != 0
                    ) return@check false
                    val event = ResourceId("selftest", "signal")
                    val executing = source.withEntity(other)
                    if (run("pdraw particle bind extra on $event burst v2_hit each", executing) != 1 ||
                        EntityParticleEffects.trigger(other, event, other.position()) != 1 ||
                        EntityParticleEffects.trigger(other, event, other.position()) != 1 ||
                        run("pdraw particle unbind ext?a", executing) != 1 ||
                        EntityParticleEffects.trigger(other, event, other.position()) != 0
                    ) return@check false
                    other.discard()
                    !EntityParticleEffects.isBound(other, "arrow_fx")
                } finally {
                    run("execute positioned 0 201 0 run kill $selector")
                    run("scoreboard objectives remove pd_test_arrow_fx")
                    level.setBlockAndUpdate(block, saved)
                }
            }
            check("生物可单独绑定死亡事件") {
                val target = "@e[type=minecraft:pig,tag=pd_selftest_living,limit=1]"
                if (run("""summon minecraft:pig 0.5 201.5 0.5 {Tags:["pd_selftest_living"],NoAI:1b}""") != 1) return@check false
                val pig = EntityArgument.entity().parse(StringReader(target)).findSingleEntity(source)
                try {
                    run("pdraw particle bind death_fx on death burst v2_hit", source.withEntity(pig)) == 1 &&
                        run("damage $target 100 minecraft:generic") == 1 &&
                        EntityParticleEffects.isBound(pig, "death_fx") &&
                        EntityParticleEffects.trigger(pig, EntityParticleEffects.DEATH, pig.position()) == 0
                } finally {
                    pig.discard()
                }
            }
            check("文字采样与图元") {
                run("pdraw particle emitter v2_text sample text \"SELFTEST\\nV2\" count 512 height 2") == 1 &&
                    CommandParticleRecipes.get(dimension, "v2_text").recipe.seeds.size == 512 &&
                    run("pdraw particle draw text as v2_letters at 0 70 0 text A size 2 count 6000") == 6000 &&
                    run("pdraw particle draw text as v2_letters at 0 70 0 text A count 8193") == 0 &&
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
                    run("pdraw particle advanced group $name morph configure slots all \"vec3(i, 70, 0)\" ticks 10") == 1 &&
                    run("pdraw particle advanced group $name morph start") == 1 &&
                    run("pdraw particle advanced group $name morph inspect") == 1 &&
                    run("pdraw particle advanced group $name morph configure slots all " +
                        "\"vec3(cos(u*2*PI)*2, sin(u*2*PI)*2, 0)\" space local ticks 10") == 1 &&
                    run("pdraw particle advanced group $name morph start") == 1 &&
                    run("pdraw particle draw line as v2_auto from 0 70 0 to 4 70 0 count 5 " +
                        "then morph slots all \"vec3(i, 70, 0)\" ticks 5") == 5 &&
                    CommandParticleGroups.get(dimension, "v2_auto")?.group?.size() == 5 &&
                    run("pdraw particle delete group v2_auto") == 1
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
                    suggest("pdraw particle draw cuboid at 0 60 0 width 2 height 2 depth 2 density 7 ").contains("edges") &&
                    suggest("pdraw particle draw dot at 0 60 0 enter ").containsAll(listOf("fade", "grow", "fade_grow")) &&
                    suggest("pdraw particle draw dot at 0 60 0 enter grow 8 ease ").contains("ease_out_quad") &&
                    suggest("pdraw particle emitter $name ").containsAll(listOf("at", "sample", "look", "start")) &&
                    suggest("pdraw particle emitter $name look glow ").contains("start") &&
                    suggest("pdraw particle start ").contains(name) &&
                    suggest("pdraw particle delete template ").contains(name)
            }
            check("外观、资源、模板覆盖与实体选择器补全") {
                val draw = "pdraw particle draw dot at ~ ~ ~"
                val emitter = "pdraw particle emitter $name"
                suggest("$draw look texture particledrawing:builtin/key").contains("particledrawing:builtin/keyframe_glow") &&
                    suggest("$emitter look texture ").contains("particledrawing:builtin/keyframe_glow") &&
                    suggest("$draw look color red ").none { it == "color" || it == "gradient" } &&
                    "model" !in suggest("pdraw particle draw beam from ~ ~ ~ to ~ ~1 ~ look ") &&
                    "gradient" !in suggest("$emitter look ") &&
                    suggest("$emitter look light_falloff ").contains("inverse_square") &&
                    suggest("pdraw particle use $name as preview without look.tex").contains("look.texture") &&
                    suggest("$draw then follow @e[ty").any { it.endsWith("type=") } &&
                    suggest("$emitter with environment entity_type minecraft:arr").contains("minecraft:arrow") &&
                    suggest("$draw then fade_in 10 ease ease_out_qu").contains("ease_out_quad") &&
                    suggest("pdraw help cmd bi").contains("bind")
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
            check("匿名组可按名称删除、取消错峰并在过期后回收") {
                val old = CommandParticleGroups.names(dimension).toSet()
                val engine = manager.getEngine()
                fun drawAnonymous(command: String): CommandParticleGroups.Entry? {
                    val prior = CommandParticleGroups.names(dimension).toSet()
                    if (run(command) <= 0) return null
                    val key = CommandParticleGroups.names(dimension).singleOrNull { it !in prior } ?: return null
                    return CommandParticleGroups.get(dimension, key)
                }
                val short = drawAnonymous("pdraw particle draw dot at 0 70 0 life 1") ?: return@check false
                val staged = drawAnonymous("pdraw particle draw line from 0 70 0 to 3 70 0 count 4 stagger 2 then spin 0 1 0 1")
                    ?: return@check false
                val last = drawAnonymous("pdraw particle draw dot at 0 70 0") ?: return@check false
                if (!short.anonymous || !short.name.startsWith("anon:") || engine.pendingDrawCount(staged.group.id) != 3 ||
                    run("pdraw particle delete group ${last.name}") != 1 || engine.getGroup(last.group.id) != null
                ) return@check false
                repeat(2) { engine.tick(level.players()) }
                CommandParticleGroups.pruneAnonymous(dimension)
                if (CommandParticleGroups.get(dimension, short.name) != null) return@check false
                val anonymousCount = CommandParticleGroups.names(dimension).count { it.startsWith("anon:") }
                run("pdraw particle clear group anon:*") == anonymousCount &&
                    engine.pendingDrawCount(staged.group.id) == 0 && engine.getGroup(staged.group.id) == null &&
                    CommandParticleGroups.names(dimension).containsAll(old.filterNot { it.startsWith("anon:") })
            }
            check("通配符按完整名字工作，批量删除保护外部锚点引用") {
                if (run("pdraw particle draw dot as glob.a at 0 70 0") != 1 ||
                    run("pdraw particle draw dot as globXa at 0 70 0") != 1 ||
                    run("pdraw particle delete group glob.?") != 1 ||
                    CommandParticleGroups.get(dimension, "globXa") == null ||
                    run("pdraw particle delete group missing_*") != 0 ||
                    run("pdraw particle emitter glob_root at 0 65 0 start") != 1 ||
                    run("pdraw particle emitter glob_child at emitter glob_root start") != 1 ||
                    run("pdraw particle emitter outside at emitter glob_child") != 1 ||
                    run("pdraw particle delete emitter glob_*") != 0 ||
                    CommandParticleRecipes.find(dimension, "glob_root") == null ||
                    CommandParticleRecipes.find(dimension, "glob_child") == null ||
                    run("pdraw particle delete emitter outside") != 1 ||
                    run("pdraw particle stop glob_*") != 2
                ) return@check false
                val root = CommandParticleRecipes.get(dimension, "glob_root")
                val child = CommandParticleRecipes.get(dimension, "glob_child")
                listOf(root, child).all { draft -> draft.activeHandles().none { ServerParticleSources.isContinuouslyEmitting(it.id) } } &&
                    run("pdraw particle clear emitter glob_*") == 2 &&
                    run("pdraw particle delete emitter glob_*") == 2 &&
                    CommandParticleRecipes.names(dimension).none { it.startsWith("glob_") } &&
                    run("pdraw particle template glob_a sample count 1") == 1 &&
                    run("pdraw particle template glob_b sample count 1") == 1 &&
                    run("pdraw particle delete template glob_?") == 2 &&
                    run("pdraw particle field attract \"pdself:glob_a\" 4 0.5") == 1 &&
                    run("pdraw particle field attract \"pdself:glob_b\" 4 0.5") == 1 &&
                    run("pdraw particle field remove pdself:glob_*") == 2
            }
            CommandParticleGroups.clearPrepared(dimension)
            CommandParticleRecipes.discard(dimension)
            ServerParticleSources.discard(dimension)
            work.nekow.particledrawing.command.v2.EmitterCommands.discard(dimension)
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
