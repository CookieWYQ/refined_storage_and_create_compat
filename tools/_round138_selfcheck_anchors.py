# -*- coding: utf-8 -*-
"""第 138 轮：把 11 个自检脚本里「旧写法的字面量锚点」更新到新形状（判据不放松，反例同步换）。"""
import io, os, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOOLS = os.path.join(ROOT, "tools")

EDITS = {
    "selfcheck_round25_exec_chain_logical_chamber.py": [
        ('      and "sameFamily(level.getBlockState(neighbor).getBlock(), originBlock)" in cluster)',
         '      and "sameFamily(neighborState.getBlock(), originBlock)" in cluster)'),
    ],
    "selfcheck_round42_air_rebind_entry.py": [
        ('''    check("锚点① 只读扫描已加载区块，绝不因取网络而加载区块",
          "level.isLoaded(pos)" in nearby
          and "player.serverLevel()" in nearby''',
         '''    check("锚点① 只读已 FULL 区块（第 138 轮 SafeChunkRead），绝不因取网络而加载区块",
          "SafeChunkRead.blockStateNow(level, pos)" in nearby
          and "SafeChunkRead.blockEntityNow(level, pos)" in nearby
          and "level.isLoaded(pos)" not in nearby
          and "player.serverLevel()" in nearby'''),
    ],
    "selfcheck_round59_binding_cache_freshness.py": [
        ('''check("① 供料目标解析 = 「朝向 relative」+「getChunkAt().getBlockEntity(CHECK)」，O(1)、无 BFS",
      supply_target is not None
      and "level.getChunkAt(exporterPos).getBlockEntity(exporterPos, LevelChunk.EntityCreationType.CHECK)"
          in supply_target''',
         '''check("① 供料目标解析 = 「朝向 relative」+「SafeChunkRead.blockEntityNow（只读已 FULL 区块）」，O(1)、无 BFS",
      supply_target is not None
      and "SafeChunkRead.blockEntityNow(level, exporterPos)" in supply_target
      and "level.getChunkAt(" not in supply_target'''),
    ],
    "selfcheck_round101_generator.py": [
        ('    expect("D29 推送前不加载区块（isLoaded 守卫）", "level.isLoaded(target)" in push_body)',
         '    expect("D29 推送前不加载区块（第 138 轮：SafeChunkRead 安全读，绝不阻塞）",\n'
         '           "SafeChunkRead.blockEntityNow(level, target)" in push_body\n'
         '           and "level.isLoaded(target)" not in push_body\n'
         '           and "level.getBlockEntity(target)" not in push_body)'),
        ('''counterexample("CE11 推送不加载区块守卫（会触发区块加载）", "be",
               "if (!level.isLoaded(target)) {\\n                continue;\\n            }", "")''',
         '''counterexample("CE11 推送改成阻塞读（会同步等区块生成）", "be",
               "final BlockEntity neighbour = SafeChunkRead.blockEntityNow(level, target);",
               "final BlockEntity neighbour = level.getBlockEntity(target);")'''),
    ],
    "selfcheck_round99_energy_cube.py": [
        ('        and "if (level.getBlockState(target).getBlock() instanceof FEEnergyCubeBlock) {" in push',
         '        and "if (targetState.getBlock() instanceof FEEnergyCubeBlock) {" in push'),
        ('        and "if (!level.isLoaded(target)) {" in push',
         '        and "SafeChunkRead.blockStateNow(level, target)" in push\n'
         '        and "level.isLoaded(target)" not in push'),
        ('''                   push_source_ok(be_text.replace(
                       "if (level.getBlockState(target).getBlock() instanceof FEEnergyCubeBlock) {",
                       "if (false) {")) is False)''',
         '''                   push_source_ok(be_text.replace(
                       "if (targetState.getBlock() instanceof FEEnergyCubeBlock) {",
                       "if (false) {")) is False)'''),
    ],
    "selfcheck_round68_remote_terminal_open.py": [
        ('is_loaded_in_resolve = ".isLoaded(" in resolve_body',
         'safe_read_in_resolve = "SafeChunkRead.blockEntityNow(" in resolve_body'),
        ('check(is_loaded_files == ["item"] and is_loaded_in_resolve and bind_delegates,',
         'check(is_loaded_files == [] and safe_read_in_resolve and bind_delegates,'),
        ('      "B1 整个打开链路里 `.isLoaded(` **只**出现在解析 helper resolveBoundNetwork（决定「绑真网络还是空网络」），"',
         '      "B1 整个打开链路里没有任何 `.isLoaded(`（第 138 轮改为 SafeChunkRead 安全读）；解析 helper resolveBoundNetwork 用安全读决定「绑真网络还是空网络」，"'),
        ('      % (is_loaded_files, is_loaded_in_resolve, bind_delegates))',
         '      % (is_loaded_files, safe_read_in_resolve, bind_delegates))'),
        ('      "出现文件=%s；位于 resolveBoundNetwork=%s；bindNetworkToVirtualBe 复用=%s"',
         '      "出现文件=%s；resolveBoundNetwork 用安全读=%s；bindNetworkToVirtualBe 复用=%s"'),
    ],
    "selfcheck_round71_new_machine_pacing.py": [
        ('      and ("level.getBlockEntity(lastResolvedTargetPos)" in',
         '      and ("SafeChunkRead.blockEntityNow(level, lastResolvedTargetPos)" in'),
    ],
    "selfcheck_round100_terminal_power_gate.py": [
        ('        "isLoaded(location.pos())",',
         '        "SafeChunkRead.blockEntityNow(",'),
        ('    check("④-d 绑定解析唯一实现 resolveBoundNetwork（含跨维度 dimension + isLoaded），判据复用它",',
         '    check("④-d 绑定解析唯一实现 resolveBoundNetwork（含跨维度 dimension + SafeChunkRead 安全读），判据复用它",'),
    ],
}

fail = 0
for rel, pairs in EDITS.items():
    path = os.path.join(TOOLS, rel)
    text = io.open(path, encoding="utf-8").read()
    ok = True
    for old, new in pairs:
        if text.count(old) != 1:
            print("FAIL %s: count=%d for %r" % (rel, text.count(old), old[:70]))
            ok = False
            fail += 1
            continue
        text = text.replace(old, new, 1)
    if ok:
        io.open(path, "w", encoding="utf-8", newline="\n").write(text)
        print("OK %s" % rel)
print("FAILURES=%d" % fail)
sys.exit(1 if fail else 0)
