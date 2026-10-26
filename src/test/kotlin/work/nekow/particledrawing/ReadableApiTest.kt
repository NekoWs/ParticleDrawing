package work.nekow.particledrawing

import io.netty.buffer.Unpooled
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.api.Acceleration
import work.nekow.particledrawing.api.Angle
import work.nekow.particledrawing.api.Angles
import work.nekow.particledrawing.api.Blend
import work.nekow.particledrawing.api.Color
import work.nekow.particledrawing.api.CurveChannel
import work.nekow.particledrawing.api.Ease
import work.nekow.particledrawing.api.Facing
import work.nekow.particledrawing.api.Length
import work.nekow.particledrawing.api.Lifetime
import work.nekow.particledrawing.api.Light
import work.nekow.particledrawing.api.ParticleSpawnSpec
import work.nekow.particledrawing.api.ParticleStyle
import work.nekow.particledrawing.api.ParticleVisual
import work.nekow.particledrawing.api.Scalar
import work.nekow.particledrawing.api.Time
import work.nekow.particledrawing.api.Velocity
import work.nekow.particledrawing.api.AngularSpeed
import work.nekow.particledrawing.api.blocks
import work.nekow.particledrawing.api.degrees
import work.nekow.particledrawing.api.fadeOut
import work.nekow.particledrawing.api.lifeCurve
import work.nekow.particledrawing.api.mcPixels
import work.nekow.particledrawing.api.milliseconds
import work.nekow.particledrawing.api.particleVisual
import work.nekow.particledrawing.api.radians
import work.nekow.particledrawing.api.radiansPerSecond
import work.nekow.particledrawing.api.sizeEnvelope
import work.nekow.particledrawing.api.ticks
import work.nekow.particledrawing.api.toEasingType
import work.nekow.particledrawing.api.toScalar
import work.nekow.particledrawing.api.times
import work.nekow.particledrawing.api.lerp
import work.nekow.particledrawing.api.pow
import work.nekow.particledrawing.api.polygonPoint
import work.nekow.particledrawing.api.sin
import work.nekow.particledrawing.api.square
import work.nekow.particledrawing.api.vec3
import work.nekow.particledrawing.api.Draw
import work.nekow.particledrawing.api.directionParameter
import work.nekow.particledrawing.api.exit
import work.nekow.particledrawing.api.expression
import work.nekow.particledrawing.api.ExpressionFrame
import work.nekow.particledrawing.api.Frames
import work.nekow.particledrawing.api.PartSelector
import work.nekow.particledrawing.api.ParticleBatch
import work.nekow.particledrawing.api.ParticleGroup
import work.nekow.particledrawing.api.ParticleManager
import work.nekow.particledrawing.api.TypedExpressionBuilder
import work.nekow.particledrawing.api.cos
import work.nekow.particledrawing.api.smoothstep
import work.nekow.particledrawing.api.vectorParameter
import work.nekow.particledrawing.animation.script.compileFunctionObject
import work.nekow.particledrawing.core.client.RenderParticle
import work.nekow.particledrawing.core.server.ServerExitCompletion
import work.nekow.particledrawing.core.network.ParticleExitCompletePayload
import work.nekow.particledrawing.core.network.ParticleExitWatchPayload
import work.nekow.particledrawing.core.network.ParticleSpawnPayload
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 编译门禁：这段代码不执行，只验证文档里的 Kotlin DSL 形状能被解析。
 */
@Suppress("unused")
private fun compileReadableDsl(
    manager: ParticleManager,
    group: ParticleGroup,
    batch: ParticleBatch,
    holder: net.minecraft.world.entity.Entity,
    center: Vec3,
    forward: Vec3,
) {
    val origin = group.vectorParameter("origin", center).value
    val direction = group.directionParameter("direction", forward).value
    group.expression(duration = 5.ticks) {
        val progress = progress(5.ticks)
        val random = member.random01(seed = 1234L, salt = "spread")
        val angle = member.index * Angles.goldenAngle
        val width = sin(progress * 180.degrees) * lerp(0.08.blocks, 0.24.blocks, random)
        val distance = progress * lerp(0.25.blocks, 0.70.blocks, random)
        position.world(origin + direction * distance + vec3(cos(angle) * width, 0.5.blocks, sin(angle) * width))
        scale(smoothstep(0.0.toScalar(), 1.0.toScalar(), progress) * square(1.0.toScalar() - progress))
        color(Color.WHITE)
    }.retire()

    val shape = Draw.polygon(manager) {
        name = "saw"
        coordinateFrame = Frames.ground(holder, followPosition = true, followYaw = false)
        sides = 12
        radius = 1.2.blocks
        samplesPerEdge = 2
        initialAngle = 15.degrees
        angularSpeed = 1.1.radiansPerSecond
        appearance = particleVisual {
            style = ParticleStyle.SQUARE
            worldSize(1.5.mcPixels, 1.mcPixels)
            fullBright = true
            light = Light.none()
        }
        part("teeth", PartSelector { index, edge, _ -> edge == 0 && index % 2 == 0 }, color = Color.ORANGE)
    }
    shape.part("teeth")?.resize(0.5f, 4, Ease.OUT)

    batch.exit {
        particleSize.to(0.0, 8.ticks, Ease.LINEAR)
        preserveMotion()
        removeWhenComplete()
    }
}

