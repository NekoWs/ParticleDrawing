# ParticleDrawing

[English](README.md) | **简体中文**

[![Build](https://github.com/NekoWs/ParticleDrawing/actions/workflows/build.yml/badge.svg)](https://github.com/NekoWs/ParticleDrawing/actions/workflows/build.yml)
[![Maven Central](https://img.shields.io/maven-central/v/work.nekow/particledrawing?label=Maven%20Central)](https://central.sonatype.com/artifact/work.nekow/particledrawing)
[![Modrinth](https://img.shields.io/modrinth/dt/particledrawing?label=Modrinth&logo=modrinth)](https://modrinth.com/mod/particledrawing)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
![Minecraft](https://img.shields.io/badge/Minecraft-26.2-informational)
![NeoForge](https://img.shields.io/badge/NeoForge-26.2.0.59-informational)

面向 NeoForge 的服务端权威粒子效果库，播放 [ParticleDrawing 编辑器](https://viewer.nekow.work/)导出的动画，
并提供 Kotlin / Java API，在代码里直接绘制与编排粒子。

粒子由服务端创建与持有，客户端本地求值并渲染。编排式粒子组、寿命曲线与运行时发射器这类声明式能力只下发
一次、在客户端求值，因此一段运行中的效果几乎不占逐 tick 带宽。

## 功能

- 用命令播放 `<gameDir>/animations/` 下的 `.pdrawc` 文件，或在代码里通过 `ServerAnimationManager`
  播放，并支持运行期更新变量。
- 用 `Draw` 在代码里绘制线段、圆、圆盘、曲线、折线、三角形、六芒星、矩形、球体与长方体，支持渐变着色
  与逐粒子入场延迟。
- 在 `ParticleGroup` 上录制位移、旋转、缩放、脉冲、颜色渐变以及表达式或实体驱动的运动时间线，客户端
  按渲染帧回放。
- 把拖尾或光环一次声明为运行时发射器，各客户端沿锚点按里程或按间隔发射，并带确定性的逐粒子抖动。
- 单个数据包最多生成 256 条粒子规格，批量位置、速度、力与轨道操作自动分包。
- 支持逐粒子贴图、子矩形 UV、各向异性尺寸、固定朝向、加色混合、内置柔边圆点与线段形状，以及自行同步
  到客户端的 PNG 登记，全程不需要资源包。
- 注册带可移动锚点的特效，控制其播放时钟，播放 OGG/WAV 音频并动态照亮粒子。

## 环境要求

| 组件 | 版本 |
| --- | --- |
| Minecraft | 26.2 |
| NeoForge | 26.2.0.59+ |
| Java | 25 |

## 安装

### 玩家

1. 安装适用于 Minecraft 26.2 的 NeoForge。
2. 把模组 jar 放进 `mods/` 目录，或从 [Modrinth](https://modrinth.com/mod/particledrawing) 安装。
3. 从[编辑器](https://viewer.nekow.work/)导出动画，把 `.pdrawc` 文件放进 `<gameDir>/animations/`。
4. 在游戏里使用 `/pdraw` 命令。动画按需加载，新增或替换动画后不需要重启游戏。

### 开发者

库已发布到 Maven Central：

```kotlin
// build.gradle.kts
dependencies {
    compileOnly("work.nekow:particledrawing:<version>")
    localRuntime("work.nekow:particledrawing:<version>")
}
```

`<version>` 取最新发布版，见顶部的 Maven Central 徽章或
[Maven Central](https://central.sonatype.com/artifact/work.nekow/particledrawing)。要把库打进自己的 jar、
不要求玩家单独安装，用 `jarJar(implementation("work.nekow:particledrawing:<version>"))`。

还要在 `META-INF/neoforge.mods.toml` 里声明依赖：

```toml
[[dependencies.${mod_id}]]
modId = "particledrawing"
type = "required"
ordering = "AFTER"
```

一个最小效果：

```kotlin
import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.api.Draw
import work.nekow.particledrawing.api.ParticleManager

val manager = ParticleManager.of(level)

Draw.circle(manager, Vec3(0.0, 70.0, 0.0), radius = 3.0, count = 80)
    .fadeIn(15)
    .spin(Vec3(0.0, 1.0, 0.0), Math.PI / 40)
    .delay(100)
    .stopContinuous()
    .fadeOut(20)
```

更多内容见[快速开始](doc/getting-started.md)。

## 命令

| 命令 | 说明 |
| --- | --- |
| `/pdraw list` | 列出 `animations/` 里可用的动画。 |
| `/pdraw play <name> [pos]` | 在 `pos` 播放动画，缺省为玩家前方 3 格。 |
| `/pdraw stop` | 停止当前维度里正在播放的全部动画与特效。 |
| `/pdraw reload` | 从磁盘重新加载粒子贴图（仅客户端）。 |
| `/pdraw camera <name\|stop>` | 通过正在播放动画的摄像机预览。 |
| `/pdraw var <name> <value>` | 更新正在播放动画的函数对象变量。 |
| `/pdraw debug` | 打印求值耗时、粒子数量与播放时间线。 |

## 文档

| 文档 | 内容 |
| --- | --- |
| [getting-started.md](doc/getting-started.md) | 依赖引入、第一个效果、核心概念。 |
| [guide.md](doc/guide.md) | 绘制形状、编排式动画、句柄、粒子集、发射器、动画。 |
| [api-reference.md](doc/api-reference.md) | 按包分类的类索引。 |
| [known-issues.md](doc/known-issues.md) | 已知限制，以及与编辑器之间的行为差异。 |

## 从源码构建

```bash
git clone https://github.com/NekoWs/ParticleDrawing.git
cd ParticleDrawing
./gradlew build          # compile and run the test suite
./gradlew runClient      # start a development client
./gradlew runServer      # start a development server
```

构建需要 JDK 25，运行期产物输出到 `build/libs/`。

## 发布

`gradle.properties` 里的 `mod_version` 是唯一事实来源。它每次变化，同一个提交都会打上对应的标签
（`v<mod_version>`）。推送这样的标签会通过 `publish.yml` 把产物发布到 Maven Central；标签名与
`mod_version` 不一致时该工作流会直接失败，没有标签就没有发布。

## 许可

基于 [Apache License 2.0](LICENSE) 授权。
