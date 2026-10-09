# API 参考

按包分类的类索引。`work.nekow.particledrawing.api` 是公开 API；其余包属于内部实现，可能随版本改动。
字段、参数与边界语义写在对应类的 KDoc 里，用法与示例见 [guide.md](guide.md)。

## api：公开 API

| 类 | 作用 |
| --- | --- |
| `ParticleManager` | 维度级入口：创建粒子与粒子组、声明运行时发射器、批量生成 `spawnAll`、批量指令 `trackAll` / `setVelocityAll` / `applyForceAll`、程序化贴图登记 `registerTexture` / `registerBuiltinTextures` |
| `ParticleHandle` | 单粒子句柄：移动、速度、力、实体锚点、重着色、缩放、销毁、只读查询 `position()` / `velocity()` |
| `ParticleHandle.Builder` | 粒子生成参数：位置、颜色、缩放、寿命、外观规格、寿命曲线、首帧插值端点、逐粒子延迟 |
| `ParticleGroup` | 粒子组与编排动画：轴心绑定、一次性变换、持续运动、变量热更、表达式指令、完成信号 |
| `ParticleBatch` | 程序化粒子集：成员逐 tick 增删、批量位置/速度/力、条件回收、补齐到 N |
| `ParticleSpawnSpec` | 批量生成的单颗粒子规格（纯数据，字段与 `Builder` 对应） |
| `ParticleEmitter` | 运行时发射器的构建器：口径（里程或间隔）、寿命、外观、曲线、抖动 |
| `EmitterHandle` | 已声明发射器的句柄：改锚点、口径、参数与外观，停止与状态查询 |
| `Anchor` | 发射与特效锚点：`Fixed` / `Entity` / `Movable`，`Orient` 决定整组是否随运动方向转向 |
| `ParticleCurve`、`CurveKey`、`CurveChannel` | 逐粒子寿命曲线的单通道关键帧（透明度、尺寸、RGB），同一通道多条相乘 |
| `ParticleLifeCurve` | 一条粒子的全部寿命曲线，随生成包下发 |
| `ParticleVisual` | 逐粒子外观规格：贴图、子矩形 UV、各向异性尺寸、朝向、加法混合、免光照 |
| `ParticleStyle` | 内置外观形状：`SQUARE`、`SOFT_DOT`、`LINE`，客户端按需生成 16px 贴图 |
| `Draw`、`ColorSource`、`Draw.Axis` | 静态形状库与形状参数化颜色来源（固定色、双色渐变、彩虹、lambda） |
| `Color` | 不可变 RGBA 颜色与工厂方法 |
| `Animation` | 代码构建的动画：`create`（Kotlin DSL）/ `builder`（Java Builder）构建，`play` / `stop` / `updateVariable` / `isActive` 控制播放 |
| `GetterProps`（`EntityProp` / `WorldProp`） | 表达式被动输入的封闭词表 |
| `Effects`、`EffectHandle` | 服务端特效门面：注册、按锚点播放、锚点与播放时钟控制、参数回调 |
| `ClientEffects`、`ClientEffectHandle` | 纯客户端本地特效播放 |
| `EffectOptions`、`EffectRegistry`、`EffectCallbacks` | 特效播放选项、特效注册表、回调与变量存储 |
| `GroupClock`、`LifeCurveSugar` | 组时间轴换算与曲线糖的换算（内部使用） |

## animation：动画文件与播放

| 类 | 作用 |
| --- | --- |
| `AnimationLoader` | 读写 `<gameDir>/animations/` 下的 `.pdrawc`，读取时做服务端验签 |
| `PdrawcReader` | `.pdrawc` 解析与 Ed25519 验签 |
| `ParticleAnimation` | 动画数据模型：粒子、轨道、组、函数对象、贴图、UV、摄像机 |
| `FunctionObject`、`FunctionVar`、`Entrance` | 函数对象、其变量与入场预设 |
| `AnimParticle`、`AnimTrack`、`AnimKeyframe`、`AnimCamera` | 粒子、轨道、关键帧与摄像机 |
| `TrackPr` | 轨道分量枚举，声明顺序即 `.pdrawc` 序号（共 26 个） |
| `TextObject`、`TextChar` | 文字对象源记录，供脚本通过 `this.get(id)` 只读访问 |
| `AudioAsset` | 音频资产：原始字节、量化特征列与拍点表 |
| `UvData` | UV 参数模型：静态、填充、flipbook 动画三种模式 |
| `AnimationProgress` | 服务端权威播放进度公式，两端共用 |
| `PlaybackClock` | 特效播放时钟，支持 seek、暂停与变速 |
| `ProcessClock` | 渲染帧 process 时刻的单调游标 |
| `ClientAnimationPlayer` | 客户端逐 tick 求值器：轨道插值、函数对象 `setup` / `tick` / `process`、循环回卷 |
| `ServerAnimationManager` | 服务端动画播放管理：按名或字节播放、停止、变量更新、活跃查询 |
| `ServerEffectManager` | 服务端特效播放管理：可移动锚点、播放时钟、参数与完成回调 |

