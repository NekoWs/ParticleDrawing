package work.nekow.particledrawing.lighting

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.Minecraft
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.api.LightSample
import work.nekow.particledrawing.core.client.ShaderCompat
import kotlin.math.acos
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * 光影包不认地形顶点光照时的替代表现：在相机周围的地表上烘一张“光的网格”。
 *
 * 光影包大多自己算地形光照，读不到模组并进顶点/光图的那点亮度，地面就会全黑。这里按 1 格间距采样
 * 地表高度和所有动态光的贡献，逐顶点累加颜色后整片画出来：相邻格子共享顶点位置与渐变，不会像
 * 一个光源一块贴图那样出现硬边、也不会叠成一片白。只在光影包生效时启用，平常仍走真正的地形光照。
 */
internal object LightPools {
    private const val RADIUS = 24
    private const val CELL = 1.0
    private const val MAX_LIGHTS = 24
    private const val MIN_SUM = 0.015
    private const val REBUILD_NANOS = 100_000_000L
    private const val CAMERA_MOVE_SQR = 4.0 * 4.0
    private const val TONE_SCALE = 3.0
    private const val INTENSITY = 1.0
    private const val SURFACE_OFFSET = 0.02
    private const val MAX_HEIGHT_DELTA = 8.0

    /** 一次重建出来的地面网格；positions 每 3 个浮点一个顶点，colors 每顶点一个 ARGB。 */
    internal class Mesh(val positions: FloatArray, val colors: IntArray, val vertices: Int)

    private var mesh: Mesh? = null
    private var builtVersion = Int.MIN_VALUE
    private var builtCamera: Vec3? = null
    private var builtNanos = 0L

    /** 每 100 毫秒或光源/相机变化时重建，其余帧复用上一份网格。 */
    fun build(camera: Vec3): Mesh? {
        if (!enabled()) {
            mesh = null
            return null
        }
        val now = System.nanoTime()
        val cached = mesh
        val moved = builtCamera?.let { it.distanceToSqr(camera) > CAMERA_MOVE_SQR } ?: true
        if (cached != null && !moved && builtVersion == DynamicLightManager.version() &&
            now - builtNanos < REBUILD_NANOS
        ) {
            return cached
        }
        builtNanos = now
        builtVersion = DynamicLightManager.version()
        builtCamera = camera
        return rebuild(camera).also { mesh = it }
    }

    private fun rebuild(camera: Vec3): Mesh? {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return null
        val lights = DynamicLightManager.lightSnapshot()
            .filter { it.range > 0.05 && it.intensity > 0.0 }
            .sortedBy { camera.distanceToSqr(it.x, it.y, it.z) }
            .take(MAX_LIGHTS)
        if (lights.isEmpty()) return null

        val centerX = Math.floor(camera.x).toInt()
        val centerZ = Math.floor(camera.z).toInt()
        val steps = (RADIUS / CELL).toInt()
        val side = steps + 1
        val heights = IntArray(side * side)
        val sums = FloatArray(side * side * 3)
        for (sx in 0..steps) {
            val worldX = centerX + (sx * CELL).toInt()
            for (sz in 0..steps) {
                val worldZ = centerZ + (sz * CELL).toInt()
                val index = sx * side + sz
                val height = level.getHeight(Heightmap.Types.MOTION_BLOCKING, worldX, worldZ)
                heights[index] = height
                val sampleY = height + SURFACE_OFFSET
                if (kotlin.math.abs(sampleY - camera.y) > MAX_HEIGHT_DELTA) continue
                var red = 0.0
                var green = 0.0
                var blue = 0.0
                for (light in lights) {
                    val contribution = contribution(light, worldX + 0.5, sampleY, worldZ + 0.5)
                    if (contribution <= 0.0) continue
                    red += contribution * ((light.rgb shr 16) and 0xFF) / 255.0
                    green += contribution * ((light.rgb shr 8) and 0xFF) / 255.0
                    blue += contribution * (light.rgb and 0xFF) / 255.0
                }
                // 网格边缘淡出，避免在采样范围边界出现一条硬边。
                val edgeX = kotlin.math.abs(worldX + 0.5 - camera.x)
                val edgeZ = kotlin.math.abs(worldZ + 0.5 - camera.z)
                val edge = ((RADIUS - kotlin.math.max(edgeX, edgeZ)) / 6.0).coerceIn(0.0, 1.0)
                sums[index * 3] = (red * edge).toFloat()
                sums[index * 3 + 1] = (green * edge).toFloat()
                sums[index * 3 + 2] = (blue * edge).toFloat()
            }
        }

        val positions = ArrayList<Float>(steps * steps * 12)
        val colors = ArrayList<Int>(steps * steps * 4)
        for (sx in 0 until steps) {
            for (sz in 0 until steps) {
                // 逆时针绕序让法线朝上，避免渲染类型按剔除方向把整片网格剔掉。
                val columns = intArrayOf(sx * side + sz, sx * side + sz + 1, (sx + 1) * side + sz + 1, (sx + 1) * side + sz)
                var lit = false
                for (column in columns) {
                    val sum = sums[column * 3] + sums[column * 3 + 1] + sums[column * 3 + 2]
                    if (sum > MIN_SUM) {
                        lit = true
                        break
                    }
                }
                if (!lit) continue
                for (column in columns) {
                    positions.add(centerX + (column / side) * CELL.toFloat())
                    positions.add(heights[column] + SURFACE_OFFSET.toFloat())
                    positions.add(centerZ + (column % side) * CELL.toFloat())
                    colors.add(colorOf(sums[column * 3], sums[column * 3 + 1], sums[column * 3 + 2]))
                }
            }
        }
        if (positions.isEmpty()) return null
        return Mesh(positions.toFloatArray(), colors.toIntArray(), colors.size)
    }

