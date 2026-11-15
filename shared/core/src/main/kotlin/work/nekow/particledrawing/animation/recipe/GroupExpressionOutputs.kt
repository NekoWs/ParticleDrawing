package work.nekow.particledrawing.animation.recipe

import work.nekow.particledrawing.i18n.requireLocalized
import work.nekow.particledrawing.i18n.translation

/** 固定成员组的公式只接管几何和颜色；运动状态由生成器的逐粒子公式维护。 */
object GroupExpressionOutputs {
    fun validate(code: String) {
        val unsupported = code.split(';').flatMap { statement ->
            statement.substringBefore('=').trim().removeSurrounding("[", "]").split(',').map(String::trim)
        }.filter { it in setOf("vx", "vy", "vz", "glow", "light") }.distinct()
        requireLocalized(unsupported.isEmpty()) { translation("error.expression.output", unsupported.joinToString()) }
    }
}
