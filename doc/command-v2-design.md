# 粒子命令 v2 设计

本规范定义粒子命令 v2 的行为。各 Minecraft 目标共用语法。

## 1. 范围与命令入口

命令方块和数据包可以用一条命令定义并播放一个效果。常用字段使用固定段顺序和关键字补全。公式沿用现有脚本前端和配方求值器。完整声明默认覆盖同维度的同名定义，原地修改和追加编排有显式入口。

| 入口 | 用途 |
| --- | --- |
| `particle draw` | 绘制一组图元，可附带编排 |
| `particle emitter` | 定义发射器，可立即持续发射或爆发 |
| `particle template` / `particle use` | 保存发射器模板、覆盖字段后实例化 |
| `particle start` / `particle burst` / `particle stop` | 控制已有发射器 |
| `particle clear` / `particle delete` | 分别清理实例与删除命名对象 |
| `particle advanced` | 配方热更新、复杂世界输入、已有组的追加与编排 |

命令均位于 `/pdraw` 下。`particle field`、`camera`、`animation`、`texture`、`light` 继续使用现有入口。`/pdraw help` 保留，粒子帮助改用 v2 语法。

v2 发布时删除 `particle batch`、`particle group`、`particle add`、`particle program` 和旧分步 `particle emitter` 语法。各 Minecraft 目标共用命令语义和错误条件。公开 Kotlin/Java API 和编辑器文件格式沿用原契约。

## 2. 通用语法

- 表格和代码块中的 `<…>` 是参数，`[…]` 可选，`{…}` 可重复，`|` 表示择一。关键字使用小写。命名对象按大小写区分，名称匹配 `[A-Za-z0-9_.:-]{1,64}`。发射器、模板、组分别在当前维度查找。和命令关键字同名时可加双引号，例如 `advanced emitter "list" inspect`。补全应返回可直接执行的名称。
- 数字使用有限十进制数。刻数和数量必须是整数，禁止自动取整。非退化尺寸、步长和成员上限按各段校验。角度为度，距离为世界格，速度为格/刻，加速度为格/刻²。
- `<坐标>` 使用原版 `Vec3Argument` 的绝对、`~` 和 `^` 语义，包括绝对坐标的居中规则。`<向量>` 是三个普通数字，用于偏移、速度和轴。选择器沿用原版单实体参数，在实际命令源上检查权限并解析，支持 `execute as … at …`。
- `<公式>` 是一个参数。无空白的公式可以裸写，包括括号和逗号。含空白的公式使用双引号，引号内支持 `\"` 和 `\\`。解析后的公式最多 2048 字符。字符串与资源引用同样支持双引号。文字中的 `|` 转成换行。
- 除明确可重复的段外，同一段、字段或别名写两次均报错。多值参数按规定个数消费。`look`、形变选项和高级段按各自表中的键消费参数，再交回外层解析器。未知键、缺少参数和尾随字符均返回带具体位置的错误。
- `dryrun` 只出现在支持它的命令末尾。聊天、命令方块和数据包仍受原版各入口的输入限制。长定义可放进 `.mcfunction`，v2 不另外改变原版限制。
- 权限沿用现有粒子管理命令。建议由 Brigadier 注册固定入口和权限，共享段解析器负责声明尾部的解析与补全。构造坐标和实体参数时复用原版参数读取与建议，包括选择器内原版支持的引号。

## 3. 一次性绘制

### 3.1 声明与几何

```text
/pdraw particle draw <形状> [as <组名>] [group_pivot <坐标>] <几何>
  [<形状选项>] [look <外观字段>…] [life <刻>] [stagger <刻>] [enter <样式> <刻> [ease <缓动>]]
  {also <图元>} {then <动作>} [dryrun]

<图元> = <形状> <几何> [<形状选项>] [look <外观字段>…] [life <刻>] [stagger <刻>] [enter <样式> <刻> [ease <缓动>]]
```

语法块中的换行用于排版。`also` 图元各自使用自己的外观和寿命，按书写顺序合入同一组。`then` 作用于整组。`as` 指定可查找的组名，成功后覆盖旧组。省略 `as` 时每次执行独立绘制。省略 `group_pivot` 时以首个图元的 `at` 或 `from` 为轴心。首个图元是 `field at anchor` 时默认取命令源位置。`field at anchor` 总是在组轴心生成。

| 形状 | 几何 | 形状选项 |
| --- | --- | --- |
| `dot` | `at <坐标>` | — |
| `field` | `at <坐标> count <数量>` 或 `at anchor count <数量>` | `[velocity <向量>] [force <向量> [force_ticks <刻>]]` |
| `line` | `from <坐标> to <坐标> count <数量>` | `[taper [true/false]]` |
| `beam` | `from <坐标> to <坐标>` | `[thickness <数>] [taper [true/false]]` |
| `helix` | `at <坐标> radius <数> height <数> turns <数> count <数量>` | `[taper [true/false]]` |
| `circle` | `at <坐标> radius <数> count <数量>` | `[axis <平面>]` |
| `disc` | `at <坐标> radius <数> perimeter <数量> layers <数量>` | `[axis <平面>]` |
| `triangle` / `hexagram` | `at <坐标> radius <数> segments <数量>` | `[axis <平面>]` |
| `rect` | `at <坐标> width <数> height <数> density <数量>` | `[axis <平面>] [hollow [true/false]]` |
| `sphere` | `at <坐标> radius <数> count <数量>` | — |
| `cuboid` | `at <坐标> width <数> height <数> depth <数> density <数量>` | `[hollow [true/false] \| edges [edge_gap <刻>]]` |
| `text` | `at <坐标> text <文字> [size <字高>] [count <数量>]` | — |
| `sin` | `at <坐标> width <数> height <数> cycles <数> count <数量>` | `[axis <平面>]` |
| `expr` | `at <坐标> count <数量> <vec3 公式>` | — |

