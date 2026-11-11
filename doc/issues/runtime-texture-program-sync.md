# 程序化贴图线程与动画成员同步

本页记录 `bcd92dc` 的运行时修复验收，PrimalSpells 构建基于 `853a538`。PD 的主 JAR、源码 JAR 和 60 组
410 项测试通过，PrimalSpells 的无缓存构建通过。线程派发、成员同步和播放时钟由 PD 提供。

## 当前保证

| 接入点 | 运行时语义 | 原生验证 |
|---|---|---|
| 贴图线程 | registerTexture返回值表示逻辑接受；本地GPU解码/登记由PD客户端主线程执行，同名首次生效，保留在线/晚加入同步和异常资源回收 | 服务端登记全新名字，GPU得到64×16纹理，16px子UV白芯/金边正确上屏，无错误线程WARN |
| 视距裁剪 | 程序只发给至少追踪一个成员的玩家，保持完整成员顺序与全局i/n | 1024格外存活成员收尾，客户端无粒子且不再收到空程序WARN |
| 既有成员入组 | reassignGroup维护旧组/新组及玩家计数；add(existing)不再只写逻辑列表，已有程序重发仍保持原起点 | 部分迟到成员接管x=0，最初全不可见组进入后接管x=2；尺寸与同配方原生输出一致 |
| 原程序相位 | 载荷区分原始programStartGameTime与本次下发anchorGameTime；首次迟到和后续投递沿原起点推进 | 同刻创建、同配方近处/迟到程序，44～48t观测窗口内达到原对齐门槛，不重播入场 |

该验收使用载荷注册版本 `14`，当前版本见 [API 参考](../api-reference.md)。联机两端需要匹配构建。`ParticleData.groupId`可运行时更新，延迟成员可预挂组；客户端新程序接管时摘除旧程序状态。原生范围以本页实测为准，不由代码实现推断所有扩展场景已覆盖。

## 原生复验

在PrimalSpells运行：

```powershell
pwsh -File tools/capture/capture.ps1 -Offline -SkipBuild -NoVideo -Scene pd_warnings,concentrated_light_first_person,homing_light,wand_casting,pd_api,fireball_trail -TimeoutSec 500 -FinishTimeoutSec 45
```

六场84项检查、806帧零丢帧，机制/视觉/日志门禁全部通过，正常保存退出；完整PNG、meta、报告与日志在PS `build/capture/pd-late-program-sync-verified`。`pd_warnings`保留原8项断言，未调整门槛；旧失败证据另存在`pd-runtime-late-membership-reproduced`等目录。

48t原生snapshot：部分迟到成员x=0、scale=1.25；最初全部不可见的成员x=2、scale=0.625；出生带组的迟到成员x=4、scale=0.48000002，同刻同配方近处基准为0.47625002，差一刻且小于原0.006倍率门槛。比较同帧客户端原生值，避免把服务端scale当作世界尺寸倍率。贴图线程、空程序两类旧WARN均为零。

聚光/追踪、持杖法阵入场和有限退场、PD完成回报、火球拖尾alpha与双端清理继续通过。实际客户端上屏与数值断言分开判定，没有用日志正常代替功能正确。

## 扩展验证边界

专服晚加入、多玩家不同视距、预算裁剪/恢复、运行中改挂或再加入成员、延迟预挂、离开再入、资源重载、暂停，以及追加指令/变量阶段/完成账本的组合仍需专项实机。当前没有阻塞PS接入的未决项；版本/哈希与边界分别见PS `docs/build-and-run.md`和`docs/issues/verify.md`。
