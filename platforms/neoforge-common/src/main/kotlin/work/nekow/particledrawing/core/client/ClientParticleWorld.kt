package work.nekow.particledrawing.core.client

import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.level.LightLayer
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import work.nekow.particledrawing.animation.recipe.ParticleWorldField
import work.nekow.particledrawing.animation.recipe.ParticleWorldField.*
import work.nekow.particledrawing.animation.recipe.ParticleWorldSample
import work.nekow.particledrawing.animation.recipe.ParticleWorld
import work.nekow.particledrawing.animation.recipe.RecipeCollision
import work.nekow.particledrawing.animation.recipe.SurfaceHit
import work.nekow.particledrawing.math.ResourceId
import work.nekow.particledrawing.math.Vec3
import work.nekow.particledrawing.platform.toCore
import work.nekow.particledrawing.platform.toMinecraft

/** 两种查询都使用实际形状，台阶上方的空半格仍然是空气。 */
internal object ClientParticleWorld : ParticleWorld {
    override fun cast(mode: RecipeCollision, from: Vec3, to: Vec3): SurfaceHit? =
        cast(mode, from, to, includeMaterial = false)

    override fun cast(mode: RecipeCollision, from: Vec3, to: Vec3, includeMaterial: Boolean): SurfaceHit? {
        if (mode == RecipeCollision.NONE) return null
        val level = Minecraft.getInstance().level ?: return null
        fun traceMode(solid: Boolean): SurfaceHit? {
            val hit = traceShape(from, to) { pos ->
                when {
                    !level.chunkSource.hasChunk(pos.x shr 4, pos.z shr 4) -> Shapes.empty()
                    solid -> level.getBlockState(pos).getCollisionShape(level, pos)
                    else -> level.getFluidState(pos).getShape(level, pos)
                }
            } ?: return null
            val material = if (includeMaterial) {
                val id = if (solid) BuiltInRegistries.BLOCK.getKey(level.getBlockState(hit.blockPos).block)
                else BuiltInRegistries.FLUID.getKey(level.getFluidState(hit.blockPos).type)
                ResourceId(id.namespace, id.path)
            } else null
            return surface(hit, material)
        }
        return when (mode) {
            RecipeCollision.NONE -> null
            RecipeCollision.SOLID -> traceMode(true)
            RecipeCollision.LIQUID -> traceMode(false)
            RecipeCollision.SOLID_AND_LIQUID -> {
                val solid = traceMode(true)
                val liquid = traceMode(false)
                when {
                    solid == null -> liquid
                    liquid == null -> solid
                    solid.inside != liquid.inside -> if (solid.inside) liquid else solid
                    from.distanceToSqr(solid.position) <= from.distanceToSqr(liquid.position) -> solid
                    else -> liquid
                }
            }
        }
    }

    override fun sample(position: Vec3, fields: Set<ParticleWorldField>): ParticleWorldSample {
        val level = Minecraft.getInstance().level ?: return unavailable
        val point = position.toMinecraft()
        val pos = BlockPos.containing(point)
        if (!level.chunkSource.hasChunk(pos.x shr 4, pos.z shr 4)) return unavailable
        val fluid = if (IN_FLUID in fields || FLUID_FLOW in fields) level.getFluidState(pos) else null
        val wet = fluid != null && !fluid.isEmpty && point.y - pos.y < fluid.getHeight(level, pos)
        val solid = if (SOLID in fields) level.getBlockState(pos).getCollisionShape(level, pos).toAabbs().any { box ->
            box.contains(point.x - pos.x, point.y - pos.y, point.z - pos.z)
        } else false
        val nearest = if (NEAREST_ENTITY in fields) {
            val box = AABB.ofSize(point, ENTITY_RANGE * 2.0, ENTITY_RANGE * 2.0, ENTITY_RANGE * 2.0)
            level.getEntities(null, box) { it.isAlive && !it.isSpectator }
                .minWithOrNull(compareBy({ it.position().distanceToSqr(point) }, { it.id }))
                ?.position()?.takeIf { it.distanceToSqr(point) <= ENTITY_RANGE * ENTITY_RANGE }?.subtract(point)?.toCore()
        } else null
        return ParticleWorldSample(
            loaded = true,
            blockLight = if (BLOCK_LIGHT in fields) level.getBrightness(LightLayer.BLOCK, pos) else 0,
            skyLight = if (SKY_LIGHT in fields) level.getBrightness(LightLayer.SKY, pos) else 0,
            openSky = OPEN_SKY in fields && level.canSeeSky(pos),
            solid = solid,
            inFluid = wet,
            fluidFlow = if (wet && FLUID_FLOW in fields) requireNotNull(fluid).getFlow(level, pos).toCore() else Vec3.ZERO,
            nearestEntity = nearest,
        )
    }

    internal fun trace(from: Vec3, to: Vec3, shapeAt: (BlockPos) -> VoxelShape): SurfaceHit? =
        traceShape(from, to, shapeAt)?.let { surface(it, null) }

    private fun traceShape(from: Vec3, to: Vec3, shapeAt: (BlockPos) -> VoxelShape): BlockHitResult? {
        if (from == to) return null
        val start = from.toMinecraft()
        val end = to.toMinecraft()
        // 原版裁切排除端点。把查询线段两端略微延长，让恰好触面的粒子也能反弹。
        val padding = end.subtract(start).normalize().scale(1e-5)
        val before = start.subtract(padding)
        val after = end.add(padding)
        val hit = BlockGetter.traverseBlocks<BlockHitResult, (BlockPos) -> VoxelShape>(before, after, shapeAt, { query, pos ->
            val shape = query(pos)
            if (shape.isEmpty) return@traverseBlocks null
            val boxes = shape.toAabbs()
            val x = start.x - pos.x
            val y = start.y - pos.y
            val z = start.z - pos.z
            val inside = boxes.any { x > it.minX && x < it.maxX && y > it.minY && y < it.maxY && z > it.minZ && z < it.maxZ }
            if (inside) BlockHitResult(start, Direction.UP, pos, true) else AABB.clip(boxes, before, after, pos)
        }, { BlockHitResult.miss(end, Direction.UP, BlockPos.containing(end)) })
        return hit.takeUnless { it.type == HitResult.Type.MISS }
    }

    private fun surface(hit: BlockHitResult, material: ResourceId?): SurfaceHit {
        val normal = hit.direction
        return SurfaceHit(hit.location.toCore(), Vec3(normal.stepX.toDouble(), normal.stepY.toDouble(), normal.stepZ.toDouble()), hit.isInside, material)
    }

    private val unavailable = ParticleWorldSample()
    private const val ENTITY_RANGE = 8.0
}
