package work.nekow.particledrawing.core.network

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import work.nekow.particledrawing.animation.recipe.ParticleEntityQuery
import work.nekow.particledrawing.animation.recipe.ParticleProbe
import work.nekow.particledrawing.animation.recipe.RecipeCollision
import work.nekow.particledrawing.api.*
import work.nekow.particledrawing.math.ResourceId
import work.nekow.particledrawing.platform.toCore
import work.nekow.particledrawing.platform.toMinecraft
import java.util.UUID
import work.nekow.particledrawing.i18n.LocalizedArgumentException
import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.i18n.translation

/** 命令生成器的声明、锚点更新和现存成员操作。操作码只追加。 */
enum class RecipeOperation { START, UPDATE, ANCHOR, STOP, REMOVE, EDIT, VARIABLE }

data class ParticleRecipePayload(
    val id: UUID,
    val operation: RecipeOperation,
    val recipe: ParticleRecipe? = null,
    val anchor: RecipeAnchor? = null,
    val burst: Boolean = false,
    val predicate: String = "true",
    val changes: Map<String, String> = emptyMap(),
    val deleteSelected: Boolean = false,
    val variables: Map<String, Double> = emptyMap(),
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    companion object {
        @JvmField val TYPE = CustomPacketPayload.Type<ParticleRecipePayload>(Identifier.fromNamespaceAndPath("particledrawing", "particle_recipe"))
        @JvmField val STREAM_CODEC: StreamCodec<FriendlyByteBuf, ParticleRecipePayload> = object : StreamCodec<FriendlyByteBuf, ParticleRecipePayload> {
            override fun encode(buf: FriendlyByteBuf, p: ParticleRecipePayload) {
                buf.writeUUID(p.id); buf.writeVarInt(p.operation.ordinal)
                when (p.operation) {
                    RecipeOperation.START -> { RecipeCodec.write(buf, requireNotNull(p.recipe)); RecipeCodec.writeAnchor(buf, requireNotNull(p.anchor)); buf.writeBoolean(p.burst) }
                    RecipeOperation.UPDATE -> RecipeCodec.write(buf, requireNotNull(p.recipe))
                    RecipeOperation.ANCHOR -> RecipeCodec.writeAnchor(buf, requireNotNull(p.anchor))
                    RecipeOperation.EDIT -> { buf.writeUtf(p.predicate, ParticleRecipe.MAX_EXPRESSION); RecipeCodec.writeStrings(buf, p.changes); buf.writeBoolean(p.deleteSelected) }
                    RecipeOperation.VARIABLE -> RecipeCodec.writeNumbers(buf, p.variables)
                    RecipeOperation.STOP, RecipeOperation.REMOVE -> Unit
                }
            }

            override fun decode(buf: FriendlyByteBuf): ParticleRecipePayload {
                val id = buf.readUUID()
                val operation = RecipeOperation.entries.getOrNull(buf.readVarInt()) ?: throw LocalizedArgumentException(translation("error.packet.recipe_operation"))
                return when (operation) {
                    RecipeOperation.START -> ParticleRecipePayload(id, operation, RecipeCodec.read(buf), RecipeCodec.readAnchor(buf), buf.readBoolean())
                    RecipeOperation.UPDATE -> ParticleRecipePayload(id, operation, recipe = RecipeCodec.read(buf))
                    RecipeOperation.ANCHOR -> ParticleRecipePayload(id, operation, anchor = RecipeCodec.readAnchor(buf))
                    RecipeOperation.EDIT -> ParticleRecipePayload(id, operation, predicate = buf.readUtf(ParticleRecipe.MAX_EXPRESSION), changes = RecipeCodec.readStrings(buf), deleteSelected = buf.readBoolean())
                    RecipeOperation.VARIABLE -> ParticleRecipePayload(id, operation, variables = RecipeCodec.readNumbers(buf))
                    RecipeOperation.STOP, RecipeOperation.REMOVE -> ParticleRecipePayload(id, operation)
                }
            }
        }
    }
}

internal object RecipeCodec {
    fun write(buf: FriendlyByteBuf, r: ParticleRecipe) = writeLevel(buf, r, allowChildren = true)

