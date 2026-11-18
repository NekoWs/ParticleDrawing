package work.nekow.particledrawing.core.client

import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
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
import work.nekow.particledrawing.animation.recipe.BoxSweep
import work.nekow.particledrawing.animation.recipe.ParticleEntityQuery
import work.nekow.particledrawing.animation.recipe.ParticleSweep
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
        cast(ParticleSweep(mode), from, to)

    override fun cast(sweep: ParticleSweep, from: Vec3, to: Vec3): SurfaceHit? {
        val mode = sweep.mode
        if (mode == RecipeCollision.NONE || from == to) return null
        val level = Minecraft.getInstance().level ?: return null
        fun traceMode(solid: Boolean): SurfaceHit? {
            val shapeAt: (BlockPos) -> VoxelShape = { pos ->
                when {
                    !level.chunkSource.hasChunk(pos.x shr 4, pos.z shr 4) -> Shapes.empty()
                    solid -> level.getBlockState(pos).getCollisionShape(level, pos)
                    else -> level.getFluidState(pos).getShape(level, pos)
                }
            }
            val result = if (sweep.halfSize > 0.0) traceExpanded(from, to, sweep.halfSize, shapeAt)
            else traceShape(from, to, shapeAt)?.let { it.blockPos to surface(it, null) }
            val (pos, hit) = result ?: return null
            if (!sweep.includeMaterial) return hit
            val id = if (solid) BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).block)
            else BuiltInRegistries.FLUID.getKey(level.getFluidState(pos).type)
            return hit.copy(material = ResourceId(id.namespace, id.path))
        }
        return when (mode) {
            RecipeCollision.NONE -> null
            RecipeCollision.SOLID -> traceMode(true)
            RecipeCollision.LIQUID -> traceMode(false)
            RecipeCollision.SOLID_AND_LIQUID -> BoxSweep.nearer(from, traceMode(true), traceMode(false))
            RecipeCollision.ENTITY -> traceEntities(level, sweep, from, to)
            RecipeCollision.ALL -> BoxSweep.nearer(from,
                BoxSweep.nearer(from, traceMode(true), traceMode(false)), traceEntities(level, sweep, from, to))

        }
    }

    private fun traceEntities(level: ClientLevel, sweep: ParticleSweep, from: Vec3, to: Vec3): SurfaceHit? {
        val bounds = AABB(from.toMinecraft(), to.toMinecraft()).inflate(sweep.halfSize + 1e-5)
        var best: SurfaceHit? = null
        for (entity in level.getEntities(null, bounds) { it.isAlive && !it.isSpectator && it.isPickable }) {
            val box = entity.boundingBox
            val hit = sweepBox(from, to, box, sweep.halfSize) ?: continue
            if (BoxSweep.nearer(from, best, hit) !== hit) continue
            best = if (sweep.includeMaterial) {
                val key = BuiltInRegistries.ENTITY_TYPE.getKey(entity.type)
                hit.copy(material = ResourceId(key.namespace, key.path))
            } else hit
        }
        return best
    }

    internal fun traceExpanded(
        from: Vec3, to: Vec3, halfSize: Double, shapeAt: (BlockPos) -> VoxelShape,
    ): Pair<BlockPos, SurfaceHit>? {
        var best: SurfaceHit? = null
        var bestPos: BlockPos? = null
        val visited = hashSetOf<BlockPos>()
        val reach = kotlin.math.ceil(halfSize).toInt() + 1
        BlockGetter.traverseBlocks<Boolean, (BlockPos) -> VoxelShape>(from.toMinecraft(), to.toMinecraft(), shapeAt, { query, pos ->
            for (y in -reach..reach) for (z in -reach..reach) for (x in -reach..reach) {
                val candidate = pos.offset(x, y, z)
                if (!visited.add(candidate)) continue
                for (box in query(candidate).toAabbs()) {
                    val hit = sweepBox(from, to, box.move(candidate.x.toDouble(), candidate.y.toDouble(), candidate.z.toDouble()), halfSize)
                        ?: continue
                    if (BoxSweep.nearer(from, best, hit) === hit) {
                        best = hit
                        bestPos = candidate
                    }
                }
            }
            null
        }, { false })
        val hit = best ?: return null
        return requireNotNull(bestPos) to hit
    }

    private fun sweepBox(from: Vec3, to: Vec3, box: AABB, halfSize: Double): SurfaceHit? =
        BoxSweep.first(from, to, Vec3(box.minX, box.minY, box.minZ), Vec3(box.maxX, box.maxY, box.maxZ), halfSize)

    override fun sample(position: Vec3, fields: Set<ParticleWorldField>): ParticleWorldSample =
        sample(position, fields, defaultQuery)

    override fun sample(position: Vec3, fields: Set<ParticleWorldField>, query: ParticleEntityQuery): ParticleWorldSample {
        val level = Minecraft.getInstance().level ?: return unavailable
        val point = position.toMinecraft()
        val pos = BlockPos.containing(point)
        if (!level.chunkSource.hasChunk(pos.x shr 4, pos.z shr 4)) return unavailable
        val fluid = if (IN_FLUID in fields || FLUID_FLOW in fields) level.getFluidState(pos) else null
        val wet = fluid != null && !fluid.isEmpty && point.y - pos.y < fluid.getHeight(level, pos)
        val solid = if (SOLID in fields) level.getBlockState(pos).getCollisionShape(level, pos).toAabbs().any { box ->
            box.contains(point.x - pos.x, point.y - pos.y, point.z - pos.z)
        } else false
        val nearest = if (NEAREST_ENTITY in fields && query.radius > 0.0) nearestEntity(level, point, query) else null
        return ParticleWorldSample(
            loaded = true,
            blockLight = if (BLOCK_LIGHT in fields) level.getBrightness(LightLayer.BLOCK, pos) else 0,
            skyLight = if (SKY_LIGHT in fields) level.getBrightness(LightLayer.SKY, pos) else 0,
            openSky = OPEN_SKY in fields && level.canSeeSky(pos),
            solid = solid,
            inFluid = wet,
            fluidFlow = if (wet && FLUID_FLOW in fields) requireNotNull(fluid).getFlow(level, pos).toCore() else Vec3.ZERO,
            nearestEntity = nearest,
            fieldAcceleration = if (FORCE_FIELD in fields) ClientParticleFields.accelerationAt(position, level.gameTime) else Vec3.ZERO,
        )
    }

    private fun nearestEntity(level: ClientLevel, point: net.minecraft.world.phys.Vec3, query: ParticleEntityQuery): Vec3? {
        val diameter = query.radius * 2.0
        val box = AABB.ofSize(point, diameter, diameter, diameter)
        val type = query.type
        var nearest: net.minecraft.world.phys.Vec3? = null
        var closest = query.radius * query.radius
        var id = Int.MAX_VALUE
        for (entity in level.getEntities(null, box) { it.isAlive && !it.isSpectator }) {
            if (type != null) {
                val key = BuiltInRegistries.ENTITY_TYPE.getKey(entity.type)
                if (type.namespace != key.namespace || type.path != key.path) continue
            }
            val location = entity.position()
            val distance = location.distanceToSqr(point)
            if (distance > closest || (distance == closest && entity.id >= id)) continue
            nearest = location
            closest = distance
            id = entity.id
        }
        return nearest?.subtract(point)?.toCore()
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
    private val defaultQuery = ParticleEntityQuery()
}
