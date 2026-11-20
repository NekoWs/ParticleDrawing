package work.nekow.particledrawing.core.client

import net.minecraft.resources.Identifier
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4fc
import org.joml.Quaternionfc
import org.joml.Vector3d
import org.joml.Vector4f
import org.lwjgl.system.MemoryUtil
import work.nekow.particledrawing.api.Color
import work.nekow.particledrawing.render.InstanceFrameCache
import java.nio.ByteBuffer
import kotlin.math.floor

/** CPU 快照和 GPU 弹道共用实例页。透明顺序单独提交，不搬动实例记录。 */
internal object ParticleInstanceDraws {
    const val CAPACITY = 128
    const val BLOCK_BYTES = CAPACITY * 7 * 16
    const val FRAME_BYTES = 2 * 16 + CAPACITY * Int.SIZE_BYTES

    enum class Blend { OPAQUE, ALPHA, ADDITIVE }
    data class Material(val texture: Identifier, val model: ParticleModelRenderer.Model?, val blend: Blend)

    class Sample {
        lateinit var texture: Identifier
        var model: ParticleModelRenderer.Model? = null
        var blend = Blend.OPAQUE
        val from = Vector3d()
        val to = Vector3d()
        var width0 = 0f
        var height0 = 0f
        var width1 = 0f
        var height1 = 0f
        val color0 = Vector4f()
        val color1 = Vector4f()
        var light = -1
        var orientation: Quaternionfc? = null
        val uv = Vector4f()
    }

    class Block : AutoCloseable {
        private val data = MemoryUtil.memCalloc(BLOCK_BYTES)
        var count = 0
            private set
        var pageSet: CpuPages? = null
        var pageIndex = 0

        fun position(x: Float, y: Float, z: Float, width: Float) {
            data.putFloat(x).putFloat(y).putFloat(z).putFloat(width)
        }

        fun motion(vx: Float, vy: Float, vz: Float, height: Float, ax: Float, ay: Float, az: Float, birth: Float) {
            data.putFloat(vx).putFloat(vy).putFloat(vz).putFloat(height)
            data.putFloat(ax).putFloat(ay).putFloat(az).putFloat(birth)
        }

        fun appearance(color: Color, u0: Float, v0: Float, u1: Float, v1: Float) {
            data.putFloat(color.r).putFloat(color.g).putFloat(color.b).putFloat(color.a)
            data.putFloat(u0).putFloat(v0).putFloat(u1).putFloat(v1)
            data.putFloat(color.r).putFloat(color.g).putFloat(color.b).putFloat(color.a)
            data.putFloat(0f).putFloat(0f).putFloat(0f).putFloat(0f)
            count++
        }

        fun append(sample: Sample, origin: Vec3) {
            val from = sample.from
            val to = sample.to
            position((from.x - origin.x).toFloat(), (from.y - origin.y).toFloat(), (from.z - origin.z).toFloat(), sample.width0)
            motion((to.x - from.x).toFloat(), (to.y - from.y).toFloat(), (to.z - from.z).toFloat(),
                sample.height0, sample.width1, sample.height1, sample.light.toFloat(), 0f)
            val before = sample.color0
            val after = sample.color1
            val uv = sample.uv
            val orientation = sample.orientation
            data.putFloat(before.x).putFloat(before.y).putFloat(before.z).putFloat(before.w)
            data.putFloat(uv.x).putFloat(uv.y).putFloat(uv.z).putFloat(uv.w)
            data.putFloat(after.x).putFloat(after.y).putFloat(after.z).putFloat(after.w)
            data.putFloat(orientation?.x() ?: 0f).putFloat(orientation?.y() ?: 0f)
                .putFloat(orientation?.z() ?: 0f).putFloat(orientation?.w() ?: 0f)
            count++
        }

        fun bytes(): ByteBuffer = data.duplicate().order(data.order()).clear()

        fun reset() {
            data.clear()
            count = 0
            pageSet = null
        }

