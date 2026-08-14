# 运动算法

运动算法（Motion）在 **客户端每帧** 计算粒子的位置、颜色与缩放，与服务端的 tick 循环解耦，因此动画帧率不受 `/tick` 或服务端卡顿影响。适合持续旋转、涡旋、跟随等需要高刷新率的动画。

## 工作原理

1. 服务端通过 `ServerParticleEngine.sendMotion` 下发 `MotionPayload`（组 ID、算法 ID、参数数组、基准点）。
2. 客户端 `MotionSystem.start` 为该组创建算法实例并记录每个粒子的基准位置（`basePositions` 快照）。
3. 每帧 `MotionSystem.tick` 分两阶段执行：
   - **第一阶段**：每个算法调用一次 `updatePivot` 更新组轴心（可有内部状态）。
   - **第二阶段**：对组内每个粒子依次调用 `compute`，输出新的位置/颜色/缩放，直接写入渲染粒子。

## MotionAlgorithm 接口

```kotlin
interface MotionAlgorithm {
    val id: String

    data class Result(
        val position: Vec3? = null,   // 非空则更新位置
        val color: Color? = null,     // 非空则更新颜色
        val scale: Float? = null      // 非空则更新缩放
    )

    // 每算法每帧调用一次，更新组轴心；默认不变
    fun updatePivot(pivot: Vec3, elapsedSeconds: Double, target: Vec3?): Vec3 = pivot

    // 对每个粒子调用，应为纯计算（无副作用）
    fun compute(basePos: Vec3, pivot: Vec3, elapsedSeconds: Double, target: Vec3?): Result
}
```

- `target` 由 `MotionSystem.targetProvider` 提供，默认为本地玩家位置，可替换以泛化用途。
- `elapsedSeconds` 基于 `System.nanoTime()`，是真实流逝秒数，与游戏 tick 无关。
- 辅助函数 `DoubleArray.at(index, default)` 按索引安全读取参数（越界返回默认值）。
- 辅助函数 `Vec3.rotateAround(unitAxis, radians)` 使用 Rodrigues 旋转公式。

## 注册与使用

### 通过 ParticleGroup（推荐）

```kotlin
group.addMotion("rotate", doubleArrayOf(0.0, 1.0, 0.0, 0.5))   // 绕 Y 轴 0.5 rad/s
group.stopMotion()
```

### 便捷方法

| 方法 | 等价算法 |
| --- | --- |
| `rotateMotion(radiansPerSecond)` | `rotate`（绕 X 轴） |
| `colorGradientMotion()` / `colorGradientMotion(params)` | `color_gradient` |
| `followPlayerMotion(smoothFactor)` | `follow_player` |
| `scaleByDistanceMotion(maxScale, minScale, maxDistance)` | `scale_by_distance` |

### 自定义算法

```kotlin
class MyAlgorithm(params: DoubleArray) : MotionAlgorithm {
    override val id = "my_algorithm"
    private val speed = params.at(0, 1.0)

    override fun compute(basePos: Vec3, pivot: Vec3, elapsedSeconds: Double, target: Vec3?): MotionAlgorithm.Result {
        val angle = elapsedSeconds * speed
        return MotionAlgorithm.Result(position = pivot.add(basePos.subtract(pivot).rotateAround(Vec3(0.0, 1.0, 0.0), angle)))
    }
}

// 客户端注册（例如在 mod 初始化阶段）
MotionSystem.register("my_algorithm") { params -> MyAlgorithm(params) }
```

> 自定义算法必须同时存在于客户端（真正执行计算）与服务端（只需要能通过字符串 ID 下发即可）。服务端通过 `group.addMotion("my_algorithm", params)` 下发。

## 内置算法

### rotate

绕任意轴匀速旋转。

```
params = [ax, ay, az, radiansPerSecond]
默认：轴 (0,1,0)，角速度 0
ID: "rotate"
```

### swirl

螺旋扭转，角速度随沿轴高度线性增大，模拟龙卷风剪切扭转。

