package work.nekow.particledrawing.mixin;

import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import work.nekow.particledrawing.animation.ClientAnimationPlayer;
import work.nekow.particledrawing.core.client.CameraController;

// 摄像机预览（/pdraw camera）：在 alignWithEntity 返回处覆盖玩家相机的位置与旋转，
// 之后 prepareCullFrustum / setupPerspective 都会用这个姿态。
// FOV 由 ParticleRenderHandler#onComputeFov 走 ViewportEvent.ComputeFov 覆盖，不在这里处理。
@Mixin(Camera.class)
public abstract class CameraMixin {

    /** 调用 protected {@code Camera#setRotation(float,float,float)}。 */
    @Invoker("setRotation")
    abstract void particleDrawing$invokeSetRotation(float yRot, float xRot, float roll);

    /** 写入 private 字段 {@code Camera#position}。 */
    @Accessor("position")
    abstract void particleDrawing$setPosition(Vec3 position);

    @Inject(method = "alignWithEntity", at = @At("RETURN"))
    private void particleDrawing$overrideCamera(float partialTicks, CallbackInfo ci) {
        // 渲染帧姿态：CameraController 在上一/当前 game tick 姿态间按 partialTicks 插值，
        // 并已加上播放原点偏移（粒子以 origin + 局部坐标生成，摄像机也必须同处一个世界）。
        ClientAnimationPlayer.CameraPose pose = CameraController.currentPose((double) partialTicks);
        if (pose == null) {
            return;
        }

        double[] pos = pose.getPos();
        double[] target = pose.getTarget();
        double rollDeg = pose.getRoll();

        this.particleDrawing$setPosition(new Vec3(pos[0], pos[1], pos[2]));

        // 视线方向 d = normalize(target - pos)
        double dx = target[0] - pos[0];
        double dy = target[1] - pos[1];
        double dz = target[2] - pos[2];
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0E-4) {
            // 目标点与位置重合：朝向无定义，保持原版朝向
            return;
        }

        // 局部 +Z（视线反向，THREE 摄像机约定），与 MC Camera FORWARDS=(0,0,-1) 一致
        double zx = -dx / len;
        double zy = -dy / len;
        double zz = -dz / len;

        // x = up(0,1,0) × z；y = z × x（与 THREE Matrix4.lookAt 一致）
        double h = Math.sqrt(zx * zx + zz * zz);
        double xx, xy, xz;
        if (h < 1.0E-4) {
            // 视线竖直：参考轴任意取水平方向，保持正交
            xx = 1.0; xy = 0.0; xz = 0.0;
        } else {
            xx = zz / h; xy = 0.0; xz = -zx / h;
        }
        double yx = zy * xz - zz * xy;
        double yy = zz * xx - zx * xz;
        double yz = zx * xy - zy * xx;

        // roll 绕局部 +Z（编辑器 camera.rotateZ(roll) 语义）
        double c = Math.cos(Math.toRadians(rollDeg));
        double s = Math.sin(Math.toRadians(rollDeg));
        double xpx = xx * c + yx * s;
        double xpy = xy * c + yy * s;
        double ypx = -xx * s + yx * c;
        double ypy = -xy * s + yy * c;

        // 反解 rotationYXZ(π − yRot, −xRot, −roll)。
        // 公式经 JOML 1.10.8 十万组随机朝向端到端验证（重建误差 < 1e-4），视向竖直时上面已退化处理。
        double a = Math.atan2(zx, zz);
        double b = Math.asin(Math.clamp(-zy, -1.0, 1.0));
        double cc = Math.atan2(xpy, ypy);
        float yRot = (float) Math.toDegrees(Math.PI - a);
        float xRot = (float) Math.toDegrees(-b);
        float rollMc = (float) Math.toDegrees(-cc);

        this.particleDrawing$invokeSetRotation(yRot, xRot, rollMc);
    }
}