/** 可读接口的第一层：显式单位、公开缓动、命名外观/曲线与全亮/光源分离。 */
class ReadableApiTest {

    @Test
    fun `时间单位与整数 tick 的边界`() {
        assertEquals(300.0, 6.ticks.millis, 1e-9)
        assertEquals(6.0, 300.milliseconds.ticks, 1e-9)
        assertEquals(75.0, 1.5.ticks.millis, 1e-9)
        assertEquals(1.5, 75.milliseconds.ticks, 1e-9)
        assertEquals(6, 6.ticks.requireWholeTicks())
        assertEquals(11.0, (16.ticks - 5.ticks).ticks, 1e-9)
        assertEquals(21.0, (16.ticks + 5.ticks).ticks, 1e-9)
        assertTrue((16.ticks - 5.ticks).isConstant())
        assertEquals(3.0, (6.ticks / 2.0.toScalar()).ticks, 1e-9)

        assertFailsWith<IllegalArgumentException> { 75.milliseconds.requireWholeTicks() }
        assertEquals(2, 75.milliseconds.roundedTicks())
        assertEquals(1, 75.milliseconds.floorTicks())
        assertEquals(2, 75.milliseconds.ceilTicks())
        assertFailsWith<IllegalArgumentException> { Time.ticks(Double.NaN) }
        assertFailsWith<IllegalArgumentException> { (-1).ticks.requireNonNegative() }
        assertFailsWith<IllegalArgumentException> { Time.milliseconds(1e308).plus(Time.milliseconds(1e308)) }
        assertFailsWith<IllegalArgumentException> { Time.ticks(1e12).requireWholeTicks() }
        assertFailsWith<IllegalArgumentException> { Time.milliseconds(1e12).roundedTicks() }
    }

    @Test
    fun `长度与角度只在自己的量纲内运算`() {
        assertEquals(19.2, 1.2.blocks.mcPixels, 1e-9)
        assertEquals(0.09375, 1.5.mcPixels.blocks, 1e-9)
        assertEquals(6.0, (2.0.toScalar() * 3.blocks).blocks, 1e-9)
        assertEquals(2.0, (3.blocks - 1.blocks).blocks, 1e-9)
        assertEquals(4.0, (3.blocks + 1.blocks).blocks, 1e-9)
        assertEquals(6.0, (3.blocks * 2.0.toScalar()).blocks, 1e-9)
        assertEquals(1.5, (3.blocks / 2.0.toScalar()).blocks, 1e-9)
        assertTrue((3.blocks - 1.blocks).isConstant())
        assertEquals(PI / 2.0, 90.degrees.radians, 1e-9)
        assertEquals(60.0, (90.degrees - 30.degrees).degrees, 1e-9)
        assertEquals(120.0, (90.degrees + 30.degrees).degrees, 1e-9)
        assertEquals(180.0, PI.radians.degrees, 1e-9)
        assertEquals(2.0, (1.blocks / 0.5.blocks).constant()!!, 1e-9)
        assertEquals(0.15, 3.0.radiansPerSecond.radiansPerTick, 1e-9)
        assertTrue((90.degrees - 30.degrees).isConstant())

        assertEquals(Vec3(1.0, 2.0, 3.0), Velocity.blocksPerSecond(20.0, 40.0, 60.0).toVec3())
        assertEquals(Vec3(1.0, 2.0, 3.0), Acceleration.blocksPerSecondSquared(400.0, 800.0, 1200.0).toVec3())
        assertFailsWith<IllegalArgumentException> { Length.blocks(Double.POSITIVE_INFINITY) }
        assertFailsWith<IllegalArgumentException> { Angle.degrees(Double.NaN) }
        assertFailsWith<IllegalArgumentException> { Length.blocks(Double.MAX_VALUE).mcPixels }
        assertFailsWith<IllegalArgumentException> { Angle.radians(Double.MAX_VALUE).degrees }
    }

