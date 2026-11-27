# Kotlin 与 Java 使用指南

用代码创建粒子、编排粒子组和运行发射器。依赖配置见[快速开始](getting-started.md)，
类型索引见 [API 参考](api-reference.md)。

- [绘制形状](#绘制形状)
- [编排式动画](#编排式动画)
- [单粒子](#单粒子)
- [批量生成与粒子集](#批量生成与粒子集)
- [运行时发射器](#运行时发射器)
- [单位、表达式与退场](#单位表达式与退场)
- [动画文件与轨道](animation.md) · [动态光照](lighting.md) · [游戏命令](particle-commands.md)

## 绘制形状

`Draw` 里的每个方法画出一个形状并返回它的 `ParticleGroup`，可以直接链式接编排动画。

通用可选参数：

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| `colorFn` | `ColorSource` | 沿形状参数 t ∈ [0,1] 着色，默认纯白 |
| `scale` | `Float` | 粒子大小，编辑器单位，默认 1 |
| `stagger` | `Int` | 逐粒子入场间隔（tick），第 i 个粒子延迟 i×N 出现，默认 0 |
| `group` | `ParticleGroup?` | 复用已有组而不是新建，默认 null |
| `visual` | `ParticleVisual` | 外观规格：贴图、柔边形状、各向异性、朝向、加色、免光照、光强乘数 |
| `taper` | `Boolean` | 仅 `line` / `curve`：沿线从细到粗、两端渐隐，默认 false |
| `lifeCurve` | `ParticleLifeCurve?` | 逐粒子寿命曲线，片元自己淡入收放 |

```kotlin
val m = ParticleManager.of(level)

// 单颗粒子
Draw.dot(m, Vec3(0.0, 64.0, 0.0))

// 线段：start → end，count 颗粒子
Draw.line(m, start, end, count = 40,
    colorFn = ColorSource.gradient(Color.CYAN, Color.MAGENTA))

// 自由曲线：任意参数函数
Draw.curve(m, { t -> Vec3(t * 10.0, sin(t * PI) * 3.0, 0.0) }, steps = 60)

// 圆周与圆盘（axis 选择所在平面）
Draw.circle(m, center, radius = 4.0, count = 80, axis = Draw.Axis.XZ)
Draw.disc(m, center, radius = 4.0, perimeterCount = 80, layers = 8)

// 三角形与六芒星（两个形状可分别着色）
Draw.triangle(m, center, radius = 3.0, segmentsPerEdge = 30)
Draw.hexagram(m, center, radius = 3.0, segmentsPerEdge = 40,
    colorFn1 = ColorSource.of(Color.RED),
    colorFn2 = ColorSource.of(Color.BLUE))

// 矩形网格（hollow = 只描边）与长方体
Draw.rect(m, center, width = 6.0, height = 4.0, hollow = true)
Draw.cuboid(m, center, width = 6.0, height = 6.0, depth = 6.0, hollow = true)

// 球体
Draw.sphere(m, center, radius = 3.0, count = 400)

// 折线：每段一颗沿线段躺好的贴图，一条调用画完一条丝
Draw.polyline(m, listOf(a, b, c, d), thickness = 0.12f, additive = true, glowing = true)
```

### 贴图与柔边形状

程序化粒子的默认外观是纯白硬边方块。需要柔边圆点、丝线或辉光时通过 `visual` 或形状专用参数给外观：

```kotlin
import work.nekow.particledrawing.api.ParticleStyle
import work.nekow.particledrawing.api.ParticleVisual

// 柔边圆点：黑核、能量球、薄雾一类不能是方块的效果
Draw.sphere(m, center, 1.2, count = 120,
    visual = ParticleVisual().style(ParticleStyle.SOFT_DOT).additive(true).glowing(true))

// 柔边点阵排成一条丝
Draw.line(m, a, b, count = 24, scale = 0.4f,
    visual = ParticleVisual().style(ParticleStyle.SOFT_DOT), taper = true)

// 一条丝 = 一颗粒子（内置线段贴图，长轴自动对齐线段方向）
Draw.polyline(m, points, thickness = 0.1f, taper = true, additive = true, glowing = true)

// 自己登记的贴图，尺寸按世界格给
Draw.polyline(m, points, thickness = 0.2f, texture = "mymod:bolt", additive = true)
Draw.circle(m, center, 3.0, 60, visual = ParticleVisual().texture("mymod:glass").uv(0f, 0f, 32f, 32f))
```

### 波浪入场

使用命令时可通过 `stagger` 和 `enter fade_grow` 配合完成逐颗入场，并用 `cuboid edges` 按棱线顺序绘制。详见[逐粒子入场](particle-commands.md#逐粒子入场)。

`stagger = N` 让第 i 个粒子延迟 i×N tick 出现：

```kotlin
// 200 个球面粒子每隔 2 tick 依次出现，约 7 秒展开
Draw.sphere(m, center, 5.0, count = 200, stagger = 2)
```

### 片元自己入场

形状末尾可以接一条逐粒子寿命曲线，让每颗粒子自己淡入或收放。直接缩组会把形状的间距与半径一起缩掉，
不是片元入场的效果：

```kotlin
Draw.sphere(m, center, 5.0, count = 200,
    lifeCurve = ParticleLifeCurve.of(
        ParticleCurve.alpha(CurveKey.at(0, 0f), CurveKey.at(10, 1f, EasingType.EASE_OUT)),
    ))
```

配合 `stagger` 就是逐颗冒出来。曲线随生成包一次下发，运行期没有逐帧带宽。
曲线按渲染帧的分数游戏年龄采样，1～2 tick 的短尾在高刷下也能走到中间值与零末端；
`CurveChannel.LIGHT` 是独立的光强通道，不会改颜色、透明度或尺寸。

## 渐变着色

`t ∈ [0,1]` 是形状参数：线段为起点到终点的进度，圆周为一圈的进度，球面为顶到底的进度。

```kotlin
// 固定颜色
ColorSource.of(Color.ORANGE)

// 双色插值
ColorSource.gradient(Color.YELLOW, Color.RED)

// 彩虹扫过
ColorSource.rainbow(alpha = 1f)

// 自定义 lambda
Draw.line(m, a, b, 50) { t -> if (t < 0.5) Color.BLUE else Color.WHITE }
```

## 编排式动画

组上的动画方法按时间线排列：`delay(n)` 把游标向前推进 n tick（累加，不清零），紧随其后的动画都在各自
游标时刻触发，连续两个动画共享同一时刻。`fadeIn(10)` 与紧随的 `spin(...)` 都在 t=0 并行推进；
`.delay(100)` 之后的 `stopContinuous()` 与 `fadeOut(20)` 都在 t=100 发生。

### 轴心

轴心是程序级状态：`setPivot(...)` / `followEntity(...)` 绑定一次，对其后的 `rotate` / `spin` / `scale` /
`pulse` 全部生效，直到下一次绑定。这些方法不接受轴心参数，所以「绕实体转」只能靠顺序表达，先绑轴心
再旋转：

```kotlin
group.setPivot(center)                                        // 轴心 = 固定坐标
group.followEntity(owner.uuid, offset = Vec3(0.0, 1.2, 0.0))  // 轴心 = 实体，客户端本地解析
group.spin(Vec3(0, 1, 0), Math.PI / 40)                       // 绕当前轴心转
```

轴心绑定到实体后是活的：组随实体移动，`local = true` 时连朝向一起，组内粒子保持相对轴心的偏移。
`.spin(...)` 写在 `followEntity` 之前时，那一段只绕绑定前的固定点转。

### 可移动轴心

黑洞中心、重力场中心、跟随投射物的法阵这类位置每 tick 变化、又不是实体的轴心，用 `anchor(...)` 绑定
一次，之后逐 tick 更新：

```kotlin
group.anchor(Anchor.Movable(center, velocity))   // 绑定一次，位置与朝向模式
group.updateAnchor(before, now)                  // 相邻两个服务端样本，客户端按 partialTick 插值
```

- 与 `setPivot` 的区别：固定轴心每 tick 挪一次要发一条绑定指令，帧间还会硬跳；`updateAnchor` 只发位置，
  相位与 `track` 粒子一致。
- 朝向：`Anchor.Movable` 的 `Orient.VELOCITY` 让整组随运动方向转向，`Orient.WORLD` 只跟位置。
- 边界：单条样本跳超 8 格（瞬移）或断流超过 3 tick，都按跳变处理，不会在两点之间扫出假轨迹；
  关卡暂停时位置保持，恢复后按新样本继续。
- 实体锚点用 `followEntity`，客户端本地解析，连位置包都不用发。

### 一次性变换

```kotlin
group.move(Vec3(0.0, 2.0, 0.0), durationTicks = 30, easing = EasingType.EASE_OUT)
group.rotate(Vec3(0, 1, 0), radians = Math.PI, durationTicks = 40)   // 绕当前轴心
group.recolor(Color.BLUE, durationTicks = 20)
group.scale(ratio = 2f, durationTicks = 15)        // 在当前倍率之上乘 2，半径与视觉大小同步
group.scaleBy(0.5f, durationTicks = 10)            // scale 的显式名字
group.scaleTo(1.0f, durationTicks = 8)             // 缓动到绝对 1 倍
group.moveAlongOffset(scale = 0.6f, durationTicks = 20)   // 逐成员沿各自相对轴心的方向外移
```

`moveAlongOffset` 是逐成员各自方向的平移（位移 = `scale × |偏移|`，方向取当前偏移，跟着自转一起转），
用于「球面碎片各自沿法线炸开」这类整组一个包就能表达的效果。

缩放的起点是执行那一刻的合成倍率，不是恒定的 1：

- `scaleBy(r)`：终点 = 起点 × r，`scaleBy(0.01f).scaleBy(100f)` 会回到 1 倍。
- `scaleTo(t)`：终点 = t，从任意倍率退场都不会先跳回 1。
- 运行中追加一条缩放，以那一帧的倍率为起点，已有倍率、脉冲与旋转都不被重置。
- `scaleTo(0f, ...)` 表示完全收起，客户端不绘制。固定结构想从零尺寸长开，先 `scaleTo(0f, 0)` 铺成员，
  再 `scaleTo(1f, 6)`。
- `durationTicks = 0` 表示瞬时跳变；`move` / `movePath` 只改渲染位置，不动轴心绑定。
- `stopContinuous` 同样受 `delay` 游标控制，`.spin(...).delay(100).stopContinuous()` 就是转 100 tick 后停。

### 旋转可叠加

多条 `rotate` / `spin` 的角度是累加的，后一条不覆盖前一条的相位：

```kotlin
group.spin(axis, radiansPerTick = 0.02)                          // 一直转
group.rotate(axis, radians = Math.PI / 2, durationTicks = 0)     // 补 90°，不打断自转
```

### 增量追加

程序下发之后录制的指令，其时刻按录制那一刻换算：同一 tick 内连续录制算一次会话，会话里的 `delay`
依次累加；跨 tick 追加则重新起算。长寿组因此可以在运行期继续追加动画：

```kotlin
val g = manager.createGroup(pos)      // 建组、铺成员、录一段出场动画
g.delay(1).fadeOut(durationTicks = 5) // 100 tick 后：1 tick 后开始、5 tick 淡完
```

`fadeOut(removeAfter = true)` 的销毁时刻与客户端用同一套换算，都从当下起算。成员变化触发的全量重发
保留原程序起点，只刷新成员清单和客户端绑定，不会把时间轴重新从当下起算。

### 生命周期与完成信号

```kotlin
group.fadeIn(durationTicks = 15)                 // 透明度 0 → 当前值
group.fadeOut(durationTicks = 20)                // 透明度 → 0 并销毁整组
group.destroyAfter(ticks = 200)                  // 按服务端时钟定时销毁
group.scaleTo(0f, durationTicks = 8).retire()    // 退场到零后由客户端完成信号销毁
group.onAnimationComplete { /* 账本走完时回调 */ }
```

`destroyAfter` 按服务端时钟估时，客户端因网络延迟或时钟锚点晚一点收尾时会切掉还在收尾的画面。
`retire(graceTicks = 2)` 改为等客户端的完成信号（`ProgramCompletePayload`），销毁时刻与视觉真正到零
对齐；客户端不在场或一直不上报时按账本末端 +1 秒兜底销毁。

完成账本是有限时长指令的终点、变量缓动的终点与表达式自身有限时长的并集：

- `setVariableInterpolated` 是一笔「N tick 后到点」的账，最后一次有效缓动跑完即算完成；
- 重定向替换同名旧终点，所以「每 tick 重设新目标」期间不会提前触发上一次的回调；
- 表达式组里糖指令一条都不执行，因此不认它们；要给它终点就用 `expression(code, durationTicks)`
  或一条变量缓动；
- 没有终点的东西不参与：`spin`、无限 `pulse`、`setVariableLive`；
- 账本为空时 `onAnimationComplete` 与 `retire` 都不触发。

缓动到点与上报之间隔了一 tick 的桥接余量，客户端会把刚走完的终点留到上报那一次。服务端排的兜底任务
带登记版本，账本往后挪（追加指令、缓动重定向、立即赋值取消缓动）时会重排，旧任务自动失效。

位置、尺寸、颜色和透明度按渲染帧插值。首帧接管和显式瞬移直接应用新的位置与外观。

### 持续运动

```kotlin
// 无限匀速旋转（绕当前轴心），用 stopContinuous() 停止
group.spin(axis = Vec3(0, 1, 0), radiansPerTick = Math.PI / 40)

// 折线路径：从当前位置出发依次经过各点，easing 作用于全程进度
group.movePath(
    points = listOf(Vec3(0.0, 3.0, 0.0), Vec3(8.0, 3.0, 0.0), Vec3(8.0, 0.0, -8.0)),
    durationTicks = 120,
    easing = EasingType.EASE_IN_OUT,
)

// 呼吸脉冲：1 倍与目标倍率之间往复，cycles = -1 为无限
group.pulse(peakRatio = 1.8f, halfPeriodTicks = 20, cycles = 3)
```

### 实体通道与公式

编排动画以客户端自驱程序执行：链式调用录制为指令流一次性下发，客户端按服务端时钟锚点本地求值并直写
渲染。程序仅投递给追踪组成员的玩家，成员序号 `i/n` 以完整组为准。
迟到或重新进入视距的玩家沿原始程序时间轴继续播放。

```kotlin
// 把实体以名字写进程序注册表，公式里即可被动取值
group.defineEntity(handle = "e", uuid = entity.uuid)
     .followEntity(entity.uuid, offset = Vec3(0.0, 1.0, 0.0))

// 表达式指令：每粒子每 tick 求值一段公式（纯标量走字节码快路径）
group.expression("""
    th = i / n * 2 * PI;
    [x,y,z] = [
        get_entity_x(e) + cos(th) * 2,
        get_entity_y(e) + 1 + get_world_rain() * sin(t * 0.1),
        get_entity_z(e) + sin(th) * 2
    ]
""")
```

表达式和脚本共用同一套解析与优先级：纯标量部分编译成字节码快路径，向量、矩阵、分量访问和完整内建函数
交给同一个表达式解释器；`i/n/t`、`[x,y,z] = ...` 这些写法保持不变。用到什么取什么，
`get_entity_*` / `get_world_*` 在需要处调用，不必预先声明属性。

### 字幕与形状切换

`Draw.textPoints(text, center, count, height)` 按笔画生成恰好 `count` 个世界坐标，直接用作形变目标。
`height` 以格为单位，宽度随字形比例变化，支持换行。默认使用运行该代码的机器上的无衬线粗体。
缺少中文等字形时会明确报出字符码，可在最后一个参数传入字体，服务端也可以从模组资源用
`Font.createFont(Font.TRUETYPE_FONT, stream)` 读取 TTF 或 OpenType 字体。客户端接收坐标即可绘制。

```kotlin
val points = Draw.textPoints("变换形状", center, count = 602, height = 2.4)
group.morph(points)
```

Java 可调用 `Draw.textPoints("变换形状", center, 602, 2.4)`，也可传入第五个 `Font` 参数。

`ParticleGroup.morph(points, options)` 默认接收 `List<Vec3>` 世界坐标，可直接使用文字、图片或模型的采样点。
单次可传 1–8192 个目标，粒子与目标等量时，每个目标恰好分到一颗。多余的粒子停在当前点。
`MorphOptions(space = MorphSpace.LOCAL)` 把槽位改成相对组锚点的偏移：同一份文字或形状可以原地落成，
组移动时目标跟着平移，不必把锚点坐标写进坐标里。
给每个落点配置外观时，使用 `morphSlots(List<ParticleMorphSlot>, MorphOptions)`，Java 和 Kotlin 均可调用。
`yaw` 和 `pitch` 以度为单位，分别绕 Y 轴和 X 轴走最短角差。面向摄像机的粒子相对相机旋转，固定朝向的粒子以世界 +Z 为基准。
`size` 使用 `ParticleSpawnSpec.scale` 的单位，等比缩放粒子。省略的属性沿用当前值。

公式重载通过 `MorphOptions(writeAttributes = true)` 配合 `yaw`、`pitch`、`size` 公式使用；省略槽位数用
`group.morph(position, options)`（或命令里的 `slots all`），槽位按当前成员数生成，整组一起过渡。
需要每刻更新目标时，可同时启用 `dynamicSlots`。
组表达式会接管位置，使用形变的成员应放在专用粒子组里。

### 脚本层形变

脚本可通过 `morph(slots, options)` 让本层存活粒子移动到槽位。先生成粒子，再在 `setup` 或换目标时调用一次：

```js
func setup() {
  this.spawn({ position: [-2, 0, 0] })
  this.spawn({ position: [2, 0, 0] })
  morph([vec3(0, 2, -1), vec3(0, 2, 1)], { ticks: 20, easing: "in_out_quad" })
}
```

20 刻为 1 秒。形变按动画时钟推进，渲染帧在整刻之间取样。后续调用从当时的位置重新分配；途中可以通过
`allocation`、`stagger`、`max_speed` 与 `wobble` 组合分配、错峰、限速与扰动。

| getter | 含义 |
| --- | --- |
| `get_entity_x/_y/_z(h)` | 实体坐标分量 |
| `get_entity_pos(h)` | 整取坐标，仅限 `[x,y,z] = get_entity_pos(h)` 独占赋值形态 |
| `get_entity_exists(h)` | 实体是否在场（0/1；缺失时该实体其余取值同为 0） |
| `get_entity_yaw/_pitch(h)` | 朝向角（MC 原始度数） |
| `get_entity_dirx/_diry/_dirz(h)` | 单位视线向量 |
| `get_entity_vx/_vy/_vz(h)` | 速度（格/tick，按相邻 tick 位置差分，首 tick 为 0） |
| `get_entity_hp/_hp_max(h)` | 当前与最大生命值（仅生物，其他实体为 0） |
| `get_entity_ground/_sneaking/_on_fire/_swimming/_sprinting(h)` | 状态标志（0/1） |
| `get_world_day_time()`、`get_world_game_time()` | 主世界当日刻与总刻 |
| `get_world_rain()`、`get_world_thunder()` | 降雨与雷暴强度（0~1，含平滑过渡） |
| `get_world_moon_phase()` | 月相序号 0~7 |

参数 `h` 是 `defineEntity` 定义的句柄名，也可以是注册序号，必须是编译期常量。名称未定义或句柄未登记时
编译期报错，服务端下发时另有预警日志。属性词表是封闭枚举 `EntityProp` / `WorldProp`。

`expression` 一旦出现即进入表达式模式，接管位置、颜色与缩放的最终解释权，`fadeIn` / `fadeOut` 因子仍
叠加在其 alpha 上。协议里只有数据，不向客户端发送代码字节。表达式模式下糖指令一条都不执行。

### 变量热更

```kotlin
group.setVariableLive("speed", "2")                          // 直接给常量
group.setVariableLive("rad", "speed * 2")                    // 标量公式，可引用其它程序变量
group.setVariableInterpolated("bx", "targetX", ticks = 10)   // 10 tick 内缓动到目标
```

`setVariableLive` 的公式走程序变量作用域，不注入 `t/i/n`，不能引用时间与序号；它会取消同名变量正在进行
的缓动。`setVariableInterpolated` 与它共用求值环境，收到那一刻算出目标值，但不瞬移，客户端从当前值缓动
过去，`easing` 可选，`ticks = 0` 等于立即赋值。变量常被当作空间端点使用（光束末端、场中心），立即赋值
会让整段几何跳一下，需要连续就用它。这条缓动同时进完成账本。

### 组合示例

```kotlin
Draw.circle(manager, center, radius = 3.0, count = 200)
    .fadeIn(10)                                    // t=0    渐显
    .scale(2f, durationTicks = 15)                 // t=0    放大到 2 倍
    .spin(Vec3(0, 1, 0), Math.PI / 40)             // t=0    开始无限旋转
    .delay(100)                                    // 游标推到 100
    .stopContinuous()                              // t=100  停转
    .fadeOut(20)                                   // t=100  渐隐并销毁
```

```kotlin
// 魔法阵：圆与六芒星共用一个组
val circle = manager.createGroup(center)
Draw.circle(m, center, 3.0, count = 90, colorFn = ColorSource.of(Color.BLUE), group = circle)
Draw.hexagram(m, center, 3.0,
    colorFn1 = ColorSource.of(Color.WHITE),
    colorFn2 = ColorSource.of(Color.LIGHT_GRAY), group = circle)

circle.fadeIn(20)
    .spin(Vec3(0, 1, 0), Math.PI / 60)
    .delay(60)
    .move(Vec3(0.0, 4.0, 0.0), 40, EasingType.EASE_IN_OUT)
    .pulse(1.3f, halfPeriodTicks = 12, cycles = 2)
    .delay(30)
    .stopContinuous()
    .fadeOut(25)
```

已经创建的粒子也可以 `group.add(handle)`：PD 会把 `ParticleData.groupId` 从无组/旧组改到新组，延迟
生成还没落地的成员则预挂组，出生时直接带组；程序已下发时按当前完整成员和历史指令重发全量，不要求
改用 `Builder.group`。既有粒子从旧组改挂时，新程序接管后旧程序不再写这颗粒子。

## 单粒子

`ParticleManager.create()` 返回生成规格 Builder。`spawn()` 因维度容量不足而被拒绝时返回 `null`。

```kotlin
val handle = manager.create()
    .position(x, y, z)
    .color(Color.ofHsb(0.6f, 0.9f, 0.9f))
    .scale(0.4f)
    .lifetime(-1)                  // -1 = 永存
    .offsetFromPivot(dx, dy, dz)   // 相对组轴心的偏移
    .spawn() ?: return             // 达到维度上限时为 null

handle.move(target, 20, EasingType.EASE_OUT)
handle.recolor(Color.WHITE, 10, EasingType.LINEAR)
handle.resize(2f, 10, EasingType.LINEAR)
handle.setVelocity(Vec3(0.0, 0.1, 0.0))
handle.moveInstant(pos)
handle.remove()
```

光源设置与缓动见[动态光照](lighting.md)。

延迟生成的句柄在出生前暂无权威位置，运行时更新从出生后生效。

### 外观

贴图、UV、各向异性、朝向和混合方式随 `spawn()` 下发。要替换这些规格，调用 `remove()` 后重新生成。
外观可通过 Builder 单独设置，也可传入 `ParticleVisual`。

速度拉丝、贴地软化和丝带共用粒子外观。可把同一个规格交给普通粒子、批量生成、发射器或 `ParticleRecipe`。

```kotlin
val cometAppearance = particleVisual {
    style(ParticleStyle.SOFT_DOT)
    worldSize(0.24.blocks, 0.1.blocks)
    velocityStretch(0.8)
    softFade(0.5.blocks)
    trail(16)
}
manager.create().position(p).visual(cometAppearance).lifetime(80).spawn()
```

移动的粒子会保留最多 32 刻的尾迹。配置全量绘制距离 `particleLodDistance` 可减少远处实例的绘制量。

```kotlin
// 柔边圆点 + 免光照 + 加法混合
manager.create().position(p).scale(0.5f).lifetime(60)
    .style(ParticleStyle.SOFT_DOT)
    .glowing(true)
    .additive(true)
    .spawn()

// 一条丝线：长轴沿 a→b 躺好，长 3 格、粗 0.1 格
manager.create().position(mid).lifetime(40)
    .texture("mymod:bolt")
    .scaleWorld(3.0f, 0.1f)
    .alignTo(a, b)
    .additive(true)
    .spawn()

// 从图集里取一格（贴图像素坐标）并固定朝向
manager.create().position(p).texture("mymod:sheet")
    .uv(16f, 0f, 32f, 16f)
    .billboard(false)
    .spin(Math.PI / 3)             // 平面内 60°；三轴版本 spin(rx, ry, rz)
    .spawn()

// 逐粒子生成延迟：spawn 立刻返回句柄，粒子推迟 20 tick 出现
manager.create().position(p).delay(20).spawn()
```

尺寸口径：

| 入口 | 单位 | 换算 |
| --- | --- | --- |
| `scale(Float)`、`scale(w, h)` | 编辑器单位 | 渲染整宽 = 值 × 0.2 格 × 贴图尺寸系数 |
| `scaleWorld(w, h)` | 世界格 | 直接是整宽与整高，`Draw.polyline` 用的就是它 |

贴图尺寸系数 = 取景框最长边 / 16，`uv` 时的取景框就是那个子矩形。16px 贴图的系数为 1（内置形状都是
16px），32px 贴图会把尺寸放大到 2 倍，想精确控制世界尺寸就一律用 `scaleWorld`。

### 贴图登记与下发

```kotlin
ParticleManager.registerTexture("mymod:glow", pngBytes)   // 幂等，字节上限 1 MiB，名字上限 256 字符
```

登记成功后自动同步 PNG。客户端主线程完成 GPU 解码，在线玩家收到新贴图，晚加入的玩家收到已登记的贴图。
返回 `true` 表示登记已接受。客户端在贴图可用前用纯白方块和 16px 尺寸基准渲染，到达后按实际尺寸重算。

内置形状不必登记，`ParticleStyle.SOFT_DOT` / `LINE` 在两端各自生成同一份像素；需要提前确认可用时调
`ParticleManager.registerBuiltinTextures()`，它同样把生成排到客户端主线程，返回后不保证当帧已可用。两张内置贴图的可见尺寸比标称略小：

| 形状 | 可见比例 | 用途 |
| --- | --- | --- |
| `SOFT_DOT` | 直径约 0.75 × 尺寸 | 密铺成实心球体，间距不超过 0.3 × 直径就不会有方块感或空隙 |
| `LINE` | 粗细约 0.7 × 尺寸，两端各约 0.2 × 长度渐隐 | 一条丝 = 一颗粒子，相邻段接缝处略暗 |

`ParticleVisual` 是可变的链式对象，多颗粒子想共用一份外观再各改一点时用 `copy()`。

为已启用的拖尾设置颜色、粗细和独立淡出时间，可在 Kotlin 或 Java 中使用
`ParticleVisual.trailStyle(ParticleTrailStyle(...))`。两端颜色和宽度按实际路径长度插值。

```kotlin
val visual = ParticleVisual().apply {
    trailTicks = 24
    trailStyle = ParticleTrailStyle(30, 0.2f, 1.5f, Color.CYAN.withAlpha(0.25f), Color.WHITE)
}
```

配方可通过 `ParticleSources.startInstanced` 批量绘制透明粒子和 `visual.model` 网格。绘制帧从相邻两刻的实例数据插值得到外观。

### 原版与模组粒子贴图

`particle:命名空间:粒子名称` 从客户端当前资源包的 `particles/<名称>.json` 中读取全部帧，
合成为 PD 的帧图集。有限寿命按 `floor(年龄/寿命 × (帧数-1))` 选帧，和原版的年龄选帧口径一致；无限寿命默认
每 60 tick 循环一次。时钟来自粒子的游戏年龄，暂停游戏会暂停换帧。

可选后缀：`particle:minecraft:end_rod#3` 固定使用第 4 帧（从 0 开始），
`particle:minecraft:end_rod@8` 则按 8 FPS 循环。显式帧率模式按经过时间选帧，寿命到点仍会销毁。
资源包替换与 F3+T 重载会自动更新贴图。
有贴图列表的其它模组粒子也使用同样的语法。
整张资源贴图按标准图标尺寸计算，资源包的像素密度不会改变粒子大小；显式给 `uv` 时仍按像素取子矩形。

这复用原版贴图序列与年龄选帧方式，不执行原版粒子工厂的随机漂移、颜色渐变或物理逻辑；颜色、尺寸、
发光和淡入淡出继续使用 PD 的参数与编排。`end_rod` 的明亮外观配合 `glowing`，`light=0` 则避免向世界投光。
没有贴图列表、帧号越界或客户端缺资源时，会显示紫黑错误贴图并记录具体原因。

动态图集最多 256 帧、合计 16,777,216 像素，各帧需为相同尺寸。

```kotlin
val visual = ParticleVisual().particleTexture("minecraft:end_rod").anisoWorld(0.12f, 0.12f).glowing(true).lightLevel(0)
Draw.circle(manager, center, 2.0, 80, visual = visual)

// 同样适用于粒子构建器、发射器，以及可读外观 DSL
val textureName = ParticleTextures.of("minecraft:end_rod", 3)
val look = particleVisual { particleTexture("minecraft:end_rod"); fullBright = true }
```

### 寿命曲线

淡出与收缩曲线随生成包下发。曲线值是乘数，缺省为 1。时刻从粒子出生起按 game tick 计算。

```kotlin
// 拖尾颗粒：活 20 tick，最后 8 tick 淡出并缩到 0.2 倍
manager.create().position(p).scale(0.4f).lifetime(20)
    .style(ParticleStyle.SOFT_DOT)
    .fadeOut(8)                  // 也能给缓动：fadeOut(8, EasingType.EASE_IN_QUAD)
    .shrinkTo(0.2f, 8)
    .spawn()

// 逐通道给关键帧，段内用后一关键帧的缓动
manager.create().position(p).lifetime(40)
    .alphaCurve(CurveKey.at(0, 1f), CurveKey.at(24, 1f), CurveKey.at(40, 0f, EasingType.EASE_IN))
    .sizeCurve(CurveKey.at(0, 1f), CurveKey.at(40, 0.3f))
    .colorCurve(
        listOf(CurveKey.at(0, 1f), CurveKey.at(40, 0.2f)),   // 红
        listOf(CurveKey.at(0, 0.8f), CurveKey.at(40, 0.1f)), // 绿
        listOf(CurveKey.at(0, 0.6f), CurveKey.at(40, 0f)),   // 蓝
    )
    .spawn()
```

- 通道包括 `ALPHA`、`SCALE`、`RED`、`GREEN`、`BLUE`、`LIGHT`。同一通道的多条曲线相乘。
- `curve(channel, keys)` 是通用入口，`alphaCurve` / `sizeCurve` / `colorCurve` 是现成写法。
- `fadeOut` / `shrinkTo` 锚在寿命末尾，需要有限寿命，`lifetime(-1)` 与它同用会明确报错。
- 客户端按渲染帧刷新带曲线的粒子外观，只改颜色与缩放，不动位置。
- 曲线与 `track` 可以同时用：`track` 只接管位置，外观仍由曲线逐帧驱动。

### 首帧插值端点

`track` 的粒子由原版按 partialTick 在相邻两条权威位置之间插值，而 `spawn` 的粒子直接钉在当前位置，
「每 tick 采样、逐颗铺尾迹」会出现最多一整 tick 的错位。给上上一 tick 的位置，第一帧就从它扫到当前
位置，两边语义一致：

```kotlin
// 投射物每 tick 采样一次，尾迹颗粒带着上一 tick 的位置出生
manager.create().position(now).prevPosition(before)
    .scale(0.3f).lifetime(10).fadeOut(6).spawn()
```

不给 `prevPosition` 就是跳变出生。延迟生成没有上一 tick 可言，与 `prevPosition` 同用时后者不生效。

### 逐 tick 跟随与力驱动

`track` 直设位置，没有缓动。客户端每个 tick 消费一条，原版按 partialTick 在相邻两条之间插值；某个 tick
没收到新位置时端点原地保持，不会退回上一段起点。

```kotlin
handle.track(pos.x, pos.y, pos.z)
handle.position()      // 只读回服务端的权威位置，粒子不存在时返回 null
```

逐粒子每 tick 一个包在多粒子场景下太贵，用批量接口：

```kotlin
val ids = handles.map { it.id }
manager.trackAll(ids, positions)                      // 按顺序一一对应，长度不同按短的截断
manager.setVelocityAll(ids, velocities)
manager.applyForceAll(ids, accelerations, ticks = 1)   // 各自加速度，共用一个 ticks
```

需要沿一个力运动而不是逐 tick 报位置时用 `applyForce`：只在开始施力时下发一次，之后两端按同一规则逐
tick 积分。

```kotlin
handle.applyForce(Vec3(0.0, -0.05, 0.0))     // 无限施力，直到被下一次指令覆盖
handle.applyForce(accel, ticks = 20)         // 只施 20 tick，之后按惯性继续
handle.applyForce(Vec3.ZERO, ticks = 0)      // 清除力，速度保留
handle.setVelocity(Vec3.ZERO)                // 连速度一起停住
```

批量施力的 `ticks` 全组共用，默认 1（只施这一 tick）；要长效施力显式给 -1。

### 实体锚点

```kotlin
handle.attachTo(entity.id, Vec3(0.0, 0.9, 0.0))        // 世界空间偏移
handle.attachToLocal(entity.id, Vec3(0.0, 0.0, 0.6))   // 偏移随实体朝向
```

服务端只在挂载时下发一次，之后位置由客户端每 tick 本地解析。`move` / `moveInstant` / `track` /
`setVelocity` / `applyForce` 都接管位置并解除锚点；`applyForce` 与 `setVelocity` 叠加，力不覆盖已有速度。
服务端暂停（单人按 Esc）时客户端不再推进速度与力粒子，恢复后两端从同一状态继续。

| 入口 | 身份 | 偏移语义 | 粒度 |
| --- | --- | --- | --- |
| `ParticleHandle.attachTo(entity / uuid / entityId, offset)` | 实体（uuid 优先） | 世界空间，相对实体脚底 | 单粒子 |
| `ParticleHandle.attachToLocal(...)` | 同上 | 实体局部，随朝向 | 单粒子 |
| `ParticleGroup.followEntity(uuid, offset, local)` | 实体 UUID | 组轴心相对实体的偏移 | 整组 |
| `ParticleGroup.defineEntity(handle, uuid)` | 实体 UUID | 供公式 `get_entity_*(handle)` 取值 | 表达式 |

两段偏移不要隐式相加：要「实体 + 轴心」两层，先用 `followEntity` 把轴心钉在实体上，再用粒子的
`offsetFromPivot(hx, hy, hz)` 表达相对轴心的位置。单粒子要贴实体就直接 `attachTo` / `attachToLocal`。

实体不在场（未加载、已消失、换维度）时位置保持不动，实体再出现即继续跟随；锚点不会替粒子决定寿命。

## 批量生成与粒子集

逐颗 `spawn()` 是一颗一个包。一条尾迹或一次爆发用 `ParticleSpawnSpec` 攒好一批，一次包发完，每玩家按
可见性裁剪，单包最多 256 条：

```kotlin
val specs = (0 until 12).map { i ->
    ParticleSpawnSpec.at(base.add(dir.scale(i * 0.15)))
        .scale(0.3f).lifetime(10)
        .color(200, 220, 255)
        .fadeOut(6)
        .visual(ParticleVisual().style(ParticleStyle.SOFT_DOT).additive(true))
}

manager.spawnAll(specs)       // 一次包，返回与 specs 一一对应的句柄
val batch = ParticleBatch(manager)
batch.spawnAll(specs)         // 直接建一个粒子集
```

`ParticleSpawnSpec` 与 Builder 的字段一一对应。服务端按维度上限逐条判定，被拒绝的那些在返回值里是
`null` 或不计入成员数。

需要成员逐 tick 增删的粒子流（黑洞吸入、重力场下落、跟随实体的一圈粒子）用 `ParticleBatch`。它是程序化
路径，编排动画用 `ParticleGroup`。

```kotlin
val swarm = ParticleBatch(manager)

// 每 tick 补一个，被维度上限拒绝时立即返回，下次再补
swarm.ensureSize(60) { i ->
    manager.create().position(shellPoint(i)).color(purple).scale(0.3f)
        .lifetime(60).spawn()?.applyForce(centripetal(i))
}

// 按 PD 的权威位置与速度判断，不用自己维护 id 与状态表
swarm.removeIf { _, pos, _ -> pos.distanceTo(center) < 0.1 }
swarm.forEach { i, handle, pos, vel -> /* 每 tick 自己算位置也可以 */ }

swarm.trackAll(newPositions)                  // 一次包覆盖全组，成员顺序 = add 顺序
swarm.setVelocityAll(velocities)
swarm.applyForceAll(accelerations, ticks = 1)
swarm.trackEach { i, handle -> nextPos(i) }
swarm.clear()
```

成员在 PD 侧过期或被销毁后自动出列（`evictDead()`，其他操作里也会顺带做）。

## 运行时发射器

逐 tick 采样、逐颗 spawn 加逐颗更新包的写法有两个毛病：出现时刻被 tick 量化，带宽还是 O(粒子)。发射器
把这件事下沉成机制：服务端声明一次，客户端按渲染帧用插值后的锚点位置自己发射。

```kotlin
val trail = manager.emitter(Anchor.Movable(pos, velocity))   // 也可以 Anchor.Fixed / Anchor.Entity
    .spacing(0.15)        // 按里程：每 0.15 格一颗
    .life(10)             // 每颗活 10 tick
    .scale(0.4f)
    .color(Color.WHITE)
    .style(ParticleStyle.SOFT_DOT)
    .fadeOut(8)
    .shrinkTo(0.2f, 8)
    .spawn()

// 投射物每 tick 挪一次锚点，只在变更时发包
trail.updateAnchor(Anchor.Movable(newPos, newVel))
trail.spacing(0.1)        // 密度按速度自适应
trail.interval(2)         // 或改成每 2 tick 一颗，intervalMs 可给到渲染帧粒度

trail.stop()              // 停止发射，已生成的粒子各自走完寿命
trail.isActive()
```

- 两种口径：`spacing(格)` 按锚点走过的里程发射，位置落在段内等距点上，与帧率无关；`interval(ticks)` 与
  `intervalMs(ms)` 按时间发射。
- 逐颗抖动：`jitter(格)` 让每颗垂直运动方向随机偏开（圆盘内均匀），`offsetAlong(格)` 整条前后挪。偏移由
  发射器 id 与第几颗的哈希算出，同一声明在任何客户端上第 N 颗的偏移相同。
- 锚点插值：客户端每渲染帧取锚点本帧位置，实体取 `getPosition(partialTick)`，可移动锚点按相邻两个服务端
  样本插值，与 `track` 粒子同相位。瞬移（单条样本跳超 8 格）与断流（超过 3 tick 没有样本）都按跳变处理，
  不会在两点之间扫出假轨迹；Esc 暂停时不推进。
- 外观与曲线：`color` / `scale` / `style` / `texture` / `uv` / `aniso` / `billboard` / `spin` / `alignTo` /
  `additive` / `glowing` / `lightLevel` / `lightIntensity` 与 Builder 同一套语义，曲线同理。
  `velocity(...)` 给每颗粒子一个出生速度，默认静止。
- 运行期改参数：`spawn()` 返回的句柄上重新调同名方法即可，变更走分档小包，已生成的粒子不受影响。锚点
  变更只发锚点段，密度变更只发口径段，其余整份参数替换。
- 上限：`maxAlive(n)`（默认 4096）限制单个发射器同时存活的粒子数，客户端另有全局
  `maxRenderParticles` 兜底。到客户端上限时新粒子被丢弃，日志每 10 秒汇总一条并给出被丢颗数。
- 后进服、切维度、走进范围的玩家由 PD 补发声明，不会看到一条空尾迹。
- 拖尾长度按格给、寿命等于长度除以速度、总强度倍率这类游戏语义仍由调用方计算，发射器只提供机制。

### 发射预算

帧时间基本由「这一帧要处理多少颗粒子」决定。客户端配置（`config/particledrawing-client.toml`）里
按下面的顺序降，足够救回大多数机器：

| 键 | 默认 | 作用 |
| --- | --- | --- |
| `maxRenderParticles` | 1000000 | 客户端渲染管线里的粒子总数上限，先降这个（例如 12000） |
| `maxDynamicLights` / `maxDynamicLightsPerCell` | 4096 / 256 | 动态光源数量，再降这个（例如 128 / 4） |
| `dynamicLightMaxDistance` | 64 | 光影响半径，调小会明显减少区块重烘焙 |
| `enableDynamicLights` | true | 还卡就整个关掉；没有发光粒子时这一整段本来就会每帧跳过 |
| `particleBatchSize` | 65536 | 每 tick 同步多少颗粒子的缓动（公平轮转，掉到很小时缓动会变慢，不要先降它） |

配合光影包时另有三处可单独关掉的兼容开关（`customAdditivePipeline`、`sceneMaterials`、`terrainDynamicLights`），
见[光影包](known-issues.md#光影包)。

粒子被截断时会看到日志里「渲染粒子上限已满」的提醒，说明是上限而不是效果没生成。

想知道到底卡在哪一段，把 `debugLogFps` 打开（`[debug]` 段，默认关）：每 5 秒一行
`[PD-perf] fps=… avg=…ms worst=…ms | particles=… lights=…`，单刻推进超过 50ms 时还会多一行
`[PD-perf] tick=…ms`。这两行能把「粒子太多」和「单刻求值太慢」区分开。

### 配方生命周期

`ParticleSources.start` 启动客户端模拟。需要让晚进服的玩家看到已经发生的碰撞和仍在播放的尾迹时，调用
`ParticleSources.startSynced(level, recipe, anchor)`。单轮播放传入 `burst = true`。服务端按同一套配方规则推进，
进入视距的客户端从当前状态开始显示。配方的 `rate` 和 `maxAlive` 均须不超过 64。
返回的句柄支持停止出生、更新变量与移除实例。详细限制见[服务端同步](particle-commands.md#服务端同步)。

持续配方设置 `simulationRange` 与 `preserveSimulation = true` 后，可在相机远离时暂停并保留粒子状态。
同材质的全亮加色四边形可通过 `ParticleSources.startInstanced` 批量绘制，弹道爆发可用
普通 `ParticleSources.burst` 可用 `ParticleRecipe.strandSamples` 将同组相邻采样点连接为连续柔边光带。
`ParticleSources.burstGpu` 在 GPU 求匀加速运动。见[实例绘制与 GPU 弹道](particle-commands.md#实例绘制与-gpu-弹道)。

发射器的公式在启动时会被编译成一段直接读写寄存器的指令序列，逐粒子逐刻只做数组访问，
不再按名字查表。写配方时留意两点，避免掉回慢路径（约慢 5～10 倍）：

| 写法 | 走哪条路 |
| --- | --- |
| 标量运算、比较、`&&` / `||`、常用数学函数、`let` 标量中间量 | 热路径 |
| `position` / `velocity` / `acceleration` 直接写 `vec3(a,b,c)` 或 `[a,b,c]` | 热路径（自动拆成分量） |
| `let v = vec3(...)` 之后再取 `v.x`、矩阵、`.len()` 之类的方法调用 | 回退解释器 |

回退不是错误，只是慢；`where` / `kill` 里的比较和逻辑运算同样在热路径内。

比较和逻辑运算（`< > == != && || !`）的结果是布尔值，只有 `where`、`kill`、`glow` 这三处是按真假用的，
可以随便写；写在别的输出里（比如 `r = x > 0`）整条配方会回退到解释器，并且和改动前一样报类型错。

实测参考（7800 XT、2 万颗、全部逐刻重算公式）：黑洞吸积 240–260 帧，升级动画的环 100–110 帧，
单刻推进都在 4ms 以内。

**写运动公式时容易踩的坑**：`motion acceleration` 是「每刻都施加一次的加速度」，写一个恒定的切向分量
会让速度无界增长，几秒内粒子就被甩出视野（表现是画面瞬间变空、帧率虚高）。要做环绕运动，应该在
`birth velocity` 里给一次切向初速度，`motion acceleration` 只留向心分量；需要吸积再给速度一点阻尼或
向内分量，见[粒子命令](particle-commands.md)里的发射器公式段。

### 将配方绑定到实体事件

在服务端线程调用 `EntityParticleEffects`。例如为一个生物登记死亡时的爆发，`recipe` 使用有限寿命。

```kotlin
EntityParticleEffects.bind(
    entity,
    "example_death",
    events = mapOf(EntityParticleEffects.DEATH to EntityParticleBurst(recipe)),
)
```

需要持续发射时传 `emission = recipe`，依附实体生成的位置使用实体锚点。内置 `IMPACT` 使用投射物实际碰撞点，`DEATH` 使用生物死亡位置。模组自己的事件可以用 `ResourceId("example", "signal")` 作映射键，并在事件发生时调用 `EntityParticleEffects.trigger(entity, event, position)`。命令模板可用同一个 `example:signal` 订阅它。

每个实体可拥有多个名字不同的绑定。默认每种事件只触发一次。要重复爆发则设置 `each = true`。设置 `stopEmission = true` 可在事件发生时停止同一绑定的持续发射。可用 `unbind(entity, name)` 解除绑定；实体退出关卡时也会清理。

## 缓动

预设：`LINEAR`、`EASE_IN` / `EASE_OUT` / `EASE_IN_OUT`，以及 `*_QUAD` / `*_CUBIC` / `*_BOUNCE` /
`*_ELASTIC` 变体，共 14 种。无缓动用 `EasingType.NONE`，保持前一关键帧值直到下一关键帧。自定义三次
贝塞尔：

```kotlin
val custom = EasingType.custom(0.68, -0.55, 0.265, 1.55)   // (x1, y1, x2, y2)
```

## 绘制示例

```kotlin
// 上升的螺旋烟雾环
fun smokeRing(m: ParticleManager, c: Vec3) {
    Draw.curve(m, { t ->
        val ang = t * Math.PI * 6
        Vec3(c.x + cos(ang) * 3.0, c.y + t * 8.0, c.z + sin(ang) * 3.0)
    }, steps = 150,
       colorFn = { t -> Color.ofHsb(0.55f, 0.2f, (1 - t).toFloat()) },
       scale = 0.3f, stagger = 3)
        .fadeIn(10)
        .destroyAfter(160)
}

// 双组并行：两条链式调用天然并行推进，不需要额外编排器
fun dance(m: ParticleManager, c: Vec3) {
    val inner = Draw.circle(m, c, 2.0, 50, colorFn = ColorSource.of(Color.CYAN))
    val outer = Draw.sphere(m, c, 4.0, 250, scale = 0.25f)

    inner.fadeIn(10).spin(Vec3(0, 1, 0), Math.PI / 30)
    outer.fadeIn(30).spin(Vec3(0, 1, 0), -Math.PI / 90)
         .pulse(1.15f, halfPeriodTicks = 15)
}

// 扩散涟漪
fun ripples(m: ParticleManager, c: Vec3, waves: Int) {
    repeat(waves) { i ->
        Draw.circle(m, c, radius = 1.0 + i * 1.5, count = 50)
            .fadeIn(5)
            .delay(i * 15)          // 每道波错开 15 tick
            .fadeOut(20)
    }
}
```

## 单位、表达式与退场

Kotlin 使用扩展属性构造单位值，Java 使用同名静态工厂。

```kotlin
val wait = 6.ticks              // 300ms
val same = 300.milliseconds     // 6t
val width = 1.5.mcPixels        // 1.5/16 格
val turn = 90.degrees
val spin = 1.2.radiansPerSecond
val life = Lifetime.ticks(20)   // 或 Lifetime.infinite()
```

`AngularSpeed` 的表达式内部统一为弧度/毫秒：`1.radiansPerSecond * 1.seconds` 的常量与生成文本都等于
1 弧度，`Angle / Time` 也走同一口径。所有生成数字字面量都展开成普通十进制，极小频率和大坐标不会
变成 `E` 指数被求值器误读。

外观、曲线与退场：

```kotlin
val appearance = particleVisual {
    style = ParticleStyle.SQUARE
    worldSize(1.5.mcPixels, 1.mcPixels)
    fullBright = true                 // 片元免光照
    light = Light.none()              // 不向世界照明；Light.level(14) 才投光
    blend = Blend.ALPHA
}

val envelope = lifeCurve {
    scale {
        start(0.0)
        to(1.0, 1.5.ticks, Ease.OUT)
        hold(8.ticks)
        to(0.0, 12.ticks, Ease.IN_OUT)
    }
}

batch.exit {
    particleSize.to(0.0, 8.ticks, Ease.LINEAR)   // 逐成员收片元，不缩组
    preserveMotion()                             // 保留径向速度/位置
    removeWhenComplete()
}
```

`fullBright` 与 `light` 分开：`Light.level` 设置向外照明，`Light.none` 只保留全亮；
`ParticleVisual` 的 `emitsLight` 随生成包同步，旧 API 的 `glowing` 行为不变。
`exit` 的删除由客户端回报“每个成员的实际尺寸过渡已到端点”后触发；重复 `exit` 会递增版本，
旧回报按版本丢弃，离线/无同时追踪全部成员的玩家时按退场时长 + 兜底余量清理。

### 类型化表达式

组公式输出位置、颜色和缩放，速度或光照由 `ParticleRecipe` 的出生和运动公式设置。
位置应明确使用 `position.world` 或 `position.local`。组公式无法保留逐粒子的初始位置。
先在组上声明向量和方向参数，再用 DSL 构建组公式。

```kotlin
val origin = group.vectorParameter("origin", center).value
val direction = group.directionParameter("direction", forward).value

group.expression(duration = 5.ticks) {
    val progress = progress(5.ticks)
    val random = member.random01(seed = 1234L, salt = "spread")
    val angle = member.index * Angles.goldenAngle
    val width = sin(progress * 180.degrees) * lerp(0.08.blocks, 0.24.blocks, random)
    val distance = progress * lerp(0.25.blocks, 0.70.blocks, random)

    position.world(origin + direction * distance +
        vec3(cos(angle) * width, 0.5.blocks, sin(angle) * width))
    scale(smoothstep(0.0.toScalar(), 1.0.toScalar(), progress) * square(1.0.toScalar() - progress))
    color(Color.WHITE)
}.retire()
```

- 参数句柄用 `.set(Vec3)` / `.set(Vec3, over = 4.ticks, easing = Ease.IN_OUT)` 更新；
  `directionParameter` 自动归一化。
- `member.index` / `count` / `random01`、`progress`、`entity("holder")`、`world(WorldProp...)`
  都是类型入口；实体属性需要先 `group.defineEntity("holder", uuid)`。
- 必须显式调用 `position.world` 或 `position.local`。`position.local` 使用当前组锚点，支持固定、实体和可移动锚点；
  `position.preserve()` 仍会报错，不会偷偷补世界原点。
- 复用的随机值由 `i + seed + salt` 决定，暂停、回绕和重复求值不跳变；固定种子可复现。
- `group.buildExpression(...)` 返回 `TypedExpression`，`debugString()` 可查看生成文本、参数名与单位。
- 该入口覆盖组标量表达式的位置/颜色/缩放，另有 `pow`、`phase/start` 与 `local`；逐粒子速度、全亮和光照输出使用 `ParticleRecipe` 的 birth/motion。
  Java 可用 `Expressions.builder(duration)` 生成位置/缩放/颜色文本，或直接调用 `group.expression(code, ticks)`。

动态外观、阶段与共享局部值：

```kotlin
group.expression(duration = 8.ticks) {
    val cooldown = start(8.ticks)
    val presence = local("presence", 1.0.toScalar() - cooldown.progress)
    position.world(vec3(0.blocks, 1.0.blocks, 0.blocks))
    scale(pow(presence, 0.75))
    color(presence, presence, Scalar.of(0.5), Scalar.of(1.0))
}
```

`start(duration)` / `phase(duration, offset)` 都读同一组表达式时间 `t`，两个阶段可在同一配方里共享相位；
`local(name, value)` 会在生成文本开头赋值一次，后续复用变量名，调试导出会列出局部表达式。

需要“施法后才开始、可提前结束、可重入”的阶段用组原生句柄：

```kotlin
val cooldown = group.phaseParameter("cooldown")
group.expression(duration = 20.ticks) {
    val glow = 1.0.toScalar() - cooldown.progress
    position.world(vec3(0.blocks, 1.0.blocks, 0.blocks))
    scale(pow(glow, 0.75))
    color(glow, cooldown.completed, Scalar.of(0.5), Scalar.of(1.0))
}
cooldown.start(3.6.ticks)   // 施法触发；重复调用即重入
// 需要提前收束时：cooldown.complete() / finish()
// 回到未开始：cooldown.reset()
```

`phaseParameter` 的 `progress/started/completed/active/remaining` 仍只读组表达式时间 `t`；起点、时长和状态
作为程序变量热更，不增加逐 tick 服务端执行或独立播放器。阶段句柄应在 `expression` 之前创建，才能编译进
同一份表达式文本。

### 坐标基与命名部件

```kotlin
val shape = Draw.polygon(manager) {
    name = "saw"
    coordinateFrame = Frames.ground(holder, followPosition = true, followYaw = false)
    sides = 12
    radius = 1.2.blocks
    samplesPerEdge = 2
    initialAngle = 15.degrees
    angularSpeed = 1.1.radiansPerSecond
    appearance = appearance

    part("teeth", PartSelector { index, edge, _ -> edge == 0 && index % 2 == 0 },
        color = Color.ORANGE)
}

shape.group.spin(Vec3(0.0, 1.0, 0.0), 0.02)   // 继续用普通组编排
shape.part("teeth")?.resize(0.5f, 4, Ease.OUT)
```

`Frames.ground` 默认只跟位置、不跟 yaw；`followYaw = true` 才连朝向一起跟随，均由 PD 的
`followEntity` 完成，不需要每 tick 重建坐标基。`Frames.basis` 对零向量/平行 upHint 显式报错。
`Draw.polygon` 沿边线性采样：每条边含起点、不含下一条边起点，总点数固定为
`sides × samplesPerEdge`，所以边中点不会因为固定半径而鼓到圆周上。
部件选择器返回的句柄保存成功成员，容量拒绝仍计入 `requested` 而不会串到别的部件。

### 批量生成配对

`spawnAll` 不再截断第 257 条及以后；每颗规格可带出生速度和加速度，PD 在生成后用现有批量运动包
按成功项配对：

```kotlin
val specs = ... // ParticleSpawnSpec().velocity(...).acceleration(...)
val results = manager.spawnAllDetailed(specs)
for (r in results) {
    if (r.handle == null) log("index ${r.index} rejected: ${r.rejection}")
}
```

`SpawnResult.index` 始终是原始输入下标；容量拒绝是 `SpawnRejection.CAPACITY`，不会让后面的速度
错配到另一颗粒子上。

### Java

`Time` / `Length` / `Angle` / `AngularSpeed` / `Velocity` / `Acceleration` / `Lifetime` 都有静态工厂；
外观用 `ParticleAppearance.builder()`，曲线用 `LifeCurves`，形状用 `Shapes` / `Draw.polygon(manager, spec)`，
退场用 `Exits`：

```java
ParticleVisual v = ParticleAppearance.builder()
    .style(ParticleStyle.SQUARE)
    .worldSize(Length.mcPixels(1.5), Length.mcPixels(1.0))
    .fullBright(true)
    .light(Light.none())
    .build();

ParticleLifeCurve c = LifeCurves.sizeEnvelope(
    Time.ticks(12), Time.ticks(2), Time.ticks(3));

PolygonSpec p = Shapes.builder();
p.setSides(6);
p.setRadius(Length.blocks(1.0));
Shape s = Draw.polygon(manager, p);
```
