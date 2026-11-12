# 多版本核心与平台适配

共享核心以 Java 21 为运行基线。已发布的 NeoForge 目标为 Minecraft 26.2、26.3 与 1.21.1：
26.x 要求 NeoForge 26.2.0.59 / 26.3.0.102 或更高的对应系列版本并使用 Java 25，
1.21.1 要求 NeoForge 21.1.235 或更高的 21.1 系列版本并使用 Java 21。

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
| `platforms/neoforge-1.21.1/src/` | 1.21.1 的渲染类型、桥接粒子、贴图重载与 Mixin 注入点 |
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

1.21.1 目标已接入：`platforms/neoforge-1.21.1`，NeoForge 21.1.235，Java 21，
使用独立的 `settings.gradle`、`target.properties` 与 `run/neoforge-1.21.1/` 运行目录。

### 共享边界

`neoforge-common` 里按原生 API 划分，被目标整份替换的文件列在 `target.properties` 的
`shared_replacements` 中（只排除共享目录里的那一份，目标目录同名文件照常编译）：

| 文件 | 差异 |
| --- | --- |
| `platform/PlatformCompat.kt` | 发包、世界时钟、资源标识、光图、权限、FOV、区块标脏、按 UUID 找实体 |
| `core/client/BridgeParticle.kt` | 1.21.1 用 `render(VertexConsumer, Camera, float)` + `getRenderType()` |
| `core/client/ParticleTextureReloadListener.kt` | 用 `RegisterClientReloadListenersEvent` 注册 |
| `mixin/BrightnessGetterMixin.java` | 注入点是 `LevelRenderer.getLightColor(BlockAndTintGetter, BlockPos)`（类，不是接口） |
| `mixin/EntityRendererMixin.java` | 与 26.x 相同 |
| `mixin/CameraMixin.java` | 1.21.1 暂未启用，见下文已知差异 |

`BatchedQuadParticleGroup`、`OrientedQuadRenderState` 与它们的两个几何测试是 26.x 的批量渲染构造，
1.21.1 没有对应实现，整份排除。

两代 API 的命名差异用类型别名抹平，别名在使用点就被擦除，不产生运行期引用：

```kotlin
typealias Identifier = ResourceLocation          // net.minecraft.resources
typealias BlockAndLightGetter = BlockAndTintGetter // net.minecraft.world.level
```

Kotlin 会为别名文件生成空 facade 类，这些类会把 `net.minecraft.*` 包带进模组模块并触发 FML 的包冲突，
因此编译后删除对应目录、打包时排除该路径（`drop_alias_facades`）。

### 打包与运行

1.21.1 是纯 Java 版，不像 26.x 自带 Kotlin 标准库。目标把 `kotlin-stdlib` 解包进编译输出目录，
使 `kotlin` 包归属模组自身模块，开发运行与发布 JAR 都自带运行时；编译前清理该目录，避免污染增量编译。
`neoforge.mods.toml` 与 `particledrawing.mixins.json` 也下沉到各目标，1.21.1 的清单需要
`modLoader="javafml"` 与 `loaderVersion`，Mixin 兼容级别为 `JAVA_21`。

### 验证范围

构建与 271 个平台测试通过；专服启动到 `Done`，模组、配置与 Mixin 正常加载且不加载客户端渲染类；
单人客户端以 64 颗正弦粒子场景验收：8 帧拼图逐帧连续、无闪动、无漂移，逐帧粒子像素值稳定。

### 已知差异

摄像机预览（`/pdraw camera`）在 1.21.1 上暂不可用：`Camera` 在启动早期被加载，此时模组类还不在可见范围内，
混入里对模组类的直接引用会在附加阶段抛 `ClassNotFoundException`。恢复该功能需要用反射桥接或延后注入。

[NeoForge 1.21.1 文档](https://docs.neoforged.net/docs/1.21.1/gettingstarted/)