# 粒子能力缺口盘点

以命令、公开 API 和 `.pdrawc` 播放链路为准。下表区分了需要底层支持的效果、不同入口之间的能力差异，以及可用现有公式组合的效果。

## 现有能力

- 发射与采样支持参数曲线、空间网格、图片和文字点阵。普通路径发射器支持沿锚点行程等距发射，也支持按时间间隔发射。
- 配方支持出生公式、逐刻公式、逐粒子状态、噪声、寿命与条件销毁。碰撞沿粒子中心轨迹检测方块或液体形状，并支持反弹和摩擦。
- 外观支持静态或动画 UV、广告牌和固定朝向、普通透明与加法混合、速度拉丝、贴近方块时整颗淡出，以及面向镜头的轨迹丝带。点光和射灯可以影响地形光照。
- 子发射支持配方粒子正常消失时的一层定量爆发。可选距离 LOD 降低远处的绘制密度，模拟继续运行。

## 需要底层支持的效果

| 想实现的效果 | 当前边界 |
| --- | --- |
| 火星只有撞到金属才爆出另一套火花，雨点按落点溅水 | 碰撞结果只交给积分器，公式拿不到接触点、法线、速度或命中材质。现有子粒子沿用父粒子的外观规格，只由死亡触发，无法引用另一套发射配方。见 [碰撞响应](../shared/core/src/main/kotlin/work/nekow/particledrawing/animation/recipe/ParticleCollision.kt)、[配方积分](../shared/core/src/main/kotlin/work/nekow/particledrawing/animation/recipe/RecipeRuntime.kt)和[子发射](../platforms/neoforge-common/src/main/kotlin/work/nekow/particledrawing/core/client/ClientParticleSources.kt)。 |
| 大火球边缘碰墙、飞尘同时撞击地面和移动实体 | 世界查询只有线段和互斥的方块、液体模式。没有扫掠半径、实体形状或同一刻的多次碰撞求解。用大贴图画出的粒子仍按中心点碰撞。见 [查询契约](../shared/core/src/main/kotlin/work/nekow/particledrawing/animation/recipe/ParticleCollision.kt)和 [Minecraft 世界适配](../platforms/neoforge-common/src/main/kotlin/work/nekow/particledrawing/core/client/ClientParticleWorld.kt)。 |
| 雨遇屋顶停止出生、尘埃被附近移动实体吸走、粒子进入水后改变行为 | 配方公式只读本颗状态、样本和配置变量。组表达式能读预先登记的实体与天气等环境量，仍无法按粒子位置查询方块、亮度、流体或附近实体。组表达式的数学编队可以追随指定实体。见 [配方输入](../shared/core/src/main/kotlin/work/nekow/particledrawing/animation/recipe/RecipeExpressions.kt)和[组表达式属性](../shared/core/src/main/kotlin/work/nekow/particledrawing/api/GetterProps.kt)。 |
| 多个发射器共同受一个会移动的风场影响，鱼群按邻居速度避让 | 能在公式内写重力、阻尼和随空间变化的噪声风。缺少供多实例共享且可绑定实体的力场，以及供邻域交互使用的粒子空间查询。函数对象可以手工遍历自己的粒子，适合少量粒子的定制脚本。见 [配方公式接口](../shared/core/src/main/kotlin/work/nekow/particledrawing/animation/recipe/RecipeExpressions.kt)和[函数对象粒子接口](../shared/core/src/main/kotlin/work/nekow/particledrawing/animation/script/ScriptValues.kt)。 |
| 粒子均匀地从任意模型或正在挥动的武器表面飞出 | 可传入预先算好的点，或由公式构造已知曲面。内建采样缺少模型表面的三角形面积权重、发射法线，以及按实际姿势更新的采样源。见 [基础采样](../shared/core/src/main/kotlin/work/nekow/particledrawing/api/ParticleSampling.kt)和 [样本模型](../shared/core/src/main/kotlin/work/nekow/particledrawing/api/ParticleRecipe.kt)。 |
| 每颗碎片是有厚度的 3D 模型，拖尾是一根圆管，火花在斜面上留下贴合地形的焦痕 | 可用固定朝向的四边形近似碎片或印记。粒子渲染提交四边形和面向镜头的丝带，缺少网格实例、管状轨迹和地表投影。现有丝带用头部颜色、宽度和贴图计算各段，父粒子移除时丝带一并消失。见 [四边形渲染](../platforms/neoforge-common/src/main/kotlin/work/nekow/particledrawing/core/client/OrientedQuadRenderState.kt)、[丝带历史](../shared/core/src/main/kotlin/work/nekow/particledrawing/render/RibbonHistory.kt)和[丝带顶点](../platforms/neoforge-common/src/main/kotlin/work/nekow/particledrawing/core/client/RibbonVertices.kt)。 |
| 大烟团一半穿进地形时只软化交界，热浪扭曲烟团背后的画面 | `soft` 按粒子中心到碰撞面的距离调整整颗透明度。材质只有普通透明和加法混合，缺少采样场景深度或场景颜色的粒子管线。26.3 现有的顺序无关透明解决透明层合成。见 [软化限制](known-issues.md#方块表面软化)、[混合枚举](../shared/core/src/main/kotlin/work/nekow/particledrawing/api/ReadableAppearance.kt)和 [26.3 渲染管线](../platforms/neoforge-26.3/src/main/kotlin/work/nekow/particledrawing/core/client/AdditiveParticlePipeline.kt)。 |
| 粒子的射灯被墙挡住并向地面投影 | 射灯可沿中心射线截短，地形受光按网格顶点采样。缺少逐点遮挡和投影。见 [动态光照限制](known-issues.md#动态光照)。 |

## 入口与运行边界

| 想实现的效果 | 当前边界 |
| --- | --- |
| 在网页编辑器导出的动画里使用拉丝、软化、丝带或世界碰撞 | `.pdrawc` 的粒子与函数对象没有这些声明字段；播放桥也没有将这几个外观字段交给共享渲染入口。可以通过关键帧或脚本手工画出已知轨迹，当前格式无法声明实时地形碰撞或原生拖尾。见 [文件读取](../shared/core/src/main/kotlin/work/nekow/particledrawing/animation/PdrawcReader.kt)和[动画生成入口](../platforms/neoforge-common/src/main/kotlin/work/nekow/particledrawing/core/client/ClientAnimationManager.kt)。 |
| 让带配方公式的空间采样和距离发射一起工作 | 普通发射器已有按距离发射，配方出生只按刻间隔和每次尝试数量调度，两者尚未组合。常规粒子也没有配方的碰撞与子发射设置。见 [发射器 API](../platforms/neoforge-common/src/main/kotlin/work/nekow/particledrawing/api/ParticleEmitter.kt)和 [配方运行时](../shared/core/src/main/kotlin/work/nekow/particledrawing/animation/recipe/RecipeRuntime.kt)。 |
| 晚进服的玩家看到和其他人处于同一阶段的碰撞粒子和尾迹 | 持续配方向迟到玩家发送当前配置，客户端从头模拟。已经触发的一次性配方也不会补发。真实碰撞轨迹取决于客户端当时看到的世界。见 [服务端配方同步](../platforms/neoforge-common/src/main/kotlin/work/nekow/particledrawing/core/server/ServerParticleSources.kt)和[启动包](../platforms/neoforge-common/src/main/kotlin/work/nekow/particledrawing/core/network/ParticleRecipePayload.kt)。 |
| 十万颗粒子同时模拟和渲染、远离摄像机后释放模拟预算 | 客户端粒子登记上限默认 50,000，可调至 200,000。单个配方最多 8,192 个样本及存活粒子。现有路径逐颗 CPU 模拟和提交顶点，距离 LOD 只减少绘制，暂无基于实机基准的十万颗性能承诺。见 [客户端配置](../platforms/neoforge-common/src/main/kotlin/work/nekow/particledrawing/config/ParticleDrawingConfig.kt)、[配方上限](../shared/core/src/main/kotlin/work/nekow/particledrawing/api/ParticleRecipe.kt)和[客户端引擎](../platforms/neoforge-common/src/main/kotlin/work/nekow/particledrawing/core/client/ClientParticleEngine.kt)。 |

## 实现顺序

1. **世界输入和粒子事件。** 扩展 `ParticleWorld` 的数据契约，由平台统一提供所需的世界输入与接触信息。先用真实接触生成带位置、法线和速度的事件，再让事件启动受数量预算约束的现有配方。验证雨点只在命中表面时溅水，火星能按材质选择子配方。
2. **编辑器与运行时共用外观。** 先贯通 `.pdrawc`、动画状态和现有 `ParticleVisual` 渲染入口，让拉丝、软化和丝带可编辑、可导出。相同外观用命令和动画生成时应一致；丝带手动跳转时的历史处理也需要定义。实时碰撞另需明确预览与实际世界的输入和回放语义。
3. **发射采样和渲染几何。** 复用现有按时间、按距离的调度，给配方接入可更换的采样源。模型采样和模型绘制分别验证，其后再加管状轨迹、地表投影或需要场景缓冲的材质。
4. **规模化。** 用普通粒子、带碰撞粒子和带丝带粒子分别测逐刻 CPU 时间、提交顶点和帧时间，再决定是否引入模拟预算、实例绘制或 GPU 模拟。

功能对照可参考 Unity 的[粒子事件和子发射](https://docs.unity3d.com/cn/current/ScriptReference/ParticleSystem.SubEmittersModule.html)和 Godot 的[粒子渲染、碰撞与轨迹](https://docs.godotengine.org/en/stable/classes/class_gpuparticles3d.html)、[可移动粒子力场](https://docs.godotengine.org/en/stable/classes/class_gpuparticlesattractor3d.html)。
