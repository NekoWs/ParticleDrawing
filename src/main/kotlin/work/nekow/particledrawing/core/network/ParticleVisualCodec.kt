package work.nekow.particledrawing.core.network

import net.minecraft.network.FriendlyByteBuf
import work.nekow.particledrawing.api.ParticleVisual
import work.nekow.particledrawing.core.TextureRegistry

/**
 * 逐粒子外观的协议编解码：只写非默认字段，全默认时只占 1 字节。
 *
 * 全默认外观与「没有外观」编码相同，解码后统一为 null，两者语义一致。
 * 贴图按 id 引用（登记时分配，见 [TextureRegistry]），服务端未登记该名字时退回内联名字。
 * 外观在生成时定死，没有对应的更新载荷。
 */
internal object ParticleVisualCodec {

    private const val FLAG_TEXTURE = 1 shl 0
    private const val FLAG_UV_RECT = 1 shl 1
    private const val FLAG_ANISO = 1 shl 2
    private const val FLAG_WORLD_UNITS = 1 shl 3
    private const val FLAG_FIXED_ORIENT = 1 shl 4
    private const val FLAG_SPIN = 1 shl 5
    private const val FLAG_SPIN_WORLD = 1 shl 6
    private const val FLAG_ADDITIVE = 1 shl 7
    private const val FLAG_INLINE_NAME = 1 shl 8

    fun write(buf: FriendlyByteBuf, visual: ParticleVisual?) {
        if (visual == null) {
            buf.writeVarInt(0)
            return
        }

        val name = visual.texture
        val entry = if (name != null) TextureRegistry.byName(name) else null
        val spin = visual.spinXDeg != 0.0 || visual.spinYDeg != 0.0 || visual.spinZDeg != 0.0

        var flags = 0
        if (name != null) {
            flags = flags or FLAG_TEXTURE
            if (entry == null) flags = flags or FLAG_INLINE_NAME
        }
        if (visual.uvRect != null) flags = flags or FLAG_UV_RECT
        if (visual.hasAniso()) {
            flags = flags or FLAG_ANISO
            if (visual.worldUnits) flags = flags or FLAG_WORLD_UNITS
        }
        if (!visual.billboard) flags = flags or FLAG_FIXED_ORIENT
        if (spin) {
            flags = flags or FLAG_SPIN
            if (!visual.spinLocal) flags = flags or FLAG_SPIN_WORLD
        }
        if (visual.additive) flags = flags or FLAG_ADDITIVE

        buf.writeVarInt(flags)
        if (flags == 0) return

        if (name != null) {
            if (entry != null) buf.writeVarInt(entry.id) else buf.writeUtf(name)
        }
        visual.uvRect?.let {
            buf.writeFloat(it[0]); buf.writeFloat(it[1]); buf.writeFloat(it[2]); buf.writeFloat(it[3])
        }
        if (visual.hasAniso()) {
            buf.writeFloat(visual.scaleW)
            buf.writeFloat(visual.scaleH)
        }
        if (spin) {
            buf.writeFloat(visual.spinXDeg.toFloat())
            buf.writeFloat(visual.spinYDeg.toFloat())
            buf.writeFloat(visual.spinZDeg.toFloat())
        }
    }

    fun read(buf: FriendlyByteBuf): ParticleVisual? {
        val flags = buf.readVarInt()
        if (flags == 0) return null

        val visual = ParticleVisual()
        if (flags and FLAG_TEXTURE != 0) {
            visual.texture = if (flags and FLAG_INLINE_NAME != 0) {
                buf.readUtf()
            } else {
                // 未知 id：贴图尚未下发到本机，回落纯白方块但不丢粒子
                TextureRegistry.nameOf(buf.readVarInt())
            }
        }
        if (flags and FLAG_UV_RECT != 0) {
            visual.uvRect = floatArrayOf(buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat())
        }
        if (flags and FLAG_ANISO != 0) {
            visual.scaleW = buf.readFloat()
            visual.scaleH = buf.readFloat()
            visual.worldUnits = flags and FLAG_WORLD_UNITS != 0
        }
        visual.billboard = flags and FLAG_FIXED_ORIENT == 0
        if (flags and FLAG_SPIN != 0) {
            visual.spinXDeg = buf.readFloat().toDouble()
            visual.spinYDeg = buf.readFloat().toDouble()
            visual.spinZDeg = buf.readFloat().toDouble()
            visual.spinLocal = flags and FLAG_SPIN_WORLD == 0
        }
        visual.additive = flags and FLAG_ADDITIVE != 0
        return visual
    }
}
