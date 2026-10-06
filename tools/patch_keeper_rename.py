# -*- coding: utf-8 -*-
"""把定量保持器的显示文案「物品定量保持器」重命名为「资源定量保持器」（中英成对）。

只改**展示文案**（语言值），不动任何语言键名 / 注册 id / 存档键，避免存档不兼容。
一次执行内完成：json.load -> 精确替换 -> json.dump（UTF-8，缩进与既有文件一致）。

英文一致译名：
  Quantity Keeper          -> Resource Quantity Keeper
  Advanced Quantity Keeper -> Advanced Resource Quantity Keeper

用法：python tools/patch_keeper_rename.py
末行固定为 `RENAME OK (zh N / en M)` 或 `RENAME FAILED`。
"""
import io
import json
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")

# key -> (旧值, 新值)；只对「机器名」相关文案做替换（非机器名的工具/提示文案不动）
ZH = {
    "advancements.rs_create_compat.advanced_quantity_keeper.description":
        ("造出高级定量保持器", "造出高级资源定量保持器"),
    "advancements.rs_create_compat.keeper_autocraft.description":
        ("定量保持器自动补货一次", "资源定量保持器自动补货一次"),
    "advancements.rs_create_compat.quantity_keeper.description":
        ("造出定量保持器", "造出资源定量保持器"),
    "block.rs_create_compat.advanced_quantity_keeper":
        ("高级物品定量保持器", "高级资源定量保持器"),
    "block.rs_create_compat.advanced_quantity_keeper.help":
        ("4 个定量保持器合一，4 槽各设物品/流体/气体目标数量。",
         "4 个资源定量保持器合一，4 槽各设物品/流体/气体目标数量。"),
    "block.rs_create_compat.quantity_keeper":
        ("定量保持器", "资源定量保持器"),
}

EN = {
    "advancements.rs_create_compat.advanced_quantity_keeper.description":
        ("Craft an Advanced Quantity Keeper", "Craft an Advanced Resource Quantity Keeper"),
    "advancements.rs_create_compat.keeper_autocraft.description":
        ("Let the Quantity Keeper autocraft a refill",
         "Let the Resource Quantity Keeper autocraft a refill"),
    "advancements.rs_create_compat.quantity_keeper.description":
        ("Craft a Quantity Keeper", "Craft a Resource Quantity Keeper"),
    "block.rs_create_compat.advanced_quantity_keeper":
        ("Advanced Quantity Keeper", "Advanced Resource Quantity Keeper"),
    "block.rs_create_compat.advanced_quantity_keeper.help":
        ("Fuses 4 keepers into one; each slot sets an item/fluid/gas target.",
         "Fuses 4 resource quantity keepers into one; each slot sets an item/fluid/gas target."),
    "block.rs_create_compat.quantity_keeper":
        ("Quantity Keeper", "Resource Quantity Keeper"),
}


def patch(name, table):
    """返回 (改动条数, 问题列表)。new 值已存在视为幂等（跳过），old 值缺失记问题。"""
    path = os.path.join(LANG_DIR, name)
    with io.open(path, encoding="utf-8") as handle:
        data = json.load(handle)
    changed, problems = 0, []
    for key, (old, new) in table.items():
        if key not in data:
            problems.append("%s 缺少键 %s" % (name, key))
            continue
        value = data[key]
        if value == new:
            continue  # 幂等：已经是新名
        if value != old:
            problems.append("%s 的 %s 值非预期：%r（期望 %r）" % (name, key, value, old))
            continue
        data[key] = new
        changed += 1
    if changed:
        # 既有语言文件是 sorted + indent=2；这里保持一致，避免整文件重排产生无谓 diff
        with io.open(path, "w", encoding="utf-8", newline="\n") as handle:
            json.dump(data, handle, ensure_ascii=False, indent=2, sort_keys=True)
            handle.write("\n")
    return changed, problems


def main():
    zh_changed, zh_problems = patch("zh_cn.json", ZH)
    en_changed, en_problems = patch("en_us.json", EN)
    problems = zh_problems + en_problems
    if problems:
        for problem in problems:
            print("[X] %s" % problem)
        print("RENAME FAILED")
        return 1
    print("RENAME OK (zh %d / en %d)" % (zh_changed, en_changed))
    return 0


if __name__ == "__main__":
    sys.exit(main())
