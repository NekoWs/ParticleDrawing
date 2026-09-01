package work.nekow.particledrawing.core.client

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.animation.ClientAnimationPlayer
import java.util.UUID

/**
 * 客户端「切换到指定摄像机」预览状态（/pdraw camera 命令使用）。
 *
 * 命令执行时调用 [attach] 绑定到某次播放中的某个摄像机；之后由 [ClientAnimationManager.tick]
 * 每 game tick 用 `cameraPoseAt` 写入最新姿态，渲染层（CameraMixin + ComputeFov 事件）按渲染
 * partialTick 在「上一 tick 姿态 / 当前 tick 姿态」之间插值，再覆盖玩家相机的位置/旋转/FOV。
 * 动画播完或停止时由管理器调用 [detach] 恢复正常视角。
 *
 * 坐标空间：编辑器姿态是动画局部坐标，而粒子以 `origin + 局部坐标` 生成，
 * 因此这里在输出前统一把播放原点 [origin] 加到 pos/target 上，保证相机与粒子同处一个世界。
 *
 * 注意：播放端**不自动改变玩家相机**——仅当用户显式执行 `/pdraw camera` 时才进入预览模式。
 */
object CameraController {

    /** 当前预览绑定的播放 id（null = 未激活）。 */
    private var animationId: UUID? = null

    /** 当前预览绑定的摄像机 id（`AnimCamera.id`）。 */
    private var cameraId: String? = null

    /** 播放原点（世界坐标）：动画局部坐标 + origin = 世界坐标。 */
    private var origin: Vec3? = null

    /** 插值端点：上一 game tick 与当前 game tick 的姿态（均为动画局部坐标）。 */
    @Volatile
    private var prevPose: ClientAnimationPlayer.CameraPose? = null
    @Volatile
    private var currPose: ClientAnimationPlayer.CameraPose? = null

    /** 绑定到某次播放的某个摄像机（含播放原点；下一 tick 开始逐刻刷新姿态）。 */
    @JvmStatic
    fun attach(animId: UUID, camId: String, playOrigin: Vec3) {
        animationId = animId
        cameraId = camId
        origin = playOrigin
    }

    /**
     * 写入最新姿态快照（每个 game tick 由 ClientAnimationManager 调用）。
     * [newPose] 为 null 表示姿态求值失败（摄像机不存在等），直接退出预览。
     */
    @JvmStatic
    fun updatePose(newPose: ClientAnimationPlayer.CameraPose?) {
        if (newPose == null) {
            detach()
            return
        }
        prevPose = currPose
        currPose = newPose
    }

    /** 退出预览，恢复正常视角。 */
    @JvmStatic
    fun detach() {
        animationId = null
        cameraId = null
        origin = null
        prevPose = null
        currPose = null
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

    /**
     * 渲染帧姿态（世界坐标，含播放原点偏移）：在上一 tick 与当前 tick 姿态间按
     * [partialTicks]（0..1，自上一 game tick 起经过的渲染进度）线性插值，
     * 消除 20Hz 逐 tick 跳变造成的卡顿。
     * 无上一 tick 姿态（刚绑定）时直接返回当前姿态。
     */
    @JvmStatic
    fun currentPose(partialTicks: Double): ClientAnimationPlayer.CameraPose? {
        val o = origin ?: return null
        val from = prevPose
        val to = currPose ?: return null
        if (from == null) return offset(to, o)
        val f = partialTicks.coerceIn(0.0, 1.0)
        val pos = DoubleArray(3)
        val target = DoubleArray(3)
        for (i in 0 until 3) {
            pos[i] = from.pos[i] + (to.pos[i] - from.pos[i]) * f
            target[i] = from.target[i] + (to.target[i] - from.target[i]) * f
        }
        val roll = from.roll + (to.roll - from.roll) * f
        val fov = from.fov + (to.fov - from.fov) * f
        val out = ClientAnimationPlayer.CameraPose(pos, target, roll, fov)
        return offset(out, o)
    }

    /** 渲染帧 FOV（度）：与 [currentPose] 同规则插值；未激活返回 null（由 ComputeFov 事件决定是否覆盖）。 */
    @JvmStatic
    fun currentFov(partialTicks: Double): Float? {
        val from = prevPose
        val to = currPose ?: return null
        if (from == null) return to.fov.toFloat()
        val f = partialTicks.coerceIn(0.0, 1.0)
        return (from.fov + (to.fov - from.fov) * f).toFloat()
    }

    /** 动画局部姿态 + 播放原点 = 世界姿态（复制数组，避免污染缓存端点）。 */
    private fun offset(p: ClientAnimationPlayer.CameraPose, o: Vec3): ClientAnimationPlayer.CameraPose {
        return ClientAnimationPlayer.CameraPose(
            doubleArrayOf(p.pos[0] + o.x, p.pos[1] + o.y, p.pos[2] + o.z),
            doubleArrayOf(p.target[0] + o.x, p.target[1] + o.y, p.target[2] + o.z),
            p.roll,
            p.fov,
        )
    }
}