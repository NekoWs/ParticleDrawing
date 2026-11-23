package work.nekow.particledrawing.animation.recipe

import work.nekow.particledrawing.animation.script.Reg
import work.nekow.particledrawing.api.Color
import work.nekow.particledrawing.api.ParticleRecipe
import work.nekow.particledrawing.api.ParticleRecipeMode
import work.nekow.particledrawing.api.RecipeAnchor
import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.math.Vec3
import work.nekow.particledrawing.i18n.translation
import kotlin.math.abs

/** 兼容性检查和 GPU 弹道所需的初始状态，复用普通配方的出生求值。 */
internal object ParticleRecipeExecution {
    private val accelerations = setOf("ax", "ay", "az")
    private const val MAX_TRAJECTORY_OFFSET = 1_000_000.0

    data class Shot(
        val position: Vec3, val velocity: Vec3, val acceleration: Vec3,
        val color: Color, val scale: Float, val birthTick: Long,
    ) {
        fun positionAt(tick: Long): Vec3 {
            val age = (tick - birthTick).toDouble()
            return position.add(velocity.scale(age)).add(acceleration.scale(age * (age + 1.0) / 2.0))
        }
    }

    fun validate(recipe: ParticleRecipe, anchor: RecipeAnchor, burst: Boolean, mode: ParticleRecipeMode) {
        if (mode == ParticleRecipeMode.STANDARD) return
        if (mode == ParticleRecipeMode.INSTANCED) {
            validateInstances(recipe)
            return
        }
        val unsupportedInput = RecipeExpressions(recipe).externalInputs.any {
            it.needsWorld || it.readsNeighbors || it.ordinal >= RecipeInput.EVENT_X.ordinal
        }
        requireLocalized(burst && anchor.entity == null && anchor.position.add(anchor.offset).lengthSqr().isFinite() &&
            !recipe.follow && recipe.orbit.isBlank() &&
            recipe.emitFromTick == 0 && recipe.emitUntilTick < 0 &&
            recipe.collision == RecipeCollision.NONE && recipe.probes.isEmpty() && recipe.emissions.isEmpty() &&
            recipe.subEmit == null && recipe.state.isEmpty() && recipe.kill.trim() == "false" &&
            recipe.simulationRange == 0.0 && recipe.lifetime > 0 && recipe.maxAlive >= recipe.seeds.size &&
            (recipe.seeds.size - 1L) / recipe.rate * recipe.interval + recipe.lifetime <= 1_000_000 &&
            recipe.visual.lightLevel == 0 && !recipe.visual.emitsLight && recipe.visual.lightShape == null &&
            gpuAppearance(recipe) &&
            recipe.lifeCurve == null && !unsupportedInput &&
            recipe.motion.all { (key, value) -> key in accelerations && value.toDoubleOrNull()?.toFloat()?.isFinite() == true }) {
            translation("error.recipe.gpu_unsupported")
        }
    }

    private fun validateInstances(recipe: ParticleRecipe) {
        val v = recipe.visual
        requireLocalized(!v.sceneMaterial.active && !v.lightOnly &&
            recipe.emissions.none { it.decalRadius > 0.0 } && recipe.subEmit == null) {
            translation("error.recipe.instances_appearance")
        }
        for (rule in recipe.emissions) validateInstances(rule.child)
    }

    private fun gpuAppearance(recipe: ParticleRecipe): Boolean {
        val v = recipe.visual
        return v.billboard && v.additive && v.glowing && !v.lightOnly && !v.alignVelocity &&
            v.softFade == 0.0 && v.trailTicks == 0 && v.model == null && !v.sceneMaterial.active &&
            "glow" !in recipe.birth && "glow" !in recipe.motion
    }

    fun bake(recipe: ParticleRecipe, anchor: RecipeAnchor): List<Shot> {
        validate(recipe, anchor, burst = true, mode = ParticleRecipeMode.GPU)
        val runtime = RecipeRuntime(recipe, burst = true)
        return runtime.bakeBurstBirths(RecipeFrame(anchor.position.add(anchor.offset))).map { particle ->
            val r = particle.regs
            val initial = particle.position
            val velocity = Vec3(r[Reg.VX], r[Reg.VY], r[Reg.VZ])
            val acceleration = Vec3(
                recipe.motion["ax"]?.toDouble() ?: r[RecipeExpressions.AX],
                recipe.motion["ay"]?.toDouble() ?: r[RecipeExpressions.AY],
                recipe.motion["az"]?.toDouble() ?: r[RecipeExpressions.AZ],
            )
            val shot = Shot(initial, velocity, acceleration, Color.of(r[Reg.R].toFloat(), r[Reg.G].toFloat(),
                r[Reg.B].toFloat(), r[Reg.A].toFloat()), r[Reg.SC].toFloat(), particle.index / recipe.rate * recipe.interval)
            val duration = recipe.lifetime.toDouble()
            requireLocalized(
                listOf(initial.x, initial.y, initial.z).zip(
                    listOf(velocity.x to acceleration.x, velocity.y to acceleration.y, velocity.z to acceleration.z),
                ).all { (start, deltas) ->
                    abs(start) + abs(deltas.first) * duration +
                        abs(deltas.second) * duration * (duration + 1.0) / 2.0 <= MAX_TRAJECTORY_OFFSET
                },
            ) { translation("error.recipe.gpu_trajectory") }
            shot
        }
    }
}
