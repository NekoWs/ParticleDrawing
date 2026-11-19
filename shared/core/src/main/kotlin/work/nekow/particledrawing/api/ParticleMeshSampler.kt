package work.nekow.particledrawing.api

import work.nekow.particledrawing.animation.script.mulberry32
import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.i18n.translation
import work.nekow.particledrawing.math.Vec3
import java.io.InputStream
import kotlin.math.sqrt

/** 三角面顶点使用模型局部坐标。可选属性依次对应 A、B、C。 */
data class ParticleTriangle @JvmOverloads constructor(
    val a: Vec3, val b: Vec3, val c: Vec3,
    val normals: Triple<Vec3, Vec3, Vec3>? = null,
    val uv: Triple<ParticleMeshUv, ParticleMeshUv, ParticleMeshUv>? = null,
)

data class ParticleMeshUv(val u: Double, val v: Double)
data class ParticleMeshPoint(val position: Vec3, val normal: Vec3, val uv: ParticleMeshUv?)

/** 每次取样读取一份三角形快照，调用方可以返回当前模型姿势。 */
fun interface ParticleTriangleProvider {
    fun triangles(): List<ParticleTriangle>
}

object ParticleMeshSampler {
    private data class Face(
        val triangle: ParticleTriangle, val ab: Vec3, val ac: Vec3, val normal: Vec3, val end: Double,
    )

    /** 按三角面面积加权取样；相同的模型、数量和种子产生相同的点。 */
    @JvmStatic
    @JvmOverloads
    fun surface(triangles: List<ParticleTriangle>, count: Int, seed: Int = 0): List<ParticleSeed> =
        surface(ParticleTriangleProvider { triangles }, count, seed)

    @JvmStatic
    @JvmOverloads
    fun surface(provider: ParticleTriangleProvider, count: Int, seed: Int = 0): List<ParticleSeed> =
        points(provider, count, seed).mapIndexed { index, point ->
            val u = if (count == 1) 0.0 else index.toDouble() / (count - 1)
            ParticleSeed(point.position, u = u, normal = point.normal, meshUv = point.uv)
        }