## animation.script：函数对象脚本

| 类 | 作用 |
| --- | --- |
| `ScriptParser` | 脚本词法与语法分析，产出 AST |
| `ScriptRuntime` | 脚本运行时：`setup` / `tick` / `process` 三阶段、粒子句柄读写、内建函数表 |
| `ScriptValues` | 脚本值类型与 JS 语义辅助（数值截断、取整、钳制） |
| `ScriptExpr` | 标量表达式求值（RPN 编译与缓存） |
| `ScalarProgram` | 纯标量代码块的字节码编译与执行快路径 |
| `Getters` | `get_entity_*` / `get_world_*` 被动输入的重写与静态检查 |
| `ScriptNoise` | 确定性 PRNG 与 3D Simplex 噪声 |
| `ScriptFastMath` | 快速标量数学近似，仅在函数对象开启 `fastMath` 时使用 |
| `ScriptAudio` | 音频特征查表（频带、响度、起音、节拍） |
| `ScriptWavePcm` | 采样级 PCM 取值：WAV 按字节直读、OGG 按窗口解码 |

## core.client：客户端渲染

| 类 | 作用 |
| --- | --- |
| `ClientParticleEngine` | 客户端粒子引擎：网络包落地点、本地动画直写点、发射器落地点 |
| `RenderParticle` | 渲染粒子状态：缓动、速度与力积分、欧拉旋转、寿命曲线乘数 |
| `BridgeParticle` | 桥接原版粒子系统的渲染代理，按 partialTick 插值位置、尺寸与颜色 |
| `BatchedQuadParticleGroup`、`ParticleGroupRegistrar` | 自定义粒子分组，绕过原版每组的四边形上限 |
| `TrackBuffer` | track 指令的逐 tick 插值缓冲，缺包时保持端点 |
| `ParticleTakeover` | 粒子的位置接管与外观接管标记 |
| `LightCachePolicy` | 光照缓存的失效判定（动态光版本、方块变化、分摊超时） |
| `ResolvedVisual`、`AppearanceBlend` | 外观规格的客户端解析与外观混合 |
| `TextureCache` | 贴图缓存：PNG 字节与内置形状像素到 `DynamicTexture` |
| `ClientAnimationManager` | 客户端动画播放管理：求值结果差分同步、音频同步、摄像机查询 |
| `ClientAnimationProgramManager` | 编排动画程序解释器：糖指令模式与表达式模式 |
| `ProgramFrame` | 糖指令的帧计算：旋转角度账本与缩放倍率账本 |
| `ClientEmitterManager` | 客户端发射器运行时：按渲染帧推进并按需生成粒子 |
| `EmitterAdvance`、`EmitterSampling` | 发射推进的纯逻辑与逐颗抖动的确定性采样 |
| `AudioStreamPlayer` | 游戏内音频播放：OpenAL 队列流式、OGG/WAV 解码、采样级 seek、漂移校正 |
| `CameraController` | `/pdraw camera` 的摄像机预览状态 |
| `EffectAnchorResolver` | 客户端锚点解析：位置、旋转四元数与整体缩放 |
| `ParticleRenderHandler` | 客户端 tick 与渲染帧事件处理 |
| `ClientAnimationSyncManager`、`ClientTextureSyncManager` | 配置阶段文件接收与程序化贴图接收 |
| `AdditiveParticlePipeline`、`OrientedQuadRenderState` | 加法混合渲染管线与非广告牌四边形的朝向处理 |

## core.network：网络层

