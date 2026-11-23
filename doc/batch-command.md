# 批量命令 `pdraw particle batch`（设计定稿·待实现）

> 状态：设计已确认，尚未实现。最后更新：2026-11-21。

## 目标

一条命令完成一份完整配置：

- `kind=emitter`：发射器，一条命令建完并可直接开始发射；
- `kind=<形状>`：一次性粒子（dot/line/beam/图形等），形状 + 外观一次写完；
- `kind=program`：组编排时间线（move/spin/fade/expression/morph…），一次写完整条时间线；
- 键名全部带命名空间，与现有子命令一一对应；
- 先校验后落地，任一项出错整条不生效；
- Tab 补全键名与枚举值，`help` 列出键表；
- `dryrun=true` 只校验并回显解析结果，方便核对。

不在本期：跨存档导出/导入、替代现有任何命令（旧写法全部保留）。

## 命令形态

```
/pdraw particle batch <名字> kind=<emitter|形状|program> <键>=<值>...
```

- 名字：`kind=emitter` 时是发射器配置名；`kind=<形状|program>` 时是**组名**，组不存在时自动创建；
- 名字含冒号时用双引号：`batch "spell:fire" kind=emitter ...`；
- 位置值里的 `~` 按**执行命令的位置**解析，命令方块只需一块；
- `kind` 必填，没有默认值。

发射器（环形移动 + 下坠紫粒子）：

```mcfunction
pdraw particle batch ring_fall kind=emitter anchor=fixed anchor.pos=~ ~4 ~ sample.count=1 orbit="vec3(cos(t*0.0025)*4,0,sin(t*0.0025)*4)" appearance.style=soft_dot appearance.color=#b060ff appearance.size_world=0.14,0.14 appearance.glowing=true appearance.light=8 motion.vy=-0.03 emitter.lifetime=60 rate=2 interval=1 max_alive=200 mode=start
```

一次性粒子（组 `spark` 发一个球）：

```mcfunction
pdraw particle batch spark kind=sphere center=~ ~1 ~ radius=2 count=200 appearance.color=#ff4040 appearance.glowing=true appearance.size_world=0.08,0.08 particle.lifetime=30 particle.velocity=0,0.05,0
```

组编排（先清空时间线，再按序追加）：

```mcfunction
pdraw particle batch rune kind=program program.1=delay,20 program.2=fade_in,30,easeOut program.3=move,0,3,0,40,easeInOut program.4=spin,0,1,0,2.5 program.5=pulse,1.15,12,-1
```

## 键表

### 顶层键

| 键 | 值 | 说明 |
| --- | --- | --- |
| `kind` | `emitter` / 形状名 / `program` | 必填 |
| `keep` | `true` / `false`（默认） | 发射器：是否在旧配置上叠加；program：是否追加到旧时间线；一次性粒子无意义 |
| `mode` | `start`（默认）/ `burst` / `config` | 仅 `kind=emitter` |
| `dryrun` | `true` / `false`（默认） | 只校验并回显，不落地 |

### 外观键（全部要求 `appearance.` 前缀，无短别名）

`appearance.color`、`appearance.scale`、`appearance.style`、`appearance.texture`、`appearance.uv`、
`appearance.size`、`appearance.size_world`、`appearance.billboard`、`appearance.spin`、`appearance.spin_space`、
`appearance.additive`、`appearance.glowing`、`appearance.light`、`appearance.light_only`、`appearance.intensity`、
`appearance.alpha_curve`、`appearance.size_curve`、`appearance.red_curve`、`appearance.green_curve`、
`appearance.blue_curve`、`appearance.light_curve`、`appearance.spot`、`appearance.light_dir`、
`appearance.light_range`、`appearance.light_falloff`、`appearance.align_velocity`、`appearance.stretch`、
`appearance.soft`、`appearance.trail`、`appearance.trail_sides`、`appearance.trail_linger`、
`appearance.trail_width`、`appearance.trail_color`、`appearance.model`、`appearance.depth_fade`、
`appearance.distortion`

### 发射器键（`kind=emitter`）

