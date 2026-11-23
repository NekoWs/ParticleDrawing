# 命令函数示例

## 安装到测试存档

将本目录的 `.mcfunction` 文件复制到当前存档的数据包，目录结构如下。

```text
<存档>/datapacks/pdraw-test/
├─ pack.mcmeta
└─ data/pdraw_test/function/
   ├─ sine.mcfunction
   ├─ clear.mcfunction
   └─ ...
```

在 `pdraw-test` 根目录创建 `pack.mcmeta`。下方的格式范围覆盖 26.2 和 26.3。 [26.2 数据包格式](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-2) · [26.3 数据包格式](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3)

```json
{
  "pack": {
    "description": "ParticleDrawing command examples",
    "min_format": [107, 1],
    "max_format": [121, 0]
  }
}
```

开发客户端的存档位于 `run/<目标>/client/saves/`。进入存档后执行 `/reload`，用
`/datapack list enabled` 确认数据包已启用，再选择一个函数执行。若提示未知函数，检查 `function/`
是否为单数、数据包目录是否多套了一层，以及日志中是否有命令解析错误。

```mcfunction
function pdraw_test:sine
function pdraw_test:image
function pdraw_test:conditional-shell
function pdraw_test:heart
function pdraw_test:rotating-sphere
function pdraw_test:tentacles
function pdraw_test:mandala
function pdraw_test:torus-knot
function pdraw_test:mobius
function pdraw_test:cube
function pdraw_test:seven-star
function pdraw_test:sharp-star
function pdraw_test:five-star
function pdraw_test:spotlight
function pdraw_test:collision-bounce
function pdraw_test:subemit-burst
function pdraw_test:rain-contact
function pdraw_test:hell-eye
function pdraw_test:hell-eye-end
function pdraw_test:hell-eye-clear
function pdraw_test:hell-eye-batch
function pdraw_test:hell-eye-batch-end
function pdraw_test:hell-eye-batch-clear
```

运行 `mesh-surface` 前，把 [mesh-surface.obj](mesh-surface.obj) 放入服务端工作目录的 `particleMeshes/`，再执行 `function pdraw_test:mesh-surface`。

在聊天栏执行时加前缀 `/`。锚点相对函数执行位置，命令方块执行时可以用
`execute positioned ~ ~2 ~ run function ...` 调整整幅图形的位置。
执行 `function pdraw_test:clear` 可以清除所有示例粒子并重置对应配置。

## ColorBlock《地狱之眼》完整效果

[hell-eye.mcfunction](hell-eye.mcfunction) 用一条命令复刻 ColorBlock 教程里的地狱之眼：
开场从中心绽放、持续 10 秒左右、第 12 秒自动向内坍缩收场。共 12 个配置（其中一个只存锚点）、
约 1.2 万颗常驻粒子。执行位置上方 4 格是图形中心，图形画在 XY 平面；执行后往后退十几格正面看最清楚。

```mcfunction
# 站在想看的位置后执行；12 秒后自动收场
function pdraw_test:hell-eye

# 提前收场、清除（重复执行都安全）
function pdraw_test:hell-eye-end
function pdraw_test:hell-eye-clear
```

| 阶段 | 时间轴 | 行为 |
| --- | --- | --- |
| 开场 | 第 0–10 刻 | 四组图元从中心 1% 大小指数长到原尺寸，10 刻寿命，到点交棒 |
| 持续 | 第 10–240 刻 | 外层四角星和圆环每刻重画、粒子随机外扩消散；内侧星常驻慢摆；漩涡火星在体内旋转内收 |
| 收场 | 第 240 刻 | 立刻抹掉持续状态，三层图元从原尺寸每刻缩小一半，5 刻后消失 |

时间线直接写在配置里：持续状态用发射窗口 `emit_from 10` 等开场结束，收场时间用
`schedule function pdraw_test:hell-eye-end 12s replace` 触发。改 `emit_from` 的刻数和
`/schedule` 的时长就能改节奏。想让它跟着实体走，把 `hell_eye_root` 的 `anchor fixed` 换成
`anchor entity @p 0 4 0 false`，其余配置通过 `anchor emitter` 共享锚点，再给每个配置补 `follow true`。

