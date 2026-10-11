# Lead independent compile check（编译校验的独立复算，不信任 gradlew 的结论）。
#
# 【编码约定】本文件必须保存为 **UTF-8 带 BOM**：Windows PowerShell 5.1 在没有 BOM 时
#   会按 GBK 解码 .ps1，中文注释被误解成引号后会直接解析失败（本工程刚踩过一次）。
#   写入后请复核前三字节是否为 EF BB BF。
#
# 【本脚本的 classpath 判据（2026-10-11 修）】两个真实踩过的坑：
#   坑 1：本机 GRADLE_USER_HOME=D:\gradle ⇒ Gradle 真正读写的是 D:\gradle\caches；而本脚本
#     原先硬编码 C:\Users\70432\.gradle\caches，两个根里的制品版本并不一致（RS 2.0.0 vs
#     2.0.9、parchment 映射批次也不同）⇒ 校验的其实不是 Gradle 用的那一份 ⇒ 假绿。
#   坑 2：即便看对了根，原先「全量 glob 拼 classpath」也会把同族多版本（RS 2.0.0 与 2.0.9
#     同时躺在缓存里）一起塞进去，谁生效取决于目录枚举顺序 ⇒ 编译结果不可复现。
#   现在统一走 tools\_gradle_cache.ps1：跟随 GRADLE_USER_HOME + 按
#   (group, artifact, classifier) 去重（pin 优先，其次版本号最高），被排除的版本全部打印。
#   本脚本永远只在 javac 真的跑过、真的 exit 0 时才打印 OK。
$ErrorActionPreference = "Continue"
$root = "D:\MODS\refined_storage_and_create_compat"
$javac = "D:\java21\bin\javac.exe"
$outDir = "$root\build\lead_compile"
$logFile = "$root\build\lead_compile.$PID.log"
$argfile = "$root\build\lead_javac_args.txt"

if (!(Test-Path $javac)) { Write-Output "LEAD_COMPILE_FAILED javac missing"; exit 1 }
New-Item -ItemType Directory -Path $outDir -Force | Out-Null

# 缓存根 + 同族去重：唯一实现放在 tools\_gradle_cache.ps1（与 Python 侧同一套判据）。
. "$PSScriptRoot\_gradle_cache.ps1"
$artifacts = Get-RsccGradleArtifacts -ProjectRoot $root

$jars = New-Object System.Collections.Generic.List[string]
if ($artifacts.McJar) { $jars.Add($artifacts.McJar) }
foreach ($lib in $artifacts.LibJars) { $jars.Add($lib) }
foreach ($mod in $artifacts.ModuleJars) { $jars.Add($mod) }
$classpath = ($jars | Select-Object -Unique) -join ";"

# --- 自证块：把「用了哪个缓存 / 哪份 MC / 哪些版本 / 排除了什么」全部打出来 ---
# 为什么要打在 javac 之前：javac 失败时这段证据也必须可见（否则又变成「看不见的判据」）。
Write-Output "LEAD_COMPILE_CACHE path=$($artifacts.CachePath) source=$($artifacts.CacheSource)"
Write-Output "LEAD_COMPILE_MC jar=$($artifacts.McJar) candidates=$($artifacts.McCandidates.Count)"
Write-Output "LEAD_COMPILE_CLASSPATH jars=$($jars.Count) scanned=$($artifacts.ScannedJars) excludedDupes=$($artifacts.Excluded.Count)"
Write-Output "LEAD_COMPILE_PINVERSIONS rs=$(Get-RsccVersionSummary -Artifacts $artifacts)"
foreach ($warn in $artifacts.Warnings) { Write-Output "LEAD_COMPILE_WARN $warn" }
foreach ($line in $artifacts.Excluded) { Write-Output "LEAD_COMPILE_EXCLUDED $line" }

$sources = @(Get-ChildItem "$root\src\main\java" -Recurse -Filter "*.java" | ForEach-Object { $_.FullName })

$argLines = New-Object System.Collections.Generic.List[string]
$argLines.Add("-cp"); $argLines.Add('"' + $classpath + '"')
$argLines.Add("-encoding"); $argLines.Add("UTF-8")
$argLines.Add("-g"); $argLines.Add("-d"); $argLines.Add('"' + $outDir + '"')
foreach ($s in $sources) { $argLines.Add($s) }
$escaped = New-Object System.Collections.Generic.List[string]
foreach ($l in $argLines) { $escaped.Add($l.Replace('\', '\\')) }
[System.IO.File]::WriteAllLines($argfile, $escaped)

Remove-Item $logFile -Force -ErrorAction SilentlyContinue
try {
    & $javac -proc:none "-J-Dfile.encoding=UTF-8" "@$argfile" *> $logFile
} catch {
    Write-Output "LEAD_COMPILE_FAILED cannot run javac"
    exit 125
}
if (!(Test-Path $logFile)) {
    Write-Output "LEAD_COMPILE_FAILED javac produced no log (redirect failed / not executed)"
    exit 125
}
$exitCode = $LASTEXITCODE
# Read as Default (this host is GBK): javac prints localized errors, so matching on the
# ASCII word "error:" is not enough. On failure, always dump the log head as-is.
$output = @(Get-Content $logFile -Encoding Default -ErrorAction SilentlyContinue)
if ($exitCode -eq 0) {
    Write-Output "LEAD_COMPILE_OK sources=$($sources.Count)"
    # 版本摘要放在 OK 之后：让「通过」的那一行始终跟着「对着哪一版通过的」。
    Write-Output "LEAD_COMPILE_VERSION_SUMMARY rs=$(Get-RsccVersionSummary -Artifacts $artifacts) cache=$($artifacts.CachePath)"
} else {
    $nonEmpty = @($output | Where-Object { $_.Trim() -ne '' })
    Write-Output "LEAD_COMPILE_FAILED exit=$exitCode logLines=$($nonEmpty.Count)"
    $nonEmpty | Select-Object -First 40 | ForEach-Object { Write-Output $_.Trim() }
    exit $exitCode
}
