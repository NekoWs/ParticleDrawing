package work.nekow.particledrawing.command.v2

import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.animation.morph.MorphAllocation
import work.nekow.particledrawing.animation.morph.MorphEasing
import work.nekow.particledrawing.animation.morph.MorphOptions
import work.nekow.particledrawing.animation.morph.MorphPath
import work.nekow.particledrawing.animation.morph.MorphSpace
import work.nekow.particledrawing.animation.morph.MorphWobble
import work.nekow.particledrawing.api.Color
import work.nekow.particledrawing.api.ParticleGroup
import work.nekow.particledrawing.core.easing.EasingType
import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.i18n.translation
import java.util.UUID

internal object GroupActions {
    data class MorphConfig(
        var slotCount: Int = 16,
        var position: String = "",
        var yaw: String? = null,
        var pitch: String? = null,
        var size: String? = null,
        var control1: String? = null,
        var control2: String? = null,
        var ticks: Int = 40,
        var stagger: Int = 0,
        var loop: Int = 0,
        var maxSpeed: Double = 0.0,
        var maxAccel: Double = 0.0,
        var converge: Double = 0.05,
        var path: MorphPath = MorphPath.STRAIGHT,
        var easing: String = "linear",
        var wobble: MorphWobble = MorphWobble.NONE,
        var wobbleStrength: Double = 0.0,
        var wobblePeaks: Double = 1.0,
        var allocation: MorphAllocation = MorphAllocation.NEAREST,
        var dynamic: Boolean = false,
        var keepVelocity: Boolean = false,
        var attributes: Boolean? = null,
        var space: MorphSpace = MorphSpace.WORLD,
    ) {
        fun options(): MorphOptions = MorphOptions(
            ticks = ticks,
            staggerTicks = stagger,
            maxSpeed = maxSpeed,
            maxAccel = maxAccel,
            path = path,
            easing = MorphEasing.byName(easing) ?: MorphEasing.LINEAR,
            wobble = wobble,
            wobbleStrength = wobbleStrength,
            wobblePeaks = wobblePeaks,
            convergeEps = converge,
            loop = loop,
            allocation = allocation,
            keepVelocity = keepVelocity,
            writeAttributes = attributes ?: (yaw != null || pitch != null || size != null),
            space = space,
        )
    }

    sealed class Action(val activates: Boolean, val requiresActive: Boolean = false) {
        data class Delay(val ticks: Int) : Action(true)
        data class Move(val delta: Vec3, val ticks: Int, val easing: EasingType) : Action(true)
        data class MoveOffset(val ratio: Float, val ticks: Int, val easing: EasingType) : Action(true)
        data class MovePath(val points: List<Vec3>, val ticks: Int, val easing: EasingType) : Action(true)
        data class Rotate(val axis: Vec3, val degrees: Double, val ticks: Int, val easing: EasingType) : Action(true)
        data class Recolor(val color: Color, val ticks: Int, val easing: EasingType) : Action(true)
        data class ScaleBy(val ratio: Float, val ticks: Int, val easing: EasingType) : Action(true)
        data class ScaleTo(val ratio: Float, val ticks: Int, val easing: EasingType) : Action(true)
        data class FadeIn(val ticks: Int, val easing: EasingType) : Action(true)
        data class FadeOut(val ticks: Int, val keep: Boolean, val easing: EasingType) : Action(true)
        data class Spin(val axis: Vec3, val degreesPerTick: Double) : Action(true)
        data class Pulse(val peak: Float, val halfPeriod: Int, val cycles: Int) : Action(true)
        data class Pivot(val position: Vec3) : Action(true)
        data class Follow(val entity: UUID, val offset: Vec3, val local: Boolean) : Action(true)
        data class AnchorEntity(val entity: UUID, val offset: Vec3, val local: Boolean) : Action(true)
        data class VariableSet(val name: String, val value: Double) : Action(false)
        data class VariableLive(val name: String, val formula: String) : Action(true, requiresActive = true)
        data class VariableEase(val name: String, val value: String, val ticks: Int, val easing: EasingType) : Action(true, requiresActive = true)
        data class Expression(val ticks: Int, val code: String, val local: Boolean) : Action(true)
        object StopContinuous : Action(true, requiresActive = true)
        data class DestroyAfter(val ticks: Int) : Action(true)
        data class Retire(val grace: Int) : Action(true, requiresActive = true)
        data class Entity(val handle: String, val entity: UUID) : Action(false)
        object MorphStart : Action(true)
    }

