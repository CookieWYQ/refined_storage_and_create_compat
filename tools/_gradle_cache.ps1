# Gradle 缓存根定位 + 同族制品去重（PowerShell 版；与 tools\_gradle_cache.py 是同一套判据）。
#
# 【为什么需要这个文件】两个真实踩过的坑：
#
# 坑 1「看错缓存 ⇒ 假绿」：本机 GRADLE_USER_HOME=D:\gradle，Gradle 真正读写的是
#   D:\gradle\caches；但本目录下多个编译/校验脚本曾硬编码 C:\Users\70432\.gradle\caches
#   （另一个缓存根）。两个根的内容并不一致（jar 数量不同、同一坐标的版本不同），
#   于是「校验通过」证明的是另一个缓存里的制品，跟 Gradle 实际解析到的那一份不是同一个
#   文件 —— 典型的假绿。所以这里统一**跟随 GRADLE_USER_HOME**，并把来源打印出来自证。
#
# 坑 2「同族多版本同时命中 ⇒ 结果不确定」：同一个缓存根里同一 group:artifact 可以并存
#   多个版本（refinedstorage-neoforge 2.0.0 与 2.0.9 就是这样并存下来的）。原先
#   「全量 glob 拼 classpath」会把两个版本一起塞进去，最终谁生效取决于目录枚举顺序
#   ⇒ 编译结果不可复现。这里按 (group, artifact, classifier) 分组，每组只留一个版本：
#     ① gradle.properties 里 pin 的版本优先；
#     ② 没有 pin 的取版本号最高（自然序，见 ConvertTo-RsccVersionKey）；
#     ③ 被排除的版本全部由调用方打印，绝不静默。
#
# 【编码约定】本文件必须保存为 **UTF-8 带 BOM**：Windows PowerShell 5.1 在没有 BOM 时
#   会按 GBK 解码 .ps1，中文注释会被误解成引号而解析失败（本工程刚踩过一次）。
#
# 【反例自证】设环境变量 RSCC_PIN_<属性名大写>=<版本> 即可临时覆盖 pin（不改任何文件），
#   例如 RSCC_PIN_REFINEDSTORAGE_VERSION=2.0.0 ⇒ 选 2.0.0 并显式说明「来自环境变量覆盖」。

# 坐标 → gradle.properties 里 pin 它的属性名（有 pin 就优先用 pin 的版本）。
$RsccPinProperties = @{
    "com.refinedmods.refinedstorage:refinedstorage-neoforge"                = "refinedstorage_version"
    "com.refinedmods.refinedstorage:refinedstorage-quartz-arsenal-neoforge" = "refinedstorageQuartzArsenalVersion"
    "com.simibubi.create:create-1.21.1"                                     = "create_version"
}
$RsccPinEnvPrefix = "RSCC_PIN_"


# 返回缓存根：优先 GRADLE_USER_HOME（Gradle 真正在用的那个），其次 ~\.gradle\caches
# （兼容旧行为），最后 D:\gradle\caches 兜底。Source 字段说明「用了哪个、为什么」。
function Get-RsccGradleCacheRoot {
    $notes = @()
    $gradleHome = $env:GRADLE_USER_HOME
    if ($gradleHome -and $gradleHome.Trim() -ne '') {
        $gradleHome = $gradleHome.Trim()
        $candidate = Join-Path $gradleHome 'caches'
        if (Test-Path $candidate -PathType Container) {
            return [pscustomobject]@{ Path = $candidate; Source = "GRADLE_USER_HOME=$gradleHome" }
        }
        if ((Test-Path $gradleHome -PathType Container) -and ((Split-Path $gradleHome -Leaf) -ieq 'caches')) {
            return [pscustomobject]@{ Path = $gradleHome; Source = "GRADLE_USER_HOME=$gradleHome（本身即 caches 目录）" }
        }
        $notes += "[警告] GRADLE_USER_HOME=$gradleHome 下找不到 caches 目录 ⇒ 回退默认缓存根"
    }
    $legacy = Join-Path $env:USERPROFILE ".gradle\caches"
    if (Test-Path $legacy -PathType Container) {
        return [pscustomobject]@{ Path = $legacy
            Source = (($notes + "默认 $legacy（GRADLE_USER_HOME 未设置）") -join '；') }
    }
    $fallback = "D:\gradle\caches"
    if (Test-Path $fallback -PathType Container) {
        return [pscustomobject]@{ Path = $fallback; Source = (($notes + "回退 $fallback") -join '；') }
    }
    return [pscustomobject]@{ Path = $legacy; Source = (($notes + "默认 $legacy（当前不存在）") -join '；') }
}