| 键 | 值 | 对应现有 |
| --- | --- | --- |
| `anchor` | `fixed` / `entity` / `emitter` | `... anchor ...` |
| `anchor.pos` | `x,y,z` | `anchor fixed <pos>` |
| `anchor.entity` | 选择器 | `anchor entity <选择器>` |
| `anchor.emitter` | 配置名 | `anchor emitter <名字>` |
| `sample.count` | 整数 | `sample count` |
| `sample.range` | `begin,end,step` | `sample range` |
| `sample.grid` | 无值 | `sample grid` |
| `sample.bounds` | `x1,y1,z1,x2,y2,z2` | `sample bounds` |
| `sample.step` | 小数 | `sample step` |
| `sample.stride` | 整数 | `image stride` |
| `sample.plane` | `xy` / `xz` / `yz` | `image plane` |
| `sample.image` | 文件名 | `image file` |
| `sample.mesh` | 文件名 | `mesh file` |
| `birth.x` / `birth.y` / `birth.z` / `birth.position` / `birth.velocity` | 公式（双引号） | `birth <属性> <公式>` |
| `motion.x` … / `motion.acceleration` | 公式（双引号） | `motion <属性> <公式>` |
| `orbit` | `vec3(...)` 公式 | `orbit vec3(...)` |
| `let.<名字>` | 公式（双引号），可重复出现 | `let <名字> <公式>` |
| `where` | 条件公式（双引号） | `where <公式>` |
| `emitter.lifetime` | 整数刻 | 发射器出生粒子的寿命 |
| `emitter.emit_from` / `emitter.emit_until` | 整数刻（`until` 可 `-1`） | 发射窗口：从第几刻开始、到第几刻停止出生 |
| `rate` / `interval` / `max_alive` | 整数 | 同名子命令 |
| `spacing` | 小数 | `spacing <距离>` |
| `simulation_range` | 小数 | `simulation_range` |
| `follow` | `true` / `false` | `follow <布尔>` |
| `collide` | `true` / `false` | `collide` 开关 |
| `collision.size` / `collision.steps` / `collision.friction` | 小数 / 整数 / 小数 | `collision_size` / `collision_steps` / 摩擦（实现时按现有键对齐） |
| `subemit` | `off` / `on` | `subemit off` / 子项 |
| `subemit.count` / `subemit.lifetime` / `subemit.speed` / `subemit.scale` | 数值 | 同名子命令 |
| `unset` | 段名，可重复出现 | `unset <section>` |

### 一次性粒子键

| 键 | 说明 |
| --- | --- |
| `pos` | `dot`、`field` 的生成点 |
| `start` / `end` | `line`、`beam` 端点 |
| `center` | 图形中心 |
| `radius` / `height` / `turns` | 螺旋、圆、球等参数 |
| `count` / `perimeter` / `layers` / `segments` / `density` / `depth` / `width` | 各形状自己的分辨率/尺寸参数（按形状必填，见下） |
| `particle.lifetime` | 一次性粒子寿命（刻） |
| `particle.velocity` | 初速度 `x,y,z` |
| `particle.force` / `particle.force_ticks` | 持续受力和时长（`field` 类） |
| `particle.stagger` | 错峰刻数 |

形状与必填键：

| 形状 | 必填键 |
| --- | --- |
| `dot` | `pos` |
| `field` | `pos` + `count`；`anchor=true` 时不需 `pos` |
| `line` | `start`、`end`、`count` |
| `beam` | `start`、`end` |
| `helix` | `center`、`radius`、`height`、`turns`、`count` |
| `circle` | `center`、`radius`、`count` |
| `disc` | `center`、`radius`、`perimeter`、`layers` |
| `triangle` / `hexagram` | `center`、`radius`、`segments` |
| `rect` | `center`、`width`、`height`、`density` |
| `sphere` | `center`、`radius`、`count` |
| `cuboid` | `center`、`width`、`height`、`depth`、`density` |

### 组编排键（`kind=program`）

时间线动作按 `program.<序号>=<动作>,<参数...>` 排列，序号从小到大执行，允许跳号，重复序号报错。

| 动作 | 参数 |
| --- | --- |
| `delay` | `ticks` |
| `move` | `x,y,z,ticks[,easing]` |
| `move_offset` | `ratio,ticks[,easing]` |
| `move_path` | `ticks,points[,easing]` |
| `rotate` | `x,y,z,degrees,ticks[,easing]` |
| `recolor` | `color,ticks[,easing]` |
| `scale_by` / `scale_to` | `ratio,ticks[,easing]` |
| `fade_in` / `fade_out` / `fade_out_keep` | `ticks[,easing]` |
| `spin` | `x,y,z,degrees_per_tick` |
| `pulse` | `peak,half_period[,cycles]` |
| `pivot` | `x,y,z` |
| `follow` | `entity[,x,y,z[,local]]` |
| `anchor.fixed` | `x,y,z` |
| `anchor.entity` | `entity[,x,y,z[,local]]` |
| `variable.set` | `name,value` |
| `variable.live` | `name,formula`（公式用双引号） |
| `variable.ease` | `name,value,ticks[,easing]` |
| `expression` | `ticks,"code"` |
| `expression.local` | `ticks,"code"` |
| `stop_continuous` | 无 |
| `destroy_after` | `ticks` |
| `retire` | `[grace]` |
| `entity` | `handle,entity` |
| `morph.start` | 无（使用 `morph.*` 里的配置启动过渡） |
| `morph.reset` | 无 |

