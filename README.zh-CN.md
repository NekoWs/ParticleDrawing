# ParticleDrawing

[English](README.md) | **简体中文**

[![Build](https://github.com/NekoWs/ParticleDrawing/actions/workflows/build.yml/badge.svg)](https://github.com/NekoWs/ParticleDrawing/actions/workflows/build.yml)
[![Maven Central](https://img.shields.io/maven-central/v/work.nekow/particledrawing-neoforge-26.3?label=Maven%20Central)](https://central.sonatype.com/artifact/work.nekow/particledrawing-neoforge-26.3)
[![Modrinth](https://img.shields.io/modrinth/dt/particledrawing?label=Modrinth&logo=modrinth)](https://modrinth.com/mod/particledrawing)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
![Minecraft](https://img.shields.io/badge/Minecraft-26.2%20%2F%2026.3%20%2F%201.21.1-informational)
![NeoForge](https://img.shields.io/badge/NeoForge-26.2%20%2F%2026.3%20%2F%2021.1-informational)

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
- 用 `/pdraw particle emitter` 配置采样、出生公式、逐刻运动和发射窗口：一段命令就能做出曲线、
  曲面、图片点阵和分阶段特效，运行期只下发声明。
- 单个数据包最多生成 256 条粒子规格，批量位置、速度、力与轨道操作自动分包。
- 命令参考见[粒子命令](doc/particle-commands.md)。
- 支持逐粒子贴图、子矩形 UV、各向异性尺寸、固定朝向、加色混合、内置柔边圆点与线段形状，以及自行同步
  到客户端的 PNG 登记，全程不需要资源包。
- 注册带可移动锚点的特效，控制其播放时钟，播放 OGG/WAV 音频并动态照亮粒子。

## 环境要求

| Minecraft | 最低 NeoForge | Java |
| --- | --- | --- |
| 26.2 | 26.2.0.59 | 25 |
| 26.3 | 26.3.0.101 | 25 |
| 1.21.1 | 21.1.235 | 21 |

当前可下载 26.2、26.3 与 1.21.1。

## 安装

### 玩家

1. 安装适用于 Minecraft 26.2、26.3 或 1.21.1 的 NeoForge。
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
| `/pdraw particle draw …` | 绘制一个或多个图元，并用 `then` 编排粒子组。 |
| `/pdraw particle emitter …` | 一条命令定义发射器，可立即播放。 |
| `/pdraw particle template` / `/pdraw particle use …` | 保存并复用发射器声明。 |
| `/pdraw particle advanced …` | 检查发射器、热更新配方或追加组动作。 |
| `/pdraw particle clear` / `/pdraw particle delete …` | 清理运行实例或删除命名定义。 |
| `/pdraw animation list` | 列出 `animations/` 里可用的动画。 |
| `/pdraw animation play <name> [pos]` | 在 `pos` 播放动画，缺省为玩家前方 3 格。 |
| `/pdraw animation stop` | 停止当前维度里正在播放的全部动画与特效。 |
| `/pdraw animation camera <name\|stop>` | 通过正在播放动画的摄像机预览。 |
| `/pdraw animation var <name> <value>` | 更新正在播放动画的函数对象变量。 |
| `/pdraw animation debug` | 打印求值耗时、粒子数量与播放时间线。 |
| `/pdraw texture reload` | 从磁盘重新加载粒子贴图（仅客户端）。 |

用 `draw` 绘制图元并编排粒子组。发射器用 `emitter` 定义，`start` 和 `burst` 控制播放。
语法和示例见[粒子命令](doc/particle-commands.md#命令绘制与数据包)。

## 文档

[文档目录](doc/README.md)

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