# 读 gradle.properties（Java Properties 的最简子集：key=value，忽略 # / ! 注释行）。
function Read-RsccGradleProperties {
    param([string]$ProjectRoot)
    $props = @{}
    if (-not $ProjectRoot) { return $props }
    $path = Join-Path $ProjectRoot 'gradle.properties'
    if (-not (Test-Path $path)) { return $props }
    foreach ($line in [System.IO.File]::ReadAllLines($path)) {
        $t = $line.Trim()
        if ($t -eq '' -or $t.StartsWith('#') -or $t.StartsWith('!')) { continue }
        $i = $t.IndexOf('=')
        if ($i -lt 1) { continue }
        $props[$t.Substring(0, $i).Trim()] = $t.Substring($i + 1).Trim()
    }
    return $props
}


# 返回 @{ "group:artifact" = @{ Version=...; Source=... } }；环境变量可临时覆盖（反例自证用）。
function Get-RsccPinnedMap {
    param([string]$ProjectRoot)
    $props = Read-RsccGradleProperties -ProjectRoot $ProjectRoot
    $pins = @{}
    foreach ($coord in $RsccPinProperties.Keys) {
        $prop = $RsccPinProperties[$coord]
        $version = $props[$prop]
        $source = "gradle.properties:$prop"
        $envName = $RsccPinEnvPrefix + $prop.ToUpperInvariant()
        $override = [System.Environment]::GetEnvironmentVariable($envName)
        if ($override -and $override.Trim() -ne '') {
            $version = $override.Trim()
            $source = "环境变量 $envName=$version（临时覆盖 gradle.properties:$prop）"
        }
        if ($version) {
            $pins[$coord] = [pscustomobject]@{ Version = $version; Source = $source }
        }
    }
    return $pins
}


# 版本号自然序排序键：数字段补零到 12 位后仍按字符串比较，于是
# "2.0.9" > "2.0.0"、"9.10.1" > "9.9.1"、"6.0-alpha-3" > "5.0.4"。
function ConvertTo-RsccVersionKey {
    param([string]$Version)
    $sb = New-Object System.Text.StringBuilder
    foreach ($m in [regex]::Matches([string]$Version, '\d+|\D+')) {
        $tok = $m.Value
        if ($tok -match '^\d+$') {
            [void]$sb.Append($tok.PadLeft(12, '0'))
        } else {
            [void]$sb.Append($tok.ToLowerInvariant())
        }
        [void]$sb.Append('|')
    }
    return $sb.ToString()
}


# 沿用历史规则：哪些缓存 jar 不该进 classpath。
# sources/javadoc 不是可编译制品；natives-windows 是平台本地库；jade 是工程内本地制品
# （libs\jade-*.jar），必须排除缓存里的同名族以免「缓存里躺着一个旧版本」的歧义。
function Test-RsccJarAllowed {
    param([System.IO.FileInfo]$Jar)
    if ($Jar.Name -match '(?i)sources|javadoc|natives-windows') { return $false }
    if ($Jar.Name -match '(?i)^jade-') { return $false }
    if ($Jar.FullName -match '(?i)parchment|fabric-loader|yarn|sponge-mixin-transformer') { return $false }
    return $true
}


