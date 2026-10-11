# Local javac compile using cached Gradle jars. No network (bypasses sandbox).
# Usage: & .\tools\manual_compile.ps1

$ErrorActionPreference = "Continue"
$root = "d:\MODS\refined_storage_and_create_compat"
$javac = "D:\java21\bin\javac.exe"
$outDir = "$root\build\manual_compile"
$logFile = "$root\build\manual_compile.log"
$argfile = "$root\build\mcjavac_args.txt"

if (!(Test-Path $javac)) { Write-Output "javac not found: $javac"; exit 1 }
if (!(Test-Path "$root\build")) { New-Item -ItemType Directory -Path "$root\build" -Force | Out-Null }
if (!(Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir -Force | Out-Null }

# 0) 刷新「构建指纹」资源（src\main\resources\build_info.properties）
#    为什么必须在这里做：日志要能自证「是哪一版代码跑出来的」（见 TECHNICAL_HANDOFF.md §7.3 结论 ⑥）。
#    javac 不处理资源，所以这步不能省；游戏启动时 RsccBuildInfo 会打出唯一一行 [rscc-build]。
#    Python 找不到时只告警不失败（指纹退化为旧值 / "?"，编译照常）。
$stampScript = "$root\tools\gen_build_info.py"
if (Test-Path $stampScript) {
    $python = Get-Command python -ErrorAction SilentlyContinue
    if ($python) {
        & $python.Source $stampScript --quiet
        if ($LASTEXITCODE -ne 0) { Write-Output "[warn] 构建指纹生成失败（exit=$LASTEXITCODE），编译继续" }
    } else {
        Write-Output "[warn] 找不到 python，跳过构建指纹刷新（日志将无法自证版本）"
    }
} else {
    Write-Output "[warn] 找不到 $stampScript，跳过构建指纹刷新"
}

# 1) collect classpath jars (MC compiled artifact + all deps)
#    缓存根与「同族多版本去重」统一交给 tools\_gradle_cache.ps1：
#      * 缓存根**跟随 GRADLE_USER_HOME**（本机 = D:\gradle\caches，Gradle 真正在用的那个）。
#        原先这里写死 C:\Users\70432\.gradle\caches，而两个根里的制品版本并不一致
#        （RS 2.0.0 vs 2.0.9），于是「编译通过」证明的不是 Gradle 实际用的那一份。
#      * 按 (group, artifact, classifier) 去重，每族只留一个版本（pin 优先，其次版本号最高）。
#        原先「全量 glob 拼 classpath」会把同族多版本一起塞进去，谁生效取决于目录枚举顺序。
#    注意：Jade 是「工程内本地制品」（libs/jade-15.10.5+neoforge.jar，见 build.gradle 的说明），
#    因此 helper (a) 显式加入 libs\*.jar，(b) 从 Gradle 缓存里排除同名/同族的 jade 制品，
#    保证**编译脚本与 Gradle 构建用的是同一份制品**（不会出现「缓存里躺着一个旧版本」的歧义）。
. "$PSScriptRoot\_gradle_cache.ps1"
$artifacts = Get-RsccGradleArtifacts -ProjectRoot $root
$jars = New-Object System.Collections.Generic.List[string]
if ($artifacts.McJar) { $jars.Add($artifacts.McJar) }
foreach ($lib in $artifacts.LibJars) { $jars.Add($lib) }
foreach ($mod in $artifacts.ModuleJars) { $jars.Add($mod) }
$classpath = ($jars | Select-Object -Unique) -join ";"

# 自证块：用了哪个缓存 / 哪些版本 / 排除了什么（被排除的版本绝不静默）。
Write-Output "MANUAL_COMPILE_CACHE path=$($artifacts.CachePath) source=$($artifacts.CacheSource)"
Write-Output "MANUAL_COMPILE_CLASSPATH jars=$($jars.Count) scanned=$($artifacts.ScannedJars) excludedDupes=$($artifacts.Excluded.Count)"
Write-Output "MANUAL_COMPILE_PINVERSIONS rs=$(Get-RsccVersionSummary -Artifacts $artifacts)"
foreach ($warn in $artifacts.Warnings) { Write-Output "MANUAL_COMPILE_WARN $warn" }
foreach ($line in $artifacts.Excluded) { Write-Output "MANUAL_COMPILE_EXCLUDED $line" }

# 2) source files as array (must not be joined into one arg)
$sources = @(Get-ChildItem "$root\src\main\java" -Recurse -Filter "*.java" | ForEach-Object { $_.FullName })

# 3) write argfile (backslash is escape char in javac @file, so double it)
$argLines = New-Object System.Collections.Generic.List[string]
$argLines.Add("-cp")
$argLines.Add('"' + $classpath + '"')
$argLines.Add("-encoding")
$argLines.Add("UTF-8")
$argLines.Add("-g")
$argLines.Add("-d")
$argLines.Add('"' + $outDir + '"')
foreach ($s in $sources) { $argLines.Add($s) }
$escaped = New-Object System.Collections.Generic.List[string]
foreach ($l in $argLines) { $escaped.Add($l.Replace('\', '\\')) }
[System.IO.File]::WriteAllLines($argfile, $escaped)

# 4) run javac (-proc:none skips Mixin AP mapping check; runtime remap unaffected)
#    【2026-10-10 修】原实现用固定日志名 "$root\build\manual_compile.log"。
#    多个 agent / 会话并行跑本脚本时该文件被占用，PowerShell 的重定向（Out-File）打开失败，
#    javac 根本没执行，而 $LASTEXITCODE 仍是上一条命令（上面 gen_build_info.py）的 0
#    => 脚本会打印**假阳性**的 "COMPILE OK"。并发开发期这会让人误信编译通过。
#    两道防线：(a) 日志名带 PID，从根本上消除占用冲突；
#              (b) 先删后查——javac 跑完若日志文件不存在或没被本轮创建，判定为「编译未真正执行」并失败。
$logFile = "$root\build\manual_compile.$PID.log"
Remove-Item $logFile -Force -ErrorAction SilentlyContinue
$logCreated = $false
try {
    & $javac -proc:none "-J-Dfile.encoding=UTF-8" "@$argfile" *> $logFile
    $logCreated = Test-Path $logFile
} catch {
    Write-Output "===== COMPILE FAILED: 无法执行 javac 或无法写日志 $logFile ====="
    Write-Output $_
    exit 125
}
if (-not $logCreated) {
    Write-Output "===== COMPILE FAILED: javac 未产生日志（重定向失败 / 未执行），不是编译通过 ====="
    exit 125
}
$exitCode = $LASTEXITCODE
$output = Get-Content $logFile -Encoding Default
$errors = $output | Where-Object { $_ -match "error|cannot find|错误|符号" }
if ($exitCode -eq 0) {
    Write-Output "===== COMPILE OK ($($sources.Count) sources) ====="
} else {
    Write-Output "===== COMPILE FAILED exit=$exitCode ====="
    if ($errors) {
        $errors | Select-Object -First 80 | ForEach-Object { Write-Output $_.Trim() }
    } else {
        $output | Select-Object -First 60 | ForEach-Object { Write-Output $_ }
    }
    exit $exitCode
}