    private fun writeLevel(buf: FriendlyByteBuf, r: ParticleRecipe, allowChildren: Boolean) {
        buf.writeVarInt(r.seeds.size)
        for (seed in r.seeds) {
            StreamCodecs.writeVec(buf, seed.position)
            buf.writeFloat(seed.color.r); buf.writeFloat(seed.color.g); buf.writeFloat(seed.color.b); buf.writeFloat(seed.color.a)
            buf.writeDouble(seed.u)
            buf.writeBoolean(seed.normal != null)
            seed.normal?.let { StreamCodecs.writeVec(buf, it) }
            buf.writeBoolean(seed.meshUv != null)
            seed.meshUv?.let { uv -> buf.writeDouble(uv.u); buf.writeDouble(uv.v) }
        }
        buf.writeVarInt(r.rate); buf.writeVarInt(r.interval); buf.writeVarInt(r.lifetime); buf.writeVarInt(r.maxAlive)
        buf.writeBoolean(r.follow)
        writeNumbers(buf, r.variables); writeNumbers(buf, r.state)
        writeStrings(buf, r.lets); writeStrings(buf, r.birth); writeStrings(buf, r.motion)
        buf.writeUtf(r.where, ParticleRecipe.MAX_EXPRESSION); buf.writeUtf(r.kill, ParticleRecipe.MAX_EXPRESSION)
        ParticleVisualCodec.write(buf, r.visual); ParticleCurveCodec.write(buf, r.lifeCurve)
        buf.writeUtf(r.orbit, ParticleRecipe.MAX_EXPRESSION)
        buf.writeVarInt(r.collision.ordinal)
        buf.writeDouble(r.bounce); buf.writeDouble(r.friction)
        buf.writeBoolean(r.subEmit != null)
        r.subEmit?.let {
            buf.writeVarInt(it.count); buf.writeVarInt(it.lifetime)
            buf.writeDouble(it.speed); buf.writeDouble(it.scale)
        }
        buf.writeDouble(r.spacing)
        buf.writeDouble(r.collisionHalfSize)
        buf.writeVarInt(r.collisionSteps)
        buf.writeDouble(r.entityQuery.radius)
        buf.writeBoolean(r.entityQuery.type != null)
        r.entityQuery.type?.let { buf.writeUtf(it.toString(), MAX_MATERIAL_LENGTH) }
        buf.writeDouble(r.neighborRadius)
        buf.writeDouble(r.simulationRange)
        buf.writeVarInt(r.probes.size)
        for (probe in r.probes) {
            buf.writeDouble(probe.range)
            buf.writeVarInt(probe.mode.ordinal)
            buf.writeBoolean(probe.direction != null)
            probe.direction?.let { StreamCodecs.writeVec(buf, it.toMinecraft()) }
            buf.writeBoolean(probe.material != null)
            probe.material?.let { buf.writeUtf(it.toString(), MAX_MATERIAL_LENGTH) }
            StreamCodecs.writeVec(buf, probe.origin.toMinecraft())
            buf.writeDouble(probe.halfSize)
        }
        requireLocalized(allowChildren || r.emissions.isEmpty()) { translation("error.packet.recipe_size") }
        buf.writeVarInt(r.emissions.size)
        for (rule in r.emissions) {
            buf.writeVarInt(rule.event.ordinal)
            buf.writeBoolean(rule.material != null)
            rule.material?.let { buf.writeUtf(it.toString(), MAX_MATERIAL_LENGTH) }
            buf.writeBoolean(rule.once)
            buf.writeBoolean(rule.removeParent)
            buf.writeDouble(rule.decalRadius)
            buf.writeBoolean(rule.wrapDecal)
            writeLevel(buf, rule.child, allowChildren = false)
        }
    }

    fun read(buf: FriendlyByteBuf): ParticleRecipe = readLevel(buf, allowChildren = true)

