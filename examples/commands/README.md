# 命令函数示例

将 `.mcfunction` 放进数据包的 `data/pdraw_test/function/`，执行 `/reload` 后使用：

```mcfunction
function pdraw_test:sine
function pdraw_test:image
function pdraw_test:conditional-shell
function pdraw_test:heart
function pdraw_test:rotating-sphere
function pdraw_test:tentacles
```

每次选择一个函数运行即可。锚点相对函数执行位置，命令方块执行时可以用
`execute positioned ~ ~2 ~ run function ...` 调整整幅图形的位置。

image 示例需要把项目的 `src/main/resources/icon.png` 复制为游戏工作目录的
`particleImages/pd-icon.png`。680×680 图标、stride=8 共 7225 点，max_alive=8192 可容纳完整图片，
rate=1024 在 8 刻内画完。替换图片后按有效像素数调整 stride、spacing、容量与寿命；
采样超过 8192 点会报错。

这些函数都是普通配置，使用同一套采样与公式命令。可以复制、更名、修改公式，或只调用末尾的 burst
重复播放。参数、坐标系与实体跟随用法见[分步配置粒子生成器](../../doc/particle-commands.md)。