- 平面可选 `xy`、`xz`、`yz`。现有平面图元默认 `xz`，`sin` 默认 `xy`。`sin` 在第一个轴上绘制 `(u - 0.5) * width`，第二个轴上绘制 `sin(u * 2 * PI * cycles) * height`，`at` 是波形中心。`expr` 的结果是相对 `at` 的世界轴偏移。
- `sin`、`expr` 取 `i = 0..n-1`，`u = i/(n-1)`，单点时 `u = 0`。已有图元的点序、采样和默认几何选项沿用 `Draw`。文字采用旧文字点阵，默认字高 2、目标点数 1024，实际成员数以点阵结果为准。
- `life` 默认 `-1`，与旧绘图命令一致。显式寿命接受 `-1` 或非负 32 位整数。`0` 在首次寿命检查时过期。`stagger` 默认 0，按各图元内部点序安排出生。`field` 和 `dot` 不支持非零 `stagger`。`force_ticks` 缺省 `-1`，显式值为 `-1` 或非负整数。
- `cuboid edges` 要求三轴尺寸均为正。每条棱含端点均匀采样 `density` 个位置，共享角点只在第一次经过时生成。以底面最小 X/Z 角点为起点，依次画底边、同序竖边和顶边。第 `i` 颗粒子在 `i × stagger + e × edge_gap` 刻出生，`i` 是图元内从零开始的粒子序号，`e` 是从零开始计数的有新增粒子的棱序号。`edge_gap` 默认 0。仅设置 `edge_gap` 时每条棱的新增粒子同时出生。
- `enter` 从每颗粒子出生开始播放。`fade` 将透明度乘数从 0 缓动到 1，`grow` 改变尺寸乘数，`fade_grow` 同时改变两者。时长必须为正整数，默认使用 `ease_out_quad`，结束后乘数保持 1。预设与 `look` 自定义寿命曲线相乘，有限寿命到期时粒子照常销毁。
- 普通 `count` 为 `1..4096`，`text` 为 `1..8192`。分段、密度和层数沿用现有图元的上下限。在准备阶段按实际生成数校验整组最多 8192 个成员，包含待出生的成员。创建结果要么足额，要么失败。`beam` 的 `look` 只接受色源、`texture`、`additive`、`glow` 和寿命曲线。`thickness` 默认 0.1 格，`taper` 默认开启。两端太近、无法形成条带时报错。
- 命名组可接受后续 `advanced group` 操作，成员自然过期后保留组名。匿名绘制通过内部 ID 关联编排，无成员和待执行任务时自动回收。覆盖或清理命名组时，旧成员、待出生任务、编排和形变草稿一起清理。

### 3.2 外观 `look`

`look` 后各字段可换序，至少写一个。接受下表中的全部字段。外观字段用现有的视觉、颜色、寿命曲线校验。渲染路径无法表达的组合报错。

| 字段 | 输入与语义 |
| --- | --- |
| `color` | `<颜色>` 或 `expr <vec3 公式>`。沿用现有具名颜色和 `#RRGGBB` / `#RRGGBBAA`，井号可省略 |
| `gradient` | `<起色> <终色>`，用于 `draw`，沿用原图元渐变。与 `color` 互斥 |
| `scale` | `<数>`，粒子倍率 |
| `style` / `texture` / `model` | `square/soft_dot/line`、贴图引用、模型资源 ID。`style` 提供默认贴图，显式 `texture` 覆盖它。`model none` 清除模型 |
| `size` / `size_world` / `size_relative` | `size <边长>` 为世界等宽等高。`size_world <宽> <高>` 为世界尺寸。`size_relative <宽> <高>` 为相对尺寸。三者互斥 |
| `uv` | `<u> <v> <宽> <高>` |
| `spin` / `spin_space` | `<x> <y> <z>` 角度与 `world/local`，默认 `local`。`spin_space` 需要 `spin` |
| `glow` / `additive` / `billboard` / `light_only` / `align_velocity` | `[true/false]`，只写键表示 `true`。`glowing` 是 `glow` 的别名 |
| `light` / `light_intensity` | `<0..15>` 与 `<强度倍数>`。`intensity` 是 `light_intensity` 的别名 |
| `spot` / `light_dir` / `light_range` / `light_falloff` | `<外角[,内角[,边缘]]>`、`<偏航> <俯仰>`、`<距离>`、`<衰减曲线>` |
| `alpha_curve` / `size_curve` / `red_curve` / `green_curve` / `blue_curve` / `light_curve` | 一个曲线参数，`刻:值[:缓动],刻:值[:缓动],…` |
| `soft` | `<0..4>` 格，软粒子采样半径，0 关闭 |
| `trail` / `trail_sides` / `trail_linger` | `<0..32>` 刻、`<0 或 3..12>` 边、`<脱离后保留刻数>` |
| `trail_width` / `trail_color` | `<起宽> <末宽>`、`<起色或inherit> <末色或inherit>` |
| `stretch` / `depth_fade` / `distortion` | `<0..10>` 拉丝强度、`<0..16>` 深度淡出、`<0..32>` 场景折射。`stretch` 大于 0 且 `align_velocity` 省略时启用速度对齐 |

