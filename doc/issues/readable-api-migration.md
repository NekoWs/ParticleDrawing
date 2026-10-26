# 可读API的消费迁移缺口

状态：待修复。源码基准为`e49025f`。PrimalSpells按匹配的主/源码JAR核对，没有修改PD运行代码；以下入口暂停迁移，保留原配方。

## PD-READABLE-UNITS：常量单位减法与表达式文本不一致

`api/Units.kt`的`Time.binary`、`Length.binary`、`Angle.binary`对加减都计算常量`a+b`，但生成的文本使用实际操作符。因此常量校验/曲线API与表达式API会得到不同的值。

```kotlin
(16.ticks - 5.ticks).ticks          // 当前21，预期11
(3.blocks - 1.blocks).blocks       // 当前4，预期2
(90.degrees - 30.degrees).degrees  // 当前120，预期60
```

影响：无法直接把“寿命−退场时长”迁移为单位算术。`times/div`对常量Scalar也始终标记非常量，后续`requirePositive`会拒绝；大数换算与`requireWholeTicks`还需覆盖溢出，不能通过Int饱和转换接受超范围时长。

期望：常量值与表达式语义一致，有限运算保留常量性，不同量纲不能经`Scalar + Length`等入口隐式相加。覆盖减法、零/负结果、混合单位、常量乘除、溢出及常量/表达式等价测试。

## PD-POLYGON-EDGES：多边形轮廓实际在圆周采样

`api/ReadableShapes.kt`的`PolygonSpec.build`按`u=(edge+edgeT)/sides`插值角度，再取固定半径的sin/cos；边中点仍在圆周上，没有沿两顶点连线采样。

复现：`sides=3, radius=1.blocks, samplesPerEdge=3, initialAngle=0.degrees`。第一边中点当前为局部`(0.5,0,0.866...)`，预期两顶点中点`(0.25,0,0.433...)`。

影响：替换脚下三角形或蓄能菱框会改变轮廓。

期望：先生成边端点再线性采样，明确端点去重与部件索引。需支持原“每边24点、不重复下一条边首点”的模式。验证三角形/四边形边中点、指定采样数、跟随位置但不跟yaw、部件选择和容量拒绝。

## PD-EXIT-COMPLETION：退场仍按固定服务端时长删除

`api/ReadableExit.kt`的`ExitSpec.apply`在`AnimationScheduler.schedule(maxTicks)`后直接删除成员，没有接入客户端完成信号；`state`也按定时器到期，而不是实际合成外观结束。

复现：已有出生尺寸曲线的批次执行`exit { particleSize.to(0.0,over=8.ticks); preserveMotion(); removeWhenComplete() }`，对照删除任务和客户端零尺寸采样，并在延迟、暂停、中途重定向与重复退出时复验。

影响：无法直接替换已有清理余量的锯片/碎片退场；删除时刻变化，也不满足原需求“不要把Deferred.after搬进门面”的语义。

期望：接入现有完成账本/客户端回报，成员级尺寸端点参与账本，重定向更新终点，从当前合成值接续，停止幂等并保留速度/力。明确离线/卸载兜底及清理余量，不新增独立播放器或计时缓存。

验收：真实片元95%→5%连续下降，删除前已收束；延迟/暂停不截断，重定向不按旧终点删，重复停止不重复回调，场景收尾零残留。

## PD-EXPRESSION-OUTPUTS：动态外观和共享阶段缺少可读入口

`api/ReadableExpression.kt`的`ExpressionFrame.color`只接收常量Color，标量函数没有`pow`，也没有共享时钟/阶段句柄。`ExitState`仅描述退场任务，不能表示独立冷却阶段。

影响：脚下阵列包含动态RGB巡光、`pow(presence,0.75)`及两个共享相位的冷却进度，不能完整迁移。消费方保留旧字符串后端，没有用`Scalar.expr`拼回一套生成器。常量色的施法短闪已接入新入口，984个子帧与原配方的坐标/尺寸误差均为0。

建议提供：

- `color(red:Scalar,green:Scalar,blue:Scalar,alpha:Scalar)`。
- `pow(base:Scalar,exponent:Scalar/Number)`，映射现有标量后端。
- PD原生时间参考与阶段句柄：`start(duration)`、进度、出现/完成状态，不要求`±1e12`时间哨兵。
- 命名局部表达式/公共子表达式绑定，保留调试导出与错误来源。

验收：固定成员与时间样本下，三角法阵坐标、RGB、尺寸与双冷却逐点等价；保留出生隐藏、实时跟随、慢速自转、切杖交接和连续退场门禁。
