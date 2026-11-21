# 清理项目里可再生的临时产物。
#
# 默认只删「看了就没用」的东西：截图、录制视频、运行日志、临时序列帧。
# 带 -All 时再删构建目录和缓存（下一次构建要重新下载/反编译，联网才有耐心）。
#
# 用法：
#   pwsh tools/clean.ps1            # 轻量清理，可定期跑
#   pwsh tools/clean.ps1 -All       # 深度清理，构建产物和缓存一起删
#   pwsh tools/clean.ps1 -DryRun    # 只打印会删什么

[CmdletBinding()]
param(
    [switch]$All,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$freed = 0L
$removed = 0

function Get-Size([string]$path) {
    if (-not (Test-Path -LiteralPath $path)) { return 0L }
    $item = Get-Item -LiteralPath $path -Force
    if ($item.PSIsContainer) {
        return [long]((Get-ChildItem -LiteralPath $path -Recurse -File -Force -ErrorAction SilentlyContinue |
            Measure-Object Length -Sum).Sum)
    }
    return [long]$item.Length
}

function Remove-Target([string]$relative) {
    $path = Join-Path $root $relative
    if (-not (Test-Path -LiteralPath $path)) { return }
    $size = Get-Size $path
    if ($DryRun) {
        Write-Host ("[dry-run] 删除 {0}  ({1:N1} MB)" -f $relative, ($size / 1MB))
    } else {
        Remove-Item -LiteralPath $path -Recurse -Force -ErrorAction SilentlyContinue
        Write-Host ("删除 {0}  ({1:N1} MB)" -f $relative, ($size / 1MB))
    }
    $script:freed += $size
    $script:removed++
}

function Remove-Files([string]$relativeDir, [string[]]$patterns) {
    $dir = Join-Path $root $relativeDir
    if (-not (Test-Path -LiteralPath $dir)) { return }
    foreach ($pattern in $patterns) {
        Get-ChildItem -LiteralPath $dir -Filter $pattern -File -Force -ErrorAction SilentlyContinue | ForEach-Object {
            $size = $_.Length
            if (-not $DryRun) { Remove-Item -LiteralPath $_.FullName -Force -ErrorAction SilentlyContinue }
            $script:freed += $size
            $script:removed++
        }
    }
}

Write-Host "项目根目录: $root"
Write-Host ("模式: " + $(if ($All) { '深度清理' } else { '轻量清理' }))

# 轻量：本地验收用的截图、视频、日志、序列帧
Remove-Files 'build/visual-checks' @('*.png', '*.jpg', '*.jpeg', '*.mp4', '*.gif', '*.jfr', '*.log')
Remove-Files 'build/visual-checks/gallery-hd' @('*.log', '*.txt', '*.jar')
foreach ($seq in @('seq', 'seq2', 'seq3', 'seq4')) { Remove-Target "build/visual-checks/$seq" }
Remove-Files 'run' @('*.log')
Remove-Files 'logs' @('*.log', '*.gz')

if ($All) {
    # 深度：构建产物与缓存，全部可再生（重新构建需要联网下载依赖和反编译）
    Remove-Target 'build'
    Remove-Target 'run'
    Remove-Target '.gradle'
    Remove-Target '.kotlin'
    Remove-Target 'logs'
    Get-ChildItem -LiteralPath (Join-Path $root 'platforms') -Directory -ErrorAction SilentlyContinue | ForEach-Object {
        Remove-Target ("platforms/" + $_.Name + "/build")
        Remove-Target ("platforms/" + $_.Name + "/.gradle")
    }
    Get-ChildItem -LiteralPath (Join-Path $root 'shared') -Directory -ErrorAction SilentlyContinue | ForEach-Object {
        Remove-Target ("shared/" + $_.Name + "/build")
        Remove-Target ("shared/" + $_.Name + "/.gradle")
    }
}

Write-Host ("完成：{0} 项，释放 {1:N2} GB" -f $removed, ($freed / 1GB))

