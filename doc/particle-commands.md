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
| `align_velocity=true`、`stretch=0..10` | 朝位移方向拉长；只写 `stretch` 也启用对齐 |
| `soft=0..4`、`trail=0..32` | 贴近方块碰撞面淡出的距离（格）、保留的拖尾长度（刻）；0 关闭 |
| `trail_sides=0 或 3..12`、`model=资源ID` | 平面丝带或管状截面、实际三角网格外观 |
| `depth_fade=0..16`、`distortion=0..32` | 逐像素按场景深度淡出（格）、按贴图折射场景颜色（像素） |
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
| `mesh file <路径>` / `mesh resource <ID>` | 以三角化 OBJ 为采样源，文件在 `particleMeshes/` 内 |
| `mesh count <数量>` / `mesh seed <整数>` | 采样数量和种子；按三角面面积抽样 |
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
| `appearance align_velocity true` | 长轴自动对齐世界位移方向，首次移动前沿用原朝向 |
| `appearance stretch <0..10>` | 启用速度对齐。长度乘以 `1 + stretch × 每刻位移`，最多 12 倍。默认强度 0.6 |
| `appearance soft <0..4>` | 距附近方块碰撞面的淡出范围（格），0 关闭 |
| `appearance trail <0..32>` | 保留最近几刻的世界位置，生成一条随镜头转向并沿尾端渐隐的丝带，0 关闭 |
| `appearance trail_sides <0 或 3..12>` | 拖尾截面边数；与 `appearance trail` 配合使用 |
| `subemit count <1..64>` | 粒子正常消失时在原地爆发的子粒子数量；不配置则什么都不留 | 
| `subemit lifetime <1..200>` | 子粒子寿命（刻），默认 12 |
| `subemit speed <0..2>` | 子粒子初速（格/刻），方向在球面上均匀散开，默认 0.12 |
| `subemit scale <0.05..4>` | 子粒子相对父粒子的尺寸比例，默认 0.6 |
| `subemit off` | 关闭子发射 |
| `collide none\|solid\|liquid\|both\|entity\|all` | 可检测方块、液体和实体的首个接触面；`both` 检测方块与液体 |
| `collision_size <0..1>` | 碰撞盒的半宽（格），默认 0 为中心点碰撞 |
| `collision_steps <1..4>` | 一刻内可处理的接触次数，默认 1。设为 2 或以上时按剩余时间继续反弹 |
| `bounce <0..1>` | 撞到后法线方向保留的速度比例，0 直接停下，1 完全弹性，默认 0.35 |
| `friction <0..1>` | 撞到后切向速度的保留比例，越小越「黏地」，默认 0.55 |

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

#### 以三维模型代替粒子四边形

`appearance model particledrawing:builtin/octahedron` 使用内置八面体。`appearance billboard false` 让模型保留世界朝向；
`appearance spin 25,35,0` 可设置自转，`appearance model none` 恢复四边形。

也可指定客户端资源包中的三角化 OBJ，例如 `example:particle_models/shard.obj`，最多 128 个三角面。
模型坐标会居中并归一化，粒子尺寸决定最终宽高；表面仍用粒子颜色、透明混合和光图。
每个面使用粒子当前贴图的 UV 区域，OBJ 的材质和 UV 数据不参与绘制。

#### 从模型表面发射

文件放入服务端工作目录的 `particleMeshes/`，或作为数据包资源提供。OBJ 需要先三角化。
模型坐标以格为单位，粒子按三角面面积分布。`sample_nx/ny/nz` 是模型局部法线。
OBJ 提供顶点法线时使用重心插值，其余三角面使用面法线。非网格样本的三个分量为 0。
命令、Kotlin 和 Java 共用 `ParticleMeshSampler`。API 可提供来自当下模型姿势的 `ParticleTriangleProvider`；
`points` 每次读取一份三角形快照，返回插值后的位置、法线和可选的原始 UV。
配方可用 `sample_has_uv` 判断是否有坐标，并使用 `sample_tex_u`、`sample_tex_v` 读取坐标。
样本缺少 UV 时这三项都是 0。

```mcfunction
pdraw particle emitter shell mesh file shell.obj
pdraw particle emitter shell mesh count 256
pdraw particle emitter shell mesh seed 42
pdraw particle emitter shell birth velocity vec3(sample_nx*0.1,sample_ny*0.1,sample_nz*0.1)
```

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

用 `spacing 0.5` 搭配 `start`，可让锚点每走 0.5 格发射一批。出生公式以实际经过的路点为锚点，可继续组合参数、图片或网格采样。锚点丢失或单刻跳变超过 64 格时清空剩余里程。每个配方每刻最多尝试发射 1024 颗，超出时后续从当前锚点继续。

#### 子发射

