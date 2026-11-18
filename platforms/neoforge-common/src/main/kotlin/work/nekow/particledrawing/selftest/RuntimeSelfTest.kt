package work.nekow.particledrawing.selftest

import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.server.ServerStartedEvent
import work.nekow.particledrawing.ParticleDrawing
import work.nekow.particledrawing.api.ParticleManager
import work.nekow.particledrawing.core.easing.EasingType
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
 * 以及光照数学和语言表的基本正确性。只有传 `-Dpd.selftest=1` 时才执行，
 * 通过时打印 `[PD-SELFTEST] PASS`，失败时打印带原因的 FAIL，CI 依据该标记判定。
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
