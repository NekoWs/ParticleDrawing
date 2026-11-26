# 动态光照

## 创建光源

全亮与向世界投光是两个属性。命名外观用 `fullBright` 控制全亮，用 `Light.level` 或 `Light.only`
配置照明。旧接口的 `glowing(true)` 同时使粒子全亮并发光，可用 `lightLevel(0)` 关闭对外照明。

创建纯光源时用 `lightOnly(true)` 隐藏粒子外观。普通发光粒子的有效亮度由光照等级、强度乘数、
`LIGHT` 曲线、透明度和尺寸包络共同决定。纯光源的亮度只取前三项。

`spotlight(12.0, 35.0)` 创建半径为 12 格、外锥半角为 35° 的射灯。未指定颜色时使用白光。

```java
ParticleHandle lamp = manager.create().position(pos)
    .spotlight(12, 35).lightDirection(25, -35).lightOnly(true).spawn();
if (lamp != null) {
    lamp.lightDirection(70, -45, 20, EasingType.EASE_IN_OUT)
        .lightFocus(24, 20, EasingType.EASE_OUT)
        .lightIntensity(0.4f, 8);
    lamp.move(target, 8, EasingType.LINEAR);
}
```

`lightDirection` 与 `lightFocus` 可以独立缓动。时间单位是 tick，短重载也接收 `Time` 和 `Ease`。
中途改目标时从当前显示的值接续，偏航走最短角距。时长为 0 时立即生效，聚焦缓动的起点和终点均须为射灯。

```kotlin
lamp.lightDirection(70.0, -45.0, 20.ticks, Ease.IN_OUT)
    .lightFocus(24.0, 20.ticks, Ease.OUT)

lamp.lightFocus(32.0, 18.0, 1.5, durationTicks = 12, easing = EasingType.EASE_IN_OUT)
```

## 复用形状

在单颗、批量或发射器间复用配置时，用 `work.nekow.particledrawing.light.LightShape`。
需要立即替换整份形状时，可先组合再用一次 `lightShape(shape)` 提交。

```kotlin
val beam = LightShape.spot(12.0, 35.0, 20.0, 1.5)
    .withDirection(25.0, -35.0)
    .withFalloff(LightFalloff.SMOOTH)

val lamp = manager.create().position(pos).lightShape(beam).lightOnly(true).spawn() ?: return
val batchSpec = ParticleSpawnSpec().position(pos).lightShape(beam).lightOnly(true)
val emitter = manager.emitter(pos.x, pos.y, pos.z).interval(4).lightShape(beam).spawn()
val narrowed = beam.withDirection(70.0, -45.0).withFocus(24.0)
lamp.lightShape(narrowed)
emitter.lightShape(narrowed)
```

命名外观 DSL 用 `particleVisual { light = Light.only(15, 0.6f).withShape(beam) }`。
Java 可写 `ParticleAppearance.builder().light(Light.only(15, 0.6f).withShape(beam)).build()`。

射灯默认朝下。偏航 0° 朝 +Z，+90° 朝 −X，负俯仰向下。
锥角使用半角，未指定内锥时取外锥的 60%，内外锥相等时为硬边。锐度从 1 起。
半径取 0–64 格，0 使用客户端默认值。

有显式形状的光源使用粒子 RGB，`Color.WHITE` 为白光。`LightShape.point()` 创建可着色的点光。
`lightShape(null)` 清除显式形状，粒子若仍标记为发光，则恢复为默认白色点光。
`ParticleHandle.lightShape()` 返回目标形状，迟到或重新进入视距的客户端使用该目标值。
发射器更新形状后，随后出生的粒子使用新配置。

## 光强曲线

用 `lightIntensity(multiplier, durationTicks, easing)` 从当前实际亮度过渡到新的倍率。
用 `CurveChannel.LIGHT` 定义逐粒子寿命曲线，可与运行时倍率叠加。

```kotlin
manager.create().position(p).scale(3f).lifetime(20)
    .glowing(true).lightLevel(15)
    .curve(ParticleCurve.light(CurveKey.at(0, 1f), CurveKey.at(20, 0f)))
    .spawn()
```

## 命令

`/pdraw particle add` 使用 `key=value`。分步发射器用 `appearance <参数> <值>` 设置同样的属性。
完整语法见[粒子命令](particle-commands.md)。

