package work.nekow.particledrawing.api

import work.nekow.particledrawing.animation.script.mulberry32
import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.i18n.translation
import work.nekow.particledrawing.math.Vec3
import java.io.InputStream
import kotlin.math.sqrt

/** 三角面顶点使用模型局部坐标；绕序决定发射法线。 */
data class ParticleTriangle(val a: Vec3, val b: Vec3, val c: Vec3)

object ParticleMeshSampler {
    private data class Face(val a: Vec3, val ab: Vec3, val ac: Vec3, val normal: Vec3, val end: Double)

    /** 按三角面面积加权取样；相同的模型、数量和种子产生相同的点。 */
    @JvmStatic
    @JvmOverloads
    fun surface(triangles: List<ParticleTriangle>, count: Int, seed: Int = 0): List<ParticleSeed> {
        requireLocalized(count in 1..ParticleRecipe.MAX_SEEDS) { translation("error.sampling.count", ParticleRecipe.MAX_SEEDS) }
        requireLocalized(triangles.size <= MAX_ELEMENTS) { translation("error.mesh.elements", MAX_ELEMENTS) }
        var total = 0.0
        val faces = ArrayList<Face>(triangles.size)
        for ((a, b, c) in triangles) {
            requireFinite(a); requireFinite(b); requireFinite(c)
            val ab = b.subtract(a)
            val ac = c.subtract(a)
            val cross = ab.cross(ac)
            val magnitude = cross.length()
            requireLocalized(magnitude.isFinite()) { translation("error.mesh.finite") }
            if (magnitude <= 0.0) continue
            total += magnitude
            requireLocalized(total.isFinite()) { translation("error.mesh.finite") }
            faces += Face(a, ab, ac, cross.scale(1.0 / magnitude), total)
        }
        requireLocalized(total > 0.0) { translation("error.mesh.empty") }

        val next = mulberry32(seed)
        return List(count) { index ->
            val sample = next() * total
            val face = faces[faceIndex(faces, sample)]
            val spread = sqrt(next())
            val across = next()
            val position = face.a.add(face.ab.scale(spread * (1.0 - across))).add(face.ac.scale(spread * across))
            val u = if (count == 1) 0.0 else index.toDouble() / (count - 1)
            ParticleSeed(position, u = u, normal = face.normal)
        }
    }

    private fun faceIndex(faces: List<Face>, sample: Double): Int {
        var low = 0
        var high = faces.lastIndex
        while (low < high) {
            val mid = (low + high) ushr 1
            if (sample < faces[mid].end) high = mid else low = mid + 1
        }
        return low
    }

    /** 读取三角化 OBJ 的顶点和面，可用于数据包和 API。 */
    @JvmStatic
    fun readObj(input: InputStream): List<ParticleTriangle> {
        val bytes = input.readNBytes(MAX_OBJ_BYTES + 1)
        requireLocalized(bytes.size <= MAX_OBJ_BYTES) { translation("error.mesh.file_size") }
        val vertices = ArrayList<Vec3>()
        val triangles = ArrayList<ParticleTriangle>()
        for ((line, source) in bytes.toString(Charsets.UTF_8).lineSequence().withIndex()) {
            val content = source.substringBefore('#').trim()
            if (content.isEmpty()) continue
            val parts = content.split(WHITESPACE)
            when (parts[0]) {
                "v" -> {
                    requireLocalized(parts.size == 4) { translation("error.mesh.line", line + 1) }
                    val xyz = parts.drop(1).map { it.toDoubleOrNull() }
                    requireLocalized(xyz.all { it != null && it.isFinite() }) { translation("error.mesh.line", line + 1) }
                    val (x, y, z) = xyz
                    vertices += Vec3(requireNotNull(x), requireNotNull(y), requireNotNull(z))
                }
                "f" -> {
                    requireLocalized(parts.size == 4) { translation("error.mesh.triangle", line + 1) }
                    val (a, b, c) = parts.drop(1).map { value ->
                        val vertex = value.substringBefore('/').toIntOrNull() ?: 0
                        val index = if (vertex >= 0) vertex - 1 else vertices.size + vertex
                        requireLocalized(index in vertices.indices) { translation("error.mesh.line", line + 1) }
                        vertices[index]
                    }
                    triangles += ParticleTriangle(a, b, c)
                }
                "o", "g", "s", "vt", "vn", "usemtl", "mtllib" -> Unit
                else -> requireLocalized(false) { translation("error.mesh.line", line + 1) }
            }
            requireLocalized(vertices.size <= MAX_ELEMENTS && triangles.size <= MAX_ELEMENTS) { translation("error.mesh.elements", MAX_ELEMENTS) }
        }
        return triangles
    }

    private fun requireFinite(vector: Vec3) {
        requireLocalized(vector.x.isFinite() && vector.y.isFinite() && vector.z.isFinite()) { translation("error.mesh.finite") }
    }

    private const val MAX_ELEMENTS = 100_000
    private const val MAX_OBJ_BYTES = 8 * 1024 * 1024
    private val WHITESPACE = Regex("\\s+")
}
