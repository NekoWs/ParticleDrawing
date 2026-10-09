package work.nekow.particledrawing

import io.netty.buffer.Unpooled
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.api.Color
import work.nekow.particledrawing.core.client.RenderParticle
import work.nekow.particledrawing.core.network.ParticleAttachPayload
import work.nekow.particledrawing.core.network.ParticleForceBatchPayload
import work.nekow.particledrawing.core.network.ParticleForcePayload
import work.nekow.particledrawing.core.network.ParticleTrackBatchPayload
import work.nekow.particledrawing.core.network.ParticleVelocityBatchPayload
import work.nekow.particledrawing.core.server.ParticleData
import work.nekow.particledrawing.util.AttachMath
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 力驱动（applyForce）两端积分一致 + 实体锚点求解 + 载荷编解码。
 *
 * 力只在开始施力时下发一次，之后两端各自异步积分，服务端与客户端的位置必须逐 tick 相同。
 */
class MotionAndAttachTest {

    private fun serverData(pos: Vec3 = Vec3.ZERO, vel: Vec3 = Vec3.ZERO): ParticleData {
        val data = ParticleData.create(UUID.randomUUID(), pos, Color.WHITE, 1f, -1, null, false, 15, null)
        data.setVelocity(vel)
        return data
    }

    private fun clientParticle(id: UUID, pos: Vec3 = Vec3.ZERO, vel: Vec3 = Vec3.ZERO): RenderParticle {
        val particle = RenderParticle(id, pos, Color.WHITE, 1f, false, 15, 0)
        particle.setVelocity(vel)
        return particle
    }

    @Test
    fun `力驱动：服务端与客户端逐 tick 位置与速度一致`() {
        val accel = Vec3(0.02, -0.05, 0.01)
        val server = serverData(vel = Vec3(0.1, 0.2, 0.0))
        val client = clientParticle(server.id, vel = Vec3(0.1, 0.2, 0.0))
        server.setAcceleration(accel, 5)
        client.setAcceleration(accel, 5)

        for (tick in 1..40) {
            server.stepMotion()
            client.tick()
            assertEquals(server.position().x, client.x(), 1e-12, "第 $tick tick X 不一致")
            assertEquals(server.position().y, client.y(), 1e-12, "第 $tick tick Y 不一致")
            assertEquals(server.position().z, client.z(), 1e-12, "第 $tick tick Z 不一致")
            assertEquals(server.velocity().x, client.velocity().x, 1e-12, "第 $tick tick VX 不一致")
        }
    }

    @Test
    fun `力到期后按惯性继续，速度不再增长`() {
        val accel = Vec3(0.1, 0.0, 0.0)
        val data = serverData()
        data.setAcceleration(accel, 3)
        repeat(3) { data.stepMotion() }
        val velocity = data.velocity().x
        assertEquals(0.3, velocity, 1e-12, "施力 3 tick 后速度应为 3×加速度")

        val position = data.position().x
        data.stepMotion()
        assertEquals(velocity, data.velocity().x, 1e-12, "力到期后速度不再增长")
        assertEquals(position + velocity, data.position().x, 1e-12, "力到期后仍按惯性位移")
    }

    @Test
    fun `无限力持续加速`() {
        val data = serverData()
        data.setAcceleration(Vec3(0.05, 0.0, 0.0), -1)
        repeat(20) { data.stepMotion() }
        assertEquals(1.0, data.velocity().x, 1e-12)
    }

    @Test
    fun `实体锚点：世界空间偏移直接叠加，局部偏移随朝向旋转`() {
        val entityPos = Vec3(10.0, 64.0, -3.0)
        val offset = Vec3(0.0, 1.0, 1.0)

        val world = AttachMath.resolve(entityPos, yawDeg = 90f, pitchDeg = 0f, offset = offset, local = false)
        assertEquals(10.0, world.x, 1e-9)
        assertEquals(65.0, world.y, 1e-9)
        assertEquals(-2.0, world.z, 1e-9)

        // yaw=90（MC 约定：0 = +Z，90 = -X）→ 局部 +Z 偏移转到 -X
        val local = AttachMath.resolve(entityPos, yawDeg = 90f, pitchDeg = 0f, offset = offset, local = true)
        assertEquals(9.0, local.x, 1e-6)
        assertEquals(65.0, local.y, 1e-6)
        assertEquals(-3.0, local.z, 1e-6)
    }

    @Test
    fun `批量直设位置载荷编解码对称`() {
        val updates = List(3) { i ->
            ParticleTrackBatchPayload.Track(UUID.randomUUID(), i * 1.5, -i.toDouble(), 0.25 * i)
        }
        val buf = FriendlyByteBuf(Unpooled.buffer())
        ParticleTrackBatchPayload.STREAM_CODEC.encode(buf, ParticleTrackBatchPayload(updates))
        assertEquals(updates, ParticleTrackBatchPayload.STREAM_CODEC.decode(buf).updates)
    }

