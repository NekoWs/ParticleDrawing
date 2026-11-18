package work.nekow.particledrawing.core.client

import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.shapes.VoxelShape
import work.nekow.particledrawing.animation.recipe.ParticleWorld
import work.nekow.particledrawing.animation.recipe.RecipeCollision
import work.nekow.particledrawing.animation.recipe.SurfaceHit
import work.nekow.particledrawing.math.Vec3
import work.nekow.particledrawing.platform.toCore
import work.nekow.particledrawing.platform.toMinecraft

/** 两种查询都使用实际形状，台阶上方的空半格仍然是空气。 */
internal object ClientParticleWorld : ParticleWorld {
    override fun cast(mode: RecipeCollision, from: Vec3, to: Vec3): SurfaceHit? {
        if (mode == RecipeCollision.NONE) return null
        val level = Minecraft.getInstance().level ?: return null
        return trace(from, to) { pos ->
            if (mode == RecipeCollision.SOLID) level.getBlockState(pos).getCollisionShape(level, pos)
            else level.getFluidState(pos).getShape(level, pos)
        }
    }

    internal fun trace(from: Vec3, to: Vec3, shapeAt: (BlockPos) -> VoxelShape): SurfaceHit? {
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
        if (hit.type == HitResult.Type.MISS) return null
        val normal = hit.direction
        return SurfaceHit(hit.location.toCore(), Vec3(normal.stepX.toDouble(), normal.stepY.toDouble(), normal.stepZ.toDouble()), hit.isInside)
    }
}
