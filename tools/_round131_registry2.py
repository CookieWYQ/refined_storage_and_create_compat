# -*- coding: utf-8 -*-
"""第 131 轮：gui_slot_registry 收尾（请求器设置界面删除后的账目）。

注意：`read_text` 走 universal newline（CRLF → \n），所以这里按 \n 匹配、
写回时用 newline="\r\n" 保持仓库的 CRLF 风格。
"""
from pathlib import Path

p = Path(r"D:\MODS\refined_storage_and_create_compat\tools\gui_slot_registry.py")
text = p.read_text(encoding="utf-8")

old_orphan = "ORPHAN = [\n]"
new_orphan = "\n".join([
    "ORPHAN = [",
    "    # 第 131 轮：请求器「设置界面」按用户要求整页删除（NetworkResourceRequesterScreen 已删），",
    "    # 这张 210×238 的底图因此零引用；图本身保留（历史美术资产），但登记成 ORPHAN =",
    "    # 「源码里不许再引用它」—— 将来谁把它接回新界面，这条就会判负，逼他改成正式登记。",
    "    dict(png=\"network_resource_requester.png\",",
    "         why=\"第 131 轮：请求器设置界面整页删除（用户：这界面没有任何作用）⇒ 零引用\"),",
    "]",
])
assert old_orphan in text, "ORPHAN 锚点没找到"
text = text.replace(old_orphan, new_orphan, 1)

anchor = "\n".join([
    "    dict(file=\"menu/FEEnergyCubeMenu.java\",",
    "         screen=\"client/screen/FEEnergyCubeScreen.java\",",
    "         bg=\"none\", png=None, slots=0,",
    "         note=\"故意不 addSlot：立方没有库存/升级槽、也不显示玩家背包（Menu 源码里有这行注释）\"),",
]) + "\n"
assert anchor in text, "FEEnergyCube 锚点没找到"
addition = "\n".join([
    "    dict(file=\"menu/NetworkResourceRequesterMenu.java\",",
    "         screen=None,",
    "         bg=\"none\", png=None, slots=None,",
    "         note=\"第 131 轮：设置界面整页删除、6 个升级槽删除后，这个类只剩「菜单壳」",
    "              （玩家背包槽 + stillValid），没有任何界面会打开它；登记 none 只为覆盖 Menu 清单\"),",
]) + "\n"
text = text.replace(anchor, anchor + addition, 1)

p.write_text(text, encoding="utf-8", newline="\r\n")
print("patched gui_slot_registry.py")