`draw` 的公式以各图元的最终点序提供 `i/n/u`，只接受这些采样变量、常量和允许的公式函数。准备阶段固定本次绘制的随机输入与求值结果，提交直接使用结果。`emitter` 和 `use` 的颜色公式在出生阶段求值，输入与同阶段其他公式一致。每颗粒子只求值一次，RGB 三分量必须在 `0..1`，保留未显式覆盖的 alpha。发射器同时用 `spawn set r/g/b` 写相同分量时，报字段冲突。已解析的外观字段按别名归一后合并，尺寸和色源各自为一个合并单元。

### 3.3 动作与时间轴

```text
delay <刻>
pivot <坐标>
follow <单实体> [offset <向量>] [local]
anchor fixed <坐标>
anchor entity <单实体> [offset <向量>] [local]
move <向量> <刻> [ease <缓动>]
move_offset <比例> <刻> [ease <缓动>]
move_path <刻> <x,y,z;x,y,z;…> [ease <缓动>]
rotate <轴向量> <角度> <刻> [ease <缓动>]
recolor <颜色> <刻> [ease <缓动>]
scale_by <倍率> <刻> [ease <缓动>]
scale_to <倍率> <刻> [ease <缓动>]
spin <轴向量> <度/刻>
pulse <峰值倍率> <半周期刻> [cycles <次数>]
fade_in <刻> [ease <缓动>]
fade_out <刻> [ease <缓动>]
fade_out_keep <刻> [ease <缓动>]
expression [local] <刻> <赋值块>
entity <句柄> <单实体>
variable set <变量> <数>
variable live <变量> <公式>
variable ease <变量> <公式> <刻> [ease <缓动>]
stop_continuous
destroy_after <刻>
retire [grace <宽限刻>]
morph slots <数量> <位置公式> {<形变选项>}
```

- `then` 按书写顺序记录，时间游标从 0 开始。只有 `delay` 推进游标，持续时长不会推进游标。同刻动作的顺序以声明为准，属性相互覆盖以及持续动作的停止沿用现有编排语义。
- `entity` 和 `variable set` 是初始绑定，在首个运行期动作和首次非零 `delay` 前声明，名称唯一。`variable live/ease` 复用已有热更新语义，要求已有运行期编排，且本条链中位于时间 0。已有组需要在更晚时刻执行热更新时，可由数据包定时执行一条追加命令。
- `pivot` 与 `anchor fixed` 等价。`follow` 与 `anchor entity` 等价，`local` 表示偏移随实体朝向旋转。`expression` 默认世界坐标，`local` 使用组锚点坐标。表达式和形变对几何写入的冲突沿用旧校验。
- `fade_in` 默认 `ease_out`，`fade_out` 和 `fade_out_keep` 默认 `ease_in`，其他可选缓动默认 `linear`。`pulse` 默认 `cycles -1`，表示持续，显式值接受 `-1` 或正整数。`retire` 默认 `grace 2`，仅作为链的末尾动作，沿用完成信号与宽限规则。
- 动作持续时长、延迟和宽限使用非负整数，`pulse` 半周期必须为正，旋转轴与转轴必须非零。组程序还须满足各动作自身的约束。累计游标、错峰延迟、动作结束和销毁时刻用宽整数预检，超出现有时间表示范围时拒绝。
- 错峰出生时，初始轴心和成员信息随生成生效，动作时间 0 在最后一颗成员就绪后开始。尚在播放的 `enter` 可以与组动作重叠。带动作的完整声明须保证各图元的有限寿命能覆盖时间 0。命令成功前完成动作校验，延时仅决定何时播放。组覆盖、清理或卸载后，旧任务通过组实例 ID 失效。

### 3.4 形变

`then morph slots` 为本次形变提供独立定义，紧跟目标槽位数与位置公式。形变选项可换序，每个键只出现一次。槽位数量、位置、各属性公式和各选项沿用现有 `MorphSlotFormulas` 与 `MorphOptions` 的校验和坐标语义。

```text
yaw <公式> | pitch <公式> | size <公式>
control1 <vec3 公式> | control2 <vec3 公式>
ticks <刻> | stagger <刻> | loop <次数>
max_speed <数> | max_accel <数> | converge <数>
path straight|quadratic|cubic
easing <形变缓动>
wobble none|bell|noise|vortex [strength <数>] [peaks <数>]
allocation nearest|index|angle
dynamic true|false | keep_velocity true|false | attributes true|false
```

位置、控制点和字段公式从同一份形变定义准备。默认 `ticks 40`、`stagger 0`、`loop 0`、`max_speed 0`、`max_accel 0`、`converge 0.05`、`path straight`、`easing linear`、`wobble none`、`strength 0`、`peaks 1`、`allocation nearest`、`dynamic false`、`keep_velocity false`。未给 `attributes` 时，存在 yaw、pitch 或 size 公式即启用属性写入。`loop -1` 表示无限往返。高级形变草稿在第 7 节说明。

