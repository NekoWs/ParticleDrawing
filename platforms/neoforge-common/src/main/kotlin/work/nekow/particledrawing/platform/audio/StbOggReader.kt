package work.nekow.particledrawing.platform.audio

import org.lwjgl.stb.STBVorbis
import org.lwjgl.stb.STBVorbisInfo
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import work.nekow.particledrawing.animation.script.ScriptWavePcm

class StbOggReader : ScriptWavePcm.OggReader {

        override fun info(data: ByteArray): ScriptWavePcm.OggInfo? = withHandle(data) { handle ->
            val info = STBVorbisInfo.malloc()
            try {
                STBVorbis.stb_vorbis_get_info(handle, info)
                ScriptWavePcm.OggInfo(
                    info.sample_rate(),
                    info.channels(),
                    STBVorbis.stb_vorbis_stream_length_in_samples(handle),
                )
            } finally {
                info.free()
            }
        }

        override fun read(data: ByteArray, startFrame: Int, frameCount: Int, channels: Int): FloatArray? =
            withHandle(data) { handle ->
                // 用 seek 而非 seek_frame：后者取样本会从帧边界开始，波形整体偏移最多一个块。
                if (frameCount <= 0) {
                    null
                } else if (!STBVorbis.stb_vorbis_seek(handle, startFrame)) {
                    null
                } else {
                    val out = FloatArray(frameCount * channels)
                    val got = STBVorbis.stb_vorbis_get_samples_float_interleaved(handle, channels, out)
                    when {
                        got <= 0 -> null
                        got == frameCount -> out
                        else -> out.copyOf(got * channels)
                    }
                }
            }

        private fun <T> withHandle(data: ByteArray, body: (Long) -> T): T? {
            val mem = MemoryUtil.memAlloc(data.size)
            try {
                mem.put(data).flip()
                val handle = MemoryStack.stackPush().use { stack ->
                    STBVorbis.stb_vorbis_open_memory(mem, stack.mallocInt(1), null)
                }
                if (handle == 0L) return null
                try {
                    return body(handle)
                } finally {
                    STBVorbis.stb_vorbis_close(handle)
                }
            } finally {
                MemoryUtil.memFree(mem)
            }
        }
    }
