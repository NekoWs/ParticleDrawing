# 后台截图与录像

## 一键录制

在 Windows 下从仓库根目录运行。需要 Python 3.9 或更新版本、PATH 上的 FFmpeg 和 FFprobe，以及对应目标的 JDK（1.21.1 用 JDK 21，26.x 用 JDK 25）。

```powershell
python -B tools/recording/record.py `
  --world 'run/neoforge-26.3/client/saves/PD Command v2 Smoke' `
  --setup path/to/setup.mcfunction `
  --scene path/to/scene.mcfunction `
  --output build/recordings/cube-entrance.mp4
```

`--world` 指向任意已保存的单人存档。工具在临时开发配置中打开副本，使用独立的 Windows 桌面，不显示游戏窗口，也不抓取鼠标。`--java-home` 可指定 JDK，依赖已缓存时可加 `--offline`。

`--setup` 可指定拍摄前执行的命令，用于布景和设定视角。`--scene` 可指定预录后执行的命令，用于触发动画。两者都按文件顺序以本地玩家为执行实体，每行一条命令，支持注释。cube 取景脚本适用于空旷测试存档。

默认分辨率为 1280×720，进入场景后等待 3 秒，再预录 0.5 秒并在触发后录 7 秒。`--width`、`--height`、`--warmup`、`--preroll`、`--seconds` 可分别调整。每个渲染帧都会录入，成片以可变帧率保留帧间时间。实际帧率取决于游戏渲染、显卡读回与磁盘吞吐，工具结束后会打印实测帧率。MP4 为无声视频，录制期间需要额外磁盘空间存放原始帧。用 `--help` 查看完整参数。

## 开光影录制

使用已安装 Iris、兼容 Sodium 和光影包的 NeoForge 单人客户端。先构建同一 Minecraft 版本的 ParticleDrawing JAR，再指定实例目录、包名和成品 JAR。实例需已下载所需资源，并在 `.minecraft/versions` 下提供完整合并的版本 JSON。

```powershell
$instance = Join-Path $env:APPDATA '.minecraft/versions/26.2'
$modJar = Get-ChildItem 'platforms/neoforge-26.2/build/libs/particledrawing-26.2-neoforge-*.jar' |
  Where-Object Name -NotLike '*-sources.jar' | Sort-Object LastWriteTime -Descending |
  Select-Object -First 1

python -B tools/recording/record.py `
  --instance $instance --shader-pack 'BSL_v10.1.8.zip' --mod-jar $modJar.FullName `
  --world (Join-Path $instance 'saves/新的世界') `
  --setup path/to/setup.mcfunction `
  --scene path/to/scene.mcfunction `
  --output build/recordings/cube-bsl.mp4
```

工具会复制实例的模组、配置、选中的光影包和存档，替换副本中的 ParticleDrawing 后以离线单人模式启动。Iris 确认光影已生效后才开始录制。原实例及存档保持原样。开光影时仍在独立的隐藏桌面录制每个渲染帧。

## 单帧截图

`-PpdCaptureDir` 启用开发客户端的截图通道，同时关闭鼠标抓取。26.3 启动时也关闭 SDL 的窗口自动激活。
需要空闲时继续推进场景的验收存档，在开发客户端的 `options.txt` 中保留 `pauseOnLostFocus:false`。
常规构建不编译截图工具。

在仓库目录启动测试存档。

```powershell
$captureDir = (New-Item -ItemType Directory -Path 'build/visual-checks/frames' -Force).FullName
.\gradlew.bat -p platforms/neoforge-26.3 jar runClient -PpdQuickPlay=pdspotlight "-PpdCaptureDir=$captureDir"
```

在第二个终端用临时文件提交截图请求。文件内容使用字母、数字、连字符或下划线，不能与已有截图同名。
客户端在下一帧完成后把 PNG 写入同一目录。

```powershell
$captureDir = (Resolve-Path -LiteralPath 'build/visual-checks/frames').Path
$pending = Join-Path $captureDir 'capture.next'
[IO.File]::WriteAllText($pending, 'particle-demo')
Move-Item -LiteralPath $pending -Destination (Join-Path $captureDir 'capture.request')
```

只需保留自由鼠标时，可单独用 `-PpdBackgroundCapture` 启动开发客户端。

## 连续截帧与录像

轮询默认每 250ms 一次，适合抽查单帧。要录一段连续动作，用 `-PpdCaptureIntervalMs` 调小轮询间隔，
再按顺序快速提交请求；客户端每帧最多处理一张，实际帧率受 PNG 编码和磁盘写入限制。

```powershell
$captureDir = (Resolve-Path -LiteralPath 'build/visual-checks/frames').Path
.\gradlew.bat -p platforms/neoforge-26.3 runClient -PpdQuickPlay=pdlights "-PpdCaptureDir=$captureDir" -PpdCaptureIntervalMs=20
```

下面的循环在请求被取走时立刻提交下一张，文件按序号命名，方便之后用 ffmpeg 按日志时间戳合成视频。

```powershell
$captureDir = (Resolve-Path -LiteralPath 'build/visual-checks/frames').Path
for ($i = 0; $i -lt 500; $i++) {
    $request = Join-Path $captureDir 'capture.request'
    if (-not (Test-Path $request)) {
        $next = Join-Path $captureDir 'capture.next'
        [IO.File]::WriteAllText($next, ('take{0:d4}' -f $i))
        Move-Item -LiteralPath $next -Destination $request
    }
    Start-Sleep -Milliseconds 10
}
```

`latest.log` 里每个 `PD framebuffer capture saved to ...` 带毫秒时间戳，可以据此生成每帧的显示时长，
用 ffmpeg 的 concat 分隔符按真实节奏回放，不会因为截帧抖动而变慢或变快。

## 正式客户端后台录制

手动控制正式实例的录制时，把 `platforms/<目标>/build/libs/*.jar` 放进实例的
`mods/`，再带上系统属性启动，鼠标抓取会被取消：

```text
-Dpd.backgroundCapture=true
```

需要连续画面时用后台录帧接口：启动时设置环境变量 `PD_CAPTURE_DIR`（可选 `PD_CAPTURE_FPS`，默认 30，填 0 录制每个渲染帧），
游戏会直接读渲染完成的帧缓冲并写原始像素，不依赖窗口是否在前台。在目录里建 `capture.start` 开始录制，
建 `capture.stop` 结束，输出：

| 文件 | 内容 |
| --- | --- |
| `frames.raw` | 逐帧 RGBA 8 位像素，尺寸见 `frames.meta` |
| `frames.index` | 每帧「序号 相对纳秒 宽 高」 |
| `frames.meta` | 宽、高、帧数、首末时间戳与目标帧率 |

合成视频时用 `frames.meta` 的帧数除以首末时间差得到实际帧率，再用 ffmpeg 按这个帧率读 `frames.raw`。
窗口不在前台也能渲染，但不要最小化。

为了完全不影响正在用电脑的人，可以把客户端放到一个不可见的 Windows 桌面上启动：
`CreateDesktopW` 建桌面，`CreateProcessW` 的 `STARTUPINFO.lpDesktop` 指向它，窗口从创建起就不在交互桌面，
不抢焦点也不出现在任务栏。录帧走 `PD_CAPTURE_DIR`，录完直接结束进程即可。