### 3.5 绘制示例

```mcfunction
pdraw particle draw circle at ~ ~1 ~ radius 3 count 96 look color aqua size 0.15 glow
pdraw particle draw sin at ~ ~2 ~ width 16 height 2 cycles 1 count 200 look color #7fe0ff life 200
pdraw particle draw expr at ~ ~2 ~ count 200 "vec3(u*16-8, sin(u*2*PI)*2, 0)" look color expr vec3(u,0.4,1-u)
pdraw particle draw text at ~ ~2 ~ text "HELLO" size 2 count 512 look color gold life 200
pdraw particle draw circle as feet at ~ ~1 ~ radius 3 count 64 look color #ffcc66 glow then follow @p
pdraw particle draw circle as sigil at ~ ~1 ~ radius 3 count 96 look color aqua also hexagram at ~ ~1 ~ radius 2.4 segments 24 look color #ffeeaa then move 0 1 0 40 then spin 0 1 0 2 then delay 40 then fade_out 20
```

最后一行中的上浮和旋转同刻开始，40 刻后开始淡出，20 刻后移除整组。

## 4. 发射器

### 4.1 声明顺序

```text
/pdraw particle emitter <名字> <声明> [<动作>] [dryrun]

<声明> =
    [at <锚点>] [sample <采样>]
    {let <名字> <公式>} {spawn <输出>} {move <输出>}
    [filter <条件>] [kill <条件>] [orbit <vec3 公式>]
    [look <外观字段>…]
    [life <刻>] [rate <数量>] [interval <刻>] [max <数量>]
    [window <开始刻> [until <结束刻>]] [spacing <格>]
    [sim_range <格> [preserve true|false]] [follow true|false]
    [collide <碰撞>] [subemit <子发射>]
    {with <高级配置>}

<动作> = start [standard|instanced|synced] | burst [standard|instanced|gpu|synced]
```

没有动作时只保存配置。同名成功覆盖会清理旧实例，即使新声明只保存配置。对已保存的定义单独启动和停止使用第 6 节的控制命令。`<声明>` 各段都可省略，缺省值见下文。

### 4.2 锚点与采样

```text
at <坐标> [offset <向量>]
at <单实体> [offset <向量>] [local]
at emitter <名字>

sample count <数量>
sample range <起点> <终点> <步长>
sample grid from <向量> to <向量> step <数>
sample image file|resource <路径> [pixel_spacing <数>] [alpha <0..1>] [stride <数量>] [plane xy|xz|yz]
sample mesh file|resource <路径> [count <数量>] [seed <整数>]
sample text <文字> [count <数量>] [height <数>]
```

- `at` 省略时使用命令源的精确位置。显式坐标或选择器在执行定义时解析并绑定。固定锚点偏移使用世界轴。实体锚点绑定 UUID，`local` 使偏移和局部坐标随实体朝向变换，`offset` 省略时为零。
- `at emitter` 使用同维度目标发射器的锚点绑定，允许链接到只保存配置的对象。名称链接保持到以后解析，目标换锚点时更新依赖者。拒绝未知对象、自引用和环。目标锚点变化若与正在运行的依赖者模式冲突，整次修改失败。被引用的锚点须先解除引用才能删除。
- 省略 `sample` 使用 `count 1`。各路径最多 8192 个样本。`range` 的 `u` 是采样值，起点包含在内，终点在步长能到达时包含。步长为正，起点不大于终点。`grid` 的坐标是采样空间，各轴的下界不能大于上界，固定正步长作用于三个轴。其他采样的点序和属性沿用现有采样器。
- 文件从 `particleImages` 或 `particleMeshes` 读取，资源走当前服务端资源管理器。大小、路径、图片帧数和点数走现有读取上限。超限或读取失败会报错。图片默认 `pixel_spacing 0.05`、`alpha 0`、`stride 1`、`plane xy`。模型表面默认 `count 256`、`seed 0`。文字默认 `count 1024`、`height 2`。
- `pixel_spacing` 是图像像素间距。外层 `spacing` 只控制按距离发射。资源名、选择器和公式内部的字符不会被解析成段关键字。

### 4.3 公式与求值阶段

```text
let <名字> <公式>
spawn <vec3 公式>                        等同 spawn set position …
spawn set <属性> <公式>
move <vec3 公式>                         等同 move set velocity …
move set <属性> <公式>
filter <条件>
kill <条件>
orbit <vec3 公式>
```

- `spawn` 和 `move` 可分别写多次。属性集合由 `RecipeExpressions.OUTPUTS` 提供，包括 `x/y/z`、`vx/vy/vz`、`ax/ay/az`、`r/g/b/a`、`sc`、`glow`、`light`，以及 `position/velocity/acceleration`。向量按一次求值并拆分分量。同阶段的重复写入和向量与对应标量重叠均报错。
- 复用样本和配方输入。`i/n/u` 表示采样索引、样本数和采样参数，`t` 是发射器运行毫秒，`age/life/dt` 以刻计。属性和世界输入只在现有求值阶段可用。`let` 按声明顺序求值，可以引用前面的 `let` 以及初始变量和状态。锚点公式只能引用发射器级输入及其可求值的 `let`。
- 出生顺序沿用 `RecipeRuntime`。先应用出生公式，再按结果求值 `filter` 与初次 `kill`，通过后入场。在后续刻先求值 `kill`，再求值 `move` 和现有积分、碰撞。一个阶段的输出读取同一份输入快照。`select` 是用于高级编辑的筛选，与出生 `filter` 独立。
- 缺省 `spawn`、`move` 保留样本的默认属性，缺省 `filter` 为真，`kill` 为假，`orbit` 无偏移。`look` 使用第 3.2 节的可用字段。静态颜色写 RGBA，颜色公式写 RGB，各自与已声明的对应出生分量互斥。静态 `look scale` 与 `spawn set sc` 互斥。

