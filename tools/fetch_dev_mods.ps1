# 拉取「开发环境运行时」需要、但工程没有以 Maven 依赖方式引入的前置模组。
#
# 本脚本负责两个必装前置：
#   1) Curios API（modId: curios）—— 饰品栏 API。本工程只 compileOnly 它（代码走反射调用），
#      因此**运行环境不会自动带上**；而 runClient / 玩家实例都要求它在场，否则加载器报「缺失 curios」。
#   2) Refined Storage - Curios Integration（modId: refinedstorage_curios_integration）——
#      它提供本模组终端所用的**Curios 饰品槽本体**与槽位背景精灵
#      refinedstorage_curios_integration:slot/curios（本模组不再自建饰品槽）。
#
# 为什么需要本脚本：
#   终端要能放进饰品槽，就必须有前置模组 Refined Storage - Curios Integration 提供的槽位
#   （见 src/main/templates/META-INF/neoforge.mods.toml 的 required 依赖与
#   data/curios/tags/item/refinedstorage_curios_integration.json 的物品标签）。
#   该模组只发布在 CurseForge / Modrinth，不在工程 build.gradle 使用的 Maven 仓库里，
#   所以开发环境（runClient / runServer）必须把它放进 run/mods；否则饰品槽整块不存在，
#   游戏内也会因「必装前置缺失」而拒绝加载本模组。
#
# 用法（工程根目录）：
#   powershell -ExecutionPolicy Bypass -File tools\fetch_dev_mods.ps1          # 缺什么补什么
#   powershell -ExecutionPolicy Bypass -File tools\fetch_dev_mods.ps1 -Force   # 强制重新下载
param([switch]$Force)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$modsDir = Join-Path $root 'run\mods'
New-Item -ItemType Directory -Force -Path $modsDir | Out-Null

# 版本与下载地址取自 Modrinth 官方 API（game_versions=1.21.1, loader=neoforge）。
# 升级该前置时：先查 https://api.modrinth.com/v2/project/refined-storage-curios-integration/version
# 取 1.21.1 + neoforge 的最新一条，替换下面的 file/url。
$mods = @(
    @{
        name = 'curios'
        file = 'curios-neoforge-9.5.1+1.21.1.jar'
        url  = 'https://cdn.modrinth.com/data/vvuO3ImH/versions/yohfFbgD/curios-neoforge-9.5.1%2B1.21.1.jar'
    }
    @{
        name = 'refinedstorage-curios-integration'
        file = 'refinedstorage-curios-integration-1.0.0.jar'
        url  = 'https://cdn.modrinth.com/data/s6zjL86N/versions/Mth1azz7/refinedstorage-curios-integration-1.0.0.jar'
    }
    # 连锁套壳（FTB Ultimine）实测所需的三个模组。FTB 的模组**不在 Modrinth**，走 FTB 官方 maven。
    # 注意 run\mods 里还需 Architectury API（Modrinth 项目 architectury-api，
    # 实测版本 13.0.11+neoforge → 文件名 architectury-13.0.11-neoforge.jar），
    # 以及「精致存储」与「机械动力」本身（它们走 Gradle 依赖，不在本脚本范围内）。
    @{
        name = 'ftb-ultimine-neoforge'
        file = 'ftb-ultimine-neoforge-2101.1.15.jar'
        url  = 'https://maven.ftb.dev/releases/dev/ftb/mods/ftb-ultimine-neoforge/2101.1.15/ftb-ultimine-neoforge-2101.1.15.jar'
    }
    @{
        name = 'ftb-library-neoforge'
        file = 'ftb-library-neoforge-2101.1.36.jar'
        url  = 'https://maven.ftb.dev/releases/dev/ftb/mods/ftb-library-neoforge/2101.1.36/ftb-library-neoforge-2101.1.36.jar'
    }
)

$failed = 0
foreach ($mod in $mods) {
    $target = Join-Path $modsDir $mod.file
    if ((Test-Path $target) -and -not $Force) {
        "[skip] $($mod.file) 已存在（$((Get-Item $target).Length) bytes）"
        continue
    }
    try {
        Invoke-WebRequest -Uri $mod.url -OutFile $target -TimeoutSec 180
        "[ok]   $($mod.file) -> run\mods（$((Get-Item $target).Length) bytes）"
    } catch {
        $failed++
        "[fail] $($mod.name)：$($_.Exception.Message)"
    }
}

if ($failed -gt 0) {
    "有 $failed 个前置下载失败；可手动从 CurseForge / Modrinth 下载后放入 run\mods。"
    exit 1
}
"完成：run\mods 已具备本模组运行所需的前置模组。"
