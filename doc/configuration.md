# 配置

ParticleDrawing 使用 NeoForge 的 `ModConfigSpec` 定义配置，分为 **服务端配置** 与 **客户端配置** 两部分，在 `ParticleDrawing.kt` 入口处注册。所有配置项在运行时通过 `ConfigValue.get()` 实时读取。

## 配置文件位置

| 类型 | 路径 |
| --- | --- |
| 服务端 | `<游戏目录>/config/particledrawing-server.toml` |
| 客户端 | `<游戏目录>/config/particledrawing-client.toml` |

（多人服务器中，服务端配置位于 `world/serverconfig/` 下，由服务器管理。）

## 服务端配置（ServerConfig）

### particle_limits

| 键 | 默认值 | 范围 | 说明 |
| --- | --- | --- | --- |
| `maxParticlesPerDimension` | `100000` | `1..1000000` | 每个维度同时存活的最大粒子总数。达到上限后新的生成请求会被拒绝（`ParticleHandle.Builder.spawn()` 返回 `null`），并以限频方式输出警告日志。 |
| `maxParticlesPerPlayer` | `20000` | `1..100000` | 单个玩家同时可见的最大粒子数。在粒子生成广播时，对每个玩家独立计数，超过该上限的玩家不会收到新粒子的生成包。 |

### visibility

| 键 | 默认值 | 范围 | 说明 |
| --- | --- | --- | --- |
| `visibilityRadius` | `128.0` | `16.0..512.0` | 向玩家发送粒子的最大距离（格）。超过该距离的玩家不会收到该粒子的生成/更新数据包。 |
| `visibilityCheckInterval` | `10` | `1..100` | 周期性可见性重检的刻间隔。每过该间隔，服务端会对每个玩家重检：回收已越界/已销毁的粒子、补发新进入范围的粒子，使玩家移动后的可见性保持一致。 |

## 客户端配置（ClientConfig）

### dynamic_lights

| 键 | 默认值 | 范围 | 说明 |
| --- | --- | --- | --- |
| `enableDynamicLights` | `true` | — | 启用来自发光粒子的动态照明。 |
| `maxDynamicLights` | `256` | `0..1024` | 同时存在的动态光源最大数量。 |
| `dynamicLightMaxDistance` | `16.0` | `1.0..64.0` | 动态光照影响世界的最大距离。 |

### rendering

| 键 | 默认值 | 范围 | 说明 |
| --- | --- | --- | --- |
| `maxRenderParticles` | `50000` | `1..200000` | 客户端同时渲染的最大粒子数。达到上限后，客户端会忽略新的生成包。 |
| `particleBatchSize` | `4096` | `64..65536` | 客户端每帧同步粒子缓动状态的批次大小（按轮转顺序分批推进）。该值越小，单帧 CPU 占用越低，但粒子数超过该值时过渡动画的更新频率会相应降低。 |

## 实现说明

- **粒子数上限**（`maxParticlesPerDimension`）：在 `ServerParticleEngine.spawnParticle` 中强制；被拒绝时 `ParticleHandle.Builder.spawn()` 返回 `null`，`ParticleGroup.add()` 对 `null` 安全（跳过）。
- **每玩家粒子数上限**（`maxParticlesPerPlayer`）：服务端维护「粒子 ↔ 玩家」双向追踪表，在生成广播时按玩家计数，销毁/过期/清空时同步清理，玩家离开维度时自动回收。
- **可见性重检**（`visibilityCheckInterval`）：在 `ServerParticleEngine.tick` 中按间隔触发，双向校正（补发新进入范围的粒子、回收越界粒子）。
- **客户端渲染上限**（`maxRenderParticles`）：在 `ClientParticleEngine.spawnParticle` 中强制。
- **客户端批次同步**（`particleBatchSize`）：在 `ClientParticleEngine.frameUpdate` 中按轮转顺序分批推进非运动粒子的缓动同步；处于运动算法控制下的粒子由 `MotionSystem` 每帧直接驱动，不受此限制。

> 说明：多数配置在每次使用时通过 `.get()` 读取，修改后通常无需重启即可在下一次调用生效（NeoForge 会在世界加载 / 配置文件变更时重载）。
