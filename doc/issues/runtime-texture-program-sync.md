# 程序化贴图线程与动画成员同步

状态：部分实机通过。修复代码为`d4d79d4`，PrimalSpells构建源码基准`93ca1c4`；主/源码JAR已同步更新。贴图线程和原空程序WARN已消失，但既有粒子入组后的迟到接管、首次迟到程序的相位仍未通过。消费侧没有补enqueue、成员同步或时钟。

## 当前保证

- `registerTexture(name, pngBytes): Boolean`保留逻辑登记接受语义，GPU加载通过PD客户端主线程调度；同名第一次生效、在线广播和进服补发保留。异常路径关闭尚未移交的纹理/图像。
- 新名字PNG在服务端tick登记后，客户端实际得到64×16纹理，引用16×16子UV的白芯/金边正确上屏；错误线程警告为零。
- 组程序只投递给至少有一个可见成员的玩家；最初视距外成员收尾不再产生原空程序WARN。
- 四个常规场景70项机制检查、670帧零丢帧，视觉与日志门禁通过。不能据此宣称迟到成员路径全部正常。

## PD-PROGRAM-VISIBILITY-ADD：既有粒子入组后未接管

### 原因

`ParticleGroup.add(existingHandle)`只写`ParticleGroupData.memberIds`；`ParticleData.groupId`仍为出生时的值。`ServerParticleEngine.recheckVisibility`只按该groupId通知`ServerProgramDelivery`，客户端出生处理也只按包内groupId调用`onParticleSpawned`。既有成员虽然列在程序清单中，但进入视距时无法补挂；最初全部不可见的组也不会首次补发程序。

### 原生证据

PrimalSpells `PdWarningScene`在20t创建程序，24t将1024格外成员track到近处，44～48t观察同一UUID：

| 路径 | 应有位置 | 48t原生输出 |
|---|---|---|
| 部分可见，add(existing)的第1颗按全局i/n接管 | x=0 | x=-0.25、scale=1.25，仍是track输入和完整出生尺寸 |
| 最初全不可见，add(existing)后首次进入 | x=2 | x=2.25、scale=1.25，未收到/接管程序 |
| 出生时Builder.group(group.id) | x=4 | x=4，位置能接管；相位另有下述缺口 |

### 修改要求

PD内部维护既有成员加入/移除后的程序追踪与客户端绑定关系，覆盖部分、全迟到和重新进入。保留全局成员顺序与`i/n`、变量、实体绑定、原生运动和完成账本。不能要求消费侧改另一种出生方式掩盖add入口的缺陷；不能无条件强发远处成员或只隐藏诊断。

## PD-PROGRAM-LATE-CLOCK：首次迟到从零计时

### 原因

客户端arm计算`anchorOffset = anchorGameTime - receiveGameTime`、`startAnchor = anchorGameTime`；随后`programNowMs = (clientGameTime + anchorOffset - startAnchor) * 50`使原起点被消掉。收到补发程序时经过的时间被当成时钟差，导致首次接收从零推进；服务端保留deliveryAnchorTick仍不足以维持原相位。

### 原生证据

两个程序同在20t创建，同尺寸、同配方，尺寸输出为`0.3 + clamp01(age/100t)*0.3`。近处组立即收到，远处组出生带groupId、24t进入视距。48t远处成员x=4正确，但客户端scale=0.45000002，近处同配方基准=0.47625002；差约7t。44～48t均不能在一刻误差内对齐。

尺寸直接比较同帧客户端原生snapshot，消除世界单位、编辑器倍率与初始尺寸换算差异；不拿服务端scale直接当客户端倍率。

### 修改要求

PD内部明确原程序起点、当前服务端时刻和双端时钟差，或同步等价的原生elapsed；首次迟到、离开/重入、追加指令、变量阶段和完成账本必须一致。不能要求消费侧补本地时钟、负时间哨兵或手工扣掉网络延迟。

## 复验与恢复条件

在PrimalSpells运行：

```powershell
pwsh -File tools/capture/capture.ps1 -Offline -SkipBuild -NoVideo -Scene pd_warnings -TimeoutSec 240 -FinishTimeoutSec 45
```

最终证据在PS `build/capture/pd-runtime-late-membership-reproduced`：64帧零丢帧、5/8项检查通过，画面/清理通过且日志错误与原两类WARN为零；三项成员接管/相位检查失败，整体仍为FAIL。常规回归在`pd-runtime-sync-upgrade-audit`，其中四场70项检查、670帧零丢帧通过，最初探针失败不混作通过。

修复后上述8项原生检查全部通过；再运行`pd_warnings,concentrated_light_first_person,homing_light,wand_casting,pd_api`，不更改门禁阈值。补充专服、多玩家不同视距、预算恢复、重入、资源重载和完成清理。PS登记与精确产物哈希见其`docs/issues/pd-api-requests.md`及`docs/build-and-run.md`。
