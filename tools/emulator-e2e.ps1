#Requires -Version 5.1
<#
.SYNOPSIS
    在 Android 模拟器上跑一遍 openjm 的端到端回归。

.DESCRIPTION
    覆盖验收清单里不依赖真机 ROM 的部分：
      1. 冷启动是空书架
      2. VIEW 唤起 → 导入 → 书架出现条目
      3. 点开 → 阅读页渲染（PDFium）
      4. 返回 → 写进度
      5. 杀进程 → 冷启动 → 书架与进度都还原（FR-15）

    定位控件用 `uiautomator dump` 的文本节点反查 bounds，不写死坐标：
    布局一变，写死的坐标会“假失败”，文本不会。

    依赖：Android SDK（emulator / platform-tools）、JDK 21、已创建的 AVD；
    模拟器没起就自动起（无头），脚本结束默认留着（-StopEmulator 可关）。

.PARAMETER Fresh
    先卸载 App 再装，保证“冷启动空书架”这条断言有效。

.PARAMETER Vector
    要导入的 .jm，默认用总仓的 v01-plain-no-pad。

.EXAMPLE
    .\tools\emulator-e2e.ps1 -Fresh
#>
param(
    [string]$Avd = "openjm36",
    [string]$Vector = "",
    [string]$Package = "com.openjm.reader",
    [string]$Apk = "",
    [switch]$Fresh,
    [switch]$NoBuild,
    [switch]$StopEmulator
)

$ErrorActionPreference = "Continue"
$script:Failures = New-Object System.Collections.Generic.List[string]

function Write-Step($message) { Write-Host "`n== $message" -ForegroundColor Cyan }
function Write-Ok($message)   { Write-Host "  [ok] $message" -ForegroundColor Green }
function Write-Fail($message) { Write-Host "  [FAIL] $message" -ForegroundColor Red; $script:Failures.Add($message) }

function Assert-Contains {
    param([string]$Label, [string[]]$Haystack, [string]$Needle)
    if (($Haystack -join "`n").Contains($Needle)) {
        Write-Ok "$Label（命中「$Needle」）"
    } else {
        Write-Fail "$Label：没找到「$Needle」，实际文本：$($Haystack -join ' | ')"
    }
}

# ---------------------------------------------------------------- 环境
$repoRoot = Split-Path -Parent $PSScriptRoot
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA "Android\Sdk" }
$adb = Join-Path $sdk "platform-tools\adb.exe"
$emulator = Join-Path $sdk "emulator\emulator.exe"
if (-not (Test-Path $adb)) { throw "找不到 adb：$adb（先设 ANDROID_HOME）" }

if (-not $Vector) {
    $Vector = Join-Path (Split-Path -Parent $repoRoot) "openjm\testvectors\valid\v01-plain-no-pad.jm"
}
if (-not (Test-Path $Vector)) { throw "找不到测试向量：$Vector（用 -Vector 指定）" }

$work = Join-Path $env:TEMP ("openjm-e2e-" + [guid]::NewGuid().ToString("N").Substring(0, 8))
New-Item -ItemType Directory -Force -Path $work | Out-Null

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$AdbArgs)
    $out = & $adb @AdbArgs 2>&1
    if ($LASTEXITCODE -ne 0 -and ($out -join "`n") -notmatch "Activity not started") {
        throw "adb $($AdbArgs -join ' ') 失败：$($out -join "`n")"
    }
    return $out
}

function Wait-Device {
    $deadline = (Get-Date).AddMinutes(4)
    while ((Get-Date) -lt $deadline) {
        $devices = & $adb devices 2>&1
        if ($devices -match "emulator-\d+\s+device") { return }
        Start-Sleep -Seconds 3
    }
    throw "模拟器 4 分钟内没上线（adb devices 为空）"
}

function Wait-Boot {
    $deadline = (Get-Date).AddMinutes(4)
    while ((Get-Date) -lt $deadline) {
        $boot = (& $adb shell getprop sys.boot_completed 2>&1) -join ""
        if ($boot.Trim() -eq "1") { return }
        Start-Sleep -Seconds 3
    }
    throw "模拟器 4 分钟内没完成 boot"
}

# 取当前界面的全部文本节点（Compose 的 Text 会以 text= 暴露出来）
function Get-UiTexts {
    & $adb shell uiautomator dump /sdcard/e2e-ui.xml 2>&1 | Out-Null
    $local = Join-Path $work "ui.xml"
    & $adb pull /sdcard/e2e-ui.xml $local 2>&1 | Out-Null
    $xml = Get-Content $local -Raw -Encoding UTF8
    return @([regex]::Matches($xml, 'text="([^"]*)"') | ForEach-Object { $_.Groups[1].Value } | Where-Object { $_ })
}

# 按文本反查 bounds 中心点；找不到返回 $null
function Find-Center {
    param([string]$Text)
    & $adb shell uiautomator dump /sdcard/e2e-ui.xml 2>&1 | Out-Null
    $local = Join-Path $work "ui.xml"
    & $adb pull /sdcard/e2e-ui.xml $local 2>&1 | Out-Null
    $xml = Get-Content $local -Raw -Encoding UTF8
    $node = [regex]::Match($xml, '<node[^>]*text="' + [regex]::Escape($Text) + '"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if (-not $node.Success) { return $null }
    $x1, $y1, $x2, $y2 = $node.Groups[1].Value, $node.Groups[2].Value, $node.Groups[3].Value, $node.Groups[4].Value
    return @([int](([int]$x1 + [int]$x2) / 2), [int](([int]$y1 + [int]$y2) / 2))
}

function Tap-Text {
    param([string]$Text, [string]$Label = $Text)
    $center = Find-Center $Text
    if (-not $center) { Write-Fail "点不到「$Label」：界面上没这个文本"; return $false }
    & $adb shell input tap $center[0] $center[1] 2>&1 | Out-Null
    Write-Ok "点了「$Label」($($center[0]),$($center[1]))"
    return $true
}

function Start-App {
    param([string]$Uri = "")
    if ($Uri) {
        $adbArgs = @("shell", "am", "start", "-a", "android.intent.action.VIEW", "-t", "application/octet-stream",
                     "-d", $Uri, "-n", "$Package/com.openjm.reader.android.MainActivity")
    } else {
        $adbArgs = @("shell", "monkey", "-p", $Package, "-c", "android.intent.category.LAUNCHER", "1")
    }
    Invoke-Adb -AdbArgs $adbArgs | Out-Null
    Start-Sleep -Seconds 5
}

# ---------------------------------------------------------------- 主流程
Write-Step "0) 模拟器"
$devices = (& $adb devices 2>&1) -join "`n"
if ($devices -notmatch "emulator-\d+\s+device") {
    Write-Host "  模拟器没起，无头启动 $Avd ..."
    if (-not (Test-Path $emulator)) { throw "找不到 emulator：$emulator" }
    Start-Process -FilePath $emulator -WindowStyle Hidden -ArgumentList @(
        "-avd", $Avd, "-no-window", "-no-audio", "-no-boot-anim", "-no-snapshot",
        "-gpu", "swiftshader_indirect"
    ) | Out-Null
}
Wait-Device
Wait-Boot
Write-Ok "设备在线且 boot 完成"

Write-Step "1) 构建与安装"
if (-not $Apk) { $Apk = Join-Path $repoRoot "androidApp\build\outputs\apk\debug\androidApp-debug.apk" }
if (-not $NoBuild) {
    Push-Location $repoRoot
    try {
        & (Join-Path $repoRoot "gradlew.bat") ":androidApp:assembleDebug" "--console=plain" "-q" 2>&1 | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "assembleDebug 失败" }
    } finally { Pop-Location }
    Write-Ok "assembleDebug 通过"
}
if ($Fresh) {
    & $adb uninstall $Package 2>&1 | Out-Null
    Write-Ok "已卸载旧包（冷启动空书架的前提）"
}
& $adb install -r $Apk 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { throw "安装失败：$Apk" }
Write-Ok "安装完成：$Apk"

