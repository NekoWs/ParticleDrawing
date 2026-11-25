package work.nekow.particledrawing.dev

import com.mojang.blaze3d.platform.NativeImage
import com.mojang.logging.LogUtils
import net.minecraft.client.Minecraft
import net.minecraft.commands.CommandResultCallback
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.client.event.RenderFrameEvent
import java.io.BufferedWriter
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

/** 从游戏自己的帧缓冲录制 RGBA。设置 PD_CAPTURE_FPS=0 时读取每个渲染帧。 */
@EventBusSubscriber(modid = "particledrawing", value = [Dist.CLIENT])
object RecordingCapture {
    private val logger = LogUtils.getLogger()
    private val directory = System.getenv("PD_CAPTURE_DIR")?.takeIf(String::isNotBlank)?.let(Path::of)
    private val automation = System.getenv("PD_CAPTURE_AUTOMATION") == "1"
    private val fps = System.getenv("PD_CAPTURE_FPS")?.toDoubleOrNull()?.takeIf { it == 0.0 || it in 1.0..240.0 } ?: 30.0
    private val interval = if (fps == 0.0) 0L else (1_000_000_000.0 / fps).toLong()
    private val pollInterval = TimeUnit.MILLISECONDS.toNanos(25)

    private var nextPoll = 0L
    private var setup: CompletableFuture<Unit>? = null
    private var scene: CompletableFuture<Unit>? = null
    private var take: Take? = null
    private var failed = false

    @SubscribeEvent
    @JvmStatic
    fun onFrame(event: RenderFrameEvent.Post) {
        val dir = directory ?: return
        if (failed) return
        val client = Minecraft.getInstance()
        try {
            val now = System.nanoTime()
            if (now - nextPoll >= 0L) {
                nextPoll = now + pollInterval
                poll(dir, client)
            }
            val current = take ?: return
            if (current.stopping && current.inFlight == 0) {
                current.finish()
                take = null
                if (automation) client.stop()
            } else if (!current.stopping && client.level != null && now >= current.nextFrame) {
                current.nextFrame = if (current.nextFrame + interval * 2 < now) now + interval else current.nextFrame + interval
                current.inFlight++
                Framebuffer.capture(client) { image ->
                    try {
                        image.use { current.write(it, now) }
                    } catch (error: Exception) {
                        fail(dir, client, error)
                    } finally {
                        current.inFlight--
                    }
                }
            }
        } catch (error: Exception) {
            fail(dir, client, error)
        }
    }

    private fun poll(dir: Path, client: Minecraft) {
        if (automation && client.level != null && client.player != null && !client.isPaused) {
            if (setup == null) {
                hideHud(client)
                setup = commands(dir.resolve("setup.mcfunction"), client)
            }
            if (setup?.isDone == true && !Files.exists(dir.resolve("capture.ready"))) {
                setup?.join()
                Files.createFile(dir.resolve("capture.ready"))
            }
            if (take != null && Files.deleteIfExists(dir.resolve("capture.scene"))) {
                scene = commands(dir.resolve("scene.mcfunction"), client)
            }
            if (scene?.isDone == true && !Files.exists(dir.resolve("capture.scene-done"))) {
                scene?.join()
                Files.createFile(dir.resolve("capture.scene-done"))
            }
        }
        if (take == null && Files.deleteIfExists(dir.resolve("capture.start"))) {
            Files.deleteIfExists(dir.resolve("frames.meta"))
            take = Take(dir)
            logger.info("PD recording started at {} -> {}", if (fps == 0.0) "every rendered frame" else "$fps fps", dir)
        }
        if (take != null && Files.deleteIfExists(dir.resolve("capture.stop"))) take?.stopping = true
    }

