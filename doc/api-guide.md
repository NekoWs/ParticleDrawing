# ParticleDrawing 开发者 API 指南

面向模组开发者：把 ParticleDrawing 作为依赖，用简单的 Kotlin / Java 代码在 Minecraft 世界中绘制粒子动画。

> 类索引见 [README.md](./README.md)。所有 API 位于 `work.nekow.particledrawing.api` 包。

---

## 目录

1. [添加依赖](#一添加依赖)
2. [核心概念](#二核心概念)
3. [快速开始](#三快速开始)
4. [绘制形状（Draw）](#四绘制形状draw)
5. [渐变着色（ColorSource）](#五渐变着色colorsource)
6. [编排式动画（ParticleGroup）](#六编排式动画particlegroup)
7. [单粒子控制（ParticleHandle）](#七单粒子控制particlehandle)
8. [缓动（EasingType）](#八缓动easingtype)
9. [播放 .pdraw 动画引擎（ServerAnimationManager）](#九播放-pdraw-动画引擎serveranimationmanager)
10. [代码生成动画（Animation.create）](#十代码生成动画animationcreate)
11. [完整示例集](#十一完整示例集)
12. [注意事项](#十二注意事项)

---

## 一、添加依赖

### 1. 构建脚本

把本模组的 jar 放入 `libs/` 目录，或在仓库可用时声明坐标：

```kotlin
// build.gradle.kts
dependencies {
    implementation(files("libs/particledrawing-1.0.1.jar"))
    // 或 jar-in-jar 打包，让玩家无需单独安装：
    // jarJar(implementation(...))
}
```

### 2. `neoforge.mods.toml` 声明依赖

```toml
[[dependencies.${mod_id}]]
    modId = "particledrawing"
    type = "required"
    ordering = "AFTER"
```

---

## 二、核心概念

| 概念 | 类型 | 说明 |
| --- | --- | --- |
| 粒子管理器 | `ParticleManager` | 维度级入口。`ParticleManager.of(serverLevel)` 获取 |
| 粒子组 | `ParticleGroup` | 一组粒子的集合，**动画的编排单位**；Draw 的每个形状都返回一个组 |
| 绘图工具 | `Draw` | 静态形状库：线、圆、盘、曲线、三角、星、矩形、球、长方体 |
| 单粒子句柄 | `ParticleHandle` | 移动 / 重着色 / 缩放 / 销毁单个粒子 |
| 颜色 | `Color` | 不可变 RGBA；工厂方法 `of / ofInt / ofHsb` |
| 缓动 | `EasingType` | 14 种预设 + 自定义三次贝塞尔 |

**设计原则**：`Draw.xxx(...)` 画出一个形状并返回它的 `ParticleGroup`，随后直接链式调用动画方法即可——

```kotlin
Draw.circle(manager, center, 3.0, 60)   // 返回 ParticleGroup
    .fadeIn(10)                          // 出现
    .spin(Vec3(0, 1, 0), PI / 40)        // 持续旋转
    .fadeOut(20)                         // 消失
```

---

## 三、快速开始

在服务端任意时机（命令、事件、tick 任务）执行：

```kotlin
import work.nekow.particledrawing.api.*
import work.nekow.particledrawing.core.easing.EasingType
import net.minecraft.world.phys.Vec3

fun demo(manager: ParticleManager, center: Vec3) {
    // 一个从淡入到淡出的旋转圆环
    Draw.circle(manager, center, 4.0, count = 80)
        .fadeIn(15)
        .spin(Vec3(0.0, 1.0, 0.0), Math.PI / 40)   // 每 tick 转 π/40 弧度，无限循环
        .delay(100)                                  // 5 秒后开始退场
        .stopContinuous()                            // 停掉无限旋转
        .fadeOut(20)                                 // 淡出并销毁
}
```

就这么多——一个「出现 → 持续旋转 → 消失」的完整动画完成了。

---

## 四、绘制形状（Draw）

每个方法的通用可选参数：

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| `colorFn` | `ColorSource` | 沿形状参数 t ∈ [0,1] 渐变着色（默认纯白） |
| `scale` | `Float` | 粒子大小（编辑器单位，默认 1） |
| `stagger` | `Int` | 逐粒子入场延迟（tick），实现波浪式出现（默认 0 = 全部同时出现） |
| `group` | `ParticleGroup?` | 复用已有组而非新建（默认 null） |
| `visual` | `ParticleVisual` | 外观规格：贴图 / 柔边形状 / 各向异性 / 朝向 / 加色 / 免光照（默认纯白方块） |
| `taper` | `Boolean` | 只在 `line` / `curve` / `polyline` 上：沿线从细到粗、两端渐隐（默认 false） |

### 形状一览

```kotlin
val m = ParticleManager.of(level)

// 低级：单粒子
Draw.dot(m, Vec3(0.0, 64.0, 0.0))

// 线段：start → end，count 个粒子
Draw.line(m, start, end, count = 40,
    colorFn = ColorSource.gradient(Color.CYAN, Color.MAGENTA))

// 自由曲线：任意参数函数
Draw.curve(m, { t -> Vec3(t * 10.0, sin(t * PI) * 3.0, 0.0) }, steps = 60)

// 圆周 / 圆盘（axis 选择所在平面）
Draw.circle(m, center, radius = 4.0, count = 80, axis = Draw.Axis.XZ)
Draw.disc(m, center, radius = 4.0, perimeterCount = 80, layers = 8)

// 三角形 / 六芒星
Draw.triangle(m, center, radius = 3.0, segmentsPerEdge = 30)
Draw.hexagram(m, center, radius = 3.0, segmentsPerEdge = 40,
    colorFn1 = ColorSource.of(Color.RED),
    colorFn2 = ColorSource.of(Color.BLUE))

// 矩形网格（hollow = 只描边）
Draw.rect(m, center, width = 6.0, height = 4.0, hollow = true)

// 球体（默认彩虹渐变）/ 长方体
Draw.sphere(m, center, radius = 3.0, count = 400)
Draw.cuboid(m, center, width = 6.0, height = 6.0, depth = 6.0, hollow = true)

// 折线 / 光束：每段一颗沿线段躺好的贴图，一条调用画完一条丝
Draw.polyline(m, listOf(a, b, c, d), thickness = 0.12f, additive = true, glowing = true)
```

### 用贴图与柔边形状（`visual` / `taper`）

程序化粒子的默认外观是**纯白硬边方块**。要柔边圆点、丝线、辉光，就把外观规格交给 `visual`：

```kotlin
import work.nekow.particledrawing.api.ParticleStyle
import work.nekow.particledrawing.api.ParticleVisual

// 柔边圆点：黑核 / 能量球 / 薄雾的底色（内置形状，客户端就地生成，零带宽）
Draw.sphere(m, center, 1.2, count = 120,
    visual = ParticleVisual().style(ParticleStyle.SOFT_DOT).additive(true).glowing(true))

// 柔边点阵排成一条丝：比硬方块拼的连续得多，也不用堆密度
Draw.line(m, a, b, count = 24, scale = 0.4f,
    visual = ParticleVisual().style(ParticleStyle.SOFT_DOT), taper = true)

// 一条丝 = 一颗粒子（内置线段贴图：两端渐隐、上下柔边，长轴自动对齐线段方向）
Draw.polyline(m, points, thickness = 0.1f, taper = true, additive = true, glowing = true)

// 自己登记的贴图（见「贴图登记与下发」），并按世界格给尺寸
Draw.polyline(m, points, thickness = 0.2f, texture = "mymod:bolt", additive = true)
Draw.circle(m, center, 3.0, 60, visual = ParticleVisual().texture("mymod:glass").uv(0f, 0f, 32f, 32f))
```

### 波浪入场（stagger）

`stagger = N` 时第 i 个粒子延迟 i×N tick 出现：

```kotlin
// 200 个球面粒子每隔 2 tick 依次出现 → 约 7 秒的展开动画
Draw.sphere(m, center, 5.0, count = 200, stagger = 2)
```

### 形状里的实色片元自己做入场（lifeCurve）

每个形状方法末尾都能接一条**逐粒子寿命曲线**：片元自己淡入/收放，不必去缩整个组
（缩组会把形状的间距、半径一起缩掉，那不是「片元入场」）：

```kotlin
Draw.sphere(m, center, 5.0, count = 200,
    lifeCurve = ParticleLifeCurve.of(
        ParticleCurve.alpha(CurveKey.at(0, 0f), CurveKey.at(10, 1f, EasingType.EASE_OUT)),
    ))
```

配合 `stagger` 就是「逐颗冒出来」；曲线本身随 spawn 包下发，零逐帧带宽。

---

## 五、渐变着色（ColorSource）

`t ∈ [0,1]` 是形状参数：线段为起点→终点进度、圆周为一圈进度、球面为顶→底进度。

```kotlin
// 固定颜色
ColorSource.of(Color.ORANGE)

// 双色插值
ColorSource.gradient(Color.YELLOW, Color.RED)

// 彩虹扫过（sphere 默认值）
ColorSource.rainbow(alpha = 1f)

// 自定义 lambda（Kotlin）
Draw.line(m, a, b, 50) { t -> if (t < 0.5) Color.BLUE else Color.WHITE }
```

---

## 六、编排式动画（ParticleGroup）

所有动画方法返回自身，支持链式**时间线**：`delay(n)` 把游标向前推进 n tick（累积、不清零），
之后的每个动画方法都在各自游标时刻触发——连续两个动画共享同一时刻。
`fadeIn(10)` 与紧随的 `spin(...)` 都在 t=0 并行推进；`.delay(100)` 之后的 `stopContinuous()`
与 `fadeOut(20)` 则都在 t=100 同时发生。想要「停转后再等一会儿」就再补一个 `.delay(x)`。

### 轴心：组级变换都绕它算（调用顺序即语义）

轴心是**程序级状态**：`setPivot(...)` / `followEntity(...)` 绑定一次，对**其后**的
`rotate` / `spin` / `scale` / `pulse` 全部生效，直到下一次绑定。这些方法**不接受轴心参数**，
所以「绕实体转」只能靠顺序表达——先绑轴心，再旋转：

```kotlin
group.setPivot(center)                             // 轴心 = 固定坐标
group.followEntity(owner.uuid, offset = Vec3(0.0, 1.2, 0.0))   // 轴心 = 实体（每 tick 本地解析，零带宽）
group.spin(Vec3(0, 1, 0), Math.PI / 40)            // 绕当前轴心转；接在 followEntity 后面就是跟着实体转
```

轴心绑定到实体后是**活的**：组随实体位置移动（`local = true` 时连朝向一起），
组内各粒子保持相对轴心的偏移。反过来 `.spin(...)` 写在 `followEntity` 之前，
那一段只绕绑定前的固定点转——顺序反了不会报错，只是效果不对。

**可移动轴心**（黑洞中心、重力场中心、跟随投射物的法阵）：那不是实体，只能用「服务端每 tick 报位置」。
用 `anchor(Anchor.Movable(...))` 绑定一次，之后 `updateAnchor(上一位置, 当前位置)` 逐 tick 更新：

```kotlin
group.anchor(Anchor.Movable(center, velocity))       // 绑定一次（位置 + 朝向模式）
// ...每 tick：
group.updateAnchor(before, now)                      // 相邻两个服务器样本，客户端按 partialTick 插值
```

- 与 `setPivot` 的区别：固定轴心要挪就得每 tick 再 `setPivot` 一次（每 tick 一条绑定指令 + 帧间硬跳）；
  本方法只发位置，**相位与 `track` 粒子一致**（相邻样本插值）。
- 朝向：`Anchor.Movable` 的 `Orient.VELOCITY` 让整组随运动方向转向（法阵面朝飞行方向），
  `Orient.WORLD` 只跟位置不转向。
- 边界语义：一条样本跳超 8 格（瞬移）或断流超过 3 tick，都按**跳变**处理——不会在两点之间扫出一条假轨迹；
  关卡暂停（程序不推进）时位置保持，恢复后按新样本继续。
- 实体锚点仍请用 `followEntity`（客户端本地解析，连位置包都不用发）。

### 一次性变换

```kotlin
group.move(Vec3(0.0, 2.0, 0.0), durationTicks = 30, easing = EasingType.EASE_OUT)
group.rotate(Vec3(0, 1, 0), radians = Math.PI, durationTicks = 40)   // 绕当前轴心
group.recolor(Color.BLUE, durationTicks = 20)
group.scale(ratio = 2f, durationTicks = 15)      // 在**当前倍率**之上乘 2（半径与视觉大小同步）
group.scaleBy(0.5f, durationTicks = 10)          // scale 的显式名字：再来一次是再减半
group.scaleTo(1.0f, durationTicks = 8)           // 缓动到**绝对** 1 倍（不管现在是几倍）
group.moveAlongOffset(scale = 0.6f, durationTicks = 20)  // 每颗沿自己相对轴心的方向向外飞
```

> `moveAlongOffset` 是**逐成员各自方向**的平移（`scale × |偏移|`，方向取当前偏移，跟着自转一起转）：
> 「球面碎片各自沿法线炸开」这种效果组级 `move` 表达不了——那样只能一片一个组，
> 而组数直接等于 arm 日志行数与 arm 开销；本方法整组一个包就够。

> **缩放的起点是「执行那一刻」的合成倍率**，不是恒定的 1：
> - `scaleBy(r)`（`scale` 是它的别名）：终点 = 起点 × r，`scaleBy(0.01f).scaleBy(100f)` 会回到 1 倍；
> - `scaleTo(t)`：终点 = t，从任意倍率接退场都不会先跳回 1（1.5 倍退场就是 1.5 → 0）；
> - 运行中追加一条缩放，以那**一帧**的倍率为起点（帧级重定向），已有倍率、脉冲、旋转都不被重置；
> - `scaleTo(0f, …)` 表示完全收起：客户端不绘制（不是缩到「很小但还看得见」）。固定结构想从零尺寸长开，
>   就 `scaleTo(0f, 0)` 铺成员、之后 `scaleTo(1f, 6)`。
> - `durationTicks = 0` 表示瞬时跳变；`move` / `movePath` 只改渲染位置，**不动轴心绑定**。
> - `stopContinuous` 同样受 `delay` 游标控制：`.spin(...).delay(100).stopContinuous()` = 转 100 tick 后停。

### 旋转可叠加

多条 `rotate` / `spin` 的角度是**累加**的（后一条不会覆盖前一条的相位），所以可以「先自转一阵、
中途补一段一次性旋转、再继续自转」——`durationTicks = 0` 就是瞬时补相位：

```kotlin
group.spin(axis, radiansPerTick = 0.02)              // 一直转
// ...跑了一阵之后想把相位挪到某处，并保持同样的角速度：
group.rotate(axis, radians = Math.PI / 2, durationTicks = 0)   // 补 90°，不打断自转
```

### 增量追加：delay 读作「从现在起」

程序下发之后再录制的指令，其时刻按**录制那一刻**换算（同一 tick 内连续录制算一次会话，
会话里的 `delay` 依次累加）。于是长寿组可以在运行期再追加动画：

```kotlin
val g = manager.createGroup(pos)      // 建组、铺成员、录一段出场动画
// ...100 tick 之后玩家收起法杖：
g.delay(1).fadeOut(durationTicks = 5) // 1 tick 后开始、5 tick 淡完（不是「瞬间完成」）
```

`fadeOut(removeAfter = true)` 的销毁时刻与客户端用同一套换算，都是从**当下**起算，
不会把销毁推后「已经跑过的时长」那么多 tick（旧的绝对游标口径会留下看不见但还活着的粒子）。
成员变化触发的**全量重发**会把时间轴重新从那一刻起算。

> **arm 日志**：每次 arm 一行 INFO 在实战里会刷屏（护盾一次受击 arm 32 个组），所以默认只进 DEBUG。
> 排查「程序到底有没有 arm、arm 了几颗粒子」时用 `ParticleManager.setDebugLogging(true)` 临时打开，
> 或者把客户端配置 `debugProgramLogging` 设为 `true` 常开。

### 生命周期

```kotlin
group.fadeIn(durationTicks = 15)                 // 透明度 0 → 当前值
group.fadeOut(durationTicks = 20)                // 透明度 → 0 并销毁整组
group.destroyAfter(ticks = 200)                  // 定时销毁
```

### 持续运动（服务端逐步驱动，客户端平滑插值）

```kotlin
// 无限匀速旋转（绕当前轴心）；用 stopContinuous() 停止
group.spin(axis = Vec3(0, 1, 0), radiansPerTick = Math.PI / 40)

// 折线路径：从当前位置出发依次经过各点，easing 作用于全程进度
group.movePath(
    points = listOf(
        Vec3(0.0, 3.0, 0.0),
        Vec3(8.0, 3.0, 0.0),
        Vec3(8.0, 0.0, -8.0),
    ),
    durationTicks = 120,
    easing = EasingType.EASE_IN_OUT,
)

// 呼吸脉冲：1× ↔ 目标倍率往复（同样作用于半径与视觉大小）；cycles = -1 无限
group.pulse(peakRatio = 1.8f, halfPeriodTicks = 20, cycles = 3)   // 呼吸到 1.8 倍再回原大
```

### 实体通道与公式动画（上限能力）

编排动画以「客户端自驱程序」执行：链式调用录制为指令流一次性下发，
客户端按服务端时钟锚点本地求值并直写渲染——持续动画运行期**零带宽**、帧率级平滑。

在此之上，两条 API 把上限进一步打开：

```kotlin
// 1. 实体句柄登记：把实体以名字写进程序注册表，公式里即可被动取值（客户端本地解析，零带宽）
group.defineEntity(handle = "e", uuid = entity.uuid)
     .followEntity(entity.uuid, offset = Vec3(0.0, 1.0, 0.0))   // 或者仅轴心跟随

// 2. 表达式指令：每粒子每 tick 求值一段专用标量公式（i/n/t、[x,y,z]=... 等旧式语法，
//    与 .pdraw 函数对象的 this 脚本语言不同）；
//    用到什么取什么——get_entity_*/get_world_* 在需要处调用，无需预先声明属性
group.expression("""
    th = i / n * 2 * PI;
    [x,y,z] = [
        get_entity_x(e) + cos(th) * 2,
        get_entity_y(e) + 1 + get_world_rain() * sin(t * 0.1),
        get_entity_z(e) + sin(th) * 2
    ]
""")
```

被动输入 getter 一览（未知名/未登记句柄在编译期报错；服务端下发时有预警日志）：

| getter | 含义 |
|---|---|
| `get_entity_x/_y/_z(h)` | 实体坐标分量 |
| `get_entity_pos(h)` | 整取坐标，仅限 `[x,y,z] = get_entity_pos(h)` 独占赋值形态 |
| `get_entity_exists(h)` | 实体是否在场（0/1；缺失时该实体其余取值同为 0） |
| `get_entity_yaw/_pitch(h)` | 朝向角（MC 原始度数：yaw -180~180、0=+Z；pitch -90~90） |
| `get_entity_dirx/_diry/_dirz(h)` | 单位视线向量（与渲染视角一致） |
| `get_entity_vx/_vy/_vz(h)` | 速度（block/tick，按相邻 tick 位置差分；首 tick 为 0） |
| `get_entity_hp/_hp_max(h)` | 当前/最大生命值（仅生物，其他实体为 0） |
| `get_entity_ground/_sneaking/_on_fire/_swimming/_sprinting(h)` | 状态标志（0/1） |
| `get_world_day_time()` | 主世界时钟当日刻（0~23999） |
| `get_world_game_time()` | 主世界时钟总刻 |
| `get_world_rain() / get_world_thunder()` | 降雨 / 雷暴强度（0~1，含平滑过渡） |
| `get_world_moon_phase()` | 月相序号 0~7（按主世界时钟每 24000 刻推进一相） |

> 参数 `h` 是 `defineEntity` 定义的句柄名（也可写注册序号数字），必须是编译期常量。
> 属性词表是封闭枚举：`EntityProp` / `WorldProp`（如 `EntityProp.YAW.call("e") == "get_entity_yaw(e)"`）。

运行时热改变量（服务端只发一条控制包，动画即时响应）：

```kotlin
group.setVariableLive("speed", "2")                // 直接给常量
group.setVariableLive("rad", "speed * 2")          // 标量公式，可引用其它程序变量
group.setVariableInterpolated("bx", "targetX", ticks = 10)   // 10 tick 内缓动到目标（空间端点扫过去）
```

> `setVariableLive` 的公式走程序变量作用域，不注入 `t/i/n`，因此不能引用时间/序号。
> `setVariableInterpolated` 与之同一套求值环境（收到那一刻算出目标值），只是**不瞬移**：
> 客户端从当前值缓动过去，`easing` 可选，`ticks = 0` 等于立即赋值。
> 变量常被当作空间端点用（光束末端、场中心），立即赋值会让整段几何跳一下——要连续就用它。

> `expression` 一旦出现即为**表达式模式**：接管位置/颜色/缩放的最终解释权；
> `fadeIn/fadeOut` 因子仍叠加在其 alpha 上。纯数据协议——不向客户端发送任何代码字节。

### 综合链式示例：出现 → 放大 → 旋转 → 停转 → 淡出

```kotlin
Draw.circle(manager, center, radius = 3.0, count = 200)
    .fadeIn(10)                                    // t=0    渐显（0.5s）
    .scale(2f, durationTicks = 15)                 // t=0    平滑放大到 2 倍（0 = 瞬跳）
    .spin(Vec3(0, 1, 0), Math.PI / 40)             // t=0    开始无限旋转
    .delay(100)                                    // 游标 → 100
    .stopContinuous()                              // t=100  停转
    .fadeOut(20)                                   // t=100  渐隐并销毁（1s）
```

时序说明：`fadeIn` / `scale` / `spin` 都在游标 0 处并行推进；`.delay(100)` 后的
`stopContinuous` 与 `fadeOut` 共享 t=100 时刻——停转即开始渐隐。
若想「停转后停留 2 秒再淡出」，在两者之间插入 `.delay(40)`。整段动画约 6 秒。

### 组合示例：魔法阵

```kotlin
val circle = manager.createGroup(center)
Draw.circle(m, center, 3.0, count = 90, colorFn = ColorSource.of(Color.BLUE), group = circle)
Draw.hexagram(m, center, 3.0,
    colorFn1 = ColorSource.of(Color.WHITE),
    colorFn2 = ColorSource.of(Color.LIGHT_GRAY), group = circle)   // LIGHT_GRAY 换成任意色均可

circle.fadeIn(20)
    .spin(Vec3(0, 1, 0), Math.PI / 60)                        // 整体缓慢旋转
    .delay(60)
    .move(Vec3(0.0, 4.0, 0.0), 40, EasingType.EASE_IN_OUT)    // 边转边升
    .pulse(1.3f, halfPeriodTicks = 12, cycles = 2)            // 到达后脉冲两次
    .delay(30)
    .stopContinuous()
    .fadeOut(25)
```

---

## 七、单粒子控制（ParticleHandle）

需要精确操作某个粒子时使用流式 Builder：

```kotlin
val handle = manager.create()
    .position(x, y, z)
    .color(Color.ofHsb(0.6f, 0.9f, 0.9f))
    .scale(0.4f)
    .lifetime(-1)          // -1 = 永存
    .glowing(true)         // 发光
    .lightLevel(15)        // 向外发出光照
    .offsetFromPivot(dx, dy, dz)   // 相对组轴心的偏移
    .spawn() ?: return     // 达到维度上限时为 null

handle.move(target, 20, EasingType.EASE_OUT)   // 缓动移动
handle.recolor(Color.WHITE, 10, EasingType.LINEAR)
handle.resize(2f, 10, EasingType.LINEAR)
handle.setVelocity(Vec3(0.0, 0.1, 0.0))
handle.lightLevel(7)
handle.moveInstant(pos)
handle.remove()
```

### 外观：贴图 / UV / 各向异性 / 朝向 / 加色

外观是**生成时定死**的：只在 `spawn()` 那一次同步给客户端，之后不产生任何逐 tick 开销（要换外观就销毁重生成）。
Builder 上每个设置都有对应方法，也可以直接递一整份 `ParticleVisual`：

```kotlin
// 柔边圆点 + 免光照 + 加法混合
manager.create().position(p).scale(0.5f).lifetime(60)
    .style(ParticleStyle.SOFT_DOT)   // 内置形状（SQUARE 是默认的纯白方块）
    .glowing(true)
    .additive(true)                  // 亮部叠亮、有溢出感
    .spawn()

// 一张丝线：长轴沿线段躺好，长 = 3 格，粗 = 0.1 格
manager.create().position(mid).lifetime(40)
    .texture("mymod:bolt")
    .scaleWorld(3.0f, 0.1f)          // 世界格整宽/整高（不随贴图尺寸放大）
    .alignTo(a, b)                   // 长轴对齐 a→b（会关掉广告牌，否则自转不生效）
    .additive(true)
    .spawn()

// 图集里取一格（贴图像素坐标）+ 自己给朝向
manager.create().position(p).uv(16f, 0f, 32f, 16f)
    .texture("mymod:sheet")
    .billboard(false)                // 朝向固定（世界 +Z 起算）
    .spin(Math.PI / 3)               // 平面内 60°（弧度）；三轴版本 spin(rx, ry, rz)
    .spawn()

// 逐粒子生成延迟：spawn 立刻返回句柄，粒子推迟 20 tick 才真正出现
manager.create().position(p).delay(20).spawn()
```

**尺寸口径**（`scale` 与各向异性都按这两条之一算）：

| 入口 | 单位 | 换算 |
| --- | --- | --- |
| `scale(Float)` / `scale(w, h)` | 编辑器单位 | 渲染**整宽** = 值 × 0.2 格 × 贴图尺寸系数 |
| `scaleWorld(w, h)` | 世界格 | 直接就是整宽/整高（`Draw.polyline` 用的就是它） |

「贴图尺寸系数」= 贴图取景框最长边 / 16（`uv` 时的取景框就是那个子矩形）。所以：
16px 的贴图系数为 1（内置形状都是 16px），32px 的贴图会把尺寸放大到 2 倍——
想精确控制世界尺寸就一律用 `scaleWorld`。

**贴图登记与下发**（`ParticleManager.registerTexture`）：

```kotlin
// 模组初始化或特效第一次触发时都行；幂等（同名同图只登记一次）
ParticleManager.registerTexture("mymod:glow", pngBytes)   // 字节上限 1 MiB
```

登记后字节会**自动同步到客户端**：单机与自带客户端就地解码，专用服务器在玩家进服时补发、
运行中新登记的立即广播。逐粒子载荷只写贴图 id（不写名字），所以 5 万颗粒子共用一张贴图
也只多 1 字节/颗。**没登记过的名字不会丢粒子**，只是渲染成纯白方块。

内置形状不必登记：`ParticleStyle.SOFT_DOT` / `LINE` 两端各自生成同一份像素；
需要提前确认可用时调 `ParticleManager.registerBuiltinTextures()`。

两个内置贴图的**实际可见尺寸比标称小一点**（边缘要柔化，就必然有一部分是半透明的）：

| 形状 | 可见比例 | 用途 |
| --- | --- | --- |
| `SOFT_DOT` | 直径 ≈ 0.75 × 尺寸 | 密铺成实心球体（黑核 / 能量球）：间距 ≤ 0.3 × 直径就不会有方块感或空隙 |
| `LINE` | 粗细 ≈ 0.7 × 尺寸，两端各 ≈ 0.2 × 长度渐隐 | 一条丝 = 一颗粒子；相邻段接缝处会略暗（正是「两端渐隐」的样子） |

`doc/` 里的预览（跑测试时写到 `build/preview/`）就是这两张贴图与「黑核 + 丝线」合成的人眼复核图，
调密度前可以先看一眼。

`ParticleVisual` 是可变的链式对象：多颗粒子想共用一份外观再各改一点时用 `copy()`。

### 寿命曲线：淡出 / 收缩 / 逐通道乘数

「寿命内慢慢淡掉、缩掉」原来要额外下发 `recolor` + `resize` 两个更新包；现在**随 spawn 包一次带过去**，
之后零逐帧带宽。曲线值是**乘数**（缺省 1.0），时刻从**生成那一刻**算起（tick）。

```kotlin
// 拖尾颗粒：活 20 tick，最后 8 tick 淡出并缩到 0.2 倍
manager.create().position(p).scale(0.4f).lifetime(20)
    .style(ParticleStyle.SOFT_DOT)
    .fadeOut(8)                 // 也能给缓动：fadeOut(8, EasingType.EASE_IN_QUAD)
    .shrinkTo(0.2f, 8)
    .spawn()

// 想自己捏就逐通道给关键帧（段内用后一关键帧的缓动；step 是阶跃）
manager.create().position(p).lifetime(40)
    .alphaCurve(CurveKey.at(0, 1f), CurveKey.at(24, 1f), CurveKey.at(40, 0f, EasingType.EASE_IN))
    .sizeCurve(CurveKey.at(0, 1f), CurveKey.at(40, 0.3f))
    .colorCurve(                                     // RGB 三条分开给
        listOf(CurveKey.at(0, 1f), CurveKey.at(40, 0.2f)),   // 红
        listOf(CurveKey.at(0, 0.8f), CurveKey.at(40, 0.1f)), // 绿
        listOf(CurveKey.at(0, 0.6f), CurveKey.at(40, 0f)),   // 蓝
    )
    .spawn()
```

- 通道：`ALPHA` / `SCALE` / `RED` / `GREEN` / `BLUE`；同一通道给多条曲线时**相乘**。
- `curve(channel, keys)` 是通用入口，`alphaCurve` / `sizeCurve` / `colorCurve` 是现成写法。
- `fadeOut` / `shrinkTo` 锚在**寿命末尾**，所以需要有限寿命（`lifetime(-1)` + `fadeOut` 会明确报错，
  而不是悄悄按「生成后 N tick」算）。
- 客户端逐**渲染帧**刷新带曲线的粒子外观（只改颜色/缩放，不动位置），所以淡出是平滑的、不是 20Hz 台阶。

### 首帧插值端点：让程序化粒子和 track 走同一套语义

`track` 的粒子由原版按 partialTick 在相邻两条权威位置之间插值；而 `spawn` 的粒子直接钉在当前位置，
于是「每 tick 采样、逐颗铺尾迹」会出现**最多一整 tick 的错位**（尾迹冒在头部前面或落在后面）。
给上「上一 tick 的位置」，第一帧就从它扫到当前位置，两边语义就一致了：

```kotlin
// 投射物每 tick 采样一次，尾迹颗粒带着上一 tick 的位置出生
manager.create().position(now).prevPosition(before)
    .scale(0.3f).lifetime(10).fadeOut(6).spawn()
```

不给 `prevPosition` 就是原来的行为（跳变出生）。延迟生成（`delay`）没有「上一 tick」可言，
与 `prevPosition` 同用时后者不生效。

### 运行时发射器（ParticleEmitter）

「每 tick 采样、逐颗 spawn + 逐颗更新包」的写法有两个毛病：出现时刻被 tick 量化（高刷下一跳一跳地长），
带宽还是 O(粒子)。发射器把这件事下沉成机制——**服务端声明一次，客户端按渲染帧用插值后的锚点位置自己发射**：

```kotlin
val trail = manager.emitter(Anchor.Movable(pos, velocity))   // 也可以 Anchor.Fixed / Anchor.Entity
    .spacing(0.15)        // 按里程：每 0.15 格一颗（拖尾：速度变了密度不变）
    .life(10)             // 每颗活 10 tick
    .scale(0.4f)
    .color(Color.WHITE)
    .style(ParticleStyle.SOFT_DOT)   // 与 Builder 同款外观接口（texture / uv / additive / …）
    .fadeOut(8)           // 寿命内淡出（等价于 alphaCurve）
    .shrinkTo(0.2f, 8)
    .spawn()

// 投射物每 tick 挪一次锚点（只在变更时发包）
trail.updateAnchor(Anchor.Movable(newPos, newVel))
// 密度按速度自适应，或者干脆改成按时间发射
trail.spacing(0.1)
trail.interval(2)         // 每 2 tick 一颗（intervalMs 可给到渲染帧粒度）

trail.stop()              // 停止发射；已经生成的粒子各自走完寿命
trail.isActive()
```

- **两种口径**：`spacing(格)` 按锚点走过的里程发射（位置严格落在段内等距点上，与帧率无关）；
  `interval(ticks)` / `intervalMs(ms)` 按时间发射。
- **逐颗抖动**：`jitter(格)` 让每颗垂直运动方向随机偏开（圆盘内均匀），`offsetAlong(格)` 整条前后挪。
  偏移由「发射器 id + 第几颗」的哈希算出，**确定性**：同一声明在任何客户端上第 N 颗的偏移相同。
- **锚点插值**：客户端每渲染帧取锚点的本帧位置 —— 实体取 `getPosition(partialTick)`，
  可移动锚点按**相邻两个服务器样本**插值（`lerp(prev, cur, partialTick)`），与 `track` 粒子同相位。
  瞬移（一条样本跳超 8 格）与断流（>3 tick 没样本）都按跳变处理：停住或直接落到新位置，
  不会在两点之间扫出一条假轨迹；Esc 暂停时不推进。
- **外观与曲线**：`color/scale/style/texture/uv/aniso/billboard/spin/alignTo/additive/glowing/lightLevel`、
  `alphaCurve/sizeCurve/colorCurve/fadeOut/shrinkTo` 与 `Builder` 同一套语义；
  `velocity(...)` 给每颗粒子一个出生速度（默认静止，拖尾要留在原地淡出）。
- **运行期改参数**：`spawn()` 返回的句柄上重新调同名方法即可，变更走小包、**已生成的粒子不受影响**：
  `life(ticks)` / `scale` / `color` / `curve` / `fadeOut` / `shrinkTo` / `jitter` / `offsetAlong` / `visual`…
  锚点变更（`updateAnchor`）只发锚点段、密度变更（`spacing`/`interval`）只发口径段，
  所以带阻力的投射物可以随速度连续改 `life` 而不用重挂声明。
- **上限**：`maxAlive(n)`（默认 4096）限制单个发射器同时存活的粒子数，防止间距给太小把客户端灌满；
  客户端还有全局的 `maxRenderParticles` 兜底。
- **断线/换维度/走进范围**：PD 自己负责补发声明（后进服的玩家不会看到一条空尾迹）。
- **游戏语义留在调用方**：「拖尾长度按格给、寿命 = 长度 ÷ 速度、总强度倍率」这些仍由你自己算，
  发射器只提供机制。

### 批量生成（一次包铺一整条尾迹）

逐颗 `spawn()` 是「一颗一个包」。一颗一颗攒不划算时（一条尾迹、一次爆发），用 `ParticleSpawnSpec`
攒好一批，一次包发完（每玩家按可见性裁剪、单包最多 256 条）：

```kotlin
val specs = (0 until 12).map { i ->
    ParticleSpawnSpec.at(base.add(dir.scale(i * 0.15)))
        .scale(0.3f).lifetime(10)
        .color(200, 220, 255)
        .fadeOut(6)
        .visual(ParticleVisual().style(ParticleStyle.SOFT_DOT).additive(true))
}

manager.spawnAll(specs)                       // 一次包；返回与 specs 一一对应的句柄
val batch = ParticleBatch(manager)
batch.spawnAll(specs)                         // 直接从批量生成建一个粒子集
```

`ParticleSpawnSpec` 与 `Builder` 的字段一一对应（位置/颜色/缩放/寿命/外观/寿命曲线/`prevPosition`），
服务端会按维度上限逐条判定：被拒绝的那些在返回值里是 `null` / 不计入成员数，不会静默丢。

### 逐 tick 跟随与力驱动

`track` 直设位置（无缓动）：客户端每个 tick 消费一条，原版按 partialTick 在**相邻两条**之间插值；
某个 tick 没收到新位置时端点原地保持——不会退回上一段起点，所以高速目标也不会前后抖。

```kotlin
// 投射物本体：每 tick 贴住真实位置（位置只有这一处来源）
handle.track(pos.x, pos.y, pos.z)
handle.position()      // 只读回服务端的权威位置；粒子不存在时返回 null
```

逐粒子每 tick 一个包在多粒子场景下太贵，用批量接口（一个包覆盖多颗，逐玩家按可见性裁剪，
**超过单包上限自动拆包**——公开 API 对成员数没有上限，协议上限由 PD 兜住）：

```kotlin
val ids = handles.map { it.id }
manager.trackAll(ids, positions)         // 两个列表按顺序一一对应，长度不同按短的一方截断
manager.setVelocityAll(ids, velocities)  // 每颗粒子各自的速度，一样是一个包
manager.applyForceAll(ids, accelerations, ticks = 1)  // 每颗粒子各自的加速度，共用一个 ticks
```

> 批量载荷的单包上限：速度 / 位置 / 力 / 销毁 / 锚点更新都是 512 条，生成是 256 条。
> 发送端按这个上限拆包（顺序、id↔向量对应关系与最终状态都不变），构造超限的载荷会**当场报错**
> 而不是把包发出去让客户端解码失败掉线。

需要「沿一个力运动」而不是逐 tick 报位置时用 `applyForce`：只在开始施力时下发一次，
之后服务端与客户端按同一规则逐 tick 积分（速度 += 加速度，位置 += 速度）。

```kotlin
handle.applyForce(Vec3(0.0, -0.05, 0.0))     // 无限施力，直到被下一次指令覆盖
handle.applyForce(accel, ticks = 20)         // 只施 20 tick，之后按惯性继续
handle.applyForce(Vec3.ZERO, ticks = 0)      // 清除力（速度保留）
handle.setVelocity(Vec3.ZERO)                // 连速度一起停住
```

需要跟随实体（玩家/生物）时用实体锚点：服务端只在挂载时下发一次，之后位置由客户端每 tick 本地解析。

```kotlin
handle.attachTo(entity.id, Vec3(0.0, 0.9, 0.0))        // 世界空间偏移
handle.attachToLocal(entity.id, Vec3(0.0, 0.0, 0.6))   // 偏移随实体朝向（贴在身前/身侧）
```

`move` / `moveInstant` / `track` / `setVelocity` / `applyForce` 都接管位置并解除锚点；
`applyForce` 与 `setVelocity` 叠加（力不覆盖已有速度）。服务端暂停（单人按 Esc）时
客户端不再推进速度/力粒子，恢复后两端从同一状态继续。

批量施力（`applyForceAll`）的 `ticks` 全组共用，默认 `1`（只施这一 tick）：每 tick 按距离
重算一次力的用法就是每 tick 一包全组；要长效施力显式给 `-1`。

### 程序化粒子集（ParticleBatch）

成员逐 tick 增删、每颗粒子各自受力/轨迹/寿命、按位置条件回收的粒子流（黑洞吸入、重力场下落、
跟随实体的一圈粒子）用 `ParticleBatch`——它是**程序化**路径，不是编排式动画：
编排动画（成员固定 + 组级统一变换）用 `ParticleGroup`。

```kotlin
val swarm = ParticleBatch(manager)

// 每 tick 补一个（被维度上限拒绝时 ensureSize 立即返回，下次再补）
swarm.ensureSize(60) { i ->
    manager.create().position(shellPoint(i)).color(purple).scale(0.3f)
        .lifetime(60).spawn()?.applyForce(centripetal(i))   // 一次下发，之后两端自己积分
}

// 每 tick：按 PD 的权威位置/速度做判断，不用自己维护 id ↔ 状态表
swarm.removeIf { _, pos, _ -> pos.distanceTo(center) < 0.1 }
swarm.forEach { i, handle, pos, vel -> /* 想每 tick 亲自算位置也可以 */ }

swarm.trackAll(newPositions)                  // 一次包覆盖全组（成员顺序 = add 顺序）
swarm.setVelocityAll(velocities)              // 每颗粒子各自的速度，也是一个包
swarm.applyForceAll(accelerations, ticks = 1) // 每 tick 重算的力：一包全组，位置由两端积分
swarm.trackEach { i, handle -> nextPos(i) }
swarm.clear()
```

成员在 PD 侧过期/被销毁后自动出列（`evictDead()`，其它操作里也会顺带做）；
`track` / `trackAll` / `setVelocityAll` / `applyForceAll` / `removeIf` / `forEach`
读到的都是 PD 的服务端权威位置与速度。

### 身份与偏移：三套入口怎么选

| 入口 | 身份 | 偏移语义 | 粒度 |
|---|---|---|---|
| `ParticleHandle.attachTo(entity / uuid / entityId, offset)` | 实体（uuid 优先） | 世界空间，相对实体脚底 `Entity.position()` | 单粒子 |
| `ParticleHandle.attachToLocal(...)` | 同上 | 实体局部（随朝向） | 单粒子 |
| `ParticleGroup.followEntity(uuid, offset, local = false)` | 实体 UUID | 组轴心相对实体的偏移；`local = true` 时整组随朝向 | 整组 |
| `ParticleGroup.defineEntity(handle, uuid)` | 实体 UUID | 供公式 `get_entity_*(handle)` 取值 | 动画表达式 |

推荐写法：**别把两段偏移隐式相加**。要「实体 + 轴心」两层，就先用
`followEntity(uuid, entityOffset, local)` 把轴心钉在实体上，再用粒子的 `offsetFromPivot(hx, hy, hz)`
表达相对轴心的位置——两段各写各的，改一段不会牵动另一段。单粒子要贴实体就直接
`attachTo`/`attachToLocal`，一个包一次、之后零带宽。

实体身份的生命周期语义（两条路径一致）：实体不在场（未加载/已消失/换维度）时位置保持不动，
实体再出现即继续跟随；锚点不会替粒子决定寿命。

---

## 八、缓动（EasingType）

预设：`LINEAR`、`EASE_IN/OUT/IN_OUT`、各 `*_QUAD / *_CUBIC / *_BOUNCE / *_ELASTIC` 变体，共 14 种。

无缓动（阶跃）：`EasingType.NONE`，保持前一关键帧值直到下一关键帧。

自定义贝塞尔：

```kotlin
val custom = EasingType.custom(0.68, -0.55, 0.265, 1.55)   // (x1,y1,x2,y2)
```

---

## 九、播放 .pdraw 动画引擎（ServerAnimationManager）

除了用代码实时绘制，依赖模组还可以直接播放网页编辑器导出的 `.pdraw` 动画文件，并在运行时修改变量。

服务端权威模型：`ServerAnimationManager` 只把动画定义与指令下发给客户端，粒子求值和渲染在客户端本地逐 tick 进行。

### 播放 / 停止

```kotlin
import work.nekow.particledrawing.animation.AnimationLoader
import work.nekow.particledrawing.animation.ServerAnimationManager

// 一行式：播放 <gameDir>/animations/<name>.pdraw，返回本次播放 ID；文件不存在返回 null
val animId = ServerAnimationManager.playByName(dim, level.players(), "magic_circle", origin) ?: return

// 或自行提供 JSON 来源（如模组 jar 内置资源、数据库等）
val json = AnimationLoader.load("magic_circle")          // 按名读取 animations/ 目录
ServerAnimationManager.play(dim, players, json, origin)

// 停止单次播放（只通知该次覆盖到的玩家）
ServerAnimationManager.stop(animId, players)

// 停止整个维度的全部播放
ServerAnimationManager.stopAll(dim, players)
```

### 运行时修改变量

变量更新会**清空该变量的关键帧并把基值设为给定数值**，下一 tick 生效。
`value` 必须是数字字符串（非数字会被当作 `0`）：

```kotlin
// 把半径变量改成固定数值
ServerAnimationManager.updateVariable(animId, "rad", "4", players)

// 速度也可以动态调整
ServerAnimationManager.updateVariable(animId, "speed", "2", players)
```

### 播放状态查询

```kotlin
ServerAnimationManager.isActive(animId)             // 该次播放是否仍在进行
ServerAnimationManager.activePlaybacks(dim)         // 维度内活跃播放 ID 快照
ServerAnimationManager.activePlaybacksAll()         // 全部维度
ServerAnimationManager.playbackPlayers(animId)      // 该次播放覆盖的玩家 ID
```

### 入场编排（粒子起始时间 + 寿命 + 入场预设）

> 本节字段（`st`/`life`/`ent.d`）来自 `.pdrawc` 时间轴，单位是**毫秒**；而代码 API 的
> `delay()`/`stagger`/`lifetime()`/`destroyAfter()` 走服务端 game tick（1 tick = 50ms），两者不要混用。

每个粒子/函数对象可带 `st`（起始毫秒）：`t < st` 时粒子**完全不在渲染管线中**（不是 alpha=0，而是不生成），
到点瞬间出现；循环回卷后会重新按各自 `st` 重放入场顺序。时长（`timelineLength()`）自动计入最晚的 `st`。

静态粒子还可带 `life`（寿命，毫秒，缺省 -1 = 无限）：`t ≥ st+life` 后粒子回收消失；
有限寿命同样计入 `timelineLength()`。时间轴上表现为条形长度——无限寿命向右无限延伸（∞ 标记），
拖拽右端手柄调整、双击右端在 无限⇄有限 间切换；也可在属性面板「寿命(ms)」中直接编辑（-1=无限）。

函数对象派生粒子可在 `process` 中遍历 `this.particles`，用粒子句柄 `p.life = <毫秒>` 设定逐粒子寿命（缺省 -1 = 无限）；
其可见期由「对象级 `st` 入场 → 对象整体时长 `duration`（≤0 视为无时长上限）→ 逐粒子寿命」三重门控决定，
整体提前退场请通过缩短时长或对象级 `st` 编排实现。

- 编辑器：底部时间轴模块顶边可拖拽整体调高；下方为 AE 式图层区——组聚合条（可展开成员行）、函数对象条（长度=动画跨度），
  横向拖拽即改 `st`（整数刻度吸附，抓取点保持相对位置），组条拖拽整体平移；图层区高度可拖拽分隔条调整；
- 文件字段：粒子与函数对象均可选 `"st": <毫秒>`；粒子可选 `"life": <毫秒>`；
  `"ent": {"p": "fade", "d": <毫秒>}` 为出场后淡入预设（`d` 内 alpha 线性 0→1）。预设词表是扩展接口：
  新增 preset 约定即可接入更复杂的入场动画。

### 典型场景：技能动画 + 动态参数

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

延迟收尾可配合任意服务端调度手段（如原版 `TickTask`）：

```kotlin
level.server.tell(object : TickTask(level.server.tickCount + 60) {
    override fun run() {
        if (ServerAnimationManager.isActive(id)) ServerAnimationManager.stop(id, level.players())
    }
})
```

> 客户端模组也可在本地直调 `ClientAnimationManager.play/stop/updateVariable/reloadTextures`（仅客户端环境）。

---

## 十、代码生成动画（Animation.create）

除了用 `Draw` 实时画形状、或播放网页编辑器导出的 `.pdrawc`，还可以**用代码直接构建一个动画**，
然后一行下发播放或继续链式操作。动画模型与 `.pdrawc` 播放完全同构：粒子、轨道、组、函数对象、
摄像机、贴图都可声明，播放走同一套服务端权威进度与客户端渲染链路。

### Kotlin DSL（推荐）

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
        duration = 10000                           // 时长毫秒（0 = 无上限）
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

// 播放并链式操作
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

### TrackPr 分量枚举

轨道的 `pr` 字段是类型安全的 `TrackPr` 枚举（`work.nekow.particledrawing.animation.TrackPr`），
与 `.pdrawc` 二进制里的分量序号一一对应。合法值：

| 属性 | 枚举常量 |
| --- | --- |
| 位置 | `TrackPr.POS_X` `TrackPr.POS_Y` `TrackPr.POS_Z` |
| 速度 | `TrackPr.VEL_X` `TrackPr.VEL_Y` `TrackPr.VEL_Z` |
| 颜色 | `TrackPr.COL_R` `TrackPr.COL_G` `TrackPr.COL_B` `TrackPr.COL_A` |
| 缩放 | `TrackPr.SCL_X` `TrackPr.SCL_Y` `TrackPr.SCL_Z` |
| 公转 | `TrackPr.ROT_X` `TrackPr.ROT_Y` `TrackPr.ROT_Z` |
| 自转 | `TrackPr.SPIN_X` `TrackPr.SPIN_Y` `TrackPr.SPIN_Z` |
| 公转中心 | `TrackPr.CENTER_X` `TrackPr.CENTER_Y` `TrackPr.CENTER_Z` |
| 摄像机 | `TrackPr.FOV` `TrackPr.TARGET_X` `TrackPr.TARGET_Y` `TrackPr.TARGET_Z` |

`TrackPr.FOV` 为标量分量（无 `.x/.y/.z`）。

### Animation 链式操作

| 方法 | 说明 |
| --- | --- |
| `play(level, players, origin)` | 向指定玩家下发并开始播放；返回自身 |
| `play(players, origin)` | 从首个玩家维度推导 `ServerLevel` 的便捷重载 |
| `stop()` | 停止本次播放 |
| `updateVariable(name, value)` | 运行时更新函数对象变量（下一 tick 生效） |
| `isActive()` | 本次播放是否仍在进行 |

> 代码生成动画走独立的结构化网络载荷（不验签、不生成 `.pdrawc` 文件）；播放记录同样支持
> 维度切换/重生/重连后的自动重发，与其他玩家帧号保持一致。

---

## 十一、完整示例集

### 例 1：上升的螺旋烟雾环

```kotlin
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
```

### 例 2：双组并行编舞

两条独立链式调用天然并行推进，无需额外编排器：

```kotlin
fun dance(m: ParticleManager, c: Vec3) {
    val inner = Draw.circle(m, c, 2.0, 50, colorFn = ColorSource.of(Color.CYAN))
    val outer = Draw.sphere(m, c, 4.0, 250, scale = 0.25f)

    inner.fadeIn(10).spin(Vec3(0, 1, 0), Math.PI / 30)
    outer.fadeIn(30).spin(Vec3(0, 1, 0), -Math.PI / 90)   // 反向慢转
         .pulse(1.15f, halfPeriodTicks = 15)
}
```

### 例 3：扩散涟漪

```kotlin
fun ripples(m: ParticleManager, c: Vec3, waves: Int) {
    repeat(waves) { i ->
        Draw.circle(m, c, radius = 1.0 + i * 1.5, count = 50)
            .fadeIn(5)
            .delay(i * 15)          // 每道波错开 15 tick
            .fadeOut(20)
    }
}
```

---

## 十二、注意事项

1. **主线程调用**：所有 API 必须在服务端主线程调用（命令、事件回调天然满足）；内部调度任务也在主线程执行。
2. **数量限制**：受服务端配置 `maxParticlesPerDimension`（维度总量）与 `maxParticlesPerPlayer`（单人追踪量）约束；达到上限时 `spawn()` 返回 null。
3. **可见性同步**：引擎按视距自动增减同步给客户端的粒子；持续动画只对已追踪该组的玩家广播。
4. **无限动画要停止**：无限模式（`durationTicks = -1` / `cycles = -1`）的 spin/pulse 会一直占用 tick 与带宽，记得用 `stopContinuous()` 或让组 `fadeOut()`/`destroyAfter()` 收尾；组销毁会自动取消其全部持续动画。
5. **stagger 与组查询**：`stagger > 0` 时粒子是陆续出现的，期间 `group.size()` 会逐步增长。
6. **Java 调用**：所有带默认参数的方法均生成 `@JvmOverloads` 重载；lambda 用 `ColorSource` 接口实现。
7. **外观不热改**：贴图 / UV / 各向异性 / 朝向 / 加色都在生成那一次定死（这是「零逐 tick 开销」的前提），
   中途只能改颜色、缩放标量、位置、发光等级；确实要换外观就 `remove()` 后重新生成。
8. **延迟生成的粒子**：`delay(n)` 期间它在 PD 侧还不存在，句柄上的操作一律无效，
   也读不到权威位置；`ParticleBatch` 会把延迟成员留到到点（不会误摘），但到点后仍不存在就按普通已失效成员出列。
9. **发射器要收尾**：`ParticleEmitter` 的声明不随调用方消失——用完（法术结束、投射物消亡）要 `stop()`，
   否则客户端会一直发下去。发射器不是「开火一次就结束」的 API，也不是 `ParticleGroup` 的替代品。
10. **批量生成的条数上限**：`ParticleManager.spawnAll` 单次最多 `MAX_SPAWN_BATCH`（256）条，
   超出的规格不生成（返回值比入参短），服务端再按每玩家上限裁剪——密集场景分批调用而不是堆成一个大列表。