    private fun readLevel(buf: FriendlyByteBuf, allowChildren: Boolean): ParticleRecipe {
        val seeds = List(count(buf, if (allowChildren) ParticleRecipe.MAX_SEEDS else RecipeEmission.MAX_CHILD_SEEDS)) {
            ParticleSeed(StreamCodecs.readVec(buf).toCore(), Color.of(buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat()), buf.readDouble(),
                if (buf.readBoolean()) StreamCodecs.readVec(buf).toCore() else null,
                if (buf.readBoolean()) ParticleMeshUv(buf.readDouble(), buf.readDouble()) else null)
        }
        val recipe = ParticleRecipe(seeds, buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean(),
            readNumbers(buf), readNumbers(buf), readStrings(buf), readStrings(buf), readStrings(buf),
            buf.readUtf(ParticleRecipe.MAX_EXPRESSION), buf.readUtf(ParticleRecipe.MAX_EXPRESSION),
            ParticleVisualCodec.read(buf) ?: ParticleVisual(), ParticleCurveCodec.read(buf),
            buf.readUtf(ParticleRecipe.MAX_EXPRESSION))
        val modeOrdinal = buf.readVarInt()
        requireLocalized(modeOrdinal in RecipeCollision.entries.indices) { translation("error.packet.recipe_size") }
        val bounce = buf.readDouble()
        val friction = buf.readDouble()
        val subEmit = if (buf.readBoolean()) {
            SubEmit(buf.readVarInt(), buf.readVarInt(), buf.readDouble(), buf.readDouble())
        } else {
            null
        }
        val spacing = buf.readDouble()
        val collisionHalfSize = buf.readDouble()
        val collisionSteps = buf.readVarInt()
        val entityRange = buf.readDouble()
        val entityType = if (buf.readBoolean()) ResourceId.parse(buf.readUtf(MAX_MATERIAL_LENGTH)) else null
        val neighborRadius = buf.readDouble()
        val simulationRange = buf.readDouble()
        val probes = List(count(buf, ParticleRecipe.MAX_PROBES)) {
            val range = buf.readDouble()
            val mode = RecipeCollision.entries[count(buf, RecipeCollision.entries.lastIndex)]
            val direction = if (buf.readBoolean()) StreamCodecs.readVec(buf).toCore() else null
            val material = if (buf.readBoolean()) ResourceId.parse(buf.readUtf(MAX_MATERIAL_LENGTH)) else null
            val origin = StreamCodecs.readVec(buf).toCore()
            val halfSize = buf.readDouble()
            ParticleProbe(range, mode, direction, material, origin, halfSize)
        }
        val maxRules = if (allowChildren) RecipeEmission.MAX_RULES else 0
        val emissions = List(count(buf, maxRules)) {
            val event = ParticleEvent.entries[count(buf, ParticleEvent.entries.lastIndex)]
            val material = if (buf.readBoolean()) ResourceId.parse(buf.readUtf(MAX_MATERIAL_LENGTH)) else null
            val once = buf.readBoolean()
            val removeParent = buf.readBoolean()
            val decalRadius = buf.readDouble()
            val wrapDecal = buf.readBoolean()
            RecipeEmission(event, readLevel(buf, allowChildren = false), material, once, removeParent, decalRadius, wrapDecal)
        }
        return recipe.copy(
            collision = RecipeCollision.entries[modeOrdinal],
            bounce = bounce,
            friction = friction,
            subEmit = subEmit,
            emissions = emissions,
            spacing = spacing,
            collisionHalfSize = collisionHalfSize,
            collisionSteps = collisionSteps,
            entityQuery = ParticleEntityQuery(entityRange, entityType),
            neighborRadius = neighborRadius,
            simulationRange = simulationRange,
            probes = probes,
        )
    }

    fun writeAnchor(buf: FriendlyByteBuf, a: RecipeAnchor) {
        StreamCodecs.writeVec(buf, a.position); buf.writeBoolean(a.entity != null)
        a.entity?.let(buf::writeUUID); buf.writeVarInt(a.entityId)
        StreamCodecs.writeVec(buf, a.offset); buf.writeBoolean(a.local)
    }

    fun readAnchor(buf: FriendlyByteBuf) = RecipeAnchor(StreamCodecs.readVec(buf), if (buf.readBoolean()) buf.readUUID() else null,
        buf.readVarInt(), StreamCodecs.readVec(buf), buf.readBoolean())

    fun writeStrings(buf: FriendlyByteBuf, values: Map<String, String>) {
        buf.writeVarInt(values.size)
        values.forEach { (k, v) -> buf.writeUtf(k, 48); buf.writeUtf(v, ParticleRecipe.MAX_EXPRESSION) }
    }

    fun readStrings(buf: FriendlyByteBuf): Map<String, String> = linkedMapOf<String, String>().apply {
        repeat(count(buf, ParticleRecipe.MAX_FIELDS)) { val k = buf.readUtf(48); require(k !in this); put(k, buf.readUtf(ParticleRecipe.MAX_EXPRESSION)) }
    }

    fun writeNumbers(buf: FriendlyByteBuf, values: Map<String, Double>) {
        buf.writeVarInt(values.size); values.forEach { (k, v) -> buf.writeUtf(k, 48); buf.writeDouble(v) }
    }

    fun readNumbers(buf: FriendlyByteBuf): Map<String, Double> = linkedMapOf<String, Double>().apply {
        repeat(count(buf, ParticleRecipe.MAX_FIELDS)) { val k = buf.readUtf(48); require(k !in this); put(k, buf.readDouble()) }
    }

    private const val MAX_MATERIAL_LENGTH = 256

    private fun count(buf: FriendlyByteBuf, max: Int) = buf.readVarInt().also { requireLocalized(it in 0..max) { translation("error.packet.recipe_size") } }
}
