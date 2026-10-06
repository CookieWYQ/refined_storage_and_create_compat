# -*- coding: utf-8 -*-
"""移除「打印一次」相关语言键，并同步修订与它冲突的开关 tooltip。

背景（本轮改动）：装填器的蓝图槽与加农炮的蓝图槽改为「同一份内容」（共享视图），
打印由「自动打印」开关驱动（开 = 资源齐后自动打印；关 = 只收集资源、不打印），
因此「打印一次」这个手动按钮连同它的按钮文案 / tooltip / 行动栏提示一起删除。

约定（与工程其它 patch_lang_*.py 一致）：
  * 只用 python 写入，不做手工编辑；
  * 保持既有行序与两空格缩进，只增删改对应行，其余行原样不动；
  * 写入前后都用 json.loads 校验，任一步失败即报错退出；
  * 文案要短：中文 <= 30 字、英文 <= 60 字符。
"""
import io
import json
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = r"d:\MODS\refined_storage_and_create_compat"
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")

FILES = {
    "zh_cn": os.path.join(LANG_DIR, "zh_cn.json"),
    "en_us": os.path.join(LANG_DIR, "en_us.json"),
}

# 删除的键：「打印一次」按钮文案 + tooltip + 4 条行动栏提示
REMOVED_KEYS = [
    "block.rs_create_compat.schematic_loader.print_once.no_blueprint",
    "block.rs_create_compat.schematic_loader.print_once.no_cannon",
    "block.rs_create_compat.schematic_loader.print_once.not_ready",
    "block.rs_create_compat.schematic_loader.print_once.started",
    "gui.rs_create_compat.schematic_loader.print_once",
    "gui.rs_create_compat.schematic_loader.tip.print_once",
]

# 修订的键：原文提到「需手动打印」，而手动打印入口已删除
UPDATED_KEYS = {
    "gui.rs_create_compat.schematic_loader.tip.print": {
        "zh_cn": "开：资源齐后自动打印；关：只收集资源，不打印",
        "en_us": "On: auto-print when ready; Off: collect only, no printing",
    },
}


def key_of(line):
    """返回 `  "key": "value",` 形式行的键名；不是键值行则返回 None。"""
    s = line.strip()
    if not s.startswith('"'):
        return None
    parts = s.split('"')
    return parts[1] if len(parts) > 2 else None


def patch(path, locale):
    with io.open(path, "r", encoding="utf-8") as f:
        text = f.read()
    json.loads(text)  # 前置校验
    lines = text.split("\n")

    kept, removed, updated = [], [], []
    for line in lines:
        key = key_of(line)
        if key in REMOVED_KEYS:
            removed.append(key)
            continue
        if key in UPDATED_KEYS:
            line = '  "%s": "%s",' % (key, UPDATED_KEYS[key][locale])
            updated.append(key)
        kept.append(line)

    missing = [k for k in REMOVED_KEYS if k not in removed]
    if missing:
        raise SystemExit("这些键在 %s 中不存在，删除失败: %s" % (path, missing))
    if sorted(updated) != sorted(UPDATED_KEYS.keys()):
        raise SystemExit("这些键在 %s 中不存在，修订失败: %s" % (path, updated))

    out = "\n".join(kept)
    parsed = json.loads(out)  # 后置校验
    for key in REMOVED_KEYS:
        if key in parsed:
            raise SystemExit("写入后仍存在被删键: %s -> %s" % (path, key))
    for key, value in UPDATED_KEYS.items():
        if parsed.get(key) != value[locale]:
            raise SystemExit("写入后校验失败: %s -> %s" % (path, key))
    with io.open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(out)
    print("[patch] %s: 删除 %d 键 / 修订 %d 键，剩余 %d 键"
          % (os.path.basename(path), len(removed), len(updated), len(parsed)))


def main():
    for locale, path in FILES.items():
        patch(path, locale)
    print("DONE")
    return 0


if __name__ == "__main__":
    sys.exit(main())
