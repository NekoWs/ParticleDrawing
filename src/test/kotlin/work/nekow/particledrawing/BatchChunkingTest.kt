package work.nekow.particledrawing

import io.netty.buffer.Unpooled
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.api.Anchor
import work.nekow.particledrawing.api.BatchCore
import work.nekow.particledrawing.api.EmitMode
import work.nekow.particledrawing.api.ParticleCurve
import work.nekow.particledrawing.api.CurveKey
import work.nekow.particledrawing.api.ParticleLifeCurve
import work.nekow.particledrawing.api.ParticleVisual
import work.nekow.particledrawing.core.easing.EasingType
import work.nekow.particledrawing.core.network.AnchorUpdateBatchPayload
import work.nekow.particledrawing.core.network.BatchChunking
import work.nekow.particledrawing.core.network.EmitterParams
import work.nekow.particledrawing.core.network.EmitterParamsCodec
import work.nekow.particledrawing.core.network.EmitterUpdatePayload
import work.nekow.particledrawing.core.network.ParticleDestroyPayload
import work.nekow.particledrawing.core.network.ParticleForceBatchPayload
import work.nekow.particledrawing.core.network.ParticleTrackBatchPayload
import work.nekow.particledrawing.core.network.ParticleVelocityBatchPayload
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 批量载荷的单包上限契约：发送端一律拆包，载荷两端都按同一上限拒绝畸形包。
 */
class BatchChunkingTest {

    private fun buf() = FriendlyByteBuf(Unpooled.buffer())

    private fun uuid(n: Int) = UUID(0L, n.toLong())

    @Test
    fun `拆包按原顺序切段，每段不超过上限`() {
        for ((size, expected) in listOf(0 to 0, 1 to 1, 512 to 1, 513 to 2, 1024 to 2, 1025 to 3)) {
            val items = List(size) { uuid(it) }
            val chunks = BatchChunking.chunks(items, 512)
            assertEquals(expected, chunks.size, "size=$size 的段数")
            assertTrue(chunks.all { it.size <= 512 }, "size=$size 有段超限")
            assertEquals(items, chunks.flatten(), "拆包不能改顺序")
        }
    }

    @Test
    fun `空列表拆出空结果，上限非正直接报错`() {
        assertTrue(BatchChunking.chunks(emptyList<Int>(), 512).isEmpty())
        assertFailsWith<IllegalArgumentException> { BatchChunking.chunks(listOf(1), 0) }
    }

    @Test
    fun `拆包与上限对齐时不多出空段`() {
        val chunks = BatchChunking.chunks(List(1024) { uuid(it) }, ParticleVelocityBatchPayload.MAX_BATCH)
        assertEquals(listOf(512, 512), chunks.map { it.size })
    }

    @Test
    fun `速度 位置 力 批量包：满包能往返，超限在构造时就报错`() {
        val max = ParticleVelocityBatchPayload.MAX_BATCH

        val velocities = List(max) { ParticleVelocityBatchPayload.Update(uuid(it), 0.1, 0.2, 0.3) }
        val out = buf()
        ParticleVelocityBatchPayload.STREAM_CODEC.encode(out, ParticleVelocityBatchPayload(velocities))
        val decoded = ParticleVelocityBatchPayload.STREAM_CODEC.decode(out).updates
        assertEquals(max, decoded.size)
        assertEquals(velocities.map { it.particleId }, decoded.map { it.particleId }, "id 与速度必须一一对应不串位")

        val tracks = List(max) { ParticleTrackBatchPayload.Track(uuid(it), 1.0, 2.0, 3.0) }
        val tOut = buf()
        ParticleTrackBatchPayload.STREAM_CODEC.encode(tOut, ParticleTrackBatchPayload(tracks))
        assertEquals(max, ParticleTrackBatchPayload.STREAM_CODEC.decode(tOut).updates.size)

        val forces = List(max) { ParticleForceBatchPayload.Update(uuid(it), 0.0, -0.1, 0.0) }
        val fOut = buf()
        ParticleForceBatchPayload.STREAM_CODEC.encode(fOut, ParticleForceBatchPayload(7, forces))
        val fDecoded = ParticleForceBatchPayload.STREAM_CODEC.decode(fOut)
        assertEquals(7, fDecoded.ticks, "一包里的力共用同一个 ticks")
        assertEquals(max, fDecoded.updates.size)

        assertFailsWith<IllegalArgumentException> {
            ParticleVelocityBatchPayload(List(max + 1) { ParticleVelocityBatchPayload.Update(uuid(it), 0.0, 0.0, 0.0) })
        }
        assertFailsWith<IllegalArgumentException> {
            ParticleTrackBatchPayload(List(max + 1) { ParticleTrackBatchPayload.Track(uuid(it), 0.0, 0.0, 0.0) })
        }
        assertFailsWith<IllegalArgumentException> {
            ParticleForceBatchPayload(1, List(max + 1) { ParticleForceBatchPayload.Update(uuid(it), 0.0, 0.0, 0.0) })
        }
    }

