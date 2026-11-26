# 粒子命令

`/pdraw particle` 用于命令方块和数据包。聊天栏的命令带 `/`，`.mcfunction` 中省略。完整的参数顺序、取值和迁移规则见 [粒子命令 v2 规范](command-v2-design.md)。

## 命令绘制与数据包

```mcfunction
pdraw particle draw circle as sigil at ~ ~1 ~ radius 3 count 96 look color aqua size 0.15 glow
pdraw particle draw sin at ~ ~2 ~ width 16 height 2 cycles 1 count 200 look color #7fe0ff life 200
pdraw particle draw expr at ~ ~2 ~ count 200 "vec3(u*16-8,sin(u*2*PI)*2,0)" look color expr vec3(u,0.4,1-u)
pdraw particle delete group sigil
```

`as` 命名组，重复执行会完整替换同名组。省略 `as` 时各次绘制独立。`at` 或 `from` 用原版坐标，支持 `~` 与 `^`。`group_pivot` 覆盖组轴心。`also` 将下一个图元加入同一组，每个图元可有自己的外观、寿命和错峰设置。

常用形状包括 `dot`、`field`、`line`、`beam`、`helix`、`circle`、`disc`、`triangle`、`hexagram`、`rect`、`sphere`、`cuboid`、`text`、`sin` 和 `expr`。查看[图元参数](command-v2-design.md#31-声明与几何)和[外观字段](command-v2-design.md#32-外观-look)。

### 逐粒子入场

`stagger` 设置图元内相邻粒子的出生间隔。`enter fade`、`enter grow` 和 `enter fade_grow` 分别让每颗粒子从出生起淡入、长大或同时播放两者。填入时长，单位为游戏刻，可追加 `ease <缓动>`。

```mcfunction
pdraw particle draw cuboid as cube at ~ ~1.5 ~ width 2 height 2 depth 2 density 7 edges edge_gap 2 look color aqua size 0.1 glow stagger 1 enter fade_grow 8
```

`edges` 只绘制长方体的十二条棱。示例从底边画到竖边和顶边，每隔一刻生成一颗粒子，换边多等两刻。共享角点只生成一次。每颗粒子用八刻淡入并长到指定尺寸。用 `pdraw particle clear group cube` 清理当前粒子并取消剩余入场。

### 编排命令

```mcfunction
pdraw particle draw circle as sigil at ~ ~1 ~ radius 3 count 96 look color aqua also hexagram at ~ ~1 ~ radius 2.4 segments 24 look color gold then move 0 1 0 40 then spin 0 1 0 2 then delay 40 then fade_out 20
pdraw particle advanced group sigil program append then recolor #9fe0ff 10
```

上浮和旋转同刻开始，第 40 刻开始淡出。只有 `delay` 推进时间游标。`then follow @s` 让整组跟随当前执行实体，执行上下文必须有单一实体。已有组可追加编排或先准备形变草稿再启动。查看[动作表](command-v2-design.md#33-动作与时间轴)和[已有组](command-v2-design.md#72-已有组)。

### 复用原版粒子贴图

用 `look texture particle:minecraft:end_rod` 引用粒子贴图。`#N` 选固定帧，`@FPS` 设循环帧率。资源包只提供外观，运动由本模组控制。

## 发射器与模板

```mcfunction
pdraw particle emitter fire at ~ ~1 ~ sample count 60 spawn vec3(rand()-0.5,0,rand()-0.5) move vec3(0,0.06,0) look color orange size 0.1 glow life 30 rate 6 start
pdraw particle stop fire
pdraw particle burst fire
pdraw particle clear emitter fire
pdraw particle delete emitter fire
```

`emitter` 定义完整配方。省略 `start` 或 `burst` 时只存配置。同名完整声明成功后清除旧实例。`stop` 停止出生，已有粒子按寿命运行。`clear` 清理实例并保留配置，`delete` 连同配置一起删除。`dryrun` 校验整条声明并回显样本数。

`spawn` 默认写出生位置，`move` 默认写逐刻速度。用 `spawn set velocity …` 写出生速度，或用 `move set position …` 直接控制逐刻位置。`let` 按声明顺序求值，公式中的 `u` 是采样参数，`t` 是运行毫秒，`age` 和 `life` 是刻数。带空格的公式用双引号括起。

```mcfunction
pdraw particle template flame at ~ ~1 ~ sample count 100 let radius 2 spawn vec3((rand()-0.5)*radius,0,(rand()-0.5)*radius) look color red size 0.15 glow life 40 rate 20
pdraw particle use flame as blue_flame look color blue burst
pdraw particle use flame as preview at ~ ~6 ~ start dryrun
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

```mcfunction
pdraw particle emitter glow_arc at ~ ~2 ~ sample count 96 spawn "vec3(3*cos(u*PI),3*sin(u*PI),0)" look style soft_dot color #9fdcff size 0.14 glow additive strand 96 life 80 rate 96 burst
```

光带使用普通 `burst`。模型、粒子拖尾、场景材质、仅光源和子发射使用各自的外观路径。

### 实例绘制与 GPU 弹道

`start instanced` 和 `burst instanced` 适用于兼容的外观。`burst gpu` 使用现有 GPU 弹道，要求固定锚点、有限寿命和受支持的运动及外观。命令会校验配方是否适配执行模式。查看[执行模式](command-v2-design.md#44-运行参数与动作)。

### 服务端同步

`start synced` 和 `burst synced` 在服务端模拟，适合需要向玩家同步同一段世界输入与状态的效果。每个同步实例沿用同步配方的约束，服务端最多同时保留 8 个同步源。

### 读取场景的粒子外观

`look depth_fade <0..16>` 按深度距离淡出。`look distortion <0..32>` 在支持的平台使用场景折射。`look soft <0..4>` 按附近方块表面淡出。各平台限制见 [已知限制](known-issues.md)。

### 世界输入、碰撞和事件

`collide …` 设置碰撞类别及反弹、摩擦。`subemit …` 可配置死亡后释放的子粒子。`with environment …` 和 `with probe …` 为公式提供世界输入，`with on …` 可在出生、碰撞或死亡时运行子配方。查看[高级配置](command-v2-design.md#71-配方配置和原地修改)。

## 帮助

`/pdraw help` 打开目录，`/pdraw help cmd draw` 或 `/pdraw help cmd emitter` 打开单条命令说明，`/pdraw help examples` 打开可执行示例。使用 Tab 查看当前位置支持的段和已有对象。`/pdraw particle field` 保留原来的程序化粒子场入口。

## 自由镜头 /pdraw camera

给玩家切换一个由命令驱动的镜头，用于宣传片、巡演和自动演出；镜头姿态由客户端逐渲染帧向目标插值，
20 tick 的命令更新不会出现台阶。命令必须由玩家执行（数据包里用 `execute as @p run`）。

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
