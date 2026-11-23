# 后台验收截图

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