与原教程的差异：

- ColorBlock 用 12 个命令方块（脉冲、循环、连锁和红石延时）串时间线，这里用发射窗口和
  `/schedule`，一个函数文件就能播完；没有命令方块方向、朝向和红石接线的要求。
- ColorBlock 的 `normal` 模式是正态分布：示例里用 Box-Muller 公式
  （`sqrt(-2*ln(1-random()))*cos(2*PI*random())`）在出生公式里生成高斯偏移，语义等价且能直接读懂。
- ColorBlock 的亮度参数是光图覆盖（0 等于全黑），示例用 `appearance glowing true` 走全亮绘制；
  重叠处再靠 `appearance additive true` 自然叠亮，不开光影也能读出接近教程的层次。
- ColorBlock 的 `clearparticle` 会清掉客户端所有粒子；示例收场只清自己这几组，不干扰别的特效。

## 批量命令版：一行一个配置

[hell-eye-batch.mcfunction](hell-eye-batch.mcfunction) 用 `/pdraw particle batch` 把同一份地狱之眼写成
**12 行**：每个配置一行，采样、出生/运动公式、发射窗口、外观和启动方式都写在同一条命令里。
逐条配置的版本是 [hell-eye.mcfunction](hell-eye.mcfunction)（约 170 行），两者效果相同，可以对照着看。

```mcfunction
# 一次写好外层持续状态并开始发射
pdraw particle batch hell_eye_hold_outer kind=emitter anchor=emitter anchor.emitter=hell_eye_root sample.range=-10,10,0.02 birth.x="12*sin(u)^3" birth.y="4*cos(u)^3" let.drift=0.1*random() motion.vx=x*drift motion.vy=y*drift motion.vz=z*drift emitter.lifetime=8 rate=1024 max_alive=8192 emitter.emit_from=10 appearance.texture=particle:minecraft:end_rod appearance.color=#ff4dff appearance.size_world=0.12,0.12 appearance.glowing=true appearance.additive=true

# 一次性粒子与组时间线也是同一个命令
pdraw particle batch spark kind=sphere center=~ ~1 ~ radius=2 count=200 appearance.color=#ff4040 particle.lifetime=30
pdraw particle batch rune kind=program program.1=delay,20 program.2=fade_in,30,ease_out program.3=move,0,3,0,40,easeInOut
```

- 键名按分区注册，`/pdraw particle batch help` 列出全部键与值语法；
- 公式用双引号，位置值可以带空格（`anchor.pos=~ ~4 ~`），不用再包一层引号；
- `dryrun=true` 只校验并回显结果；任一项不合法时旧配置不变，`keep=true` 表示在原配置上改；
- Tab 补全按已写的 `kind` 过滤键名，枚举键补全取值，`program.<序号>=` 补全动作名。

