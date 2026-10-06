# -*- coding: utf-8 -*-
"""生成「通用储存磁盘 32M → 64M 改名」的语言键片段。

为什么单独用脚本生成 json：
  * 语言文件是有严格形式要求的文件（UTF-8、2 空格缩进、`{"键": {"en": ..., "zh": ...}}`），
    手写整份 json 容易出编码 / 缩进问题，也容易与并行任务冲突；
  * 本脚本只写 `tools/lang_frag_disk64m.json`，随后由 `tools/apply_lang_frag.py` 合并进
    中英 lang json —— 一份 frag、合并幂等。

片段内容：
  * 新增 `item.rs_create_compat.universal_storage_disk_64m`（物品 id 改名，键必须跟着改）；
  * 删除 `item.rs_create_compat.universal_storage_disk_32m`（旧档已不存在，留着就是死键）。

用法：
    python tools/gen_lang_frag_disk64m.py
"""
import json
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "tools", "lang_frag_disk64m.json")

FRAG = {
    "item.rs_create_compat.universal_storage_disk_64m": {
        "en": "64M Universal Storage Disk",
        "zh": "64M 通用储存磁盘",
    },
    "item.rs_create_compat.universal_storage_disk_32m": None,
}


def main():
    with open(OUT, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(FRAG, handle, ensure_ascii=False, indent=2, sort_keys=True)
        handle.write("\n")
    print("[OK] 写入 %s（%d 键：1 新增 + 1 删除）" % (OUT, len(FRAG)))


if __name__ == "__main__":
    main()
