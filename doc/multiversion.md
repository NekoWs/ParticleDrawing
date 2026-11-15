# 多版本构建

从 `master` 构建各个 Minecraft 目标。最低 NeoForge 版本与 Java 要求见[快速开始](getting-started.md)，
实际构建参数由 `platforms/<目标>/target.properties` 提供。

## 代码边界

| 目录 | 职责 |
| --- | --- |
| `shared/core/` | Java 21 核心，包含动画解析与验签、脚本求值、时钟、曲线、采样和数学类型 |
| `platforms/neoforge-common/src/` | 公用的游戏 API、命令、网络、运行时、渲染与音频接入 |
| `platforms/neoforge-<版本>/` | 版本依赖、模组清单、Mixin 配置和原生接口适配 |
| `gradle/neoforge-platform.gradle` | 源码组合、工具链、开发运行和发布 |

`verifyPlatformIndependence` 检查核心的源码、依赖、字节码引用和 Java 21 字节码上限。
游戏侧 API、网络注册和资源加载留在平台层。一次游戏构建将核心与平台源码归入同一个模组。

## 类型与原生服务

动画模型使用 `work.nekow.particledrawing.math.Vec3` 与 `ResourceId`。游戏入口接受 Minecraft 原生类型。
Kotlin 可用 `toCore()` 和 `toMinecraft()` 转换 `Vec3`，Java 可调用 `VectorInteropKt` 的同名静态方法。
直接构造核心模型的旧调用方在迁移到多版本构建时需重新编译。

OGG 解码通过 `ScriptWavePcm.OggReader` 服务接口接入，NeoForge 目标提供 STB 实现。WAV 由核心读取。
动画验签使用 Java 的 Ed25519，独立宿主还需提供与 Gson 2.10.1 API 兼容的运行时。

## 构建与运行

根目录的 `gradlew build` 默认构建 26.3。独立目标使用以下命令。

```powershell
.\gradlew.bat -p shared/core build
.\gradlew.bat -p platforms/neoforge-1.21.1 build runGameTestServer
.\gradlew.bat -p platforms/neoforge-26.2 build runGameTestServer
.\gradlew.bat -p platforms/neoforge-26.3 build runGameTestServer
```

`build` 执行回归测试与打包检查，`runGameTestServer` 启动服务端并运行 GameTest。
客户端可用 `runClient`，专服可用 `runServer`。游戏目录为 `run/<目标>/<运行名>/`，动画、粒子图片和存档
放在相应目标的运行目录内。[CI 矩阵](../.github/workflows/build.yml)

## 适配与迁移

- `PlatformCompat` 封装发包、时钟、资源标识、实体查找、光图、区块标脏和权限的版本差异。
  若需整份替换共享文件，在目标配置的 `shared_replacements` 中列出对应路径。
- 1.21.1 使用 `VertexConsumer` 和 `ParticleRenderType` 渲染粒子。`Identifier` 与
  `BlockAndLightGetter` 映射到该版本的原生类型，编译后由 `drop_alias_facades` 清除别名生成的空类。
- 26.3 接入 Renderpearl 及 OIT。加色粒子分别提供深度范围、透射率和颜色累积管线。
  服务端配置在 `config/particledrawing-server.toml`，世界可用 `syncedconfig/` 下的同名文件覆盖。
  迁移旧世界时将 `serverconfig/particledrawing-server.toml` 移到该世界的 `syncedconfig/`。
  26.2 继续使用 `serverconfig/`。[NeoForge 26.3 迁移说明](https://docs.neoforged.net/primer/docs/26.3/)
- 接入其他 1.21.x 版本时，在 `platforms/` 新增目标或扩展已验证目标的兼容范围，并同步 CI 和发布矩阵。
  客户端渲染、Mixin 注入与服务端加载应分别验证。

各目标共用动画格式与网络载荷。当前 `.pdrawc` 格式为 18，动画网络编解码版本为 9，NeoForge 载荷注册
版本为 20。联机双方应采用匹配构建。平台功能差异见[已知限制](known-issues.md)。

## 发布

每个目标生成 `particledrawing-<Minecraft版本>-neoforge-<version>.jar`，主 JAR 内嵌核心。
26.3 通过 Jar-in-Jar 提供 Kotlin 标准库。1.21.1 将标准库并入模组类输出，使其归属同一 FML 模块。
源码 JAR 包含同一次构建的核心与平台源码。

Maven 坐标为 `work.nekow:particledrawing-neoforge-<Minecraft版本>:<version>`，旧发行版使用
`work.nekow:particledrawing:<version>`。`verifyPackagedCore` 检查包内核心与宿主实现，
`verifyPublishedDependencies` 检查 POM 对独立核心的引用。

一个 `v<version>` 标签对应所有目标。GitHub Release 汇集主 JAR、源码 JAR 与校验文件，
Maven Central 和 Modrinth 按游戏版本发布。
