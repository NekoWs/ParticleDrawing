package work.nekow.particledrawing.core.client

import work.nekow.particledrawing.animation.ClientAnimationPlayer
import java.util.UUID

/**
 * 客户端「切换到指定摄像机」预览状态（/pdraw camera 命令使用）。
 *
 * 命令执行时调用 [attach] 绑定到某次播放中的某个摄像机；之后由 [ClientAnimationManager.tick]
 * 每 game tick 用 `cameraPoseAt` 刷新 [pose]，渲染层（CameraMixin + ComputeFov 事件）据此覆盖
 * 玩家相机的位置/旋转/FOV。动画播完或停止时由管理器调用 [detach] 恢复正常视角。
 *
 * 注意：播放端**不自动改变玩家相机**——仅当用户显式执行 `/pdraw camera` 时才进入预览模式。
 */
object CameraController {

    /** 当前预览绑定的播放 id（null = 未激活）。 */
    private var animationId: UUID? = null

    /** 当前预览绑定的摄像机 id（`AnimCamera.id`）。 */
    private var cameraId: String? = null

    /** 每 tick 更新的当前姿态快照（渲染线程读取；game tick 与渲染同在主线程，@Volatile 仅作保险）。 */
    @Volatile
    private var pose: ClientAnimationPlayer.CameraPose? = null

    /** 绑定到某次播放的某个摄像机（不清空旧姿态，下一 tick 会刷新）。 */
    @JvmStatic
    fun attach(animId: UUID, camId: String) {
        animationId = animId
        cameraId = camId
    }

    /** 立即写入一次姿态（命令切换时让下一渲染帧立即生效，不必等下一 tick）。 */
    @JvmStatic
    fun updatePose(newPose: ClientAnimationPlayer.CameraPose?) {
        pose = newPose
    }

    /** 退出预览，恢复正常视角。 */
    @JvmStatic
    fun detach() {
        animationId = null
        cameraId = null
        pose = null
    }

    /** 是否处于摄像机预览模式。 */
    @JvmStatic
    fun isActive(): Boolean = animationId != null && cameraId != null

    /** 当前绑定的播放 id（供管理器每 tick 查询对应 entry）。 */
    @JvmStatic
    fun activeAnimationId(): UUID? = animationId

    /** 当前绑定的摄像机 id。 */
    @JvmStatic
    fun activeCameraId(): String? = cameraId

    /** 当前姿态快照（渲染层读取）。 */
    @JvmStatic
    fun currentPose(): ClientAnimationPlayer.CameraPose? = pose

    /** 当前 FOV（度），未激活返回 null（由 ComputeFov 事件决定是否覆盖）。 */
    @JvmStatic
    fun currentFov(): Float? = pose?.fov?.toFloat()
}