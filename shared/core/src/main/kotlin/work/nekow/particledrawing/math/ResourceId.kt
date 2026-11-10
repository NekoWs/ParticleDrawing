package work.nekow.particledrawing.math

/** 带命名空间的资源标识；资源查找由宿主实现。 */
data class ResourceId(val namespace: String, val path: String) {
    init {
        require(namespace.matches(Regex("[a-z0-9_.-]+")) && path.matches(Regex("[a-z0-9/._-]+"))) { "无效资源名 $namespace:$path" }
    }
    override fun toString() = "$namespace:$path"
    companion object {
        @JvmStatic fun fromNamespaceAndPath(namespace: String, path: String) = ResourceId(namespace, path)
        @JvmStatic fun parse(value: String): ResourceId {
            val colon = value.indexOf(':')
            return if (colon < 0) ResourceId("minecraft", value) else ResourceId(value.substring(0, colon).ifEmpty { "minecraft" }, value.substring(colon + 1))
        }
        @JvmStatic fun tryParse(value: String): ResourceId? = try { parse(value) } catch (_: IllegalArgumentException) { null }
    }
}
