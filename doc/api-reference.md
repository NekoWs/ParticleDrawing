# API 参考

常用 Kotlin 与 Java 入口位于 `work.nekow.particledrawing.api`。以下按用途索引，参数与重载见对应类的
KDoc。只在特定包中提供的类型单独注明。代码示例见[使用指南](guide.md)。

## 创建与控制

| 类型 | 用途 |
| --- | --- |
| `ParticleManager` | 按维度创建粒子、粒子组和发射器，登记并同步贴图 |
| `ParticleHandle.Builder`、`ParticleHandle` | 设置出生规格和延迟，运行时控制单颗粒子 |
| `ParticleGroup` | 绑定轴心，录制组动画、公式、编队形变和退场 |
| `ParticleMorphSlot` | 为 `ParticleGroup.morphSlots` 指定世界坐标（`MorphOptions.space = LOCAL` 时为组锚点相对坐标）、yaw、pitch 和 size |
| `ParticleBatch` | 管理可逐刻增删的粒子集，批量移动、施力和清理 |
| `ParticleSpawnSpec`、`SpawnResult`、`SpawnRejection` | 描述出生属性与初始运动，读取 `spawnAllDetailed` 按输入下标返回的结果 |
| `Draw`、`ColorSource`、`Color` | 绘制预设或参数形状，指定 RGBA、渐变、彩虹或自定义颜色函数 |
| `Anchor`、`Frames`、`CoordinateFrame` | 描述固定、实体和可移动锚点及形状坐标基 |
| `PolygonSpec`、`Shape`、`ShapePart`、`PartSelector`、`Shapes` | 创建正多边形和命名部件，按部件选择成员 |

## 外观与光照

| 类型 | 用途 |
| --- | --- |
| `ParticleVisual`、`ParticleStyle`、`ParticleTextures` | 设置尺寸、朝向、混合、贴图、换帧、速度拉丝、软化距离与丝带长度。`glowing` 只让片元免光照；向外投光用 `emitsLight` / `lightLevel`（默认 0），两者互不隐式联动 |
| `ParticleTrailStyle` | 设置尾端与头部的宽度和 RGBA，让尾迹脱离头部后独立淡出 |
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
| `ParticleTextSampler` | 从字符串的字形笔画生成指定数量的坐标；用 `Draw.textPoints` 直接取得世界坐标 |
| `ParticleRecipe`、`RecipeAnchor` | 用样本、公式、筛选、发射窗口（`emitFromTick` / `emitUntilTick`）和锚点声明出生与运动，配置方块或液体碰撞 |
| `SubEmit` | 在配方粒子自然到期或满足 `kill` 时沿球面方向发射一层子粒子 |
| `ParticleSources`、`ParticleSourceHandle` | 启动单轮或持续生成，修改现有实例并停止或移除 |
| `EntityParticleEffects.bind(entity, name, emission, events, emissionMode)` | 在服务端为实体登记命名绑定。持续发射和事件爆发可独立使用。重复登记同名绑定返回 `false` |
| `EntityParticleBurst(recipe, each, stopEmission, mode)` | 按事件位置爆发，选择执行模式、重复触发，以及是否停止同一绑定的持续发射 |
| `EntityParticleEffects.trigger(entity, event, position)` | 在服务端发出自定义事件，返回实际启动的爆发数。内置碰撞和死亡事件由模组派发 |
| `EntityParticleEffects.isBound(entity, name)` / `unbind(entity, name)` | 查询或解除绑定。解除后现有粒子继续走完寿命 |
| `ParticleSources.startSynced(level, recipe, anchor, burst)` | 由服务端推进配方。进入视距的玩家收到现存粒子、子粒子和尾迹。返回同一种运行句柄 |
| `ParticleVisual.texture("sheet:namespace:name")` | 引用客户端资源包中按行预排的动画图集，用描述文件指定帧尺寸、帧数和帧率 |
| `ParticleRecipe.strandSamples` | 普通爆发每条光带的样本数。相邻样本共享柔边截面，颜色和尺寸沿段插值 |
| `ParticleSources.startInstanced(level, recipe, anchor, burst)` / `burstGpu(level, recipe, anchor)` | 使用客户端配方和常驻实例数据绘制四边形、透明粒子与网格，或在 GPU 上推进四边形弹道 |

## 点源与点集

| 类型 | 用途 |
| --- | --- |
| `PointSource` | 可被形变等消费者使用的点来源：`Formula` / `Text` / `Image` / `Mesh` / `GroupRef` / `NamedSet` / `Points`，也可自定义实现 |
| `PointSource.prepare(context, members)`、`PreparedPointSource.morphPoints(context, anchor, local)` | 准备阶段缓存采样点；真正使用时才取组/点集快照，并按 `world` / `local` 落锚点 |
| `PointSet` | `work.nekow.particledrawing.pointset` 中的不可变点集：`transformed` / `concatenated` / `filteredByFormula` / `sortedBy` / `shuffled`，所有操作返回新对象 |
| `PointAxis`、`PointTransform` | 平移、绕轴旋转、缩放、镜像；旋转与缩放相对原点，按顺序应用 |
| `ParticleGroup.morph(source)` / `morph(pointSet)` | 直接以点源或核心点集为目标，槽位数按点集长度生成 |
| `PointSets` | 维度内命名点集库：`define` / `find` / `get` / `names` / `remove` / `discard`，只存相对坐标 |
| `SampleAsset` | 工作目录文件或资源包素材，图片/模型采样共用 |

[命令写法](command-v2-design.md#34-形变) · [点集用法](particle-commands.md#文字变文字)

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
| `MorphRuntime`、`MorphAllocator`、`MorphSample` | `animation.morph` 中的形变运行时：最近邻/序号/极角分配、逐 tick 推进与渲染循环批量取数；大组分配走空间网格，避免 O(n²) |

[播放与构建动画](animation.md) · [核心与平台类型转换](multiversion.md#类型与原生服务)
