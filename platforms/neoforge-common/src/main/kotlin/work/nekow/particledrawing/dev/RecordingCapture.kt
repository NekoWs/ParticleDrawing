package work.nekow.particledrawing.dev

import com.mojang.logging.LogUtils
import net.minecraft.client.Minecraft
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.client.event.RenderFrameEvent
import java.io.BufferedWriter
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit

/**
 * 后台录制：把渲染完成后的帧写成原始像素流，供 ffmpeg 合成视频。
 *
 * 只在设置了环境变量 `PD_CAPTURE_DIR` 时启用，用于宣传片/演示录制；游戏窗口不需要置前，
 * 也不影响鼠标。用法：
 *
 * ```
 * $env:PD_CAPTURE_DIR='...\build\visual-checks\trailer'
 * # 启动游戏后，在目录里创建 capture.start 开始录，创建 capture.stop 停止
 * ```
 *
 * 输出：`frames.raw`（RGBA 字节流）、`frames.index`（每帧相对时间与尺寸）、`frames.meta`（汇总）。
 * 帧率默认 30，可用 `PD_CAPTURE_FPS` 调整。像素格式随版本固定为 RGBA 8 位。
 */
@EventBusSubscriber(modid = "particledrawing", value = [Dist.CLIENT])
object RecordingCapture {
    private val logger = LogUtils.getLogger()
    private val directory: Path? = System.getenv("PD_CAPTURE_DIR")?.takeIf(String::isNotBlank)?.let(Path::of)
    private val targetFps: Double = System.getenv("PD_CAPTURE_FPS")?.toDoubleOrNull()?.takeIf { it in 1.0..240.0 } ?: 30.0
    private val intervalNanos: Long = (1_000_000_000.0 / targetFps).toLong()
    private val pollNanos: Long = TimeUnit.MILLISECONDS.toNanos(200)

    private var recording = false
    private var channel: FileChannel? = null
    private var indexWriter: BufferedWriter? = null
    private var nextFrameNanos = 0L
    private var nextPollNanos = 0L
    private var warnedCapture = false
    private var frames = 0
    private var firstNanos = 0L
    private var lastNanos = 0L
    private var width = 0
    private var height = 0
    private val scratch: ByteBuffer = ByteBuffer.allocateDirect(8 * 1024 * 1024)

    @SubscribeEvent
    @JvmStatic
    fun onFrame(event: RenderFrameEvent.Post) {
        val dir = directory ?: return
        val now = System.nanoTime()
        if (now >= nextPollNanos) {
            nextPollNanos = now + pollNanos
            poll(dir)
        }
        if (!recording) return
        if (now < nextFrameNanos) return
        val client = Minecraft.getInstance()
        if (client.level == null) return
        if (!Framebuffer.capture(client) { image -> writeFrame(image, now) }) {
            if (!warnedCapture) {
                warnedCapture = true
                logger.warn("PD recording cannot read the framebuffer yet")
            }
            return
        }
        // 落后时从当前时刻重新排，避免一次补很多帧。
        nextFrameNanos = if (nextFrameNanos + intervalNanos * 2 < now) now + intervalNanos else nextFrameNanos + intervalNanos
    }

    private fun poll(dir: Path) {
        if (!recording && Files.exists(dir.resolve("capture.start"))) {
            start(dir)
        } else if (recording && Files.exists(dir.resolve("capture.stop"))) {
            stop(dir)
        }
    }

    private fun start(dir: Path) {
        Files.createDirectories(dir)
        Files.deleteIfExists(dir.resolve("capture.start"))
        Files.deleteIfExists(dir.resolve("frames.raw"))
        Files.deleteIfExists(dir.resolve("frames.index"))
        Files.deleteIfExists(dir.resolve("frames.meta"))
        channel = FileChannel.open(
            dir.resolve("frames.raw"), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING,
        )
        indexWriter = Files.newBufferedWriter(dir.resolve("frames.index"))
        frames = 0
        warnedCapture = false
        firstNanos = 0L
        lastNanos = 0L
        width = 0
        height = 0
        recording = true
        nextFrameNanos = System.nanoTime()
        logger.info("PD recording started at {} fps -> {}", targetFps, dir)
    }

    private fun stop(dir: Path) {
        recording = false
        Files.deleteIfExists(dir.resolve("capture.stop"))
        val meta = buildString {
            append("width=").append(width).append('\n')
            append("height=").append(height).append('\n')
            append("format=rgba\n")
            append("frames=").append(frames).append('\n')
            append("firstNanos=").append(firstNanos).append('\n')
            append("lastNanos=").append(lastNanos).append('\n')
            append("fps=").append(targetFps).append('\n')
        }
        runCatching { indexWriter?.close() }
        runCatching { channel?.close() }
        runCatching { Files.writeString(dir.resolve("frames.meta"), meta) }
        indexWriter = null
        channel = null
        logger.info("PD recording stopped: {} frames", frames)
    }

