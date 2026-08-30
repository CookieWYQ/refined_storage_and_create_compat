# -*- coding: utf-8 -*-
path = r"D:\MODS\refined_storage_and_create_compat\src\main\java\cretae\cookiewyq\rs_create_compat\client\TerminalModeTabOverlay.java"
with open(path, 'r', encoding='utf-8') as f:
    src = f.read()

# 缩小 Tab：32x26 -> 24x22，位置微调（更贴合右侧栏）
old = """    private static final int TAB_W = 32;
    private static final int TAB_H = 26;"""
new = """    private static final int TAB_W = 24;
    private static final int TAB_H = 22;"""
src = src.replace(old, new)

# 图标居中位置随尺寸调整
old = """            final ItemStack icon = iconFor(mode);
            if (!icon.isEmpty()) {
                graphics.renderItem(icon, x + (TAB_W - 16) / 2, y + (TAB_H - 16) / 2);
            }"""
new = """            final ItemStack icon = iconFor(mode);
            if (!icon.isEmpty()) {
                graphics.renderItem(icon, x + (TAB_W - 16) / 2, y + (TAB_H - 16) / 2);
            }"""
src = src.replace(old, new)

with open(path, 'w', encoding='utf-8') as f:
    f.write(src)
print("OK")
