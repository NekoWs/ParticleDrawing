# 网络协议

ParticleDrawing 使用 NeoForge 的 `CustomPacketPayload` + `StreamCodec` 机制实现服务端 → 客户端的粒子同步。所有数据包均为 `playToClient`（服务端发送、客户端接收），注册于 `NetworkHandler.register`，协议版本字符串为 `"1"`。

## 数据包总览

| 数据包 | 通道 ID | 方向 | 用途 |
| --- | --- | --- | --- |
| `ParticleSpawnPayload` | `particledrawing:particle_spawn` | S→C | 生成粒子 |
| `ParticleUpdatePayload` | `particledrawing:particle_update` | S→C | 增量更新（位置/颜色/缩放 + 缓动） |
| `ParticleDestroyPayload` | `particledrawing:particle_destroy` | S→C | 销毁粒子（单个/批量/整组） |
| `ParticleGroupTransformPayload` | `particledrawing:group_transform` | S→C | 整组变换 |
| `MotionPayload` | `particledrawing:motion` | S→C | 启动/停止帧级运动算法 |

## ParticleSpawnPayload

```kotlin
data class ParticleSpawnPayload(
    val particleId: UUID,
    val style: ParticleStyle,      // 以 ordinal (VarInt) 传输
    val x: Double, val y: Double, val z: Double,
    val r: Float, val g: Float, val b: Float, val a: Float,
    val scale: Float,
    val lifetime: Int,
    val groupId: UUID?,            // 可空
    val glowing: Boolean
)
```

## ParticleUpdatePayload

支持增量更新（`hasPosition` / `hasColor` / `hasScale` 标志位），并附带缓动参数。

```kotlin
data class ParticleUpdatePayload(
    val particleId: UUID,
    val x: Double, val y: Double, val z: Double,   // 目标位置
    val r: Float, val g: Float, val b: Float, val a: Float, // 目标颜色
    val scale: Float,                              // 目标缩放
    val durationTicks: Int,
    val hasPosition: Boolean,
    val hasColor: Boolean,
    val hasScale: Boolean,
    val e0: Double, val e1: Double, val e2: Double, val e3: Double, val e4: Double  // 缓动序列化
)
```

工厂方法：`positionOnly`、`colorOnly`、`scaleOnly`、`full`。缓动参数通过 `EasingType.serialize()` 生成，客户端用 `EasingType.deserialize` 还原。

## ParticleDestroyPayload

```kotlin
data class ParticleDestroyPayload(
    val particleIds: Array<UUID>,
    val groupId: UUID?             // 组销毁时携带
)
```

工厂方法：`single(id)`、`group(groupId, memberIds)`、`batch(ids)`。

## ParticleGroupTransformPayload

整组变换，`transformType` 取值 `0=平移, 1=旋转, 2=重着色, 3=缩放`。

```kotlin
data class ParticleGroupTransformPayload(
    val groupId: UUID,
    val transformType: Int,
    val dx: Double, val dy: Double, val dz: Double,   // 平移增量
    val ax: Double, val ay: Double, val az: Double,   // 旋转轴
    val radians: Double,
    val r: Float, val g: Float, val b: Float, val a: Float, // 目标颜色
    val targetScale: Float,
    val px: Double, val py: Double, val pz: Double,   // 变换轴心
    val durationTicks: Int,
    val e0: Double, val e1: Double, val e2: Double, val e3: Double, val e4: Double
)
```

工厂方法：`translate`、`rotate`、`recolor`、`scale`。

## MotionPayload

```kotlin
data class MotionPayload(
    val groupId: UUID,
    val active: Boolean,           // true=启动, false=停止
    val algorithmId: String,       // 算法 ID（如 "rotate"、"vortex"）
    val params: DoubleArray,       // 算法参数
    val px: Double, val py: Double, val pz: Double  // 组轴心
)
```

## 编解码工具

`StreamCodecs`（内部对象）提供：

- `UUID_CODEC` —— UUID 的读写（两个 `long`）。
- `writeNullableUUID(buf, id)` / `readNullableUUID(buf)` —— 可空 UUID（布尔标志 + 两个 `long`）。

## 客户端处理

`ClientPayloadHandler` 在 `context.enqueueWork` 中把数据包分发给 `ClientParticleEngine` 的对应方法：

| 数据包 | 客户端方法 |
| --- | --- |
| `particle_spawn` | `spawnParticle(...)` |
| `particle_update` | `updateParticle(...)` |
| `particle_destroy` | `destroyParticles(ids)` |
| `group_transform` | `applyGroupTransform(...)` |
| `motion` | `addMotion(...)` |

> 协议变更时需更新 `NetworkHandler` 中的版本字符串（当前 `"1"`），否则新版客户端与旧版服务端之间可能出现数据包不兼容。
