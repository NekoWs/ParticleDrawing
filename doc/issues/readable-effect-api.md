# PD-READABLE-API：可读的特效编写接口

状态：待实现。以下接口名和代码均为设计草案，不代表当前版本已经提供这些功能。

目标：调用处能直接读出形状、尺寸、时间和动画行为；单位换算、表达式生成、成员对应和回收交给PD。保留现有低层API与播放引擎。

## 现象与源码依据

消费项目为相邻仓库PrimalSpells，路径均相对其仓库根目录。

| 位置 | 难读或容易误用的写法 | 原因 |
|---|---|---|
| `magic/effect/visual/WandCastingFx.kt` | `t/260`、`t*0.00015`、`duration*50`、替换字符串中的`t` | 组表达式时间为毫秒，玩法时间为刻；共享相位由调用方拼接 |
| 同上 | `1.0e12`、`-1.0e12`作为未开始/未结束标记 | 状态被编码为无含义的时间哨兵值 |
| 同上施法短闪 | `sin(i*127.1)*43758.5453`、`i*2.399963` | 确定性散列与近似黄金角分布藏在常数里 |
| `magic/effect/visual/PixelVfx.kt` | `Draw.line(manager,a,b,9,color,0.5f,...)` | 多个位置参数难以判断是数量、世界尺寸还是缩放倍率 |
| 同上、`ConcentratedLightFx.kt` | 手写叉积、备用轴、三角形/菱形逐边采样 | 形状与坐标基缺少统一的命名入口 |
| `magic/effect/lifecycle/ExplosionFx.kt` | 连续的`CurveKey.at(时间,倍率,缓动)`与规格/速度平行数组 | 段的含义不明显，容量拒绝后需要手动维持索引对应 |
| `magic/effect/visual/SawbladeFx.kt` | `i<64`、`i<88`式材质分区；`resize`后再延时清理 | 造型身份依赖成员拼接顺序，完成时机散落在业务代码中 |

上述`magic/`路径的完整前缀为`src/main/kotlin/work/nekow/primalspells/`。

当前PD已具备的能力：

- `api/Animation.kt`已有Kotlin DSL与Java Builder；不另建动画播放器。
- `api/ParticleGroup.kt`已有`expression(String,Int)`、变量缓动、`retire`和完成账本；新接口应复用。
- `api/Draw.kt`已有静态形状采样，`ParticleVisual`已有世界尺寸与朝向；缺少的是统一、带单位的编写门面。
- `api/ParticleCurve.kt`的实际求值采用**后一关键帧的easing**。目前部分KDoc同时描述为“到下一帧”，实现时需统一说明。
- `api/ParticleManager.kt`的`spawnAll`先截取前`MAX_SPAWN_BATCH`项，容量拒绝会返回null；`ParticleBatch.addAll`会跳过null。不能据此假定输入速度列表仍与存活成员一一对应。`doc/api-reference.md`关于自动分包的描述需要随实际实现核实更新。

需求针对代码编写接口。法术伤害、追踪强度、爆炸层数和配色等内容参数仍由消费方命名和组织，不变成PD全局规则。

## 1. 显式单位

提供公开、可组合的单位值；Kotlin属性糖与Java静态工厂表达同一含义。

| 类别 | Kotlin草案 | Java草案 | 契约 |
|---|---|---|---|
| 时间 | `6.ticks`、`250.milliseconds` | `Time.ticks(6)`、`Time.milliseconds(250)` | 1t=50ms；曲线时间保留小数刻 |
| 世界长度 | `1.2.blocks`、`1.5.mcPixels` | `Length.blocks(1.2)`、`Length.mcPixels(1.5)` | mcPixels明确为1/16格，不是贴图像素或编辑器scale |
| 角度 | `90.degrees`、`0.15.radians` | `Angle.degrees(90)` | 弧度/角度转换只在PD边界完成 |
| 角速度 | `0.15.radiansPerSecond` | `AngularSpeed.radiansPerSecond(0.15)` | 表达式内不再直接乘毫秒常数 |
| 速度/加速度 | `Velocity.blocksPerTick(...)`、`Acceleration.blocksPerTickSquared(...)` | 相同静态工厂 | 与位置、方向向量区分 |
| 无限寿命 | `Lifetime.infinite()` | 同名工厂 | 不再要求新代码传`-1`；不把无限值当有限时间计算 |

时间分量必须有限且满足使用场景的非负/正数要求。乘倍率仍保留单位；同量相除得到无量纲进度；不同量纲不能隐式相加。旧整数刻接口继续兼容。暂不支持小数的字段应明确拒绝或要求调用者显式选择取整方式，不能悄悄截断；曲线与表达式已有的小数时间精度必须保留。

## 2. 命名外观、曲线与生成规格

在现有`ParticleVisual`、`ParticleSpawnSpec`与曲线模型上提供DSL/Builder，不新增平行的数据模型或渲染器。

