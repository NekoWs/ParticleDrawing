# 折射透镜示例

一套可以直接在游戏里跑的最小折射（distortion）示例：棋盘墙前浮着一块圆形“透镜”，透镜左右缓慢横移，
镜内的棋盘格被放大并弯曲，镜外保持原样。边缘那圈小光点只用来标出镜片轮廓。

- `datapack/`：搭场地、放透镜和轮廓点的函数。
- 贴图不用单独准备：模组内置参考贴图 `particle:particledrawing:lens`（`distortion` 的示例贴图，
  红色通道控制横向偏移、绿色通道控制纵向偏移）。

## 用法

把 `datapack/` 整个目录复制到存档的 `datapacks/pd_lens/`，然后：

```mcfunction
/reload
/function pd_lens:setup    # 站在空旷处执行：清出一块场地、搭墙、把透镜摆在正前方并开始
/function pd_lens:stop     # 停掉透镜和轮廓点
```

`setup` 会以执行者为中心朝南（+Z）搭 19×12 的棋盘墙，墙在 4.2 格外、护栏 8 格远，
所以执行前确认身后上方有足够空间。放好后再微调视角即可。

## 拆开看

透镜就是一颗四边形粒子：

```mcfunction
pdraw particle emitter pd_lens_optics appearance distortion 32
pdraw particle emitter pd_lens_optics appearance size_world 5.3,5.3
pdraw particle emitter pd_lens_optics texture particle:particledrawing:lens
```

- `distortion 32`：单颗粒子的最大采样偏移，单位是屏幕像素；贴图透明度决定局部强弱。
- 内置贴图是径向的：它让每个像素朝粒子中心方向采样，所以镜内显示的是放大后的画面。
- 换成别的 `particle:` 贴图就能做热浪、水面波纹这类效果，红绿通道的方向约定见
  [粒子命令](../../doc/particle-commands.md#读取场景的粒子外观)。
- 想换大小或摆动幅度，改 `size_world 5.3,5.3` 和 `motion x 1.62*sin(t/820)`；
  轮廓点半径（2.63）要跟着镜片半径（半格数 2.65）一起改。
