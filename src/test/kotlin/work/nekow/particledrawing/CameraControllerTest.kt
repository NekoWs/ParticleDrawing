package work.nekow.particledrawing

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.animation.ClientAnimationPlayer
import work.nekow.particledrawing.core.client.CameraController
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * /pdraw camera 预览姿态的回归测试：
 * - 姿态必须加上播放原点（粒子以 origin + 局部坐标生成，摄像机同处一个世界）；
 * - 渲染帧按 partialTick 在相邻 game tick 姿态间插值（20Hz → 每帧平滑）。
 */
class CameraControllerTest {

    private fun pose(x: Double, y: Double, z: Double, tx: Double, ty: Double, tz: Double) =
        ClientAnimationPlayer.CameraPose(doubleArrayOf(x, y, z), doubleArrayOf(tx, ty, tz), 10.0, 50.0)

    private fun assertVec(expected: Vec3, actual: DoubleArray, eps: Double = 1e-9) {
        assertEquals(expected.x, actual[0], eps)
        assertEquals(expected.y, actual[1], eps)
        assertEquals(expected.z, actual[2], eps)
    }

    @Test
    fun poseIsOffsetByPlayOrigin() {
        CameraController.detach()
        val origin = Vec3(100.0, 64.0, -30.0)
        CameraController.attach(UUID.randomUUID(), "cam1", origin)
        CameraController.updatePose(pose(1.0, 2.0, 3.0, 4.0, 5.0, 6.0))
        // 刚绑定（无上一 tick 姿态）：直接返回当前姿态 + 原点
        val p = CameraController.currentPose(0.5)
        assertVec(Vec3(101.0, 66.0, -27.0), p!!.pos)
        assertVec(Vec3(104.0, 69.0, -24.0), p.target)
    }

    @Test
    fun poseIsInterpolatedByPartialTicks() {
        CameraController.detach()
        val origin = Vec3(0.0, 0.0, 0.0)
        CameraController.attach(UUID.randomUUID(), "cam1", origin)
        CameraController.updatePose(pose(0.0, 0.0, 0.0, 0.0, 0.0, -1.0))
        CameraController.updatePose(pose(10.0, 0.0, 0.0, 10.0, 0.0, -1.0))
        // partialTick 0 → 上一 tick 姿态；0.5 → 中点；1 → 当前 tick 姿态
        assertVec(Vec3(0.0, 0.0, 0.0), CameraController.currentPose(0.0)!!.pos)
        assertVec(Vec3(5.0, 0.0, 0.0), CameraController.currentPose(0.5)!!.pos)
        assertVec(Vec3(10.0, 0.0, 0.0), CameraController.currentPose(1.0)!!.pos)
        // 两帧 FOV 均为 50 → 插值恒 50
        assertEquals(50.0f, CameraController.currentFov(0.5)!!, 1e-6f)
    }

    @Test
    fun fovAndRollInterpolated() {
        CameraController.detach()
        CameraController.attach(UUID.randomUUID(), "cam1", Vec3.ZERO)
        CameraController.updatePose(ClientAnimationPlayer.CameraPose(doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(0.0, 0.0, -1.0), 0.0, 40.0))
        CameraController.updatePose(ClientAnimationPlayer.CameraPose(doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(0.0, 0.0, -1.0), 30.0, 60.0))
        val p = CameraController.currentPose(0.5)!!
        assertEquals(15.0, p.roll, 1e-9)
        assertEquals(50.0, p.fov, 1e-9)
        assertEquals(50.0f, CameraController.currentFov(0.5)!!, 1e-6f)
    }

    @Test
    fun detachClearsPose() {
        CameraController.detach()
        CameraController.attach(UUID.randomUUID(), "cam1", Vec3.ZERO)
        CameraController.updatePose(pose(0.0, 0.0, 0.0, 0.0, 0.0, -1.0))
        CameraController.detach()
        assertNull(CameraController.currentPose(0.5))
        assertNull(CameraController.currentFov(0.5))
    }
}