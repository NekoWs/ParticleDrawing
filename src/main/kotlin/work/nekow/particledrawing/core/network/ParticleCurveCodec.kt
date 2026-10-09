package work.nekow.particledrawing.core.network

import net.minecraft.network.FriendlyByteBuf
import work.nekow.particledrawing.api.CurveChannel
import work.nekow.particledrawing.api.CurveKey
import work.nekow.particledrawing.api.ParticleCurve
import work.nekow.particledrawing.api.ParticleLifeCurve
import work.nekow.particledrawing.core.easing.EasingType

// 寿命曲线的协议编解码：整段只在 spawn 包里出现一次，按通道与关键帧紧凑写。
// 缓动走紧凑编码：预设占 1~2 字节，自定义三次贝塞尔写 4 个 double。

private const val EASE_PRESET = 0
private const val EASE_CUSTOM = 1
private const val EASE_STEP = 2

internal fun writeEasingCompact(buf: FriendlyByteBuf, easing: EasingType) {
    when {
        easing.isStep() -> buf.writeByte(EASE_STEP)
        easing.isPreset() -> {
            buf.writeByte(EASE_PRESET)
            buf.writeVarInt(easing.ordinal)
        }
        else -> {
            buf.writeByte(EASE_CUSTOM)
            buf.writeDouble(easing.curve.x1)
            buf.writeDouble(easing.curve.y1)
            buf.writeDouble(easing.curve.x2)
            buf.writeDouble(easing.curve.y2)
        }
    }
}

internal fun readEasingCompact(buf: FriendlyByteBuf): EasingType = when (buf.readByte().toInt()) {
    EASE_STEP -> EasingType.NONE
    EASE_CUSTOM -> EasingType.custom(buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble())
    else -> EasingType.PRESETS.getOrElse(buf.readVarInt()) { EasingType.LINEAR }
}

/**
 * 逐粒子寿命曲线：无曲线时只写一个 0。
 */
internal object ParticleCurveCodec {

    fun write(buf: FriendlyByteBuf, lifeCurve: ParticleLifeCurve?) {
        if (lifeCurve == null || lifeCurve.isEmpty()) {
            buf.writeVarInt(0)
            return
        }
        buf.writeVarInt(lifeCurve.curves.size)
        for (curve in lifeCurve.curves) {
            buf.writeVarInt(curve.channel.ordinal)
            buf.writeVarInt(curve.keys.size)
            for (key in curve.keys) {
                buf.writeFloat(key.tTicks)
                buf.writeFloat(key.value)
                writeEasingCompact(buf, key.easing)
            }
        }
    }

    fun read(buf: FriendlyByteBuf): ParticleLifeCurve? {
        val curveCount = buf.readVarInt()
        if (curveCount <= 0) return null
        require(curveCount <= ParticleLifeCurve.MAX_CURVES) { "寿命曲线条数越界: $curveCount" }

        val curves = ArrayList<ParticleCurve>(curveCount)
        for (i in 0 until curveCount) {
            val channel = CurveChannel.entries.getOrNull(buf.readVarInt())
                ?: throw IllegalArgumentException("未知寿命曲线通道")
            val keyCount = buf.readVarInt()
            require(keyCount in 1..ParticleCurve.MAX_KEYS) { "寿命曲线关键帧数越界: $keyCount" }
            val keys = ArrayList<CurveKey>(keyCount)
            for (j in 0 until keyCount) {
                keys.add(CurveKey(buf.readFloat(), buf.readFloat(), readEasingCompact(buf)))
            }
            curves.add(ParticleCurve(channel, keys))
        }
        return ParticleLifeCurve(curves)
    }
}
