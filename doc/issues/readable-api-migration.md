# 可读API消费迁移

状态：已验证关闭。源码核对基准为`1d1ace5`；PD构建、sourcesJar及59组407项测试通过。PrimalSpells使用匹配的主/源码JAR，完成持杖四层法阵和双冷却的类型化迁移。

## 修复与验收

| 接入点 | 当前保证 | 消费侧验证 |
|---|---|---|
| PD-EXPRESSION-NUMBERS | Scalar、单位、颜色、参数和局部轴心生成统一十进制文本；标量词法也接受科学计数法 | 0.00015、1e-9、正负大坐标按普通/指数文本执行结果一致；四层实际公式逐点比较 |
| PD-ANGULAR-SPEED | 内部弧度/毫秒口径统一；工厂、Angle/Time与乘时间只换算一次 | 1rad/s×1s常量与表达式均为1rad；90°/10t×0.5s为90°；原自转、巡光与呼吸不变 |
| PD-PHASE-CONTROL | phaseParameter支持运行时start/restart、显式complete/finish及reset；进度由PD表达式时间轴推进 | 原生客户端在组创建后开始/重启，再完成/重置，位置输出到达预期值；真实冷却入场/收尾与切杖门禁通过 |
| 单位与多边形 | Time/Length/Angle算术保持常量性，正多边形沿边线性采样 | 72点三角边中点、固定单位结果与原尺寸包络通过 |
| 原生尺寸退场 | 版本化客户端尺寸终点回报后清理，保留运动与重定向 | 原锯片8t线性外散，通用碎片原缓动；运动保留、重定向及早于兜底完成通过 |

## 已接入的编写方式

- `HeldRunePrograms`声明轮廓、角印、施放延迟与充能四层的命名局部量、动态RGBA和显式角速度。
- `WandRuneFx`只声明透明出生、cycle/entry/exit阶段和presence退场；`WandCastingFx`只通知真实玩法状态。
- 冷却层不再使用delayStart/End、±1e12哨兵或clockOffset变量。迟建层只声明装备年龄的常量初始相位，之后由PD组年龄推进，保持自转同步。
- 装备8t退场由变量完成账本回收；冷却保留原3/3.5t收尾及7t有限尾段。阶段进度和成员回收是不同契约，消费侧没有自建播放器、表达式生成器或时钟。

## 实机证据与边界

PrimalSpells命令：

```powershell
pwsh -File tools/capture/capture.ps1 -Offline -SkipBuild -NoVideo -Scene new_spells,wand_casting,pd_api,void_phase,sawblade_falling,spell_lighting -TimeoutSec 500 -FinishTimeoutSec 45
```

六场239项检查、1050帧零丢帧，机制、画面和日志门禁全部通过。四层7680个采样点覆盖大世界坐标、入场/呼吸、迟建层相位、提前结束与presence；坐标/尺寸/RGBA最大误差3.7253e-9，属于double世界坐标舍入。原有984子帧短闪轨迹与尺寸误差为0。

法阵首次入场/切杖无满形闪现，双冷却按原时序展开/收尾；装备退场95%→5%跨6帧、最大单帧下降23.9%、清零前0.9%。虚空隐藏和恢复、默认全亮零光源、锯片外散与双端零残留通过。

完整证据在PS `build/capture/readable-rune-phase-verified/`，包含原帧、meta、报告与正常退出日志。多人、暂停、预算裁剪、部分追踪及离线兜底仍需专项实机验证；单客户端门禁不代表这些范围已覆盖。新缺陷重新登记到本页或相应专项问题，不将已解决方案保留为未决需求。
