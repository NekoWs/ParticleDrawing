# ParticleDrawing 类索引

ParticleDrawing 是一个面向 [NeoForge](https://neoforged.net/)（Minecraft 26.2）的粒子效果库。

本目录仅作**类索引**，说明每个类的作用。详细用法与参数说明均写在对应类的 KDoc / Javadoc 注释中；上手教程与动画编排示例见 [api-guide.md](./api-guide.md)。

## 类索引

### api —— 公开 API

| 类 | 作用 |
| --- | --- |
| `ParticleManager` | 维度级入口，创建粒子与粒子组；批量指令 `trackAll` / `setVelocityAll` / `applyForceAll` |
| `ParticleHandle` | 单粒子句柄：移动 / 速度 / 力 / 实体锚点（`Entity`/`uuid`/`entityId` 三入口）/ 重着色 / 缩放 / 销毁，含流式 `Builder` 与 `position()`/`velocity()` 只读查询 |
| `ParticleGroup` | 粒子组：编排式动画（客户端自驱程序：delay/fadeIn/spin/movePath/pulse/实体通道/公式指令） |
| `ParticleBatch` | 程序化粒子集：成员逐 tick 增删、一次包批量下发位置/速度/力、按权威位置/速度条件回收、补齐到 N |
| `Draw` | 绘图工具：点、线段、圆、圆盘、曲线、三角形、六芒星、矩形、球体、长方体；支持渐变着色与逐粒子入场 |
| `ColorSource` | 形状参数化颜色来源：固定色 / 双色渐变 / 彩虹，支持 lambda |
| `Color` | 不可变 RGBA 颜色与工厂方法 |
| `TransformOp` | 组变换操作描述 |

### core.easing —— 缓动系统

| 类 | 作用 |
| --- | --- |
| `EasingCurve` | 三次贝塞尔缓动曲线 |
| `EasingType` | 缓动类型：14 种预设 + 无缓动（阶跃）+ 自定义曲线，支持序列化 |

### core.animation —— .pdraw 动画播放

| 类 | 作用 |
| --- | --- |
| `AnimationLoader` | 解析 .pdraw 工程文件（含内嵌贴图 texData） |
| `ParticleAnimation` | 动画数据模型（轨道、粒子、贴图、UV、摄像机、文字对象、音频资产） |
| `TextObject` / `TextChar` | 文字对象源记录（v15 texts section，v17 起无组）：文本/样式/字符→粒子映射，供脚本 this.get(id) 只读访问；含自转/公转空间与广告牌字段 |
| `AudioAsset` | 音频资产（v16 audio section）：原始音频字节 + 量化特征列 + 拍点表，供游戏内播放与脚本只读访问 |
| `ScriptWavePcm` | 音频采样级 PCM：脚本 `a.sampleAt/peakAt/sampleRate/channels/waveReady` 的取值来源，WAV 按字节直读、OGG 按窗口解码 |
| `TrackPr` | 分量轨道标识枚举（pos/vel/col/scl/rot/spin/center 的 xyz + fov + target.xyz，共 26 个） |
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
| `ClientParticleEngine` | 客户端粒子引擎（缓动同步、直接同步、非均匀缩放、track 逐 tick 插值、实体锚点本地解析） |
| `TrackBuffer` | track 的逐 tick 插值缓冲（按到达顺序排队、每 tick 消费一条、缺包原地保持） |
| `RenderParticle` | 渲染粒子状态（缓动 + 速度积分 + 欧拉旋转） |
| `BridgeParticle` | 桥接原版粒子系统的渲染代理（纯色方块 / 自定义贴图 + UV 采样；v17 非广告牌粒子按自转四元数固定朝向） |
| `TextureCache` | 内嵌贴图缓存（PNG 字节 → DynamicTexture） |
| `ClientAnimationManager` | 客户端 .pdraw 动画播放管理 |
| `AudioStreamPlayer` | 游戏内音频播放（OpenAL 队列流式 + OGG/WAV 解码 + 采样级精确 seek 重灌 + 漂移校正，主线程驱动） |
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
| `ParticleTrackBatchPayload` | 批量直设位置包（一个包覆盖多粒子） |
| `ParticleForcePayload` | 加速度（力）包：只在开始施力时下发一次 |
| `ParticleAttachPayload` | 实体锚点包：只在挂载时下发一次，客户端本地解析位置 |
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

## 已知限制

### 立体声源的 `pan`：已实施，与编辑器有两处已知差别

编辑器把音频声像串在 Web Audio 的 `StereoPanner` 上（`objects/audio-playback.js`：
`source → gain → panner → destination`）。播放端原来把它塞进 `AL_POSITION`，而 OpenAL 对**立体声源**
在立体声输出下走直通声道、位置分量不参与混音——实测（`ALC_SOFT_loopback` 离屏渲染真 OpenAL Soft，
见 `StereoSourcePanTest`）pan 从 -1 到 +1 左右电平差 **0.0 dB**，也就是声像完全无效。

现在立体声素材改走 `AL_SOFT_source_panning` 的 `AL_PAN_SOFT`（`OpenAlSink.prepareSource` 置
`AL_PANNING_ENABLED_SOFT`，`setPan` 用 `AL_PAN_SOFT` 代替 `AL_POSITION`），实测是**真左右平衡**：
pan=0 两侧不动，pan=±1 本侧不动、对侧 -177 dB，中段线性（±0.5 → 对侧 -6.1 dB）。扩展不可用时
退回 `AL_POSITION`（即改动前的行为，不报错）；单声道素材也继续走 `AL_POSITION`（对它有效，行为不变）。

与编辑器仍有两处差别，都在两端之外：

1. **中段曲线**：OpenAL 线性、Web Audio 等功率（`gainL = cos(x·π/2)`、`gainR = sin(x·π/2)`）。
   pan=0 完全一致；pan=±1 对侧都静音、本侧自身内容都不衰减，只剩下面第 2 点的折叠差别；
   中间不一致（pan=0.5 时对侧：OpenAL -6.1 dB、等功率 -3.0 dB）。
2. **对侧折叠**：Web Audio 规范对立体声输入算的是 `outputL = inputL + inputR·gainL`，
   硬声像时会把对侧声道**混进**近侧输出；`AL_PAN_SOFT` 不折叠（实测对侧分量 -104 dB），近侧
   只剩本来的内容。

若将来必须逐点一致，路径是自己按规范算增益与折叠（在解码块上做每样本混音，或拆双单声道源并
保持队列/seek/位置记账锁步）——已评估 **150~200 行**，不建议只为这两点做。

开关语义（实测）：开启 `AL_PANNING_ENABLED_SOFT` 之后 `AL_POSITION` 就不再参与这条 source 的混音
（位置挪到右侧、`AL_PAN_SOFT` 给 0 时左右仍完全对称），所以两套声像不会叠加；`prepareSource` 仍把
位置钉在原点，只是不留一个与实际声像对不上的旧值。这个开关只作用于 `AudioStreamPlayer` 自建、
只播 `.pdrawc` 音频的 source；游戏本体的 3D 定位音效走 Minecraft 自己的 source，不受影响。

### 素材采样率不必等于设备率

播放端把 WAV/OGG 头里的采样率原样交给 `alBufferData`，由 OpenAL 重采样到设备率。素材率 **≠**
设备率时，建 source 会换成带限 sinc 重采样器（`AL_SOFT_source_resampler`）——OpenAL 默认那档是
纯插值、没有抗混叠，会把 24kHz 以上的内容按原电平折回可听带（实测 192kHz 素材的 30kHz 单音折回
可听带 **-9.0 dBFS**，换成带限 sinc 后 -70~-81 dBFS）。

触发条件（实测 `AudioResamplerAliasingTest`，读回 `AL_SOURCE_RESAMPLER_SOFT` 确认）：

| 设备率 | 素材率 | 行为 |
| --- | --- | --- |
| 48k | 48k | **不设**（读回仍是默认档），mixer 走 1:1 快路径，零开销、与不换挡时完全一致 |
| 48k | 192k | 设为带限 sinc |
| 48k | 44.1k | 也设（非整数比，同样在重采样） |
| 192k | 192k | **不设** |
| 设备率查不到（返回 0） | 任意 | 仍会设（保守：假设可能有重采样） |
| 缺扩展 / 没有带限 sinc 可选 | — | 一个 AL 调用都不发，退回 OpenAL 默认 |

设备率每次建 source **现查**（不缓存）：同一次播放里设备换了率，缓存旧值会把「新设备率 == 素材率」
误判成立而漏换重采样器。换素材或换设备前照这张表看即可。

---

> 变更记录：`ParticleStyle` 枚举与 `core.motion` 运动算法包已移除——无贴图粒子统一渲染为纯色方块，帧级运动能力由编排式动画 API（spin / movePath / pulse）承担。
