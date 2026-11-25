# 快速开始

## 环境要求

| Minecraft | 最低 NeoForge | Java |
| --- | --- | --- |
| 1.21.1 | 21.1.235 | 21 |
| 26.2 | 26.2.0.59 | 25 |
| 26.3 | 26.3.0.101 | 25 |

选择与游戏版本对应的 ParticleDrawing JAR，放入 `mods/`。联机时，客户端与服务端安装同一版本的
ParticleDrawing。[播放第一个动画](animation.md#播放动画文件)

## Gradle 依赖

以下以 Minecraft 26.3 为例。坐标末尾替换为目标游戏版本，`<version>` 取该目标已发布的版本。

```kotlin
dependencies {
    compileOnly("work.nekow:particledrawing-neoforge-26.3:<version>")
    localRuntime("work.nekow:particledrawing-neoforge-26.3:<version>")
}
```

在自己的 `META-INF/neoforge.mods.toml` 中声明依赖。

```toml
[[dependencies.${mod_id}]]
modId = "particledrawing"
type = "required"
ordering = "AFTER"
```

## 第一个粒子效果

在服务端主线程调用。`level` 是 `ServerLevel`，`center` 是原生 `Vec3`。

```kotlin
val manager = ParticleManager.of(level)
Draw.circle(manager, center, radius = 4.0, count = 80)
    .fadeIn(15)
    .spin(Vec3(0.0, 1.0, 0.0), Math.PI / 40)
    .delay(100)
    .stopContinuous()
    .fadeOut(20)
```

`Draw.circle` 返回 `ParticleGroup`。示例在第 0 刻启动淡入和旋转，在第 100 刻停止旋转并开始淡出。
1 刻为 50 毫秒。[更多代码示例](guide.md) · [类型索引](api-reference.md)
