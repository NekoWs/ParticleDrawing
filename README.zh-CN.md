# ParticleDrawing

[English](https://github.com/NekoWs/ParticleDrawing/blob/master/README.md) | [简体中文](https://github.com/NekoWs/ParticleDrawing/blob/master/README.zh-CN.md)

[![Build](https://github.com/NekoWs/ParticleDrawing/actions/workflows/build.yml/badge.svg)](https://github.com/NekoWs/ParticleDrawing/actions/workflows/build.yml)
[![Maven Central](https://img.shields.io/maven-central/v/work.nekow/particledrawing-neoforge-26.3?label=Maven%20Central)](https://central.sonatype.com/artifact/work.nekow/particledrawing-neoforge-26.3)
[![Modrinth](https://img.shields.io/modrinth/dt/particledrawing?label=Modrinth&logo=modrinth)](https://modrinth.com/mod/particledrawing)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](https://github.com/NekoWs/ParticleDrawing/blob/master/LICENSE)
![Minecraft](https://img.shields.io/badge/Minecraft-26.2%20%2F%2026.3%20%2F%201.21.1-informational)
![NeoForge](https://img.shields.io/badge/NeoForge-26.2%20%2F%2026.3%20%2F%2021.1-informational)

ParticleDrawing 是一个面向 NeoForge 的服务端权威粒子效果库。效果主要通过 `/pdraw` 命令系统或
Kotlin/Java API 构建，也可以播放 ParticleDrawing 编辑器导出的 `.pdrawc` 动画。

服务端声明并拥有效果，客户端负责求值与渲染。编排组、寿命曲线和运行时发射器只下发一次，由客户端
逐帧求值，因此运行中的效果几乎不产生逐 tick 带宽开销。

## 亮点

- **命令优先。** 一条命令即可绘制形状、编排粒子组并声明发射器。支持聊天栏、命令方块和数据包
  函数，带 Tab 补全和游戏内帮助。
- **API 优先。** 用 Kotlin 或 Java 通过 `Draw`、`ParticleGroup`、`ParticleBatch` 和运行时发射器
  绘制与动画。不需要 `.pdrawc` 文件或编辑器。
- **基础效果不需要资源包。** 内置柔边圆点和线条形状，直接使用原版粒子贴图；逐粒子 UV、各向异性
  尺寸、加法混合、固定朝向和 PNG 注册都已支持。
- **声明式，运行期开销低。** 采样布局、出生与运动公式、寿命曲线和编排时间轴都在客户端求值。
  批量生成、速度、受力和跟踪操作会自动分包。
- **多版本。** 同一套代码支持 Minecraft 26.2、26.3 和 1.21.1，运行在 NeoForge 上。
- **可选编辑器播放。** 可播放带摄像机、音频、函数对象和运行时变量的 `.pdrawc` 动画，文件放在
  `<gameDir>/animations/`。

## 命令

`/pdraw` 可在聊天栏、命令方块和 `.mcfunction` 数据包函数中使用。在函数中省略开头的 `/`。运行
`/pdraw help` 打开主题索引，或 `/pdraw help cmd draw`、`/pdraw help cmd emitter` 查看完整参数。

```mcfunction
# 绘制一个命名组并播放动画。
pdraw particle draw circle as sigil at ~ ~1 ~ radius 3 count 96 look color aqua size 0.15 glow
pdraw particle draw circle as sigil at ~ ~1 ~ radius 3 count 96 look color aqua also hexagram at ~ ~1 ~ radius 2.4 segments 24 look color gold then move 0 1 0 40 then spin 0 1 0 2 then delay 40 then fade_out 20

# 声明发射器并在同一条命令里启动。
pdraw particle emitter fire at ~ ~1 ~ sample count 60 spawn vec3(rand()-0.5,0,rand()-0.5) move vec3(0,0.06,0) look color orange size 0.1 glow life 30 rate 6 start

# 停止发射、清理实例或删除定义。
pdraw particle stop fire
pdraw particle clear emitter fire
pdraw particle delete emitter fire
```

| 命令 | 说明 |
| --- | --- |
| `/pdraw particle draw …` | 绘制一个或多个图元，命名粒子组，并追加 `then` 动作。 |
| `/pdraw particle emitter …` | 用采样、公式、外观和发射窗口声明一个发射器。 |
| `/pdraw particle template` / `use …` | 保存发射器声明，并在实例化时覆盖参数。 |
| `/pdraw particle start` / `burst` / `stop …` | 控制持续发射、一次性爆发和正在运行的实例。 |
| `/pdraw particle clear` / `delete …` | 清理运行中的实例，或删除命名的发射器、粒子组或模板。 |
| `/pdraw particle advanced …` | 查看配方、修改运行中的发射器，或向已有组追加动作。 |
| `/pdraw animation …` | 列出、播放、停止和调试 `.pdrawc` 动画；切换预览摄像机。 |
| `/pdraw camera …` | 驱动自由镜头姿态，用于录制和运镜。 |
| `/pdraw texture reload` | 从磁盘热重载粒子贴图。 |
| `/pdraw particle field …` | 配置程序化力场入口。 |

常用语法：

- `draw` 支持 `dot`、`field`、`line`、`beam`、`helix`、`circle`、`disc`、`triangle`、
  `hexagram`、`rect`、`sphere`、`cuboid`、`text`、`sin` 和 `expr`。
- `stagger` 和 `enter fade|grow|fade_grow` 控制逐粒子入场。
- `then move|spin|delay|recolor|fade_out|follow …` 组成粒子组时间轴。
- `look` 控制颜色、尺寸、贴图、发光、混合模式和场景相关效果。
- `emitter` 支持 `sample`、`spawn`、`move`、`life`、`rate`、`window`、`collide`、`subemit`、
  `with environment`、`with probe` 和 `with on`。

更多语法和示例：[粒子命令](https://github.com/NekoWs/ParticleDrawing/blob/master/doc/particle-commands.md)。

## API

按游戏版本加入依赖：

```kotlin
// build.gradle.kts
dependencies {
    compileOnly("work.nekow:particledrawing-neoforge-26.3:<version>")
    localRuntime("work.nekow:particledrawing-neoforge-26.3:<version>")
}
```

```toml
# META-INF/neoforge.mods.toml
[[dependencies.${mod_id}]]
modId = "particledrawing"
type = "required"
ordering = "AFTER"
```

Minecraft 26.2 请把 `neoforge-26.3` 换成 `neoforge-26.2`。已发布版本见 Maven Central：
[26.2](https://central.sonatype.com/artifact/work.nekow/particledrawing-neoforge-26.2) ·
[26.3](https://central.sonatype.com/artifact/work.nekow/particledrawing-neoforge-26.3)。

在服务端主线程绘制：

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

`Draw` 对大多数形状返回 `ParticleGroup`；同一套 API 还覆盖线、曲线、折线、多边形、球体、长方体
和文字点阵。除了单次绘制组，还可以：

- 用 `ParticleBatch` 管理成员随时间增删的粒子流。
- 用 `ParticleManager.emitter(anchor)` 声明按距离或间隔发射的运行时发射器。
- 用 `Effects` 注册和播放带可移动锚点、播放时钟的命名特效。
- 用 `Animation.create { … }` 在代码里直接构建与 `.pdrawc` 等效的动画。
- 从同一套 API 使用逐粒子贴图、UV 帧、各向异性尺寸、固定朝向、动态光照和 OGG/WAV 音频。

完整 API 索引：[API 参考](https://github.com/NekoWs/ParticleDrawing/blob/master/doc/api-reference.md)。
更多代码示例：[指南](https://github.com/NekoWs/ParticleDrawing/blob/master/doc/guide.md) 和
[快速开始](https://github.com/NekoWs/ParticleDrawing/blob/master/doc/getting-started.md)。

## 编辑器与 `.pdrawc` 动画

ParticleDrawing 编辑器把项目保存为 `.pdraw`，导出运行时使用的 `.pdrawc` 文件。这条流程是可选的；
命令和 API 可以覆盖不需要编辑器工程的效果。

1. 从[编辑器](https://viewer.nekow.work/)导出动画。
2. 把文件放进 `<gameDir>/animations/`。
3. 在游戏内播放：

```mcfunction
pdraw animation play meteor
pdraw animation list
pdraw animation stop
```

动画按需加载，添加或替换文件后不需要重启游戏。播放还支持预览摄像机、运行时函数对象变量和计时
诊断：

```mcfunction
pdraw animation camera <name>
pdraw animation var <name> <value>
pdraw animation debug
```

在代码里，可以用 `ServerAnimationManager` 播放 `ParticleAnimation` 或 `.pdrawc` 文件、更新变量和
停止播放。摄像机、音频资产、函数对象和时间轴轨道都属于动画格式。

更多：[动画播放](https://github.com/NekoWs/ParticleDrawing/blob/master/doc/animation.md)。

## 环境要求

| Minecraft | 最低 NeoForge | Java |
| --- | --- | --- |
| 1.21.1 | 21.1.235 | 21 |
| 26.2 | 26.2.0.59 | 25 |
| 26.3 | 26.3.0.101 | 25 |

## 安装

### 玩家

1. 为 Minecraft 26.2、26.3 或 1.21.1 安装 NeoForge。
2. 把对应游戏版本的 ParticleDrawing JAR 放进 `mods/`。
3. 在游戏内使用 `/pdraw`。如果要播放编辑器动画，再把 `.pdrawc` 文件放进
   `<gameDir>/animations/`。

下载：[Modrinth](https://modrinth.com/mod/particledrawing) ·
[GitHub Releases](https://github.com/NekoWs/ParticleDrawing/releases)。

在服务器上，客户端和服务端需要安装相同版本的 ParticleDrawing。

### 开发者

依赖坐标按游戏版本区分。要把 ParticleDrawing 打包进自己的 JAR：

```kotlin
jarJar(implementation("work.nekow:particledrawing-neoforge-26.3:<version>"))
```

旧版本仍使用 `work.nekow:particledrawing:<version>` 坐标。坐标、运行目录和迁移说明见
[多版本指南](https://github.com/NekoWs/ParticleDrawing/blob/master/doc/multiversion.md)。

## 文档

- [文档索引](https://github.com/NekoWs/ParticleDrawing/blob/master/doc/README.md)
- [快速开始](https://github.com/NekoWs/ParticleDrawing/blob/master/doc/getting-started.md)
- [粒子命令](https://github.com/NekoWs/ParticleDrawing/blob/master/doc/particle-commands.md)
- [API 参考](https://github.com/NekoWs/ParticleDrawing/blob/master/doc/api-reference.md)
- [动画播放](https://github.com/NekoWs/ParticleDrawing/blob/master/doc/animation.md)
- [多版本布局](https://github.com/NekoWs/ParticleDrawing/blob/master/doc/multiversion.md)
- [已知问题](https://github.com/NekoWs/ParticleDrawing/blob/master/doc/known-issues.md)

## 从源码构建

```bash
git clone https://github.com/NekoWs/ParticleDrawing.git
cd ParticleDrawing
./gradlew build          # 编译并运行测试
./gradlew runClient      # 启动开发客户端
./gradlew runServer      # 启动开发服务端
```

构建需要 JDK 25。根构建目标为 26.3，共享核心目标为 Java 21。可用
`./gradlew -p platforms/neoforge-26.2 build` 或 `./gradlew -p platforms/neoforge-26.3 build`
单独构建某个适配版本。

## 发布

`gradle.properties` 的 `mod_version` 是唯一版本来源。每次修改后，同一个提交会打上匹配的
`v<mod_version>` 标签。推送标签会通过 `publish.yml` 发布到 Maven Central，通过 `modrinth.yml`
发布每个目标到 Modrinth，并通过 `release.yml` 创建 GitHub Release。标签名与 `mod_version`
不一致时所有工作流都会失败。

如果要为已有标签补发 GitHub Release，打开 **Actions → Publish GitHub Release → Run workflow**，
输入 `v<version>`。工作流使用内置 `GITHUB_TOKEN` 的 `contents: write` 权限，不需要额外 secret。
重跑会更新同名附件并保留现有发布说明。

## 许可证

基于 [Apache License 2.0](https://github.com/NekoWs/ParticleDrawing/blob/master/LICENSE) 授权。
