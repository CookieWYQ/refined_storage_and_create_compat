# -*- coding: utf-8 -*-
def fix(path, rules):
    with open(path, 'r', encoding='utf-8') as f:
        src = f.read()
    for old, new in rules:
        if old not in src:
            print(f"[WARN] {path}: {old[:70]!r}")
            continue
        src = src.replace(old, new)
    with open(path, 'w', encoding='utf-8') as f:
        f.write(src)
    print(f"[OK] {path}")

base = r"D:\MODS\refined_storage_and_create_compat\src\main\java\cretae\cookiewyq\rs_create_compat\client\screen"

# 基础装填器：滚动条 x 171→173（库存右边缘与插件栏之间）
fix(base + r"\SchematicLoaderScreen.java", [
    ("""    private static final int SCROLLBAR_X = 171;
    private static final int SCROLLBAR_Y = 28;
    private static final int SCROLLBAR_H = 106;""",
     """    private static final int SCROLLBAR_X = 173;
    private static final int SCROLLBAR_Y = 28;
    private static final int SCROLLBAR_H = 106;"""),
])

# 高级装填器：滚动条 x 171→173
fix(base + r"\AdvancedSchematicLoaderScreen.java", [
    ("""    private static final int SCROLLBAR_X = 171;
    private static final int SCROLLBAR_Y = STORAGE_TOP + 1;
    private static final int SCROLLBAR_H = STORAGE_H_PX - 2;""",
     """    private static final int SCROLLBAR_X = 173;
    private static final int SCROLLBAR_Y = STORAGE_TOP + 1;
    private static final int SCROLLBAR_H = STORAGE_H_PX - 2;"""),
])
print("done")