    private fun colorOf(red: Float, green: Float, blue: Float): Int {
        fun channel(sum: Float): Int =
            ((1.0 - exp(-TONE_SCALE * sum)) * INTENSITY * 255.0).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (channel(red) shl 16) or (channel(green) shl 8) or channel(blue)
    }

    /** 单盏灯在地表点上的相对亮度：距离衰减 + 射灯锥体收边。 */
    private fun contribution(light: LightSample, x: Double, y: Double, z: Double): Double {
        val dx = x - light.x
        val dy = y - light.y
        val dz = z - light.z
        val distanceSqr = dx * dx + dy * dy + dz * dz
        if (distanceSqr >= light.range * light.range) return 0.0
        val distance = sqrt(distanceSqr)
        val falloff = 1.0 - distance / light.range
        var amount = (light.intensity / 15.0) * falloff * falloff * falloff
        val shape = light.shape
        if (shape != null && shape.isSpot && distance > 1.0e-4) {
            val (dirX, dirY, dirZ) = shape.direction()
            val cos = (dx * dirX + dy * dirY + dz * dirZ) / distance
            val angle = Math.toDegrees(acos(cos.coerceIn(-1.0, 1.0)))
            val outer = shape.outerDeg
            if (angle >= outer) return 0.0
            val inner = shape.effectiveInnerDeg
            if (angle > inner) {
                val t = (angle - inner) / (outer - inner).coerceAtLeast(1.0e-4)
                amount *= Math.pow(1.0 - t, shape.edge)
            }
        }
        return amount
    }

    /** 画地面网格；坐标相对相机，和粒子光束的提交方式一致。 */
    fun draw(mesh: Mesh, camera: Vec3, pose: PoseStack.Pose, vertices: VertexConsumer) {
        val positions = mesh.positions
        val colors = mesh.colors
        var i = 0
        var v = 0
        while (v < mesh.vertices) {
            val x = (positions[i] - camera.x).toFloat()
            val y = (positions[i + 1] - camera.y).toFloat()
            val z = (positions[i + 2] - camera.z).toFloat()
            vertices.addVertex(pose, x, y, z).setColor(colors[v])
            i += 3
            v++
        }
    }

    /** 开关条件集中在一起，避免渲染入口和绘制逻辑各判一套。 */
    private fun enabled(): Boolean =
        DynamicLightManager.hasActiveLights() && ShaderCompat.useLightPools()
}