### 4.4 运行参数与动作

| 段 | 值域与默认 |
| --- | --- |
| `life` | `-1` 或 `1..72000`。默认 80。`-1` 的粒子仍可被公式、碰撞或显式清理移除 |
| `rate` | `1..1024`。默认 8。是每次发射最多尝试的样本数 |
| `interval` | `1..72000`。默认 1。按刻发射模式的间隔 |
| `max` | `1..8192`。默认 4096。满额时等待空位，保留采样游标 |
| `window` | 起点 `0..72000`，终点须大于起点且不超过 72000。含起点，终点不含。默认从 0 开始，无终点 |
| `spacing` | `0` 或 `0.05..64`。默认 0。为正时按距离发射，`interval` 仅在改回按刻发射后生效。沿用世界步进预算 |
| `sim_range` | `0..512`。默认 0，即不限制。`preserve` 默认 `false`，出范围后保留模拟由 `true` 启用 |
| `follow` | 默认 `false`。为 `true` 时已有粒子继续使用当前锚点的坐标系。实体锚点本身总是跟随所绑定的实体 |
| `collide` | `none/solid/liquid/both/entity/all`，默认 `none`。可跟 `[bounce <0..1>] [friction <0..1>] [half_size <0..1>] [steps <1..4>]`，默认依次为 0.35、0.55、0、1 |
| `subemit` | `off` 或 `count <1..64> [life <1..72000>] [speed <0..2>] [scale <0.05..4>]`。默认 `off`，启用时省略的值使用 `SubEmit` 默认值 |

`start` 持续遍历样本，`burst` 按当前节奏遍历一轮，可能跨多刻，受发射窗口和存活上限影响。`burst` 要求 `spacing` 和 `sim_range` 为 0。动作默认 `standard`。`gpu` 只用于 `burst`，继续使用现有的有限寿命、无实体锚点和弹道约束。`instanced`、`synced` 的配方兼容性和容量限制各走现有运行时校验。非法动作或不兼容的组合在准备阶段报错。客户端已有的光影兼容行为沿用各平台渲染契约。

### 4.5 发射示例

```mcfunction
pdraw particle emitter fire at ~ ~1 ~ sample count 100 let radius 2 spawn vec3((rand()-0.5)*radius,0,(rand()-0.5)*radius) look color red size 0.15 glow life 40 rate 20 start
pdraw particle emitter meteor at ~ ~4 ~ sample count 200 spawn vec3((rand()-0.5)*8,0,(rand()-0.5)*8) move vec3(0,-0.05,0) look color orange life 60 rate 64 burst
pdraw particle emitter ribbon at ~ ~2 ~ sample count 2 let phase t/680+i*PI spawn vec3(2*cos(phase),0.7*sin(phase),0) move set position vec3(2*cos(phase),0.7*sin(phase),0) look trail 32 life 160 rate 2 burst
pdraw particle emitter sigil at @s sample image file "pd-icon.png" pixel_spacing 0.05 stride 8 filter "u > 0.2" max 8192 spacing 0.25 start
```

图片示例由玩家执行。将模组的 `icon.png` 放到游戏工作目录的 `particleImages/pd-icon.png` 后，移动玩家即可按距离发射。

## 5. 模板与覆盖

```text
/pdraw particle template <名字> <声明> [dryrun]
/pdraw particle template <名字> from emitter <发射器> [dryrun]
/pdraw particle use <模板> as <名字> {without <路径>} <声明> [<动作>] [dryrun]
```

模板是同维度的内存配置，存声明、参数和顺序。模板定义检查语法、可独立判定的默认值和公式，实体、名称链接及资源的实际解析发生在 `use`。模板的 `~`、`^`、省略的 `at` 和选择器都使用 `use` 的执行上下文。`from emitter` 保存当前配置的独立快照。已经绑定的固定坐标和实体保持原值，`at emitter` 保留名字链接。样本布局和公式配置可以复用，现有粒子、运行中的计时、状态变量的当前值和实例句柄不会复制。

`use` 先复制模板，再应用 `without`，最后应用显式覆盖并校验完整结果。成功后按 `emitter` 的覆盖规则保存目标配置。模板变更仅影响以后执行的 `use`。

| 覆盖项 | 合并规则 |
| --- | --- |
| `at/sample/filter/kill/orbit/window/collide/subemit/sim_range` | 整段替换。段内省略的参数用新段默认值 |
| `look` | 字段级替换。别名归一化。颜色与渐变、世界与相对尺寸分别作为一项替换 |
| `life/rate/interval/max/spacing/follow` | 对应标量替换 |
| `let`、`with variable/state/change` | 按名称替换，保留旧项位置。新项按声明顺序追加 |
| `spawn/move` | 按输出字段替换。向量与对应分量的交叉写入须先清除，确保写入顺序和公式求值次数明确 |
| `with environment/select` | 按子字段替换 |
| `with probe/on` | 按指令应用追加、设置或清除，保留声明顺序 |

