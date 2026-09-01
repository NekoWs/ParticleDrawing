package work.nekow.particledrawing

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.animation.AnimCamera
import work.nekow.particledrawing.animation.AnimKeyframe
import work.nekow.particledrawing.animation.AnimTrack
import work.nekow.particledrawing.animation.ClientAnimationPlayer
import work.nekow.particledrawing.animation.ParticleAnimation
import work.nekow.particledrawing.core.easing.EasingType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * 摄像机「旋转」（绕看向目标点公转，v8）与编辑器同语义的回归测试。
 */
class CameraOrbitPlayerTest {

    private fun player(cam: AnimCamera, tracks: List<AnimTrack>): ClientAnimationPlayer =
        ClientAnimationPlayer(
            ParticleAnimation(loop = false, particles = emptyList(), tracks = tracks, groups = emptyMap(), cameras = listOf(cam)),
            origin = Vec3.ZERO, startGameTick = 0L, currentGameTick = 0L,
        )

    private fun rotTrack(comp: String, deg: Double, mode: AnimTrack.Mode = AnimTrack.Mode.SET): AnimTrack =
        AnimTrack("rot.$comp", listOf("c:cam1"), listOf(AnimKeyframe(0, deg, EasingType.LINEAR)), mode)

    @Test
    fun worldOrbitMatchesEditorSemantics() {
        val cam = AnimCamera("cam1", "c", doubleArrayOf(2.0, 0.0, 0.0), doubleArrayOf(0.0, 0.0, 0.0), 0.0, 50.0, rotLocal = false)
        val pose = assertNotNull(player(cam, listOf(rotTrack("y", 90.0))).cameraPoseAt("cam1", 0.0))
        // 绕世界 Y 轴 90°：(2,0,0) → (0,0,-2)；target 不变
        assertEquals(0.0, pose.pos[0], 1e-5)
        assertEquals(0.0, pose.pos[1], 1e-5)
        assertEquals(-2.0, pose.pos[2], 1e-5)
        assertEquals(listOf(0.0, 0.0, 0.0), pose.target.toList())
    }

    @Test
    fun localOrbitUsesLookAtFrame() {
        // pos (2,0,0) 看向原点：局部 +Z=(1,0,0)，局部 X（right）=(0,0,-1)，局部 Y=(0,1,0)。
        // 绕局部 X 90°：(2,0,0) → (0,-2,0)
        val cam = AnimCamera("cam1", "c", doubleArrayOf(2.0, 0.0, 0.0), doubleArrayOf(0.0, 0.0, 0.0), 0.0, 50.0, rotLocal = true)
        val pose = assertNotNull(player(cam, listOf(rotTrack("x", 90.0))).cameraPoseAt("cam1", 0.0))
        assertEquals(0.0, pose.pos[0], 1e-5)
        assertEquals(-2.0, pose.pos[1], 1e-5)
        assertEquals(0.0, pose.pos[2], 1e-5)
    }

    @Test
    fun localOrbitIncludesRollInBasis() {
        // pos (0,0,2) 看向原点，roll=90：局部 X 轴转到世界 (0,1,0)；rot.x=90 → (2,0,0)。
        val cam = AnimCamera("cam1", "c", doubleArrayOf(0.0, 0.0, 2.0), doubleArrayOf(0.0, 0.0, 0.0), 90.0, 50.0, rotLocal = true)
        val pose = assertNotNull(player(cam, listOf(rotTrack("x", 90.0))).cameraPoseAt("cam1", 0.0))
        assertEquals(2.0, pose.pos[0], 1e-5)
        assertEquals(0.0, pose.pos[1], 1e-5)
        assertEquals(0.0, pose.pos[2], 1e-5)
    }

    @Test
    fun degenerateLookAtFallsBackToWorld() {
        // 摄像机在目标正上方（视线与世界 up 平行）：lookAt 退化 → 回退世界轴旋转。
        // 绕世界 Z 90°：(0,2,0) → (-2,0,0)
        val cam = AnimCamera("cam1", "c", doubleArrayOf(0.0, 2.0, 0.0), doubleArrayOf(0.0, 0.0, 0.0), 0.0, 50.0, rotLocal = true)
        val pose = assertNotNull(player(cam, listOf(rotTrack("z", 90.0))).cameraPoseAt("cam1", 0.0))
        assertEquals(-2.0, pose.pos[0], 1e-5)
        assertEquals(0.0, pose.pos[1], 1e-5)
        assertEquals(0.0, pose.pos[2], 1e-5)
    }

    @Test
    fun coincidentWithTargetSkipsOrbit() {
        val cam = AnimCamera("cam1", "c", doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(0.0, 0.0, 0.0), 0.0, 50.0, rotLocal = true)
        val pose = assertNotNull(player(cam, listOf(rotTrack("y", 90.0))).cameraPoseAt("cam1", 0.0))
        assertEquals(listOf(0.0, 0.0, 0.0), pose.pos.toList())
    }
}