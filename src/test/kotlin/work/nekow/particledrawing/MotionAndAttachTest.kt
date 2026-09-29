package work.nekow.particledrawing

import io.netty.buffer.Unpooled
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.api.Color
import work.nekow.particledrawing.core.client.RenderParticle
import work.nekow.particledrawing.core.network.ParticleAttachPayload
import work.nekow.particledrawing.core.network.ParticleForcePayload
import work.nekow.particledrawing.core.network.ParticleTrackBatchPayload
import work.nekow.particledrawing.core.server.ParticleData
import work.nekow.particledrawing.util.AttachMath
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 力驱动（applyForce）两端积分一致 + 实体锚点求解 + 新增载荷编解码。
 *
 * 力只在开始施力时下发一次，之后两端各自异步积分，所以「服务端算出的位置」与
 * 「客户端算出的位置」必须逐 tick 相同，否则两边会越走越远。
 */
class MotionAndAttachTest {

    private fun serverData(pos: Vec3 = Vec3.ZERO, vel: Vec3 = Vec3.ZERO): ParticleData {
        val data = ParticleData.create(UUID.randomUUID(), pos, Color.WHITE, 1f, -1, null, false, 15, null)
        data.setVelocity(vel)
        return data
    }

    private fun clientParticle(id: UUID, pos: Vec3 = Vec3.ZERO, vel: Vec3 = Vec3.ZERO): RenderParticle {
        val particle = RenderParticle(id, pos, Color.WHITE, 1f, false, 15, 0L)
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
        val attach = ParticleAttachPayload(id, 42, 1.0, 2.0, 3.0, true)

        val buf = FriendlyByteBuf(Unpooled.buffer())
        ParticleForcePayload.STREAM_CODEC.encode(buf, force)
        ParticleAttachPayload.STREAM_CODEC.encode(buf, attach)
        assertEquals(force, ParticleForcePayload.STREAM_CODEC.decode(buf))
        assertEquals(attach, ParticleAttachPayload.STREAM_CODEC.decode(buf))
    }
}
