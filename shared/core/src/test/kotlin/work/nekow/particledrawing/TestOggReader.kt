package work.nekow.particledrawing

import work.nekow.particledrawing.animation.script.ScriptWavePcm

/** 只在核心单元测试中注册，通过与宿主相同的服务入口提供波形。 */
class TestOggReader : ScriptWavePcm.OggReader {
    override fun info(data: ByteArray): ScriptWavePcm.OggInfo? = active.get()?.info(data)

    override fun read(data: ByteArray, startFrame: Int, frameCount: Int, channels: Int): FloatArray? =
        active.get()?.read(data, startFrame, frameCount, channels)

    companion object {
        private val active = ThreadLocal<ScriptWavePcm.OggReader>()

        fun <T> withReader(reader: ScriptWavePcm.OggReader, body: () -> T): T {
            val previous = active.get()
            active.set(reader)
            try {
                return body()
            } finally {
                if (previous == null) active.remove() else active.set(previous)
            }
        }
    }
}
