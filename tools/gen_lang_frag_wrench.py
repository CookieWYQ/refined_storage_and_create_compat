# -*- coding: utf-8 -*-
"""生成「机械动力扳手分离 RS 线缆」的语言键片段。

为什么单独用脚本生成 json：
  * 语言文件是有严格形式要求的文件（UTF-8、2 空格缩进、`{"键": {"en": ..., "zh": ...}}`），
    手写容易出编码 / 转义 / 缩进问题；
  * 本轮只往 `tools/lang_frag_wrench.json` 写键，随后由 `tools/apply_lang_frag.py` 统一合并进
    中英 lang json —— 各自一份 frag、合并幂等，与并行任务互不冲突。

键的用途（全部走 actionbar，句子刻意短）：
  * cable_seam_cut / cable_seam_restored / cable_seam_none —— 方式 A「右键接缝断开」；
  * cable_face_cut / cable_face_restored               —— 方式 B「右键面切换自动连接」。

用法：
    python tools/gen_lang_frag_wrench.py
"""
import json
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "tools", "lang_frag_wrench.json")

PREFIX = "message.rs_create_compat."

FRAG = {
    PREFIX + "cable_seam_cut": {
        "en": "Seam cut: this cable no longer connects here",
        "zh": "已断开：此处不再连接",
    },
    PREFIX + "cable_seam_restored": {
        "en": "Seam restored: this cable connects here again",
        "zh": "已恢复：此处重新连接",
    },
    PREFIX + "cable_seam_none": {
        "en": "Nothing to cut here (no cable connection on this side)",
        "zh": "这里没有可断开的连接",
    },
    PREFIX + "cable_face_cut": {
        "en": "This face no longer auto-connects",
        "zh": "该面已停止自动连接",
    },
    PREFIX + "cable_face_restored": {
        "en": "This face auto-connects again",
        "zh": "该面已恢复自动连接",
    },
}


def main():
    with open(OUT, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(FRAG, handle, ensure_ascii=False, indent=2, sort_keys=True)
        handle.write("\n")
    print("[OK] 写入 %s（%d 键）" % (OUT, len(FRAG)))


if __name__ == "__main__":
    main()
