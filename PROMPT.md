# ParticleDrawing 函数对象脚本编写指南

函数对象（`.pdraw`）包含两段脚本：

- `setup`：对象初始化时执行一次（对象级）。
- `process`：每个粒子、每个求值时间点执行一次（粒子级）。

旧 `code` 字段已移除；当前工程格式为 `.pdraw v10`、`.pdrawc v9`，旧版本一律拒绝打开。

## this 对象

脚本通过唯一的保留标识符 `this` 访问上下文与粒子输出；`this` 本身不是值，不能单独使用（如 `x = this;` 抛错）。

### setup 环境（对象级）
只读字段：

- `this.count`：粒子总数（`fx.count`，最小 1）。
- `this.time`：当前时间（tick）。
- `this.duration`：动画总时长（tick；编辑端 `maxTick()`，播放端 `timelineLength()`）。
- `fx.vars` 中的变量：按变量名只读注入。

不可访问：`this.index`、`this.delta`、`this.uv`、`this.life`，以及所有输出字段。

### process 环境（粒子级）
只读字段：

- `this.index`：粒子序号（`0 .. count-1`）。
- `this.count`：粒子总数。
- `this.time`：当前时间（tick）。
- `this.delta`：距上次求值经过的秒数（连续播放为帧/步进间隔；seek、循环回绕、加载后首次为 `0`）。
- `this.duration`：动画总时长（tick）。
- `this.uv`：`vec2(uv_x, uv_y)`，网格 UV（列优先平铺到近正方形网格）。

输出字段（读写当前粒子输出）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `this.position` | vec3 | 世界坐标（随后叠加对象中心 `fx.center`） |
| `this.color` | vec4 | 各分量钳制到 `[0,1]`；`vec3` 赋值只改 RGB、alpha 保留 |
| `this.velocity` | vec3 | 速度 |
| `this.scale` | num | 缩放 |
| `this.glow` | num/bool | 读为 bool；写 `>0.5` 视为 true |
| `this.light` | num | 整数，钳制到 `[0,15]` |
| `this.life` | num | 寿命（tick；写入 `Math.round` 取整，负值/非有限视为 -1=无限，读为当前值） |

## 语法

- 语句以 `;` 结束（`{}` 块后无分号）；支持 `//` 与 `/* */` 注释。
- 赋值形式：
    - 变量：`name = expr;`
    - 数组元素：`arr[idx] = expr;`
    - 拆包/打包：`[a,b,c] = vec3`、`[a,b,c] = [e1,e2,e3]`
    - 向量分量：`v.x = expr;`
    - 输出字段：`this.position = vec3 | [x,y,z];`
    - 输出分量：`this.position.x = expr;`（`velocity`/`color` 同理）
- 声明：
    - `global name = expr;`（仅 setup；对象级共享，process 只读）
    - `static name = expr;`（仅 process；每粒子独立，首次执行初始化一次）
- 控制流：`if/else`、`while`、`do/while`、`for`、`break`、`continue`。
- 函数：顶层 `func name(p1, p2) { ... }`，支持递归，两阶段均可调用。
- 调试（仅 setup）：`print(...)`、`assert(cond, "msg")`。

## 类型

`num`、`bool`、`vec2`、`vec3`、`vec4`、`mat3`、`mat4`、`array`、`func`。

- 向量分量：`.x .y .z .w`；颜色别名 `.r .g .b .a`（`r/g/b` 即 `x/y/z`，`a` 即 `w`）。
- 矩阵：`mat3(r0,r1,r2)` / `mat4(...)` 行主序；`mat * vec` 矩阵变换，`mat3*mat3`、`mat4*mat4` 矩阵乘法。
- 数组：`[]`、`[a,b,c]`；方法 `push/insert/remove/slice/size/find/includes/sort/unique/reverse`。
- 无 `null`；未定义变量、越界访问、类型错误均抛错。

## 函数

- 构造/变换：`vec2` `vec3` `vec4` `mat3` `mat4` `translate` `scale` `rotate` `lookAt` `rotX` `rotY` `rotZ` `rotAxis`
- 向量：`dot` `cross`（仅 vec3）`len` `len2` `norm` `lerp`/`mix` `distance` `angle_between` `project` `reflect`
- 数学：`sin cos tan asin acos atan atan2 sqrt abs sign exp log ln floor ceil round fract pow min max clamp step smoothstep mod map_range remap int float bool`
- 噪声/随机：`noise` `fbm` `rand`（`fx.seed` 确定性）`random`（非确定）
- 缓动：`ease_linear` `ease_in_out` `ease_out_back` `ease_in_elastic`
- 集合：`unique` `reverse` `sort` `len`

## vars 变量

- 每项：`{ b: 数值基值, kf: [[tick, value, easing], ...] }`
- `kf` 非空时按关键帧插值，忽略 `b`
- 变量为数值，只读注入；脚本不能对其赋值（编辑器与播放端不支持变量表达式/跨变量引用）
- 变量名不得为 `this`，也不得与关键字/常量名冲突

## 示例

```
vars: { rad: { b: 3, kf: [] } }
setup:
  global arr = [];
  for (k = 0; k < this.count; k = k + 1) {
    arr.push(vec3(k, 0, 0));
  }
process:
  th = this.index / this.count * 2 * pi;
  this.position = arr[this.index] * rad;
  this.position.y = cos(th) * 2;
  this.color = vec4(sin(th) * 0.5 + 0.5, cos(th) * 0.5 + 0.5, 0.5, 1);
```

> 如果你是一个 AI 助手，在这之后请回答：“我已经阅读并理解了这份文档，请问你要我绘制一个什么样的粒子动画呢？”