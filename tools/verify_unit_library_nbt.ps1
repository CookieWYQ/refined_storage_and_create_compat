# Offline (no game launch) round-trip verification for GrowingUnitLibrary NBT persistence.
# Requires: tools\manual_compile.ps1 already ran (builds build\manual_compile).
# Usage: & .\tools\verify_unit_library_nbt.ps1
# 注意：本文件必须保存为 **UTF-8 带 BOM**（PowerShell 5.1 无 BOM 时按 GBK 解码中文注释会解析失败）。

$ErrorActionPreference = "Continue"
$root = "d:\MODS\refined_storage_and_create_compat"
$javac = "D:\java21\bin\javac.exe"
$java = "D:\java21\bin\java.exe"
$modOut = "$root\build\manual_compile"
$testOutDir = "$root\build\nbt_verify"
$argfile = "$root\build\nbt_verify_args.txt"

if (!(Test-Path $javac)) { Write-Output "javac not found: $javac"; exit 1 }
if (!(Test-Path $modOut)) { Write-Output "mod classes not found, run tools\manual_compile.ps1 first"; exit 1 }
if (!(Test-Path $testOutDir)) { New-Item -ItemType Directory -Path $testOutDir -Force | Out-Null }

# 1) classpath: mod classes + MC/deps jars (same set as manual_compile)
#    缓存根 / 同族去重统一走 tools\_gradle_cache.ps1（跟随 GRADLE_USER_HOME，按
#    (group, artifact, classifier) 每族只留一个版本，pin 优先）—— 原先写死
#    C:\Users\70432\.gradle\caches 且全量 glob，同族多版本同时进 classpath 时谁生效
#    取决于目录顺序，运行期验证结果因此不可复现。
. "$PSScriptRoot\_gradle_cache.ps1"
$artifacts = Get-RsccGradleArtifacts -ProjectRoot $root
$jars = New-Object System.Collections.Generic.List[string]
$jars.Add($modOut)
if ($artifacts.McJar) { $jars.Add($artifacts.McJar) }
foreach ($mod in $artifacts.ModuleJars) { $jars.Add($mod) }
# 说明：历史上手写「跳过缓存里旧的 fastutil 8.3.1（运行时缺 IntList.of）」这一步，现在由
# 同族去重自动完成（8.5.12 版本号更高而胜出，并被打印进 NBT_VERIFY_EXCLUDED）。这里保留
# 一道显式过滤作为兜底（去重逻辑万一退回时仍然安全）。
$jars = @($jars | Where-Object { $_ -notmatch 'fastutil.+8\.3\.1' })
$classpath = ($jars | Select-Object -Unique) -join ";"
Write-Output "NBT_VERIFY_CACHE path=$($artifacts.CachePath) source=$($artifacts.CacheSource)"
Write-Output "NBT_VERIFY_CLASSPATH jars=$($jars.Count) excludedDupes=$($artifacts.Excluded.Count)"
Write-Output "NBT_VERIFY_PINVERSIONS rs=$(Get-RsccVersionSummary -Artifacts $artifacts)"
foreach ($line in $artifacts.Excluded) { Write-Output "NBT_VERIFY_EXCLUDED $line" }

# 2) compile the test
$src = "$root\tools\nbt_verify\UnitLibraryNbtRoundTrip.java"
[System.IO.File]::WriteAllLines($argfile, @(
    "-cp",
    ('"' + ($classpath.Replace('\', '\\')) + '"'),
    "-encoding", "UTF-8",
    "-d", ('"' + ($testOutDir.Replace('\', '\\')) + '"'),
    ($src.Replace('\', '\\'))
))
& $javac -proc:none "-J-Dfile.encoding=UTF-8" "@$argfile"
if ($LASTEXITCODE -ne 0) { Write-Output "===== NBT VERIFY COMPILE FAILED ====="; exit 1 }

# 3) run the test
& $java "-Dfile.encoding=UTF-8" "-cp" "$testOutDir;$classpath" "UnitLibraryNbtRoundTrip"
exit $LASTEXITCODE
