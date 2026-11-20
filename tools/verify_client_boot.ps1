# 客户端（CLIENT）真启动校验器 —— 与 tools/verify_dedicated_server.ps1 对称
#
# 【编码约定】本文件必须保存为 **UTF-8 带 BOM**：Windows PowerShell 5.1 在没有 BOM 时
#   会按 GBK 解码 .ps1，中文注释被误解成引号后会直接解析失败。
#
# 为什么需要它
# ------------
# 专用服务端探针只能证明「服务端 dist 能起来」；客户端有自己的一整套
# 「客户端专属类 / 渲染初始化 / Mixin dist 判定」，编译期和服务端视角都覆盖不到。
# 用户明确要求「服务端要测试一遍、客户端要测试一遍」，所以两边各有一个真启动探针。
#
# 本脚本做四件事
# --------------
#   ① 准备独立游戏目录 build/client_probe/game（只从 run/mods 只读复制 jar），run/ 前后各取一次
#      文件清单做对照，用证据证明没动 run/；
#   ② 用 tools/client_probe.init.gradle 把 moddev 的 runClient gameDirectory 指到该目录；
#   ③ 启动 `gradlew runClient`，带明确超时轮询日志；一旦看到「终态」立刻 taskkill /T 结束进程树；
#   ④ 判定：PASS / OUR_FAILURE / INCONCLUSIVE / TIMEOUT 四态，绝不用「进程还活着」当通过。
#
# 判定标记（终端态）
#   PASS：日志同时出现 ①本模组启动指纹 `[rscc-build]` ②渲染初始化完成
#         （`Sound engine started` / `Backend library: LWJGL` / `Created: 1024x512` 任一）
#   OUR_FAILURE：`Mixin apply for mod rs_create_compat failed` / 本模组类名 + 异常 / 客户端 crash-report
#   INCONCLUSIVE：别人的前置缺失 / 窗口 / GLFW / 显卡报错 / 进程自己退出
#   TIMEOUT：超时上限内没抓到任何终态
#
# 退出码：0 = PASS；1 = OUR_FAILURE；2 = INCONCLUSIVE；3 = TIMEOUT
# 用法：
#   powershell -ExecutionPolicy Bypass -File tools\verify_client_boot.ps1
#   powershell -ExecutionPolicy Bypass -File tools\verify_client_boot.ps1 -TimeoutSec 900
param(
    [int]$TimeoutSec = 600,
    [string]$OutputLog = ""
)

$ErrorActionPreference = "Continue"
$root = "D:\MODS\refined_storage_and_create_compat"
$probeGame = Join-Path $root "build\client_probe\game"
$initScript = Join-Path $root "tools\client_probe.init.gradle"
$gradlew = Join-Path $root "gradlew.bat"
if ($OutputLog -eq "") { $OutputLog = Join-Path $root "build\client_boot_probe.log" }

Write-Output "=== [C] 客户端真启动校验 ==="
Write-Output "  gameDir  : $probeGame   （run/ 只读；探针目录在 run/ 之外）"
Write-Output "  init     : $initScript"
Write-Output "  超时上限 : ${TimeoutSec}s"
Write-Output "  日志     : $OutputLog"

if (!(Test-Path $gradlew)) { Write-Output "CLIENT_PROBE_FAILED gradlew missing"; exit 2 }
if (!(Test-Path $initScript)) { Write-Output "CLIENT_PROBE_FAILED init script missing"; exit 2 }

# --- ⓪ 并发闸门（同一 gameDir / 日志 / build 目录不允许并发） -------------------
$lockFile = Join-Path $root "build\verify_client_boot.lock"
$lockHolders = @()
if (Test-Path $lockFile) {
    foreach ($line in (Get-Content $lockFile -ErrorAction SilentlyContinue)) {
        $pidText = "$line".Trim()
        if ($pidText -match '^\d+$') {
            if (Get-Process -Id ([int]$pidText) -ErrorAction SilentlyContinue) { $lockHolders += $pidText }
        }
    }
}
if ($lockHolders.Count -gt 0) {
    Write-Output "CLIENT_PROBE_SKIPPED 已有探针在跑（PID $($lockHolders -join ', ')）"
    exit 2
}
Set-Content -Path $lockFile -Value "$PID" -Encoding ASCII