    @Test
    fun `力与实体锚点载荷编解码对称`() {
        val id = UUID.randomUUID()
        val force = ParticleForcePayload(id, 0.1, -0.2, 0.3, -1)
        val attach = ParticleAttachPayload(id, 42, UUID.randomUUID(), 1.0, 2.0, 3.0, true)
        val attachById = ParticleAttachPayload(id, 7, null, 0.0, 1.0, 0.0, false)

        val buf = FriendlyByteBuf(Unpooled.buffer())
        ParticleForcePayload.STREAM_CODEC.encode(buf, force)
        ParticleAttachPayload.STREAM_CODEC.encode(buf, attach)
        ParticleAttachPayload.STREAM_CODEC.encode(buf, attachById)
        assertEquals(force, ParticleForcePayload.STREAM_CODEC.decode(buf))
        assertEquals(attach, ParticleAttachPayload.STREAM_CODEC.decode(buf))
        assertEquals(attachById, ParticleAttachPayload.STREAM_CODEC.decode(buf), "只按网络 id 挂载时 uuid 必须能编成 null")
    }

    @Test
    fun `批量速度与批量力载荷编解码对称`() {
        val velocities = List(3) { i ->
            ParticleVelocityBatchPayload.Update(UUID.randomUUID(), i * 0.1, -i * 0.2, 0.25 * i)
        }
        val forces = List(2) { i ->
            ParticleForceBatchPayload.Update(UUID.randomUUID(), 0.1 * i, -0.5, 0.0)
        }

        val buf = FriendlyByteBuf(Unpooled.buffer())
        ParticleVelocityBatchPayload.STREAM_CODEC.encode(buf, ParticleVelocityBatchPayload(velocities))
        ParticleForceBatchPayload.STREAM_CODEC.encode(buf, ParticleForceBatchPayload(-1, forces))

        assertEquals(velocities, ParticleVelocityBatchPayload.STREAM_CODEC.decode(buf).updates)

        val decodedForce = ParticleForceBatchPayload.STREAM_CODEC.decode(buf)
        assertEquals(-1, decodedForce.ticks, "无限施力（ticks < 0）必须原样编解码")
        assertEquals(forces, decodedForce.updates)
    }

    @Test
    fun `批量施力一包覆盖多粒子，两端逐 tick 位置仍然一致`() {
        val accelerations = listOf(
            Vec3(0.02, -0.05, 0.0),
            Vec3(-0.03, 0.01, 0.04),
            Vec3(0.0, -0.1, 0.0),
        )
        val server = accelerations.map { serverData() }

        // 服务端把每颗粒子的力打进一个包；客户端按解码结果逐条施加（与客户端处理器同一路径）
        val payload = ParticleForceBatchPayload(
            ticks = 5,
            updates = server.mapIndexed { i, data ->
                ParticleForceBatchPayload.Update(
                    data.id, accelerations[i].x, accelerations[i].y, accelerations[i].z
                )
            },
        )
        val buf = FriendlyByteBuf(Unpooled.buffer())
        ParticleForceBatchPayload.STREAM_CODEC.encode(buf, payload)
        val received = ParticleForceBatchPayload.STREAM_CODEC.decode(buf)

        val client = received.updates.map { clientParticle(it.particleId) }
        server.forEachIndexed { i, data -> data.setAcceleration(accelerations[i], received.ticks) }
        received.updates.forEachIndexed { i, u ->
            client[i].setAcceleration(Vec3(u.ax, u.ay, u.az), received.ticks)
        }

        for (tick in 1..40) {
            for (i in server.indices) {
                server[i].stepMotion()
                client[i].tick()
                assertEquals(server[i].position().x, client[i].x(), 1e-12, "第 $i 颗第 $tick tick X 不一致")
                assertEquals(server[i].position().y, client[i].y(), 1e-12, "第 $i 颗第 $tick tick Y 不一致")
                assertEquals(server[i].position().z, client[i].z(), 1e-12, "第 $i 颗第 $tick tick Z 不一致")
            }
        }
    }

    @Test
    fun `速度与力指令接管位置并解除实体锚点`() {
        val byVelocity = serverData()
        byVelocity.attach(42, UUID.randomUUID(), Vec3(0.0, 1.0, 0.0), false)
        assertTrue(byVelocity.isAttached())
        byVelocity.setVelocity(Vec3(0.1, 0.0, 0.0))
        assertFalse(byVelocity.isAttached(), "设速度后必须解除锚点，否则位置每 tick 仍被锚点覆盖")

        val byForce = serverData()
        byForce.attach(42, null, Vec3.ZERO, false)
        byForce.setAcceleration(Vec3(0.0, -0.05, 0.0), 5)
        assertFalse(byForce.isAttached(), "施力后必须解除锚点")

        val byClear = serverData()
        byClear.attach(42, null, Vec3.ZERO, false)
        byClear.setAcceleration(Vec3.ZERO, 0)
        assertFalse(byClear.isAttached(), "清力同样是运动指令，一样接管位置")
    }
}
