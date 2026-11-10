package work.nekow.particledrawing.animation.recipe

/** 固定成员组的公式只接管几何和颜色；运动状态由生成器的逐粒子公式维护。 */
internal object GroupExpressionOutputs {
    fun validate(code: String) {
        val unsupported = code.split(';').flatMap { statement ->
            statement.substringBefore('=').trim().removeSurrounding("[", "]").split(',').map(String::trim)
        }.filter { it in setOf("vx", "vy", "vz", "glow", "light") }.distinct()
        require(unsupported.isEmpty()) { "组 expression 不支持输出 ${unsupported.joinToString()}；请用 particle emitter 的 birth/motion 设置逐粒子属性" }
    }
}