        override fun close() = MemoryUtil.memFree(data)
    }

    /** 同一批 CPU 页合并上传，按设备要求的对齐量绑定单页。 */
    class CpuPages : AutoCloseable {
        private val blocks = arrayListOf<Block>()
        private var staging: ByteBuffer? = null
        var revision = 0L
            private set

        fun add(block: Block) {
            block.pageSet = this
            block.pageIndex = blocks.size
            blocks += block
        }

        fun bytes(stride: Int): ByteBuffer {
            val size = stride * blocks.size
            val current = staging
            val data = if (current != null && current.capacity() >= size) current else {
                if (current != null) MemoryUtil.memFree(current)
                MemoryUtil.memAlloc(size).also { staging = it }
            }
            data.clear()
            for ((i, block) in blocks.withIndex()) {
                data.position(i * stride)
                data.put(block.bytes())
            }
            return data.position(size).flip()
        }

        fun reset() {
            blocks.clear()
            revision++
        }

        override fun close() {
            staging?.let { MemoryUtil.memFree(it) }
            staging = null
            reset()
        }
    }

    data class Frame(
        val origin: Vec3, val tick: Float, val lifetime: Float, val lastBirth: Float, val partialTick: Float,
    ) {
        fun write(out: ByteBuffer, order: IntArray?, count: Int) {
            out.putFloat(origin.x.toFloat()).putFloat(origin.y.toFloat()).putFloat(origin.z.toFloat()).putFloat(tick)
            out.putFloat(lifetime).putFloat(lastBirth).putFloat(partialTick).putFloat(if (order == null) 0f else 1f)
            if (order != null) for (i in 0 until count) out.putInt(order[i])
            out.position(FRAME_BYTES).flip()
        }
    }

    private data class Key(val material: Material, val origin: Vec3)
    private class Page(val key: Key, val block: Block)
    private class Ordered(val page: Page, val index: Int, val from: Vec3, val to: Vec3, val id: java.util.UUID) {
        var depth = 0.0
    }

    private class Batches {
        val cache = InstanceFrameCache<BridgeParticle>()
        val offered = arrayListOf<BridgeParticle>()
        val pages = linkedMapOf<Key, MutableList<Page>>()
        val data = CpuPages()
        val transparent = arrayListOf<Ordered>()
        var appearanceChanged = false
    }

    private val fixed = Batches()
    private val sampled = Batches()
    private val batches = listOf(fixed, sampled)
    private val available = ArrayDeque<Block>()
    private val sample = Sample()
    private val transparent = arrayListOf<Ordered>()
    private val depthOrder = compareByDescending<Ordered> { it.depth }.thenBy { it.id }
    private val order = IntArray(CAPACITY)

    fun offer(bridge: BridgeParticle, sampledAppearance: Boolean, changed: Boolean) {
        val target = if (sampledAppearance) sampled else fixed
        target.offered.add(bridge)
        target.appearanceChanged = target.appearanceChanged || changed
    }

    fun invalidate() {
        for (batch in batches) batch.cache.clear()
    }

    fun hasDraws() = fixed.offered.isNotEmpty() || sampled.offered.isNotEmpty() || ClientGpuParticles.hasDraws()