粒子消失时在原地炸一批更小、更短命的粒子，用来做撞击火花、破碎和消散。
子粒子是普通渲染粒子，**不会再触发子发射**——只做一层，避免数量指数爆炸。

```mcfunction
pdraw particle emitter reset meteor
pdraw particle emitter meteor anchor fixed ~ ~8 ~
pdraw particle emitter meteor sample count 1
pdraw particle emitter meteor birth position vec3((rand()-0.5)*8, 0, (rand()-0.5)*8)
pdraw particle emitter meteor birth vy -0.45
pdraw particle emitter meteor collide solid
pdraw particle emitter meteor subemit count 10
pdraw particle emitter meteor subemit lifetime 14
pdraw particle emitter meteor subemit speed 0.22
pdraw particle emitter meteor subemit scale 0.45
pdraw particle emitter meteor appearance glowing true
pdraw particle emitter meteor appearance light 15
pdraw particle emitter meteor lifetime 26
pdraw particle emitter meteor rate 4
pdraw particle emitter meteor start
```

上面的粒子落到地面后消失，每一颗在地面位置炸出 10 颗小火花。子粒子沿用父配方外观，
只是按 `subemit scale` 缩小、按 `subemit lifetime` 提前消失。
#### 用事件启动子配方

`on birth <子配方>`、`on death <子配方>` 和 `on contact <子配方>` 把一轮子配方放到父粒子的出生、死亡或接触点。手动移除不触发死亡事件。接触默认每条规则、每颗粒子触发一次；加 `repeat` 可在每次入射接触时触发，加 `remove_parent` 可在命中后移除父粒子。可选 `material "<资源 ID>"` 筛选方块、液体或实体类型，放在末尾选项之前。

```mcfunction
pdraw particle emitter sparks on contact metal_sparks material "minecraft:iron_block" remove_parent
pdraw particle emitter sparks on contact stone_dust material "minecraft:stone"
pdraw particle emitter sparks on clear
```

绑定时复制子配方配置。子配方最多 64 个样本，寿命 1–1200 刻，用世界坐标轴，并从真实接触点法线外侧开始；每条规则共享子配方的 `max_alive`。客户端每刻尝试生成至多 256 颗事件粒子，超过预算的事件不会补发。子配方使用现有采样、筛选和公式，不再挂事件、自动子发射、跟随或轨道。事件公式可读 `event_x/y/z`（位置）、`event_nx/ny/nz`（接触法线）、`event_vx/vy/vz`（入射位移或生命周期事件时的速度）。位置、法线和速度均为世界坐标。

可在接触规则末尾加 `decal <半宽>`，把子配方的外观投影到命中法线朝向的方块表面，例如
`on contact scorch material "minecraft:stone" decal 0.6`。投影裁剪到实际方块碰撞形状，
并在命中面法线方向的 ±0.6 格内跨平行的台阶面，每颗印记最多 64 个片段。建议子配方用一个样本，
保持位置固定，用寿命和透明度控制消散。

在半宽后加 `wrap`，从实际命中点沿相连的外露面展开贴图，包括方块的竖面和台阶下层。
半宽按展开后的表面距离计算，棱边两侧的 UV 连续。断开的地形面不会收到同一枚印记。

```mcfunction
pdraw particle emitter sparks on contact scorch decal 0.8 wrap
```

[雨滴与飞溅示例](../examples/commands/rain-contact.mcfunction)用世界条件筛选出生位置，在接触真实表面后执行溅水配方。

#### 模拟范围

持续配方可用 `simulation_range <0..512>` 限制客户端模拟距离，默认 0 表示始终模拟。
镜头离绑定锚点超过指定格数时，客户端回收该配方的现存粒子、事件子粒子和拖尾，停止推进公式；
镜头回到该距离的 90% 时，从第一轮重新发射。测距使用绑定锚点，配方轨道偏移和离开锚点的粒子不参与测距。
只对允许按观察者重启的特效开启。一次性发射与事件子配方要求此值为 0。
回收不触发生命周期事件，和仅减少绘制的 `particleLodDistance` 客户端选项可以独立使用。

#### 世界输入

`world_x/y/z` 是当前求值点的世界坐标。`world_loaded` 为 1 时表示客户端已收到该位置的区块。`block_light` 和 `sky_light` 返回 0–15；`open_sky`、`in_solid`、`in_fluid` 返回 0 或 1；`flow_x/y/z` 为该点液体流向。`entity_near` 表示范围内有活跃非旁观实体，`entity_dx/dy/dz` 为最近实体相对粒子的世界坐标偏移。用 `environment entity_radius <0..32>` 设置半径（默认 8，0 禁用），用 `environment entity_type <资源 ID 或 any>` 筛选实体类型。区块未加载时环境量为 0。出生公式查询初始样本的位置，`where` 和出生阶段的 `kill` 查询应用出生公式后的位置，逐刻公式查询推进前的位置。锚点 `orbit` 使用发射器级输入。

