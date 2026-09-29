package work.nekow.particledrawing

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.core.client.TrackBuffer
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * track 的逐 tick 插值：端点必须始终是一对**相邻**的权威位置，缺包 tick 原地保持。
 *
 * 症状：端点只在收到包时才前进的话，缺包的 tick 会把同一段 xo→x 重扫一遍
 * （partialTick 在 tick 边界归零），粒子先退回段起点再追回来——目标越快退得越远，
 * 就是实机上的「前后瞬移」。这里先按老规则量出这个倒退，再钉新规则在同样的到达抖动下单调不减。
 *
 * 到达抖动按客户端与服务端 tick 相位漂移建模：每 7 个客户端 tick 收到 7 条位置，
 * 但分布是 0/2/1/…（有的 tick 一条没到、有的到了两条）。
 */
class TrackInterpolationTest {

    /** 匀速直线的权威位置：speed 格/tick。 */
    private fun serverPos(tick: Int, speed: Double): Vec3 = Vec3(tick * speed, 0.0, 0.0)

    /** 每客户端 tick 收到的位置条数（7 tick 平均 1 条）。 */
    private fun arrivals(tick: Int): Int = when (tick % 7) {
        0, 4 -> 0
        2, 6 -> 2
        else -> 1
    }

    /** 桥接粒子的插值端点，规则与 ClientParticleEngine 相同。 */
    private class Bridge {
        var xo = Vec3.ZERO
        var x = Vec3.ZERO

        /** 新规则：每 tick 消费缓冲里最旧的一条；没有就原地保持。 */
        fun applyNew(buffer: TrackBuffer) {
            if (buffer.advance()) {
                xo = x
                x = buffer.current!!
            } else {
                val cur = buffer.current ?: return
                xo = cur
                x = cur
            }
        }

        /** 老规则：只在本 tick 有包时写端点（且只留最后一条），没有就什么都不做。 */
        fun applyOld(delivered: Vec3?) {
            if (delivered == null) return
            xo = x
            x = delivered
        }

        fun render(partialTick: Double): Vec3 = xo.add(x.subtract(xo).scale(partialTick))
    }

    private class Run(val samples: List<Vec3>, val maxBackward: Double, val backwardSteps: Int)

    private fun run(newRule: Boolean, speed: Double, maxTicks: Int = 300, frames: Int = 1400): Run {
        val buffer = TrackBuffer()
        val bridge = Bridge()
        val samples = ArrayList<Vec3>()
        var serverTick = 1
        var ticks = 0
        var prev = Double.NEGATIVE_INFINITY
        var maxBackward = 0.0
        var backward = 0

        loop@ for (step in frameSchedule(60.0, frames, 300_000)) {
            for (t in 0 until step.ticks) {
                ticks++
                var delivered: Vec3? = null
                repeat(arrivals(ticks)) {
                    val pos = serverPos(serverTick++, speed)
                    if (newRule) buffer.offer(pos) else delivered = pos
                }
                if (newRule) bridge.applyNew(buffer) else bridge.applyOld(delivered)
                if (ticks >= maxTicks) break@loop
            }
            val rendered = bridge.render(step.partialTick)
            samples.add(rendered)
            if (rendered.x < prev - 1e-9) {
                backward++
                maxBackward = maxOf(maxBackward, prev - rendered.x)
            }
            prev = rendered.x
        }
        return Run(samples, maxBackward, backward)
    }

    @Test
    fun `症状实测：老规则在缺包 tick 上把粒子往回退，5 格每 tick 的目标退满一格`() {
        val old = run(newRule = false, speed = 5.0)
        assertTrue(old.samples.size > 100, "样本太少：${old.samples.size}")
        assertTrue(old.backwardSteps > 0, "老规则必然出现倒退，实测 ${old.backwardSteps} 次")
        assertTrue(old.maxBackward > 1.0, "单次倒退应到一个 tick 的位移量级，实测 ${old.maxBackward} 格")
    }