    @Test
    fun `寿命无限不再用负数字面量，有限寿命要求整数 tick`() {
        assertEquals(-1, Lifetime.infinite().toTicksOrInfinite())
        assertEquals(3, Lifetime.of(3.ticks).ticks)
        assertFailsWith<IllegalArgumentException> { Lifetime.of(75.milliseconds) }
        assertFailsWith<IllegalArgumentException> { Lifetime.ticks(-1) }
        assertFailsWith<IllegalArgumentException> { Lifetime.infinite().requireFinite() }
    }

    @Test
    fun `公开缓动映射到既有预设`() {
        assertEquals(Ease.OUT, Ease.of(Ease.OUT.toEasingType()))
        assertEquals(Ease.IN_OUT, Ease.of(Ease.IN_OUT.toEasingType()))
        assertTrue(Ease.STEP.toEasingType().isStep())
        assertFailsWith<IllegalArgumentException> { Ease.custom(Double.NaN, 0.0, 1.0, 1.0) }
    }

    @Test
    fun `命名外观把世界尺寸、全亮、照明、混合与朝向分开`() {
        val visual = particleVisual {
            style = ParticleStyle.SQUARE
            worldSize(1.5.mcPixels, 1.mcPixels)
            fullBright = true
            light = Light.none()
            blend = Blend.ALPHA
            facing = Facing.BILLBOARD
        }
        assertTrue(visual.glowing, "全亮")
        assertFalse(visual.emitsLight, "全亮不等于向世界照明")
        assertEquals(0, visual.lightLevel)
        assertEquals(1.5 / 16.0, visual.scaleW.toDouble(), 1e-9)
        assertEquals(1.0 / 16.0, visual.scaleH.toDouble(), 1e-9)
        assertTrue(visual.billboard)

        val emitting = particleVisual {
            fullBright = false
            light = Light.level(12, 0.5f)
            blend = Blend.ADDITIVE
            facing = Facing.FIXED
        }
        assertFalse(emitting.glowing, "投光不必全亮")
        assertTrue(emitting.emitsLight)
        assertEquals(12, emitting.lightLevel)
        assertEquals(0.5f, emitting.lightIntensity)
        assertTrue(emitting.additive)
        assertFalse(emitting.billboard)

        assertFailsWith<IllegalArgumentException> { Light.level(16) }
        assertFailsWith<IllegalArgumentException> { Light.level(4, -0.1f) }

        // 生成规格接收同一份外观契约，不再让调用方重复设 glowing/lightLevel
        val spec = ParticleSpawnSpec().visual(emitting)
        assertFalse(spec.glowing)
        assertEquals(12, spec.lightLevel)
        assertTrue(spec.visual!!.emitsLight)
    }

    @Test
    fun `全亮与投光在渲染粒子和协议里分开`() {
        val position = Vec3.ZERO
        val emitting = RenderParticle(
            java.util.UUID.randomUUID(), position, Color.WHITE, 1f,
            false, 14, -1, emitsLight = true,
        )
        assertEquals(14f, emitting.lightIntensity(0f), 1e-6f)

        val fullOnly = RenderParticle(
            java.util.UUID.randomUUID(), position, Color.WHITE, 1f,
            true, 0, -1, emitsLight = false,
        )
        assertEquals(0f, fullOnly.lightIntensity(0f), 1e-6f)

        val resizing = RenderParticle(
            java.util.UUID.randomUUID(), position, Color.WHITE, 1f,
            false, 0, -1,
        )
        assertEquals(null, resizing.scaleCompletionAtMs())
        resizing.setTargetColorScale(null, 0f, work.nekow.particledrawing.core.easing.EasingType.LINEAR, 400L)
        assertEquals(400L, resizing.scaleCompletionAtMs())

        val visual = ParticleVisual().emitsLight(true).lightLevel(9)
        val out = FriendlyByteBuf(Unpooled.buffer())
        ParticleSpawnPayload.STREAM_CODEC.encode(
            out,
            ParticleSpawnPayload(
                java.util.UUID.randomUUID(), 0.0, 0.0, 0.0, 1f, 1f, 1f, 1f, 1f, -1, null, false, 9, visual,
            ),
        )
        val decoded = ParticleSpawnPayload.STREAM_CODEC.decode(out)
        assertTrue(decoded.visual!!.emitsLight)
        assertEquals(9, decoded.lightLevel, "照明等级仍走生成包的独立字段")
        assertFalse(decoded.glowing)
    }

