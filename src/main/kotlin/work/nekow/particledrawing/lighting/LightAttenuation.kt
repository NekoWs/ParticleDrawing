package work.nekow.particledrawing.lighting

import work.nekow.particledrawing.core.easing.EasingCurve

@Suppress("unused")
fun interface LightAttenuation {

    fun evaluate(distance: Float, maxDistance: Float): Float

    companion object {
        val LINEAR = LightAttenuation { distance, maxDistance ->
            if (distance >= maxDistance) 0f
            else 1f - (distance / maxDistance)
        }

        val INVERSE_SQUARE = LightAttenuation { distance, maxDistance ->
            if (distance >= maxDistance) 0f
            else {
                val t = distance / maxDistance
                1f / (1f + t * t * 8f)
            }
        }

        val SMOOTHSTEP = LightAttenuation { distance, maxDistance ->
            if (distance >= maxDistance) 0f
            else {
                val t = distance / maxDistance
                1f - (t * t * (3f - 2f * t))
            }
        }

        val INVERSE_LINEAR = LightAttenuation { distance, maxDistance ->
            if (distance >= maxDistance) 0f
            else 1f / (1f + distance * 2f)
        }

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
