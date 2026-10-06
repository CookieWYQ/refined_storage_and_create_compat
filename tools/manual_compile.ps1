# Local javac compile using cached Gradle jars. No network (bypasses sandbox).
# Usage: & .\tools\manual_compile.ps1

$ErrorActionPreference = "Continue"
$root = "d:\MODS\refined_storage_and_create_compat"
$gradleCache = "C:\Users\70432\.gradle\caches"
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
#    注意：Jade 是「工程内本地制品」（libs/jade-15.10.5+neoforge.jar，见 build.gradle 的说明），
#    因此这里 (a) 显式加入 libs\*.jar，(b) 从 Gradle 缓存里排除同名/同族的 jade 制品，
#    保证**编译脚本与 Gradle 构建用的是同一份制品**（不会出现「缓存里躺着一个旧版本」的歧义）。
$jars = New-Object System.Collections.Generic.List[string]
$mcJar = Get-ChildItem "$gradleCache\neoformruntime\intermediate_results" -Filter "compiledWithNeoForge_*_output.jar" | Select-Object -First 1
if ($mcJar) { $jars.Add($mcJar.FullName) }
$libsDir = "$root\libs"
if (Test-Path $libsDir) {
    Get-ChildItem $libsDir -Filter "*.jar" | ForEach-Object { $jars.Add($_.FullName) }
} else {
    Write-Output "[warn] 找不到 $libsDir（Jade 等本地可选依赖制品应放在这里）"
}
Get-ChildItem "$gradleCache\modules-2\files-2.1" -Recurse -Filter "*.jar" |
    Where-Object {
        $_.Name -notmatch "sources|javadoc" -and
        $_.Name -notmatch "natives-windows" -and
        $_.Name -notmatch "^(?i)jade-" -and
        $_.FullName -notmatch "parchment|fabric-loader|yarn|sponge-mixin-transformer"
    } |
    ForEach-Object { $jars.Add($_.FullName) }
$classpath = ($jars | Select-Object -Unique) -join ";"

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
& $javac -proc:none "-J-Dfile.encoding=UTF-8" "@$argfile" *> $logFile
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
