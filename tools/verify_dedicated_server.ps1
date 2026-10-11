# 专用服务端（DEDICATED_SERVER）真启动校验器 —— 第一遍「A：真跑一次」
#
# 【编码约定】本文件必须保存为 **UTF-8 带 BOM**：Windows PowerShell 5.1 在没有 BOM 时
#   会按 GBK 解码 .ps1，中文注释被误解成引号后会直接解析失败（本工程已踩过）。
#
# 为什么需要它
# ------------
# 1.1.0 让专用服务端「启动即崩」，而**所有编译期 / 客户端语境的静态校验都报 0 问题**。
# 结论：编译期视角无法覆盖「运行期 Mixin 应用」与「专服 dist」。唯一能证伪的办法是
# **真的 headless 启动一次 NeoForge dedicated server**，把日志抓下来做归属判定。
#
# 本脚本做四件事
# --------------
#   ① 准备一个**独立**的游戏目录 build/server_probe/game（eula.txt / server.properties），
#      run/ 保持只读 —— 并且**前后各取一次 run/ 的文件清单做对照**，用证据证明没动 run/；
#   ② 用 tools/server_probe.init.gradle 把 moddev 的 runServer gameDirectory 指到该目录
#      （为什么不能只改 workingDir：见 init script 顶部注释里的实测教训）；
#   ③ 启动 `gradlew runServer`，**带明确超时**（默认 900 秒）轮询日志；
#      一旦出现「终态」立刻 taskkill /T 结束进程树，绝不挂死；
#   ④ 交给 tools/verify_dedicated_server.py 做归属判定（我们的错 / 别人的错 / 超时）。
#
# 退出码（与 Python 判定器一致）
#   0 = 服务端真的起来了（PASS）
#   1 = 判定为「我们（rs_create_compat）的错」
#   2 = 「别人的错」或环境不可用 ⇒ 无法证明我们的模组没问题（INCONCLUSIVE）
#   3 = 超时 / 没抓到任何终态（同样 INCONCLUSIVE，且明确告诉你是超时）
#
# 用法
#   powershell -ExecutionPolicy Bypass -File tools\verify_dedicated_server.ps1
#   powershell -ExecutionPolicy Bypass -File tools\verify_dedicated_server.ps1 -ModJar build\libs\rs_create_compat-1.1.0.jar -Expect our-failure
#   powershell -ExecutionPolicy Bypass -File tools\verify_dedicated_server.ps1 -TimeoutSec 1200
param(
    [int]$TimeoutSec = 900,
    [string]$ModJar = "",
    [ValidateSet("pass", "our-failure", "our-mixin-failure", "any")]
    [string]$Expect = "pass",
    [string]$OutputLog = "",
    [switch]$NoJudge
)

$ErrorActionPreference = "Continue"
$root = "D:\MODS\refined_storage_and_create_compat"
$python = "C:\Users\70432\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe"
$probeGame = Join-Path $root "build\server_probe\game"
$initScript = Join-Path $root "tools\server_probe.init.gradle"
$judge = Join-Path $root "tools\verify_dedicated_server.py"

if ($OutputLog -eq "") {
    if ($ModJar -ne "") { $OutputLog = Join-Path $root "build\dedicated_server_probe.regression.log" }
    else                { $OutputLog = Join-Path $root "build\dedicated_server_probe.log" }
}

Write-Output "=== [A] 专用服务端真启动校验 ==="
Write-Output "  gameDir  : $probeGame   （run/ 只读；探针目录在 run/ 之外）"
Write-Output "  init     : $initScript"
$modSourceText = "<dev build/classes>"
if ($ModJar -ne "") { $modSourceText = $ModJar }
Write-Output "  mod 来源 : $modSourceText"
Write-Output "  超时上限 : ${TimeoutSec}s"
Write-Output "  日志     : $OutputLog"
Write-Output "  期望     : $Expect"

