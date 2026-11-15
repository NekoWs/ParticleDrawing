# 粒子命令

`/pdraw particle` 支持聊天、命令方块与数据包函数，需要原版等级 2 命令权限。
`group/add/program` 用于固定成员的组编排，`emitter` 用于按样本生成粒子和控制逐粒子运动。
[组编排](#命令绘制与数据包) · [分步生成器](#分步配置粒子生成器) · [动画](animation.md#播放动画文件) · [光照](lighting.md#命令)

## 命令绘制与数据包

数据包目录与 `pack.mcmeta` 见[命令函数示例](../examples/commands/README.md#安装到测试存档)。

`/pdraw particle` 把公开绘图与 `ParticleGroup` 编排能力拆成可组合的命令，适合 `.mcfunction`、连锁
命令方块和聊天调试。一个效果按三步构建：

1. `group create` 或 `group reset` 建一个有名字的组；
2. 用多条 `add` 把图元加全；
3. 用多条 `program` 录制组动画，或用逐粒子表达式接管粒子状态。

组名在当前维度内唯一，允许字母、数字、下划线、短横线、点和冒号，最长 64 字符。`reset` 会先移除
同名旧组再创建，适合可重复触发的数据包函数。命名组只存在于本次服务器运行期，关卡卸载或服务器关闭时
自动清理。

下面的简写示例放在同一个 `.mcfunction` 中执行时，共用函数入口的坐标。拆到多个命令方块时，每个方块的
`~` 都相对该方块自身；连锁执行不会继承前一个方块的执行位置。同组只共享编排，不会把不同 `add` 命令
生成的图形自动移到同一个中心。简单示例见下方的[命令方块 sin 曲线](#命令方块简单用法sin-曲线)。

```mcfunction
# 建组并添加两个图元；~ 和 ^ 都按原版命令源解析
pdraw particle group reset demo ~ ~1 ~
pdraw particle add demo circle ~ ~1 ~ 3 96 color=#55ddff style=soft_dot additive glowing
pdraw particle add demo hexagram ~ ~1 ~ 2.4 24 gradient=#ffffff:#5588ff style=soft_dot scale=0.35

# 每条 program 只负责一个动作，按调用顺序接到同一时间线
pdraw particle program demo fade_in 8 ease_out
pdraw particle program demo spin 0 1 0 2
pdraw particle program demo pulse 1.2 10 -1
pdraw particle program demo delay 80
pdraw particle program demo stop_continuous
pdraw particle program demo fade_out 12 ease_in
```

### 命令方块简单用法：sin 曲线

准备一个脉冲命令方块，接两个“无条件、始终活动”的连锁命令方块，箭头按顺序相连。每块各填一行：

```mcfunction
pdraw particle group reset sine ~ ~2 ~
pdraw particle add sine field anchor 81 texture=particle:minecraft:end_rod size_world=0.12,0.12 glowing light=0 lifetime=80
pdraw particle program sine expression local 0 u=i/(n-1);x=8*u;y=sin(2*PI*u-t*0.002);z=0;
```

触发第一块，即可在它上方 2 格处画出一条长 8 格、振幅 1 格、随时间起伏的 sin 曲线。
`field` 提供 81 颗粒子，`i` 是从 0 开始的序号，`n` 是总数，`u` 把序号映射到 0..1。
`field anchor` 在组锚点生成成员，`expression local` 把公式输出视为相对该锚点的偏移；移动效果只改
第一条命令的坐标。曲线会向下延伸 1 格，建议把起点高度设在地面上方 2 格。清除时执行：

```mcfunction
pdraw particle group remove sine
```

`lifetime=80` 表示粒子存在 80 tick（4 秒），到点自动消失；默认未写寿命时永存。末地烛贴图会在这
4 秒里按粒子年龄播放完整个帧序列。公式中的 `t` 是毫秒时间，控制波形运动；去掉 `-t*0.002` 可固定
曲线形状，贴图仍会换帧。不需要重复命令方块每 tick 重画。

要跟随实体，在最后一块公式命令之前插入一个连锁命令方块：

```mcfunction
pdraw particle program sine anchor entity @p 0 2 0 false
```

此时曲线相对目标实体脚下上方 2 格绘制。`false` 只跟随位置，改成 `true` 也会随实体朝向旋转；
`@p` 也可换成玩家名或只选一个目标的实体选择器。

`field` 的 `lifetime` 是粒子存在时间；`expression local 80 ...` 中的 80 只控制公式求值时长，不负责
删除粒子。所有 `add` 图元均可指定 `lifetime`。也可用 `program <组> destroy_after 80` 销毁整组。

### 复用原版粒子贴图

使用 `texture=particle:minecraft:end_rod` 播放原版粒子贴图序列，`#3` 固定为第 4 帧，`@8`
按 8 FPS 循环。也可引用其他模组提供的粒子贴图。换帧与资源包规则见[粒子贴图](guide.md#原版与模组粒子贴图)。

### 组与图元语法

| 命令 | 作用 |
| --- | --- |
| `group create <组> <轴心>` | 新建组；同名已存在时报错 |
| `group reset <组> <轴心>` | 移除同名旧组并重建，适合重复触发 |
| `group list` | 列出当前维度的命名组 |
| `group remove <组>` / `group clear` | 删除一个组或清空当前维度 |
| `add <组> dot <位置>` | 单颗粒子 |
| `add <组> field <位置> <数量>` | 同点粒子场，通常交给 `expression` 展开 |
| `add <组> field anchor <数量>` | 在组当前锚点生成粒子场，无需重复写坐标 |
| `add <组> line <起点> <终点> <数量>` | 粒子线段 |
| `add <组> beam <起点> <终点>` | 一颗带方向和世界尺寸的贴图光束 |
| `add <组> helix <中心> <半径> <高度> <圈数> <数量>` | 螺旋曲线 |
| `add <组> circle <中心> <半径> <数量>` | 圆周 |
| `add <组> disc <中心> <半径> <外圈数量> <层数>` | 实心圆盘 |
| `add <组> triangle <中心> <半径> <每边数量>` | 三角形轮廓 |
| `add <组> hexagram <中心> <半径> <每边数量>` | 六芒星轮廓 |
| `add <组> rect <中心> <宽> <高> <密度>` | 矩形网格或边框 |
| `add <组> sphere <中心> <半径> <数量>` | 斐波那契球面 |
| `add <组> cuboid <中心> <宽> <高> <深> <密度>` | 长方体网格或外壳 |

每条 `add` 后面可继续写空格分隔的 `key=value`。布尔项也可只写键名，例如 `additive` 等价于
`additive=true`。颜色写 `#RRGGBB` 或 `#RRGGBBAA`，向量写 `x,y,z`，含空格的贴图名用双引号包住。
不适用于当前图元的参数会直接报错。

| 参数 | 说明 |
| --- | --- |
| `color=#RRGGBB` / `gradient=颜色:颜色` / `rainbow[=透明度]` | 三选一的着色方式 |
| `scale=N`、`stagger=tick`、`taper` | 标量尺寸、逐颗错峰生成、线条收细 |
| `axis=xz\|xy\|yz`、`hollow`、`thickness=N` | 平面、空心网格、光束宽度 |
| `style=square\|soft_dot\|line`、`texture=名称`、`uv=u,v,w,h` | 内置形状、自定义贴图与 UV |
| `size=w,h` / `size_world=w,h`、`billboard=true\|false` | 编辑器单位或世界格尺寸、是否面向摄像机 |
| `spin=x,y,z`、`spin_space=local\|world` | 贴图欧拉角（度）及旋转空间 |
| `additive`、`glowing`、`light=0..15`、`intensity=N`、`light_only` | 混合、全亮和动态光参数；`light_only` 只发光不画外观 |
| `alpha_curve` / `size_curve` / `red_curve` 等 | `tick:value[:easing]`，多关键帧以逗号分隔 |
| `lifetime=tick` | 所有图元：每颗粒子的存在刻数，未写时保留原来的无限寿命 |
| `velocity=x,y,z`、`force=x,y,z`、`force_ticks=tick` | 仅 `field`：初速度和持续受力 |

`stagger` 会让 `Draw` 逐颗生成成员。命令层会把紧随其后的 `program` 暂存到最后一颗成员入组之后，并
保持多条编排命令的顺序，不需要数据包自行估算等待时间。一个命名组最多编排 8192 颗粒子，超过时应拆成
多个组；开始执行第一条 `program` 后不能再追加图元。

### 编排命令

| 命令 | 作用 |
| --- | --- |
| `program <组> anchor fixed <位置>` | 绑定固定坐标锚点，解除已有的实体跟随 |
| `program <组> anchor entity <实体> [x y z [local]]` | 绑定实体锚点及偏移；`local=true` 时偏移和整组随实体朝向旋转 |
| `program <组> pivot <位置>` / `follow <实体> ...` | 上面两条锚点命令的兼容简写 |
| `program <组> delay <tick>` | 推进该组时间线，后续动作从新时刻开始 |
| `program <组> fade_in\|fade_out\|fade_out_keep <tick> [缓动]` | 淡入、淡出并删除、淡出但保留 |
| `program <组> move <x> <y> <z> <tick> [缓动]` | 整组平移 |
| `program <组> move_offset <倍率> <tick> [缓动]` | 每颗沿自身相对轴心方向散开 |
| `program <组> move_path <tick> <x,y,z;x,y,z...> [缓动]` | 沿一组相对位移途经点移动 |
| `program <组> rotate <轴x> <轴y> <轴z> <角度> <tick> [缓动]` | 绕轴心旋转一次，角度单位为度 |
| `program <组> recolor <颜色> <tick> [缓动]` | 变色 |
| `program <组> scale_by\|scale_to <倍率> <tick> [缓动]` | 相对缩放或缓动到绝对倍率 |
| `program <组> spin <轴x> <轴y> <轴z> <每tick角度>` | 持续旋转 |
| `program <组> pulse <峰值倍率> <半周期> [次数]` | 往复缩放，次数 `-1` 表示持续 |
| `program <组> stop_continuous` | 在当前时间线时刻停止持续旋转和脉冲 |
| `program <组> destroy_after <tick>` / `retire [余量]` | 定时销毁，或等有限程序完成后销毁 |
| `program <组> entity <句柄> <实体>` | 在程序启动前注册公式实体句柄 |
| `program <组> variable set <名称> <数字>` | 在程序启动前声明静态变量 |
| `program <组> variable live <名称> <公式>` | 程序启动后立即更新变量 |
| `program <组> variable ease <名称> <公式> <tick> [缓动]` | 程序启动后把变量缓动到公式结果 |
| `program <组> expression <tick> <代码>` | 每颗每 tick 求值；`0` 表示持续 |
| `program <组> expression local <tick> <代码>` | 输出相对组当前锚点，可直接跟随实体与局部朝向；`0` 表示持续 |

缓动名支持 `linear`、`ease_in`、`ease_out`、`ease_in_out`，以及 `_quad`、`_cubic`、`_bounce`、
`_elastic` 变体。命令补全会列出全部名称。

实体锚点在命令执行时把选择器解析为一个实体 UUID，之后会持续跟随当时选中的同一个实体。默认偏移是
`0 0 0`，按世界坐标解释；最后一个参数写 `true` 时，偏移和组内图形都会随实体的 yaw/pitch 转动。客户端
暂时找不到该实体时会隐藏该组，实体重新进入客户端后继续显示。锚点通常放在淡入、旋转等动画命令之前：

```mcfunction
# 组轴心放在实体脚下，图形画在头顶 1.8 格，再用相同偏移绑定到该实体
pdraw particle group reset halo ~ ~ ~
pdraw particle add halo circle ~ ~1.8 ~ 0.7 48 color=#ffe080 style=soft_dot additive glowing
pdraw particle program halo anchor entity @s 0 1.8 0 false
pdraw particle program halo spin 0 1 0 3

# 随实体朝向的前方施法环：局部 Z 偏移 1.2 格
pdraw particle group reset cast_ring ~ ~ ~
pdraw particle add cast_ring circle ~ ~1.2 ~1.2 1 56 axis=xy color=#70c8ff style=soft_dot additive glowing
pdraw particle program cast_ring anchor entity @s 0 1.2 1.2 true
```

### 数据包法阵示例

下面的效果把静态法阵和动态光点拆成两个组。静态组使用形状与组动画，动态组使用 192 颗同点粒子和
逐粒子公式，运行时只下发声明，旋转、实体跟随、颜色和退场由客户端执行。

入口需要保留实体与位置上下文：

```mcfunction
execute as @p at @s run function example:magic/start
```

`data/example/function/magic/start.mcfunction`：

```mcfunction
pdraw particle group reset spell_sigil ~ ~ ~
pdraw particle add spell_sigil circle ~ ~0.06 ~ 3.2 112 color=#45d9ff style=soft_dot scale=0.32 additive glowing light=12 intensity=1.4
pdraw particle add spell_sigil hexagram ~ ~0.06 ~ 2.55 22 gradient=#ffffff:#487dff style=soft_dot scale=0.28 additive glowing
pdraw particle program spell_sigil anchor entity @s 0 0.06 0 false
pdraw particle program spell_sigil fade_in 8 ease_out
pdraw particle program spell_sigil spin 0 1 0 1.8
pdraw particle program spell_sigil pulse 1.12 12 -1
pdraw particle program spell_sigil delay 80
pdraw particle program spell_sigil stop_continuous
pdraw particle program spell_sigil fade_out 12 ease_in

pdraw particle group reset spell_motes ~ ~1 ~
pdraw particle add spell_motes field ~ ~1 ~ 192 color=#56dfff00 style=soft_dot size_world=0.07,0.07 additive glowing light=13 intensity=1.6
pdraw particle program spell_motes entity caster @s
pdraw particle program spell_motes variable set radius 3
pdraw particle program spell_motes variable set presence 1
pdraw particle program spell_motes expression 0 q=(i%64)/64;layer=floor(i/64);ang=q*2*PI+t*0.002*(1-2*(layer%2));rad=radius*(0.55+layer*0.2);x=get_entity_x(caster)+cos(ang)*rad;y=get_entity_y(caster)+0.08+sin(ang*3+t*0.004)*0.08;z=get_entity_z(caster)+sin(ang)*rad;sc=presence*(0.65+0.25*sin(t*0.006+i));a=presence;r=0.2;g=0.78;b=1;
schedule function example:magic/end 4s replace
```

`data/example/function/magic/end.mcfunction`：

```mcfunction
pdraw particle program spell_motes variable ease presence 0 12 ease_in
pdraw particle program spell_motes retire 2
```

表达式里的 `i` 是成员序号，`n` 是成员总数，`t` 是毫秒时间；旧式 `expression <tick>` 的 `x/y/z`
是世界绝对坐标，`expression local <tick>` 则是相对当前组锚点的偏移，
`r/g/b/a`、`sc` 控制外观，glowing/light 在 add 时设置；逐粒子运动和光照输出使用 emitter 的 birth/motion。
组公式可以读取已注册实体和世界状态，完整 getter 表见
[组公式与实体输入](guide.md#实体通道与公式)。一个组用了 `expression` 后，公式会接管该组所有成员的位置和外观；需要同时
保留静态图形时，应像示例一样拆组。

拆成命令方块链时，统一每条命令的执行位置和实体上下文；入门见[命令方块 sin 曲线](#命令方块简单用法sin-曲线)。

### 让常驻粒子群变形到新编队

`program <组> morph` 将现有成员移动到新槽位，默认采用最近邻分配。
位置公式输出世界坐标。`i` 是槽位序号，`n` 是总数，`u` 是 0–1 的进度，动态目标还能读取毫秒 `t`。

```mcfunction
pdraw particle program demo morph slots 120 vec3(cos(u*6.2832)*3, 101.5, sin(u*6.2832)*3)
pdraw particle program demo morph yaw 55
pdraw particle program demo morph pitch -30
pdraw particle program demo morph size 0.8+u*0.5
pdraw particle program demo morph ticks 120
pdraw particle program demo morph easing in_out_cubic
pdraw particle program demo morph max_speed 0.5
pdraw particle program demo morph loop -1
pdraw particle program demo morph start
```

| 子命令 | 含义 |
| --- | --- |
| `morph slots <数量> <位置公式>` | 目标槽位；位置必须是 `vec3(...)` |
| `morph yaw\|pitch\|size <公式>` | 槽位朝向和尺寸的公式。yaw 绕 Y 轴，pitch 绕 X 轴，单位为度；size 使用 `scale` 的单位 |
| `morph control1\|control2 <公式>` | 每颗粒子自己的路径控制点（二次/三次贝塞尔） |
| `morph ticks\|stagger\|loop <整数>` | 时长、错峰、往返次数（`-1` 无限） |
| `morph max_speed\|max_accel\|converge <数>` | 限速、限加速度、收敛阈值 |
| `morph path straight\|quadratic\|cubic` | 直线或贝塞尔路径 |
| `morph easing <名字>` | 缓动名，与 `move` 等命令同一套 |
| `morph wobble <none\|bell\|noise\|vortex> [强度] [峰值]` | 过渡中段的扰动，收尾必衰减到 0 |
| `morph allocation <nearest\|index\|angle>` | 分配策略 |
| `morph dynamic\|keep_velocity\|attributes <true\|false>` | 动态目标、到达后继续飞、槽位属性开关。填写 yaw、pitch 或 size 时自动启用，可用 `attributes false` 关闭 |
| `morph start` / `inspect` / `reset` | 下发、查看配置、清空配置 |

过渡中再次执行 `start`，从当前实际位置、速度、朝向和尺寸接续。省略的属性沿用当前值。
面向相机的粒子以相机为基准旋转；用 `billboard=false` 添加粒子，可以看到以世界 +Z 为基准的固定朝向。
设有限速时会延长过短的过渡时长。
用代码绘制字幕并切换到几何形状，见[字幕与形状切换](guide.md#字幕与形状切换)。

## 分步配置粒子生成器

`/pdraw particle emitter` 把采样、出生属性、运动与筛选分开配置。每个名称是一份可复用的配置，
不代表固定形状。曲线、曲面、图片点阵都使用同一套公式与生命周期。
需要原版等级 2 命令权限；可在聊天、命令方块和数据包函数里使用。
生成器名称含冒号时使用双引号，例如 `emitter "spell:fire" burst`。
贴图引用、图片路径和 bounds 保留完整文本，支持冒号、斜杠和逗号；含空格的图片路径可用双引号包住。

### 第一个例子：会运动的 sin 曲线

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

### 图片转粒子

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

### 参数曲线与动态曲面示例

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

#### 四种常见模式怎么对应

| ColorBlock | PD 写法 |
| --- | --- |
| `parameter` 一次画完 | `sample range` + `birth position`，`rate` 拉高，`burst` |
| `tickparameter` 逐步画 | 同上，`rate` 设成每刻要画的颗数，`burst` 只遍历一轮 |
| `conditional` 空间筛选 | `sample grid` + `where`，条件用 `sx/sy/sz` 看原始网格坐标 |
| 速度表达式里的矩阵 | `let` 存矩阵和向量，`motion velocity` 一次写三个分量 |

四个可直接运行的例子在 `examples/commands/`：`cube`、`seven-star`、`sharp-star`、`five-star`。
以锐角星形为例，把 ColorBlock 的一行拆成可读的几行：

```mcfunction
pdraw particle emitter sharp-star let p floor(u/78.5)/25
pdraw particle emitter sharp-star let q u%157/25
pdraw particle emitter sharp-star birth position vec3(5*(cos(p)*cos(q))^3, 5*(sin(p)*cos(q))^3, 5*sin(q)^3)
pdraw particle emitter sharp-star let ang 0.05
pdraw particle emitter sharp-star let turn mat3(vec3(-sin(ang),0,-cos(ang)), vec3(0,1,0), vec3(cos(ang),0,-sin(ang)))
pdraw particle emitter sharp-star motion velocity turn*vec3(x*2*sin(ang), 0.1, z*2*sin(ang))
```

`u`、`t`、`x`、`a` 这些名字是内建输入，不能拿来当 `let`/变量名；换成语义名（`p`、`q`、`amp`、`ang`）更清楚。
`where` 要求 `&&`/`||`，不是 ColorBlock 的 `&`/`|`；比较仍可用 `==`、`<`、`abs`。

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

### 采样和公式

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
| `orbit vec3(x,y,z)` | 锚点偏移公式，按发射器运行毫秒 `t` 求值，叠加在锚点原点上 |
| `orbit unset` | 取消锚点偏移 |
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
| `sx,sy,sz` | 本轮样本的原始坐标；grid 是网格点，range/count 是 0，图片是像素位置。出生公式改了 `x/y/z` 也不影响它 |
| `dx,dy,dz` | 出生公式算完后的初始位置；适合保留曲线、图片或网格布局做变换；birth 阶段读取它们时为原始样本坐标 |
| `vx,vy,vz`、`ax,ay,az` | 当前速度（格/刻）和加速度（格/刻²） |
| `r,g,b,a`、`sc`、`glow`、`light` | 当前颜色、尺寸倍率、全亮、向外照明等级 |

可写属性是 `x/y/z/vx/vy/vz/ax/ay/az/r/g/b/a/sc/glow/light` 和用 `state` 声明的字段。
也可以一次写三个分量：`position` 写 `x/y/z`、`velocity` 写 `vx/vy/vz`、`acceleration` 写 `ax/ay/az`，
公式结果是 `vec3`。同一条命令里不能同时写 `position` 和 `x`，会明确报错。
颜色必须在 0..1，尺寸不得为负，照明等级在 0..15。`glow` 可用布尔条件。
同一阶段的输出使用同一输入快照，例如 `motion x y` 与 `motion y x` 能交换两个分量。
需要多步计算时使用多条 `let`；`let` 可以保留数值、布尔、向量和矩阵，后续公式按 `.x/.y/.z` 取分量。
`where` 在出生公式之后求值，用 `sx/sy/sz` 可以回到原始采样坐标；用 `x/y/z` 则看出生后的位置。

#### 让发射器沿轨迹移动

`orbit` 给锚点叠一条按时间求值的偏移，发射器原点就会动起来；已出生的粒子留在出生位置，
配合 `rate`/`interval` 就能沿轨迹铺出粒子。公式只能引用 `t`（发射器运行毫秒）、`variable`、
`state` 与 `let`，写样本级的 `u`/`i`/`age` 会直接报错。

```mcfunction
pdraw particle emitter trail anchor fixed ~ ~ ~
pdraw particle emitter trail sample count 1
pdraw particle emitter trail orbit vec3(sin(t*0.001)*4, 0, cos(t*0.001)*4)
pdraw particle emitter trail lifetime 400
pdraw particle emitter trail rate 1
pdraw particle emitter trail interval 1
pdraw particle emitter trail start
```

上面这段让发射器沿半径 4 的圆匀速绕行，每刻发一颗，留下一条圆环。

#### 公式求值次序

每条公式只写一个属性。每刻先检查寿命与 `kill`，再计算 motion，随后速度加上加速度、位置加上速度。
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
修正配置后，执行 `remove`，再用 `start` 或 `burst` 重新生成。

### 条件曲面

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

### 锚点和联动

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

### 筛选、修改、停止

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
scale、uv、size、size_world、billboard、spin、spin_space、additive、glowing、light、intensity、
spot、light_dir、light_range、light_falloff 与 `*_curve`。

光源属性和射灯示例见[动态光照](lighting.md#命令)。

曲线写法仍是 `0:1,40:0:ease_out`。修改 spin_space 前先设置 spin。

配置按维度保存在运行时内存中，重启或卸载维度后清空；建议在数据包的 load 函数里声明，技能函数只触发
burst 或更新参数。每维度最多 256 个配置，全服最多 256 个播放实例。有限寿命 burst 与停止后的实例自动回收；
无限寿命实例需显式 remove/delete。持续生成的实例会给后来进入维度的玩家下发当前声明，从头开始生成，
不补演历史拖尾；burst 只向触发时同维度在线的玩家播放，不对后来进入的玩家重复触发。

代码入口为 `ParticleSampling`、`ParticleImageSampler`、`ParticleRecipe`、`RecipeAnchor` 和
`ParticleSources.start`。[API 参考](api-reference.md#采样与运行时生成)
