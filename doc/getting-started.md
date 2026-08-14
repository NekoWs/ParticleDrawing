# 快速开始

## 环境要求

| 依赖 | 版本 |
| --- | --- |
| JDK | 25（Temurin 发行版推荐） |
| Kotlin | 2.4.10（由 Gradle 插件自动管理） |
| Gradle | 使用项目自带 Wrapper（9.6.1），无需手动安装 |
| IDE | IntelliJ IDEA（推荐） |

## 构建

在项目根目录执行：

```bash
# Windows
./gradlew.bat build

# Linux / macOS
./gradlew build
```

构建产物输出到 `build/libs/`，生成 `<mod_id>-<version>.jar`（例如 `particledrawing-1.0.0.jar`）。

常用命令：

| 命令 | 说明 |
| --- | --- |
| `./gradlew build` | 完整构建 |
| `./gradlew runClient` | 启动开发环境客户端 |
| `./gradlew runServer` | 启动开发环境服务端 |
| `./gradlew runClientData` | 运行数据生成（`clientData` 运行配置） |
| `./gradlew clean` | 清理构建产物（不影响源码） |
| `./gradlew --refresh-dependencies` | 刷新依赖缓存 |

## 安装到游戏

1. 执行 `./gradlew build`。
2. 将 `build/libs/particledrawing-1.0.0.jar` 复制到 NeoForge 实例的 `mods/` 目录。
3. 启动游戏。

## IDE 配置（IntelliJ IDEA）

1. 使用 IntelliJ IDEA 打开项目根目录（`build.gradle` 所在目录）。
2. IDEA 会通过 Gradle 自动同步依赖，首次同步可能需要较长时间（下载 Minecraft 与 NeoForge 依赖）。
3. 在运行配置中选择 `runClient` / `runServer` 即可启动。
4. 若缺少库或出现依赖问题，运行 `./gradlew --refresh-dependencies` 后重新同步。

## 在代码中使用

ParticleDrawing 是一个库式 mod，通过 `api` 包对外提供功能。服务端代码示例：

```kotlin
import work.nekow.particledrawing.api.*

fun spawnDemo(serverLevel: ServerLevel) {
    val manager = ParticleManager.of(serverLevel)

    // 生成单粒子
    manager.create()
        .style(ParticleStyle.DUST)
        .position(serverLevel.sharedSpawnPos.center)
        .color(Color.ofHsb(0.55f, 0.9f, 0.9f))
        .scale(0.4f)
        .lifetime(200)
        .spawn()
}
```

> 注意：`ParticleManager.of(Level)` 仅接受 `ServerLevel`，传入客户端 `Level` 会抛出 `IllegalArgumentException`。所有粒子操作必须发生在服务端。

## 快速验证

启动游戏后，在聊天框输入命令即可快速验证各功能：

```text
/particledraw circle 5 64
/particledraw demo vortex
/particledraw status
/particledraw clear
```

完整的命令列表见代码注释中的 `ParticleDrawCommands` 类，或 [类索引](./README.md)。

## 常见问题

- **IDEA 无法解析 Minecraft / NeoForge 符号**：等待 Gradle 同步完成，或执行 `./gradlew --refresh-dependencies` 后重新同步。
- **构建报错找不到 JDK 25**：在 IDEA 的 Gradle 设置或 `JAVA_HOME` 中指向 JDK 25。
- **运行时报数据包版本不匹配**：确保客户端与服务端都安装了相同版本的 ParticleDrawing。