配套函数：[hell-eye-batch-end.mcfunction](hell-eye-batch-end.mcfunction) 提前收场，
[hell-eye-batch-clear.mcfunction](hell-eye-batch-clear.mcfunction) 删除配置并取消排程。
批量命令的完整键表和实现说明见 [doc/batch-command.md](../../doc/batch-command.md)，常用写法见
[命令文档的批量命令一节](../../doc/particle-commands.md#批量命令-batch)。

## 几何动态示例

| 函数 | 效果 | 配置名 |
| --- | --- | --- |
| mandala | 青色十二瓣玫瑰、金色双三角星、蓝色三重外环，分层反向旋转 | mandala、mandala-star、mandala-ring |
| torus-knot | 倾斜旋转的三叶环面结，沿曲线流动的粉蓝渐变 | knot |
| halo-aura | 脚底光环，加上从环里升起、循环爬升的竖条柱 | halo、aura |
| morph-formation | 常驻粒子群在圆环与螺旋塔之间无限往返变形 | morphdemo |
| spotlight | 三盏锥角与衰减各异的射灯照在脚前地面，可改成横向扫动的光斑 | lamps |
| mobius | 绕中心旋转的莫比乌斯扭带，点阵呈现单面曲面的扭转 | mobius |

三组均存在 240 刻（12 秒），最后 30 刻淡出，一次触发即可。mandala 共 2040 颗，另外两组分别
960、1008 颗。站在图形侧上方更容易看出曼陀罗分层与曲面的立体关系。

改坐标只需修改函数里的 anchor fixed。曼陀罗的另外两层引用 mandala 锚点，运行后也可以直接绑定实体：

```mcfunction
pdraw particle emitter mandala anchor entity @p 0 2 0 false
pdraw particle emitter knot variable speed 0.0008
pdraw particle emitter mobius variable tilt 1.1
```

speed 使用弧度/毫秒，tilt 使用弧度；这些示例的公式每刻读取变量，更新时不会重新生成整组。
重播曼陀罗时需依次 burst 三个配置；重新运行整个函数则会清除旧效果并从头播放。

## ColorBlock 等价示例

| 函数 | 效果 | 配置名 | 画完用时 |
| --- | --- | --- | --- |
| cube | 青色立方体十二条棱，粒子缓慢上浮 | cube | 2 刻 |
| seven-star | 七芒星曲线，每刻画 25 颗 | seven-star | 41 刻 |
| sharp-star | 锐角星形，参数拆成 p/q，速度用矩阵旋转 | sharp-star | 7 刻 |
| five-star | 五角星曲线，步长 2 | five-star | 1 刻 |

这些函数用 `sample` 选参数、`let` 拆中间量、`birth position` / `motion velocity` 一次写三个分量。
`where` 里的 `sx/sy/sz` 是采样原始坐标，和出生后的 `x/y/z` 分开，筛选不受出生位移影响。
参数含义见[分步配置粒子生成器](../../doc/particle-commands.md)。

## 大规模场景示例

| 函数 | 效果 | 配置名 | 粒子数 |
| --- | --- | --- | --- |
| blackhole | 三层吸积壳旋进黑洞，落进视界即被吞没后自动补位 | hole_a/b/c | 约 2 万 |
| levelup | 脚下环向上划过，拖着由少到多再到少的残影 | levelup_a/b/c | 约 6100 |
| light-only | 挂在实体上的常驻灯：粒子不可见，只往光图注入照明 | waist_light | 1 |
| subemit-burst | 陨石落地时在原地炸出一批小火花 | meteor | 约 600 |
| collision-bounce | 带初速的粒子落到地面后按 bounce/friction 反弹减速 | sparks | 约 400 |

这两个示例专门用来压性能：黑洞带向心加速度、逐刻吞没判定和动态光，升级动画每刻重算位置、
透明度与尺寸。两者都走配方的热路径（标量公式 + `vec3(...)` 输出），两万颗在 7800 XT 上
稳定 90–105 帧。想再压一点就减 `sample count` 或 `max_alive`；想更密就提到 6667（单发射器上限 8192）。

升级动画的三个发射器各占 1/3 圈，`u` 被放大 512 倍后：整数部分决定角度、小数部分决定这颗在
拖尾里的滞后量，所以每个角度上有一列粒子。改 `sin(ph*3.1416)*2.6` 里的 2.6 就是改残影最长能拖多远，
改 `rate 1024` 可让整组在更少刻内出生（两刻内出生完，动画才齐）。

## 粒子外观示例

| 函数 | 效果 | 配置名 |
| --- | --- | --- |
| soft-fade | 在平整地板上对照同高度下的两组粒子，右组靠近表面时淡出 | soft_reference、soft_demo |
| ribbon-trail | 两颗绕锚点运动的粒子，各自保留 32 刻的渐隐丝带 | ribbon_demo |

## 图片示例

image 示例需要把项目的 `platforms/neoforge-common/src/main/resources/icon.png` 复制为游戏工作目录的
`particleImages/pd-icon.png`。680×680 图标、stride=8 共 7225 点，max_alive=8192 可容纳完整图片，
rate=1024 在 8 刻内画完。替换图片后按有效像素数调整 stride、spacing、容量与寿命；
采样超过 8192 点会报错。

这些函数都是普通配置，使用同一套采样与公式命令。可以复制、更名、修改公式，或只调用末尾的 burst
重复播放。参数、坐标系与实体跟随用法见[分步配置粒子生成器](../../doc/particle-commands.md)。