# --- ① 准备独立游戏目录 ------------------------------------------------------
if (!(Test-Path $probeGame)) { New-Item -ItemType Directory -Path $probeGame -Force | Out-Null }
if (!(Test-Path (Join-Path $probeGame "mods"))) { New-Item -ItemType Directory -Path (Join-Path $probeGame "mods") -Force | Out-Null }
# 【为什么要复制 run/mods 里的 jar】探针目录独立以后 mods/ 是空的，而本模组的
#   neoforge.mods.toml 把 refinedstorage_curios_integration 声明为 **required** 前置
#   ⇒ 服务端会在「模组排序」阶段就报
#       Missing or unsupported mandatory dependencies:
#         Mod ID: 'refinedstorage_curios_integration', Requested by: 'rs_create_compat'
#     然后直接退出 —— **根本走不到 Mixin 应用**，于是这个探针就什么都验不出来了
#     （实测：第一次改成独立目录后就撞上了这个）。
#   所以这里把 run/mods 的 jar **只读复制**一份过来（run/ 本身一个字节都不写）；
#   只在缺失/大小不同时才复制，重复跑几乎零成本。
$runMods = Join-Path $root "run\mods"
$probeMods = Join-Path $probeGame "mods"
$copiedMods = 0
if (Test-Path $runMods) {
    Get-ChildItem $runMods -Filter *.jar -File | ForEach-Object {
        $dest = Join-Path $probeMods $_.Name
        if (!(Test-Path $dest) -or (Get-Item $dest).Length -ne $_.Length) {
            Copy-Item $_.FullName $dest -Force
            $copiedMods++
        }
    }
}
Write-Output "  探针 mods : $((Get-ChildItem $probeMods -Filter *.jar -File).Count) 个 jar（本次新复制 $copiedMods 个，来源 run\mods 只读）"
# eula=true 只写在**探针目录**里
[System.IO.File]::WriteAllText((Join-Path $probeGame "eula.txt"),
    "# rscc dedicated server probe - only inside build/server_probe`neula=true`n",
    (New-Object System.Text.UTF8Encoding($false)))
$serverProps = @(
    "online-mode=false",
    "level-name=world",
    "level-type=minecraft\:flat",
    "max-players=1",
    "view-distance=4",
    "simulation-distance=4",
    "spawn-protection=0",
    "sync-chunk-writes=false",
    "enable-command-block=false",
    "motd=rscc dedicated server probe"
)
Set-Content -Path (Join-Path $probeGame "server.properties") -Value $serverProps -Encoding ASCII

# --- ② run/ 只读自证：跑之前拍一份清单 ---------------------------------------
function Get-TreeManifest([string]$path) {
    $map = @{}
    if (!(Test-Path $path)) { return $map }
    Get-ChildItem $path -Recurse -Force -File -ErrorAction SilentlyContinue | ForEach-Object {
        $map[$_.FullName] = "{0}:{1}" -f $_.Length, $_.LastWriteTimeUtc.Ticks
    }
    return $map
}
$runDir = Join-Path $root "run"
$before = Get-TreeManifest $runDir

# --- ③ 启动（明确超时 + 终态轮询 + 强杀进程树）--------------------------------
if (!(Test-Path $initScript)) { Write-Output "PROBE_FAILED init script missing: $initScript"; exit 2 }
if (!(Test-Path $python)) { Write-Output "PROBE_FAILED python missing: $python"; exit 2 }

Remove-Item $OutputLog -Force -ErrorAction SilentlyContinue

# 被测代码自证：dev 模式下服务端加载的是 build/classes（不是 build/libs/*.jar），
# 所以必须确认「字节码不比源码旧」。踩过的坑：Gradle 的文件系统监视会给出**过期哈希**，
# 让 compileJava 误报 UP-TO-DATE，于是服务端加载上一轮编译的旧类（本次 1.1.0 崩溃正是这样
# 被复现出来的）。这里既打印时间戳，也统一加 --no-watch-fs 关掉文件监视强制重新哈希。
$srcNewest = (Get-ChildItem (Join-Path $root "src\main\java") -Recurse -Filter *.java |
              Sort-Object LastWriteTime -Descending | Select-Object -First 1)
$clsRoot = Join-Path $root "build\classes\java\main"
$clsNewest = $null
if (Test-Path $clsRoot) {
    $clsNewest = (Get-ChildItem $clsRoot -Recurse -Filter *.class |
                  Sort-Object LastWriteTime -Descending | Select-Object -First 1)
}
if ($srcNewest) { Write-Output "  最新源码   : $($srcNewest.LastWriteTime)  $($srcNewest.Name)" }
if ($clsNewest) { Write-Output "  最新字节码 : $($clsNewest.LastWriteTime)  $($clsNewest.Name)" }
if ($srcNewest -and $clsNewest -and $clsNewest.LastWriteTime -lt $srcNewest.LastWriteTime) {
    Write-Output "  [警告] 字节码比源码旧 ⇒ 本次可能测到上一轮编译的旧类"
}

$gradleArgs = @(
    "runServer",
    "--init-script", "tools\server_probe.init.gradle",
    "--no-configuration-cache",
    "--no-watch-fs",
    "--no-daemon",
    "--console=plain",
    "-PrsccProbeGameDir=$probeGame"
)
if ($ModJar -ne "") {
    $jarAbs = $ModJar
    if (![System.IO.Path]::IsPathRooted($jarAbs)) { $jarAbs = Join-Path $root $ModJar }
    if (!(Test-Path $jarAbs)) { Write-Output "PROBE_FAILED -ModJar 不存在: $jarAbs"; exit 2 }
    $gradleArgs += "-PrsccProbeModJar=$jarAbs"
}
# 【踩过的坑】cmd /c 后面如果紧跟一个引号，cmd 会把**整串的首尾引号都剥掉**
#   ⇒ `cmd /c "D:\...\gradlew.bat" runServer ... > "log" 2>&1` 会被解析坏，
#     进程立刻退出、日志文件根本不生成（表现为「瞬间 PROCESS_EXITED 且无日志」）。
#   修法：命令以 `call ` 开头（首字符不是引号即可，不再触发那条剥离规则）。
$cmdLine = "call `"$root\gradlew.bat`" " + ($gradleArgs -join " ") + " > `"$OutputLog`" 2>&1"

