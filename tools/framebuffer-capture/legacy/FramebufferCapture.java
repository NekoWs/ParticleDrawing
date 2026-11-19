package work.nekow.particledrawing.dev;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import java.util.function.Consumer;

final class FramebufferCapture {
    static void capture(Minecraft client, Consumer<NativeImage> saver) {
        saver.accept(Screenshot.takeScreenshot(client.getMainRenderTarget()));
    }
}
