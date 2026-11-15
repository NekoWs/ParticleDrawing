# 动画播放与构建

## 播放动画文件

从[编辑器](https://viewer.nekow.work/)导出已签名的 `.pdrawc`，放到游戏或服务端工作目录的 `animations/`。
开发运行目录见[多版本构建](multiversion.md#构建与运行)。播放时会校验签名。

| 命令 | 用途 |
| --- | --- |
| `/pdraw animation list` | 列出动画名称 |
| `/pdraw animation play <名称> [位置]` | 播放动画。命令方块需给出位置 |
| `/pdraw animation var <名称> <值>` | 更新当前维度播放中动画的同名函数对象变量 |
| `/pdraw animation stop` | 停止当前维度的动画 |
| `/pdraw animation camera <名称>` / `/pdraw animation camera stop` | 预览动画摄像机或退出预览，见[版本限制](known-issues.md#minecraft-1211) |
| `/pdraw animation debug` | 查看播放状态 |

### Kotlin 与 Java 入口

服务端用 `Effects.register(key, bytes)` 注册签名文件，再按锚点播放。
`key` 使用宿主的资源标识，`origin` 是原生 `Vec3`。

```kotlin
val bytes = AnimationLoader.load("magic_circle") ?: return
if (!Effects.register(key, bytes)) return

val effect = Effects.play(key, level, Anchor.Fixed(origin), EffectOptions().authority(Authority.SERVER))
effect.setParam("rad", 4.0).setSpeed(0.75)
```

`seek`、`pause`、`resume` 与 `setSpeed` 要求 `Authority.SERVER`。
`setParam` 覆盖编辑器中声明的变量，`onFinished` 可监听自然结束或主动停止。
按文件名直接播放可用 `ServerAnimationManager.playByName`，纯客户端播放使用 `ClientEffects`。
完整类型见 [API 参考](api-reference.md)。

### 入场编排

`.pdrawc` 时间轴使用毫秒。粒子组的 `delay`、`stagger`、`lifetime` 和 `destroyAfter` 使用 game tick。

粒子和函数对象在 `st` 指定的时刻生成，循环时按各自起点重新入场。静态粒子的 `life` 缺省为 -1，表示
无限寿命；有限寿命在 `st + life` 到期，`timelineLength()` 会计入入场和有限寿命。

函数对象可以在 `process` 中给派生粒子设置 `p.life`（毫秒）。可见期同时受对象的 `st`、`duration`
和粒子寿命约束。`duration ≤ 0` 表示对象无时间上限。

客户端在游戏刻求值，渲染帧更新插值与脚本 `process`。函数对象派生粒子在出生帧继承 UV，
UV 公式随播放时钟更新。

### 音频

播放端使用 WAV 或 OGG 文件自身的采样率，由 OpenAL 适配输出设备。设备率不同且支持带限 sinc
重采样时，会为音频源选择该算法。[立体声声像差异](known-issues.md#音频声像)

## 代码构建动画

除了用 `Draw` 实时画形状或播放编辑器导出的文件，还可以用代码直接构建一个动画再下发播放。动画模型与
`.pdrawc` 播放同构：粒子、轨道、组、函数对象、摄像机、贴图都可声明，播放走同一套服务端权威进度与客户端
渲染链路。

### Kotlin DSL

```kotlin
import work.nekow.particledrawing.api.*
import work.nekow.particledrawing.animation.TrackPr
import work.nekow.particledrawing.core.easing.EasingType
import net.minecraft.world.phys.Vec3

val anim = Animation.create {
    loop = true

    particle {
        id = "p0"
        pos = Vec3(0.0, 10.0, 0.0)
        color = Color.CYAN
        scale = 1f
        life = -1
    }

    track {
        pr = TrackPr.POS_X
        ids = listOf("p0")
        keyframe(0, 0.0, EasingType.LINEAR)
        keyframe(1000, 5.0, EasingType.EASE_OUT)   // 关键帧时刻为毫秒
    }

    function {
        id = "fx0"
        center = Vec3(0.0, 10.0, 0.0)
        duration = 10000                           // 时长毫秒，0 表示无上限
        seed = 1
        variable("rad", 3.0)
        source = """
            func setup() {
              repeat(100) { this.spawn() }
            }
            func process() {
              for (const p of this.particles) {
                let th = p.index / this.particles.size() * 2 * PI
                p.position = vec(cos(th) * rad, 0, sin(th) * rad)
              }
            }
        """.trimIndent()
    }
}

anim.play(level.players(), origin)
    .updateVariable("rad", "4")
    .isActive()
```

### Java Builder

```java
Animation anim = Animation.builder()
    .loop(true)
    .particle(p -> p.id("p0").pos(0, 10, 0).color(Color.CYAN).scale(1f).life(-1))
    .track(t -> t.pr(TrackPr.POS_X).ids("p0")
        .keyframe(0, 0.0, EasingType.LINEAR)
        .keyframe(1000, 5.0, EasingType.EASE_OUT))
    .function(f -> f.id("fx0").center(0, 10, 0).duration(10000).seed(1)
        .variable("rad", 3.0)
        .source("""
            func setup() {
              repeat(100) { this.spawn() }
            }
            func process() {
              for (const p of this.particles) {
                let th = p.index / this.particles.size() * 2 * PI;
                p.position = vec(cos(th) * rad, 0, sin(th) * rad);
              }
            }
            """))
    .build();

anim.play(level.players(), origin).updateVariable("rad", "4");
```

### TrackPr

轨道的 `pr` 是类型安全的 `TrackPr` 枚举，与 `.pdrawc` 二进制里的分量序号一一对应。

| 属性 | 枚举常量 |
| --- | --- |
| 位置 | `POS_X` `POS_Y` `POS_Z` |
| 速度 | `VEL_X` `VEL_Y` `VEL_Z` |
| 颜色 | `COL_R` `COL_G` `COL_B` `COL_A` |
| 缩放 | `SCL_X` `SCL_Y` `SCL_Z` |
| 公转 | `ROT_X` `ROT_Y` `ROT_Z` |
| 自转 | `SPIN_X` `SPIN_Y` `SPIN_Z` |
| 公转中心 | `CENTER_X` `CENTER_Y` `CENTER_Z` |
| 摄像机 | `FOV` `TARGET_X` `TARGET_Y` `TARGET_Z` |
| 音频（属主 `a:<资产id>`） | `VOL` `SPEED` `PAN` `FADE_IN` `FADE_OUT` |

`TrackPr.FOV` 是标量分量，没有 `.x` / `.y` / `.z`。

### 链式操作

| 方法 | 说明 |
| --- | --- |
| `play(level, players, origin)` | 向指定玩家下发并开始播放，返回自身 |
| `play(players, origin)` | 从首个玩家推导 `ServerLevel` 的便捷重载 |
| `stop()` | 停止本次播放 |
| `updateVariable(name, value)` | 运行期更新函数对象变量，下一 tick 生效 |
| `isActive()` | 本次播放是否仍在进行 |

代码生成的动画走独立的结构化网络载荷，不验签、不生成文件。播放记录同样支持维度切换、重生与重连后的
自动重发，与其他玩家帧号保持一致。