`without` 接受上述段名和具名字段，例如 `without orbit`、`without let.radius`、`without spawn.position`、`without look.trail`、`without on`。整段清除恢复缺省行为，删除一项保留同段其他项。别名归一后校验路径，未知路径或重复清除报错，已缺省的合法路径可重复执行。合并后仍引用被删 `let` 或其他声明时，拒绝整个 `use`。

```mcfunction
pdraw particle template fire at ~ ~1 ~ sample count 100 spawn vec3(rand()-0.5,0,rand()-0.5) orbit vec3(sin(t/1000),0,0) look color red size 0.15 glow life 40 rate 20
pdraw particle use fire as fire_blue without orbit look color blue burst
pdraw particle use fire as fire_high at ~ ~6 ~ start
pdraw particle use fire as fire_preview burst dryrun
```

## 6. 控制命令与对象生命周期

```text
/pdraw particle start <发射器> [standard|instanced|synced] [dryrun]
/pdraw particle burst <发射器> [standard|instanced|gpu|synced] [dryrun]
/pdraw particle stop <发射器>
/pdraw particle clear emitter|group <名字>
/pdraw particle delete emitter|group|template <名字>
```

| 操作 | 行为 |
| --- | --- |
| `start` | 从已有配置启动一个持续实例。已有持续实例仍在发射时显式失败。允许旧实例扫尾，同时追踪其句柄 |
| `burst` | 从已有配置新增一次爆发。同名的持续实例和其他爆发保留 |
| `stop` | 停止该名字下全部实例继续发射根粒子。已存活粒子、尾迹及其后续事件按原运行时推进 |
| `clear emitter` | 移除该名字的所有实例、粒子、尾迹和当前实例可能产生的后续出生，保留配置，之后可用 `start` 或 `burst` |
| `delete emitter` | 先校验锚点引用，再按 `clear emitter` 清理并删除配置 |
| `clear group` | 立即清理成员、程序、待出生任务和形变草稿，同名空组保留原本建立时的轴心 |
| `delete group` | 按 `clear group` 清理，再删除组名 |
| `delete template` | 删除模板。已实例化的配置与实例保持独立 |

- 命名对象不存在时报错，已存在对象的 `stop` 和 `clear` 可安全重试。`clear`、`delete` 显式指定种类，允许不同种类同名。已经停止但包含无限寿命粒子的实例仍可按名字清理。
- 成功重建或删除后，旧 ID 的延迟任务和回调不得作用于同名新对象。关卡卸载和停服时清除本命令系统持有的模板、组、配置和挂起引用，不写入存档。
- 每维度最多 256 个命名组、256 个命名发射器配置和 256 个模板。活跃实例仍受运行时全服 256 个源及最多 8 个同步源约束。停止后仍需扫尾的实例计入活跃容量。完全移除的旧实例，在同一次替换容量检查中可用其名额。匿名组受现有服务端粒子和组资源约束，结束即回收。

## 7. 高级命令

### 7.1 配方配置和原地修改

```text
/pdraw particle advanced emitter list
/pdraw particle advanced emitter <名字> inspect
/pdraw particle advanced emitter <名字> edit {without <路径>} <声明> [dryrun]
/pdraw particle advanced emitter <名字> apply [where <条件>]
/pdraw particle advanced emitter <名字> remove_selected [where <条件>]
```

`edit` 至少提供一个配置变更，覆盖规则与 `use` 一致。它保留实例 ID、已有粒子、当前发射进度和运行时状态。采样布局、状态字段集合等不支持热更新的改动，要在实例停止并完全回收后执行，或用完整声明重建。校验需覆盖目标配置及该名称下所有活跃实例。`apply` 使用配置中已保存的 `with change`，只改已有粒子。`remove_selected` 删除选中的已有粒子。省略 `where` 时使用已保存的 `with select`，默认 `true`。模式不支持运行期编辑时返回错误。

`with` 段可以随完整声明提交，公式可以引用同一条命令所配置的变量、状态与探测。`edit` 也可使用这些段。

```text
with variable <名字> <数>
with state <名字> <数>
with select <条件>
with change <属性> <公式>

with environment entity_radius <0..32>
with environment entity_type any|<实体类型>
with environment neighbor_radius <0.05..16>

with probe add <探测参数>
with probe set <索引> <探测参数>
with probe remove <索引>
with probe clear
<探测参数> = <碰撞模式> (velocity | direction <向量>) <范围>
             [origin <向量>] [halfsize <格>] [material <资源 ID>]

with on birth <子配方>
with on death <子配方>
with on contact <子配方> [material <资源 ID>]
                         [decal <半径> [wrap]] [repeat|remove_parent]
with on clear
```

