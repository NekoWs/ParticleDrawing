# 程序化贴图线程与动画成员同步

状态：未解决。消费侧源码基准为`1d1ace5`，仅登记缺陷；没有修改PD运行时或在PrimalSpells实现替代同步。

## PD-TEXTURE-THREAD：服务端登记触碰GPU

### 证据与调用契约

PrimalSpells当前client日志18:20:31、90行：`primalspells:concentrated_beam 注册失败：Rendersystem called from wrong thread`，线程为Server thread。`ParticleManager.registerTexture`公开契约允许随时登记，返回值表示逻辑名字/字节合法，不表示GPU资源已就绪。

该方法按物理Dist.CLIENT直接调用`ClientTextureSyncManager.loadLocal`，没有检查当前线程；后者同步进入`TextureCache.load → registerImage → DynamicTexture`。单机服务端线程同样处于CLIENT分发，因而越线程调用GPU。API仍返回true；进服贴图同步可能稍后成功，不能据最终上屏断言首次登记没有失败。

### 修改需求

- 保留公开签名`registerTexture(name, pngBytes): Boolean`与同名首次生效语义。逻辑登记、在线广播与晚加入补发保持服务端权威。
- 将本地GPU解码/登记交给PD内部客户端/渲染线程调度；专服不能加载客户端类。不得要求消费侧自行enqueue或实现贴图同步。
- 排队后重复登记、断线/资源重载与同名更新均需明确所有权；异常路径释放尚未移交给纹理系统的NativeImage。
- 文档区分“已接受登记”与“客户端资源就绪”；如需就绪回报，提供公开完成信号，而非让消费侧调用TextureCache。

## PD-PROGRAM-VISIBILITY：程序引用了未发送的成员

### 证据与原因

当前client日志18:20:51～18:22:40共35条`armed with zero known particles (1 ids)`。最小复现证明：视距外粒子在服务端创建成功，客户端按预期没有收到出生包；对该成员执行组缩放/retire后，客户端仍收到了程序并警告。

`ServerParticleEngine.broadcastSpawn`按视距和玩家预算裁剪出生包；`ParticleGroup.flush`却向`manager.getPlayers()`全部发送完整成员清单。`ClientAnimationProgramManager.arm`仅在初次接收时snapshot，未命中成员不建立PState；之后可见性补发也没有相应回补路径。因此既可能刷出本不应收到的空程序，也存在部分/迟到成员无法接管动画的缺口。

### 修改需求

- 程序下发与原生成员追踪保持一致；没有可见成员的玩家不应收到无意义程序或警告，不绕过客户端/服务端预算。
- 保留布局的全局成员顺序及`i/n`语义，不能简单裁剪成员列表后重新编号，导致不同玩家看见不同造型。
- 对合法迟到、部分可见、跨入视距与重新追踪成员补齐状态，按同一原生程序时间轴接入；不重播入场或回到满尺寸。
- 真正缺失/损坏的出生数据仍应诊断；不能仅删除/降级警告掩盖漏播。
- 完成/retire账本继续遵守实际可见参与者与离线兜底，避免空程序误完成、无法回收或远处成员泄漏。

## 原生复现与验收

在PrimalSpells运行：

```powershell
pwsh -File tools/capture/capture.ps1 -Offline -SkipBuild -NoVideo -Scene pd_warnings -TimeoutSec 240 -FinishTimeoutSec 45
```

`PdWarningScene`在服务端tick登记一张全新名称的现有PNG，再对1024格外的存活粒子执行正式组收尾；近处对照片元通过PD尺寸退场。没有替代渲染、计时器或同步实现。当前依赖应由日志门禁明确报失败，不能把4项造景/可见性/清理检查通过当作缺陷修好。

验收要求：两类警告均消失；贴图可被真正引用并正确取UV，单机首次进入/再次进入/资源重载及专服晚加入均正常。视距外、部分成员、迟到出生、预算拒绝/恢复、两名玩家不同视距均验证；保留`i/n`布局、当前相位与有限回收。升级后在PS复验`pd_warnings,concentrated_light_first_person,homing_light,wand_casting,pd_api`，不更改门禁阈值。原生证据在PS `build/capture/pd-warning-reproduced`：64帧零丢帧、4项造景/可见性/清理检查和画面门禁通过，日志门禁正确失败（两条错误线程和一条空程序警告）。用户原日志快照和完整恢复要求见PS的`docs/issues/pd-api-requests.md`。
