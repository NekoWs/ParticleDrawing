# 多版本核心与平台适配

共享核心以 Java 21 为运行基线。已发布的 NeoForge 目标为 Minecraft 26.2 和 26.3，分别要求
NeoForge 26.2.0.59 和 26.3.0.102 或更高的对应系列版本，游戏适配器均使用 Java 25。
下一目标是 NeoForge 1.21.1，见[接入计划](#接入-1211)。

同一份 `.pdrawc` 由两端共用的核心解析，公式、时间单位、命令语法和载荷字段布局跨目标保持一致。

## 代码边界

| 目录 | 职责 |
| --- | --- |
| `shared/core/src/main/kotlin/` | 动画模型与求值、脚本和标量虚拟机、时钟、曲线、图片采样、配方模拟、资源标识和数学 |
| `shared/core/src/test/` | 不加载 Minecraft 的核心回归 |
| `shared/core/src/testFixtures/` | 两个平台与编辑器共用的签名文件、脚本夹具和帧时钟测试工具 |
| `platforms/neoforge-common/src/` | 当前两套 26.x 适配器共编译的世界与实体接入、命令、网络、API、渲染、资源与音频 |
| `platforms/neoforge-26.2/src/` | 26.2 渲染管线、配置类型等版本差异 |
| `platforms/neoforge-26.3/src/` | Renderpearl/OIT 管线、SYNCED 配置等版本差异 |
| `gradle/neoforge-platform.gradle` | 26.x 的 Java 25、NeoGradle runs、源码组合、核心打包与发布规则 |

核心禁止导入 Minecraft、Forge、NeoForge、Mojang 渲染和 LWJGL 类型。
`verifyPlatformIndependence` 同时检查源码、编译依赖、JAR 字节码引用与 Java 21 字节码上限。
Minecraft 网络包装和命令注册保留在平台层；核心接收普通模型和参数，输出可供宿主提交的粒子状态。

## 类型与原生服务

核心坐标为 `work.nekow.particledrawing.math.Vec3`，资源标识为 `ResourceId`。
动画粒子、文本位置、采样点、配方锚点与速度单位使用核心类型。
Minecraft API 入口继续接受原生向量，并在边界转换；Kotlin 端也提供模型工厂重载。
读取模型位置后需要交给 Minecraft 接口时，使用 `toMinecraft()`；原生向量进入核心时使用 `toCore()`：

```kotlin
import work.nekow.particledrawing.platform.toCore
import work.nekow.particledrawing.platform.toMinecraft

val simulationPosition = entity.position().toCore()
val minecraftPosition = simulationPosition.toMinecraft()
```

Java 可调用 `VectorInteropKt.toCore(...)` / `VectorInteropKt.toMinecraft(...)`。
直接构造核心模型的 Java 调用方应使用核心 Vec3；模型构造器与位置字段的 JVM 类型已经变化，依赖方需要重新编译。
常用的 ParticleManager、Draw、ParticleHandle 等游戏入口保留平台向量接口。

OGG 的窗口管理和脚本采样在核心，STB 解码通过 `ScriptWavePcm.OggReader` 服务接口提供。
平台通过 META-INF/services 注册解码器；缺少宿主实现时明确报错。WAV 直接读取不需要原生解码器。
验签使用 Java 21 已具备的 Ed25519 实现，仍要求签名有效。
核心按 Gson 2.10.1 的 API 编译，游戏运行时使用 Minecraft 自带的 Gson；独立宿主需自行提供兼容版本。
开发运行也把核心与平台源码归入同一个模组，禁止自动重复归组，避免同包类被模块加载器隔离。

## 构建与运行

根目录的 Gradle 入口默认构建 26.3，保留日常使用的 `gradlew build`。
各平台也能通过独立 settings 和目标属性单独构建；两者消费相同的核心源代码。
构建输出按宿主隔离，游戏运行目录为 `run/<目标>/<运行名>/`，例如 `run/neoforge-26.3/client/`。
旧的 `run/client/` 文件保持原位；切换开发运行目录后，动画、粒子图片和测试存档应放入对应目标目录。

```powershell
.\gradlew.bat -p shared/core build
.\gradlew.bat -p platforms/neoforge-26.3 build
.\gradlew.bat -p platforms/neoforge-26.2 build
.\gradlew.bat -p platforms/neoforge-26.3 runGameTestServer
.\gradlew.bat -p platforms/neoforge-26.2 runGameTestServer
```

CI 使用 Java 21 构建独立核心，使用 Java 25 构建两个游戏适配器。各平台的 `build` 执行核心测试、
平台测试和打包检查，`runGameTestServer` 验证服务端加载。

2026-11 在两个游戏目标的单人客户端验证了动态 sin 曲线、图片点阵、法阵与三维曲面、实体锚点跟随、
有限寿命回收，以及签名动画的静态贴图、UV 公式、换帧和加法混合。测试使用独立的新建存档，主音量关闭。
26.3 同时核验默认透明度和改进透明度（OIT），实际后端为 OpenGL。
派生粒子的首帧贴图与后续 UV 同步由 `FunctionTextureTest` 和客户端场景共同验证。

## 分发与后续适配

每个模组 JAR 内嵌同一次构建的核心，源码 JAR 同时包含平台和核心源码。按游戏版本选择 PD 模组 JAR。
`verifyPackagedCore` 检查核心类和 OGG 宿主实现是否齐全。
Maven POM 不引用另一个未发布的核心制品，`verifyPublishedDependencies` 对此设门禁。

重构后的目标坐标为：

- `work.nekow:particledrawing-neoforge-26.2:<version>`
- `work.nekow:particledrawing-neoforge-26.3:<version>`

模组文件名为 `particledrawing-<Minecraft版本>-neoforge-<version>.jar`。
这些坐标已发布到 Maven Central，旧版继续使用 `work.nekow:particledrawing:<version>`。
一个 PD 标签对应两个目标，GitHub Release 汇集两个目标的主 JAR、源码 JAR 与校验文件；
Modrinth 版本号包含游戏版本，各目标单独发布，保留其他目标的推荐状态。
Maven 工作流使用 `publishAndReleaseToMavenCentral`，制品在 Central 完成验证和同步后可下载。

`master` 维护多版本代码，各游戏版本以目标目录区分，历史发行版通过标签定位。

## 接入 1.21.1

NeoForge 1.21.1 尚未接入。目标使用 Java 21，沿用独立平台构建、核心打包、目标坐标和隔离运行目录。
先核对目标的加载器、世界查询、网络、资源、渲染、音频与 Mixin 签名，再决定哪些代码可跨版本共编译。
目前 `neoforge-common` 按 26.x 的原生 API 编译，接入新版本时应按实际接口差异划分共享边界。
[NeoForge 1.21.1 文档](https://docs.neoforged.net/docs/1.21.1/gettingstarted/)

使用相同的核心测试、动画夹具与命令示例验证行为等价。平台专有的渲染管线可以换用 1.21.1 的实现，
验收覆盖普通透明、加法混合、朝向、UV 与动态光照，以及命令、锚点、音频、摄像机和联机同步。
目标通过构建、服务端启动和客户端画面验证后，再加入对应发布目标与公开安装说明。