morph 配置键（`kind=program` 时与 `program.*` 一起写）：

| 键 | 值 |
| --- | --- |
| `morph.slots` | `count,"position"`（位置公式含空格，用双引号） |
| `morph.yaw` / `morph.pitch` / `morph.size` | 公式 |
| `morph.control1` / `morph.control2` | 公式 |
| `morph.ticks` / `morph.stagger` / `morph.loop` | 整数 |
| `morph.max_speed` / `morph.max_accel` / `morph.converge` | 小数 |
| `morph.path` | `straight` / `quadratic` / `cubic` |
| `morph.easing` | 缓动名 |
| `morph.wobble` | `kind[,strength[,peaks]]` |
| `morph.allocation` | `nearest` / `index` / `angle` |
| `morph.dynamic` / `morph.keep_velocity` / `morph.attributes` | 布尔 |

## 值语法

- 标量：数字、`true`/`false`、枚举、颜色 `#rrggbb` / `#rrggbbaa`、字符串；
- 列表：逗号分隔，允许逗号后带空格；
- 公式/代码：双引号包住，允许空格，`\` 转义（沿用 `ParticleCommandOptions.tokenize`）；
- 嵌套：`morph.slots=64,"p.draw(...)"` 这类值里的引号内逗号不参与外层切分；
- 重复键：`let.*`、`unset`、`program.<序号>` 按出现顺序；其余键重复即报错；
- 未给出的键：`keep=false` 时按 reset 后的默认值，`keep=true` 时保持原值。

## 执行流程（原子性）

1. 分词：拆 `key=value`，引号内不切；
2. 映射：映射到现有 `ParticleRecipeDraft` / 形状参数 / `CommandParticlePrograms` 动作；
3. 校验：必填键、枚举、数值范围、公式编译、时间线序号，全部不落地；
4. 失败：报键名、期望格式、公式出错位置，现有配置与时间线**完全不变**；
5. 落地：按 `keep` reset/叠加，写入配置与时间线；
6. 动作：`mode=start/burst/config`，`kind=program` 直接生效，一次性粒子直接生成；
7. 反馈：成功报名字、kind、样本数、`max_alive`（发射器）/动作数（program）/粒子数（形状）；`dryrun=true` 只回显解析结果。

## 补全与帮助

- 键注册表驱动的 Tab 补全：按前缀分组（`appearance.`、`sample.`、`motion.`、`anchor.`、`program.`、`morph.`），
  枚举键（`anchor=`、`mode=`、`morph.path=` 等）补全取值，缓动名补全现有缓动；
- `pdraw particle batch help [分区]` 输出键表与三条示例；
- 键表与补全、help 共用同一张注册表，避免三处各写一份。

## 落地拆分

| 步骤 | 内容 | 位置 |
| --- | --- | --- |
| 1 | 键注册表 + 分词/值解析/错误类型 | `platforms/neoforge-common/.../command/batch/` |
| 2 | 命令树、补全、help，挂到 `pdraw particle batch` | `ParticleDrawParticleCommands.kt` |
| 3 | 三种 kind 的落地函数（emitter / one-shot / program） | 共享核心，1.21.1 同源 |
| 4 | i18n（zh/en） | `lang/*.json` |
| 5 | 单测：键表、引号与逗号、错误原子性、序号排序、补全建议 | `shared/core` 测试 |
| 6 | 文档与示例 | 本文 + `doc/particle-commands.md` + `examples/commands/` |

## 已定决策

1. `kind` 必填；`<名字>`：发射器是配置名，其余是组名，组不存在自动创建；
2. 默认 reset + `mode=start`；`keep=true` 叠加、`mode=burst|config` 可改；
3. 公式用双引号；多值用逗号；全部键强制命名空间，不提供短别名；
4. 两套寿命命名：`emitter.lifetime` 与 `particle.lifetime`；
5. 一次性粒子的速度/受力/错峰放 `particle.*`；
6. 原子校验，失败不改任何现有配置；
7. program 采用 `program.<序号>=动作,参数...`，默认清空时间线，`keep=true` 追加；
8. morph 用 `morph.*` 配置 + `program.<n>=morph.start` 触发；
9. 键名补全 + `help` + `dryrun=true`；
10. 三平台共用同一份解析与落地代码。
