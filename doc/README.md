# ParticleDrawing 文档

ParticleDrawing 是一个面向 [NeoForge](https://neoforged.net/)（Minecraft 26.2）的高性能粒子效果库。它以 **服务端权威（server-authoritative）** 的架构提供跨维度粒子管理、缓动动画、帧级运动算法、动态光照，以及内置的绘图 API 与命令演示系统。

## 快速上手

```kotlin
// 服务端代码
val manager = ParticleManager.of(serverLevel)

// 生成单个粒子
val handle = manager.create()
    .style(ParticleStyle.DUST)
    .position(x, y, z)
    .color(Color.RED)
    .scale(0.5f)
    .lifetime(100)                    // -1 = 永存
    .spawn()

// 绘制一个圆环，返回可整体变换的粒子组
val circle = Draw.circle(manager, center, radius = 5.0, count = 64, axis = Draw.Axis.XZ)
circle.rotate(Vec3(0.0, 1.0, 0.0), Math.PI * 2, 80, EasingType.EASE_IN_OUT)
circle.recolor(Color.RED, 40, EasingType.EASE_OUT)
```

## 文档目录

| 文档 | 内容 |
| --- | --- |
| [项目概览](./overview.md) | 特性清单、技术栈、定位 |
| [快速开始](./getting-started.md) | 环境要求、构建、运行、安装、IDE 配置 |
| [架构与数据流](./architecture.md) | 包结构、服务端/客户端数据流、线程模型 |
| [核心 API](./api.md) | `ParticleManager`、`ParticleHandle`、`ParticleGroup`、`Draw`、`Color`、`ParticleStyle`、`TransformOp` |
| [缓动系统](./api.md#缓动系统-easingtype--easingcurve) | 预设与自定义缓动曲线 |
| [运动算法](./motion-algorithms.md) | 帧级运动系统与内置算法（旋转、涡旋、渐变等） |
| [动态光照](./dynamic-lighting.md) | 发光粒子、光照注入、衰减函数 |
| [配置](./configuration.md) | 服务端与客户端配置项说明 |
| [命令与演示](./commands.md) | `/particledraw` 命令树与内置演示 |
| [网络协议](./network-protocol.md) | 自定义数据包格式与编解码 |

## 项目元信息

| 项目 | 值 |
| --- | --- |
| Mod ID | `particledrawing` |
| Minecraft | 26.2 |
| NeoForge | 26.2.0.59 |
| Java | 25 |
| Kotlin | 2.4.10 |
| 源码命名空间 | `work.nekow.particledrawing` |
| 许可证 | All Rights Reserved（保留所有权利） |
