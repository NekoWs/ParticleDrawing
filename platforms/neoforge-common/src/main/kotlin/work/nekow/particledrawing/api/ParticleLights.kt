package work.nekow.particledrawing.api

import work.nekow.particledrawing.lighting.DynamicLightManager

/**
 * 动态光源的只读入口。
 *
 * PD 每帧从发光粒子收集光源并写进客户端光图；这里把这批光源的**当帧快照**公开出来，
 * 让第三方不用读粒子引擎对象，也不用自己重写筛选和排序。
 *
 * 快照里的坐标已经按渲染帧插值，强度已经含等级、静态乘数和光照曲线，[LightSample.range]
 * 是这一帧的照射半径。筛选预算、接到哪套渲染管线（原版光图、光影包 uniform、自建体素照明）
 * 都由使用方决定。
 *
 * 注意：PD 注入的是**客户端光图**。读取光图的光影包能直接继承效果；自己维护体素追踪或
 * 手持照明的包（例如部分 iterationRP 版本）通常需要显式消费这份快照才会照到地形。
 */
object ParticleLights {

    /** 动态光照开关（客户端配置 `enableDynamicLights`）。 */
    @JvmStatic
    fun isEnabled(): Boolean = DynamicLightManager.isEnabled()

    /**
     * 最近一帧的光源快照；没有光源或功能关闭时返回空列表。
     *
     * 每调用一次新建一份列表，逐帧取用请改用 [snapshotInto]。
     */
    @JvmStatic
    fun snapshot(): List<LightSample> = DynamicLightManager.lightSnapshot()

    /**
     * 把最近一帧的光源写进 [out]（先清空），返回数量。
     *
     * 逐帧取用且想复用容器时用它，避免每次新建列表。
     */
    @JvmStatic
    fun snapshotInto(out: MutableList<LightSample>): Int = DynamicLightManager.snapshotInto(out)
}
