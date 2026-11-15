# API 参考

常用 Kotlin 与 Java 入口位于 `work.nekow.particledrawing.api`。以下按用途索引，参数与重载见对应类的
KDoc。只在特定包中提供的类型单独注明。代码示例见[使用指南](guide.md)。

## 创建与控制

| 类型 | 用途 |
| --- | --- |
| `ParticleManager` | 按维度创建粒子、粒子组和发射器，登记并同步贴图 |
| `ParticleHandle.Builder`、`ParticleHandle` | 设置出生规格和延迟，运行时控制单颗粒子 |
| `ParticleGroup` | 绑定轴心，录制组动画、公式、变量和退场 |
| `ParticleBatch` | 管理可逐刻增删的粒子集，批量移动、施力和清理 |
| `ParticleSpawnSpec`、`SpawnResult`、`SpawnRejection` | 描述出生属性与初始运动，读取 `spawnAllDetailed` 按输入下标返回的结果 |
| `Draw`、`ColorSource`、`Color` | 绘制预设或参数形状，指定 RGBA、渐变、彩虹或自定义颜色函数 |
| `Anchor`、`Frames`、`CoordinateFrame` | 描述固定、实体和可移动锚点及形状坐标基 |
| `PolygonSpec`、`Shape`、`ShapePart`、`PartSelector`、`Shapes` | 创建正多边形和命名部件，按部件选择成员 |

## 外观与光照

| 类型 | 用途 |
| --- | --- |
| `ParticleVisual`、`ParticleStyle`、`ParticleTextures` | 设置尺寸、朝向、混合方式、贴图及换帧 |
| `particleVisual`、`ParticleAppearance`、`Light`、`Blend`、`Facing` | Kotlin DSL 和 Java Builder，分别描述全亮与对外照明 |
| `ParticleCurve`、`CurveKey`、`CurveChannel`、`ParticleLifeCurve` | 逐粒子的外观和光强寿命曲线 |
| `lifeCurve`、`LifeCurves` | 用 DSL 或 Java 入口创建关键帧和淡出包络 |
| `LightShape`、`LightFalloff` | `work.nekow.particledrawing.light` 中的点光、射灯和衰减配置 |
| `ParticleLights`、`LightSample` | 在客户端读取光源位置、亮度、形状和 RGB |

[光照用法](lighting.md) · [贴图用法](guide.md#贴图登记与下发)

## 采样与运行时生成

| 类型 | 用途 |
| --- | --- |
| `ParticleEmitter`、`EmitterHandle` | 按距离或间隔沿锚点生成粒子，更新参数或停止生成 |
| `ParticleSampling`、`ParticleImageSampler`、`ParticleSeed` | 计数、范围、网格和图片样本 |
| `ParticleRecipe`、`RecipeAnchor` | 用样本、公式、筛选和锚点声明出生与运动 |
| `ParticleSources`、`ParticleSourceHandle` | 启动单轮或持续生成，修改现有实例并停止或移除 |

## 单位、表达式与退场

| 类型 | 用途 |
| --- | --- |
| `Time`、`Length`、`Angle`、`AngularSpeed`、`Velocity`、`Acceleration`、`Lifetime`、`Ease` | 带单位的参数、时间与缓动 |
| `Scalar`、`Vector`、`Direction`、`ExpressionFrame`、`ExpressionMember`、`ExpressionPosition` | Kotlin 类型化组表达式，描述时间、索引、坐标和输出属性 |
| `ParameterInfo`、`TypedExpression`、`Phase`、`PhaseParameter` | 表达式参数、固定与可更新运行阶段 |
| `Getters`、`GetterProps`、`EntityProp`、`WorldProp` | 组公式可读取的实体与世界属性。`Getters` 位于 `animation.script` |
| `vec3`、`Angles`、`Scalars`、`Expressions`、`ExpressionTextBuilder` | 向量和标量运算，以及 Java 表达式编写入口 |
| `ExitSpec`、`ParticleSizeExit`、`ExitState`、`Exits` | 保留运动的逐粒子尺寸退场和完成清理 |

## 动画与特效

| 类型 | 用途 |
| --- | --- |
| `Animation` | 用 Kotlin DSL 或 Java Builder 构建并播放带轨道的动画 |
| `Effects`、`EffectHandle`、`EffectOptions` | 服务端注册、播放、锚定、控制时间轴和修改参数 |
| `ClientEffects`、`ClientEffectHandle` | 客户端本地播放与控制 |
| `AnimationLoader`、`ServerAnimationManager` | 在 `animation` 包中按文件名加载、验签和播放 |
| `ParticleAnimation`、`TrackPr`、`UvData` | `animation` 包中的动画模型、轨道分量和贴图取景规格 |

[播放与构建动画](animation.md) · [核心与平台类型转换](multiversion.md#类型与原生服务)
