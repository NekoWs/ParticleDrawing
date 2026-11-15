# 动态光源 / 射灯：现状与卡点交接

> 后续验收已完成。26.2、26.3 和 1.21.1 的开发客户端均显示三种锥角的彩色光斑。
> 26.3 另验证了编辑器导出的脚本射灯。当前用法见 [动态光与射灯](particle-commands.md#动态光与射灯)。
> 下文保留此前的诊断记录，其中的临时启动钩子已移除。

> 写于 2026-11-15。这份文档只记录**已经验证过的事实**，以及**尚未解决的问题**。
> 读者应在不重复踩坑的前提下继续排查。

仓库：`D:\Backup\IDEA Projects\ParticleDrawing`
分支：`feature/neoforge-1.21.1`
最近提交：`ac26a8d 诊断：光照渲染入口无条件留痕，确认运行路径`

---

## 1. 目标

给 ParticleDrawing 的动态光源系统加**射灯（spotlight）**能力，并补齐光源自由度，使各种发光效果可以组合：

- 锥角（内外锥、软硬边）、朝向、每灯半径、衰减曲线
- 颜色、强度
- 与粒子运动绑定（光源就是粒子）
- 多光源叠加与预算控制
- 26.2 / 26.3 / 1.21.1 三端一致
- 命令层与 `.pdrawc` 脚本均可配置
- 26.3 做画面验收

---

## 2. 已经完成并且**有自动测试保障**的部分

### 2.1 核心数学（`shared/core`，平台无关）

| 文件 | 内容 |
| --- | --- |
| `shared/core/src/main/kotlin/work/nekow/particledrawing/light/LightModel.kt` | 五种衰减曲线（CONSTANT / LINEAR / SMOOTH / INVERSE / INVERSE_SQUARE）、锥体权重（内外锥 + `edge` 锐度）、多灯合成（亮度取最大、颜色按贡献加权）、朝向换算 `directionOf(yaw,pitch)`、光斑半径估算；另有 `contributionOf(...)` 原始类型入口供热路径使用（不分配对象） |
| `shared/core/src/main/kotlin/work/nekow/particledrawing/light/LightShape.kt` | 挂在粒子外观上的光源形状：`range / outerDeg / innerDeg / edge / yawDeg / pitchDeg / falloff`，含 `validated()` 与内锥默认推导（外锥 60%） |
| `shared/core/src/main/kotlin/work/nekow/particledrawing/api/ParticleVisual.kt` | 增加 `var lightShape: LightShape?` 与链式方法 `spotlight(...)` / `lightDirection(...)` / `lightFalloff(...)`（共用 `withShape` 辅助，无重复代码） |

测试：`shared/core/src/test/kotlin/.../LightMathTest.kt` —— **20 条**，覆盖：所有曲线中心满亮/边界归零/单调不增、内外锥过渡单调、背面不漏光、锐度方向、多灯取最亮不相加、颜色加权、朝向单位化、光斑半径线性增长。

### 2.2 协议与渲染链路（`platforms/neoforge-common`）

| 位置 | 改动 |
| --- | --- |
| `core/network/ParticleVisualCodec.kt` | 新增 `FLAG_LIGHT_SHAPE`（`1 shl 12`），写入 7 个字段（range/outer/inner/edge/yaw/pitch/falloff ordinal），读取时 `LightShape(...).validated()` |
| `core/client/ResolvedVisual.kt` | 透传 `lightShape` |
| `core/client/ClientParticleEngine.kt` | `spawnParticle(...)` 增加 `lightShape` 参数并透传 |
| `core/client/RenderParticle.kt` | 保存 `lightShape`，提供 `fun shape(): LightShape?` |
| `lighting/DynamicLightManager.kt` | `LightSource` 增加 `range/dirX/dirY/dirZ/innerCos/outerCos/edge/falloff`；求值改用 `LightMath.contributionOf(...)`；搜索半径按本帧最大灯半径扩展（上限 `MAX_LIGHT_RANGE = 64.0`） |
| `command/ParticleCommandOptions.kt` | 新增 `spot=外锥[,内锥[,锐度]]`、`light_dir=偏航,俯仰`、`light_range=`、`light_falloff=`；`optionalFloats` 允许省略尾部参数 |
| `command/ParticleDrawParticleCommands.kt` | `VISUAL_OPTIONS` 加入上述 4 个键（**这一步曾漏掉，导致带 `spot` 的整条 `add` 被静默拒绝**） |
| `command/ParticleDrawCommands.kt` | 新增 `/pdraw light`（诊断）、`/pdraw light demo`（一条命令搭验收场景） |

测试：`platforms/neoforge-common/src/test/kotlin/.../LightShapeTest.kt` —— **12 条**，覆盖：射灯参数协议往返、缺省形状只写零标志、内锥推导、点光方向为零、非法参数拒绝、链式设置保留字段、参数解析（含省略写法）、未知衰减曲线报错。

当前测试总数：core 290、26.3 293、1.21.1 283，**全绿**。

### 2.3 混入（两个注入点，缺一不可）

| 文件 | 目标 | 作用 |
| --- | --- | --- |
| `platforms/neoforge-common/src/main/java/.../mixin/BrightnessGetterMixin.java` | `LightCoordsUtil.BrightnessGetter`（接口，`lambda$static$0`） | 实体 / 粒子的取光 |
| `platforms/neoforge-1.21.1/.../BrightnessGetterMixin.java` | `LevelRenderer.getLightColor(BlockAndTintGetter, BlockPos)` | 1.21.1 的地形取光 |
| `platforms/neoforge-26.3/src/main/java/.../mixin/TerrainLightMixin.java`（26.2 有一份副本） | `@Mixin(targets = "net.minecraft.client.renderer.block.BlockModelLighter$Cache")` 的 `getLightCoords(BlockState, BlockAndTintGetter, BlockPos)` | 26.x 的地形取光 |

注册在 `platforms/neoforge-26.{2,3}/src/main/resources/particledrawing.mixins.json` 的 `client` 列表。

### 2.4 光图写入（关键细节）

26.3 的打包布局已用 `javap` 确认：

```
pack(block, sky) = (block << 4) | (sky << 20)
block(packed)    = (packed >> 4) & 15
sky(packed)      = (packed >> 20) & 15
```

**不要自己拼位**——早期版本用 `(lightmap and -0x100000) or (dynamic*16)`，曾导致受影响区块整块变黑。现在改用原版工具：

```kotlin
// PlatformCompat.kt
fun lightAddEmission(packed: Int, amount: Float): Int =
    LightCoordsUtil.addSmoothBlockEmission(packed, amount)

// DynamicLightManager.getLightmapWithDynamicLight
val block = PlatformCompat.lightBlock(lightmap)
if (dynamic <= block) return lightmap
return PlatformCompat.lightAddEmission(lightmap, (dynamic - block).toFloat())
```

---

## 3. 已经验证成立的运行时事实

以下数据来自 `/pdraw light` 命令（它把数据同时写进服务端日志，行首 `[PD-light]`）：

```
[PD-light] 动态光: 启用=true 源=16 版本=1 | 脚下=0.40 下方=3.20 上方=0.00
  | 烘焙调用=4875342 覆盖=182806 读到光=991362 峰值=14.21@(5.5,-59.5,0.5)
    地形烘焙=12837254 最近烘焙距离=1.6 脏区块提交=51000
  | 首个光源 pos=(-6.0,-58.0,0.5) 强度=15.00 半径=30.0
```

逐项含义与结论：

| 指标 | 数值 | 结论 |
| --- | --- | --- |
| `源` | 16 | 光源被正确收集 |
| `脚下/下方` | 0.40 / 3.20 | 光照数学在查询点给出合理值 |
| `烘焙调用` | 487 万 | `BrightnessGetter` 注入生效（实体/粒子取光） |
| `地形烘焙` | 1283 万 | **地形取光注入生效**，且运行在 `Worker-Main-*` 线程 |
| `读到光` | 99 万 | 地形烘焙中有近百万次读到非零动态光 |
| `覆盖` | 18 万 | 光图确实被改写过 |
| `峰值` | 14.21 | 峰值接近满级 15 |
| `最近烘焙距离` | 1.6 | 灯下方 1.6 格处的顶点被烘焙到了 |

**结论：从光源采集 → 数学求值 → 地形烘焙 → 光图写入，整条链路已经被证明是通的。**

### 3.1 曾经踩过的三个坑（都已修）

1. **`BrightnessGetterMixin` 里有 `if (level.getBlockState(pos).isSolidRender()) return original;`**
   地形表面全是实体方块 → 动态光被完全挡在地面之外。已删除。
2. **`SHAPE_OPTIONS` 白名单不含光源键**
   `particle add <组> circle ... spot=40,20` 整条被拒、粒子凭空消失，但**命令不报错、后续命令照常执行**。已把 4 个键并入 `VISUAL_OPTIONS`。
3. **26.x 地形取光不在 `BlockModelLighter.getLightCoords`**
   那只是个转发器，真正逐顶点取光的是内部类 `BlockModelLighter$Cache.getLightCoords`。挂外层时调用次数为 0。

### 3.2 关于验收世界的两个事实

- 验收存档 `pdparity` 是**超平坦**，地面顶部在 **y = -64**。此前所有摆在 y≈100 的场景隔了 145 格，任何半径都够不到。
- 单人游戏默认在窗口失焦时**暂停**，玩家 tick / 计分板 / 数据包函数全部冻结。已在 `run/neoforge-26.3/client/options.txt` 写入 `pauseOnLostFocus:false`。

---

## 4. 当前卡点（需要外部帮助的地方）

### 4.1 现象

在 `DynamicLightManager.renderDynamicLights(...)` 的**第一条语句**放了一行无条件日志：

```kotlin
@JvmOverloads
fun renderDynamicLights(engine: ClientParticleEngine, partialTick: Float = 0f) {
    if (!entryLogged) {
        entryLogged = true
        org.apache.logging.log4j.LogManager.getLogger("ParticleDrawing").info(
            "[PD-entry] 光照渲染入口已执行 pd.demo={}", System.getProperty("pd.demo"),
        )
    }
    work.nekow.particledrawing.core.client.ClientAnimationProgramManager.maybeRunDemo()
    if (!isEnabled()) { clear(); applyPendingSections(); return }
    ...
}
```

调用链（`platforms/neoforge-common/src/main/kotlin/.../core/client/ParticleRenderHandler.kt`）：

```kotlin
fun onRenderFrame(event: RenderFrameEvent.Pre) {
    ...
    val engine = ClientParticleEngine.instance() ?: return
    engine.frameSyncCurves(partialTick)
    engine.frameSyncScaleEases(partialTick)
    DynamicLightManager.renderDynamicLights(engine, partialTick)   // ← 应打印 PD-entry
    sampleFrameTime(engine)                                        // ← 打印 [PD-perf] fps=...
}
```

**实测：`[PD-perf]` 每 5 秒稳定打印，`[PD-entry]` 一次都没有。**

同一函数内、同一 logger、前者在后者之前执行——在同一份代码里不可能同时成立。

### 4.2 已排除的假设

| 假设 | 验证方式 | 结果 |
| --- | --- | --- |
| `build/classes` 未更新 | 在 `.class` 文件里搜 `PD-entry` 字符串 | 命中 |
| 运行的是旧 jar | 解包 `build/libs/*.jar` 搜同一字符串 | 命中（15:16 构建的 1.0.24） |
| 更旧的 jar 被优先加载 | 删除 `particledrawing-26.3-neoforge-1.0.23-ALPHA.jar` 后重跑 | 仍然不打印 |
| 子工程 `pdraw-core` 里有旧副本 | 逐个 jar 检查是否含 `DynamicLightManager.class` | 只有主 jar 含，且是新的 |
| 游戏失焦暂停 | 写入 `pauseOnLostFocus:false`，运行中把窗口置前 | 仍然不打印 |
| 玩家 tick 路径没被调用 | 先把钩子放在 `ClientAnimationProgramManager.tick()` | 也不打印（但该路径曾驱动过形态动画，说明平时是活的） |

### 4.3 尚未验证的方向（建议按顺序）

1. **`runClient` 的真实 classpath**
   用 `gradlew -p platforms/neoforge-26.3 runClient --info` 抓实际命令行与 classpath，确认加载的是哪一份 `DynamicLightManager.class`。
   这是唯一能解释"同一函数内两行日志只有一行出现"的方向。

2. **是否存在第二个 `renderDynamicLights` 实现**
   例如另有一个同名类在别的包/别的 jar 里（搜索整个仓库与所有 jar）。

3. **`sampleFrameTime` 是否真在 `onRenderFrame` 内被调用**
   它可能被另一个事件（如 `RenderFrameEvent.Post` 或另一处 handler）调用，而 `onRenderFrame` 本身从未执行。
   验证方法：在 `sampleFrameTime` 里打印一次 `Thread.currentThread().name` 与调用栈（`Thread.currentThread().stackTrace.take(6)`）。

4. **属性传递**
   在**能打印的那行**（`[PD-perf]` 所在函数）里附带输出 `System.getProperty("pd.demo")`，一次即可确认 `-PpdDemo` → `-Dpd.demo=1` 是否生效。

---

## 5. 复现与调试用的固定套路

### 5.1 构建 / 测试 / 启动

```powershell
# 单元测试（三端）
.\gradlew -p platforms/neoforge-26.3 :test
.\gradlew -p platforms/neoforge-1.21.1 :test
.\gradlew -p shared/core test

# 重新打包（注意：runClient 走的是 jar，改完代码要重新 jar）
.\gradlew -p platforms/neoforge-26.3 jar

# 启动客户端并直入存档
.\gradlew -p platforms/neoforge-26.3 runClient -PpdQuickPlay=pdparity
```

可用属性（定义在 `gradle/neoforge-platform.gradle`）：

- `-PpdQuickPlay=<存档名>` → `--quickPlaySingleplayer <名>`
- `-PpdConnect=<地址>` → `--quickPlayMultiplayer <地址>`
- `-PpdDemo` → `-Dpd.demo=1`（**效果尚未验证**）

### 5.2 验收场景（数据包）

存档：`run/neoforge-26.3/client/saves/pdparity`
数据包：`datapacks/pdraw_test`

- `data/minecraft/tags/function/load.json` → `pdraw_test:load`
- `data/minecraft/tags/function/tick.json` → `pdraw_test:tick`

`load.mcfunction`（当前）：

```mcfunction
difficulty peaceful
gamemode spectator @a
time set midnight
weather clear
scoreboard objectives add pdrawT dummy
scoreboard players set #t pdrawT 0
```

`tick.mcfunction`（当前）：

```mcfunction
scoreboard players add #t pdrawT 1
execute if score #t pdrawT matches 100 run pdraw light demo
```

**注意**：数据包函数依赖游戏 tick，因此必须保证 `pauseOnLostFocus:false`，否则窗口一失焦就整体停摆。

### 5.3 演示命令

`/pdraw light demo` 会：

1. `gamemode spectator`、`time set midnight`、`particle group clear`
2. 在 `y=-61` 处建三组灯，锥角分别 20° / 45° / 65°，`light=15 light_range=26`，`light_dir=0,-90`
3. 把玩家 tp 到 `(0, -58.5, 9)` 俯视 `(0, -63, 0)`

实现方式是**转发已有命令**（`commands.performPrefixedCommand`），不另写建灯逻辑。

### 5.4 诊断命令

`/pdraw light` 打印：

```
动态光: 启用=… 源=… 版本=… | 脚下=… 下方=… 上方=…
        | 烘焙调用=… 覆盖=… 读到光=… 峰值=…@坐标
          地形烘焙=… 最近烘焙距离=… 脏区块提交=…
        | 首个光源 pos=… 强度=… 半径=…
```

同一行以 `[PD-light]` 前缀写入服务端日志。

---

## 6. 关键文件清单

```
shared/core/src/main/kotlin/work/nekow/particledrawing/light/LightModel.kt
shared/core/src/main/kotlin/work/nekow/particledrawing/light/LightShape.kt
shared/core/src/main/kotlin/work/nekow/particledrawing/api/ParticleVisual.kt
shared/core/src/test/kotlin/work/nekow/particledrawing/LightMathTest.kt

platforms/neoforge-common/src/main/kotlin/work/nekow/particledrawing/lighting/DynamicLightManager.kt
platforms/neoforge-common/src/main/kotlin/work/nekow/particledrawing/core/client/ResolvedVisual.kt
platforms/neoforge-common/src/main/kotlin/work/nekow/particledrawing/core/client/RenderParticle.kt
platforms/neoforge-common/src/main/kotlin/work/nekow/particledrawing/core/client/ClientParticleEngine.kt
platforms/neoforge-common/src/main/kotlin/work/nekow/particledrawing/core/client/ParticleRenderHandler.kt
platforms/neoforge-common/src/main/kotlin/work/nekow/particledrawing/core/network/ParticleVisualCodec.kt
platforms/neoforge-common/src/main/kotlin/work/nekow/particledrawing/command/ParticleCommandOptions.kt
platforms/neoforge-common/src/main/kotlin/work/nekow/particledrawing/command/ParticleDrawParticleCommands.kt
platforms/neoforge-common/src/main/kotlin/work/nekow/particledrawing/command/ParticleDrawCommands.kt
platforms/neoforge-common/src/main/kotlin/work/nekow/particledrawing/platform/PlatformCompat.kt

platforms/neoforge-common/src/main/java/work/nekow/particledrawing/mixin/BrightnessGetterMixin.java
platforms/neoforge-1.21.1/src/main/java/work/nekow/particledrawing/mixin/BrightnessGetterMixin.java
platforms/neoforge-26.3/src/main/java/work/nekow/particledrawing/mixin/TerrainLightMixin.java
platforms/neoforge-26.2/src/main/java/work/nekow/particledrawing/mixin/TerrainLightMixin.java

platforms/neoforge-26.3/src/test/kotlin/work/nekow/particledrawing/LightShapeTest.kt
platforms/neoforge-common/src/test/kotlin/work/nekow/particledrawing/LightIntensityTest.kt（既有）
```

---

## 7. 下一步的目标（与卡点无关，可并行）

卡点解决后仍需完成：

1. 拿到可交付的验收图（近景、够亮、三锥角对比）
2. 光源**颜色**进入渲染（目前只有灰度写入光图，颜色仅存在于 `LightEmission`/`LightSample`，供第三方或着色器使用）
3. 闪烁 / 脉动（可用现有 `light_curve` 曲线，但缺少射灯专用的时间调制）
4. 体积散射感（光锥在空气中的可见性）
5. `.pdrawc` 脚本可配置光源形状
6. 26.2 / 1.21.1 的地形取光注入需要各自验证一次（1.21.1 挂的是 `LevelRenderer.getLightColor`，尚未实测）
7. 文档：`doc/particle-commands.md` 补光源自由度表