Write-Step "2) 冷启动：书架应为空"
Start-App
$texts = Get-UiTexts
Assert-Contains "空书架文案" $texts "书架还是空的"

Write-Step "3) VIEW 唤起 → 导入"
& $adb push $Vector /data/local/tmp/e2e.jm 2>&1 | Out-Null
Invoke-Adb -AdbArgs @("shell", "run-as", $Package, "mkdir", "-p", "files") | Out-Null
Invoke-Adb -AdbArgs @("shell", "run-as", $Package, "cp", "/data/local/tmp/e2e.jm", "files/e2e.jm") | Out-Null
Invoke-Adb -AdbArgs @("shell", "am", "force-stop", $Package) | Out-Null
Start-App "file:///data/data/$Package/files/e2e.jm"
$texts = Get-UiTexts
Assert-Contains "书架出现条目" $texts ".pdf"

$shelf = & $adb shell run-as $Package ls files/books 2>&1
if (($shelf -join "`n") -match "\.pdf") { Write-Ok "沙盒里已有转正后的明文 PDF" } else { Write-Fail "沙盒里没有 PDF：$($shelf -join ' ')" }
$tmpLeft = & $adb shell run-as $Package ls files/tmp 2>&1
if (($tmpLeft -join "").Trim()) { Write-Fail "tmp/ 有残留：$($tmpLeft -join ' ')" } else { Write-Ok "tmp/ 无残留" }

Write-Step "4) 点开 → 阅读页"
$bookTitle = ($texts | Where-Object { $_ -like "*.pdf" } | Select-Object -First 1)
if (-not $bookTitle) { Write-Fail "书架上没找到 .pdf 标题" } else {
    if (Tap-Text $bookTitle "书架条目") {
        Start-Sleep -Seconds 5
        $reader = Get-UiTexts
        Assert-Contains "阅读页打开（有返回）" $reader "返回"
    }
}

Write-Step "5) 返回 → 杀进程 → 冷启动还原"
Tap-Text "返回" | Out-Null
Start-Sleep -Seconds 3
Invoke-Adb -AdbArgs @("shell", "am", "force-stop", $Package) | Out-Null
Start-Sleep -Seconds 2
Start-App
$after = Get-UiTexts
Assert-Contains "冷启动后书名还在" $after ".pdf"
Assert-Contains "冷启动后进度还在" $after "页"

$index = & $adb shell run-as $Package cat files/books/index.json 2>&1
if (($index -join "`n") -match '"docId"') { Write-Ok "books/index.json 已落盘" } else { Write-Fail "books/index.json 缺失或为空" }

# ---------------------------------------------------------------- 收尾
if ($StopEmulator) { & $adb emu kill 2>&1 | Out-Null }
else { Write-Host "`n（模拟器保持运行，需要关闭：adb emu kill）" -ForegroundColor DarkGray }
Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue

Write-Host ""
if ($script:Failures.Count -eq 0) {
    Write-Host "E2E PASSED" -ForegroundColor Green
    exit 0
}
Write-Host "E2E FAILED ($($script:Failures.Count) 项)" -ForegroundColor Red
$script:Failures | ForEach-Object { Write-Host "  - $_" -ForegroundColor Red }
exit 1
