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

/**
 * 摄像机预览：覆盖玩家相机的位置与旋转（/pdraw camera 命令）。
 * <p>
 * 原版 {@link Camera#update} 时序：{@code alignWithEntity}（设 position/rotation）→
 * {@code fov = calculateFov} → {@code prepareCullFrustum}（用 position/rotation 备视锥）→
 * {@code setupPerspective}。故在 {@code alignWithEntity} 返回处覆盖 position/rotation，
 * 之后视锥与投影都使用覆盖后的姿态，时序正确。
 * <p>
 * {@code setRotation(float,float,float)} 为 protected，用 {@link Invoker} 调用；
 * {@code position} 字段为 private，用 {@link Accessor} 写入。
 * <p>
 * FOV 由 {@link work.nekow.particledrawing.core.client.ParticleRenderHandler#onComputeFov}
 * 走 {@code ViewportEvent.ComputeFov} 覆盖，不在本 mixin 处理。
 */
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
        ClientAnimationPlayer.CameraPose pose = CameraController.currentPose();
        if (pose == null) {
            return;
        }

        double[] pos = pose.getPos();
        double[] rot = pose.getRot();

        // 旋转约定：编辑器 rot = [pitch, yaw, roll]（度，THREE 'XYZ' intrinsic）。
        // Minecraft setRotation(yRot, xRot, roll) = rotationYXZ（绕 Y 偏航、绕 X 俯仰、绕视线 roll）。
        // 二者旋转顺序不同，此处先按近似映射 (yRot=yaw, xRot=pitch, roll=roll) 实现；
        // TODO: 如需轴对齐外的精确一致，再做欧拉角→矩阵→YXZ 的精确换算。
        this.particleDrawing$invokeSetRotation((float) rot[1], (float) rot[0], (float) rot[2]);
        this.particleDrawing$setPosition(new Vec3(pos[0], pos[1], pos[2]));
    }
}