    @Test
    fun `新规则：同样的到达抖动下渲染位置单调不减`() {
        for (speed in listOf(0.5, 5.0)) {
            val now = run(newRule = true, speed = speed)
            assertTrue(now.samples.size > 100, "样本太少：${now.samples.size}")
            assertEquals(0, now.backwardSteps, "$speed 格/tick 下不该出现任何倒退")
            assertEquals(0.0, now.maxBackward, 0.0, "$speed 格/tick 下最大倒退量应为 0")
        }
    }

    @Test
    fun `缓冲按到达顺序逐 tick 消费一条`() {
        val buffer = TrackBuffer()
        val a = Vec3(1.0, 0.0, 0.0)
        val b = Vec3(2.0, 0.0, 0.0)
        val c = Vec3(3.0, 0.0, 0.0)
        buffer.offer(a)
        buffer.offer(b)
        buffer.offer(c)
        assertEquals(3, buffer.queued())

        assertTrue(buffer.advance())
        assertEquals(a, buffer.current)
        assertEquals(null, buffer.previous)
        assertTrue(buffer.advance())
        assertEquals(b, buffer.current)
        assertEquals(a, buffer.previous)
        assertTrue(buffer.advance())
        assertEquals(c, buffer.current)
        assertEquals(b, buffer.previous)
        // 空缓冲：不前进，端点保持在上一条
        assertTrue(!buffer.advance())
        assertEquals(c, buffer.current)
    }

    @Test
    fun `缓冲超过上限丢最旧的一条，落后量有上限`() {
        val buffer = TrackBuffer(maxQueued = 2)
        for (i in 0 until 5) buffer.offer(Vec3(i.toDouble(), 0.0, 0.0))
        assertEquals(2, buffer.queued())
        buffer.advance()
        assertEquals(Vec3(3.0, 0.0, 0.0), buffer.current)
        buffer.advance()
        assertEquals(Vec3(4.0, 0.0, 0.0), buffer.current)
    }

    @Test
    fun `球壳刚性：一组粒子按同一个包每 tick 前移时形状不变`() {
        val count = 24
        val radius = 0.85
        val offsets = List(count) { i ->
            val phi = acos(2.0 * ((i + 0.5) / count) - 1.0)
            val theta = i * 2.399963229728653 // 黄金角：球面上铺得均匀
            Vec3(sin(phi) * cos(theta) * radius, cos(phi) * radius, sin(phi) * sin(theta) * radius)
        }
        val buffers = Array(count) { TrackBuffer() }
        // 出生瞬间：xo = x = 自己的球面偏移（与服务端 spawn 后桥接粒子的初始状态一致）
        val bridges = Array(count) { Bridge().also { b -> b.xo = offsets[it]; b.x = offsets[it] } }

        var spin = 0.0
        var ticks = 0
        var maxRelDev = 0.0
        loop@ for (step in frameSchedule(60.0, 1400, 300_000)) {
            for (t in 0 until step.ticks) {
                ticks++
                spin += 0.03 // 球壳每 tick 绕 Y 转 0.03 rad
                repeat(arrivals(ticks)) {
                    for (i in 0 until count) buffers[i].offer(rotateY(offsets[i], spin))
                }
                for (i in 0 until count) bridges[i].applyNew(buffers[i])
                if (ticks >= 300) break@loop
            }
            val pts = bridges.map { it.render(step.partialTick) }
            for (i in pts.indices) {
                for (j in i + 1 until pts.size) {
                    val initial = offsets[i].distanceTo(offsets[j])
                    maxRelDev = maxOf(maxRelDev, abs(pts[i].distanceTo(pts[j]) - initial) / initial)
                }
            }
        }
        // 逐粒子线性插值本身会把旋转弦缩短一丝（0.03 rad 量级 → 相对偏差 1e-4 量级），
        // 这不是塌陷；塌成环/排时这个偏差会到 0.1 以上。
        assertTrue(maxRelDev < 1e-3, "球壳形状偏差过大（塌陷）：$maxRelDev")
    }

