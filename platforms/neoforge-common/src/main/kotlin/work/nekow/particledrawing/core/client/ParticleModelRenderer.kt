package work.nekow.particledrawing.core.client

import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.Minecraft
import org.joml.Quaternionf
import org.joml.Vector3f
import work.nekow.particledrawing.api.ParticleMeshSampler
import work.nekow.particledrawing.api.ParticleTriangle
import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.i18n.requireNotNullLocalized
import work.nekow.particledrawing.i18n.translation
import work.nekow.particledrawing.math.ResourceId
import work.nekow.particledrawing.math.Vec3
import work.nekow.particledrawing.platform.toMinecraft
import work.nekow.particledrawing.render.SurfaceDecal
import work.nekow.particledrawing.render.SurfaceDecalWrap
import work.nekow.particledrawing.render.WorldBounds

/** 三角网格共用粒子层的颜色、混合、贴图和光图。 */
internal object ParticleModelRenderer {
    data class Face(val triangle: ParticleTriangle, val normal: Vec3)
    class Model(val faces: List<Face>, val patches: List<SurfaceDecal.Patch> = emptyList()) {
        val fixedRadius = patches.maxOfOrNull { patch -> patch.vertices.maxOf { it.position.length().toFloat() } } ?: 0f
    }

    private val cache = linkedMapOf<ResourceId, Model>()
    private val octahedron = ResourceId.parse("particledrawing:builtin/octahedron")

    fun model(id: ResourceId): Model = cache[id] ?: load(id).also {
        if (cache.size >= MAX_CACHE) cache.remove(cache.keys.first())
        cache[id] = it
    }

    fun clear() = cache.clear()

    private fun load(id: ResourceId): Model {
        val mesh = if (id == octahedron) builtinOctahedron() else {
            val manager = Minecraft.getInstance().resourceManager
            val resource = manager.getResource(id.toMinecraft()).orElse(null)
            requireNotNullLocalized(resource) { translation("error.mesh.missing", id) }.open().use(ParticleMeshSampler::readObj)
        }
        return normalize(mesh)
    }

    internal fun normalize(mesh: List<ParticleTriangle>): Model {
        requireLocalized(mesh.isNotEmpty() && mesh.size <= MAX_FACES) { translation("error.mesh.model_size", MAX_FACES) }
        val vertices = mesh.flatMap { listOf(it.a, it.b, it.c) }
        val low = Vec3(vertices.minOf { it.x }, vertices.minOf { it.y }, vertices.minOf { it.z })
        val high = Vec3(vertices.maxOf { it.x }, vertices.maxOf { it.y }, vertices.maxOf { it.z })
        val center = low.lerp(high, 0.5)
        val radius = vertices.maxOf { it.distanceTo(center) }
        requireLocalized(radius.isFinite() && radius > 0.0) { translation("error.mesh.empty") }
        fun unit(v: Vec3) = v.subtract(center).scale(1.0 / radius)
        val triangles = mesh.map { ParticleTriangle(unit(it.a), unit(it.b), unit(it.c)) }.filter {
            it.b.subtract(it.a).cross(it.c.subtract(it.a)).lengthSqr() > 1e-14
        }
        requireLocalized(triangles.isNotEmpty()) { translation("error.mesh.empty") }
        return Model(triangles.map { face ->
            Face(face, face.b.subtract(face.a).cross(face.c.subtract(face.a)).normalize())
        })
    }

    private fun builtinOctahedron(): List<ParticleTriangle> {
        val top = Vec3(0.0, 1.0, 0.0)
        val bottom = top.scale(-1.0)
        val equator = listOf(Vec3(1.0, 0.0, 0.0), Vec3(0.0, 0.0, 1.0), Vec3(-1.0, 0.0, 0.0), Vec3(0.0, 0.0, -1.0))
        return equator.indices.flatMap { i ->
            val a = equator[i]
            val b = equator[(i + 1) % equator.size]
            listOf(ParticleTriangle(top, b, a), ParticleTriangle(bottom, a, b))
        }
    }

    fun decal(position: Vec3, hit: Vec3, normal: Vec3, radius: Double, wrap: Boolean): Model? {
        val range = radius + SurfaceDecal.DEPTH
        val origin = if (wrap) hit else position
        val box = WorldBounds(origin.add(-range, -range, -range), origin.add(range, range, range))
        val shapes = ClientParticleWorld.blockShapes(box)
        val patches = if (wrap) SurfaceDecalWrap.project(origin, normal, radius, shapes, position)
            else SurfaceDecal.project(position, normal, radius, shapes)
        return if (patches.isEmpty()) null else Model(emptyList(), patches)
    }

    private val scratch = Vector3f()

    fun draw(
        model: Model, consumer: VertexConsumer, position: Vector3f, rotation: Quaternionf,
        width: Float, height: Float, u0: Float, u1: Float, v0: Float, v1: Float, color: Int, light: Int,
    ) {
        if (!ParticleGeometryBudget.reserve(model.faces.size + model.patches.size)) return
        for (patch in model.patches) for ((point, u, v) in patch.vertices) {
            consumer.addVertex(position.x + point.x.toFloat(), position.y + point.y.toFloat(), position.z + point.z.toFloat())
                .setUv(u0 + (u1 - u0) * u, v1 + (v0 - v1) * v).setColor(color).setLight(light)
        }
        for ((face, normal) in model.faces) {
            val (a, b, c) = face
            rotation.transform(scratch.set(normal.x.toFloat(), normal.y.toFloat(), normal.z.toFloat()))
            val shade = (0.70f + 0.28f * scratch.y + 0.10f * scratch.x).coerceIn(0.32f, 1f)
            val shaded = (color and -0x1000000) or
                (((color ushr 16 and 255) * shade).toInt() shl 16) or
                (((color ushr 8 and 255) * shade).toInt() shl 8) or ((color and 255) * shade).toInt()

            fun vertex(point: Vec3, u: Float, v: Float) {
                rotation.transform(scratch.set((point.x * width).toFloat(), (point.y * height).toFloat(), (point.z * height).toFloat()))
                consumer.addVertex(position.x + scratch.x, position.y + scratch.y, position.z + scratch.z)
                    .setUv(u, v).setColor(shaded).setLight(light)
            }

            // 粒子通道使用四边形，重复末顶点提交一个退化的第二三角。
            vertex(a, u0, v1)
            vertex(b, u1, v1)
            vertex(c, u0, v0)
            vertex(c, u0, v0)
        }
    }

    private const val MAX_FACES = 128
    private const val MAX_CACHE = 64
}
