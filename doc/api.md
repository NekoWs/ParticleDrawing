# 核心 API

所有公开 API 位于 `work.nekow.particledrawing.api` 包。以下按使用频率组织说明。

## 目录

- [ParticleManager —— 入口](#particlemanager--入口)
- [ParticleHandle —— 单粒子句柄](#particlehandle--单粒子句柄)
- [ParticleGroup —— 粒子组](#particlegroup--粒子组)
- [Draw —— 绘图工具](#draw--绘图工具)
- [Color —— 颜色](#color--颜色)
- [ParticleStyle —— 粒子样式](#particlestyle--粒子样式)
- [TransformOp —— 变换操作](#transformop--变换操作)
- [缓动系统：EasingType / EasingCurve](#缓动系统easingtype--easingcurve)
- [底层引擎 ServerParticleEngine](#底层引擎-serverparticleengine)

---

## ParticleManager —— 入口

每个维度一个门面实例，负责创建粒子和粒子组，内部委托给 `ServerParticleEngine`。

```kotlin
val manager = ParticleManager.of(serverLevel)   // 推荐：传入 ServerLevel
val manager2 = ParticleManager.of(level)        // 接受 Level，但必须为 ServerLevel，否则抛异常
```

| 成员 | 说明 |
| --- | --- |
| `create()` | 返回 `ParticleHandle.Builder`，用于流式生成单粒子 |
| `createGroup(pivot: Vec3)` | 创建空粒子组，返回 `ParticleGroup` |
| `getGroup(groupId: UUID)` | 获取已存在的组，不存在返回 `null` |
| `getEngine()` | 获取底层 `ServerParticleEngine` |
| `level` / `dimensionId` | 关联的 `ServerLevel` 与维度 UUID |

---

## ParticleHandle —— 单粒子句柄

由 `ParticleHandle.Builder.spawn()` 返回，用于对已生成粒子做后续控制。

| 方法 | 说明 |
| --- | --- |
| `move(target, durationTicks, easing)` | 缓动移动到目标位置 |
| `moveInstant(target)` | 立即移动（等价 `move(target, 0, EasingType.LINEAR)`） |
| `recolor(color, durationTicks, easing)` | 缓动重着色 |
| `resize(scale, durationTicks, easing)` | 缓动缩放 |
| `remove()` | 立即销毁该粒子 |
| `data()` | 读取服务端 `ParticleData`（不存在返回 `null`） |

### Builder 用法

```kotlin
val handle = manager.create()
    .style(ParticleStyle.DUST)
    .position(x, y, z)                // 或 .position(Vec3)
    .color(Color.RED)                 // 或 .color(r, g, b[, a])
    .scale(0.5f)
    .lifetime(100)                    // tick；-1 = 永存
    .group(groupId)                   // 可选，关联到组
    .glowing(true)                    // 可选，发光
    .offsetFromPivot(Vec3)            // 可选，相对组轴心偏移
    .spawn()                          // 返回 ParticleHandle?（达到维度上限时为 null）
```

| Builder 方法 | 默认值 |
| --- | --- |
| `style(style)` | `ParticleStyle.DUST` |
| `position(...)` | `Vec3.ZERO` |
| `color(...)` | `Color.WHITE` |
| `scale(scale)` | `1.0f` |
| `lifetime(ticks)` | `-1`（永存） |
| `group(groupId)` | `null` |
| `glowing(glowing)` | `false` |
| `offsetFromPivot(offset)` | `Vec3.ZERO` |

---

## ParticleGroup —— 粒子组

一组可整体变换的粒子。由 `Draw` 工具或 `ParticleManager.createGroup` 创建。

```kotlin
val group = Draw.circle(manager, center, 5.0, 64, Draw.Axis.XZ)
group.rotate(Vec3(0.0, 1.0, 0.0), Math.PI * 2, 80, EasingType.EASE_IN_OUT)
group.move(Vec3(0.0, 2.0, 0.0), 60, EasingType.EASE_OUT_BOUNCE)
group.recolor(Color.BLUE, 40, EasingType.EASE_IN_OUT)
group.scale(1.5f, 30, EasingType.EASE_OUT)
group.remove()                        // 销毁整组
```

| 方法 | 说明 |
| --- | --- |
| `setPivot(pivot)` | 设置后续变换的基准点 |
| `move(delta, durationTicks, easing)` | 平移整组（`pivot` 随 delta 更新） |
| `rotate(axis, radians, durationTicks, easing)` | 绕基准点旋转 |
| `recolor(color, durationTicks, easing)` | 整组重着色 |
| `scale(targetScale, durationTicks, easing)` | 相对基准点缩放 |
| `add(handle?)` | 将粒子加入组（`handle` 可为 null，此时跳过） |
| `size()` | 组成员数量 |
| `remove()` | 销毁整组及其粒子 |

### 运动（Motion）便捷方法

```kotlin
group.rotateMotion(Math.toRadians(100.0))     // 绕 X 轴，100°/秒
group.colorGradientMotion()                   // 默认 HSB 渐变
group.colorGradientMotion(params)             // 自定义渐变参数
group.followPlayerMotion(0.06)                // 跟随玩家，指数平滑
group.scaleByDistanceMotion(1.0, 0.05, 6.0)   // 按玩家距离缩放
group.addMotion("rotate", doubleArrayOf(0.0, 1.0, 0.0, 0.5))  // 自定义算法
group.stopMotion()                            // 停止所有运动
```

详见 [运动算法](./motion-algorithms.md)。

---

## Draw —— 绘图工具

`Draw` 提供高级形状生成，每个方法返回 `ParticleGroup`。默认颜色为白色、默认样式为 `DUST`、默认缩放见各方法。

| 方法 | 说明 |
| --- | --- |
| `line(manager, start, end, count[, color[, style, scale]])` | 两点间线段 |
| `circle(manager, center, radius, count, axis[, color[, style, scale]])` | 圆环 |
| `disc(manager, center, radius, perimeterCount, layers, axis[, color, style, scale])` | 同心圆叠加的实心圆盘 |
| `curve(manager, posFunc, steps, color, style, scale)` | 参数曲线，`posFunc(t)` 返回世界坐标，`t ∈ [0,1]` |
| `sphere(manager, center, radius, count[, style, scale])` | 斐波那契球面分布 + 彩虹渐变 |
| `triangle(manager, center, radius[, segmentsPerEdge, rotationOffset, axis, color, style, scale, group])` | 正三角形 |
| `hexagram(manager, center, radius[, segmentsPerEdge, axis, color1, color2, style, scale])` | 六芒星（两三角旋转 60°） |
| `cuboid(manager, center, width, height, depth[, particlesPerAxis, hollow, color, style, scale])` | 3D 长方体网格 |
| `rect(manager, center, width, height[, particlesPerAxis, hollow, axis, color, style, scale])` | 2D 矩形网格 |

### Draw.Axis

描述 2D 图形所在平面：

| 值 | 平面 |
| --- | --- |
| `XZ` | 水平面（默认，Y 为法线） |
| `XY` | 朝 Z 方向的垂直面 |
| `YZ` | 朝 X 方向的垂直面 |

示例：

```kotlin
val circle = Draw.circle(manager, center, 5.0, 64, Draw.Axis.XZ,
    Color.CYAN, ParticleStyle.DUST, 0.4f)

val sphere = Draw.sphere(manager, center, 3.0, 800)

val box = Draw.cuboid(manager, center, 9.0, 9.0, 9.0, 20, hollow = false)
```

---

## Color —— 颜色

不可变 RGBA 颜色，分量范围为 `[0, 1]` 的 `Float`。

```kotlin
val red = Color.RED
val semiBlue = Color.of(0f, 0f, 1f, 0.5f)
val teal = Color.ofInt(0, 128, 128)
val hs = Color.ofHsb(0.55f, 0.9f, 0.9f)
```

| 成员 | 说明 |
| --- | --- |
| `r / g / b / a` | 浮点分量 `[0,1]` |
| `rInt / gInt / bInt / aInt` | 整数分量 `[0,255]` |
| `withAlpha(alpha)` | 返回替换透明度后的新颜色 |
| `multiply(factor)` | 颜色乘系数 |
| `lerp(target, t)` | 线性插值（t 自动 clamp 到 `[0,1]`） |
| `packABGR()` / `packARGB()` | 打包为整型 |
| `luminance()` | 亮度 |
| `isOpaque()` / `isTransparent()` | 是否不透明 / 全透明 |

### 工厂方法

| 工厂 | 说明 |
| --- | --- |
| `of(r, g, b[, a])` | 浮点分量 |
| `ofInt(r, g, b[, a])` | 整数分量 |
| `ofPacked(abgr)` | 从 ABGR 整型还原 |
| `ofHsb(hue, saturation, brightness[, alpha])` | HSB 转 RGB |

### 预设常量

`WHITE`、`BLACK`、`RED`、`GREEN`、`BLUE`、`YELLOW`、`CYAN`、`MAGENTA`、`ORANGE`、`TRANSPARENT`。

---

## ParticleStyle —— 粒子样式

枚举，映射到原版粒子精灵与渲染类型。

| 枚举 | 精灵 | 渲染类型 | 支持颜色 |
| --- | --- | --- | --- |
| `DUST` | `generic_0` | OPAQUE | ✅ |
| `FLAME` | `flame` | LIT | ❌ |
| `SOUL_FIRE` | `soul_fire_flame` | LIT | ❌ |
| `NOTE` | `note` | OPAQUE | ✅ |
| `HEART` | `heart` | TRANSLUCENT | ❌ |
| `SPARK` | `generic_6` | TRANSLUCENT | ❌ |
| `GLOW` | `glow` | LIT | ❌ |
| `BUBBLE` | `bubble` | TRANSLUCENT | ❌ |
| `DRAGON_BREATH` | `generic_0` | OPAQUE | ✅ |
| `SMOKE` | `generic_7` | TRANSLUCENT | ❌ |

渲染类型（`ParticleStyle.ParticleRenderStyle`）：`OPAQUE`、`TRANSLUCENT`、`LIT`、`LIT_TRANSLUCENT`。

> `supportsColor` 为 `false` 的样式会忽略传入颜色（始终以白色/精灵自带颜色渲染）。

---

## TransformOp —— 变换操作

描述应用于 `ParticleGroup` 的变换，不可变、面向网络序列化。

```kotlin
TransformOp.translate(delta, pivot)
TransformOp.rotate(axis, radians, pivot)
TransformOp.recolor(color)
TransformOp.scale(targetScale, pivot)
```

枚举 `Type`：`TRANSLATE`、`ROTATE`、`RECOLOR`、`SCALE`。一般情况下通过 `ParticleGroup` 的便捷方法调用即可，无需直接构造。

---

## 缓动系统：EasingType / EasingCurve

缓动基于三次贝塞尔曲线（CSS `cubic-bezier` 同款参数）。`EasingType` 封装预设与自定义曲线，并支持网络序列化。

### 预设缓动

| 常量 | 说明 |
| --- | --- |
| `LINEAR` | 线性 |
| `EASE_IN` / `EASE_OUT` / `EASE_IN_OUT` | 标准缓入 / 缓出 / 缓入缓出 |
| `EASE_IN_QUAD` / `EASE_OUT_QUAD` / `EASE_IN_OUT_QUAD` | 二次方 |
| `EASE_IN_CUBIC` / `EASE_OUT_CUBIC` / `EASE_IN_OUT_CUBIC` | 三次方 |
| `EASE_IN_BOUNCE` / `EASE_OUT_BOUNCE` | 弹跳 |
| `EASE_IN_ELASTIC` / `EASE_OUT_ELASTIC` | 弹性 |

### 自定义曲线

```kotlin
val custom = EasingType.custom(0.2, 0.0, 0.8, 1.0)          // 直接指定控制点
val matched = EasingType.fromCurve(0.42, 0.0, 0.58, 1.0)   // 匹配最近预设，否则自定义
```

### 序列化

- `easing.serialize(): DoubleArray` —— 预设返回 `[ordinal, 0,0,0,0]`，自定义返回 `[-1, x1,y1,x2,y2]`。
- `EasingType.deserialize(data)` —— 反向还原。
- `EasingCurve.fromCss("cubic-bezier(...)")` —— 从 CSS 字符串解析。
- `easing.evaluate(t)` —— 计算进度 `t ∈ [0,1]` 的缓动值。

---

## 底层引擎 ServerParticleEngine

`work.nekow.particledrawing.core.server.ServerParticleEngine` 是服务端权威引擎，每个维度一个实例。多数场景下通过 `ParticleManager` 即可，需要更底层控制时可直接使用。

```kotlin
val engine = ServerParticleEngine.getOrCreate(dimensionId)

// 链式更新构建器
engine.update(particleId)
    .position(x, y, z)
    .color(Color.BLUE)
    .easing(EasingType.EASE_OUT, 10)
    .send(players)
```

| 成员 | 说明 |
| --- | --- |
| `spawnParticle(...)` | 生成粒子并广播 |
| `updateParticle(...)` | 增量更新位置/颜色/缩放 |
| `update(id)` | 返回 `UpdateBuilder`（链式更新） |
| `applyGroupTransform(...)` | 对整组应用变换 |
| `destroyParticle(id, players)` | 销毁单粒子 |
| `destroyGroup(groupId, players)` | 销毁整组 |
| `createGroup(groupId, pivot)` | 创建组 |
| `getParticle(id)` / `getGroup(id)` | 查询 |
| `particleCount()` / `groupCount()` | 计数 |
| `clearAll(players)` | 清空维度全部粒子 |
| `sendMotion(...)` | 下发运动指令 |
| `tick(players)` | 每 tick 推进生命周期 |

静态方法：`getOrCreate(dimensionId)`、`get(dimensionId)`、`clearDimension(dimensionId)`。