```
params = [ax, ay, az, speed, twist]
[0..2] axis   旋转轴（默认 0,1,0）
[3]    speed  基准角速度 rad/s（默认 0.8）
[4]    twist  每格沿轴高度附加角速度 rad/s（默认 0.35）
ID: "swirl"
```

### vortex

涡旋：粒子绕轴心螺旋内卷，卷到中心后从外缘循环再生，叠加波纹、差分旋转与螺旋色相。

```
params = [spin, falloff, inflow, waveFreq, waveSpeed, amp, maxR, hueBase, hueSpan, ax, ay, az]
[0]     spin      基准角速度 rad/s（默认 1.2）
[1]     falloff   角速度随半径衰减系数，实际角速度 = spin/(1 + r*falloff)（默认 0.25）
[2]     inflow    径向内卷速度 blocks/s（默认 0.6）
[3]     waveFreq  波纹空间频率（默认 2.5）
[4]     waveSpeed 波纹相位速度，负值向外扩散（默认 -3.2）
[5]     amp       波纹振幅 blocks（默认 0.55）
[6]     maxR      外缘半径 blocks（默认 5.5）
[7]     hueBase   基础色相（默认 0.5）
[8]     hueSpan   色相沿角度跨度（默认 0.35）
[9..11] axis      旋转轴（默认 0,1,0）
ID: "vortex"
```

### follow_player

跟随目标点（默认玩家位置）移动，带指数平滑。

```
params = [smoothFactor]（默认 0.02）
ID: "follow_player"
```

### scale_by_distance

基于目标点（默认玩家位置）距离缩放，越近越大，越远越小（二次曲线）。

```
params = [maxScale, minScale, maxDistance]（默认 [1.0, 0.05, 6.0]）
ID: "scale_by_distance"
```

### color_gradient

将粒子相对 pivot 的坐标沿渐变方向投影，映射到 HSB 或 RGB 渐变。

```
params 布局（含缺省值）：
[0]  axis   渐变方向：0=X, 1=Y(默认), 2=Z, 3=自定义向量
[1]  mode   颜色模式：0=HSB(默认), 1=RGB
[2]  min    渐变下界（默认 -1）
[3]  max    渐变上界（默认 1）
[4]  hueStart | r0
[5]  hueEnd   | g0
[6]  sat      | b0
[7]  bri      | r1
[8]  -        | g1
[9]  -        | b1
[10] alpha   透明度（默认 1.0）
[11..13] dx,dy,dz  自定义方向（默认 0,1,0）
ID: "color_gradient"
```

推荐使用工厂方法构造参数：

```kotlin
val hsbParams = ColorGradientAlgorithm.hsbParams(
    axis = ColorGradientAlgorithm.AXIS_Y,
    hueStart = 0.0, hueEnd = 1.0,
    saturation = 0.9, brightness = 0.9
)
group.colorGradientMotion(hsbParams)

val rgbParams = ColorGradientAlgorithm.rgbParams(
    axis = ColorGradientAlgorithm.AXIS_Y,
    start = Color.RED, end = Color.BLUE
)
group.colorGradientMotion(rgbParams)
```

其中 `ColorGradientAlgorithm.AXIS_X = 0`、`AXIS_Y = 1`、`AXIS_Z = 2`、`AXIS_CUSTOM = 3`；`MODE_HSB = 0`、`MODE_RGB = 1`。

## 算法 ID 速查

| ID | 类 | 说明 |
| --- | --- | --- |
| `rotate` | `RotateAlgorithm` | 绕轴旋转 |
| `swirl` | `SwirlAlgorithm` | 螺旋扭转 |
| `vortex` | `VortexAlgorithm` | 涡旋内卷 + 波纹 |
| `follow_player` | `FollowPlayerAlgorithm` | 跟随目标 |
| `scale_by_distance` | `ScaleByDistanceAlgorithm` | 距离缩放 |
| `color_gradient` | `ColorGradientAlgorithm` | 渐变着色 |
