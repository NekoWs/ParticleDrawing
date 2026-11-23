package work.nekow.particledrawing.dev;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** 在渲染帧完成后读取游戏缓冲，避免依赖窗口位置和焦点。 */
@EventBusSubscriber(modid = "particledrawing", value = Dist.CLIENT)
public final class DevelopmentCapture {
    private static final Pattern NAME = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}");
    private static final String OUTPUT = System.getProperty("pd.capture.directory");
    /** 两次轮询的最小间隔；录像时用 -PpdCaptureIntervalMs 调小。 */
    private static final long POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(pollMillis());
    private static long nextPoll = System.nanoTime();

    private static long pollMillis() {
        String raw = System.getProperty("pd.capture.intervalMs", "250").strip();
        try {
            return Math.max(0L, Long.parseLong(raw));
        } catch (NumberFormatException ignored) {
            return 250L;
        }
    }

    @SubscribeEvent
    public static void onFrame(RenderFrameEvent.Post event) {
        if (OUTPUT == null) return;
        long now = System.nanoTime();
        if (now - nextPoll < 0L) return;
        nextPoll = now + POLL_NANOS;

        Minecraft client = Minecraft.getInstance();
        if (client.level == null) return;
        Path directory = Path.of(OUTPUT);
        Path request = directory.resolve("capture.request");
        if (!Files.isRegularFile(request)) return;
        try {
            String name = Files.readString(request).strip();
            Files.delete(request);
            if (!NAME.matcher(name).matches()) {
                throw new IOException("Capture names may contain letters, digits, hyphens and underscores");
            }
            Path target = directory.resolve(name + ".png");
            if (Files.exists(target)) throw new IOException("Capture already exists: " + target);
            FramebufferCapture.capture(client, image -> {
                try (image) {
                    image.writeToFile(target);
                    LogUtils.getLogger().info("PD framebuffer capture saved to {}", target);
                } catch (IOException exception) {
                    LogUtils.getLogger().error("Unable to save framebuffer capture", exception);
                }
            });
        } catch (IOException exception) {
            LogUtils.getLogger().error("Unable to handle framebuffer capture request", exception);
        }
    }
}
