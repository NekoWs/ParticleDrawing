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
| `ClientParticleEngine` | 客户端粒子引擎（缓动同步、直接同步、非均匀缩放） |
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

### 立体声源的 `pan` 在播放端无效（与编辑器分叉，未修）

编辑器把音频声像串在 Web Audio 的 `StereoPanner` 上（`objects/audio-playback.js`：
`source → gain → panner → destination`），是真正的左右平衡；播放端把它塞进 `AL_POSITION`
（`AudioStreamPlayer.applyMix`），而 OpenAL 对**立体声源**在立体声输出下走直通声道，位置分量
不参与混音。

实测（`ALC_SOFT_loopback` 离屏渲染真 OpenAL Soft，见 `StereoSourcePanTest`）：立体声源
`pan` 从 -1 到 +1，左右声道的自身分量电平差 **0.0 dB**；同样操作放到单声道源上有 22.5 dB 的
左右差。也就是编辑器预览里听得到的 `pan` 关键帧，进游戏后会被静默忽略。

可行修法：把交织立体声拆成左右两路单声道源，每路各自的 `AL_GAIN` 按编辑器的 StereoPanner
增益曲线给，并关掉空间化（单声道素材则两路喂同一份数据，走同一条代码路径）。不要直接用
单声道源 + `AL_POSITION` 代替：实测它的中间位置比贴边低 4.4 dB，与编辑器的等功率平衡曲线不同。
未实施——代价是分块队列、seek、位置记账与测试面都要变成两路。

### 素材采样率不必等于设备率

播放端把 WAV/OGG 头里的采样率原样交给 `alBufferData`，由 OpenAL 重采样到设备率。素材率 **≠**
设备率时，建 source 会换成带限 sinc 重采样器（`AL_SOFT_source_resampler`）——OpenAL 默认那档是
纯插值、没有抗混叠，会把 24kHz 以上的内容按原电平折回可听带（实测 192kHz 素材的 30kHz 单音折回
可听带 **-9.0 dBFS**）。素材率 **==** 设备率时不会去设重采样器，mixer 走 1:1 快路径、零额外开销。

---

> 变更记录：`ParticleStyle` 枚举与 `core.motion` 运动算法包已移除——无贴图粒子统一渲染为纯色方块，帧级运动能力由编排式动画 API（spin / movePath / pulse）承担。