    @Test
    fun `命名曲线按目标帧归属缓动，并校验时间与通道`() {
        val curve = lifeCurve {
            scale {
                start(0)
                to(1, 1.5.ticks, Ease.OUT)
                hold(5.ticks)
                to(0, 10.ticks, Ease.IN_OUT)
            }
        }
        val keys = curve.curves.single().keys
        assertEquals(listOf(0f, 1.5f, 5f, 10f), keys.map { it.tTicks })
        assertEquals(listOf(0f, 1f, 1f, 0f), keys.map { it.value })
        assertTrue(keys[1].easing.isPreset())
        assertTrue(keys[3].easing.isPreset())
        assertEquals(CurveChannel.SCALE, curve.curves.single().channel)

        val envelope = sizeEnvelope(10.ticks, 2.ticks, 3.ticks)
        assertEquals(listOf(0f, 2f, 7f, 10f), envelope.curves.single().keys.map { it.tTicks })
        assertEquals(listOf(0f, 1f, 1f, 0f), envelope.curves.single().keys.map { it.value })

        val alpha = fadeOut(10.ticks, 3.ticks)
        assertEquals(listOf(0f, 7f, 10f), alpha.curves.single().keys.map { it.tTicks })
        assertEquals(listOf(1f, 1f, 0f), alpha.curves.single().keys.map { it.value })

        assertFailsWith<IllegalArgumentException> { sizeEnvelope(10.ticks, 8.ticks, 3.ticks) }
        assertFailsWith<IllegalArgumentException> {
            lifeCurve {
                scale {
                    start(0)
                    to(1, 2.ticks)
                    to(0, 2.ticks)
                }
            }
        }
        assertFailsWith<IllegalArgumentException> {
            lifeCurve { scale { to(1, 2.ticks) } }
        }
        assertFailsWith<IllegalArgumentException> {
            lifeCurve { scale { start(0); to(1, (-1).ticks) } }
        }
    }

    @Test
    fun `生成规格把出生速度和加速度与下标一起保存`() {
        val spec = ParticleSpawnSpec()
            .velocity(Velocity.blocksPerTick(1.0, 2.0, 3.0))
            .acceleration(Acceleration.blocksPerTickSquared(0.1, 0.2, 0.3), ticks = 4)
        assertEquals(Vec3(1.0, 2.0, 3.0), spec.velocityPerTick)
        assertEquals(Vec3(0.1, 0.2, 0.3), spec.accelerationPerTickSquared)
        assertEquals(4, spec.accelerationTicks)
        val copy = spec.copy()
        assertEquals(spec.velocityPerTick, copy.velocityPerTick)
        assertEquals(spec.accelerationTicks, copy.accelerationTicks)
    }

    @Test
    fun `坐标基对退化输入报错，局部点按右手基落到世界`() {
        val frame = Frames.basis(Vec3(0.0, 0.0, 1.0), Vec3(0.0, 1.0, 0.0))
        val origin = Vec3(10.0, 2.0, -3.0)
        val placed = Frames.fixed(origin).apply(Vec3(1.0, 2.0, 3.0))
        assertEquals(origin.add(Vec3(1.0, 2.0, 3.0)), placed)
        assertEquals(Vec3(1.0, 0.0, 0.0), frame.right)
        assertEquals(Vec3(0.0, 1.0, 0.0), frame.up)
        assertEquals(Vec3(0.0, 0.0, 1.0), frame.forward)

        assertFailsWith<IllegalArgumentException> { Frames.basis(Vec3.ZERO, Vec3(0.0, 1.0, 0.0)) }
        assertFailsWith<IllegalArgumentException> {
            Frames.basis(Vec3(0.0, 1.0, 0.0), Vec3(0.0, 2.0, 0.0))
        }
    }

    @Test
    fun `类型化表达式生成位置、缩放与颜色文本`() {
        val builder = TypedExpressionBuilder()
        val frame = ExpressionFrame(null, builder)
        with(frame) {
            val progress = progress(300.milliseconds)
            val width = sin(progress * 90.degrees) * lerp(0.1.blocks, 0.2.blocks, member.random01(1L, "spread"))
            position.world(vec3(width, 1.5.blocks, 2.blocks))
            scale(square(1.0.toScalar() - progress))
            color(Color.WHITE)
        }
        val code = builder.build()
        assertTrue(code.startsWith("x="))
        assertTrue(code.contains("y=") && code.contains("z="))
        assertTrue(code.contains("sc="))
        assertTrue(code.contains("r=1.0;g=1.0;b=1.0;a=1.0;"))
        assertTrue(code.contains("rand(i*12.9898+(1.0)+"))
        assertTrue(code.contains("t/300.0"))

        assertFailsWith<IllegalArgumentException> {
            val f = ExpressionFrame(null, TypedExpressionBuilder())
            with(f) { position.local(vec3(1.blocks, 0.blocks, 0.blocks)) }
        }
        assertFailsWith<UnsupportedOperationException> {
            val f = ExpressionFrame(null, TypedExpressionBuilder())
            with(f) { position.preserve() }
        }
    }

