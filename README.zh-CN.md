# ParticleDrawing

[English](https://github.com/NekoWs/ParticleDrawing/blob/master/README.md) | [简体中文](https://github.com/NekoWs/ParticleDrawing/blob/master/README.zh-CN.md)

[![Build](https://github.com/NekoWs/ParticleDrawing/actions/workflows/build.yml/badge.svg)](https://github.com/NekoWs/ParticleDrawing/actions/workflows/build.yml)
[![Maven Central](https://img.shields.io/maven-central/v/work.nekow/particledrawing-neoforge-26.3?label=Maven%20Central)](https://central.sonatype.com/artifact/work.nekow/particledrawing-neoforge-26.3)
[![Modrinth](https://img.shields.io/modrinth/dt/particledrawing?label=Modrinth&logo=modrinth)](https://modrinth.com/mod/particledrawing)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](https://github.com/NekoWs/ParticleDrawing/blob/master/LICENSE)

ParticleDrawing 是一个基于 NeoForge 的 Minecraft 粒子效果库。用 `/pdraw` 可以在聊天栏、命令方块
或数据包函数中绘制图形、编排粒子组动画和控制发射器。模组开发者可以用 Kotlin 或 Java API 构建效果。
游戏中也能播放 ParticleDrawing 编辑器导出的 `.pdrawc` 动画。

服务端只需下发一次粒子组动画、寿命曲线和客户端发射器的配置，后续由客户端计算和渲染。
批量生成、速度、受力和跟踪更新会自动分包。

内置柔边圆点、线条和原版粒子贴图可直接使用，无需自定义资源包。ParticleDrawing 还支持自定义 PNG 贴图、
逐粒子 UV、独立宽高、固定朝向、加法混合和动态光照。

## 安装

| Minecraft | 最低 NeoForge | Java |
| --- | --- | --- |
| 1.21.1 | 21.1.235 | 21 |
| 26.2 | 26.2.0.59 | 25 |
| 26.3 | 26.3.0.101 | 25 |

1. 安装与 Minecraft 版本对应的 NeoForge。
2. 将对应的 ParticleDrawing JAR 放进 `mods/`。联机时，服务端与所有客户端需安装同一版本的 ParticleDrawing。
3. 进入游戏后，使用有命令权限的账号运行 `/pdraw help`。

下载地址见 [Modrinth](https://modrinth.com/mod/particledrawing) 与 [GitHub Releases](https://github.com/NekoWs/ParticleDrawing/releases)。

## 命令

第一条命令绘制圆环。第二条替换同名粒子组，画出一同上升、旋转并淡出的圆环和六芒星。
示例采用 `.mcfunction` 写法，在聊天栏执行时请加上开头的 `/`。

```mcfunction
# 绘制名为 sigil 的发光圆环。
pdraw particle draw circle as sigil at ~ ~1 ~ radius 3 count 96 look color aqua size 0.15 glow

# 用两个图形替换 sigil，并为整组编排动画。
pdraw particle draw circle as sigil at ~ ~1 ~ radius 3 count 96 look color aqua also hexagram at ~ ~1 ~ radius 2.4 segments 24 look color gold then move 0 1 0 40 then spin 0 1 0 2 then delay 40 then fade_out 20

# 定义并启动发射器。
pdraw particle emitter fire at ~ ~1 ~ sample count 60 spawn vec3(rand()-0.5,0,rand()-0.5) move vec3(0,0.06,0) look color orange size 0.1 glow life 30 rate 6 start

# 停止发射、清理实例或删除定义。
pdraw particle stop fire
pdraw particle clear emitter fire
pdraw particle delete emitter fire
```

| 命令 | 说明 |
| --- | --- |
| `/pdraw particle draw …` | 绘制一个或多个图元，命名粒子组，并追加 `then` 动作。 |
| `/pdraw particle emitter …` | 设置采样方式、出生与运动公式、外观和发射时序。 |
| `/pdraw particle template` / `use …` | 保存发射器声明，并在实例化时覆盖参数。 |
| `/pdraw particle start` / `burst` / `stop …` | 启动持续发射、触发一轮发射，或停止生成新粒子。 |
| `/pdraw particle clear` / `delete …` | 清理运行中的实例，或删除命名的发射器、粒子组或模板。 |
| `/pdraw particle advanced …` | 查看配方、修改运行中的发射器，或向已有组追加动作。 |
| `/pdraw animation …` | 列出、播放或停止 `.pdrawc` 动画，也可查看播放状态。 |
| `/pdraw camera …` | 控制自由镜头，用于录制和运镜。 |
| `/pdraw texture reload` | 从磁盘热重载粒子贴图。 |
| `/pdraw particle field …` | 配置粒子力场。 |

以下参数可组合使用。

- `draw` 支持 `dot`、`field`、`line`、`beam`、`helix`、`circle`、`disc`、`triangle`、
  `hexagram`、`rect`、`sphere`、`cuboid`、`text`、`sin` 和 `expr`。
- `stagger` 和 `enter fade|grow|fade_grow` 控制逐粒子入场。
- `then move|spin|delay|recolor|fade_out|follow …` 组成粒子组时间轴。
- `look` 控制颜色、尺寸、贴图、发光、混合模式和场景相关效果。
- `emitter` 支持 `sample`、`spawn`、`move`、`life`、`rate`、`window`、`collide`、`subemit`、
  `with environment`、`with probe` 和 `with on`。

按 Tab 可查看当前位置可用的参数。运行 `/pdraw help cmd draw` 或 `/pdraw help cmd emitter`
可查看对应的命令帮助。[粒子命令语法与示例](https://github.com/NekoWs/ParticleDrawing/blob/master/doc/particle-commands.md)

## API

在 Gradle 中配置 Maven Central，并按 Minecraft 版本选择依赖坐标。下面是 Minecraft 26.3 的 Kotlin DSL 依赖示例。
请将 `<version>` 替换为该目标已发布的 ParticleDrawing 版本。

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

[Minecraft 1.21.1](https://central.sonatype.com/artifact/work.nekow/particledrawing-neoforge-1.21.1) ·
[Minecraft 26.2](https://central.sonatype.com/artifact/work.nekow/particledrawing-neoforge-26.2) ·
[Minecraft 26.3](https://central.sonatype.com/artifact/work.nekow/particledrawing-neoforge-26.3)

在服务端主线程调用绘制 API，传入 `ServerLevel`。示例中的圆环会一边旋转一边淡入，停止旋转后再淡出。

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

- `Draw` 可绘制线段、曲线、折线、多边形、球体、长方体和点阵文字等，返回 `ParticleGroup`。
- `ParticleBatch` 用于管理成员会随时间增删的粒子流。
- `ParticleManager.emitter(anchor)` 可创建按移动距离或时间间隔发射的运行时发射器。
- `Effects` 可注册和播放命名特效，支持移动锚点和播放控制。
- `Animation.create { … }` 可直接在代码中构建动画，使用与 `.pdrawc` 相同的数据模型。

将 ParticleDrawing 打包进自己的模组时，可使用下列 Gradle 依赖声明。

```kotlin
jarJar(implementation("work.nekow:particledrawing-neoforge-26.3:<version>"))
```

旧发行版使用 `work.nekow:particledrawing:<version>`。[多版本布局与迁移指南](https://github.com/NekoWs/ParticleDrawing/blob/master/doc/multiversion.md)

## 播放编辑器动画

编辑器将项目保存为 `.pdraw`，并导出供游戏播放的 `.pdrawc`。动画可以包含时间轴轨道、摄像机轨道、
OGG/WAV 音频和带脚本的函数对象。

1. 从 [ParticleDrawing 编辑器](https://viewer.nekow.work/)导出 `.pdrawc` 文件。
2. 将文件放到游戏目录或专用服务端工作目录下的 `animations/`。
3. 在聊天栏运行 `/pdraw animation play meteor`，播放 `meteor.pdrawc`。

动画按需加载，可以在游戏运行时添加或替换文件。用 `/pdraw animation list` 列出动画，
用 `/pdraw animation stop` 停止当前维度的动画。

用 `/pdraw animation var <name> <value>` 更新脚本变量，用 `/pdraw animation debug` 查看播放状态，
或用 `/pdraw animation camera <name>` 预览摄像机轨道。Minecraft 1.21.1 暂不支持动画摄像机预览。
代码中可用 `ServerAnimationManager` 播放动画、更新变量或停止播放。[动画播放](https://github.com/NekoWs/ParticleDrawing/blob/master/doc/animation.md)

## 文档

- [文档索引](https://github.com/NekoWs/ParticleDrawing/blob/master/doc/README.md)
- [快速开始](https://github.com/NekoWs/ParticleDrawing/blob/master/doc/getting-started.md)
- [粒子命令](https://github.com/NekoWs/ParticleDrawing/blob/master/doc/particle-commands.md)
- [API 参考](https://github.com/NekoWs/ParticleDrawing/blob/master/doc/api-reference.md)
- [Kotlin 与 Java 示例](https://github.com/NekoWs/ParticleDrawing/blob/master/doc/guide.md)
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

根目录默认构建 Minecraft 26.3 版，需要 JDK 25。可用 `./gradlew -p platforms/neoforge-26.2 build` 或
`./gradlew -p platforms/neoforge-26.3 build` 单独构建指定的 26.x 版本。Minecraft 1.21.1 目标
（`./gradlew -p platforms/neoforge-1.21.1 build`）和独立核心（`./gradlew -p shared/core build`）
均以 Java 21 为目标。

## 发布

更新 `gradle.properties` 中的 `mod_version`，并在同一个提交上打标签 `v<mod_version>`。
推送标签会触发 Maven Central 发布（`publish.yml`）、Modrinth 发布（`modrinth.yml`）和
GitHub Release（`release.yml`）。三个发布工作流都会校验标签与 `mod_version` 是否一致。

为已有标签补发 GitHub Release 时，打开 **Actions → Publish GitHub Release → Run workflow**，
输入 `v<version>`。工作流使用内置 `GITHUB_TOKEN` 的 `contents: write` 权限。
重跑会替换同名附件并保留发布说明。

## 许可证

采用 [Apache License 2.0](https://github.com/NekoWs/ParticleDrawing/blob/master/LICENSE)。