    private fun pack(batch: Batches, partialTick: Float) {
        if (!batch.cache.needsPacking(ClientParticleEngine.tickSequence(), TextureCache.version(),
                batch.offered, batch.appearanceChanged)) return
        recyclePages(batch)
        var lastGroup: MutableList<Page>? = null
        var lastKey: Key? = null
        for (bridge in batch.offered) {
            bridge.instanceSample(partialTick, sample)
            if (maxOf(sample.color0.w, sample.color1.w) <= 0f) continue
            val from = sample.from
            val to = sample.to
            val x = cell(from.x); val y = cell(from.y); val z = cell(from.z)
            val prior = lastKey
            val key = if (prior != null && prior.origin.x == x && prior.origin.y == y && prior.origin.z == z &&
                prior.material.texture == sample.texture && prior.material.model === sample.model && prior.material.blend == sample.blend
            ) prior else Key(Material(sample.texture, sample.model, sample.blend), Vec3(x, y, z))
            val group = if (key === prior) checkNotNull(lastGroup) else batch.pages.getOrPut(key) { arrayListOf() }
            lastKey = key
            lastGroup = group
            val page = group.lastOrNull()?.takeIf { it.block.count < CAPACITY }
                ?: Page(key, available.removeFirstOrNull() ?: Block()).also {
                    group.add(it)
                    batch.data.add(it.block)
                }
            val block = page.block
            if (sample.blend == Blend.ALPHA) batch.transparent += Ordered(page, block.count,
                Vec3(from.x, from.y, from.z), Vec3(to.x, to.y, to.z), bridge.particleId)
            block.append(sample, key.origin)
        }
    }

    private fun cell(coordinate: Double) = floor(coordinate / 256.0 + 0.5) * 256.0

    fun forEach(camera: Vec3, matrix: Matrix4fc, draw: (Material, Block, Frame, IntArray?, Int) -> Unit) {
        val partialTick = ClientGpuParticles.partialTick.coerceIn(0f, 1f)
        for (batch in batches) pack(batch, partialTick)
        fun submit(page: Page, indices: IntArray?, count: Int) {
            val (material, origin) = page.key
            if (material.model != null && !ParticleGeometryBudget.reserve(count * material.model.faces.size)) return
            draw(material, page.block, Frame(origin.subtract(camera), -1f, 0f, 0f, partialTick), indices, count)
        }
        fun submitUnordered(blend: Blend) {
            for (batch in batches) for ((key, blocks) in batch.pages) {
                if (key.material.blend == blend) for (page in blocks) submit(page, null, page.block.count)
            }
        }
        submitUnordered(Blend.OPAQUE)
        transparent.clear()
        for (batch in batches) transparent.addAll(batch.transparent)
        if (transparent.isNotEmpty()) {
            for (entry in transparent) {
                val a = entry.from
                val b = entry.to
                val x = a.x + (b.x - a.x) * partialTick - camera.x
                val y = a.y + (b.y - a.y) * partialTick - camera.y
                val z = a.z + (b.z - a.z) * partialTick - camera.z
                entry.depth = -(matrix.m02() * x + matrix.m12() * y + matrix.m22() * z)
            }
            transparent.sortWith(depthOrder)
            var page = transparent.first().page
            var count = 0
            for (entry in transparent) {
                if (entry.page !== page) {
                    submit(page, order, count)
                    page = entry.page
                    count = 0
                }
                order[count++] = entry.index
            }
            submit(page, order, count)
        }
        submitUnordered(Blend.ADDITIVE)
        ClientGpuParticles.forEach(camera) { texture, blocks, frame ->
            val material = Material(texture, null, Blend.ADDITIVE)
            for (block in blocks) draw(material, block, frame, null, block.count)
        }
    }

    fun endFrame() {
        for (batch in batches) {
            if (batch.offered.isEmpty() && batch.pages.isNotEmpty()) {
                batch.cache.clear()
                recyclePages(batch)
            }
            batch.offered.clear()
            batch.appearanceChanged = false
        }
        transparent.clear()
    }

    private fun recyclePages(batch: Batches) {
        for (blocks in batch.pages.values) for (page in blocks) {
            page.block.reset()
            available.addLast(page.block)
        }
        batch.pages.clear()
        batch.transparent.clear()
        batch.data.reset()
    }

    fun close() {
        for (batch in batches) {
            batch.cache.clear()
            batch.offered.clear()
            batch.appearanceChanged = false
            recyclePages(batch)
            batch.data.close()
        }
        transparent.clear()
        for (block in available) block.close()
        available.clear()
    }
}