`neighbor_count`、`neighbor_dx/dy/dz` 和 `neighbor_vx/vy/vz` 分别给出同配方实例内邻居的数量、平均世界坐标偏移和平均世界速度。用 `environment neighbor_radius <0.05..16>` 设置半径（默认 2 格）。查询读取整刻推进前的状态，排除粒子本身。密集区域会在每个空间格均匀抽取至多 24 名候选邻居。

```mcfunction
pdraw particle emitter motes environment entity_type minecraft:bee
pdraw particle emitter motes environment entity_radius 6
pdraw particle emitter motes motion ax entity_dx*0.01
pdraw particle emitter motes motion az entity_dz*0.01
```

粒子可以在发生碰撞之前先探测前方。一次求值可以同时查询多组探测，每组独立配置起点、方向、
模式与材质筛选，结果按声明顺序编号。

```mcfunction
pdraw particle emitter motes probe solid velocity 2
pdraw particle emitter motes probe solid direction "0,0,1" 3 material minecraft:iron_block
pdraw particle emitter motes motion r probe_matches
```

每组的尾随参数是一段自由文本，按 `key value` 成对解析，顺序任意：

| 参数 | 含义 |
| --- | --- |
| `origin x,y,z` | 相对粒子位置的起点偏移，默认 0,0,0 |
| `halfsize <格>` | 大于 0 时按该半宽的盒体扫掠，避免高速或体积大的粒子漏检，上限 2 |
| `material <资源 ID>` | 只把该方块或液体算作匹配，其他表面仍会阻挡探测 |

多组探测用 `probe add` 追加（最多 4 组）、`probe set <序号>` 替换、`probe remove <序号>` 删除、
`probe clear` 清空。不带序号的 `probe` 作用于第 0 组。

```mcfunction
pdraw particle emitter motes probe add solid velocity 3 origin "0,0,0" halfsize 0.3
pdraw particle emitter motes probe add solid direction "1,0,0" 4 material minecraft:iron_block
```

第 0 组沿用不带序号的输入名，第 1 组起带序号：

| 输入 | 含义 |
| --- | --- |
| `probe_hit`、`probe1_hit` … | 该组是否有命中 |
| `probe_matches`、`probe1_matches` … | 首个命中的材质是否符合筛选，未指定材质时等同 `_hit` |
| `probe_distance`、`probe1_distance` … | 命中距离；未命中时等于该组配置的最大距离 |
| `probe_nx/ny/nz`、`probe1_nx…` | 世界坐标下的命中法线，未命中时为 0 |
| `probe_px/py/pz`、`probe1_px…` | 命中点世界坐标，未命中时为 0 |

方向可使用世界固定向量，也可沿粒子当前速度。范围为 0.01–32 格。沿速度探测时，静止粒子视为未命中。
探测本身不会移动粒子。停用时先移除使用这些输入的公式，再执行 `probe clear`。

#### 读取场景的粒子外观

`depth_fade` 以格为单位。粒子片段和已写入深度的场景表面越接近，该片段的透明度越低。
`distortion` 以像素为单位。贴图红、绿通道控制偏移方向，0.5 表示该轴无偏移，贴图透明度控制偏移幅度和混合。
两个参数都可设为 0 关闭。游戏命令和 API 的默认材质保持原有绘制路径。

```mcfunction
pdraw particle emitter smoke appearance depth_fade 1.25
pdraw particle emitter haze appearance style soft_dot
pdraw particle emitter haze appearance distortion 16
```

两种效果使用普通场景透明合成后的同一份深度与颜色副本。26.3 开启 OIT 时，场景材质在 OIT 合成后绘制，
可读取已合成的画面；场景材质之间按批次混合。透过不写深度的表面时，淡出使用深度缓冲记录的表面。
多个折射粒子读取同一底图。逐像素淡出可以与已有的 `soft` 方块表面淡出叠用。
Kotlin 和 Java 可设置 `ParticleVisual.sceneMaterial`。这些材质参数当前用于命令和 API。

#### 在多个配方之间共享力场

`pdraw particle field` 在当前维度管理力场。以命令执行位置作为中心，可重绑到固定坐标或实体；
风场方向和旋涡轴随绑定实体转向。每个配方可读取 `field_ax/ay/az`，得到所在位置上所有力场
加速度的和。单个场的范围为 0.05–128 格，强度为 -4 到 4。