- `apply` 与 `remove_selected` 的条件在粒子当前状态上求值。`with change` 和出生输出使用同一属性集合。已有实例的变量和状态保留各自的更新规则。改变状态初值用在新出生的粒子上，已有粒子继续使用自己的状态。
- 探测使用 `ParticleProbe` 的范围、材质及数量校验，碰撞模式不能用 `none`。索引从 0 开始。`set` 只允许替换已有索引。`remove` 和 `clear` 后按剩余各组顺序重新编号，引用缺失探测字段的配方校验失败。旧版设置首组的用法在空列表用 `add`，已有首组用 `set 0`。
- 事件使用同维度已定义的子配方，在准备时冻结其采样和配方快照。子配方后续覆盖不影响已保存的事件定义。禁止自引用，子配方不得嵌套事件或子发射，并满足 `RecipeEmission` 的数量和寿命限制。碰撞事件默认单次触发，`repeat` 开启重复。`on clear` 清空原规则。

### 7.2 已有组

```text
/pdraw particle advanced group list
/pdraw particle advanced group clear_all
/pdraw particle advanced group <组名> create at <坐标> [dryrun]
/pdraw particle advanced group <组名> reset at <坐标> [dryrun]
/pdraw particle advanced group <组名> append <图元> {also <图元>} [dryrun]
/pdraw particle advanced group <组名> program [append|replace] then <动作> {then <动作>} [dryrun]
/pdraw particle advanced group <组名> morph configure slots <数量> <位置公式> {<形变选项>} [dryrun]
/pdraw particle advanced group <组名> morph start [dryrun]
/pdraw particle advanced group <组名> morph inspect
/pdraw particle advanced group <组名> morph reset
```

- `create` 仅创建空组，同名时报错。`reset` 覆盖成指定轴心的空组。`append` 仅允许尚未录制程序的组，保留轴心、已有成员及形变草稿。已有组可通过第 6 节清理。`clear_all` 删除当前维度的命名组及其状态。
- `program` 默认 `append`，要求已有成员或正在等待已登记的成员。首个下发前，连续编排共用预备游标。已有程序下发后，追加链从当前播放时刻开始，`delay` 为相对此次追加的偏移。`replace` 只允许替换尚未下发的编排，重置游标和未下发绑定。已下发的组要重做整段表演，使用同名 `draw` 重建。
- `morph configure` 保存完整形变草稿，`start` 以 `program append` 的时钟开始形变。`inspect` 查询草稿，`reset` 删除草稿。草稿和已经下发的形变各自独立。`then morph slots` 直接携带定义，可在同一条时间线中写多次。

## 8. 校验、提交与可观测结果

1. 解析成带参数位置的声明。先归一化别名，再检查重复项、段顺序和互斥项。解析器、帮助和补全共用字段定义。补全处理不完整输入，只读取当前维度中与命令相关的配置和名称。
2. 在服务端主线程准备操作。复制旧配置或模板，解析引用与资产，校验依赖、模式、取值、容量和完整动作链。一次操作只读一份采样或分析结果。绘制在准备阶段确定点数、可验证的公式结果、待出生计划和编排容量。发射器编译公式并验证可判定的输出，随未来世界或粒子状态变化的计算继续在运行时求值。
3. `dryrun` 执行适用的准备逻辑并回显。模板定义仅校验它在保存时可判定的内容。绘制回显成员数，动作操作回显动作或模式，发射回显原始样本数、速率和存活上限。它不修改注册表、旧实例、随机流、任务队列，也不发出粒子数据。
4. 准备成功后，一次提交更新名字映射和运行对象。须先保证所需容量，包含待出生成员和本次可回收的旧容量。应用完整变更后按先移除旧 ID、再发布新 ID 的顺序下发。提交阶段不重新选择实体、重新读资源或执行可失败的业务校验。校验失败时，旧对象、已运行实例与依赖者保持原状态。客户端断线或未来公式出错使用现有运行时错误处理。
5. 对客户端迟到、错峰出生和重复执行，继续使用现有按实例 ID 的同步和回收机制。同名重建、清理和卸载使用实例 ID 识别已过期的计划。组动作受现有成员、待发指令和单包容量约束。指令按协议分包，单包最多 512 条。配方按已有样本、公式、环境、探测和事件限制校验。

错误返回 0，消息包含出错的字段、目标名字或参数位置。`draw`、`advanced group … append` 返回实际接收的成员数，`program` 返回接受的动作数。其他成功操作和 `dryrun` 返回 1。帮助、错误、补全提示与反馈文案同步维护 `zh_cn`、`en_us`。

## 9. 迁移规则

