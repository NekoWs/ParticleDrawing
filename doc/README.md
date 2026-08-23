# ParticleDrawing 类索引

ParticleDrawing 是一个面向 [NeoForge](https://neoforged.net/)（Minecraft 26.2）的粒子效果库。

本目录仅作**类索引**，说明每个类的作用。详细用法与参数说明均写在对应类的 KDoc / Javadoc 注释中；上手教程与动画编排示例见 [api-guide.md](./api-guide.md)。

## 类索引

### api —— 公开 API

| 类 | 作用 |
| --- | --- |
| `ParticleManager` | 维度级入口，创建粒子与粒子组 |
| `ParticleHandle` | 单粒子句柄：移动 / 速度 / 重着色 / 缩放 / 销毁，含流式 `Builder` |
| `ParticleGroup` | 粒子组：编排式动画（客户端自驱程序：delay/fadeIn/spin/movePath/pulse/实体通道/公式指令） |
| `Draw` | 绘图工具：点、线段、圆、圆盘、曲线、三角形、六芒星、矩形、球体、长方体；支持渐变着色与逐粒子入场 |
| `ColorSource` | 形状参数化颜色来源：固定色 / 双色渐变 / 彩虹，支持 lambda |
| `Color` | 不可变 RGBA 颜色与工厂方法 |
| `TransformOp` | 组变换操作描述 |

### core.easing —— 缓动系统

| 类 | 作用 |
| --- | --- |
| `EasingCurve` | 三次贝塞尔缓动曲线 |
| `EasingType` | 缓动类型：14 种预设 + 自定义曲线，支持序列化 |

### core.animation —— .pdraw 动画播放

| 类 | 作用 |
| --- | --- |
| `AnimationLoader` | 解析 .pdraw 工程文件（含内嵌贴图 texData） |
| `ParticleAnimation` | 动画数据模型（轨道、粒子、贴图、UV） |
| `ClientAnimationPlayer` | 客户端逐 tick 求值器（公式/变量/轨道插值） |
| `ServerAnimationManager` | 服务端动画引擎：playByName / play / stop / stopAll / updateVariable，含活跃播放查询 |
| `UvData` | UV 参数数据模型（静态 / 填充 / flipbook 动画模式） |

### core.server —— 服务端权威引擎

| 类 | 作用 |
| --- | --- |
| `ServerParticleEngine` | 服务端权威粒子引擎（每维度一个）：生成/更新/销毁与可见性同步 |
| `AnimationScheduler` | 服务端 tick 调度器：延迟任务队列（stagger 入场、定时销毁等） |
| `ParticleData` | 粒子运行时数据 |
| `ParticleGroupData` | 粒子组成员与轴心 |
| `ParticleVisibilityManager` | 粒子可见性判定 |
| `ServerParticleHandler` | 服务端 tick 事件处理（推进引擎 + 动画调度器） |
| `AnimationSyncService` | .pdraw 文件同步服务 |
| `AnimationSyncConfigTask` | 配置阶段文件同步任务 |
| `DynamicLightCleanup` | 动态光源清理 |

### core.client —— 客户端渲染

| 类 | 作用 |
| --- | --- |
| `ClientParticleEngine` | 客户端粒子引擎（缓动同步、直接同步、非均匀缩放） |
| `RenderParticle` | 渲染粒子状态（缓动 + 速度积分 + 欧拉旋转） |
| `BridgeParticle` | 桥接原版粒子系统的渲染代理（纯色方块 / 自定义贴图 + UV 采样） |
| `TextureCache` | 内嵌贴图缓存（PNG 字节 → DynamicTexture） |
| `ClientAnimationManager` | 客户端 .pdraw 动画播放管理 |
| `ClientAnimationProgramManager` | 编排动画程序解释器：指令流本地求值、实体通道、公式模式（客户端自驱） |
| `ClientAnimationSyncManager` | 配置阶段文件接收管理 |
| `ParticleRenderHandler` | 客户端 tick 事件处理 |

### core.network —— 网络层

| 类 | 作用 |
| --- | --- |
| `NetworkHandler` | 注册数据包 |
| `ClientPayloadHandler` | 数据包分发到 `ClientParticleEngine` |
| `ServerPayloadHandler` | 服务端配置阶段请求处理 |
| `ParticleSpawnPayload` | 粒子生成包 |
| `ParticleUpdatePayload` | 粒子增量更新包（位置/颜色/缩放 + 缓动） |
| `ParticleDestroyPayload` | 粒子销毁包 |
| `AnimationProgramPayload` / `AnimationProgramAppendPayload` | 编排动画程序下发 / 追加指令包 |
| `SetProgramVarPayload` / `StopAnimationProgramPayload` | 程序变量热更 / 停止包 |
| `ParticleRotationPayload` / `ParticleTranslatePayload` / `ParticleSetPositionPayload` | 绕轴心旋转 / 平移 / set 位置包 |
| `ParticleVelocityPayload` / `ParticleLightLevelPayload` | 速度 / 光照等级包 |
| `PlayAnimationPayload` / `StopAnimationPayload` / `VariableUpdatePayload` | 动画播放控制包 |
| `AnimationSyncBegin/File/Done/Request Payload` | 配置阶段文件同步包 |
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
| `command.ParticleDrawCommands` | `/pdraw` 命令（play / stop / reload 等） |
| `config.ParticleDrawingConfig` | 服务端 / 客户端配置 |
| `util.ParticleUtils` | 工具方法 |
| `util.Vec3Math` | 向量数学（Rodrigues 轴角旋转扩展函数） |

### mixin —— 渲染注入（Java）

| 类 | 作用 |
| --- | --- |
| `EntityRendererMixin` | 实体光照注入 |
| `BrightnessGetterMixin` | 亮度查询注入 |
| `QuadParticleRenderStateMixin` | 非均匀缩放粒子渲染注入 |

---

> 变更记录：`ParticleStyle` 枚举与 `core.motion` 运动算法包已移除——无贴图粒子统一渲染为纯色方块，帧级运动能力由编排式动画 API（spin / movePath / pulse）承担。
