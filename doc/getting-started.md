# 快速开始

把 ParticleDrawing 作为依赖引入，用几行 Kotlin / Java 在 Minecraft 世界里画出一段粒子动画。

## 环境要求

| 组件 | 版本 |
| --- | --- |
| Minecraft | 26.2 |
| NeoForge | 26.2.0.59+ |
| Java | 25 |

## 引入依赖

模组已发布到 Maven Central，包根为 `work.nekow.particledrawing`，坐标 `work.nekow:particledrawing`：

```kotlin
// build.gradle.kts
dependencies {
    compileOnly("work.nekow:particledrawing:<version>")
    localRuntime("work.nekow:particledrawing:<version>")
}
```

`<version>` 取最新发布版，见
[Maven Central](https://central.sonatype.com/artifact/work.nekow/particledrawing)。也可以把库打进自己的 jar，
让玩家不必单独安装：

```kotlin
dependencies {
    jarJar(implementation("work.nekow:particledrawing:<version>"))
}
```

在 `META-INF/neoforge.mods.toml` 里声明依赖：

```toml
[[dependencies.${mod_id}]]
modId = "particledrawing"
type = "required"
ordering = "AFTER"
```

公开 API 都在 `work.nekow.particledrawing.api` 包下。

## 第一个效果

在服务端任意时机（命令、事件回调、调度任务）执行：

```kotlin
import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.api.Draw
import work.nekow.particledrawing.api.ParticleManager

fun demo(manager: ParticleManager, center: Vec3) {
    Draw.circle(manager, center, radius = 4.0, count = 80)
        .fadeIn(15)                                    // 15 tick 淡入
        .spin(Vec3(0.0, 1.0, 0.0), Math.PI / 40)       // 每 tick 转 π/40 弧度
        .delay(100)                                    // 游标推到 100 tick
        .stopContinuous()                              // 停止旋转
        .fadeOut(20)                                   // 20 tick 淡出并销毁
}
```

`Draw.circle(...)` 画出形状并返回它的 `ParticleGroup`，后续的动画方法都挂在这个组上。

## 核心概念

| 概念 | 类型 | 说明 |
| --- | --- | --- |
| 粒子管理器 | `ParticleManager` | 维度级入口，用 `ParticleManager.of(level)` 获取 |
| 粒子组 | `ParticleGroup` | 一组粒子的集合，也是编排动画的单位；每个绘制形状都返回一个组 |
| 绘图工具 | `Draw` | 静态形状库：点、线段、曲线、圆、圆盘、三角、六芒星、矩形、球、长方体、折线 |
| 单粒子句柄 | `ParticleHandle` | 移动、速度、力、实体锚点、重着色、缩放、销毁单个粒子 |
| 粒子集 | `ParticleBatch` | 成员逐 tick 增删的粒子流（黑洞吸入、重力场下落一类） |
| 发射器 | `ParticleEmitter` / `EmitterHandle` | 沿锚点持续产出粒子的运行时声明 |
| 颜色 | `Color` | 不可变 RGBA，工厂方法 `of` / `ofInt` / `ofPacked` / `ofHsb` |
| 缓动 | `EasingType` | 14 种预设、无缓动（阶跃）与自定义三次贝塞尔 |

服务端权威：粒子生成、销毁与组变换都在服务端执行，客户端按本地时钟求值并渲染。

## 时间线

组上的动画方法按游标排列：`delay(n)` 把游标向前推进 n tick（累加、不清零），紧随其后的动画共享
同一时刻。上面例子里 `fadeIn` 与 `spin` 都在 t=0 开始，`stopContinuous` 与 `fadeOut` 都在 t=100 发生。
单位是 game tick（1 tick = 50ms）。

## 调用约定

- 所有 API 在服务端主线程调用，命令与事件回调天然满足。
- 粒子数量受服务端配置 `maxParticlesPerDimension`（维度总量）与 `maxParticlesPerPlayer`（单人追踪量）
  约束，达到上限时 `spawn()` 返回 `null`。
- Java 调用方：绘制形状与部分链式方法带 `@JvmOverloads` 重载，其余带默认参数的方法要写全参数；
  颜色来源用 `ColorSource` 接口或 lambda 实现。

## 下一步

- [guide.md](guide.md)：形状参数、编排动画、发射器、播放 `.pdrawc`、代码构建动画。
- [api-reference.md](api-reference.md)：按包分类的类索引。
- [known-issues.md](known-issues.md)：平台差异与已知限制。