    @Test
    fun `销毁与锚点更新包也按同一上限拒绝，并提供拆包入口`() {
        val ids = List(1025) { uuid(it) }
        val chunks = ParticleDestroyPayload.chunked(ids)
        assertEquals(3, chunks.size)
        assertTrue(chunks.all { it.particleIds.size <= ParticleDestroyPayload.MAX_BATCH })
        assertEquals(ids, chunks.flatMap { it.particleIds.toList() }, "拆包后的顺序与组一致")
        assertTrue(chunks.all { it.groupId == null })

        val grouped = ParticleDestroyPayload.chunked(ids, uuid(999))
        assertTrue(grouped.all { it.groupId == uuid(999) }, "组 id 每个分包都要带上")

        assertFailsWith<IllegalArgumentException> {
            ParticleDestroyPayload(Array(ParticleDestroyPayload.MAX_BATCH + 1) { uuid(it) }, null)
        }
        assertFailsWith<IllegalArgumentException> {
            AnchorUpdateBatchPayload(
                List(AnchorUpdateBatchPayload.MAX_BATCH + 1) { AnchorUpdateBatchPayload.AnchorUpdate(uuid(it), 0.0, 0.0, 0.0, 0.0, 0.0, 0.0) },
            )
        }
    }

    @Test
    fun `解码端拒绝畸形条数：不按包里的计数分配内存`() {
        val out = buf()
        out.writeVarInt(ParticleVelocityBatchPayload.MAX_BATCH + 7)
        assertFailsWith<IllegalArgumentException> { ParticleVelocityBatchPayload.STREAM_CODEC.decode(out) }

        val destroy = buf()
        destroy.writeVarInt(ParticleDestroyPayload.MAX_BATCH + 7)
        assertFailsWith<IllegalArgumentException> { ParticleDestroyPayload.STREAM_CODEC.decode(destroy) }

        val anchors = buf()
        anchors.writeVarInt(AnchorUpdateBatchPayload.MAX_BATCH + 7)
        assertFailsWith<IllegalArgumentException> { AnchorUpdateBatchPayload.STREAM_CODEC.decode(anchors) }
    }

    private class Fake(val id: UUID = UUID.randomUUID()) {
        var pos: Vec3? = Vec3.ZERO
    }