    private fun rotateY(v: Vec3, radians: Double): Vec3 {
        val c = cos(radians)
        val s = sin(radians)
        return Vec3(v.x * c - v.z * s, v.y, v.x * s + v.z * c)
    }

    /**
     * 把两条规则的插值轨迹画成图，落在 `build/verification/track-interpolation.png`。
     * 纵轴是「渲染位置 − 平均步进 × 帧序号」的残差：老规则（红）在缺包 tick 上出现幅度约
     * 一个 tick 位移的锯齿/回退，新规则（绿）只有一条平滑的小幅漂移。
     * 这条不是断言测试，产物供人工复核（断言在其它用例里）。
     */
    @Test
    fun `输出插值轨迹图供人工复核`() {
        val speed = 5.0
        val old = run(newRule = false, speed = speed)
        val now = run(newRule = true, speed = speed)
        val frames = minOf(200, old.samples.size, now.samples.size)
        val width = 1200
        val height = 420

        fun residual(run: Run): DoubleArray {
            val step = (run.samples[frames - 1].x - run.samples[0].x) / (frames - 1)
            return DoubleArray(frames) { run.samples[it].x - step * it }
        }

        val oldRes = residual(old)
        val nowRes = residual(now)
        val lo = minOf(oldRes.min(), nowRes.min()) - 1.0
        val hi = maxOf(oldRes.max(), nowRes.max()) + 1.0

        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = AwtColor(24, 24, 28)
        g.fillRect(0, 0, width, height)

        fun px(i: Int) = 60.0 + i.toDouble() / (frames - 1) * (width - 90)
        fun py(v: Double) = height - 40 - (v - lo) / (hi - lo) * (height - 100)

        // 每 5 格一条横线（残差的量级 = 格）
        g.color = AwtColor(52, 52, 60)
        var v = ceil(lo / 5.0) * 5.0
        while (v <= hi) {
            g.drawLine(60, py(v).toInt(), width - 30, py(v).toInt())
            v += 5.0
        }
        // 零线（= 与平均步进一致）
        g.color = AwtColor(96, 96, 108)
        g.drawLine(60, py(0.0).toInt(), width - 30, py(0.0).toInt())

        fun trace(values: DoubleArray, color: AwtColor) {
            g.color = color
            var prevX = 0
            var prevY = 0
            for (i in 0 until frames) {
                val x = px(i).toInt()
                val y = py(values[i]).toInt()
                if (i > 0) g.drawLine(prevX, prevY, x, y)
                prevX = x
                prevY = y
            }
        }
        trace(nowRes, AwtColor(90, 220, 120))
        trace(oldRes, AwtColor(240, 90, 90))

        // 老规则的每一次回退标一条黄竖线，长度就是倒退量
        g.color = AwtColor(255, 210, 90)
        for (i in 1 until frames) {
            if (old.samples[i].x < old.samples[i - 1].x - 1e-9) {
                g.drawLine(px(i).toInt(), py(oldRes[i - 1]).toInt(), px(i).toInt(), py(oldRes[i]).toInt())
            }
        }

        g.color = AwtColor(230, 230, 235)
        g.drawString("ParticleDrawing track interpolation — residual = rendered position - mean advance (blocks), target ${speed} blocks/tick, samples at 60fps, y grid = 5 blocks", 40, 20)
        g.drawString("green = fixed: monotone forward (holds instead of re-sweeping)", 40, 38)
        g.drawString("red = before fix: re-sweeps the previous segment on a tick without a packet", 40, 56)
        g.drawString("yellow = backward jump of the old rule (full tick of motion)", 40, 74)
        g.dispose()

        val out = File("build/verification/track-interpolation.png")
        out.parentFile.mkdirs()
        ImageIO.write(image, "png", out)
        assertTrue(out.length() > 0, "轨迹图应当写出来")
    }

    /** AWT 颜色别名（本文件里不带前缀的 Color 是导出用的 API 类型名）。 */
}

private typealias AwtColor = java.awt.Color
