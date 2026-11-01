# -*- coding: utf-8 -*-
"""第 131 轮：把 gui_slot_registry.py 里「请求器设置界面」的槽位账整块删掉。

为什么：`NetworkResourceRequesterScreen` 已按用户要求整页删除，请求器的 6 个升级槽也删了
（用户：红石激活速度决定发送速度，跟插件槽没关系）。登记表还留着就会：
 ① 指向已删除的 screen 文件（锚点全失效）；② 要求旧 PNG 有 6 个插件槽框（实际已无界面使用）。
"""
from pathlib import Path

p = Path(r"D:\MODS\refined_storage_and_create_compat\tools\gui_slot_registry.py")
lines = p.read_text(encoding="utf-8").split("\n")

out = []
i = 0
removed_blocks = 0
while i < len(lines):
    line = lines[i]
    # 块 1：png="network_resource_requester.png" 的 dict(...)
    if line.strip() == 'dict(' and i + 2 < len(lines) \
        and 'png="network_resource_requester.png"' in lines[i + 2]:
        depth = 0
        start = i
        while i < len(lines):
            depth += lines[i].count("(") - lines[i].count(")")
            i += 1
            if depth == 0:
                break
        out.extend([
            "    # 第 131 轮：请求器「设置界面」（NetworkResourceRequesterScreen）按用户要求整页删除，",
            "    # 6 个升级槽也一并删除（用户：红石激活速度决定发送速度，跟插件槽没关系）。",
            "    # 因此这里不再登记 network_resource_requester.png 的槽位账（那张底图已无界面引用）。",
        ])
        removed_blocks += 1
        continue
    # 块 2：dict(file="menu/NetworkResourceRequesterMenu.java", ...)
    if line.strip().startswith('dict(file="menu/NetworkResourceRequesterMenu.java"'):
        depth = 0
        while i < len(lines):
            depth += lines[i].count("(") - lines[i].count(")")
            i += 1
            if depth == 0:
                break
        out.extend([
            "    # 第 131 轮：请求器设置界面已删除，瘦菜单没有任何界面，因此没有槽位账。",
        ])
        removed_blocks += 1
        continue
    out.append(line)
    i += 1

p.write_text("\n".join(out), encoding="utf-8")
print("removed_blocks =", removed_blocks)