    @Test
    fun `1024 个成员（中途有死亡出列）时 id 与向量仍逐个对齐`() {
        val sent = ArrayList<Pair<List<UUID>, List<Vec3>>>()
        val core = BatchCore<Fake>(
            idOf = { it.id },
            stateOf = { f -> f.pos?.let { it to Vec3.ZERO } },
            sendPositions = { ids, positions -> sent.add(ids to positions); ids.size },
            sendVelocities = { ids, values -> sent.add(ids to values); ids.size },
            sendForces = { ids, values, _ -> sent.add(ids to values); ids.size },
            destroy = { it.pos = null },
        )
        val members = List(1024) { Fake() }
        for (m in members) core.add(m)
        // 每隔 3 个死掉一个：出列后剩下的必须仍与各自的速度对上号
        for (i in members.indices step 3) members[i].pos = null

        val alive = members.filter { it.pos != null }
        val velocities = alive.map { Vec3(it.id.leastSignificantBits.toDouble(), 0.0, 0.0) }
        val n = core.setVelocityAll(velocities)

        assertEquals(alive.size, n)
        assertEquals(alive.map { it.id }, sent[0].first, "出列后顺序不变")
        assertEquals(velocities, sent[0].second, "速度与 id 逐个对齐")

        // 拆包后仍然逐段对齐：flatten 回来必须等于原序列
        assertEquals(sent[0].first, BatchChunking.chunks(sent[0].first, 512).flatten())
    }

    @Test
    fun `发射器参数（含抖动与前后偏移）逐字段往返`() {
        val params = EmitterParams(
            EmitMode.DISTANCE, 0.15, 50, 10,
            1f, 0.5f, 0.25f, 1f, 0.4f,
            ParticleVisual().additive(true),
            ParticleLifeCurve.of(ParticleCurve.alpha(CurveKey.at(0, 1f), CurveKey.at(10, 0f, EasingType.EASE_IN))),
            Vec3(0.0, 0.1, 0.0), true, 12, 2048, 0.045, -0.2,
        )
        val out = buf()
        EmitterParamsCodec.write(out, params)
        val decoded = EmitterParamsCodec.read(out)

        assertEquals(params.mode, decoded.mode)
        assertEquals(params.spacing, decoded.spacing)
        assertEquals(params.intervalMs, decoded.intervalMs)
        assertEquals(params.lifetimeTicks, decoded.lifetimeTicks)
        assertEquals(params.scale, decoded.scale)
        assertEquals(params.velocity, decoded.velocity)
        assertEquals(params.lightLevel, decoded.lightLevel)
        assertEquals(params.maxAlive, decoded.maxAlive)
        assertEquals(0.045, decoded.jitter, 1e-9)
        assertEquals(-0.2, decoded.offsetAlong, 1e-9)
        assertEquals(params.visual?.additive, decoded.visual?.additive)
        assertEquals(1, decoded.lifeCurve?.curves?.size)
    }

    @Test
    fun `发射器更新按段下发：只带锚点时不含口径与参数`() {
        val id = uuid(1)
        val onlyAnchor = EmitterUpdatePayload(id, anchor = Anchor.Movable(Vec3(1.0, 2.0, 3.0), Vec3.ZERO))
        val out = buf()
        EmitterUpdatePayload.STREAM_CODEC.encode(out, onlyAnchor)
        val decoded = EmitterUpdatePayload.STREAM_CODEC.decode(out)
        assertEquals(onlyAnchor.emitterId, decoded.emitterId)
        assertTrue(decoded.anchor is Anchor.Movable)
        assertNull(decoded.cadence, "只挪锚点时不该带口径段")
        assertNull(decoded.params, "只挪锚点时不该背曲线与外观的字节")
        assertEquals(0, out.readableBytes(), "解码后不该剩字节")

        val full = EmitterUpdatePayload(
            id,
            anchor = Anchor.Fixed(Vec3.ZERO),
            cadence = EmitterUpdatePayload.EmitterCadence(EmitMode.TIME, 0.3, 100),
            params = EmitterParams(
                EmitMode.TIME, 0.3, 100, 20, 1f, 1f, 1f, 1f, 0.5f,
                null, null, Vec3.ZERO, false, 15, 4096, 0.0, 0.0,
            ),
        )
        val fullOut = buf()
        EmitterUpdatePayload.STREAM_CODEC.encode(fullOut, full)
        val fullDecoded = EmitterUpdatePayload.STREAM_CODEC.decode(fullOut)
        assertEquals(EmitMode.TIME, fullDecoded.cadence?.mode)
        assertEquals(0.3, fullDecoded.cadence?.spacing)
        assertEquals(20, fullDecoded.params?.lifetimeTicks)
    }
}
