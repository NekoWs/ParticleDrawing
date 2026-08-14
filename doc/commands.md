# 命令与演示

ParticleDrawing 提供 `/particledraw` 命令，用于快速验证各功能。命令在服务端执行（需要玩家身份；单机需开启作弊）。

## 命令树

```
/particledraw
├── line <count>                  # 在视线前方生成彩色线段
├── circle <radius> <count>       # 生成圆形粒子环
├── disc <radius> <count>         # 生成实心圆盘
├── glow <count>                  # 生成一圈彩虹发光粒子
├── stress <count>                # 压力测试：批量生成大量粒子
├── group                         # 生成测试粒子组
│   ├── rotate                    # 旋转测试组 360°
│   ├── move                      # 上移测试组（弹跳缓动）
│   └── recolor                   # 将测试组重着色为蓝色
├── status                        # 显示服务端/客户端粒子数量
├── styles                        # 在面前排列显示所有粒子样式
├── demo                          # 启动演示
│   ├── wave                      # 正弦波圆环
│   ├── rain                      # 粒子雨
│   ├── sphere                    # 球体（旋转 + 渐变）
│   ├── magic                     # 法阵（六芒星 + 内外圆 + 跟随）
│   ├── matrix                    # 粒子矩阵（距离缩放）
│   ├── tornado                   # 龙卷风
│   ├── vortex                    # 涡旋
│   ├── heart                     # 爱心
│   ├── helix                     # DNA 双螺旋
│   ├── spiral                    # 星系
│   └── shockwave                 # 雷达波
└── clear                         # 清除所有演示与粒子
```

## 基础命令

| 命令 | 参数范围 | 说明 |
| --- | --- | --- |
| `line <count>` | `1..5000` | 在视线前方生成一条彩虹渐变色线段 |
| `circle <radius> <count>` | 半径 `0.5..50`，数量 `4..10000` | 在 XZ 平面生成青色圆环 |
| `disc <radius> <count>` | 半径 `0.5..30`，数量 `4..5000` | 生成填充圆盘 |
| `glow <count>` | `1..500` | 一圈 `GLOW` 样式发光粒子 |
| `stress <count>` | `100..50000` | 分批生成大量粒子，输出耗时与总数 |
| `status` | — | 显示服务端粒子/组数与客户端粒子数 |
| `styles` | — | 排列展示全部 `ParticleStyle` |
| `clear` | — | 停止演示并清空当前维度所有粒子 |

## 组操作命令

`/particledraw group` 先生成一个红色圆形测试组，随后可对其操作：

- `/particledraw group rotate` —— 绕 Y 轴旋转 360°，80 tick，`EASE_IN_OUT`。
- `/particledraw group move` —— 上移 2 格，`EASE_OUT_BOUNCE` 弹跳缓动。
- `/particledraw group recolor` —— 重着色为蓝色。

## 演示（demo）

每个 `demo` 子命令对应 `ParticleDrawCommands` 中的一个演示，展示不同的 API 用法与运动算法。

| 命令 | 粒子规模 | 关键技术 |
| --- | --- | --- |
| `demo` | ~120 | 圆环绕 Y 轴旋转（服务端 tick 驱动） |
| `demo wave` | ~80 | 每粒子独立 Y 轴正弦波（`engine.update` 增量更新） |
| `demo rain` | ~80 | 粒子下落与触底复位（`createGroup` + `ParticleHandle`） |
| `demo sphere` | ~800 | `Draw.sphere` + `rotateMotion` + `colorGradientMotion` |
| `demo magic` | ~500 | `Draw.hexagram` + 多组叠加 + `follow_player` + `rotate` |
| `demo matrix` | ~8000 | `Draw.cuboid` + `scale_by_distance` |
| `demo tornado` | ~7000 | 手写螺旋线 + `SwirlAlgorithm` |
| `demo vortex` | ~数千 | `Draw.disc` + `VortexAlgorithm` |
| `demo heart` | ~890 | 心形参数方程 + 服务端心跳/粒子雨/星光 |
| `demo helix` | ~数千 | DNA 双螺旋 + `rotate` |
| `demo spiral` | ~数千 | 倾斜旋臂星系 + `rotate` |
| `demo shockwave` | ~600 | 同心环外扩 + 服务端 tick 驱动 |

> 演示由 `ServerParticleHandler.onServerTick` 通过 `ParticleDrawCommands.tickDemos()` 驱动。使用 `/particledraw clear` 可停止并销毁所有演示。

## 演示中的 API 流程示例

以 `circle` 演示为例，展示了最典型的调用链：

```kotlin
val manager = ParticleManager.of(level)
val center = player.position().add(player.lookAngle.scale(4.0))

// 1. 在指定平面等间距生成 count 个粒子，自动归组
val group = Draw.circle(manager, center, 3.0, 120, Draw.Axis.XZ,
    Color.WHITE, ParticleStyle.DUST, 0.35f)

// 2. 存入 DemoState，由 tickDemos() 每 tick 旋转
group.rotate(Vec3(0.0, 1.0, 0.0), Math.toRadians(3.0), 0, EasingType.LINEAR)
```
