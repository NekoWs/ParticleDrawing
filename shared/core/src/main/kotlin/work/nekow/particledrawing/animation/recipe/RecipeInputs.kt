package work.nekow.particledrawing.animation.recipe

import work.nekow.particledrawing.animation.script.Reg
import work.nekow.particledrawing.math.ResourceId
import work.nekow.particledrawing.math.Vec3

internal data class EventInput(
    val position: Vec3,
    val velocity: Vec3,
    val normal: Vec3 = Vec3.ZERO,
    val material: ResourceId? = null,
    val decalRadius: Double = 0.0,
)

internal enum class RecipeInput(val key: String, val worldField: ParticleWorldField? = null) {
    WORLD_X("world_x"),
    WORLD_Y("world_y"),
    WORLD_Z("world_z"),
    WORLD_LOADED("world_loaded"),
    BLOCK_LIGHT("block_light", ParticleWorldField.BLOCK_LIGHT),
    SKY_LIGHT("sky_light", ParticleWorldField.SKY_LIGHT),
    OPEN_SKY("open_sky", ParticleWorldField.OPEN_SKY),
    IN_SOLID("in_solid", ParticleWorldField.SOLID),
    IN_FLUID("in_fluid", ParticleWorldField.IN_FLUID),
    FLOW_X("flow_x", ParticleWorldField.FLUID_FLOW),
    FLOW_Y("flow_y", ParticleWorldField.FLUID_FLOW),
    FLOW_Z("flow_z", ParticleWorldField.FLUID_FLOW),
    ENTITY_NEAR("entity_near", ParticleWorldField.NEAREST_ENTITY),
    ENTITY_X("entity_dx", ParticleWorldField.NEAREST_ENTITY),
    ENTITY_Y("entity_dy", ParticleWorldField.NEAREST_ENTITY),
    ENTITY_Z("entity_dz", ParticleWorldField.NEAREST_ENTITY),
    FIELD_AX("field_ax", ParticleWorldField.FORCE_FIELD),
    FIELD_AY("field_ay", ParticleWorldField.FORCE_FIELD),
    FIELD_AZ("field_az", ParticleWorldField.FORCE_FIELD),
    NEIGHBOR_COUNT("neighbor_count"),
    NEIGHBOR_X("neighbor_dx"),
    NEIGHBOR_Y("neighbor_dy"),
    NEIGHBOR_Z("neighbor_dz"),
    NEIGHBOR_VX("neighbor_vx"),
    NEIGHBOR_VY("neighbor_vy"),
    NEIGHBOR_VZ("neighbor_vz"),

    SURFACE_NX("sample_nx"),
    SURFACE_NY("sample_ny"),
    SURFACE_NZ("sample_nz"),
    EVENT_X("event_x"),
    EVENT_Y("event_y"),
    EVENT_Z("event_z"),
    EVENT_NX("event_nx"),
    EVENT_NY("event_ny"),
    EVENT_NZ("event_nz"),
    EVENT_VX("event_vx"),
    EVENT_VY("event_vy"),
    EVENT_VZ("event_vz");

    val readsNeighbors: Boolean get() = ordinal in NEIGHBOR_COUNT.ordinal..NEIGHBOR_VZ.ordinal

    val needsWorld: Boolean get() = worldField != null || this == WORLD_LOADED

    fun read(
        position: Vec3, sample: ParticleWorldSample, event: EventInput?, normal: Vec3?, neighbors: ParticleNeighbors.Sample,
    ): Double = when (this) {
        WORLD_X -> position.x
        WORLD_Y -> position.y
        WORLD_Z -> position.z
        WORLD_LOADED -> sample.loaded.numeric()
        BLOCK_LIGHT -> sample.blockLight.toDouble()
        SKY_LIGHT -> sample.skyLight.toDouble()
        OPEN_SKY -> sample.openSky.numeric()
        IN_SOLID -> sample.solid.numeric()
        IN_FLUID -> sample.inFluid.numeric()
        FLOW_X -> sample.fluidFlow.x
        FLOW_Y -> sample.fluidFlow.y
        FLOW_Z -> sample.fluidFlow.z
        ENTITY_NEAR -> (sample.nearestEntity != null).numeric()
        ENTITY_X -> sample.nearestEntity?.x ?: 0.0
        ENTITY_Y -> sample.nearestEntity?.y ?: 0.0
        ENTITY_Z -> sample.nearestEntity?.z ?: 0.0
        FIELD_AX -> sample.fieldAcceleration.x
        FIELD_AY -> sample.fieldAcceleration.y
        FIELD_AZ -> sample.fieldAcceleration.z
        NEIGHBOR_COUNT -> neighbors.count.toDouble()
        NEIGHBOR_X -> neighbors.offset.x
        NEIGHBOR_Y -> neighbors.offset.y
        NEIGHBOR_Z -> neighbors.offset.z
        NEIGHBOR_VX -> neighbors.velocity.x
        NEIGHBOR_VY -> neighbors.velocity.y
        NEIGHBOR_VZ -> neighbors.velocity.z

        SURFACE_NX -> normal?.x ?: 0.0
        SURFACE_NY -> normal?.y ?: 0.0
        SURFACE_NZ -> normal?.z ?: 0.0
        EVENT_X -> event?.position?.x ?: 0.0
        EVENT_Y -> event?.position?.y ?: 0.0
        EVENT_Z -> event?.position?.z ?: 0.0
        EVENT_NX -> event?.normal?.x ?: 0.0
        EVENT_NY -> event?.normal?.y ?: 0.0
        EVENT_NZ -> event?.normal?.z ?: 0.0
        EVENT_VX -> event?.velocity?.x ?: 0.0
        EVENT_VY -> event?.velocity?.y ?: 0.0
        EVENT_VZ -> event?.velocity?.z ?: 0.0
    }