    @Test
    fun `表达式标量保留单位文本并支持常量折叠`() {
        val scalar = 2.0.toScalar() * 3.0.toScalar()
        assertEquals(6.0, scalar.constant()!!, 1e-9)
        val expr = (2.0.toScalar() * 3.blocks)
        assertEquals("(2.0*3.0)", expr.expr)
        assertEquals(2.399963229728653, Angles.goldenAngle.constant()!!, 1e-12)
        assertTrue(Scalar.of(1.0).plus(2.0).constant() == 3.0)
    }

    @Test
    fun `空成员退场立即完成并注销账本`() {
        val token = java.util.UUID.randomUUID()
        var removed = false
        ServerExitCompletion.start(
            token,
            java.util.UUID.randomUUID(),
            emptyList(),
            8,
            emptyList(),
        ) { removed = true }
        assertTrue(removed)
        assertFalse(ServerExitCompletion.isPending(token))
    }

    @Test
    fun `退场观察与完成载荷按版本编解码`() {
        val exitId = java.util.UUID.randomUUID()
        val ids = listOf(java.util.UUID.randomUUID(), java.util.UUID.randomUUID())
        val watch = ParticleExitWatchPayload(exitId, 3, 8, ids, true)
        val watchBuf = FriendlyByteBuf(Unpooled.buffer())
        ParticleExitWatchPayload.STREAM_CODEC.encode(watchBuf, watch)
        val decodedWatch = ParticleExitWatchPayload.STREAM_CODEC.decode(watchBuf)
        assertEquals(watch, decodedWatch)

        val complete = ParticleExitCompletePayload(exitId, 3)
        val completeBuf = FriendlyByteBuf(Unpooled.buffer())
        ParticleExitCompletePayload.STREAM_CODEC.encode(completeBuf, complete)
        assertEquals(complete, ParticleExitCompletePayload.STREAM_CODEC.decode(completeBuf))
    }

    @Test
    fun `多边形沿边采样而不是沿圆周采样`() {
        val mid = polygonPoint(
            sides = 3,
            radius = 1.0,
            initialAngle = 0.0,
            edge = 0,
            edgeT = 0.5,
        )
        assertEquals(0.25, mid.x, 1e-9)
        assertEquals(0.0, mid.y, 1e-9)
        assertEquals(0.4330127018922193, mid.z, 1e-9)

        val start = polygonPoint(3, 1.0, 0.0, 0, 0.0)
        val nextStart = polygonPoint(3, 1.0, 0.0, 1, 0.0)
        assertEquals(1.0, start.x, 1e-9)
        assertEquals(0.0, start.z, 1e-9)
        assertEquals(-0.5, nextStart.x, 1e-9)
        assertEquals(0.8660254037844386, nextStart.z, 1e-9)

        val quarter = polygonPoint(4, 2.0, 0.0, 0, 0.25)
        assertEquals(1.5, quarter.x, 1e-9)
        assertEquals(0.5, quarter.z, 1e-9)
    }

    @Test
    fun `动态颜色、pow、阶段与命名局部表达式`() {
        val builder = TypedExpressionBuilder()
        val frame = ExpressionFrame(null, builder)
        with(frame) {
            val phase = start(2.ticks)
            val presence = local("presence", phase.progress)
            position.world(vec3(0.blocks, 0.blocks, 0.blocks))
            scale(pow(presence, 0.75))
            color(presence, presence, Scalar.of(0.5), phase.completed)
        }
        val code = builder.build()
        assertTrue(code.startsWith("presence="))
        assertTrue(code.contains("pow(presence,0.75)"))
        assertTrue(code.contains("r=presence;g=presence;b=0.5;a="))
        assertTrue(code.contains("step(100.0,(t-0.0))"))
        assertNotNull(compileFunctionObject(code, emptyList()), "局部赋值/动态颜色必须能被现有编译器接受")

        assertFailsWith<IllegalArgumentException> {
            val f = ExpressionFrame(null, TypedExpressionBuilder())
            with(f) {
                local("presence", Scalar.of(1.0))
                local("presence", Scalar.of(2.0))
            }
        }
    }
}