外观字段分别声明`worldSize`、`fullBright`、`light`、`blend`和`facing`。全亮不等于向世界照明；新门面创建的单颗、批量、Draw与发射器必须使用同一份外观契约，避免调用方反复给visual和Builder同时设置光照标记。保留旧API的默认行为，不暗改已有项目。

曲线采用“到达目标关键帧”的命名，缓动明确作用于上一帧至目标帧。下面是设计草案：

```kotlin
val timing = FragmentTiming(
    lifetime = 16.ticks,
    enter = 1.5.ticks,
    leave = 5.ticks,
)
val appearance = particleVisual {
    style = ParticleStyle.SQUARE
    worldSize(width = 1.5.mcPixels, height = 1.mcPixels)
    fullBright = true
    light = Light.none()
    blend = Blend.ALPHA
}
val shapeEnvelope = lifeCurve {
    scale {
        start(value = 0.0)
        to(value = 1.0, at = timing.enter, easing = Ease.OUT)
        hold(until = timing.lifetime - timing.leave)
        to(value = 0.0, at = timing.lifetime, easing = Ease.IN_OUT)
    }
}
```

`FragmentTiming`是消费方自己的命名参数类。`Ease`须有公开API入口，不让新代码必须导入`core.*`。曲线构建后不可变、可复用，多个通道相乘及通道上限沿用现有契约。重复时间点、反向时间、NaN、无限值与超限应在构建时给出明确诊断。

提供常用`sizeEnvelope(enter,leave)`与`fadeOut`糖，但必须说明寿命过短时的行为；默认拒绝不合法的段重叠，压缩时长需显式选择。迁移既有视觉时允许完整关键帧模式，不自动改变原曲线。

## 3. 类型化表达式与参数句柄

为`ParticleGroup.expression`增加类型化构建入口，生成现有专用标量表达式。不能把Kotlin闭包发到客户端，也不能每刻在服务端执行整段DSL。

- 构建一次表达式树；区分标量、长度、时间、角度、颜色及向量，提供成员索引/数量、组年龄、实体被动输入。
- 参数返回带类型的句柄，更新时使用句柄；未知名字、重复绑定、错误维度在构建时拒绝。
- 位置必须明确选择世界坐标或绑定坐标系下的局部坐标。只写尺寸/颜色时明确保留哪一种位置输入，不能隐式补世界原点。
- 提供`progress(duration)`、`smoothstep`、`lerp`、`clamp01`、`goldenAngle`与确定性成员随机数；随机种子、成员索引和salt均显式，逐帧求值不能重新抽随机数。
- 公共子表达式、常量和坐标基只计算必要次数；提供调试导出，能看到生成后的表达式、参数名、单位与所属部件。
- 不混同组标量表达式、函数对象脚本和UV表达式。类型化入口必须指明目标后端，超出该后端能力时拒绝并提示。

以`WandCastingFx.cast`中的短闪为例，将250ms/60ms与散列常数改成可解释的时间和随机输入。下面仅展示新接口的形状：

```kotlin
val timing = CastFlashTiming(travel = 5.ticks, enter = 1.2.ticks)
val origin = group.vectorParameter("origin", center)
val direction = group.directionParameter("direction", forward)

group.expression(duration = timing.travel) { frame ->
    val progress = frame.progress(timing.travel)
    val appear = smoothstep(frame.progress(timing.enter))
    val random = frame.member.random01(seed = castSeed, salt = "spread")
    val angle = frame.member.index * Angles.goldenAngle
    val width = sin(progress * 180.degrees) * lerp(0.08.blocks, 0.24.blocks, random)
    val distance = progress * lerp(0.25.blocks, 0.70.blocks, random)

    position.world(origin + direction * distance + vec3(
        cos(angle) * width,
        sin(angle) * width,
        sin(angle * 1.71) * width,
    ))
    scale(appear * square(1.0 - progress))
    color(CastPalette.flash)
}.retire()
```

`CastFlashTiming`与`CastPalette`归消费方；`1.71`仍是明确保留的造型比例，可继续在消费方命名。该示例用于说明编写体验，不承诺与旧`sin`散列逐点相同；迁移要求同图时应保留原散列或提供明确的兼容随机模式。时间、坐标、透明度和回收语义不能随迁移改变。

## 4. 形状、部件与坐标基

扩展`Draw`的命名规格入口；形状采样沿用现有实现。需要正多边形轮廓、圆环、折线、晶面，以及稳定的`basis.forward(direction,upHint)`。正前方与upHint平行、零向量、退化线段都应有明确结果或诊断。

```kotlin
val frame = Frames.ground(holder, followPosition = true, followYaw = false)
val triangle = Draw.polygon(manager) {
    name = "ground-triangle"
    coordinateFrame = frame
    sides = 3
    radius = 1.2.blocks
    samplesPerEdge = 24
    initialAngle = 90.degrees
    angularSpeed = 0.15.radiansPerSecond
    appearance = triangleAppearance
}
```

