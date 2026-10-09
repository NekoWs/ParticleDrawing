package work.nekow.particledrawing

import io.netty.buffer.Unpooled
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.animation.program.AnimInstruction
import work.nekow.particledrawing.animation.program.PivotRef
import work.nekow.particledrawing.animation.program.finiteDurationMs
import work.nekow.particledrawing.api.Orient
import work.nekow.particledrawing.core.easing.EasingType
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 编排动画指令流的编解码，以及「旋转绕哪个轴心」这条语义在指令形状上的体现。
 *
 * 轴心是**程序级状态**：只由 [AnimInstruction.BindPivot] 绑定一次，之后所有旋转/缩放类指令都绕它算。
 * 所以 [AnimInstruction.Spin] / [AnimInstruction.RotateOnce] 身上**没有**轴心字段——
 * `ParticleGroup.spin/rotate` 的参数里也没有，「先 `followEntity` 再 `spin`」的顺序即语义。
 */
class AnimationProgramInstructionTest {

    private val sample = listOf(
        AnimInstruction.FadeIn(0, 20, EasingType.LINEAR),
        AnimInstruction.FadeOut(500, 20, EasingType.EASE_OUT),
        AnimInstruction.Recolor(0, 1f, 0.5f, 0.25f, 1f, 10, EasingType.EASE_IN),
        AnimInstruction.ScaleBy(0, 2f, 10, EasingType.LINEAR),
        AnimInstruction.Translate(0, Vec3(1.0, 2.0, 3.0), 10, EasingType.LINEAR),
        AnimInstruction.RotateOnce(50, Vec3(0.0, 1.0, 0.0), 1.5, 20, EasingType.EASE_IN_OUT),
        AnimInstruction.MovePath(0, listOf(Vec3.ZERO, Vec3(1.0, 0.0, 0.0)), 10, EasingType.LINEAR),
        AnimInstruction.Spin(100, Vec3(0.0, 1.0, 0.0), 0.02),
        AnimInstruction.Pulse(0, 1.5f, 10, -1),
        AnimInstruction.StopContinuous(200),
        AnimInstruction.BindPivot(0, PivotRef.Fixed(Vec3(4.0, 5.0, 6.0))),
        AnimInstruction.BindPivot(0, PivotRef.FollowEntity(UUID.randomUUID(), Vec3(0.0, 1.0, 0.0), local = true)),
        AnimInstruction.BindPivot(0, PivotRef.Movable(Vec3(1.0, 2.0, 3.0), Vec3(0.5, 0.0, 0.0), Orient.VELOCITY)),
        AnimInstruction.Expression(0, "[x,y,z]=get_entity_pos(p)"),
        AnimInstruction.MoveEach(0, 0.35f, 12, EasingType.EASE_OUT),
        AnimInstruction.ScaleTo(0, 0.01f, 12, EasingType.EASE_IN_OUT),
    )

    @Test
    fun `指令流逐条编解码对称，顺序不变`() {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        for (ins in sample) ins.write(buf)

        val decoded = List(sample.size) { AnimInstruction.read(buf) }

        assertEquals(sample, decoded, "指令编解码必须逐字段对称（改指令布局就要同时改双端）")
        assertEquals(0, buf.readableBytes(), "解码后不该剩下字节")
    }

    @Test
    fun `旋转类指令不带轴心字段，轴心只能来自 BindPivot`() {
        for (cls in listOf(AnimInstruction.Spin::class.java, AnimInstruction.RotateOnce::class.java)) {
            val fields = cls.declaredFields.map { it.name }
            assertTrue(
                "pivot" !in fields,
                "${cls.simpleName} 不该带逐指令轴心（现在有：$fields）——轴心由 BindPivot 绑定、对后续指令粘性生效"
            )
        }
        assertTrue(
            AnimInstruction.BindPivot::class.java.declaredFields.any { it.name == "pivot" },
            "BindPivot 才是轴心的唯一载体"
        )
    }

    @Test
    fun `指令平移时刻保留其余字段，且可逆`() {
        val shifted = sample.map { it.shiftStartMs(1500) }

        for ((i, ins) in shifted.withIndex()) {
            assertEquals(sample[i].startMs + 1500, ins.startMs, "第 $i 条的时刻没平移")
        }
        assertEquals(sample, shifted.map { it.shiftStartMs(-1500) }, "平移必须可逆、不丢字段")
        assertTrue(sample[0].shiftStartMs(0) === sample[0], "偏移为 0 时原样返回，不做无谓拷贝")
    }

    @Test
    fun `旋转指令的线上长度里没有轴心的位置`() {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        AnimInstruction.Spin(100, Vec3(0.0, 1.0, 0.0), 0.02).write(buf)

        // tag(1) + startMs(1) + axis(24) + radiansPerMs(8)；若再塞一个 PivotRef.Fixed 会多出 1 + 24 字节
        assertEquals(34, buf.readableBytes(), "Spin 的载荷只该有轴向量与角速度")
    }

    @Test
    fun `有限指令给出终点，无限持续的不参与判定`() {
        assertEquals(250, AnimInstruction.FadeOut(0, 250, EasingType.LINEAR).finiteDurationMs())
        assertEquals(400, AnimInstruction.ScaleTo(0, 0f, 400, EasingType.LINEAR).finiteDurationMs())
        assertEquals(80, AnimInstruction.MoveEach(0, 0.5f, 80, EasingType.LINEAR).finiteDurationMs())
        assertEquals(0, AnimInstruction.BindPivot(0, PivotRef.Movable(Vec3.ZERO)).finiteDurationMs())

        assertNull(AnimInstruction.Spin(0, Vec3(0.0, 1.0, 0.0), 0.02).finiteDurationMs(), "无限自转没有终点")
        assertNull(AnimInstruction.Pulse(0, 1.2f, 100, -1).finiteDurationMs(), "无限脉冲没有终点")
        assertNull(AnimInstruction.Expression(0, "[x]=0").finiteDurationMs())
    }

    @Test
    fun `有限脉冲的终点按「半周期 × 2 × 圈数」算`() {
        assertEquals(600, AnimInstruction.Pulse(0, 1.2f, 100, 3).finiteDurationMs())
    }
}
