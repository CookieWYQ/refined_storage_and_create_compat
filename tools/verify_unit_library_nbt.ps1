# Offline (no game launch) round-trip verification for GrowingUnitLibrary NBT persistence.
# Requires: tools\manual_compile.ps1 already ran (builds build\manual_compile).
# Usage: & .\tools\verify_unit_library_nbt.ps1

$ErrorActionPreference = "Continue"
$root = "d:\MODS\refined_storage_and_create_compat"
$gradleCache = "C:\Users\70432\.gradle\caches"
$javac = "D:\java21\bin\javac.exe"
$java = "D:\java21\bin\java.exe"
$modOut = "$root\build\manual_compile"
$testOutDir = "$root\build\nbt_verify"
$argfile = "$root\build\nbt_verify_args.txt"

if (!(Test-Path $javac)) { Write-Output "javac not found: $javac"; exit 1 }
if (!(Test-Path $modOut)) { Write-Output "mod classes not found, run tools\manual_compile.ps1 first"; exit 1 }
if (!(Test-Path $testOutDir)) { New-Item -ItemType Directory -Path $testOutDir -Force | Out-Null }

# 1) classpath: mod classes + MC/deps jars (same set as manual_compile)
# Note: skip cached old fastutil 8.3.1 (missing IntList.of used at runtime) to avoid version clash.
$jars = New-Object System.Collections.Generic.List[string]
$jars.Add($modOut)
$mcJar = Get-ChildItem "$gradleCache\neoformruntime\intermediate_results" -Filter "compiledWithNeoForge_*_output.jar" | Select-Object -First 1
if ($mcJar) { $jars.Add($mcJar.FullName) }
$allJars = Get-ChildItem "$gradleCache\modules-2\files-2.1" -Recurse -Filter "*.jar"
foreach ($jar in $allJars) {
    $name = $jar.Name
    $full = $jar.FullName
    if ($name -match "sources|javadoc") { continue }
    if ($name -match "natives-windows") { continue }
    if ($full -match "parchment|fabric-loader|yarn|sponge-mixin-transformer") { continue }
    if ($full -match "fastutil.+8\.3\.1") { continue }
    $jars.Add($full)
}
$classpath = ($jars | Select-Object -Unique) -join ";"
Write-Output "classpath entries: $($jars.Count)"

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