| 旧能力 | v2 写法或迁移约定 |
| --- | --- |
| `batch kind=emitter`、分步发射器配置 | `emitter`。原 batch 默认会启动，迁移时要显式写 `start`。`mode=config` 使用无动作声明 |
| `anchor=fixed/entity/emitter`、`anchor.*` | 对应 `at` 的坐标、选择器或发射器链接。`offset`、`local` 放进锚点段 |
| `sample.*`、分步 `image/mesh/text` | 集中到 `sample`。图片 `sample.spacing` 改为 `pixel_spacing` |
| `birth.*` / `motion.*` | `spawn set` / `move set`。单个 position / velocity 输出可用简写。逐刻位置、出生速度、加速度、颜色和透明度都能迁移 |
| `where/kill/orbit/let/variable/state` | `filter/kill/orbit/let`，变量与状态用 `with`。配置好世界输入后可在同一条命令中引用 |
| `interval/emit_from/emit_until/simulation_range/follow/collision_*` | `interval/window/sim_range/follow/collide`。子发射及全部碰撞参数保留 |
| `select/change/apply/remove_selected`、`environment/probe/on/decal` | 用 `with` 定义配置，`advanced emitter` 原地编辑或执行筛选 |
| `emitter <名字> start/burst/stop/remove/delete` | 第 6 节控制命令。原 `remove` 对应 `clear emitter`，原 `delete` 对应 `delete emitter` |
| `*_instanced/*_gpu/*_synced`、`dryrun=true` | 动作后跟对应模式，`gpu` 仅用于 `burst`。`dryrun` 移到末尾 |
| `emitter copy` | `template <模板> from emitter <源>` 后 `use <模板> as <目标>` |
| 发射器 `keep=true`、`unset` | `advanced emitter <名字> edit` 和 `without`。需要复用配置时使用模板 |
| `batch kind=形状`、`particle add`、`group create/reset` | `draw`。同组组合图元用 `also`，跨多条命令建组用 `advanced group … create/reset/append` |
| `particle.*/shape.*`、`appearance.*` | 图元选项、`life/stagger` 和 `look`。旧相对 `size` 改为 `size_relative`，原 `size_world` 保持世界单位 |
| `group list/clear/remove` | `advanced group list/clear_all` 和 `delete group` |
| `program`、`batch kind=program` | 新定义内使用 `then`。已有组使用 `advanced group … program append/replace`。多个 `program.<序号>` 先按原序号排序再迁移 |
| `morph.*`、`program morph` | `then morph slots` 或 `advanced group … morph configure/start/inspect/reset` |
| `rainbow` | 改用 `color expr`。迁移带透明度的效果时用颜色公式配合独立 alpha 设置，发射器可用 `spawn set a`，图元可用恒定 `alpha_curve` |
| `particle batch help` / `help keys` | `/pdraw help` 的 v2 命令主题 |

旧 `batch` 的绝对坐标按精确数值读取。迁移到原版坐标参数时，如需保留整数 X/Z 的精确位置，写成小数，例如 `0.0 64 0.0`。相对坐标、偏移和方向向量沿各自原始语义迁移。

迁移以实际使用的参数和效果为准。`examples/commands/cube.mcfunction`、帮助中的命令和运行时自测均检查。包含 `execute … run`、函数宏、辅助函数和 `schedule` 的入口也纳入检查。`hell-eye` 的两个旧版本各保留一组 v2 回归输入，可共用同义的定义或函数，但两条开始、收尾、清理路径均需验收。

## 10. 实施与验收

1. 在共享 NeoForge 命令层实现段解析、补全、错误位置和准备模型。用独立测试分派器挂载 v2，避免与旧 `emitter <名字> start` 的含义冲突。
2. 接通 `draw`、组事务和时间轴，覆盖多图元、错峰、形变及已有组编辑。随后接通发射器、模板、引用、不同执行模式和生命周期。现有公式、图元、采样和编排行为以现有实现为准，本设计明确改变的命令行为按本规范验收。
3. 完成高级命令后迁移所有示例、自测和帮助。帮助由命令定义生成目录、说明和补全。同步中英文文本，废弃的帮助链接和键一起清理。`doc/particle-commands.md` 作为 v2 用户入口，移除旧 `doc/batch-command.md` 并改掉链接。README、指南和未改语法的命令页更新相关交叉引用。
4. 在同一发布切换移除旧入口、挂载正式 v2 入口并迁移对应文档。旧实现可以作为提交前的对照测试。发布产物只注册 v2 粒子语法。若落地需要新增网络语义，先确认跨版本影响，按网络契约更新注册版本及编解码测试。

| 验收项 | 通过条件 |
| --- | --- |
| 文法与补全 | 本文和公开帮助的命令逐条由真实分派器解析。覆盖引号、资源 ID、选择器、三类坐标、整数 X/Z 居中、后续段、未知字段、重复别名和非法模式 |
| 绘制与编排 | 组合图元成员数、正弦端点、单点输入、颜色公式、错峰后时间 0、显式延迟、局部表达式和连续形变均符合定义。错峰中清理或覆盖后旧粒子不会再出现 |
| 生命周期与失败 | 同名组、模板、发射器互不干扰。覆盖失败、超容量、模式不兼容、坏资产、最后一段错误和 `dryrun` 不改变旧状态。循环覆盖、停发扫尾、永久粒子清理和引用环都可复现 |
| 模板与配方 | 在不同位置和执行者下使用同一模板，坐标与选择器结果符合定义。覆盖、清除、快照、锚点依赖、事件配置和热更新通过配置与运行结果断言 |
| 示例效果 | `cube` 验证棱线绘制与逐粒子入场；`hell-eye` 验证开场、延迟持续、收尾与清理。配方示例验证出生速度、逐刻位置、透明度、样本数和非默认间隔 |
| 多版本门禁 | 独立核心 `build`，以及 1.21.1、26.2、26.3 的 `build runGameTestServer -PpdSelfTest` 均通过，各运行日志出现 `[PD-SELFTEST] PASS`。渲染和客户端补全另用实际开发客户端验收 |

复用 `RuntimeSelfTest` 的真实命令分派器。`ParticleCommandExamplesTest` 与 `HellEyeExampleTest` 保留对样本和运行结果的断言，其辅助解析与语法测试共用同一份声明测试输入，避免把另一个手写解析器当成命令入口的验收。