| 类 | 作用 |
| --- | --- |
| `NetworkHandler` | 注册全部数据包 |
| `ClientPayloadHandler`、`ServerPayloadHandler` | 客户端与服务端的数据包分发 |
| `ParticleSpawnPayload`、`ParticleSpawnBatchPayload` | 单颗与批量生成（批量一次最多 256 条） |
| `ParticleUpdatePayload`、`ParticleDestroyPayload`、`ParticleLightLevelPayload` | 增量更新、销毁与光照等级 |
| `ParticleVelocityPayload`、`ParticleVelocityBatchPayload`、`ParticleForcePayload`、`ParticleForceBatchPayload` | 速度与加速度（力）指令 |
| `ParticleTrackPayload`、`ParticleTrackBatchPayload`、`ParticleSetPositionPayload`、`ParticleTranslatePayload`、`ParticleRotationPayload` | 直设位置、平移与绕轴旋转 |
| `ParticleAttachPayload` | 实体锚点：只在挂载时下发一次 |
| `ParticleTexturePayload` | 程序化贴图内容分块 |
| `EmitterPayload` | 发射器声明、更新与停止，含参数编解码 `EmitterParams` / `EmitterParamsCodec` |
| `AnimationProgramPayload` | 编排程序下发、追加、变量热更、变量渐变与停止 |
| `ProgramAnchorPayload`、`ProgramCompletePayload` | 移动轴心样本与完成信号 |
| `PlayAnimationPayload`、`PlayAnimationDataPayload`、`StopAnimationPayload`、`VariableUpdatePayload` | 动画播放控制 |
| `PlayEffectPayload`、`EffectDataPayload`、`EffectRequestPayload`、`AnchorUpdateBatchPayload`、`ClockSyncPayload` | 特效播放、锚点同步与时钟同步 |
| `AnimationSyncBeginPayload`、`AnimationSyncRequestPayload`、`AnimationSyncFilePayload`、`AnimationSyncDonePayload` | 配置阶段动画文件同步 |
| `BatchChunking`、`ParticleVisualCodec`、`ParticleCurveCodec`、`ParticleAnimationCodec`、`StreamCodecs` | 拆包与编解码工具 |

批量载荷的单包上限由编解码两端共同约束，超限构造即报错：速度、位置、力、销毁与锚点更新为 512 条，
批量生成为 256 条；发送端先经 `BatchChunking` 拆段，公开 API 对成员数没有上限。

## core.server：服务端权威

| 类 | 作用 |
| --- | --- |
| `ServerParticleEngine` | 每维度一个的权威粒子引擎：生成、更新、销毁与可见性同步 |
| `ParticleData`、`ParticleGroupData` | 粒子运行时数据与组成员、轴心 |
| `ParticleVisibilityManager` | 按视距判定玩家是否可见 |
| `ServerEmitterManager` | 发射器登记表：声明、分档更新，以及后进服与走进范围玩家的补发 |
| `ServerProgramCompletion` | 编排动画完成信号的登记表，含兜底销毁 |
| `AnimationScheduler` | 服务端延迟任务队列（stagger 入场、定时销毁等） |
| `AnimationSyncService`、`AnimationSyncConfigTask` | 动画文件同步的纯逻辑与配置阶段任务 |
| `ServerParticleHandler` | 服务端 tick、卸载、切维度、重生与登录事件 |
| `TextureSyncService`、`TextureRegistry` | 程序化贴图的登记、广播与进服补发 |

## lighting、command、config、util、mixin

| 类 | 作用 |
| --- | --- |
| `DynamicLightManager` | 动态光照：向光图坐标注入粒子光照，按脏 section 重烘焙 |
| `LightAttenuation` | 光照衰减函数：线性、反平方、平滑步进等预设 |
| `ParticleDrawCommands` | `/pdraw` 命令：`list` / `play` / `stop` / `reload` / `camera` / `debug` / `var` |
| `ParticleDrawingConfig` | 服务端与客户端配置项 |
| `ParticleUtils`、`HashUtils`、`Vec3Math`、`VisualMath`、`AttachMath`、`BuiltinTextures` | 维度 UUID、哈希、向量与视觉数学、实体锚点数学、内置形状贴图 |
| `BrightnessGetterMixin`、`CameraMixin`、`EntityRendererMixin` | 方块亮度合并动态光、摄像机预览覆盖、实体光照计入动态光 |