| 参数 | 含义 |
| --- | --- |
| `light=15` | 0–15 的对外光照等级，0 关灯 |
| `intensity=0.75` | 非负强度倍数，最终亮度最高为 15 |
| `spot=45,25,2` | 外锥半角、内锥半角、边缘锐度；可省略后两项。内锥默认是外锥的 60%，锐度默认 1，内外锥相等为硬边 |
| `light_dir=0,-90` | 世界偏航与俯仰，单位为度。偏航 0 朝 +Z，俯仰 -90 朝下 |
| `light_range=12` | 灯的照射半径，最大 64 格，0 沿用客户端默认半径 |
| `light_falloff=smooth` | 径向衰减，支持 `constant`、`linear`（默认）、`smooth`、`inverse`、`inverse_square` |
| `color=#ff5938` | 设置粒子颜色；已配置形状的光源也用它为地形新增亮度上色 |
| `light_only=true` | 隐藏发光粒子的外观 |
| `light_curve=0:1,8:0.2,16:1` | 按粒子存活刻数调光，可与锥角、衰减和亮度组合 |

在平坦地面上执行以下函数，射灯位于执行点上方三格。

```mcfunction
pdraw particle group reset lamps ~ ~3 ~
pdraw particle add lamps dot ~ ~3 ~ light=15 light_only=true color=#ff5938 spot=35,20 light_dir=0,-90 light_range=12 light_falloff=smooth
```

`/pdraw light` 在单人世界显示光源和地形覆盖数据。

## 编辑器脚本

在 `process` 中更新位置、光强和光源形状，导出签名的 `.pdrawc` 后可在游戏中播放。

```js
func setup() {
  this.spawn({ color: [1, 1, 1, 1], scale: 0.16, glow: true, light: 15 })
}

func process() {
  let p = this.particles[0]
  let t = this.time / 1000
  let pulse = 0.5 + 0.5 * sin(t)
  let outer = 18 + 25 * pulse
  p.position = [2 * cos(t), 0, 2 * sin(t)]
  p.lightIntensity = 0.4 + 0.6 * pulse
  p.lightShape = [12, outer, outer * 0.6, 1, 45 * t, -55, "smooth"]
}
```

`lightShape` 依次为半径、外锥半角、内锥半角、边缘锐度、偏航、俯仰和衰减。
可在编辑器中声明外部变量并用它驱动这些字段。播放时用
`/pdraw animation var yaw 90` 更新脚本读取的 `yaw`。

## 客户端配置与光影

`maxDynamicLights` 和 `maxDynamicLightsPerCell` 分别限制全局和每 16 格立方区域参与照明的光源数。
`showSpotlightBeams` 可显示附近的射灯光束，光束沿光轴遇到方块时收短。
`maxShadowedParticleLights` 允许最近的 0–16 盏粒子灯投射方块阴影，默认 0。
该预算选择照射半径不超过 16 格的光源。方块碰撞形状作为遮挡体，客户端在相关方块或区块变化后，
下一帧刷新只读遮挡索引。光源小范围移动时复用索引，地形顶点使用当前光源位置计算遮挡。
阴影以更小的位移阈值优先提交地形重建，每批最多 128 个区段，提交间隔至少 25 毫秒。

读取原版光图的光影包可继承标量亮度。独立计算体素追踪或手持光源的管线可用
`ParticleLights.snapshotInto(out)` 读取当帧世界坐标、强度、半径、形状和 RGB。
读取时可复用目标列表，按自己的渲染预算选取光源。[渲染限制](known-issues.md#动态光照)

客户端配置 `[compatibility]` 段的 `terrainDynamicLights`（默认 `true`）单独控制动态光是否烘进地形。
装 Sodium 时地形网格由它的区块构建器生成，这一路改为把动态光并进 Sodium 的方块光采样，再按方块位置
给顶点上色，开关同时控制两条路；Sodium 下方块的自发光面与流体不受影响。
光影包自己计算地形光照、地面被粒子灯照得过曝时关掉它，实体和粒子仍然通过光图接收动态光。
启用 Iris 光影包后，客户端配置 `shaderCompat`（默认开）会把自定义粒子的绘制安排在 Iris
合成世界之后，保留加法混合、实例化和场景材质。如果光影包忽略原版光图，可以开启
`shaderLightPools`（默认关），用表面软光斑替换 ParticleDrawing 的地形顶点动态光。
其余兼容开关见[光影包](known-issues.md#光影包)。




## 亮度总开关

粒子光写进光图的等级由粒子自身的 `light`（0-15）决定；`[dynamic_lights] lightBrightness`（默认 1.0，0-2）再整体乘一次。
场景里发光粒子很多、地面被叠得发白，或者加色粒子在亮地面上褪成纯白时，把它调到 0.6-0.8 就能收住。

