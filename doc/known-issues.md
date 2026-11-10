# 已知限制

## Minecraft 26.3

当前提供独立的 26.2 和 26.3 适配器。26.3 最低 NeoForge 为 26.3.0.102，26.2 为 26.2.0.59。
游戏适配器使用 Java 25，共享核心使用 Java 21；见[多版本架构](multiversion.md)。
NeoGradle 使用 7.1.39，修复 26.3 开发环境重编译 HolderSet 时的访问权限问题，见
[上游问题](https://github.com/neoforged/NeoGradle/issues/327)。

26.3 的 FML 服务端同步配置改为 SYNCED，文件名保留 `particledrawing-server.toml`。
默认文件位于游戏或服务端的 `config/`，世界内的 `syncedconfig/` 可覆盖它。
升级旧世界时，把 `serverconfig/particledrawing-server.toml` 迁移到该世界的
`syncedconfig/particledrawing-server.toml`，以继续使用原来的粒子限额和可见性设置。

渲染管线改用 Renderpearl API。普通透明贴图使用原版 OIT 粒子管线，加色粒子单独提供深度范围、
透射率、颜色累积三个阶段；OIT_ADDITIVE 保留按 alpha 加权的加色，避免把加色层当作遮挡层。
原版计时器、摄像机注入点和动态光照注入点按 26.3 源码核对。
迁移接口参考 [26.2 → 26.3 官方说明](https://docs.neoforged.net/primer/docs/26.3/)。

26.3 的数据包格式为 121.0，资源包格式为 97.1；旧的命令函数数据包需要更新 pack.mcmeta 中的格式范围。
`.pdrawc` 布局、脚本语义和命令语法不随游戏版本迁移改变。

编译、JVM 回归与独立目录的 GameTestServer 启动已验证；客户端 GPU 画面和多人效果仍需游戏内验收。
开发启动器在非交互终端会提示终端能力受限，NeoForge 的 MixinExtras 依赖选择也会打印 JarJar 提示；
两者来自开发环境，服务端加载与 GameTest 可正常完成。
JVM 回归中的 LWJGL 3.4.3 内存后端会触发 JDK 25 的 Unsafe 弃用提示；这是原版提供的原生依赖，
测试保留其默认后端，与游戏的内存访问路径一致。

26.2 适配器继续使用 SERVER 配置和原有 serverconfig 目录。

## 命令生成器的验证范围

贴图、资源路径和 bounds 使用完整字符串参数，避免冒号或逗号被单词参数截断，导致整个 mcfunction 加载失败。
这些参数的回归直接使用 Brigadier 解析器，不需要启动游戏。

采样、公式、粒子年龄、存活上限、回收上界、图片颜色/方向、配置联动和协议有纯 JVM 回归。
普通 `gradlew test` 不启动 NeoForge 游戏环境；命令方块箭头、真实实体跟随、客户端顶点与纹理效果仍需在游戏里检查。
可用 [examples/commands](../examples/commands/) 的函数文件复现；新载荷要求联机双方一起更新。

## 程序化贴图与程序成员同步

贴图线程、视距外程序、既有粒子add入组后的迟到接管与首次迟到相位均通过PS原生复验：专属8项断言、六场84项检查及806帧全部通过，两类旧WARN为零。运行时语义、证据和未覆盖的联机/动态边界见[线程和成员同步](issues/runtime-texture-program-sync.md)。

## 可读特效编写接口

[PD-READABLE-API](issues/readable-effect-api.md) 已落地为编写门面，底层播放器与完成账本复用：
`Time/Length/Angle/...` 单位、公开 `Ease`、`particleVisual` / `lifeCurve`、类型化组标量表达式、
`Frames` + `Draw.polygon` 命名部件、`exit { particleSize.to(...) }` 与 `spawnAllDetailed` 已可用。

当前边界：

- 类型化表达式只生成现有组标量表达式后端，覆盖位置、颜色、缩放、`pow`、固定/可更新阶段与命名局部表达式；
  `glow/light` 和速度等逐粒子输出使用 `ParticleRecipe` 的 birth/motion；组字符串公式向这些属性赋值会报错。
  可更新阶段用 `group.phaseParameter(name)`，由 PD 管理开始、重入、完成与重置。
- `position.local` 使用组当前锚点，支持实体与可移动锚点；实体局部朝向由锚点的 `local` 开关控制。
- 标量表达式不能保留逐粒子初始位置，`position.preserve()` 明确报错。
- Java 端有单位、外观、曲线、形状、退场、`Scalars.pow` 与批量结果入口；类型化表达式的高级参数/实体输入以 Kotlin DSL 为主，
  Java 可用 `Expressions.builder` 生成位置/缩放/颜色文本或继续用字符串表达式。
- 部件选择器在生成时执行，部件句柄只记录成功成员；容量拒绝仍返回 null/`SpawnRejection.CAPACITY`。
- 退场完成使用客户端尺寸端点回报；程序载荷新增原起点字段，registrar 升到 `"14"`。没有同时追踪全部成员的玩家时走兜底清理。

组表达式新增局部坐标标志，采样生成声明新增网络载荷，当前 registrar 为 `"16"`；联机双方需要同时更新。字符串 API 使用
`expressionLocal`，命令使用 `expression local`，旧 `expression` 仍输出世界坐标。

数值文本已统一展开为普通十进制，求值器也接受科学计数法；角速度表达式与运行时阶段控制已补齐。
修复记录与剩余的游戏内验收项见[消费迁移](issues/readable-api-migration.md)。完整草案与验收见需求正文。

## 立体声声像与编辑器有两处差别

编辑器把声像串在 Web Audio 的 `StereoPanner` 上（`source → gain → panner → destination`）。播放端原来
把它交给 `AL_POSITION`，但 OpenAL 对立体声源在立体声输出下走直通声道，位置分量不参与混音，声像实际
无效。

现在立体声素材改用 `AL_SOFT_source_panning` 的 `AL_PAN_SOFT`，左右平衡真实生效。扩展不可用时退回
`AL_POSITION`（即改动前的行为，不报错）；单声道素材继续走 `AL_POSITION`，对它本来就有效。
开启 `AL_PANNING_ENABLED_SOFT` 后 `AL_POSITION` 不再参与该 source 的混音，两套声像不会叠加；
这个开关只作用于 `AudioStreamPlayer` 自建、只播 `.pdrawc` 音频的 source，游戏本体的 3D 定位音效不受影响。

与编辑器仍有两处差别，都只在中间段（pan = 0 与 pan = ±1 两端一致）：

| 差别 | 编辑器（Web Audio） | 播放端（OpenAL） |
| --- | --- | --- |
| 中段曲线 | 等功率（`gainL = cos(x·π/2)`、`gainR = sin(x·π/2)`） | 线性；pan=0 与 ±1 一致，中段偏大 |
| 对侧折叠 | 立体声输入按规范混入近侧输出（`outputL = inputL + inputR·gainL`） | 不折叠，近侧只有本来的内容 |

数值由 `StereoSourcePanTest` 用 `ALC_SOFT_loopback` 离屏渲染验证。要做到逐点一致只能自己按规范算增益
与折叠（在解码块上做每样本混音，或拆双单声道源并保持队列/seek/位置记账锁步），代价约 150~200 行，
不建议只为这两点做。

## 素材采样率不必等于设备率

播放端把 WAV/OGG 头里的采样率原样交给 `alBufferData`，由 OpenAL 重采样到设备率。两者不一致时，建
source 会换成带限 sinc 重采样器（`AL_SOFT_source_resampler`）：OpenAL 默认那档是纯插值、没有抗混叠，
会把奈奎斯特以上的内容按原电平折回可听带。

| 设备率 | 素材率 | 行为 |
| --- | --- | --- |
| 48k | 48k | 不设，mixer 走 1:1 快路径 |
| 48k | 192k | 设为带限 sinc |
| 48k | 44.1k | 设为带限 sinc（非整数比，同样在重采样） |
| 192k | 192k | 不设 |
| 查不到（返回 0） | 任意 | 仍会设（保守处理） |
| 缺扩展或没有带限 sinc 可选 | — | 不发任何 AL 调用，退回 OpenAL 默认 |

设备率在每次建 source 时现查，不缓存：同一次播放里设备换了率，缓存旧值会把「新设备率 == 素材率」
误判成立而漏换重采样器。行为由 `AudioResamplerAliasingTest` 读回 `AL_SOURCE_RESAMPLER_SOFT` 验证。

## 贴图与外观的时效

`particle:minecraft:end_rod` 等资源引用默认播放定义中的整个帧序列。有限寿命按年龄与寿命选帧，无限寿命
每 60 tick 循环；`@帧率` 覆盖为固定帧率循环，`#帧号` 固定为某一静态帧。默认粒子永存，`field` 可用
`lifetime=80` 指定 4 秒寿命，组也可用 `destroy_after` 清理。粒子年龄不随单人暂停流逝。
这条路径仍是 PD 粒子，不执行原版粒子工厂的随机运动、自动淡色等行为。没有 `textures` 列表的粒子不能通过
此方式复用；缺资源与帧号越界会显示紫黑错误贴图，并在客户端日志记录原因。切换资源包或 F3+T 后重新读取。
动态图集最多 256 帧、总计 16777216 像素，各帧需同尺寸，超出会明确报错并显示错误贴图。

粒子外观（贴图、子矩形 UV、各向异性、朝向、加色）在生成那一刻定死，之后不再产生逐 tick 开销；
运行期只能改颜色、缩放、位置、发光等级与光强乘数。需要换贴图一类的外观就销毁后重新生成。
未登记过的贴图名不会丢粒子，客户端按纯白方块渲染。

光强与片元外观分开：`lightIntensity` 只影响动态光照，热更时可以从当前实际亮度缓动过去；
`CurveChannel.LIGHT` 让光强在寿命内走独立曲线。两者都不会改变透明度或全亮标志。