    private val FORMULA_NAME = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
    private val RESERVED_NAMES = setOf(
        "i", "n", "t", "PI", "E", "x", "y", "z", "r", "g", "b", "a", "vx", "vy", "vz", "sc", "glow", "light",
    )

    internal fun requireName(name: String): String {
        requireLocalized(FORMULA_NAME.matches(name)) { translation("error.command.identifier", "name", name) }
        requireLocalized(name !in RESERVED_NAMES) { translation("error.command.identifier_reserved", name) }
        return name
    }

    internal fun parsePath(raw: String): List<Vec3> = raw.split(';').mapIndexed { index, point ->
        val parts = point.split(',')
        requireLocalized(parts.size == 3) { translation("error.command.path_point", index + 1) }
        val values = parts.map { it.trim().toDoubleOrNull()?.takeIf(Double::isFinite) }
        requireLocalized(values.all { it != null }) { translation("error.command.path_number", index + 1) }
        Vec3(values[0]!!, values[1]!!, values[2]!!)
    }

    internal fun applyAction(group: ParticleGroup, action: Action, config: MorphConfig?) {
        when (action) {
            is Action.Delay -> group.delay(action.ticks)
            is Action.Move -> group.move(action.delta, action.ticks, action.easing)
            is Action.MoveOffset -> group.moveAlongOffset(action.ratio, action.ticks, action.easing)
            is Action.MovePath -> group.movePath(action.points, action.ticks, action.easing)
            is Action.Rotate -> group.rotate(action.axis, Math.toRadians(action.degrees), action.ticks, action.easing)
            is Action.Recolor -> group.recolor(action.color, action.ticks, action.easing)
            is Action.ScaleBy -> group.scaleBy(action.ratio, action.ticks, action.easing)
            is Action.ScaleTo -> group.scaleTo(action.ratio, action.ticks, action.easing)
            is Action.FadeIn -> group.fadeIn(action.ticks, action.easing)
            is Action.FadeOut -> group.fadeOut(action.ticks, removeAfter = !action.keep, easing = action.easing)
            is Action.Spin -> group.spin(action.axis, Math.toRadians(action.degreesPerTick))
            is Action.Pulse -> group.pulse(action.peak, action.halfPeriod, action.cycles)
            is Action.Pivot -> group.setPivot(action.position)
            is Action.Follow -> group.followEntity(action.entity, action.offset, action.local)
            is Action.AnchorEntity -> group.followEntity(action.entity, action.offset, action.local)
            is Action.VariableSet -> group.setVariable(action.name, action.value)
            is Action.VariableLive -> group.setVariableLive(action.name, action.formula)
            is Action.VariableEase -> group.setVariableInterpolated(action.name, action.value, action.ticks, action.easing)
            is Action.Expression -> if (action.local) group.expressionLocal(action.code, action.ticks) else group.expression(action.code, action.ticks)
            is Action.StopContinuous -> group.stopContinuous()
            is Action.DestroyAfter -> group.destroyAfter(action.ticks)
            is Action.Retire -> group.retire(action.grace)
            is Action.Entity -> group.defineEntity(action.handle, action.entity)
            is Action.MorphStart -> {
                val morph = checkNotNull(config)
                group.morph(
                    morph.slotCount, morph.position, morph.options(),
                    morph.yaw, morph.pitch, morph.size, morph.control1, morph.control2, morph.dynamic,
                )
            }
        }
    }
}