    private fun writeFrame(image: Any, stamp: Long) {
        val out = channel ?: return
        val w = Framebuffer.width(image)
        val h = Framebuffer.height(image)
        if (w <= 0 || h <= 0) return
        val bytes = Framebuffer.pixels(image) ?: return
        width = w
        height = h
        if (frames == 0) firstNanos = stamp
        lastNanos = stamp
        frames++
        if (frames == 1) logger.info("PD recording first frame {}x{}", w, h)
        runCatching {
            indexWriter?.apply {
                write(frames.toString())
                write(" ")
                write((stamp - firstNanos).toString())
                write(" ")
                write(w.toString())
                write(" ")
                write(h.toString())
                write("\n")
            }
        }
        val buffer = if (bytes is ByteBuffer) bytes.duplicate() else byteBufferOf(bytes)
        while (buffer.hasRemaining()) out.write(buffer)
    }

    private fun byteBufferOf(bytes: Any): ByteBuffer {
        val array = bytes as ByteArray
        val buffer = scratch
        buffer.clear()
        if (array.size > buffer.capacity()) {
            return ByteBuffer.wrap(array)
        }
        buffer.put(array)
        buffer.flip()
        return buffer
    }

    /** 跨版本读写 NativeImage：26.x 有 getPixelBytes，1.21.1 只有 getPixelsRGBA。全部走反射。 */
    private object Framebuffer {
        private val minecraftClass = Minecraft::class.java
        private val screenshotClass = Class.forName("net.minecraft.client.Screenshot")
        private val callbackCapture: Method? = screenshotClass.methods
            .firstOrNull { it.name == "takeScreenshot" && it.parameterCount == 2 }
        private val directCapture: Method? = screenshotClass.methods
            .firstOrNull { it.name == "takeScreenshot" && it.parameterCount == 1 }
        private val gameRendererField: java.lang.reflect.Field? = runCatching { minecraftClass.getField("gameRenderer") }.getOrNull()
        private val gameRendererMethod: Method? = runCatching { minecraftClass.getMethod("gameRenderer") }.getOrNull()
        private val mainRenderTargetGetter: Method? = runCatching { minecraftClass.getMethod("getMainRenderTarget") }.getOrNull()
        private val widthMethod: Method? = Class.forName("com.mojang.blaze3d.platform.NativeImage").getMethod("getWidth")
        private val heightMethod: Method? = Class.forName("com.mojang.blaze3d.platform.NativeImage").getMethod("getHeight")
        private val pixelBytes: Method? = runCatching {
            Class.forName("com.mojang.blaze3d.platform.NativeImage").getMethod("getPixelBytes")
        }.getOrNull()
        private val pixelsRgba: Method? = runCatching {
            Class.forName("com.mojang.blaze3d.platform.NativeImage").getMethod("getPixelsRGBA")
        }.getOrNull()
        private val pixelsAbgr: Method? = runCatching {
            Class.forName("com.mojang.blaze3d.platform.NativeImage").getMethod("getPixelsABGR")
        }.getOrNull()

        fun capture(client: Minecraft, consumer: (Any) -> Unit): Boolean {
            val target = renderTarget(client) ?: return false
            return runCatching {
                if (callbackCapture != null) {
                    callbackCapture.invoke(null, target, java.util.function.Consumer<Any> { consumer(it) })
                } else {
                    val image = directCapture?.invoke(null, target) ?: return false
                    consumer(image)
                }
                true
            }.getOrElse {
                LogUtils.getLogger().error("PD recording capture failed", it)
                false
            }
        }

        private fun renderTarget(client: Minecraft): Any? {
            mainRenderTargetGetter?.let { getter -> return runCatching { getter.invoke(client) }.getOrNull() }
            val renderer = gameRendererField?.let { runCatching { it.get(client) }.getOrNull() }
                ?: gameRendererMethod?.let { runCatching { it.invoke(client) }.getOrNull() }
                ?: return null
            return runCatching { renderer.javaClass.getMethod("mainRenderTarget").invoke(renderer) }.getOrNull()
        }

        fun width(image: Any): Int = widthMethod?.let { runCatching { it.invoke(image) as Int }.getOrNull() } ?: 0
        fun height(image: Any): Int = heightMethod?.let { runCatching { it.invoke(image) as Int }.getOrNull() } ?: 0

        /** 返回 ByteBuffer（原始 RGBA）或 IntArray（1.21.1）。 */
        fun pixels(image: Any): Any? {
            pixelBytes?.let { method -> runCatching { return method.invoke(image) as ByteBuffer }.getOrNull() }
            pixelsRgba?.let { method ->
                val ints = runCatching { method.invoke(image) as IntArray }.getOrNull() ?: return@let
                return rgbaBytes(ints)
            }
            pixelsAbgr?.let { method ->
                val ints = runCatching { method.invoke(image) as IntArray }.getOrNull() ?: return@let
                return rgbaBytes(ints, abgr = true)
            }
            return null
        }

        private fun rgbaBytes(pixels: IntArray, abgr: Boolean = false): ByteArray {
            val out = ByteArray(pixels.size * 4)
            var i = 0
            for (pixel in pixels) {
                val a = (pixel ushr 24) and 0xFF
                val b = (pixel ushr 16) and 0xFF
                val g = (pixel ushr 8) and 0xFF
                val r = pixel and 0xFF
                if (abgr) {
                    out[i++] = r.toByte(); out[i++] = g.toByte(); out[i++] = b.toByte(); out[i++] = a.toByte()
                } else {
                    out[i++] = r.toByte(); out[i++] = g.toByte(); out[i++] = b.toByte(); out[i++] = a.toByte()
                }
            }
            return out
        }
    }
}
