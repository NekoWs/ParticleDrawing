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
