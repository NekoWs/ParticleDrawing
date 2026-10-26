# 可读API消费迁移

状态：3项已修复，回归以真实表达式求值器、单位混用和阶段公式覆盖。以下保留修复前证据，当前实现以源码为准。

## PD-EXPRESSION-NUMBERS（已修复）：生成文本的科学计数法与求值器不兼容

级别：语义错误，阻塞类型化动态法阵及大坐标局部表达式迁移。

`Scalar.of`、单位工厂以及`ExpressionPosition.local`直接使用`Double.toString()`。`ScriptExpr.evaluate`的词法处理不接受其中的指数格式，有时抛错，有时把`E`当内建常量而静默算错。

对真实JAR运行`ScriptExprKt.evaluate(Scalar.of(value).getExpr(), Map.of())`：

| 输入 | 生成文本 | 实际结果 | 应有结果 |
|---|---|---|---|
| 0.00015 | `1.5E-4` | -1.281718171540955 | 0.00015 |
| 16777217.25 | `1.677721725E7` | 未知变量E7 | 16777217.25 |
| 0.000000001 | `1.0E-9` | -6.281718171540955 | 0.000000001 |

PrimalSpells法阵新配方含0.00015、0.0018等频率；大坐标等价检查在原生`new_spells`场景触发E7异常。候选表达式迁移已撤回，没有在消费侧格式化/正则改写生成文本。原始证据保存在PS的`build/capture/readable-api-new-backend-audit/`及`build/verify/readable-api-audit/probe.txt`。

要求：PD统一数值字面量的生成与解析契约，覆盖Scalar、Time/Length/Angle、局部轴心与输出常量；不能让合法有限值被解析成变量或Euler常量。以真实表达式后端测试正负指数、极小频率、大世界坐标及正负数，常量值与求值结果一致。修复后恢复法阵坐标、RGBA、尺寸逐点等价检查。

## PD-ANGULAR-SPEED（已修复）：角速度乘时间的文本多除1000

级别：单位语义错误；现有自转频率保留原参数，暂不迁移该入口。

```kotlin
val angle = 1.radiansPerSecond * 1.seconds
// angle.radians == 1.0
// 实际生成文本为 (0.001*1000.0/1000.0)，求值为0.001
```

`AngularSpeed.radiansPerSecond`先把`expr`转为弧度/毫秒，`AngularSpeed.times(Time)`再次除1000；常量属性和表达式相差1000倍。由`Angle / Time`生成的角速度又采用另一种口径，必须一起统一。

要求：明确内部量纲并只换算一次；`radiansPerSecond`、`degreesPerSecond`及`Angle/Time`三条路径与tick/ms/sec混用逐点一致。覆盖常量/动态年龄两种时间，不能只检查`angle.radians`而不执行生成文本。复现输出同PS的`probe.txt`。

## PD-PHASE-CONTROL（已修复）：阶段仍只有固定时间偏移

级别：能力缺口；动态双冷却保留原字符串后端。

新增`ExpressionFrame.phase/start`只接受构建时的正常量时长与非负常量偏移，没有可更新阶段句柄。它可以描述装备后固定3.6t开始的阶段，不能表达施法后才确定的双冷却开始、提早结束、重入和共用装备相位。因此仍无法移除`delayStart`、`delayEnd`、`clockOffset`等手动时间字段及±1e12哨兵。

期望提供组原生`phaseParameter(name)`一类句柄，由PD管理`start(duration)`、结束/重启、progress/started/completed；同一组时间轴可被多个输出复用，保留暂停、客户端插值与完成账本，不另建播放器。具体命名由PD决定。

验收：法阵持续自转不回跳；任意时刻施法可立即展开双冷却，重复施法时旧提示连贯退场；卸下法杖、虚空、死亡及维度变化不残留。消费侧无需时钟缓存或特殊时间数值。

## 已验证与已接入

- Time/Length/Angle加减和常量倍率已修复；PS保留固定结果与原尺寸包络检查。
- 正多边形沿边采样已修复；72点三角轮廓用于持杖法阵透明出生，后续动态动画仍为原配方。
- 退场已走版本化客户端尺寸终点回报；PS锯片与通用碎片接入`exit`，保留八刻线性/原缓动、外散速度与受力，不再自行按时删除。暂停、多人追踪、离线兜底仍需专项复验。
- 动态RGBA、pow和local入口已提供；受数值文本缺陷影响，动态法阵尚不能迁移。现有常量色施法短闪保持原类型化实现。

## 本轮修复记录

- 数值文本：新增统一`numberLiteral`，`Scalar.of`、单位工厂、参数句柄、颜色、局部轴心与阶段变量都不再输出科学计数法；同时让`ScriptExpr`词法接受`1.5E-4`/`1.677721725E7`，并用真实求值器覆盖正负指数、极小频率与大坐标。
- 角速度：内部表达式统一为弧度/毫秒；`radiansPerSecond`、`degreesPerSecond`、`Angle/Time`与`times(Time)`只换算一次，常量和动态时间都按同一口径求值。
- 阶段：新增组原生`ParticleGroup.phaseParameter(name)`与`PhaseParameter`，支持`start(duration)`、`restart`、`complete/finish`、`reset`；`progress/started/completed/active/remaining`由表达式`t`和状态变量计算，不引入独立时钟或逐 tick 服务端执行。
- 退出验收仍需游戏内复验；本次只补了表达式/单位/阶段回归与既有全量测试。
