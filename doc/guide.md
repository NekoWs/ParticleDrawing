# 使用指南

从绘制形状到播放动画文件的完整用法。类索引见 [api-reference.md](api-reference.md)，依赖引入与第一个
效果见 [getting-started.md](getting-started.md)。

## 目录

- [绘制形状](#绘制形状)
- [渐变着色](#渐变着色)
- [编排式动画](#编排式动画)
- [单粒子](#单粒子)
- [批量生成与粒子集](#批量生成与粒子集)
- [运行时发射器](#运行时发射器)
- [缓动](#缓动)
- [播放动画文件](#播放动画文件)
- [代码构建动画](#代码构建动画)
- [示例](#示例)
- [注意事项](#注意事项)

## 绘制形状

`Draw` 里的每个方法画出一个形状并返回它的 `ParticleGroup`，可以直接链式接编排动画。

通用可选参数：

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| `colorFn` | `ColorSource` | 沿形状参数 t ∈ [0,1] 着色，默认纯白 |
| `scale` | `Float` | 粒子大小，编辑器单位，默认 1 |
| `stagger` | `Int` | 逐粒子入场间隔（tick），第 i 个粒子延迟 i×N 出现，默认 0 |
| `group` | `ParticleGroup?` | 复用已有组而不是新建，默认 null |
| `visual` | `ParticleVisual` | 外观规格：贴图、柔边形状、各向异性、朝向、加色、免光照 |
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

`fadeOut(removeAfter = true)` 的销毁时刻与客户端用同一套换算，都从当下起算。成员变化触发的全量重发会
把时间轴重新从那一刻起算。

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

### 视觉通道按渲染帧插值

组级尺寸（`scaleTo` / `scaleBy` / 表达式 `sc`）、颜色与透明度都在渲染帧上按 partialTick 插值：位置由
桥接粒子的 `xo/x` 扫掠，尺寸与颜色由 `BridgeParticle` 记下的上一 tick 到本 tick 两个端点插值。程序本身
仍按 game tick 求值，只是写渲染层那一步做了插值。

生成包给的透明度与尺寸只是占位：出生后第一次真正同步（表达式首帧、寿命曲线首帧、即时改色改尺寸）会把
两端原子写成新值。首次接管与显式瞬移（轴心跳变、断流恢复）同样对位置、宽高、颜色与透明度一起原子落地。

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
渲染，持续动画在运行期零带宽。

```kotlin
// 把实体以名字写进程序注册表，公式里即可被动取值
group.defineEntity(handle = "e", uuid = entity.uuid)
     .followEntity(entity.uuid, offset = Vec3(0.0, 1.0, 0.0))

// 表达式指令：每粒子每 tick 求值一段标量公式
group.expression("""
    th = i / n * 2 * PI;
    [x,y,z] = [
        get_entity_x(e) + cos(th) * 2,
        get_entity_y(e) + 1 + get_world_rain() * sin(t * 0.1),
        get_entity_z(e) + sin(th) * 2
    ]
""")
```

表达式语法是旧式标量公式（`i/n/t`、`[x,y,z] = ...`），与 `.pdraw` 函数对象的 `this` 脚本语言不同。
用到什么取什么，`get_entity_*` / `get_world_*` 在需要处调用，不必预先声明属性。

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

## 单粒子

需要精确控制某一颗粒子时用流式 Builder：

```kotlin
val handle = manager.create()
    .position(x, y, z)
    .color(Color.ofHsb(0.6f, 0.9f, 0.9f))
    .scale(0.4f)
    .lifetime(-1)                  // -1 = 永存
    .glowing(true)
    .lightLevel(15)                // 向外发出光照
    .offsetFromPivot(dx, dy, dz)   // 相对组轴心的偏移
    .spawn() ?: return             // 达到维度上限时为 null

handle.move(target, 20, EasingType.EASE_OUT)
handle.recolor(Color.WHITE, 10, EasingType.LINEAR)
handle.resize(2f, 10, EasingType.LINEAR)
handle.setVelocity(Vec3(0.0, 0.1, 0.0))
handle.lightLevel(7)
handle.moveInstant(pos)
handle.remove()
```

### 外观

外观在 `spawn()` 那一次同步给客户端，之后不产生逐 tick 开销，要换外观就销毁后重新生成。Builder 上每个
设置都有对应方法，也可以直接递一整份 `ParticleVisual`：

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
manager.create().position(p).uv(16f, 0f, 32f, 16f)
    .texture("mymod:sheet")
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

登记后字节自动同步到客户端：单机与自带客户端就地解码，专用服务器在玩家进服时补发、运行中新登记的
立即广播。逐粒子载荷只写贴图 id，5 万颗粒子共用一张贴图也只多 1 字节每颗。没登记过的名字不会丢粒子，
只是渲染成纯白方块。

内置形状不必登记，`ParticleStyle.SOFT_DOT` / `LINE` 在两端各自生成同一份像素；需要提前确认可用时调
`ParticleManager.registerBuiltinTextures()`。两张内置贴图的可见尺寸比标称略小：

| 形状 | 可见比例 | 用途 |
| --- | --- | --- |
| `SOFT_DOT` | 直径约 0.75 × 尺寸 | 密铺成实心球体，间距不超过 0.3 × 直径就不会有方块感或空隙 |
| `LINE` | 粗细约 0.7 × 尺寸，两端各约 0.2 × 长度渐隐 | 一条丝 = 一颗粒子，相邻段接缝处略暗 |

`ParticleVisual` 是可变的链式对象，多颗粒子想共用一份外观再各改一点时用 `copy()`。

### 寿命曲线

寿命内的淡出与收缩随生成包一次带到客户端，之后零逐帧带宽。曲线值是乘数（缺省 1.0），时刻从生成那一刻
算起，单位 tick。

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

- 通道：`ALPHA`、`SCALE`、`RED`、`GREEN`、`BLUE`；同一通道给多条曲线时相乘。
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
  `additive` / `glowing` / `lightLevel` 与 Builder 同一套语义，曲线同理。`velocity(...)` 给每颗粒子一个
  出生速度，默认静止。
- 运行期改参数：`spawn()` 返回的句柄上重新调同名方法即可，变更走分档小包，已生成的粒子不受影响。锚点
  变更只发锚点段，密度变更只发口径段，其余整份参数替换。
- 上限：`maxAlive(n)`（默认 4096）限制单个发射器同时存活的粒子数，客户端另有全局
  `maxRenderParticles` 兜底。
- 后进服、切维度、走进范围的玩家由 PD 补发声明，不会看到一条空尾迹。
- 拖尾长度按格给、寿命等于长度除以速度、总强度倍率这类游戏语义仍由调用方计算，发射器只提供机制。

## 缓动

预设：`LINEAR`、`EASE_IN` / `EASE_OUT` / `EASE_IN_OUT`，以及 `*_QUAD` / `*_CUBIC` / `*_BOUNCE` /
`*_ELASTIC` 变体，共 14 种。无缓动用 `EasingType.NONE`，保持前一关键帧值直到下一关键帧。自定义三次
贝塞尔：

```kotlin
val custom = EasingType.custom(0.68, -0.55, 0.265, 1.55)   // (x1, y1, x2, y2)
```

## 播放动画文件

除了用代码实时绘制，也可以播放网页编辑器导出的 `.pdrawc` 动画并在运行期修改变量。服务端只把动画定义
与指令下发给客户端，粒子求值与渲染在客户端本地逐 tick 进行。

### 播放与停止

```kotlin
import work.nekow.particledrawing.animation.AnimationLoader
import work.nekow.particledrawing.animation.ServerAnimationManager

// 播放 <gameDir>/animations/<name>.pdrawc，返回本次播放 ID；文件不存在返回 null
val animId = ServerAnimationManager.playByName(dim, level.players(), "magic_circle", origin) ?: return

// 或自行提供字节来源（模组内置资源、数据库等）
val json = AnimationLoader.load("magic_circle")
ServerAnimationManager.play(dim, players, json, origin)

ServerAnimationManager.stop(animId, players)      // 停止单次播放
ServerAnimationManager.stopAll(dim, players)      // 停止整个维度
```

### 修改变量与查询状态

```kotlin
// 清空该变量的关键帧并把基值设为给定数值，下一 tick 生效；非数字字符串按 0 处理
ServerAnimationManager.updateVariable(animId, "rad", "4", players)

ServerAnimationManager.isActive(animId)          // 该次播放是否仍在进行
ServerAnimationManager.activePlaybacks(dim)      // 维度内活跃播放 ID 快照
ServerAnimationManager.activePlaybacksAll()      // 全部维度
ServerAnimationManager.playbackPlayers(animId)   // 该次播放覆盖的玩家 ID
```

### 入场编排

> 这些字段来自 `.pdrawc` 时间轴，单位是毫秒；代码 API 的 `delay` / `stagger` / `lifetime` /
> `destroyAfter` 走 game tick，两者不要混用。

每个粒子与函数对象可带 `st`（起始毫秒）：`t < st` 时粒子完全不生成，不是以透明度 0 渲染，
到点瞬间出现；循环回卷后按各自 `st` 重放入场顺序。时长 `timelineLength()` 自动计入最晚的 `st`。

静态粒子还可带 `life`（寿命毫秒，缺省 -1 表示无限），`t ≥ st+life` 后粒子回收消失，有限寿命同样计入
`timelineLength()`。函数对象派生的粒子可在 `process` 中遍历 `this.particles`，用 `p.life = <毫秒>` 设定
逐粒子寿命；其可见期由对象级 `st`、对象整体时长 `duration`（≤0 视为无上限）与逐粒子寿命三重门控决定。

### 典型场景

```kotlin
fun castSkill(level: ServerLevel, caster: Player) {
    val dim = ParticleUtils.dimensionUUID(level)
    val origin = caster.position().add(0.0, 1.0, 0.0)
    val id = ServerAnimationManager.playByName(dim, level.players(), "skill_burst", origin) ?: return

    // 施法者移速越快，动画转速变量越大
    val speed = caster.movementSpeed().toFloat()
    ServerAnimationManager.updateVariable(id, "spin", speed.toString(), level.players())
}
```

延迟收尾可配合任意服务端调度手段：

```kotlin
level.server.tell(object : TickTask(level.server.tickCount + 60) {
    override fun run() {
        if (ServerAnimationManager.isActive(id)) ServerAnimationManager.stop(id, level.players())
    }
})
```

客户端模组也可以在本地直调 `ClientAnimationManager.play/stop/updateVariable/reloadTextures`，仅限客户端
环境。

## 代码构建动画

除了用 `Draw` 实时画形状或播放编辑器导出的文件，还可以用代码直接构建一个动画再下发播放。动画模型与
`.pdrawc` 播放同构：粒子、轨道、组、函数对象、摄像机、贴图都可声明，播放走同一套服务端权威进度与客户端
渲染链路。

### Kotlin DSL

```kotlin
import work.nekow.particledrawing.api.*
import work.nekow.particledrawing.animation.TrackPr
import work.nekow.particledrawing.core.easing.EasingType
import net.minecraft.world.phys.Vec3

val anim = Animation.create {
    loop = true

    particle {
        id = "p0"
        pos = Vec3(0.0, 10.0, 0.0)
        color = Color.CYAN
        scale = 1f
        life = -1
    }

    track {
        pr = TrackPr.POS_X
        ids = listOf("p0")
        keyframe(0, 0.0, EasingType.LINEAR)
        keyframe(1000, 5.0, EasingType.EASE_OUT)   // 关键帧时刻为毫秒
    }

    function {
        id = "fx0"
        center = Vec3(0.0, 10.0, 0.0)
        duration = 10000                           // 时长毫秒，0 表示无上限
        seed = 1
        variable("rad", 3.0)
        source = """
            func setup() {
              repeat(100) { this.spawn() }
            }
            func process() {
              for (const p of this.particles) {
                let th = p.index / this.particles.size() * 2 * PI
                p.position = vec(cos(th) * rad, 0, sin(th) * rad)
              }
            }
        """.trimIndent()
    }
}

anim.play(level.players(), origin)
    .updateVariable("rad", "4")
    .isActive()
```

### Java Builder

```java
Animation anim = Animation.builder()
    .loop(true)
    .particle(p -> p.id("p0").pos(0, 10, 0).color(Color.CYAN).scale(1f).life(-1))
    .track(t -> t.pr(TrackPr.POS_X).ids("p0")
        .keyframe(0, 0.0, EasingType.LINEAR)
        .keyframe(1000, 5.0, EasingType.EASE_OUT))
    .function(f -> f.id("fx0").center(0, 10, 0).duration(10000).seed(1)
        .variable("rad", 3.0)
        .source("""
            func setup() {
              repeat(100) { this.spawn() }
            }
            func process() {
              for (const p of this.particles) {
                let th = p.index / this.particles.size() * 2 * PI;
                p.position = vec(cos(th) * rad, 0, sin(th) * rad);
              }
            }
            """))
    .build();

anim.play(level.players(), origin).updateVariable("rad", "4");
```

### TrackPr

轨道的 `pr` 是类型安全的 `TrackPr` 枚举，与 `.pdrawc` 二进制里的分量序号一一对应。

| 属性 | 枚举常量 |
| --- | --- |
| 位置 | `POS_X` `POS_Y` `POS_Z` |
| 速度 | `VEL_X` `VEL_Y` `VEL_Z` |
| 颜色 | `COL_R` `COL_G` `COL_B` `COL_A` |
| 缩放 | `SCL_X` `SCL_Y` `SCL_Z` |
| 公转 | `ROT_X` `ROT_Y` `ROT_Z` |
| 自转 | `SPIN_X` `SPIN_Y` `SPIN_Z` |
| 公转中心 | `CENTER_X` `CENTER_Y` `CENTER_Z` |
| 摄像机 | `FOV` `TARGET_X` `TARGET_Y` `TARGET_Z` |

`TrackPr.FOV` 是标量分量，没有 `.x` / `.y` / `.z`。

### 链式操作

| 方法 | 说明 |
| --- | --- |
| `play(level, players, origin)` | 向指定玩家下发并开始播放，返回自身 |
| `play(players, origin)` | 从首个玩家推导 `ServerLevel` 的便捷重载 |
| `stop()` | 停止本次播放 |
| `updateVariable(name, value)` | 运行期更新函数对象变量，下一 tick 生效 |
| `isActive()` | 本次播放是否仍在进行 |

代码生成的动画走独立的结构化网络载荷，不验签、不生成文件。播放记录同样支持维度切换、重生与重连后的
自动重发，与其他玩家帧号保持一致。

## 示例

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

## 注意事项

1. 所有 API 在服务端主线程调用，内部调度任务也在主线程执行。
2. 粒子数量受 `maxParticlesPerDimension` 与 `maxParticlesPerPlayer` 约束，达到上限时 `spawn()` 返回 null。
3. 引擎按视距自动增减同步给客户端的粒子，持续动画只对已追踪该组的玩家广播。
4. 无限动画（`durationTicks = -1`、`cycles = -1`）会一直占用 tick 与带宽，用 `stopContinuous()` 或让组
   `fadeOut()` / `destroyAfter()` 收尾。
5. `stagger > 0` 时粒子陆续出现，期间 `group.size()` 会逐步增长。
6. Java 调用方：绘制形状与部分链式方法带 `@JvmOverloads` 重载，其余带默认参数的方法要写全参数；
   颜色来源用 `ColorSource` 接口或 lambda 实现。
7. 外观在生成时定死，中途只能改颜色、缩放标量、位置与发光等级，要换外观就 `remove()` 后重新生成。
8. `delay(n)` 期间粒子在 PD 侧还不存在，句柄上的操作一律无效，position() 读不到权威位置；
   `ParticleBatch` 会把延迟成员留到到点。
9. 发射器声明不随调用方消失，法术结束或投射物消亡时要 `stop()`，否则客户端会一直发下去。
10. `ParticleManager.spawnAll` 单次最多 256 条，超出的规格不生成（返回值比入参短），服务端再按每玩家
    上限裁剪；密集场景分批调用。
11. 光照结果按方块坐标、动态光版本与分摊超时缓存。动态光变化下一渲染帧刷新；插拆火把、昼夜变化、区块
    重载这类没有版本号的世界光变化由每颗粒子各自错开的 1 秒周期重采样兜住。
12. `lifetime(n)` 是 n 个 game tick，关卡不 tick（单人按 Esc 暂停）时寿命与缓动都不流逝，恢复后从原处
    继续。寿命曲线同样按引擎 tick 的年龄取值。
13. 一个组是一段编排而不是粒子场：受控清单要一次发完，成员上限 8192，超了明确报错；指令按单包 512 条
    拆段下发，组还没有成员时最多缓存 4096 条指令，超出丢弃并告警。
14. 同时活跃的发射器上限 512，到顶时新声明被拒绝并打 ERROR，可用 `EmitterHandle.isActive()` 检测。
15. 贴图名长度上限 256 字符，过长在调用点直接报错。