```mcfunction
pdraw particle field wind courtyard 12 0.02 1,0,0
pdraw particle field attract beacon 6 0.04
pdraw particle field vortex vortex_demo 8 0.03 0,1,0
pdraw particle field turbulence turbulent_air 12 0.015
pdraw particle field anchor beacon entity @s
pdraw particle emitter motes motion acceleration vec3(field_ax,field_ay,field_az)
pdraw particle field list
pdraw particle field remove courtyard
```

吸引场的负强度会排斥。查询结果的坐标轴是世界轴；局部旋转的配方应按自己的坐标变换使用这些分量。
Kotlin 和 Java 可用 `ParticleForceFields` 配置锚点、方向、距离衰减与湍流种子。

#### 碰撞与落地

碰撞在客户端沿每刻的世界坐标线段查询方块或液体形状，停在首个碰撞面。锚点位移、局部旋转和公式
显式写入的坐标都参与检测。开启碰撞后，单刻位移超过 64 格会报错。

```mcfunction
pdraw particle emitter reset sparks
pdraw particle emitter sparks anchor fixed ~ ~6 ~
pdraw particle emitter sparks sample count 1
pdraw particle emitter sparks birth position vec3((rand()-0.5)*6, 0, (rand()-0.5)*6)
pdraw particle emitter sparks birth vy -0.55
pdraw particle emitter sparks birth vx 0.06
pdraw particle emitter sparks birth vz -0.04
pdraw particle emitter sparks collide solid
pdraw particle emitter sparks bounce 0.45
pdraw particle emitter sparks friction 0.65
pdraw particle emitter sparks appearance glowing true
pdraw particle emitter sparks appearance light 15
pdraw particle emitter sparks lifetime 240
pdraw particle emitter sparks rate 4
pdraw particle emitter sparks start
```

`solid` 按碰撞箱判定：台阶、栅栏、树叶都能挡住粒子。竖直方向优先处理，
`bounce` 控制反射后的法线速度，`friction` 控制切向速度的保留比例。

软粒子按粒子中心沿六个世界坐标轴到附近碰撞面的距离调整整颗粒子的透明度。拖尾复用粒子的贴图、颜色和
混合模式，传送时从新落点重新记录。客户端 `maxParticleGeometryQuads` 为拖尾和模型共用的每帧四边形预算，默认 200,000。参见[软粒子示例](../examples/commands/soft-fade.mcfunction)和
[丝带示例](../examples/commands/ribbon-trail.mcfunction)。

客户端配置 `particleLodDistance` 默认 `0`。设为正数后，该距离以内全量绘制，远处按距离平方反比
稳定抽样，到四倍距离停止绘制。模拟、寿命、碰撞与照明继续推进。
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
| `emitter <名称> rate <数量>` / `interval <刻数>` | 按时间发射时，每隔 interval 刻尝试出生 rate 颗 |
| `emitter <名称> spacing <0 或 0.05..64>` | 锚点每移动指定距离尝试出生 rate 颗；0 恢复按时间发射 |
| `emitter <名称> max_alive <数量>` | 单个播放实例的同时存活上限 |

出生、运动、变量、let、条件、寿命、速率和锚点支持热更新，保留现存粒子的年龄与状态。
寿命和外观只影响后续出生的成员；motion 和 variable 会影响已有成员。运行中不能增删 state 字段。
变量更新使用单独的小包，不会重新发送图片点阵；锚点更新与筛选操作也独立下发。
更换采样布局或图片前先 `remove`，再配置并启动。清除运动项可用 `unset motion <属性>`；持续加速度
或速度已存进粒子状态时，需另用 change/apply 或 motion 显式清零。

外观使用 `texture <资源名>` 或 `appearance <参数> <值>`，每条只设置一项。支持现有的 style、color、
scale、uv、size、size_world、billboard、spin、spin_space、additive、glowing、light、intensity、
spot、light_dir、light_range、light_falloff、align_velocity、stretch、soft、trail 与 `*_curve`。

光源属性和射灯示例见[动态光照](lighting.md#命令)。

曲线写法仍是 `0:1,40:0:ease_out`。修改 spin_space 前先设置 spin。

配置按维度保存在运行时内存中，重启或卸载维度后清空；建议在数据包的 load 函数里声明，技能函数只触发
burst 或更新参数。每维度最多 256 个配置，全服最多 256 个播放实例。有限寿命 burst 与停止后的实例自动回收；
无限寿命实例需显式 remove/delete。持续生成的实例会给后来进入维度的玩家下发当前声明，从头开始生成，
不补演历史拖尾；burst 只向触发时同维度在线的玩家播放，不对后来进入的玩家重复触发。

代码入口为 `ParticleSampling`、`ParticleImageSampler`、`ParticleRecipe`、`RecipeAnchor` 和
`ParticleSources.start`。[API 参考](api-reference.md#采样与运行时生成)