    /** 按实际三角面面积抽样，使用同一组重心权重插值位置、法线和 UV。 */
    @JvmStatic
    @JvmOverloads
    fun points(provider: ParticleTriangleProvider, count: Int, seed: Int = 0): List<ParticleMeshPoint> {
        requireLocalized(count in 1..ParticleRecipe.MAX_SEEDS) { translation("error.sampling.count", ParticleRecipe.MAX_SEEDS) }
        val triangles = provider.triangles()
        requireLocalized(triangles.size <= MAX_ELEMENTS) { translation("error.mesh.elements", MAX_ELEMENTS) }
        var total = 0.0
        val faces = ArrayList<Face>(triangles.size)
        for (triangle in triangles) {
            val (a, b, c, normals, coordinates) = triangle
            requireFinite(a); requireFinite(b); requireFinite(c)
            normals?.let { (na, nb, nc) -> requireFinite(na); requireFinite(nb); requireFinite(nc) }
            coordinates?.let { (ta, tb, tc) ->
                requireLocalized(ta.finite() && tb.finite() && tc.finite()) { translation("error.mesh.finite") }
            }
            val ab = b.subtract(a)
            val ac = c.subtract(a)
            val cross = ab.cross(ac)
            val magnitude = cross.length()
            requireLocalized(magnitude.isFinite()) { translation("error.mesh.finite") }
            if (magnitude <= 0.0) continue
            total += magnitude
            requireLocalized(total.isFinite()) { translation("error.mesh.finite") }
            faces += Face(triangle, ab, ac, cross.scale(1.0 / magnitude), total)
        }
        requireLocalized(total > 0.0) { translation("error.mesh.empty") }

        val next = mulberry32(seed)
        return List(count) {
            val sample = next() * total
            val face = faces[faceIndex(faces, sample)]
            val spread = sqrt(next())
            val across = next()
            val b = spread * (1.0 - across)
            val c = spread * across
            val a = 1.0 - spread
            val position = face.triangle.a.add(face.ab.scale(b)).add(face.ac.scale(c))
            val normal = face.triangle.normals?.let { (na, nb, nc) ->
                na.normalize().scale(a).add(nb.normalize().scale(b)).add(nc.normalize().scale(c)).normalize()
                    .takeUnless { it == Vec3.ZERO }
            } ?: face.normal
            val uv = face.triangle.uv?.let { (ta, tb, tc) ->
                ParticleMeshUv(ta.u * a + tb.u * b + tc.u * c, ta.v * a + tb.v * b + tc.v * c)
            }
            ParticleMeshPoint(position, normal, uv)
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
    fun objProvider(input: InputStream): ParticleTriangleProvider {
        val triangles = readObj(input)
        return ParticleTriangleProvider { triangles }
    }

    @JvmStatic
    fun readObj(input: InputStream): List<ParticleTriangle> {
        val bytes = input.readNBytes(MAX_OBJ_BYTES + 1)
        requireLocalized(bytes.size <= MAX_OBJ_BYTES) { translation("error.mesh.file_size") }
        val vertices = ArrayList<Vec3>()
        val normals = ArrayList<Vec3>()
        val coordinates = ArrayList<ParticleMeshUv>()
        val triangles = ArrayList<ParticleTriangle>()
        for ((line, source) in bytes.toString(Charsets.UTF_8).lineSequence().withIndex()) {
            val content = source.substringBefore('#').trim()
            if (content.isEmpty()) continue
            val parts = content.split(WHITESPACE)
            when (parts[0]) {
                "v", "vn" -> {
                    requireLocalized(parts.size == 4) { translation("error.mesh.line", line + 1) }
                    val xyz = parts.drop(1).map { it.toDoubleOrNull() }
                    requireLocalized(xyz.all { it != null && it.isFinite() }) { translation("error.mesh.line", line + 1) }
                    val (x, y, z) = xyz
                    val table = if (parts[0] == "v") vertices else normals
                    table += Vec3(requireNotNull(x), requireNotNull(y), requireNotNull(z))
                }
                "vt" -> {
                    val values = parts.drop(1).mapNotNull(String::toDoubleOrNull)
                    requireLocalized(parts.size in 2..4 && values.size == parts.size - 1 && values.all(Double::isFinite)) {
                        translation("error.mesh.line", line + 1)
                    }
                    coordinates += ParticleMeshUv(values[0], values.getOrElse(1) { 0.0 })
                }
                "f" -> {
                    requireLocalized(parts.size == 4) { translation("error.mesh.triangle", line + 1) }
                    triangles += face(parts.drop(1), vertices, coordinates, normals, line + 1)
                }
                "o", "g", "s", "usemtl", "mtllib" -> Unit
                else -> requireLocalized(false) { translation("error.mesh.line", line + 1) }
            }
            requireLocalized(vertices.size <= MAX_ELEMENTS && triangles.size <= MAX_ELEMENTS &&
                normals.size <= MAX_ELEMENTS && coordinates.size <= MAX_ELEMENTS) { translation("error.mesh.elements", MAX_ELEMENTS) }
        }
        return triangles
    }

    private fun face(
        fields: List<String>, vertices: List<Vec3>, coordinates: List<ParticleMeshUv>, normals: List<Vec3>, line: Int,
    ): ParticleTriangle {
        val refs = fields.map { it.split('/') }
        fun <T> attribute(values: List<T>, column: Int): Triple<T, T, T>? {
            if (values.isEmpty()) return null
            val (a, b, c) = refs.map { ref ->
                ref.getOrNull(column)?.takeIf(String::isNotEmpty)?.let { at(values, it, line) }
            }
            return if (a == null || b == null || c == null) null else Triple(a, b, c)
        }
        val (a, b, c) = refs.map { at(vertices, it.first(), line) }
        return ParticleTriangle(a, b, c, attribute(normals, 2), attribute(coordinates, 1))
    }

    private fun <T> at(values: List<T>, reference: String, line: Int): T {
        val ordinal = reference.toIntOrNull() ?: 0
        val index = if (ordinal >= 0) ordinal - 1 else values.size + ordinal
        requireLocalized(index in values.indices) { translation("error.mesh.line", line) }
        return values[index]
    }

    private fun ParticleMeshUv.finite() = u.isFinite() && v.isFinite()

    private fun requireFinite(vector: Vec3) {
        requireLocalized(vector.x.isFinite() && vector.y.isFinite() && vector.z.isFinite()) { translation("error.mesh.finite") }
    }

    private const val MAX_ELEMENTS = 100_000
    private const val MAX_OBJ_BYTES = 8 * 1024 * 1024
    private val WHITESPACE = Regex("\\s+")
}