Write-Output ""
Write-Output "  启动命令 : cmd /c $cmdLine"
$proc = Start-Process -FilePath "cmd.exe" -ArgumentList "/c", $cmdLine -WorkingDirectory $root -PassThru -WindowStyle Hidden

# 终态判据：命中任意一条即认为「已经有结论」，立刻停掉进程树。
# 注意：gradle 在服务端崩溃时仍然报 BUILD SUCCESSFUL（实测），所以**绝不能**用退出码判定，
# 只能看日志内容 —— 这正是本校验器存在的意义之一。
$terminalPatterns = @(
    "Done \(",                                   # 服务端真的起来了
    "Failed to start the minecraft server",
    "Mod loading has failed",
    "Mixin apply for mod",
    "has an invalid signature",
    "for invalid dist DEDICATED_SERVER",
    "Incompatible mod set",
    "Missing or unsupported mandatory dependencies"
)
$deadline = (Get-Date).AddSeconds($TimeoutSec)
$verdictReason = "TIMEOUT"
$seenDone = $false
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Milliseconds 1500
    if (Test-Path $OutputLog) {
        $tail = ""
        try {
            # 只读尾部，避免日志上百万行时把内存吃光
            $fs = [System.IO.File]::Open($OutputLog, [System.IO.FileMode]::Open,
                                         [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
            $len = $fs.Length
            $read = [Math]::Min(400000, $len)
            $fs.Seek($len - $read, [System.IO.SeekOrigin]::Begin) | Out-Null
            $buf = New-Object byte[] $read
            $fs.Read($buf, 0, $read) | Out-Null
            $fs.Close()
            $tail = [System.Text.Encoding]::UTF8.GetString($buf)
        } catch { $tail = "" }
        foreach ($pat in $terminalPatterns) {
            if ($tail -match $pat) {
                if ($pat -eq "Done \(") { $seenDone = $true }
                $verdictReason = $pat
                break
            }
        }
        if ($verdictReason -ne "TIMEOUT") { break }
    }
    if ($proc.HasExited -and (Get-Date) -lt $deadline) {
        # 进程自己退了（崩溃/正常退出）：再等 2 秒让日志刷完
        Start-Sleep -Seconds 2
        $verdictReason = "PROCESS_EXITED"
        break
    }
}

# 抓到终态（或超时）后一律强杀整棵进程树，避免留下孤儿 java 进程 / gradle 守护进程。
if (-not $proc.HasExited) {
    & taskkill.exe /PID $proc.Id /T /F 2>&1 | Out-Null
}
Start-Sleep -Seconds 3
# 兜底：如果还有 runServer 的 java 进程活着（例如 moddev 把服务端挂在守护进程下），再杀一次
Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -like "*forgeserverdev*" } |
    ForEach-Object { & taskkill.exe /PID $_.ProcessId /T /F 2>&1 | Out-Null }

Write-Output "  终态判据 : $verdictReason  (Done=$seenDone)"

# --- ④ run/ 只读自证：跑之后再拍一份，逐条列出新增/修改 ----------------------
$after = Get-TreeManifest $runDir
$changed = New-Object System.Collections.Generic.List[string]
foreach ($k in $after.Keys) {
    if (-not $before.ContainsKey($k)) { $changed.Add("新增 $k") }
    elseif ($before[$k] -ne $after[$k]) { $changed.Add("改动 $k") }
}
foreach ($k in $before.Keys) { if (-not $after.ContainsKey($k)) { $changed.Add("删除 $k") } }
if ($changed.Count -eq 0) {
    Write-Output "  run/ 只读自证 : OK（前后清单完全一致，本次运行没有写 run/ 任何文件）"
} else {
    Write-Output "  run/ 只读自证 : 警告 —— run/ 被改动了 $($changed.Count) 处："
    $changed | Select-Object -First 15 | ForEach-Object { Write-Output "      $_" }
}
# 探针目录是否真的被用到（没有的话说明 gameDirectory 覆盖失败，结论不可信）
$probeTouched = Test-Path (Join-Path $probeGame "logs")
Write-Output "  探针目录被写入 : $probeTouched  （$probeGame）"

if (-not (Test-Path $OutputLog)) {
    Write-Output "PROBE_FAILED 没有产生日志文件：$OutputLog"
    exit 2
}

# --- ⑤ 归属判定 ---------------------------------------------------------------
if ($NoJudge) { exit 0 }
Write-Output ""
& $python $judge $OutputLog "--expect=$Expect"
exit $LASTEXITCODE
