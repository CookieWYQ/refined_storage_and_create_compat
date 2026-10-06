# -*- coding: utf-8 -*-
"""把「序列装配诊断日志」的用法提示更新为新的指令写法 /rs_create_compat debug assembly <on|off>。

为什么要用脚本改而不是手写 JSON：语言文件条目很多、行序与缩进必须保持稳定，
json.load 校验 + 按原样回写能避免手改引入格式错误（用户规则：有格式要求的文件用 python 写）。
"""
import json
import io
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FILES = {
    "zh_cn.json": "用法：/rs_create_compat debug assembly <on|off>（等价写法：/rs_create_compat assemblydebug <on|off>）",
    "en_us.json": "Usage: /rs_create_compat debug assembly <on|off> (alias: /rs_create_compat assemblydebug <on|off>)",
}
KEY = "message.rs_create_compat.assemblydebug.usage"


def patch(path, value):
    with io.open(path, "r", encoding="utf-8") as handle:
        data = json.load(handle)
    if KEY not in data:
        raise SystemExit("missing key %s in %s" % (KEY, path))
    before = data[KEY]
    data[KEY] = value
    with io.open(path, "w", encoding="utf-8", newline="\n") as handle:
        # ensure_ascii=False：中文按原样写入；indent=2 + 末尾换行：与既有文件风格一致
        json.dump(data, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    # 回读校验：确认写回的是合法 JSON 且值已生效
    with io.open(path, "r", encoding="utf-8") as handle:
        check = json.load(handle)
    assert check[KEY] == value, "round-trip mismatch in %s" % path
    print("OK %s\n  - %s\n  + %s" % (os.path.basename(path), before, value))


def main():
    for name, value in FILES.items():
        patch(os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat",
                           "lang", name), value)
    return 0


if __name__ == "__main__":
    sys.exit(main())
