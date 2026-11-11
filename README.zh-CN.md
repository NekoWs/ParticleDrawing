# ParticleDrawing

[English](README.md) | **简体中文**

[![Build](https://github.com/NekoWs/ParticleDrawing/actions/workflows/build.yml/badge.svg)](https://github.com/NekoWs/ParticleDrawing/actions/workflows/build.yml)
[![Maven Central](https://img.shields.io/maven-central/v/work.nekow/particledrawing-neoforge-26.3?label=Maven%20Central)](https://central.sonatype.com/artifact/work.nekow/particledrawing-neoforge-26.3)
[![Modrinth](https://img.shields.io/modrinth/dt/particledrawing?label=Modrinth&logo=modrinth)](https://modrinth.com/mod/particledrawing)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
![Minecraft](https://img.shields.io/badge/Minecraft-26.2%20%2F%2026.3-informational)
![NeoForge](https://img.shields.io/badge/NeoForge-26.2%20%2F%2026.3-informational)

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

| Minecraft | 最低 NeoForge | Java |
| --- | --- | --- |
| 26.2 | 26.2.0.59 | 25 |
| 26.3 | 26.3.0.102 | 25 |

NeoForge 1.21.1 是下一个计划适配的目标。当前可下载 26.2 和 26.3。

## 安装

### 玩家

1. 安装适用于 Minecraft 26.2 或 26.3 的 NeoForge。
2. 将对应游戏版本的 JAR 放入 `mods/`。 [Modrinth](https://modrinth.com/mod/particledrawing) · [GitHub Releases](https://github.com/NekoWs/ParticleDrawing/releases)
3. 从[编辑器](https://viewer.nekow.work/)导出动画，把 `.pdrawc` 文件放进 `<gameDir>/animations/`。
4. 在游戏里使用 `/pdraw` 命令。动画按需加载，新增或替换动画后不需要重启游戏。

### 开发者

重构后的发行版在 Maven Central 按游戏版本区分坐标：

```kotlin
// build.gradle.kts
dependencies {
    compileOnly("work.nekow:particledrawing-neoforge-26.3:<version>")
    localRuntime("work.nekow:particledrawing-neoforge-26.3:<version>")
}
```

上述按游戏版本区分的坐标用于重构后的发行版；已发布旧版本继续使用
`work.nekow:particledrawing:<version>`。
使用 Minecraft 26.2 时，把坐标里的 `neoforge-26.3` 改为 `neoforge-26.2`。

`<version>` 取对应目标已发布的版本。 [26.2](https://central.sonatype.com/artifact/work.nekow/particledrawing-neoforge-26.2) · [26.3](https://central.sonatype.com/artifact/work.nekow/particledrawing-neoforge-26.3)

要把库嵌入自己的 JAR，可用 `jarJar(implementation("work.nekow:particledrawing-neoforge-26.3:<version>"))`。

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
| `/pdraw particle group ...` | 创建、列出或删除数据包与命令方块共用的命名粒子组。 |
| `/pdraw particle add ...` | 向组内添加点、线、光束、螺旋、法阵、球体等图元。 |
| `/pdraw particle emitter ...` | 分步配置采样、图片、公式、锚点与粒子寿命。 |
| `/pdraw particle program ...` | 用多条命令编排锚点、实体跟随、移动、旋转、脉冲、变量和逐粒子公式。 |
| `/pdraw animation list` | 列出 `animations/` 里可用的动画。 |
| `/pdraw animation play <name> [pos]` | 在 `pos` 播放动画，缺省为玩家前方 3 格。 |
| `/pdraw animation stop` | 停止当前维度里正在播放的全部动画与特效。 |
| `/pdraw animation camera <name\|stop>` | 通过正在播放动画的摄像机预览。 |
| `/pdraw animation var <name> <value>` | 更新正在播放动画的函数对象变量。 |
| `/pdraw animation debug` | 打印求值耗时、粒子数量与播放时间线。 |
| `/pdraw texture reload` | 从磁盘重新加载粒子贴图（仅客户端）。 |

编排粒子组用 `group → add → program`。曲线采样、图片和持续发射用 `emitter` 分步配置，再用
`burst` 或 `start` 触发。两种方式都可以在数据包函数和命令方块里使用。完整语法、
参数表和可直接放进数据包的法阵示例见[使用指南](doc/guide.md#命令绘制与数据包)。
命令方块入门可从[末地烛贴图的 sin 曲线](doc/guide.md#命令方块简单用法sin-曲线)开始；
`texture=particle:minecraft:end_rod` 可在 PD 粒子上直接复用原版贴图。
贴图默认按粒子年龄与寿命换帧；`field anchor` 与 `expression local` 可共用组锚点，`lifetime=80` 指定 4 秒寿命。 [数据包安装与函数示例](examples/commands/README.md)

## 文档

| 文档 | 内容 |
| --- | --- |
| [getting-started.md](doc/getting-started.md) | 依赖引入、第一个效果、核心概念。 |
| [guide.md](doc/guide.md) | 绘制形状、编排式动画、句柄、粒子集、发射器、动画。 |
| [particle-commands.md](doc/particle-commands.md) | 分步配置发射器、公式与图片采样。 |
| [api-reference.md](doc/api-reference.md) | 按包分类的类索引。 |
| [known-issues.md](doc/known-issues.md) | 已知限制，以及与编辑器之间的行为差异。 |
| [multiversion.md](doc/multiversion.md) | 核心边界、平台构建、发布坐标与类型迁移。 |

## 从源码构建

```bash
git clone https://github.com/NekoWs/ParticleDrawing.git
cd ParticleDrawing
./gradlew build          # compile and run the test suite
./gradlew runClient      # start a development client
./gradlew runServer      # start a development server
```

游戏适配器构建需要 JDK 25，根入口默认构建 26.3。两个目标可分别执行
`gradlew -p platforms/neoforge-26.2 build` 和 `gradlew -p platforms/neoforge-26.3 build`。
共享核心使用 Java 21，目录、坐标与迁移说明见[多版本架构](doc/multiversion.md)。

## 发布

`gradle.properties` 里的 `mod_version` 是唯一事实来源。它每次变化，同一个提交都会打上对应的标签
（`v<mod_version>`）。推送标签后，`publish.yml` 运行 `publishAndReleaseToMavenCentral`，
产物经 Central 验证和同步后可下载。`modrinth.yml` 按游戏目标发布到 [Modrinth](https://modrinth.com/mod/particledrawing)（同时把 `README.md` 同步为项目
正文）。`release.yml` 会构建两个游戏目标并创建 GitHub Release，附上各自的主 JAR、源码 JAR、SHA-256 校验文件，以及从上一个
标签以来的提交标题生成的发布说明。ALPHA、BETA、RC 标为预发布。三个工作流在标签名与
`mod_version` 不一致时都会直接失败，没有标签就没有发布。

给已有标签补发 GitHub Release 时，进入 **Actions → Publish GitHub Release → Run workflow**，
填写 `v<version>`。它会检出该标签源码，只运行 GitHub Release 工作流。使用内置的 `GITHUB_TOKEN`
和 `contents: write` 权限，无需新增 secret。重跑会替换同名附件，保留已有的发布说明。

## 许可

基于 [Apache License 2.0](LICENSE) 授权。
