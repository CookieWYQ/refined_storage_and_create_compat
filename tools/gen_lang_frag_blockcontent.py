# -*- coding: utf-8 -*-
"""生成「拆方块时内容物去向」策略的语言键片段 tools/lang_frag_blockcontent.json（幂等）。

由 tools/apply_lang_frag.py 统一合并进中英语言文件；本脚本只负责产出分片 JSON，
保证 UTF-8 + 2 空格缩进，避免手改整份语言文件。
"""
import json
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "tools", "lang_frag_blockcontent.json")

FRAGMENT = {
    "message.rs_create_compat.blockcontent.set": {
        "en": "Block-content disposition is now: %s",
        "zh": "拆方块内容物去向已设为：%s",
    },
    "message.rs_create_compat.blockcontent.current": {
        "en": "Current block-content disposition: %s",
        "zh": "当前拆方块内容物去向：%s",
    },
    "message.rs_create_compat.blockcontent.usage": {
        "en": "Usage: /rs_create_compat blockcontent <network|drop|block>",
        "zh": "用法：/rs_create_compat blockcontent <network|drop|block>",
    },
    "message.rs_create_compat.blockcontent.mode.network": {
        "en": "Network (falls back to keeping it in the block when offline)",
        "zh": "回网（没接网络时自动保存在方块内部）",
    },
    "message.rs_create_compat.blockcontent.mode.drop": {
        "en": "Drop as items",
        "zh": "掉落出来",
    },
    "message.rs_create_compat.blockcontent.mode.block": {
        "en": "Keep inside the block (restored when replaced)",
        "zh": "保存在方块内部（重新放下原样恢复）",
    },
}


def main():
    with open(OUT, "w", encoding="utf-8") as handle:
        json.dump(FRAGMENT, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    print("[OK] 写入 %s（%d 键）" % (os.path.relpath(OUT, ROOT), len(FRAGMENT)))


if __name__ == "__main__":
    main()