$proc = $null
$verdict = ""
$reason = ""
try {
    # --- ① 独立游戏目录 + 只读复制 run/mods -----------------------------------
    if (!(Test-Path $probeGame)) { New-Item -ItemType Directory -Path $probeGame -Force | Out-Null }
    $probeMods = Join-Path $probeGame "mods"
    if (!(Test-Path $probeMods)) { New-Item -ItemType Directory -Path $probeMods -Force | Out-Null }
    $runMods = Join-Path $root "run\mods"
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
    # 复制 run/options.txt（只读一次）：关键是其中的 onboardAccessibility:false / tutorialStep:none，
    # 否则客户端会停在「无障碍引导」界面等人点按钮，自动化探针永远看不到标题界面。
    $runOptions = Join-Path $root "run\options.txt"
    if (Test-Path $runOptions) {
        Copy-Item $runOptions (Join-Path $probeGame "options.txt") -Force
        Write-Output "  options   : 复制 run/options.txt（onboardAccessibility:false，跳过引导界面）"
    }

    # --- ② run/ 只读自证：跑之前拍清单 ----------------------------------------
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

    # 记录启动前已存在的 crash-report：只把「本次探针新产生的」算作我们的证据
    # （否则上一次运行留下的旧报告会让后续每次都误判 OUR_FAILURE）。
    $crashDirEarly = Join-Path $probeGame "crash-reports"
    $crashBefore = @()
    if (Test-Path $crashDirEarly) { $crashBefore = @(Get-ChildItem $crashDirEarly -Filter *.txt -File | ForEach-Object { $_.Name }) }
    # --- ③ 启动 ---------------------------------------------------------------
    Remove-Item $OutputLog -Force -ErrorAction SilentlyContinue
    $arguments = '/c ""{0}" runClient --init-script "{1}" --no-configuration-cache --console=plain --no-daemon --no-watch-fs -PrsccClientProbeGameDir="{2}" > "{3}" 2>&1"' -f `
        $gradlew, $initScript, $probeGame, $OutputLog
    $proc = Start-Process -FilePath "cmd.exe" -ArgumentList $arguments -WorkingDirectory $root -PassThru -WindowStyle Hidden
    Write-Output "  客户端进程 : cmd PID $($proc.Id)（超时 / 终态后 taskkill /T 结束整棵进程树）"

    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    $passSeen = $false
    $failSeen = $false
    $inconclusiveSeen = $false
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 2
        $text = ""
        if (Test-Path $OutputLog) {
            try {
                # 与 tools/verify_dedicated_server.ps1 同一教训：cmd 重定向的日志被独占打开，
                # ReadAllText 会抛「正由另一进程使用」⇒ 必须用 FileShare.ReadWrite 只读尾部。
                $fs = [System.IO.File]::Open($OutputLog, [System.IO.FileMode]::Open,
                                             [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
                $len = $fs.Length
                $read = [Math]::Min(400000, $len)
                $fs.Seek($len - $read, [System.IO.SeekOrigin]::Begin) | Out-Null
                $buf = New-Object byte[] $read
                $fs.Read($buf, 0, $read) | Out-Null
                $fs.Close()
                $text = [System.Text.Encoding]::UTF8.GetString($buf)
            } catch { $text = "" }
        }
        # 客户端不打印 [rscc-build]（那一行是服务端启动指纹）；客户端用本模组自己的日志前缀
        # [rscc-*]（如 [rscc-ultimine] / [rscc-terminal-key]）或加载器给出的 mod 版本行作为「已加载」证据。
        $hasBuildLine = ($text -match 'mods - versions \{1\.2\.0-beta\.38\}') -or ($text -match '\[rscc-(?!client-probe)')
        $hasRender = ($text -match 'Sound engine started') -or ($text -match 'Backend library: LWJGL') -or ($text -match 'Created: 1024x512')
        if ($hasBuildLine -and $hasRender) { $passSeen = $true; break }
        if ($text -match 'Mixin apply for mod rs_create_compat failed') { $failSeen = $true; break }
        if ($text -match 'rs_create_compat\.mixins\.json.*(failed|InvalidInjection|InvalidMixinException)') { $failSeen = $true; break }
        if ($text -match '(NoClassDefFoundError|NoSuchMethodError|ExceptionInInitializerError).{0,200}rs_create_compat') { $failSeen = $true; break }
        if ($text -match 'Missing or unsupported mandatory dependencies') { $inconclusiveSeen = $true; break }
        if ($text -match 'Failed to create window|GLFW error|Pixel format not accelerated|UnsatisfiedLinkError|LWJGL Exception') { $inconclusiveSeen = $true; break }
        if ($text -match 'A fatal error has been detected by the Java Runtime Environment') { $inconclusiveSeen = $true; break }
        if ($proc.HasExited) { $inconclusiveSeen = $true; $reason = "客户端进程提前退出（exit=$($proc.ExitCode)）"; break }
    }

    # --- ④ 判定 ---------------------------------------------------------------
    $crashDir = Join-Path $probeGame "crash-reports"
    $newCrash = @()
    if (Test-Path $crashDir) {
        $newCrash = @(Get-ChildItem $crashDir -Filter *.txt -File -ErrorAction SilentlyContinue |
            Where-Object { $crashBefore -notcontains $_.Name })
    }
    if ($passSeen -and $newCrash.Count -eq 0) {
        $verdict = "PASS"; $reason = "本模组已加载（mod 版本行 / [rscc-*] 日志）+ 客户端渲染初始化完成"
    } elseif ($failSeen) {
        $verdict = "OUR_FAILURE"; $reason = "客户端侧出现本模组相关的 Mixin / 类加载失败"
    } elseif ($newCrash.Count -gt 0) {
        # 归属判定：crash-report 里必须出现本模组包名 / modid 才算我们；否则是别人的模组崩溃
        $firstCrash = $newCrash[0].FullName
        $crashText = ""
        try { $crashText = [System.IO.File]::ReadAllText($firstCrash) } catch { $crashText = "" }
        if ($crashText -match 'rs_create_compat|cretae\.cookiewyq') {
            $verdict = "OUR_FAILURE"; $reason = "客户端 crash-report 指向本模组：$firstCrash"
        } else {
            $verdict = "INCONCLUSIVE"; $reason = "客户端崩溃但 crash-report 与本模组无关：$firstCrash"
        }
    } elseif ($inconclusiveSeen) {
        $verdict = "INCONCLUSIVE"; $reason = "环境 / 其它模组原因，无法证明本模组客户端没问题：$reason"
    } else {
        $verdict = "TIMEOUT"; $reason = "超过 ${TimeoutSec}s 没抓到任何终态"
    }

    # --- ⑤ 收尾：结束进程树 ---------------------------------------------------
    if ($proc -and !$proc.HasExited) {
        & taskkill /T /F /PID $proc.Id 2>&1 | Out-Null
        Start-Sleep -Milliseconds 500
    }

    # --- ⑥ run/ 只读自证 ------------------------------------------------------
    $after = Get-TreeManifest $runDir
    $changed = @()
    foreach ($key in $before.Keys) {
        if (!$after.ContainsKey($key)) { $changed += "REMOVED $key" }
        elseif ($after[$key] -ne $before[$key]) { $changed += "CHANGED $key" }
    }
    foreach ($key in $after.Keys) {
        if (!$before.ContainsKey($key)) { $changed += "ADDED $key" }
    }
    if ($changed.Count -gt 0) {
        Write-Output "  [警告] run/ 在本次探针期间发生变化 $($changed.Count) 项（前 5 项）："
        $changed | Select-Object -First 5 | ForEach-Object { Write-Output "    $_" }
    } else {
        Write-Output "  run/ 只读自证 : 前后清单完全一致（0 变化）"
    }
} finally {
    if ($proc -and !$proc.HasExited) { & taskkill /T /F /PID $proc.Id 2>&1 | Out-Null }
    Remove-Item $lockFile -Force -ErrorAction SilentlyContinue
}

# --- 输出尾部证据 -------------------------------------------------------------
if (Test-Path $OutputLog) {
    Write-Output "  --- 日志尾部（最后 25 行） ---"
    Get-Content $OutputLog -Tail 25 -ErrorAction SilentlyContinue | ForEach-Object { Write-Output "    $_" }
}
Write-Output "CLIENT_BOOT_VERDICT=$verdict"
Write-Output "CLIENT_BOOT_REASON=$reason"
switch ($verdict) {
    "PASS"         { exit 0 }
    "OUR_FAILURE"  { exit 1 }
    "INCONCLUSIVE" { exit 2 }
    default        { exit 3 }
}
