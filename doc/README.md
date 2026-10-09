# ParticleDrawing 文档

ParticleDrawing 是面向 [NeoForge](https://neoforged.net/)（Minecraft 26.2）的粒子效果库：播放网页编辑器
导出的 `.pdrawc` 动画，同时提供 Kotlin / Java API 在代码里绘制与编排粒子。

粒子由服务端创建与持有，客户端本地求值、逐渲染帧渲染。编排动画、寿命曲线、发射器这类声明式能力
只下发一次，运行期几乎不占带宽。

## 目录

| 文档 | 内容 |
| --- | --- |
| [getting-started.md](getting-started.md) | 引入依赖、第一个效果、核心概念 |
| [guide.md](guide.md) | 绘制形状、编排动画、单粒子、粒子集、发射器、缓动、播放动画、代码构建动画 |
| [api-reference.md](api-reference.md) | 按包分类的类索引 |
| [known-issues.md](known-issues.md) | 平台差异与已知限制 |

## 包结构

| 包 | 内容 |
| --- | --- |
| `work.nekow.particledrawing.api` | 公开 API：粒子管理器、绘制、粒子组、单粒子句柄、粒子集、发射器、特效、动画构建 |
| `work.nekow.particledrawing.animation` | `.pdrawc` 读取与验签、播放进度、客户端逐 tick 求值、服务端播放管理 |
| `work.nekow.particledrawing.animation.script` | 函数对象脚本：词法语法、运行时、标量字节码快路径、音频采样 |
| `work.nekow.particledrawing.core.client` | 客户端渲染引擎、桥接原版粒子、编排程序解释器、音频播放、贴图缓存 |
| `work.nekow.particledrawing.core.network` | 全部数据包与编解码 |
| `work.nekow.particledrawing.core.server` | 服务端权威粒子引擎、调度器、发射器登记、动画文件同步 |
| `work.nekow.particledrawing.lighting` | 动态光照注入与衰减函数 |
| `work.nekow.particledrawing.command`、`.config`、`.util` | `/pdraw` 命令、配置、工具函数 |

原版渲染注入（Mixin）在 `src/main/java/work/nekow/particledrawing/mixin`。
