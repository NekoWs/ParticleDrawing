# 粒子能力缺口盘点

以命令、公开 API 和 `.pdrawc` 播放链路为准。下表区分了需要底层支持的效果、不同入口之间的能力差异，以及可用现有公式组合的效果。

## 现有能力

- 发射与采样支持参数曲线、空间网格、图片、文字点阵及三角化 OBJ 表面。普通发射器和配方都支持沿锚点行程等距发射。
- 配方支持出生公式、逐刻公式、逐粒子状态、噪声、寿命与条件销毁。支持中心点或带半宽的碰撞盒，能检测方块、液体和实体当前包围盒，并在一刻内处理最多四次反弹。
- 外观支持静态或动画 UV、广告牌和固定朝向、普通透明与加法混合、速度拉丝、贴近方块时整颗淡出、平面丝带、管状拖尾和低面数三角网格。接触事件可将贴图投影到附近的平行方块表面。点光和射灯可以影响地形光照。
- 配方的出生、真实接触与死亡可触发一轮指定子配方；接触规则可按方块或液体的资源 ID 筛选。公式可读取局部环境、最近实体、邻居快照和维度共享力场。可选距离 LOD 降低远处的绘制密度，模拟继续运行。

## 需要底层支持的效果

| 想实现的效果 | 当前边界 |
| --- | --- |
| 让粒子根据前方的世界改变路线 | 配方能探测沿固定方向或当前速度的首个命中，公式可用命中材质、距离和世界法线作判断。探测从粒子当前求值点出发，暂不支持多个探测点或在公式中指定任意探测起点。见 [世界输入命令](particle-commands.md#世界输入)和[碰撞查询](../shared/core/src/main/kotlin/work/nekow/particledrawing/animation/recipe/ParticleWorld.kt)。 |
| 粒子均匀地从任意模型或正在挥动的武器表面飞出 | 三角化 OBJ 可按面积采样并输出发射法线。动态骨骼和 Minecraft 当前渲染姿势仍没有采样源。见 [基础采样](../shared/core/src/main/kotlin/work/nekow/particledrawing/api/ParticleSampling.kt)和 [样本模型](../shared/core/src/main/kotlin/work/nekow/particledrawing/api/ParticleRecipe.kt)。 |
| 让焦痕从地面绕到台阶的竖面 | 接触事件可投影印记并裁剪到多个平行的地形面。绕过棱角的投影仍缺少多方向展开。现有丝带用头部颜色、宽度和贴图计算各段，父粒子移除时丝带一并消失。见 [四边形渲染](../platforms/neoforge-common/src/main/kotlin/work/nekow/particledrawing/core/client/OrientedQuadRenderState.kt)、[丝带历史](../shared/core/src/main/kotlin/work/nekow/particledrawing/render/RibbonHistory.kt)和[丝带顶点](../platforms/neoforge-common/src/main/kotlin/work/nekow/particledrawing/core/client/RibbonVertices.kt)。 |
| 大烟团一半穿进地形时只软化交界，热浪扭曲烟团背后的画面 | `soft` 按粒子中心到碰撞面的距离调整整颗透明度。材质只有普通透明和加法混合，缺少采样场景深度或场景颜色的粒子管线。26.3 现有的顺序无关透明解决透明层合成。见 [软化限制](known-issues.md#方块表面软化)、[混合枚举](../shared/core/src/main/kotlin/work/nekow/particledrawing/api/ReadableAppearance.kt)和 [26.3 渲染管线](../platforms/neoforge-26.3/src/main/kotlin/work/nekow/particledrawing/core/client/AdditiveParticlePipeline.kt)。 |
| 粒子的射灯被墙挡住并向地面投影 | 射灯可沿中心射线截短，地形受光按网格顶点采样。缺少逐点遮挡和投影。见 [动态光照限制](known-issues.md#动态光照)。 |

## 入口与运行边界

| 想实现的效果 | 当前边界 |
| --- | --- |
| 让网页编辑器导出的粒子在游戏中响应世界碰撞 | `.pdrawc` v20 可声明拉丝、软化、丝带和管状拖尾。动画的时间轴和脚本直接控制位置，仍缺少保存、重建世界交互状态的播放语义。见 [文件读取](../shared/core/src/main/kotlin/work/nekow/particledrawing/animation/PdrawcReader.kt)和[动画生成入口](../platforms/neoforge-common/src/main/kotlin/work/nekow/particledrawing/core/client/ClientAnimationManager.kt)。 |
| 晚进服的玩家看到和其他人处于同一阶段的碰撞粒子和尾迹 | 持续配方向迟到玩家发送当前配置，客户端从头模拟。已经触发的一次性配方也不会补发。真实碰撞轨迹取决于客户端当时看到的世界。见 [服务端配方同步](../platforms/neoforge-common/src/main/kotlin/work/nekow/particledrawing/core/server/ServerParticleSources.kt)和[启动包](../platforms/neoforge-common/src/main/kotlin/work/nekow/particledrawing/core/network/ParticleRecipePayload.kt)。 |
| 十万颗粒子同时模拟和渲染、远离摄像机后释放模拟预算 | 客户端粒子登记上限默认 50,000，可调至 200,000。单个配方最多 8,192 个样本及存活粒子。距离 LOD 作用于绘制，模拟继续运行。26.3 的十万颗 CPU 模拟场景已实测，见下表。 |

### 26.3 开发客户端实测

同一台 Ryzen 5 9600X、Radeon RX 7800 XT 的本地平坦世界，关闭垂直同步和距离 LOD，将客户端上限设为 120,000。
四边形场景的粒子正对镜头并用公式改变高度。统计切换后完整的 5 秒窗口，帧时间包含游戏模拟、绘制和 GC。

| 场景 | 优化前平均帧时间 | 优化后平均帧时间 |
| --- | ---: | ---: |
| 空场景 | 0.39–0.40 ms | 0.38–0.39 ms |
| 10,000 颗四边形 | 1.69–1.77 ms | 1.32–1.40 ms |
| 50,000 颗四边形 | 93.92–122.97 ms | 21.31 ms |
| 106,496 颗四边形 | 1,020–1,038 ms | 94–101 ms |
| 8,192 颗启用方块碰撞 | 1.38–1.40 ms | 1.34–1.39 ms |
| 1,024 条 8 边、16 刻管状拖尾 | — | 7.37–7.63 ms |

配方在粒子出生时绑定引擎与桥接状态，随后复用绑定和原有状态写入。50,000 颗场景切换后还出现过一次约 2.4 秒的长帧。
十万颗场景约为 10 FPS。以上数据来自一台机器，其他场景还会受粒子体积、碰撞次数、拖尾几何和贴图覆盖影响。

## 实现顺序

1. **世界输入和粒子事件。** 局部环境读取、接触事件和子配方已接通，可用[雨滴示例](../examples/commands/rain-contact.mcfunction)复现。碰撞盒已接入同一查询；移动实体使用客户端当前包围盒。
2. **编辑器与运行时共用外观。** `.pdrawc` v20 的拉丝、软化、丝带和管状拖尾已复用命令粒子的渲染入口。实时碰撞另需明确预览与实际世界的输入和回放语义。
3. **发射采样和渲染几何。** 配方已支持按距离发射和静态模型采样。三角网格可作为粒子外观绘制。动态采样和需要场景缓冲的材质仍可独立扩展。
4. **规模化。** 四边形、碰撞和管状拖尾场景已测。管状拖尾、丝带与三角网格共用几何预算。模拟预算、实例绘制和 GPU 模拟仍待实现。

功能对照可参考 Unity 的[粒子事件和子发射](https://docs.unity3d.com/cn/current/ScriptReference/ParticleSystem.SubEmittersModule.html)和 Godot 的[粒子渲染、碰撞与轨迹](https://docs.godotengine.org/en/stable/classes/class_gpuparticles3d.html)、[可移动粒子力场](https://docs.godotengine.org/en/stable/classes/class_gpuparticlesattractor3d.html)。
