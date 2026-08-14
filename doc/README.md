# ParticleDrawing 类索引

ParticleDrawing 是一个面向 [NeoForge](https://neoforged.net/)（Minecraft 26.2）的粒子效果库。

本目录仅作**类索引**，说明每个类的作用。详细用法与参数说明均写在对应类的 KDoc / Javadoc 注释中。

## 类索引

### api —— 公开 API

| 类 | 作用 |
| --- | --- |
| `ParticleManager` | 维度级入口，创建粒子与粒子组 |
| `ParticleHandle` | 单粒子句柄：移动 / 速度 / 重着色 / 缩放 / 销毁，含流式 `Builder` |
| `ParticleGroup` | 粒子组：整体变换 + 帧级运动算法 |
| `Draw` | 绘图工具：线段、圆、圆盘、曲线、球体、三角形、六芒星、长方体、矩形 |
| `Color` | 不可变 RGBA 颜色与工厂方法 |
| `ParticleStyle` | 粒子视觉样式枚举 |
| `TransformOp` | 组变换操作描述 |

### core.easing —— 缓动系统

| 类 | 作用 |
| --- | --- |
| `EasingCurve` | 三次贝塞尔缓动曲线 |
| `EasingType` | 缓动类型：预设 + 自定义曲线，支持序列化 |

### core.motion —— 帧级运动算法

| 类 | 作用 |
| --- | --- |
| `MotionAlgorithm` | 运动算法接口 |
| `MotionSystem` | 帧级运动系统：注册、启动、逐帧计算 |
| `MotionPayload` | 运动指令数据包 |
| `MotionMath` | 数学辅助 |
| `algorithms.RotateAlgorithm` | 绕轴匀速旋转 |
| `algorithms.SwirlAlgorithm` | 螺旋扭转 |
| `algorithms.VortexAlgorithm` | 涡旋内卷 + 波纹 |
| `algorithms.FollowPlayerAlgorithm` | 跟随目标 |
| `algorithms.ScaleByDistanceAlgorithm` | 按距离缩放 |
| `algorithms.ColorGradientAlgorithm` | 渐变着色 |

### core.server —— 服务端权威引擎

| 类 | 作用 |
| --- | --- |
| `ServerParticleEngine` | 服务端权威粒子引擎（每维度一个） |
| `ParticleData` | 粒子运行时数据 |
| `ParticleGroupData` | 粒子组成员与轴心 |
| `ParticleVisibilityManager` | 粒子可见性判定 |
| `ServerParticleHandler` | 服务端 tick 事件处理 |
| `DynamicLightCleanup` | 动态光源清理 |

### core.client —— 客户端渲染

| 类 | 作用 |
| --- | --- |
| `ClientParticleEngine` | 客户端粒子引擎 |
| `RenderParticle` | 渲染粒子状态（缓动 + 速度积分） |
| `BridgeParticle` | 桥接原版粒子系统的渲染代理 |
| `ParticleRenderHandler` | 客户端 tick 事件处理 |

### core.network —— 网络层

| 类 | 作用 |
| --- | --- |
| `NetworkHandler` | 注册数据包 |
| `ClientPayloadHandler` | 数据包分发到 `ClientParticleEngine` |
| `ParticleSpawnPayload` | 粒子生成包 |
| `ParticleUpdatePayload` | 粒子增量更新包 |
| `ParticleDestroyPayload` | 粒子销毁包 |
| `ParticleGroupTransformPayload` | 组变换包 |
| `ParticleVelocityPayload` | 粒子速度包 |
| `StreamCodecs` | 编解码工具 |

### lighting —— 动态光照

| 类 | 作用 |
| --- | --- |
| `DynamicLightManager` | 动态光源管理与光照等级查询 |
| `DynamicLightEngine` | 放置 / 移除光源方块 |
| `DynamicLightPositions` | 光源位置追踪 |
| `LightAttenuation` | 光照衰减函数 |

### command / config / util

| 类 | 作用 |
| --- | --- |
| `command.ParticleDrawCommands` | `/particledraw` 命令与演示 |
| `config.ParticleDrawingConfig` | 服务端 / 客户端配置 |
| `util.ParticleUtils` | 工具方法 |

### mixin —— 渲染注入（Java）

| 类 | 作用 |
| --- | --- |
| `EntityRendererMixin` | 实体光照注入 |
| `LightmapRenderStateExtractorMixin` | 光照贴图刷新注入 |

## 其他文档

| 文档 | 内容 |
| --- | --- |
| [快速开始](./getting-started.md) | 环境要求、构建、运行 |
| [发布到 Maven Central](./publishing.md) | 发布流程 |
