# ParticleDrawing 代码块编写指南

代码块是函数对象中的 `code` 字段，对每个粒子 `i = 0 .. n-1` 求值，通过赋值语句设置粒子属性。

## 语法
- 语句：`目标 = 表达式;`，用分号或换行分隔，顺序执行；可定义临时变量供后续语句使用。
- 内置变量：`i`（序号）、`n`（总数）、`t`（时间）、`pi`、`e`；`i/n/t` 只读。
- 输出属性（保留字，不可作变量名）：`x y z r g b a vx vy vz sc glow light`。
  后处理：颜色分量 clamp 到 `[0,1]`；`sc` 不小于 `0.01`；`glow == 1` 视为开启；`light` 取整并 clamp 到 `[0,15]`。
- 赋值形式：
    - 打包赋值：`[x,y,z]=[ex,ey,ez]`、`[r,g,b,a]=[er,eg,eb,ea]`、`[vx,vy,vz]=[evx,evy,evz]`
    - 向量拆包：`[x,y,z] = 向量`
    - 单属性：`sc = 2.0; glow = 1; light = 12;`
    - 临时变量：`th = acos(...)`
- 运算符优先级：`^` > 一元负 `-` > `* / %` > `+ -`；可用括号改变。
- 类型：标量、向量 `vec3`、矩阵 `mat3`；向量可 `.x/.y/.z` 访问分量；支持 `m * v` 矩阵变换。
- 函数：
    - 标量：`sin(1) cos(1) tan(1) asin(1) acos(1) atan(1) atan2(y,x) sqrt(1) abs(1) sign(1) exp(1) log(1) ln(1) floor(1) ceil(1) round(1) fract(1) pow(a,b) min(a,b) max(a,b) clamp(x,lo,hi) lerp(a,b,t) step(edge,x) smoothstep(e0,e1,x) mod(a,b) random() rand(seed)`
    - 向量：`vec(x,y,z) dot(a,b) cross(a,b) len(v) norm(v)`
    - 矩阵：`rotX(t) rotY(t) rotZ(t) rotAxis(axis,t)`
    - 坐标构造：`polar(r,a) sphere(r,th,ph) torus(R,r,th,ph)`

  注意：`atan2` 先 `y` 后 `x`；`mod` 为 GLSL 语义；`random()` 每帧变化，`rand(seed)` 由 seed 控制随机；`smoothstep` 在 `[e0,e1]` 内平滑阶跃。
- 不支持任何形式的注释。
- 不支持 if 等逻辑语句。

## vars 变量
- 每项：`{ expr: 表达式字符串, kf: [[tick, value, easing], ...] }`
- `kf` 非空时按关键帧插值，忽略 `expr`
- 变量可引用 `i/n/t` 和其他变量
- **禁止循环引用**
- 不能与输出属性同名

## 最佳实践
- 临时变量先定义后使用；不修改保留字。
- 颜色取值 `[0,1]`，`light` 取整数 `[0,15]`，`glow` 取 `0/1`；稳定外观用 `rand(seed)`，每帧变化用 `random()`。

## 示例
```
vars: { rad: { expr: "3", kf: [] } }
code:
  th = acos(1 - 2 * (i + 0.5) / n);
  ph = i * pi * (3 - sqrt(5));
  [x, y, z] = [rad * sin(th) * cos(ph), rad * cos(th), rad * sin(th) * sin(ph)]
```

> 如果你是一个 AI 助手，在这之后请回答：“我已经阅读并理解了这份文档，请问你要我绘制一个什么样的粒子动画呢？”