# 从文件名剥出 classifier（"" = 无 classifier）；不符合 <artifact>-<version>[-<classifier>].jar
# 布局时返回 $null ⇒ 调用方按「无法判定同族」处理，原样保留，绝不因为看不出来而丢 jar。
function Get-RsccClassifier {
    param([string]$FileName, [string]$Artifact, [string]$Version)
    if (-not $FileName.ToLower().EndsWith('.jar')) { return $null }
    $stem = $FileName.Substring(0, $FileName.Length - 4)
    $prefix = "$Artifact-$Version"
    if ($stem -eq $prefix) { return '' }
    if ($stem.StartsWith($prefix + '-')) { return $stem.Substring($prefix.Length + 1) }
    return $null
}


# 收集并去重整个 classpath 所需的 Gradle 侧制品。返回：
#   CachePath / CacheSource —— 用的是哪个缓存根、为什么
#   McJar / McCandidates    —— Minecraft 编译产物（多份候选时取 mtime 最新）
#   LibJars                 —— $ProjectRoot\libs\*.jar（工程内本地制品，如 jade）
#   ModuleJars              —— 去重后的 modules-2 制品路径（按路径排序，顺序确定）
#   Chosen / Excluded       —— 每族最终选了谁 / 被谁顶掉（含原因），供调用方打印
#   Warnings                —— pin 缺失之类的告警
function Get-RsccGradleArtifacts {
    param(
        [string]$ProjectRoot = "D:\MODS\refined_storage_and_create_compat",
        [string]$CacheRoot
    )
    $cacheInfo = Get-RsccGradleCacheRoot
    if ($CacheRoot) { $cacheInfo = [pscustomobject]@{ Path = $CacheRoot; Source = "显式指定 $CacheRoot" } }
    $pins = Get-RsccPinnedMap -ProjectRoot $ProjectRoot
    $warnings = New-Object System.Collections.Generic.List[string]

    # --- Minecraft 编译产物：多份候选（不同 parchment 映射批次）时取 mtime 最新 ---
    $mcCandidates = @()
    if ($cacheInfo.Path) {
        $mcCandidates = @(Get-ChildItem (Join-Path $cacheInfo.Path 'neoformruntime\intermediate_results') `
                -Filter 'compiledWithNeoForge_*_output.jar' -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime, FullName)
    }
    $mcJar = $null
    if ($mcCandidates.Count -gt 0) { $mcJar = $mcCandidates[-1].FullName }
    if ($mcCandidates.Count -gt 1) {
        $warnings.Add("MC 编译产物有 $($mcCandidates.Count) 份候选（不同 parchment 映射批次）⇒ 取 mtime 最新：$($mcCandidates[-1].Name)")
    }

    # --- 工程内本地制品（jade 等）---
    $libJars = @()
    $libsDir = Join-Path $ProjectRoot 'libs'
    if (Test-Path $libsDir) {
        $libJars = @(Get-ChildItem $libsDir -Filter '*.jar' | Sort-Object FullName | ForEach-Object { $_.FullName })
    } else {
        $warnings.Add("[警告] 找不到 $libsDir（Jade 等本地可选依赖制品应放在这里）")
    }

    # --- modules-2：按 (group, artifact, classifier) 分组后每组只留一个版本 ---
    $base = Join-Path $cacheInfo.Path 'modules-2\files-2.1'
    $buckets = @{}
    $unkeyed = New-Object System.Collections.Generic.List[string]
    $total = 0
    if (Test-Path $base) {
        foreach ($jar in Get-ChildItem $base -Recurse -Filter '*.jar' -ErrorAction SilentlyContinue) {
            if (-not (Test-RsccJarAllowed -Jar $jar)) { continue }
            $total++
            $rel = $jar.FullName.Substring($base.Length).TrimStart('\')
            $parts = $rel.Split('\')
            if ($parts.Length -lt 5) { $unkeyed.Add($jar.FullName); continue }
            $cls = Get-RsccClassifier -FileName $jar.Name -Artifact $parts[1] -Version $parts[2]
            if ($null -eq $cls) { $unkeyed.Add($jar.FullName); continue }
            $key = "$($parts[0]):$($parts[1])[$cls]"
            if (-not $buckets.ContainsKey($key)) {
                $buckets[$key] = New-Object System.Collections.Generic.List[object]
            }
            $buckets[$key].Add([pscustomobject]@{
                    Path = $jar.FullName; Group = $parts[0]; Artifact = $parts[1]
                    Version = $parts[2]; Classifier = $cls
                })
        }
    }

    $kept = New-Object System.Collections.Generic.List[string]
    $chosen = New-Object System.Collections.Generic.List[object]
    $excluded = New-Object System.Collections.Generic.List[string]
    foreach ($path in $unkeyed) { $kept.Add($path) }

    foreach ($key in ($buckets.Keys | Sort-Object)) {
        $items = $buckets[$key]
        $group = $items[0].Group
        $artifact = $items[0].Artifact
        $classifier = $items[0].Classifier
        $coord = "$group`:$artifact"
        $label = if ($classifier -eq '') { $coord } else { "$coord[$classifier]" }

        $byVersion = @{}
        foreach ($item in $items) {
            if (-not $byVersion.ContainsKey($item.Version)) { $byVersion[$item.Version] = @() }
            $byVersion[$item.Version] += $item
        }
        $versionsSorted = @($byVersion.Keys | Sort-Object @{ Expression = { ConvertTo-RsccVersionKey $_ } }, @{ Expression = { $_ } })
        $pin = $pins[$coord]
        if ($pin -and $byVersion.ContainsKey($pin.Version)) {
            $want = $pin.Version
            $reason = "pin 命中（$($pin.Source)）"
        } else {
            $want = $versionsSorted[-1]
            $reason = "版本号最高"
            if ($pin) {
                $reason += "；pin $($pin.Version)（$($pin.Source)）在缓存里没有该版本"
                $warnings.Add("[警告] $coord 的 pin 版本 $($pin.Version)（$($pin.Source)）在缓存里找不到 ⇒ 本次退回 $want；结果与 gradle.properties 的编译基线不一致，请先跑一次 gradle 依赖解析")
            }
        }
        $sameVersion = @($byVersion[$want] | Sort-Object Path)
        $kept.Add($sameVersion[0].Path)
        $chosen.Add($sameVersion[0])
        foreach ($dup in $sameVersion | Select-Object -Skip 1) {
            $excluded.Add("$($dup.Path)  ->  $label：同版本 $want 有 $($sameVersion.Count) 份拷贝，按路径取第一个以保证结果确定")
        }
        foreach ($version in $versionsSorted) {
            if ($version -eq $want) { continue }
            foreach ($old in ($byVersion[$version] | Sort-Object Path)) {
                $excluded.Add("$($old.Path)  ->  $label：版本 $version 被 $want 顶掉（$reason）")
            }
        }
    }

    return [pscustomobject]@{
        CachePath     = $cacheInfo.Path
        CacheSource   = $cacheInfo.Source
        Pins          = $pins
        McJar         = $mcJar
        McCandidates  = @($mcCandidates | ForEach-Object { $_.FullName })
        LibJars       = $libJars
        ModuleJars    = @($kept | Sort-Object)
        Chosen        = @($chosen | Sort-Object Path)
        Excluded      = @($excluded | Sort-Object)
        Warnings      = @($warnings)
        ScannedJars   = $total
    }
}


# 一行「classpath 版本摘要」：至少含 Refined Storage 的实际版本，让下次一眼看出用的是哪一版。
function Get-RsccVersionSummary {
    param($Artifacts)
    $rs = @($Artifacts.Chosen | Where-Object { $_.Group -eq 'com.refinedmods.refinedstorage' } |
        ForEach-Object { "$($_.Artifact)=$($_.Version)" } | Sort-Object)
    if ($rs.Count -eq 0) { $rs = @('<无>') }
    return ($rs -join ', ')
}
