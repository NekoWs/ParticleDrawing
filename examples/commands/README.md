# 命令函数示例

## 安装到测试存档

将本目录的 `.mcfunction` 文件复制到当前存档的数据包，目录结构如下。

```text
<存档>/datapacks/pdraw-test/
├─ pack.mcmeta
└─ data/pdraw_test/function/
   ├─ sine.mcfunction
   ├─ clear.mcfunction
   └─ ...
```

在 `pdraw-test` 根目录创建 `pack.mcmeta`。下方的格式范围覆盖 26.2 和 26.3。 [26.2 数据包格式](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-2) · [26.3 数据包格式](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3)

```json
{
  "pack": {
    "description": "ParticleDrawing command examples",
    "min_format": [107, 1],
    "max_format": [121, 0]
  }
}
```

开发客户端的存档位于 `run/<目标>/client/saves/`。进入存档后执行 `/reload`，用
`/datapack list enabled` 确认数据包已启用，再选择一个函数执行。若提示未知函数，检查 `function/`
是否为单数、数据包目录是否多套了一层，以及日志中是否有命令解析错误。

```mcfunction
function pdraw_test:sine
function pdraw_test:image
function pdraw_test:conditional-shell
function pdraw_test:heart
function pdraw_test:rotating-sphere
function pdraw_test:tentacles
function pdraw_test:mandala
function pdraw_test:torus-knot
function pdraw_test:mobius
```

在聊天栏执行时加前缀 `/`。锚点相对函数执行位置，命令方块执行时可以用
`execute positioned ~ ~2 ~ run function ...` 调整整幅图形的位置。
执行 `function pdraw_test:clear` 可以清除所有示例粒子并重置对应配置。

## 几何动态示例

| 函数 | 效果 | 配置名 |
| --- | --- | --- |
| mandala | 青色十二瓣玫瑰、金色双三角星、蓝色三重外环，分层反向旋转 | mandala、mandala-star、mandala-ring |
| torus-knot | 倾斜旋转的三叶环面结，沿曲线流动的粉蓝渐变 | knot |
| mobius | 绕中心旋转的莫比乌斯扭带，点阵呈现单面曲面的扭转 | mobius |

三组均存在 240 刻（12 秒），最后 30 刻淡出，一次触发即可。mandala 共 2040 颗，另外两组分别
960、1008 颗。站在图形侧上方更容易看出曼陀罗分层与曲面的立体关系。

改坐标只需修改函数里的 anchor fixed。曼陀罗的另外两层引用 mandala 锚点，运行后也可以直接绑定实体：

```mcfunction
pdraw particle emitter mandala anchor entity @p 0 2 0 false
pdraw particle emitter knot variable speed 0.0008
pdraw particle emitter mobius variable tilt 1.1
```

speed 使用弧度/毫秒，tilt 使用弧度；这些示例的公式每刻读取变量，更新时不会重新生成整组。
重播曼陀罗时需依次 burst 三个配置；重新运行整个函数则会清除旧效果并从头播放。

## 图片示例

image 示例需要把项目的 `platforms/neoforge-common/src/main/resources/icon.png` 复制为游戏工作目录的
`particleImages/pd-icon.png`。680×680 图标、stride=8 共 7225 点，max_alive=8192 可容纳完整图片，
rate=1024 在 8 刻内画完。替换图片后按有效像素数调整 stride、spacing、容量与寿命；
采样超过 8192 点会报错。

这些函数都是普通配置，使用同一套采样与公式命令。可以复制、更名、修改公式，或只调用末尾的 burst
重复播放。参数、坐标系与实体跟随用法见[分步配置粒子生成器](../../doc/particle-commands.md)。
