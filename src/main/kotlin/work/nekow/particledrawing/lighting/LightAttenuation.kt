package work.nekow.particledrawing.lighting

import work.nekow.particledrawing.core.easing.EasingCurve

/**
 * 光照衰减函数接口。
 * 定义动态光源随距离衰减的强度计算方式。
 */
@Suppress("unused")
fun interface LightAttenuation {

    /**
     * 计算指定距离下的衰减因子。
     * @param distance 到光源的距离
     * @param maxDistance 光源最大有效范围
     * @return 衰减因子 (0=无光照, 1=满光照)
     */
    fun evaluate(distance: Float, maxDistance: Float): Float

    companion object {
        /** 线性衰减: 距离越远越暗 */
        @JvmField val LINEAR = LightAttenuation { distance, maxDistance ->
            if (distance >= maxDistance) 0f
            else 1f - (distance / maxDistance)
        }

        /** 平方反比衰减: 模拟真实物理光照 */
        @JvmField val INVERSE_SQUARE = LightAttenuation { distance, maxDistance ->
            if (distance >= maxDistance) 0f
            else {
                val t = distance / maxDistance
                1f / (1f + t * t * 8f)
            }
        }

        /** 平滑阶梯衰减: 使用 S 曲线过渡 */
        @JvmField val SMOOTHSTEP = LightAttenuation { distance, maxDistance ->
            if (distance >= maxDistance) 0f
            else {
                val t = distance / maxDistance
                1f - (t * t * (3f - 2f * t))
            }
        }

        /** 反比线性衰减: 近距离衰减快，远距离保持微弱光照 */
        @JvmField val INVERSE_LINEAR = LightAttenuation { distance, maxDistance ->
            if (distance >= maxDistance) 0f
            else 1f / (1f + distance * 2f)
        }

        /**
         * 使用贝塞尔曲线自定义衰减。
         * @param x1 控制点1 X
         * @param y1 控制点1 Y
         * @param x2 控制点2 X
         * @param y2 控制点2 Y
         * @return 自定义衰减函数
         */
        @JvmStatic
        fun bezier(x1: Double, y1: Double, x2: Double, y2: Double): LightAttenuation {
            val curve = EasingCurve(x1, y1, x2, y2)
            return LightAttenuation { distance, maxDistance ->
                if (distance >= maxDistance) 0f else {
                    val t = distance / maxDistance
                    1f - curve.evaluate(t)
                }
            }
        }
    }
}
