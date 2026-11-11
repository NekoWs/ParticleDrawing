# 分步配置粒子生成器

`/pdraw particle emitter` 把采样、出生属性、运动与筛选分开配置。每个名称是一份可复用的配置，
不代表固定形状。曲线、曲面、图片点阵都使用同一套公式与生命周期。
需要原版等级 2 命令权限；可在聊天、命令方块和数据包函数里使用。
生成器名称含冒号时使用双引号，例如 `emitter "spell:fire" burst`。
贴图引用、图片路径和 bounds 保留完整文本，支持冒号、斜杠和逗号；含空格的图片路径可用双引号包住。

## 第一个例子：会运动的 sin 曲线

以下内容可直接保存为 `.mcfunction`。命令方块里每行放一块：第一块脉冲、需要红石，后续连锁、
无条件、始终活动；箭头依次相连。只触发一次，不要用循环方块反复执行配置。
数据包目录、版本元数据和完整函数见[安装说明](../examples/commands/README.md#安装到测试存档)。

```mcfunction
pdraw particle emitter reset sine
pdraw particle emitter sine anchor fixed ~ ~2 ~
pdraw particle emitter sine sample range -4 4 0.1
pdraw particle emitter sine birth x u
pdraw particle emitter sine birth y sin(u)
pdraw particle emitter sine motion y sin(u-age*0.1)
pdraw particle emitter sine texture particle:minecraft:end_rod
pdraw particle emitter sine appearance size_world 0.12,0.12
pdraw particle emitter sine appearance glowing true
pdraw particle emitter sine appearance light 0
pdraw particle emitter sine lifetime 80
pdraw particle emitter sine rate 81
pdraw particle emitter sine burst
```

原点只在 `anchor fixed` 那一行设置，公式输出都是相对这个原点的坐标。命令方块各自的位置不会
进入后续公式。`~` 仍由原版解析，所以这里的锚点相对执行 `anchor fixed` 的那一块命令方块。
在函数里可以用 `execute positioned ... run function ...` 统一执行位置。

`burst` 遍历一次全部样本，仍受 `rate` 和 `interval` 控制。这里有 81 个样本、每刻最多生成 81 颗，
因此一刻画完。每颗存在 80 刻（4 秒），期间按自己的年龄计算波形。
末地烛的资源贴图按每颗粒子的年龄与寿命选帧；它仍是 PD 粒子，运动由这里的公式定义。

配置完成后可以只重复执行最后的 `burst`，多次触发的实例可以同时存在。持续生成则用 `start`。

## 图片转粒子

把静态 PNG 放进游戏或服务端工作目录的 `particleImages/`，例如 `particleImages/ring.png`。
开发运行使用 `run/<目标>/<运行名>/particleImages/`，例如 26.3 单人的 `run/neoforge-26.3/client/particleImages/`。
服务端读取图片后同步采样结果，联机玩家直接接收坐标与颜色。

```mcfunction
pdraw particle emitter reset sigil
pdraw particle emitter sigil anchor fixed ~ ~2 ~
pdraw particle emitter sigil image file ring.png
pdraw particle emitter sigil image stride 2
pdraw particle emitter sigil image spacing 0.04
pdraw particle emitter sigil image plane xz
pdraw particle emitter sigil texture particle:minecraft:end_rod
pdraw particle emitter sigil appearance size_world 0.06,0.06
pdraw particle emitter sigil appearance glowing true
pdraw particle emitter sigil appearance light 0
pdraw particle emitter sigil lifetime 100
pdraw particle emitter sigil max_alive 8192
pdraw particle emitter sigil rate 1024
pdraw particle emitter sigil motion vy 0.015
pdraw particle emitter sigil motion a 1-progress
pdraw particle emitter sigil burst
```

每个有效像素是一颗独立粒子，保留 RGB 和透明度；图片几何中心位于锚点，默认 XY 平面的图片顶部对应正 Y。
`plane xz` 将图像放在水平面。`spacing` 是相邻原始像素的世界间距；`stride` 只减少采样数量，不缩小图片。
透明度不大于 `image alpha` 的像素跳过，默认阈值为 0。支持 ImageIO 能识别的单帧图片；多帧图明确拒绝。

图片要完整出现，`max_alive` 必须容得下全部有效样本，寿命也要长于画完一轮所需时间。
默认容量 4096 适合持续发射，但 680×680 的不透明图片以 stride=8 采样时共有 7225 点，会在第 4096 点
等待空位，看起来像只画了一半。上面的配置允许 8192 颗同时存在，每刻生成 1024 颗，
7225 点在 8 刻内画完；每颗保留 100 刻。`start/burst` 的反馈会显示实际样本数和容量。
未筛选时，一轮至少需要 `ceil(样本数/rate)` 个发射批次，批次间隔由 interval 决定；
where 筛选可能减少存活量，kill 和运动也会改变最终显示。

数据包可以携带图片：把文件放在 `data/demo/images/ring.png`，改用：

```mcfunction
pdraw particle emitter sigil image resource demo:images/ring.png
```

每次启动时读取当前图片；数据包资源修改后先 `/reload`。文件路径必须留在 `particleImages/` 内，
不支持网络 URL。图片最多 8 MiB、边长 4096、总像素 1048576；有效样本最多 8192，超限会报错，
请显式调整 `stride`，不会自动丢点或改变密度。

## 参数曲线与动态曲面示例

[ColorBlock 作者网站的案例](https://mc.ecylt.top/#resource)使用参数方程、极坐标转换、空间条件筛选和
逐粒子运动。它们可以用相同的底层步骤表达，不需要给每一种效果增加一个命令子项：

| 案例类型 | PD 的组合方式 |
| --- | --- |
| 正方体棱线 | grid 采样，where 保留至少两个分量位于边界的点 |
| 球、围圈、竖直圆、菊型球 | range/count 采样，let 计算角度和半径，birth 写入转换后的 xyz |
| 锐角星型、桃心、七芒星、V 字 | 参数曲线分别写入 birth x/y/z；多条 let 拆解计算 |
| 旋转球、缠绕、触手 | motion 用初始位置或参数计算旋转、弯曲，并更新颜色 |
| 向外散开的圆、上升粒子 | birth 定义形状，motion 定义速度或加速度，lifetime 限制拖尾长度 |
| 逐步画出的曲线与循环 | rate/interval 控制绘制速度；burst 画一轮，start 持续发射 |

提供以下可修改的函数，均使用现有命令，不注册固定效果：

| 文件 | 行为 |
| --- | --- |
| [heart.mcfunction](../examples/commands/heart.mcfunction) | 逐步画出桃心，按发射器时间脉动，寿命末尾淡出 |
| [rotating-sphere.mcfunction](../examples/commands/rotating-sphere.mcfunction) | 768 点球面，旋转并逐颗变色；speed 可热更新 |
| [tentacles.mcfunction](../examples/commands/tentacles.mcfunction) | 九条动态曲臂；let 拆出分臂、沿臂参数、角度与半径 |
| [mandala.mcfunction](../examples/commands/mandala.mcfunction) | 十二瓣玫瑰、双三角星和三重外环，共享锚点并分层反向旋转 |
| [torus-knot.mcfunction](../examples/commands/torus-knot.mcfunction) | 三叶环面结，立体旋转、倾斜及沿曲线流动的渐变 |
| [mobius.mcfunction](../examples/commands/mobius.mcfunction) | 双参数点阵组成莫比乌斯带，一圈后横向次序反转 |

这些示例展示对应的几何与运动，不复现网站截图的光影、镜头和全部参数。触手示例采用有界波形，
不照搬案例里接近正切极点的超远坐标。球面是表面点阵，体积填充可另用空间网格与球内条件。
ColorBlock 的参数 t 在 PD 中对应采样参数 u；PD 的 t 专指发射器毫秒，不能直接整段复制原公式。

例如运行球面函数后，单独改变转速，或者把整个球跟随玩家：

```mcfunction
pdraw particle emitter sphere variable speed 0.08
pdraw particle emitter sphere anchor entity @p 0 3 0 false
```

这些动态示例都设置 follow=true，已有成员会一起移动。曼陀罗的三个配置共用主层锚点，
只需更新 mandala 的 anchor。几何示例的粒子数、变量和重播方式见[函数说明](../examples/commands/README.md)。
要持续播放，可把函数末尾的 burst 改为 start；
start 会不断生成新一轮，存活数量仍受 max_alive 约束。需要长期只保留同一批成员时，使用 lifetime=-1
配合 burst，并按需删除淡出公式；用 remove 清除。

## 采样和公式

| 命令（前缀 `pdraw particle emitter <名称>`） | 含义 |
| --- | --- |
| `sample count <数量>` | 生成 N 个同原点样本，`u` 从 0 到 1，包含两端；单点为 0 |
| `sample range <begin> <end> <step>` | 按指定步长采样参数 u，包含不超出 end 的最后一个点 |
| `sample grid` | 切换为空间网格采样 |
| `sample bounds <x,y,z> <x,y,z>` | 网格最小和最大相对坐标 |
| `sample step <间距>` | 网格三个方向的采样间距 |
| `image file <路径>` / `image resource <ID>` | 切换为文件或数据包图片采样 |
| `image stride <整数>` | 每多少个像素采样一次，1..256 |
| `image spacing <格数>` | 原始像素的世界间距 |
| `image alpha <阈值>` | 透明像素筛选阈值，0..1 |
| `image plane xy\|xz\|yz` | 图片所在平面 |
| `variable <名称> <数值>` | 可热更新的数值输入，适合多条命令控制同一效果 |
| `state <名称> <初值>` | 每颗粒子独立保存的状态 |
| `let <名称> <公式>` | 中间计算量，按声明顺序求值 |
| `birth <属性> <公式>` | 只在出生时计算的属性 |
| `motion <属性> <公式>` | 每刻读取当前状态并计算的属性 |
| `where <条件>` | 出生公式之后，只生成满足条件的样本 |
| `kill <条件>` | 每刻及出生时判定，成立即真正销毁 |
| `unset variable\|state\|let\|birth\|motion\|change <字段>` | 删除一项配置 |

每条命令只写一个公式，不加赋值符号或末尾分号，例如 `birth x cos(u)`。
公式可以包含空格，不用再套引号。变量名区分大小写。

| 公式输入 | 含义 |
| --- | --- |
| `u` | 当前采样参数；图片中为像素列的归一化位置 |
| `i`、`n` | 本实例从 0 开始的总出生尝试序号、单轮样本数量；循环发射时 i 持续递增 |
| `t` | 发射器运行毫秒；单人暂停时不增长 |
| `age`、`life`、`progress` | 当前粒子年龄（刻）、出生时寿命（刻）、年龄/寿命；无限寿命的 progress 为 0 |
| `dt` | 一次模拟的时间，固定为 1 刻 |
| `x,y,z` | 当前锚点坐标系内的位置 |
| `dx,dy,dz` | 出生公式算完后的初始位置；适合保留曲线、图片或网格布局做变换；birth 阶段读取它们时为原始样本坐标 |
| `vx,vy,vz`、`ax,ay,az` | 当前速度（格/刻）和加速度（格/刻²） |
| `r,g,b,a`、`sc`、`glow`、`light` | 当前颜色、尺寸倍率、全亮、向外照明等级 |

可写属性是 `x/y/z/vx/vy/vz/ax/ay/az/r/g/b/a/sc/glow/light` 和用 `state` 声明的字段。
颜色必须在 0..1，尺寸不得为负，照明等级在 0..15。`glow` 可用布尔条件。
同一阶段的输出使用同一输入快照，例如 `motion x y` 与 `motion y x` 能交换两个分量。
需要多步计算时使用多条 `let`。不能通过一条属性公式改变其它属性。

每刻先检查寿命与 kill，再计算 motion，随后速度加上加速度、位置加上速度。
显式输出了某个位置分量时，该分量直接采用公式结果，不再叠加对应速度。
新生粒子保留完整的 age=0 首帧，下一刻才运行 motion。

数学沿用脚本表达式：三角函数、幂、比较、`&&/||/!`、条件表达式、噪声、向量和矩阵等。
角度以弧度计。例如分条旋转一张 XZ 图片：

```mcfunction
pdraw particle emitter sigil let angle t*0.002
pdraw particle emitter sigil motion x dx*cos(angle)-dz*sin(angle)
pdraw particle emitter sigil motion z dx*sin(angle)+dz*cos(angle)
```

矩阵按行向量构造，例如 `mat3(vec3(0,1,0),vec3(1,0,0),vec3(0,0,1))*vec3(x,y,z)`；
用 `.x/.y/.z` 取分量。公式不执行赋值、循环、生成粒子或任意文件访问。
未知符号在配置时拒绝；运行中遇到除零、非有限数或属性越界，会停止并清理对应实例，在客户端报告错误。
修正配置后执行 remove，再 start/burst 重试；不会生成替代图形或偷偷使用默认值。

## 条件曲面

下面先采样空间，再保留球壳；球壳只是条件表达式的结果，不是内置特效。

```mcfunction
pdraw particle emitter reset shell
pdraw particle emitter shell anchor fixed ~ ~3 ~
pdraw particle emitter shell sample grid
pdraw particle emitter shell sample bounds -2,-2,-2 2,2,2
pdraw particle emitter shell sample step 0.25
pdraw particle emitter shell where abs(sqrt(x*x+y*y+z*z)-2)<0.1
pdraw particle emitter shell motion vx x*0.01
pdraw particle emitter shell motion vy y*0.01
pdraw particle emitter shell motion vz z*0.01
pdraw particle emitter shell lifetime 60
pdraw particle emitter shell rate 512
pdraw particle emitter shell burst
```

`rate` 限制每刻检查的样本数，包含被 where 筛掉的样本。达到 `max_alive` 时等待空位，不推进样本游标。
循环生成默认每刻最多 8 个样本；`rate` 允许 1..1024，`interval` 允许 1..72000 刻，`max_alive` 允许 1..8192。
每轮样本最多 8192，网格按筛选前的采样点总数计算这个上限。

## 锚点和联动

```mcfunction
pdraw particle emitter shell anchor entity @p
pdraw particle emitter shell follow true
```

实体偏移和朝向可以显式配置：`anchor entity @p 0 2 0 false`。
偏移是三个数值，`false` 使用世界坐标轴，`true` 将偏移和粒子坐标系随实体朝向旋转。
锚点按实体 UUID 识别，实体暂时不在客户端时不继续出生；follow 成员隐藏，重新出现后恢复。

`follow false`（默认）只让出生位置跟随，已经生成的粒子留在出生时的坐标系中，适合拖尾。
`follow true` 让所有现存成员继续跟随当前锚点，适合法阵与护盾。

不同层可以共用命名锚点：

```mcfunction
pdraw particle emitter sigil anchor emitter shell
```

此后只改 shell 的 anchor，sigil 会一起更新。允许多级引用，禁止循环引用和删除仍被引用的配置。
也可用原版 marker 实体为多层特效提供共同锚点。

## 筛选、修改、停止

```mcfunction
pdraw particle emitter shell select age>20 && y>0
pdraw particle emitter shell change vy 0.2
pdraw particle emitter shell change r 1
pdraw particle emitter shell apply
```

先声明筛选条件和修改内容，最后 `apply` 执行一次，只修改当前选中的现存成员，不改变以后出生的粒子。
`remove_selected` 按当前 select 真正删除成员；条件位置与运动公式使用同一个坐标系。
同名配置触发的多个 burst 实例都参与筛选。持续行为使用 motion 或 kill。

| 命令 | 行为 |
| --- | --- |
| `emitter create <名称>` | 新配置，初始锚点为执行位置 |
| `emitter reset <名称>` | 清除旧配置与粒子后重新创建 |
| `emitter copy <来源> <新名称>` | 复制配置，不复制播放实例 |
| `emitter list` / `emitter <名称> inspect` | 查询名称、参数、公式与条件 |
| `emitter <名称> start` | 循环遍历样本持续出生 |
| `emitter <名称> burst` | 只遍历一轮；同一配置可以反复触发 |
| `emitter <名称> stop` | 停止出生，现存成员继续运动直到寿命到点 |
| `emitter <名称> remove` | 立即清除该配置产生的全部实例，保留配置 |
| `emitter <名称> delete` | 清除实例并删除配置 |
| `emitter <名称> lifetime <刻数>` | 每颗寿命，默认 80，1..72000；显式 -1 为无限 |
| `emitter <名称> rate <数量>` / `interval <刻数>` | 每隔 interval 刻尝试出生 rate 颗 |
| `emitter <名称> max_alive <数量>` | 单个播放实例的同时存活上限 |

出生、运动、变量、let、条件、寿命、速率和锚点支持热更新，保留现存粒子的年龄与状态。
寿命和外观只影响后续出生的成员；motion 和 variable 会影响已有成员。运行中不能增删 state 字段。
变量更新使用单独的小包，不会重新发送图片点阵；锚点更新与筛选操作也独立下发。
更换采样布局或图片前先 `remove`，再配置并启动。清除运动项可用 `unset motion <属性>`；持续加速度
或速度已存进粒子状态时，需另用 change/apply 或 motion 显式清零。

外观使用 `texture <资源名>` 或 `appearance <参数> <值>`，每条只设置一项。支持现有的 style、color、
scale、uv、size、size_world、billboard、spin、spin_space、additive、glowing、light、intensity 与 `*_curve`。
曲线写法仍是 `0:1,40:0:ease_out`。修改 spin_space 前先设置 spin。

配置按维度保存在运行时内存中，重启或卸载维度后清空；建议在数据包的 load 函数里声明，技能函数只触发
burst 或更新参数。每维度最多 256 个配置，全服最多 256 个播放实例。有限寿命 burst 与停止后的实例自动回收；
无限寿命实例需显式 remove/delete。持续生成的实例会给后来进入维度的玩家下发当前声明，从头开始生成，
不补演历史拖尾；burst 只向触发时同维度在线的玩家播放，不对后来进入的玩家重复触发。

## 与组命令、API 的关系

`group/add/program` 仍用于固定成员的组级编排；`emitter` 用于出生和逐粒子行为。
现有所有 `add` 图元都可以显式指定 `lifetime=80`，不再局限于 field；未写时仍保留原来的无限寿命。
组 expression 仅输出位置、颜色和尺寸；向 vx/vy/vz/glow/light 赋值会报错，请使用生成器的 birth/motion。

公开 API 为 `ParticleSampling`、`ParticleImageSampler`、`ParticleRecipe`、`RecipeAnchor`、
`ParticleSources.start` 与 `ParticleSourceHandle`。API 与命令共用出生调度、运动求值和网络声明。
`.pdrawc` 格式和编辑器脚本语义不变。
