# 粒子命令

`/pdraw particle` 用于命令方块和数据包。聊天栏的命令带 `/`，`.mcfunction` 中省略。完整的参数顺序、取值和迁移规则见 [粒子命令 v2 规范](command-v2-design.md)。

## 命令绘制与数据包

```mcfunction
pdraw particle draw circle as ring at ~ ~1 ~ radius 3 count 96 look color aqua
pdraw particle delete group ring
```

`as` 命名组，重复执行会完整替换同名组。省略 `as` 时各次绘制独立，反馈与 `/pdraw particle advanced group list` 会显示 `anon:…` 临时组名，复制这个名字即可清理单组。临时组在成员与待出生任务耗尽后自动回收。`at` 或 `from` 用原版坐标，支持 `~` 与 `^`。`group_pivot` 覆盖组轴心。`also` 将下一个图元加入同一组，每个图元可有自己的外观、寿命和错峰设置。

常用形状包括 `dot`、`field`、`line`、`beam`、`helix`、`circle`、`disc`、`triangle`、`hexagram`、`rect`、`sphere`、`cuboid`、`text`、`sin` 和 `expr`。查看[图元参数](command-v2-design.md#31-声明与几何)和[外观字段](command-v2-design.md#32-外观-look)。

`draw text` 和 `sample text` 由服务端 Java 进程按字体轮廓采样。多行文字用引号内的 `\n`，例如 `text "第一行\n第二行"`。若绘制中文等文字时提示缺少字形，请在服务端安装覆盖该文字的字体，并重启服务端，使 Java 的默认无衬线字体能找到相应字形。

### 逐粒子入场

`stagger` 设置图元内相邻粒子的出生间隔。`enter fade`、`enter grow` 和 `enter fade_grow` 分别让每颗粒子从出生起淡入、长大或同时播放两者。填入时长，单位为游戏刻，可追加 `ease <缓动>`。

```mcfunction
pdraw particle draw line as line from ~ ~1 ~ to ~4 ~1 ~ count 12 stagger 2 enter fade 8
```

直线上的粒子每隔两刻生成一颗，每颗用八刻淡入。用 `pdraw particle clear group line` 清理当前粒子并取消剩余入场。

### 编排命令

```mcfunction
pdraw particle draw circle as ring at ~ ~1 ~ radius 3 count 96 then spin 1 1 0 2
```

这条命令围绕倾斜轴旋转粒子组。`then` 中只有 `delay` 推进时间游标。查看[动作表](command-v2-design.md#33-动作与时间轴)、[已有组](command-v2-design.md#72-已有组)。

### 原地形变

`then morph slots` 在时间轴里把成员过渡到新的位置。槽位数写 `all` 时跟随组成员数，整组一起过渡；`count` 改成多少都不用同步改这里。默认槽位是世界坐标；加 `space local` 后槽位相对组轴心，换位置执行同一条命令时形状仍落在原地。

```mcfunction
pdraw particle draw text as hello at ~ ~2 ~ text "HELLO" size 2 count 4096 look style soft_dot size 0.08 then delay 20 then morph slots all "vec3(cos(u*2*PI)*3, sin(u*2*PI)*3, 0)" ticks 40 easing in_out_cubic loop 1 allocation angle space local
```

任意位置执行都会在文字中心排成圆环；不加 `space local` 时公式按绝对世界坐标解释。全部形变选项见[形变](command-v2-design.md#34-形变)。

### 文字变文字

`then morph target` 直接接一处点源：文字、图片、模型、已有组或命名点集。目标在形变开始时求值，不需要把目标坐标写成公式。

```mcfunction
pdraw particle draw text as hello at ~ ~2 ~ text "HELLO" size 2 count 512 look style soft_dot size 0.08 then delay 20 then morph target text "WORLD" size 2 ticks 40 easing in_out_cubic loop 1 space local
```

`space local` 让目标贴着当前组轴心，换位置执行同一条命令也能原地变形；`target group <组名>` 取另一组当前的位置快照。文字、图片与模型只改位置，需要逐槽位外观或 `dynamic` 时仍用 `slots`。

点集把采样结果存起来，多处复用：

```mcfunction
pdraw particle pointset define word_b text "WORLD" size 2 count 512
pdraw particle pointset transform word_b scale 1.5 rotate y 20 as word_b_tilt
pdraw particle pointset combine word_b_tilt merge word_b as word_b_pair
pdraw particle pointset filter word_b_pair "x > 0" as word_b_right
pdraw particle pointset inspect word_b_right
```

点集存相对坐标，`at` 换到哪里都按消费方的位置落地；`pointset define` 的写法和形变目标相同，`group` 取该组相对自己轴心的形状。变换、合并、筛选与排序都会写成 `as` 指定的新点集，源点集不被修改；写成同名即原地更新。

同一个点集可以被三种消费者复用：

```mcfunction
pdraw particle draw pointset word_b_right at ~ ~2 ~ look color aqua
pdraw particle emitter word_ps sample pointset word_b_right life 20 rate 1
pdraw particle draw text as hello at ~ ~2 ~ text "HELLO" size 2 count 512 then morph target pointset word_b_right ticks 40 space local
```

删除用 `pointset delete <名称或模式>`。

### 程序模板与组内控制流

重复出现的 `then` 动作可以存成带参数的程序模板，再用 `call` 展开；模板参数用 `{名称}` 占位，可出现在公式、文字、数字与点源名称里：

```mcfunction
pdraw particle program define wave with rx 5 then delay {rx} then morph target text "W" size 2 ticks 20
pdraw particle draw text as hello at ~ ~2 ~ text "HI" size 2 count 64 then call wave rx 8
pdraw particle program inspect wave
```

模板在服务端展开成线性指令，客户端不需要新协议。`then if <条件>` 按展开时的参数、成员数 `n` 与重复序号 `r` 选一支执行，`then repeat <次数> then … end` 最多 256 次：

```mcfunction
pdraw particle draw line as branch at ~ ~1 ~ to ~1 ~1 ~ count 8 then if n > 4 then repeat 2 then delay 2 end else then delay 4 end
```

控制流有界：展开后最多 4096 条指令，`call` 最多嵌套 4 层；`dryrun` 会完整展开并校验但不创建组。模板按维度保存，用 `program list` 查看、`program delete <名称或模式>` 删除。

### 复用原版粒子贴图

用 `look texture particle:minecraft:end_rod` 引用粒子贴图。`#N` 选固定帧，`@FPS` 设循环帧率。资源包只提供外观，运动由本模组控制。

在 `look texture ` 后按 Tab，可以看到内置贴图、服务端已注册的贴图，以及客户端当前启用的资源包提供的 `particle:` 粒子贴图和 `sheet:` 图集。也可以直接输入路径开头，例如 `end` 会提示 `particle:minecraft:end_rod`。在完整粒子名后输入 `#` 可列出有效帧号，输入 `@` 可选择常用帧率。切换资源包或按 F3+T 后会刷新本地候选。联机时，贴图能否显示取决于各玩家是否加载了对应资源。

### 预排动画图集

`look texture sheet:<命名空间>:<名称>` 从客户端资源包加载预排图集。将描述文件放在
`assets/<命名空间>/particle_sheets/<名称>.json`。例如下面的描述读取四列、两行的 320×180 贴图帧，
有效帧数为 8，按 30 fps 循环。PNG 按从左到右、从上到下排列，整图宽高都须是帧尺寸的整数倍。

```json
{"texture":"example:textures/particles/sequence.png","frame_width":320,"frame_height":180,"frames":8,"fps":30}
```

```mcfunction
pdraw particle draw dot at ~ ~2 ~ look texture sheet:example:sequence size_world 2 1.125 life 20
```

每张图集最多 256 帧和 16,777,216 像素。显示尺寸由 `size_world` 控制，可以在多个世界坐标放置。
每帧是粒子四边形的一张贴图，游戏深度会遮挡它。

## 发射器与模板

```mcfunction
pdraw particle emitter rising at ~ ~1 ~ move vec3(0,0.04,0) life 20 rate 1 start
pdraw particle stop rising
pdraw particle burst rising
pdraw particle clear emitter rising
pdraw particle delete emitter rising
```

`emitter` 定义完整配方。省略 `start` 或 `burst` 时只存配置。同名完整声明成功后清除旧实例。`stop` 停止出生，已有粒子按寿命运行。`clear` 清理实例并保留配置，`delete` 连同配置一起删除。`dryrun` 校验整条声明并回显样本数。

`spawn` 默认写出生位置，`move` 默认写逐刻速度。用 `spawn set velocity …` 写出生速度，或用 `move set position …` 直接控制逐刻位置。`let` 按声明顺序求值，公式中的 `u` 是采样参数，`t` 是运行毫秒，`age` 和 `life` 是刻数。带空格的公式用双引号括起。

```mcfunction
pdraw particle template colored_dot at ~ ~1 ~ sample count 1 look color red life 20
pdraw particle use colored_dot as blue_dot look color blue burst
```

模板中的坐标和选择器在 `use` 时按当时的命令源解析。用 `without orbit`、`without look.trail` 等清除继承值。要在原有效果上修改参数，可用 `advanced emitter <名字> edit …`。需要复用所有设置时可用 `template <名字> from emitter <发射器>` 保存快照。

### 采样与发射窗口

`sample count` 在 0 到 1 之间取参数，`sample range` 以指定步长取值，`sample grid` 以给定边界和步长取点。图片和 OBJ 可从工作目录的 `particleImages`、`particleMeshes` 读取，也可用 `resource` 引用数据包资源。每个发射器最多 8192 个样本，超限会报错。图片用 `pixel_spacing` 调整像素间距，独立的 `spacing` 控制按锚点移动距离发射。

`window 10 until 240` 在第 10 刻开始发射，第 240 刻停止。`interval` 设按刻发射的间隔，`rate` 控制每次最多尝试的样本数。样本量大于 `max` 时满额等待空位。`burst` 遍历一轮采样，可以跨多刻。

### 连续光带

`strand <每条样本数>` 放在 `look` 之后、`life` 之前，按样本顺序把相邻点接成光带。总样本数
必须能被每条样本数整除，`strand 0` 恢复独立粒子。每条光带按相邻采样点逐帧更新位置、
宽度和颜色。纹理沿宽度采样中心截面，`style soft_dot` 提供柔边。滤掉或删除中间样本时，
光带在缺口处断开。

可用普通 `burst` 绘制光带。需要高帧率时，给光带加上 `glow additive`，并使用 `burst instanced`。GPU 在渲染帧中插值端点、按相机方向展开带面。位置、颜色和状态公式仍每刻在 CPU 求值。`glow` 只让粒子保持全亮，默认不会照亮地形；要投光再显式写 `light`。

```mcfunction
pdraw particle emitter band at ~ ~2 ~ sample count 8 spawn vec3(u*3,0,0) strand 8 life 60 burst
```

### GPU 弹道

`burst gpu` 在 GPU 上计算固定锚点、有限寿命的弹道。下面让一颗粒子以固定速度向上发射，再按恒定加速度落下。

```mcfunction
pdraw particle emitter falling_dot at ~ ~2 ~ sample count 1 spawn set velocity vec3(0,0.1,0) move set ay -0.006 look glow additive life 32 burst gpu
```

逐刻表达式、热更新和连续带面可选 `burst instanced`。查看[执行模式](command-v2-design.md#44-运行参数与动作)。

### 服务端同步

`start synced` 和 `burst synced` 在服务端模拟，适合需要向玩家同步同一段世界输入与状态的效果。每个同步实例沿用同步配方的约束，服务端最多同时保留 8 个同步源。

### 读取场景的粒子外观

`look depth_fade <0..16>` 按深度距离淡出。`look distortion <0..32>` 在支持的平台使用场景折射。`look soft <0..4>` 按附近方块表面淡出。各平台限制见 [已知限制](known-issues.md)。

### 世界输入、碰撞和事件

`collide …` 设置碰撞类别及反弹、摩擦。`subemit …` 可配置死亡后释放的子粒子。`with environment …` 和 `with probe …` 为公式提供世界输入，`with on …` 可在出生、碰撞或死亡时运行子配方。查看[高级配置](command-v2-design.md#71-配方配置和原地修改)。

## 按名字批量清理

`*` 匹配零个或多个字符，`?` 匹配一个字符。模式匹配整个名字并区分大小写，`.`、`:` 等字符按字面比较。一条命令只在指定类型、当前维度内操作。`unbind` 只操作执行命令的实体。带通配符的命令返回匹配数量，未匹配时返回 0。

| 命令 | 效果 |
| --- | --- |
| `/pdraw particle delete group anon:*` | 清理所有匿名组及其待出生任务 |
| `/pdraw particle clear group demo_?` | 清理匹配的粒子组，显式命名的组保留名字和初始轴心 |
| `/pdraw particle stop trail_*` | 停止匹配发射器的新粒子，已有粒子自然消散 |
| `/pdraw particle clear emitter trail_*` | 清除匹配发射器的实例和粒子，保留配方 |
| `/pdraw particle delete emitter trail_*` | 清除匹配发射器及其配方 |
| `/pdraw particle delete template demo_*` | 删除匹配模板 |
| `/pdraw particle unbind aura_*` | 解除当前执行实体上匹配的绑定 |
| `/pdraw particle field remove example:wind_*` | 删除匹配完整资源 ID 的力场 |

互相引用的发射器可在同一次删除中一起移除。若有未选中的发射器仍引用其中某个锚点，整次删除失败。`delete group *` 会删除当前维度中命令创建的所有组。

## 实体绑定与事件

用 `execute as <选择器>` 让实体执行 `pdraw particle bind <名称>`。每个实体维护自己的命名绑定。可以单独使用 `emit <模板>` 让发射器随实体移动，单独使用 `on <事件> burst <模板>` 在事件的位置爆发，或把两者写进同一个绑定。多个绑定可同时挂在一个实体上。

- `impact` 取投射物撞到实体或方块的碰撞点。 `death` 取生物死亡时的位置。其他模组可通过 API 提供自定义的命名空间事件。
- 事件默认只执行一次。加 `each` 可重复触发，加 `stop` 同时停掉**当前绑定**的持续发射。两者一起使用时写 `each stop`。
- 重复绑定同名配置会保留原配置。使用 `pdraw particle unbind <名称>` 解除绑定；已有粒子走完寿命。实体离开关卡也会自动解除绑定。

### 多箭矢拖尾与命中

这个例子用距离发射生成每支箭的拖尾，并在首次碰撞时爆发和停发。穿透箭的后续碰撞不会再次爆发。

先用聊天命令或脉冲命令方块执行以下三条，各执行一次。聊天栏命令需要加 `/`。发射器模板存在于当前维度的内存中，重启后需重新定义。两个模板都要用有限的粒子寿命，拖尾用 `spacing` 沿飞行距离取样，命中模板用 `burst` 的按刻发射参数。

```mcfunction
scoreboard objectives add pd_arrow_fx dummy
pdraw particle template arrow_trail sample count 1 look style soft_dot color aqua size 0.10 glow life 9 max 160 spacing 0.20
pdraw particle template arrow_impact sample count 16 spawn set velocity vec3(0.14*cos(2*PI*u),0.09,0.14*sin(2*PI*u)) move set ay -0.016 look style soft_dot color gold size 0.15 glow life 12 rate 16
```

让重复方块的箭头指向连锁方块。两个方块都设为无条件、始终活动。

| 顺序 | 方块 | 命令 |
| --- | --- | --- |
| 1 | 重复 | `execute as @e[type=minecraft:arrow,distance=..64,tag=!pd_arrow_fx_done,nbt={inGround:0b}] at @s store success score @s pd_arrow_fx run pdraw particle bind arrow_fx emit arrow_trail on impact burst arrow_impact stop` |
| 2 | 连锁 | `tag @e[type=minecraft:arrow,tag=!pd_arrow_fx_done,scores={pd_arrow_fx=1}] add pd_arrow_fx_done` |

记分板按每支箭保存绑定是否成功。第二块只给成功绑定的箭打标，减少重复扫描的工作。选择器覆盖附近所有普通箭，包括生物或发射器射出的箭。光灵箭可为 `minecraft:spectral_arrow` 加一组同样的方块。若只想处理地图脚本生成的箭，在第一块的选择器中追加自定义来源标签。对于脚本生成且可能同刻命中的投射物，生成后应立即执行绑定命令。每刻扫描只能捕获执行时仍存在且可选中的箭。

## 帮助

`/pdraw help` 打开目录，`/pdraw help cmd draw` 或 `/pdraw help cmd emitter` 打开单条命令说明，`/pdraw help examples` 打开可执行示例。使用 Tab 查看当前位置支持的段和已有对象。`/pdraw particle field` 保留原来的程序化粒子场入口。

在参数后留一个空格，按 Tab 即可查看下一段。选择器内可继续补全，例如 `@e[ty`。`without look.` 会列出可清除的外观字段。`sample image file` 和 `sample mesh file` 补全服务端素材路径，`resource` 补全数据包中常用目录下的资源 ID，例如 `particle_images/` 和 `particle_meshes/`。带空格的名字会自动加引号。

## 自由镜头 /pdraw camera

设置玩家镜头的位置、朝向和视场角。客户端逐渲染帧向目标插值。命令必须由玩家执行（数据包里用 `execute as @p run`）。

```mcfunction
# 位置 + 朝向：MC 玩家视角口径，0 为 +Z，90 为 -X；pitch 正为俯视
execute as @p run pdraw camera pos 0 152 22 0 5 70 12

# 位置 + 注视点：自动反算偏航/俯仰角
execute as @p run pdraw camera look 0 152 22 0 154 0 70 12

# 恢复玩家视角
execute as @p run pdraw camera stop
```

`pos`/`look` 的末尾两个可选参数是 FOV（1..179，默认 70）与平滑速率（0..60，默认 12，0 表示直接跳到目标）。
客户端同时覆盖镜头的位置、朝向和 FOV。
