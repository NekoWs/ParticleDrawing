# ParticleDrawing 开发者 API 指南

面向模组开发者：把 ParticleDrawing 作为依赖，用简单的 Kotlin / Java 代码在 Minecraft 世界中绘制粒子动画。

> 类与职责的完整清单见 [README.md](./README.md)。所有 API 位于 `work.nekow.particledrawing.api` 包。

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
10. [完整示例集](#十完整示例集)
11. [注意事项](#十一注意事项)

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
| `scale` | `Float` | 粒子大小（默认 0.5） |
| `stagger` | `Int` | 逐粒子入场延迟（tick），实现波浪式出现（默认 0 = 全部同时出现） |
| `group` | `ParticleGroup?` | 复用已有组而非新建（默认 null） |

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
```

### 波浪入场（stagger）

`stagger = N` 时第 i 个粒子延迟 i×N tick 出现：

```kotlin
// 200 个球面粒子每隔 2 tick 依次出现 → 约 7 秒的展开动画
Draw.sphere(m, center, 5.0, count = 200, stagger = 2)
```

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

### 一次性变换

```kotlin
group.move(Vec3(0.0, 2.0, 0.0), durationTicks = 30, easing = EasingType.EASE_OUT)
group.rotate(Vec3(0, 1, 0), radians = Math.PI, durationTicks = 40)
group.recolor(Color.BLUE, durationTicks = 20)
group.scale(ratio = 2f, durationTicks = 15)      // 放大到 2 倍（半径与视觉大小同步翻倍）
```

> `scale` 是**倍率**语义（2f = 两倍，0.5f = 一半）；`durationTicks = 0` 表示瞬时跳变，
> 需要渐变过程请给足时长。`stopContinuous` 同样受 `delay` 游标控制：
> `.spin(...).delay(100).stopContinuous()` = 转 100 tick 后停。

### 生命周期

```kotlin
group.fadeIn(durationTicks = 15)                 // 透明度 0 → 当前值
group.fadeOut(durationTicks = 20)                // 透明度 → 0 并销毁整组
group.destroyAfter(ticks = 200)                  // 定时销毁
```

### 持续运动（服务端逐步驱动，客户端平滑插值）

```kotlin
// 无限匀速旋转；负时长 = 无限，用 stopContinuous() 停止
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

// 呼吸脉冲：当前缩放 ↔ 目标倍率往复；cycles = -1 无限
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

// 2. 表达式指令：每粒子每 tick 求值一段函数对象代码（编辑器同款语法）；
//    用到什么取什么——get_entity_*/get_world_* 在需要处调用，无需预先声明属性
group.expression("""
    th = i / n * 2 * pi;
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
group.setVariableLive("speed", "2")              // 数字
group.setVariableLive("rad", "3 + sin(t * 0.2)") // 任意公式
```

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

---

## 八、缓动（EasingType）

预设：`LINEAR`、`EASE_IN/OUT/IN_OUT`、各 `*_QUAD / *_CUBIC / *_BOUNCE / *_ELASTIC` 变体，共 14 种。

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

变量更新会**替换表达式 → 清空该变量的关键帧 → 重编译函数对象**，下一 tick 生效。
value 支持任意公式表达式（可用 `i/n/t/pi/sin(...)` 等），不只是数字：

```kotlin
// 把半径变量改成随时间呼吸的表达式
ServerAnimationManager.updateVariable(animId, "rad", "3 + sin(t * 0.2)", players)

// 也可以只给一个常量
ServerAnimationManager.updateVariable(animId, "speed", "2", players)
```

### 播放状态查询

```kotlin
ServerAnimationManager.isActive(animId)             // 该次播放是否仍在进行
ServerAnimationManager.activePlaybacks(dim)         // 维度内活跃播放 ID 快照
ServerAnimationManager.activePlaybacksAll()         // 全部维度
ServerAnimationManager.playbackPlayers(animId)      // 该次播放覆盖的玩家 ID
```

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

## 十、完整示例集

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

## 十一、注意事项

1. **主线程调用**：所有 API 必须在服务端主线程调用（命令、事件回调天然满足）；内部调度任务也在主线程执行。
2. **数量限制**：受服务端配置 `maxParticlesPerDimension`（维度总量）与 `maxParticlesPerPlayer`（单人追踪量）约束；达到上限时 `spawn()` 返回 null。
3. **可见性同步**：引擎按视距自动增减同步给客户端的粒子；持续动画只对已追踪该组的玩家广播。
4. **无限动画要停止**：无限模式（`durationTicks = -1` / `cycles = -1`）的 spin/pulse 会一直占用 tick 与带宽，记得用 `stopContinuous()` 或让组 `fadeOut()`/`destroyAfter()` 收尾；组销毁会自动取消其全部持续动画。
5. **stagger 与组查询**：`stagger > 0` 时粒子是陆续出现的，期间 `group.size()` 会逐步增长。
6. **Java 调用**：所有带默认参数的方法均生成 `@JvmOverloads` 重载；lambda 用 `ColorSource` 接口实现。
