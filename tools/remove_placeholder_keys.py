import collections
import io
import json
import os
import sys

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")

# 2026-10-05 用户实测：按 Shift 看到 `输入原料：%s`。
# 由 tools/audit_placeholder_keys.py 定位到两条：
#   * sequence_pattern_terminal.input_slot.any —— 代码仍在调它但已不传参 ⇒ 原样画出 %s（就是那个 bug）
#   * sequence_pattern_terminal.card.input_any —— 已无任何调用点（改用 card.input）
# 两条都删掉（文案已由 single-arg 的 `card.input` / `input_slot.tip` 承担）。
DEAD = [
    "gui.rs_create_compat.sequence_pattern_terminal.input_slot.any",
    "gui.rs_create_compat.sequence_pattern_terminal.card.input_any",
]


def main():
    for lang in ("zh_cn", "en_us"):
        path = os.path.join(LANG_DIR, "%s.json" % lang)
        with io.open(path, encoding="utf-8") as handle:
            data = json.load(handle, object_pairs_hook=collections.OrderedDict)
        removed = [key for key in DEAD if key in data]
        for key in removed:
            del data[key]
        with io.open(path, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(json.dumps(data, ensure_ascii=False, indent=2) + "\n")
        print("%s -> %d keys (removed %d)" % (lang, len(data), len(removed)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
