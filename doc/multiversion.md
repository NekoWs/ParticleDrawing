# 多版本核心与平台适配

共享核心以 Java 21 为运行基线，为 Minecraft 1.21.x 及后续版本提供接入基础。
当前已实现的游戏目标为 NeoForge 26.2 和 26.3；1.21.x 的游戏适配器尚未接入。
`.pdrawc` 格式、公式、时间单位、命令语法和网络字段布局保持原有约定。

## 代码边界

| 目录 | 职责 |
| --- | --- |
| `shared/core/src/main/kotlin/` | 动画模型与求值、脚本和标量虚拟机、时钟、曲线、图片采样、配方模拟、资源标识和数学 |
| `shared/core/src/test/` | 不加载 Minecraft 的核心回归 |
| `shared/core/src/testFixtures/` | 两个平台与编辑器共用的签名文件、脚本夹具和帧时钟测试工具 |
| `platforms/neoforge-common/src/` | 世界与实体接入、命令、网络封装、API 游戏入口、渲染桥接、资源读取、原生音频 |
| `platforms/neoforge-26.2/src/` | 26.2 渲染管线、配置类型等版本差异 |
| `platforms/neoforge-26.3/src/` | Renderpearl/OIT 管线、SYNCED 配置等版本差异 |
| `gradle/neoforge-platform.gradle` | 两个平台共用的构建、打包与发布规则 |

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

核心在 Java 21 下编译和测试，两个游戏适配器使用 Java 25。CI 分别运行独立核心和两个平台的构建，
平台构建同时执行核心测试、平台测试、打包门禁与 GameTest 启动检查。
客户端 GPU 画面、真实命令方块链和多人效果仍需游戏内验收。

## 分发与后续适配

每个模组 JAR 内嵌同一次构建的核心；用户按游戏版本安装一个 PD 模组文件即可。
`verifyPackagedCore` 检查核心类和 OGG 宿主实现是否齐全。
Maven POM 不引用另一个未发布的核心制品，`verifyPublishedDependencies` 对此设门禁。

重构后的目标坐标为：

- `work.nekow:particledrawing-neoforge-26.2:<version>`
- `work.nekow:particledrawing-neoforge-26.3:<version>`

模组文件名为 `particledrawing-<Minecraft版本>-neoforge-<version>.jar`。
这些坐标由重构后的发行流程发布；已发布旧版本仍使用原有坐标。
一个 PD 标签对应两个目标，GitHub Release 汇集两个目标的主 JAR、源码 JAR 与校验文件；
Modrinth 版本号包含游戏版本，两个目标各自发布。
发布时保留其他版本已有的推荐状态，不自动取消其他目标的推荐。

接入 1.21.x 时，应新增目标适配器并独立验证加载器、世界查询、网络、资源与渲染接口，
继续使用相同的核心测试和夹具。版本目标没有实现并通过验证前，不扩大 Minecraft 兼容范围。
