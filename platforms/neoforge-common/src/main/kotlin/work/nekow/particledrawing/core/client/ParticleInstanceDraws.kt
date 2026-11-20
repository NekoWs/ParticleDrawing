package work.nekow.particledrawing.core.client

import net.minecraft.resources.Identifier
import net.minecraft.world.phys.Vec3
import org.lwjgl.system.MemoryUtil
import java.nio.ByteBuffer

/** CPU 实例和 GPU 弹道共用四边形、批次和实例字段布局。 */
internal object ParticleInstanceDraws {
    const val CAPACITY = 192
    const val BLOCK_BYTES = CAPACITY * 5 * 16
    const val FRAME_BYTES = 2 * 16

    class Block(val persistent: Boolean) : AutoCloseable {
        private val data = MemoryUtil.memCalloc(BLOCK_BYTES)
        var count = 0
            private set

        fun position(x: Float, y: Float, z: Float, width: Float) {
            data.putFloat(x).putFloat(y).putFloat(z).putFloat(width)
        }

        fun motion(vx: Float, vy: Float, vz: Float, height: Float, ax: Float, ay: Float, az: Float, birth: Float) {
            data.putFloat(vx).putFloat(vy).putFloat(vz).putFloat(height)
            data.putFloat(ax).putFloat(ay).putFloat(az).putFloat(birth)
        }

        fun appearance(r: Float, g: Float, b: Float, a: Float, u0: Float, v0: Float, u1: Float, v1: Float) {
            data.putFloat(r).putFloat(g).putFloat(b).putFloat(a)
            data.putFloat(u0).putFloat(v0).putFloat(u1).putFloat(v1)
            count++
        }

        fun bytes(): ByteBuffer {
            // GLSL 声明了整块数组，实际绘制的实例数由 count 决定。
            return data.duplicate().order(data.order()).clear()
        }

        fun reset() { data.clear(); count = 0 }

        override fun close() = MemoryUtil.memFree(data)
    }

    data class Frame(
        val origin: Vec3, val tick: Float, val lifetime: Float, val lastBirth: Float, val partialTick: Float,
    ) {
        fun write(out: ByteBuffer) {
            out.putFloat(origin.x.toFloat()).putFloat(origin.y.toFloat()).putFloat(origin.z.toFloat()).putFloat(tick)
            out.putFloat(lifetime).putFloat(lastBirth).putFloat(partialTick).putFloat(0f).flip()
        }
    }

    private val cpu = linkedMapOf<Identifier, MutableList<Block>>()
    private val available = ArrayDeque<Block>()
    private val cpuFrame = Frame(Vec3.ZERO, -1f, 0f, 0f, 0f)

    fun offer(texture: Identifier, x: Double, y: Double, z: Double, width: Float, height: Float,
              r: Float, g: Float, b: Float, a: Float, u0: Float, v0: Float, u1: Float, v1: Float) {
        if (a <= 0f || width <= 0f || height <= 0f) return
        val batches = cpu.getOrPut(texture) { arrayListOf() }
        val block = batches.lastOrNull()?.takeIf { it.count < CAPACITY }
            ?: (available.removeFirstOrNull() ?: Block(persistent = false)).also(batches::add)
        block.position(x.toFloat(), y.toFloat(), z.toFloat(), width)
        block.motion(0f, 0f, 0f, height, 0f, 0f, 0f, 0f)
        block.appearance(r, g, b, a, u0, v0, u1, v1)
    }

    fun hasDraws() = cpu.isNotEmpty() || ClientGpuParticles.hasDraws()

    fun forEach(camera: Vec3, draw: (Identifier, List<Block>, Frame) -> Unit) {
        for ((texture, blocks) in cpu) draw(texture, blocks, cpuFrame)
        ClientGpuParticles.forEach(camera, draw)
    }

    fun endFrame() {
        for (blocks in cpu.values) for (block in blocks) {
            block.reset()
            available.addLast(block)
        }
        cpu.clear()
    }

    fun close() {
        endFrame()
        for (block in available) block.close()
        available.clear()
    }
}
