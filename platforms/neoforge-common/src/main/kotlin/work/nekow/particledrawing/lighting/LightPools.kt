package work.nekow.particledrawing.lighting

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.config.ParticleDrawingConfig
import work.nekow.particledrawing.core.client.ShaderCompat

/**
 * 光影包不认地形顶点光照时的替代表现：在光源照到的表面上画一块软光斑。
 *
 * 光影包大多自己算地形光照，读不到模组并进顶点/光图的那点亮度，地面就会全黑。这里改成几何光斑：
 * 从光源沿照射方向打一条射线找到表面，再在表面上方铺一块带径向渐变贴图的四边形，用原版的半透明自发光
 * 渲染类型提交，光影包会正常渲染它。只在光影包生效时启用，平常仍走真正的地形光照。
 */
internal object LightPools {
    private const val MAX_POOLS = 48
    private const val MIN_ALPHA = 0.015
    private const val FULL_BRIGHT = 0xF000F0
    private val DOWN = Vec3(0.0, -1.0, 0.0)

    internal class Pool(
        val x: Double,
        val y: Double,
        val z: Double,
        val radius: Double,
        val red: Float,
        val green: Float,
        val blue: Float,
        val alpha: Float,
    )

    /** 收集本帧要画的光斑；没有光影包时返回空表，不影响原本的地形光照。 */
    fun collect(camera: Vec3): List<Pool> {
        if (!enabled()) return emptyList()
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return emptyList()
        val player = mc.player ?: return emptyList()
        val pools = ArrayList<Pool>()
        val ordered = DynamicLightManager.lightSnapshot().sortedBy { camera.distanceToSqr(it.x, it.y, it.z) }
        for (light in ordered) {
            if (pools.size >= MAX_POOLS) break
            val origin = Vec3(light.x, light.y, light.z)
            val shape = light.shape
            val direction = if (shape != null && shape.isSpot) {
                val (x, y, z) = shape.direction()
                Vec3(x, y, z)
            } else {
                DOWN
            }
            val reach = light.range.coerceAtLeast(0.5)
            val hit = level.clip(ClipContext(
                origin, origin.add(direction.scale(reach)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player,
            ))
            if (hit.type != HitResult.Type.BLOCK) continue
            val surface = hit.location
            val fade = 1.0 - (origin.distanceTo(surface) / reach).coerceIn(0.0, 1.0)
            val alpha = light.intensity / 15.0 * fade * 0.55
            if (alpha < MIN_ALPHA) continue
            val rgb = light.rgb
            pools += Pool(
                surface.x, surface.y + 0.02, surface.z,
                (reach * 0.35).coerceIn(0.5, 6.0),
                ((rgb shr 16) and 0xFF) / 255f,
                ((rgb shr 8) and 0xFF) / 255f,
                (rgb and 0xFF) / 255f,
                alpha.toFloat(),
            )
        }
        return pools
    }

    /** 画贴图四边形；正反两面各画一遍，渲染类型的剔除方向不会把光斑整个剔掉。 */
    fun draw(pools: List<Pool>, camera: Vec3, pose: PoseStack.Pose, vertices: VertexConsumer) {
        val us = floatArrayOf(0f, 1f, 1f, 0f)
        val vs = floatArrayOf(1f, 1f, 0f, 0f)
        for (pool in pools) {
            val argb = (((pool.alpha * 255).toInt().coerceIn(0, 255)) shl 24) or
                ((pool.red * 255).toInt().coerceIn(0, 255) shl 16) or
                ((pool.green * 255).toInt().coerceIn(0, 255) shl 8) or
                (pool.blue * 255).toInt().coerceIn(0, 255)
            // 提交阶段的坐标相对相机，和光束一样先减掉相机位置。
            val x = (pool.x - camera.x).toFloat()
            val y = (pool.y - camera.y).toFloat()
            val z = (pool.z - camera.z).toFloat()
            val radius = pool.radius.toFloat()
            for (clockwise in booleanArrayOf(true, false)) {
                val xs = if (clockwise) floatArrayOf(-1f, 1f, 1f, -1f) else floatArrayOf(-1f, -1f, 1f, 1f)
                val zs = if (clockwise) floatArrayOf(-1f, -1f, 1f, 1f) else floatArrayOf(-1f, 1f, 1f, -1f)
                for (i in 0 until 4) {
                    vertices.addVertex(pose, x + radius * xs[i], y, z + radius * zs[i])
                        .setColor(argb)
                        .setUv(us[i], vs[i])
                        .setLight(FULL_BRIGHT)
                        .setOverlay(OverlayTexture.NO_OVERLAY)
                        .setNormal(pose, 0f, 1f, 0f)
                }
            }
        }
    }

    /** 开关条件集中在一起，避免渲染入口和绘制逻辑各判一套。 */
    private fun enabled(): Boolean =
        ParticleDrawingConfig.CLIENT.shaderLightPools.get() &&
            DynamicLightManager.isEnabled() &&
            DynamicLightManager.hasActiveLights() &&
            ShaderCompat.legacyPipelines()
}
