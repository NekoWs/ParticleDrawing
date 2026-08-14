# 动态光照

ParticleDrawing 允许粒子发光并照亮周围环境。发光粒子由 `ParticleHandle.Builder.glowing(true)` 标记，并叠加 GLOW 等发光样式以获得最佳效果。

## 系统组成

动态光照由两个互补的组件实现：

| 组件 | 职责 | 作用对象 |
| --- | --- | --- |
| `DynamicLightManager` | 维护活跃光源列表，按距离/亮度筛选，提供任意位置的光照等级查询 | 方块光照（经由 Mixin 注入）与实体光照 |
| `DynamicLightEngine` | 在服务端放置真实的 `minecraft:light` 光源方块，让原版光照引擎计算方块光照 | 方块（仅单机集成服务端） |

### DynamicLightManager

- 每帧由 `ParticleRenderHandler.onClientTick` 调用 `renderDynamicLights(engine, camera)`，从 `ClientParticleEngine.getGlowingParticles()` 收集发光粒子。
- 按「亮度 / (1 + 距离 × 0.1)」加权评分排序，取前 `maxDynamicLights` 个。
- `getDynamicLightLevel(x, y, z)` 遍历活跃光源，应用衰减函数取最大贡献，返回 `0..15` 的光照等级。
- 线程安全（`ReentrantReadWriteLock`），供渲染线程与 Mixin 查询并发访问。

### DynamicLightEngine

- 仅在**单机集成服务端**（`mc.singleplayerServer != null`）下工作。
- 将发光粒子映射到最近的 `BlockPos`，计算亮度等级（`max(r,g,b) × alpha × 15`，钳制到 `8..15`），放置 `minecraft:light` 方块。
- 记录原始方块，在粒子熄灭或越界后恢复；`DynamicLightPositions` 负责跨维度追踪所有被占据的位置，并在服务器停止 / 维度卸载时清理。

### 渲染注入（Mixin）

| Mixin | 注入点 | 作用 |
| --- | --- | --- |
| `EntityRendererMixin` | `EntityRenderer#getPackedLightCoords` 返回值 | 将动态光照叠加到实体的打包光照坐标 |
| `LightmapRenderStateExtractorMixin` | `LightmapRenderStateExtractor#extract` 头部 | 在计算光照贴图前刷新 `DynamicLightManager` 数据 |

## 使用示例

```kotlin
manager.create()
    .style(ParticleStyle.GLOW)   // LIT 渲染类型，视觉上自发光
    .position(x, y, z)
    .color(Color.ofHsb(hue, 1.0f, 1.0f))
    .scale(1.2f)
    .lifetime(-1)
    .glowing(true)               // 启用动态光照
    .spawn()
```

发光粒子的亮度由颜色与透明度决定：`亮度 = max(r, g, b) × a`。亮度低于约 `0.05` 的粒子不会生成光照。

## 配置

相关配置项见 [配置](./configuration.md)：

- `dynamic_lights.enableDynamicLights` —— 总开关
- `dynamic_lights.maxDynamicLights` —— 最大光源数量
- `dynamic_lights.dynamicLightMaxDistance` —— 光照最大影响距离

## 衰减函数（LightAttenuation）

`DynamicLightManager` 使用衰减函数将距离映射为衰减因子（`0 = 无光照`，`1 = 满光照`）：

| 常量 | 说明 |
| --- | --- |
| `LINEAR` | 线性衰减 |
| `INVERSE_SQUARE` | 平方反比（模拟物理） |
| `SMOOTHSTEP` | S 曲线平滑阶梯（默认使用） |
| `INVERSE_LINEAR` | 反比线性（近处衰减快） |
| `bezier(x1, y1, x2, y2)` | 基于贝塞尔曲线自定义 |

当前 `DynamicLightManager` 内部使用 `SMOOTHSTEP`。

## 限制与说明

- **方块级真实光照**（`DynamicLightEngine`）依赖单机集成服务端，在专用服务器（dedicated server）环境下不会放置光源方块；但实体光照与 `DynamicLightManager` 的查询仍可用。
- 动态光照只在**客户端**生效（渲染表现），不影响服务端逻辑。
- 发光粒子数量受 `maxDynamicLights` 限制，超出部分按评分裁剪。
