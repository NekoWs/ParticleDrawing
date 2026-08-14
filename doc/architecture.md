# 架构与数据流

## 包结构

```
work.nekow.particledrawing
├── ParticleDrawing.kt            # @Mod 入口，注册配置
├── api/                          # 公开 API
│   ├── ParticleManager.kt        # 维度级入口门面
│   ├── ParticleHandle.kt         # 单粒子句柄 + 构建器
│   ├── ParticleGroup.kt          # 粒子组（整体变换、运动）
│   ├── ParticleStyle.kt          # 粒子视觉样式枚举
│   ├── Color.kt                  # RGBA 颜色
│   ├── Draw.kt                   # 内置绘图工具
│   └── TransformOp.kt            # 组变换操作描述
├── core/
│   ├── server/                   # 服务端权威引擎
│   │   ├── ServerParticleEngine.kt
│   │   ├── ParticleData.kt
│   │   ├── ParticleGroupData.kt
│   │   ├── ParticleVisibilityManager.kt
│   │   ├── ServerParticleHandler.kt
│   │   └── DynamicLightCleanup.kt
│   ├── client/                   # 客户端渲染
│   │   ├── ClientParticleEngine.kt
│   │   ├── RenderParticle.kt     # 插值状态
│   │   ├── BridgeParticle.kt     # 桥接原版粒子
│   │   └── ParticleRenderHandler.kt
│   ├── network/                  # 网络层
│   │   ├── NetworkHandler.kt
│   │   ├── ClientPayloadHandler.kt
│   │   ├── ParticleSpawnPayload.kt
│   │   ├── ParticleUpdatePayload.kt
│   │   ├── ParticleDestroyPayload.kt
│   │   ├── ParticleGroupTransformPayload.kt
│   │   └── StreamCodecs.kt
│   ├── easing/                   # 缓动
│   │   ├── EasingType.kt
│   │   └── EasingCurve.kt
│   └── motion/                   # 帧级运动
│       ├── MotionAlgorithm.kt
│       ├── MotionSystem.kt
│       ├── MotionMath.kt
│       ├── MotionPayload.kt
│       └── algorithms/           # 内置算法
├── lighting/                     # 动态光照
│   ├── DynamicLightManager.kt
│   ├── DynamicLightEngine.kt
│   ├── DynamicLightPositions.kt
│   └── LightAttenuation.kt
├── command/
│   └── ParticleDrawCommands.kt   # /particledraw 命令与演示
├── config/
│   └── ParticleDrawingConfig.kt  # 配置定义
├── util/
│   └── ParticleUtils.kt
└── mixin/                        # Java Mixin（客户端光照）
    ├── EntityRendererMixin.java
    └── LightmapRenderStateExtractorMixin.java
```

## 核心数据流

```
┌─────────────────────────── 服务端 ───────────────────────────┐
│  ParticleManager.of(level)                                    │
│        │  getOrCreate                                          │
│        ▼                                                      │
│  ServerParticleEngine (每维度一个实例)                          │
│   ├─ spawnParticle / updateParticle / destroyParticle          │
│   ├─ applyGroupTransform / destroyGroup / tick                 │
│   └─ sendMotion                                                │
│        │  广播 (PacketDistributor, 按可见半径过滤)               │
└────────┼───────────────────────────────────────────────────────┘
         │  CustomPacketPayload (playToClient)
         ▼
┌─────────────────────────── 网络 ─────────────────────────────┐
│  particle_spawn / particle_update / particle_destroy           │
│  group_transform / motion                                       │
└────────┼───────────────────────────────────────────────────────┘
         │  ClientPayloadHandler (enqueueWork)
         ▼
┌─────────────────────────── 客户端 ───────────────────────────┐
│  ClientParticleEngine                                          │
│   ├─ RenderParticle   (保存当前值/目标值，按缓动曲线插值)          │
│   ├─ BridgeParticle   (SingleQuadParticle，接入原版渲染管线)      │
│   └─ MotionSystem     (帧级运动算法，逐帧计算)                    │
│        │  frameUpdate (ClientTickEvent.Post 每帧)               │
│        ▼                                                      │
│  渲染 + 动态光照 (DynamicLightManager / DynamicLightEngine)      │
└────────────────────────────────────────────────────────────────┘
```

## 关键设计

### 服务端权威 + 客户端插值

服务端持有粒子的**权威状态**（`ParticleData`）。当属性变化时，服务端把**目标值 + 缓动参数 + 持续时间**打包发送给客户端；客户端在 `RenderParticle.tick()` 中根据缓动曲线逐帧插值，从而在低网络开销下获得平滑动画。

- `durationTicks = 0` → 客户端立即跳变（`snap` 同步）。
- 组变换使用粒子的**目标位置**（而非插值中的当前值）计算，避免逐帧漂移。

### 每维度一个引擎

`ServerParticleEngine.getOrCreate(dimensionId)` 维护全局维度 → 引擎映射，`dimensionId` 由维度的资源标识符做名称哈希得到（`ParticleUtils.dimensionUUID`），保证确定性。维度卸载时在 `LevelEvent.Unload` 中清理对应引擎。

### 可见性过滤

服务端广播时按 `ParticleDrawingConfig.SERVER.visibilityRadius` 对玩家做距离过滤（`ParticleVisibilityManager.isWithinRange`），减少无谓的网络流量。

### 生命周期

- 服务端 `ServerParticleHandler.onServerTick` 每 tick 推进所有维度引擎的 `tick()`，处理粒子过期与组清理。
- 客户端 `ParticleRenderHandler.onClientTick` 每帧推进插值与运动系统。

## 线程与并发

| 组件 | 线程 | 说明 |
| --- | --- | --- |
| `ServerParticleEngine` | 服务端主线程 | 状态使用 `ConcurrentHashMap`，组成员使用 `CopyOnWriteArrayList` |
| `ClientParticleEngine` | 客户端渲染线程 | 同样使用 `ConcurrentHashMap` |
| `DynamicLightManager` | 渲染线程 + Mixin 注入查询 | 使用 `ReentrantReadWriteLock` 保护活跃光源列表 |
| `MotionSystem` | 客户端渲染线程 | `ConcurrentHashMap`，目标点提供者 `targetProvider` 为 `@Volatile` |

> 粒子 API 的调用方（插件 / 其他 mod）应确保在**服务端线程**调用 `ParticleManager` 相关方法；跨线程调用需自行同步。
