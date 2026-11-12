package work.nekow.particledrawing.api

import java.util.UUID

/**
 * 一帧动态光源的只读快照。
 *
 * 坐标是渲染帧插值后的世界坐标，[intensity] 是这个光源参与光图写入的有效亮度
 * （已经含等级、静态光强乘数和光照曲线），[range] 是它在本帧能影响到的最远距离（块）。
 *
 * 使用方按自己的预算筛选后接到对应渲染管线上；PD 只负责给出这一份数据，
 * 不假定具体的光影包、曝光或衰减算法。
 */
class LightSample internal constructor(
    val id: UUID,
    val x: Double,
    val y: Double,
    val z: Double,
    val intensity: Double,
    val range: Double,
) {
    override fun toString(): String =
        "LightSample(id=$id, pos=($x, $y, $z), intensity=$intensity, range=$range)"
}
