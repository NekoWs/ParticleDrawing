# 已知限制

## 程序化贴图与程序成员同步

单机服务端登记贴图会越线程触碰GPU；组程序也可能发给没有收到成员出生包的玩家，出现空程序警告和迟到成员接管缺口。两项尚未修复，原因、复现与验收见[线程和成员同步](issues/runtime-texture-program-sync.md)。

## 可读特效编写接口

[PD-READABLE-API](issues/readable-effect-api.md) 已落地为编写门面，底层播放器与完成账本复用：
`Time/Length/Angle/...` 单位、公开 `Ease`、`particleVisual` / `lifeCurve`、类型化组标量表达式、
`Frames` + `Draw.polygon` 命名部件、`exit { particleSize.to(...) }` 与 `spawnAllDetailed` 已可用。

当前边界：

- 类型化表达式只生成现有组标量表达式后端，覆盖位置、颜色、缩放、`pow`、固定/可更新阶段与命名局部表达式；
  `glow/light` 等其它输出继续用字符串 API。可更新阶段用 `group.phaseParameter(name)`，由 PD 管理开始、重入、完成与重置。
- `position.local` 只适用于固定轴心；实体/可移动轴心的局部坐标会显式拒绝，不偷偷按世界原点处理。
- 标量表达式不能保留逐粒子初始位置，`position.preserve()` 明确报错。
- Java 端有单位、外观、曲线、形状、退场、`Scalars.pow` 与批量结果入口；类型化表达式的高级参数/实体输入以 Kotlin DSL 为主，
  Java 可用 `Expressions.builder` 生成位置/缩放/颜色文本或继续用字符串表达式。
- 部件选择器在生成时执行，部件句柄只记录成功成员；容量拒绝仍返回 null/`SpawnRejection.CAPACITY`。
- 退场完成使用客户端尺寸端点回报；协议新增观察/完成载荷，registrar 升到 `"13"`。没有同时追踪全部成员的玩家时走兜底清理。

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

粒子外观（贴图、子矩形 UV、各向异性、朝向、加色）在生成那一刻定死，之后不再产生逐 tick 开销；
运行期只能改颜色、缩放、位置、发光等级与光强乘数。需要换贴图一类的外观就销毁后重新生成。
未登记过的贴图名不会丢粒子，客户端按纯白方块渲染。

光强与片元外观分开：`lightIntensity` 只影响动态光照，热更时可以从当前实际亮度缓动过去；
`CurveChannel.LIGHT` 让光强在寿命内走独立曲线。两者都不会改变透明度或全亮标志。