此入口应生成`ParticleGroup`及其必要表达式/锚点。跟随位置而不跟随实体旋转，不能靠消费方每刻重建坐标实现。世界坐标保持double精度。

同一造型支持命名部件与元数据选择，例如`"teeth"`、`"rim"`、`"core"`。部件声明各自颜色与尺寸，返回可操作的部件句柄，避免业务代码依赖`i<64`。元数据只用于编写/调试及批量选择，不要求每帧复制一份服务端成员簿记。各部件的局部坐标原点、顺序和粒子容量拒绝语义须明确。

## 5. 状态与退场

给已有完成账本和插值变量增加命名状态门面。`notStarted/running/completed`显式表达，不再用`±1e12`表示状态。不同提示可绑定同一PD时间参考，表达式直接读取时间句柄，不再手动维护`clockOffset`与正则替换`t`。

新接口需区分：

| 意图 | 语义 |
|---|---|
| 组形状缩向中心 | 改局部坐标距离与片元尺寸 |
| 只缩小片元 | 保留运动与形状位置，只改片元尺寸 |
| 爆炸/碎散退场 | 保持初速/加速度，单片熄灭，不能把整个球壳缩回中心 |
| 正常停止 | 停止新增，等待声明的有限尾段与完成信号 |
| 世界卸载/强制清理 | 立即撤销本世界的成员与任务 |

调用示意：`batch.exit { particleSize.to(0.0,over=8.ticks,easing=Ease.LINEAR); preserveMotion(); removeWhenComplete() }`。

表达式模式使用同一可组合的外观通道或变量端点，不能生成当前模式下不会执行的组糖指令。运行中重新退场/重定向应从当前合成值接续，重复停止不重复回调。完成账本仍由PD维护；不能把`Deferred.after(ticks+2)`搬进新门面作为通用回收方案。首次外观接管复用现有原子接管，禁止先闪出完整尺寸。

## 6. 生成与运动保持配对

扩展批量入口，让一条声明同时携带出生规格、初速度、加速度和部件元数据。调用方不再维护两到三份平行数组，也不需记住256条上限。

- 内部按现有包限制分段，处理第257条及以后输入，不静默截断。
- 返回按原始索引对应的结果，包含成功句柄或拒绝原因。容量拒绝发生在中间项时，其余项仍使用自己的速度、颜色和部件身份。
- 不隐式重试、改变种子或补发已经生成的项；容量拒绝正常返回，不当作网络/表达式错误。
- 尽量复用生成与批量运动载荷；API简化不能退化成每粒子每刻发包。
- 若初速度/加速度需要新增出生包字段，先列协议兼容方案；不能无版本说明地改字段布局。

## 实现顺序与兼容

1. 单位值、公开缓动入口、命名外观/曲线，先覆盖单颗、批量、Draw和发射器。
2. 类型化标量表达式、参数句柄、进度和确定性随机；接上现有表达式后端与诊断。
3. 形状坐标基、命名部件、状态和退场门面，再迁移复杂持杖法阵。
4. 批量配对与自动分段；只在确需变更载荷时升级网络协议。

不删除或暗改字符串表达式、现有Builder及负寿命旧入口。Kotlin与Java应有等价入口；DSL是编译期编写门面，不增加独立播放时钟、渲染器或新的粒子注册表。需要影响`.pdrawc`或编辑器语义时，再明确同步范围并执行双方一致性测试；纯编写门面优先降低到现有协议。

## 验收

- Kotlin DSL与Java Builder示例实际编译，错误单位不能混用；诊断包含效果/部件/参数和期望单位，不只输出最终字符串。
- 6t与300ms、1.5t与75ms等值；非有限值、负时长、溢出、需要取整的字段分别有测试；世界远坐标保持double精度。
- 曲线逐点等价、easing归属明确，重复通道/热更不意外叠乘；全亮与动态光源在四类生成入口中一致。
- 类型化表达式与其生成的旧表达式逐点对照；随机固定种子稳定，暂停、回绕、变量热更与共享相位不跳帧。
- 命名部件不随采样数改变而串色；0/1/256/257/513条批次、零容量与中间拒绝都保持数据配对。
- 真实PD输出验证实体脚下位置跟随但不跟随yaw；出生无闪现，普通退场连续，锯片碎散只收片元不内缩；停止发射后尾粒子自然结束。
- 记录同一配方的准备耗时、分配、网络包数及客户端求值成本；不得每帧构建DSL/解析字符串，也不得增加逐刻装饰同步。
- 单元测试不能替代游戏内视觉验收。PrimalSpells待PD完成后再构建匹配主/源码JAR，迁移持杖法阵、短闪、锯片和爆炸；保留原语义对照及当前退场门禁。
