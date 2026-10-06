# -*- coding: utf-8 -*-
"""生成「高级远程终端快捷键」的语言键片段。

为什么单独用脚本生成 json：
  * 语言文件是有严格形式要求的文件（UTF-8、2 空格缩进、`{"键": {"en": ..., "zh": ...}}`），
    手写容易出编码 / 转义 / 缩进问题；
  * 本轮只往 `tools/lang_frag_keybind.json` 写键，随后由 `tools/apply_lang_frag.py` 统一合并进
    中英 lang json —— 各自一份 frag、合并幂等，与并行任务互不冲突。

键的用途：
  * `key.categories.rs_create_compat`                  —— 原版「选项 → 控制 → 按键绑定」里的分类名；
  * `key.rs_create_compat.open_advanced_remote_terminal` —— 按键名（默认 G）。

用法：
    python tools/gen_lang_frag_keybind.py
"""
import json
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "tools", "lang_frag_keybind.json")

FRAG = {
    "key.categories.rs_create_compat": {
        "en": "RS & Create Compat",
        "zh": "RS × 机械动力兼容",
    },
    "key.rs_create_compat.open_advanced_remote_terminal": {
        "en": "Open Advanced Remote Terminal",
        "zh": "打开高级远程终端",
    },
}


def main():
    with open(OUT, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(FRAG, handle, ensure_ascii=False, indent=2, sort_keys=True)
        handle.write("\n")
    print("[OK] 写入 %s（%d 键）" % (OUT, len(FRAG)))


if __name__ == "__main__":
    main()