    private fun commands(path: Path, client: Minecraft): CompletableFuture<Unit> {
        if (!Files.exists(path)) return CompletableFuture.completedFuture(Unit)
        val lines = Files.readAllLines(path).map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }
        val server = checkNotNull(client.singleplayerServer) { "Recording commands require a singleplayer world" }
        val playerId = checkNotNull(client.player).uuid
        val completion = CompletableFuture<Unit>()
        server.execute {
            try {
                val player = checkNotNull(server.playerList.getPlayer(playerId))
                for (line in lines) {
                    // 每条命令重新取玩家位置，前一条命令可能刚传送过玩家。
                    val playerSource = player.createCommandSourceStack()
                    var success = false
                    val source = server.createCommandSourceStack().withEntity(player)
                        .withPosition(playerSource.position).withRotation(playerSource.rotation).withLevel(playerSource.level)
                        .withSuppressedOutput().withCallback(CommandResultCallback { ok, _ -> success = success || ok })
                    server.commands.performPrefixedCommand(source, line)
                    check(success) { "Recording command failed in ${path.fileName}: $line" }
                }
                completion.complete(Unit)
            } catch (error: Exception) {
                completion.completeExceptionally(error)
            }
        }
        return completion
    }

    private fun hideHud(client: Minecraft) {
        val options = client.options
        val legacy = options.javaClass.fields.firstOrNull { it.name == "hideGui" }
        if (legacy != null) {
            legacy.setBoolean(options, true)
        } else {
            val hud = client.gui.javaClass.getField("hud").get(client.gui)
            if (hud.javaClass.getMethod("isHidden").invoke(hud) == false) hud.javaClass.getMethod("toggle").invoke(hud)
        }
    }

    private fun fail(dir: Path, client: Minecraft, error: Exception) {
        if (failed) return
        failed = true
        logger.error("PD recording failed", error)
        runCatching { take?.close() }
        runCatching { Files.writeString(dir.resolve("capture.error"), (error.cause ?: error).toString()) }
        if (automation) client.stop()
    }

    private class Take(private val dir: Path) : AutoCloseable {
        private val index: BufferedWriter = Files.newBufferedWriter(dir.resolve("frames.index"))
        private val raw: FileChannel = try {
            FileChannel.open(dir.resolve("frames.raw"), StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)
        } catch (error: Exception) {
            index.close()
            throw error
        }
        var nextFrame = System.nanoTime()
        var stopping = false
        var inFlight = 0
        private var frames = 0
        private var first = 0L
        private var last = 0L
        private var width = 0
        private var height = 0

        fun write(image: NativeImage, stamp: Long) {
            if (failed) return
            if (frames == 0) {
                first = stamp
                width = image.width
                height = image.height
            }
            check(image.width == width && image.height == height && (frames == 0 || stamp > last)) {
                "Recording frame size or order changed"
            }
            val pixels = Framebuffer.pixels(image)
            check(pixels.remaining().toLong() == width.toLong() * height * 4L) { "Invalid RGBA frame size" }
            while (pixels.hasRemaining()) raw.write(pixels)
            last = stamp
            index.write("${++frames} ${stamp - first} $width $height\n")
            if (frames == 1 && automation) Files.createFile(dir.resolve("capture.active"))
        }

        fun finish() {
            close()
            val metadata = dir.resolve("frames.meta.next")
            Files.writeString(metadata, "width=$width\nheight=$height\nformat=rgba\nframes=$frames\n" +
                "firstNanos=$first\nlastNanos=$last\nfps=$fps\n")
            Files.move(metadata, dir.resolve("frames.meta"), StandardCopyOption.ATOMIC_MOVE)
            logger.info("PD recording stopped: {} frames", frames)
        }

        override fun close() {
            try {
                index.close()
            } finally {
                raw.close()
            }
        }
    }

    private object Framebuffer {
        private val screenshots = Class.forName("net.minecraft.client.Screenshot").methods
            .filter { it.name == "takeScreenshot" }
        private val asynchronous = screenshots.firstOrNull { it.parameterTypes.lastOrNull() == Consumer::class.java && it.parameterCount == 2 }
        private val synchronous = screenshots.firstOrNull { it.parameterCount == 1 }
        private val legacyTarget = Minecraft::class.java.methods.firstOrNull { it.name == "getMainRenderTarget" }
        private val bytes = NativeImage::class.java.methods.firstOrNull { it.name == "getPixelBytes" }
        private val packed = NativeImage::class.java.methods.firstOrNull { it.name in setOf("getPixelsABGR", "getPixelsRGBA") }

        fun capture(client: Minecraft, save: Consumer<NativeImage>) {
            val target = if (legacyTarget != null) legacyTarget.invoke(client) else {
                val renderer = Minecraft::class.java.getField("gameRenderer").get(client)
                renderer.javaClass.getMethod("mainRenderTarget").invoke(renderer)
            }
            if (asynchronous != null) asynchronous.invoke(null, target, save)
            else save.accept(checkNotNull(synchronous).invoke(null, target) as NativeImage)
        }

        fun pixels(image: NativeImage): ByteBuffer {
            if (bytes != null) return (bytes.invoke(image) as ByteBuffer).duplicate()
            val pixels = checkNotNull(packed).invoke(image) as IntArray
            val output = ByteBuffer.allocate(pixels.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            output.asIntBuffer().put(pixels)
            return output
        }
    }
}