    private fun Boolean.numeric() = if (this) 1.0 else 0.0

    companion object {
        val byKey = entries.associateBy(RecipeInput::key)
    }
}

internal class RecipeInputReader(
    expressions: RecipeExpressions,
    private val world: ParticleWorld?,
    private val entities: ParticleEntityQuery,
    probes: List<ParticleProbe>,
) {
    private val inputs = expressions.externalInputs.map { it to expressions.slots.slot(it.key) }
    private val fields = inputs.mapNotNull { (input, _) -> input.worldField }.toSet()
    private val needsWorld = inputs.any { (input, _) -> input.needsWorld }
    val readsNeighbors = inputs.any { (input, _) -> input.readsNeighbors }
    private val unavailable = ParticleWorldSample()

    /** 单次探测的结果缓冲：逐粒子遍历复用，避免每组每刻新建数组。 */
    private val probeResult = DoubleArray(PROBE_INPUTS)

    /** 每组探测的输入键与槽位；没有声明探测时为空，热路径多一次空列表遍历。 */
    private val probes = probes.mapIndexed { index, spec ->
        val offset = index * PROBE_INPUTS
        ProbeReader(
            spec,
            expressions.probeKeys.subList(offset, offset + PROBE_INPUTS).toTypedArray(),
            IntArray(PROBE_INPUTS) { i -> expressions.slots.slot(expressions.probeKeys[offset + i]) },
        )
    }

    fun prepare(particle: RecipeRuntime.Particle, frame: RecipeFrame?, neighbors: ParticleNeighbors? = null) =
        sample(particle, frame, neighbors) { _, slot, value -> particle.regs[slot] = value }

    fun readInto(
        particle: RecipeRuntime.Particle, frame: RecipeFrame?, neighbors: ParticleNeighbors?, into: MutableMap<String, Double>,
    ) = sample(particle, frame, neighbors) { key, _, value -> into[key] = value }

    private inline fun sample(
        particle: RecipeRuntime.Particle, frame: RecipeFrame?, neighbors: ParticleNeighbors?,
        store: (String, Int, Double) -> Unit,
    ) {
        val position = frame?.position(particle.x, particle.y, particle.z) ?: Vec3.ZERO
        if (inputs.isNotEmpty()) {
            val sample = if (needsWorld && frame != null) world?.sample(position, fields, entities) ?: unavailable else unavailable
            val nearby = if (readsNeighbors && frame != null) neighbors?.at(particle, position) ?: ParticleNeighbors.EMPTY
                else ParticleNeighbors.EMPTY
            for ((input, slot) in inputs) {
                store(input.key, slot, input.read(position, sample, particle.event, particle.seed.normal, nearby))
            }
        }
        if (probes.isNotEmpty()) {
            val velocity = Vec3(particle.regs[Reg.VX], particle.regs[Reg.VY], particle.regs[Reg.VZ])
            for (probe in probes) {
                probe.readInto(world, frame, position, velocity, probeResult)
                for (i in 0 until PROBE_INPUTS) store(probe.keys[i], probe.slots[i], probeResult[i])
            }
        }
    }

    fun copyInputs(particle: RecipeRuntime.Particle, into: MutableMap<String, Double>) {
        for ((input, slot) in inputs) into[input.key] = particle.regs[slot]
    }

    companion object {
        /** 每组探测暴露的输入数量：hit、matches、distance、法线三轴、命中点三轴。 */
        const val PROBE_INPUTS = 9
    }
}

/** 一组探测的结果：命中、材质匹配、距离、法线三轴、命中点三轴。 */
private class ProbeReader(
    private val spec: ParticleProbe,
    val keys: Array<String>,
    val slots: IntArray,
) {
    fun readInto(
        world: ParticleWorld?, frame: RecipeFrame?, position: Vec3, velocity: Vec3, out: DoubleArray,
    ) {
        val reading = spec.read(world, frame, position, velocity)
        val hit = reading.hit
        out[0] = if (hit != null) 1.0 else 0.0
        out[1] = if (reading.matches) 1.0 else 0.0
        out[2] = reading.distance
        out[3] = hit?.normal?.x ?: 0.0
        out[4] = hit?.normal?.y ?: 0.0
        out[5] = hit?.normal?.z ?: 0.0
        out[6] = hit?.position?.x ?: 0.0
        out[7] = hit?.position?.y ?: 0.0
        out[8] = hit?.position?.z ?: 0.0
    }
}