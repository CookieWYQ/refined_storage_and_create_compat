# -*- coding: utf-8 -*-
"""把「装填器集群 / 蓝图槽锁定 / 共享队列」新增的语言键写入 zh_cn.json 与 en_us.json。

约定（与工程其它 patch_lang_*.py 一致）：
  * 只用 python 写入，不做手工编辑；
  * 保持既有行序（两个文件都是按键名字典序排列）与两空格缩进；
  * 只插入对应新行（键已存在时只替换该行），其余行原样不动；
  * 写入前后都用 json.loads 校验，任一步失败即报错退出。
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

# 新增键（文案要短：中文 <= 30 字、英文 <= 60 字符）
NEW_KEYS = {
    "gui.rs_create_compat.schematic_loader.blueprint_locked.tip": {
        "zh_cn": "蓝图槽由高级装填器接管，请到高级装填器操作",
        "en_us": "Blueprint slot is taken over by the advanced loader",
    },
    "gui.rs_create_compat.advanced_schematic_loader.queue_scroll.tip": {
        "zh_cn": "滚动查看全部共享蓝图队列",
        "en_us": "Scroll to browse the shared blueprint queue",
    },
}


def key_of(line):
    """返回 `  "key": "value",` 形式行的键名；不是键值行则返回 None。"""
    s = line.strip()
    if not s.startswith('"'):
        return None
    parts = s.split('"')
    return parts[1] if len(parts) > 2 else None


def patch(path, values):
    with io.open(path, "r", encoding="utf-8") as f:
        text = f.read()
    json.loads(text)  # 前置校验
    lines = text.split("\n")

    # 统一成「只剩键值行」的列表：去掉末尾空行与收尾花括号（花括号最后统一补回，
    # 因为本工程两个文件都把 `}` 直接接在最后一条键值后面）。
    while lines and lines[-1].strip() == "":
        lines.pop()
    if not lines or not lines[-1].rstrip().endswith("}"):
        raise SystemExit("找不到 JSON 收尾花括号: %s" % path)
    if lines[-1].strip() == "}":
        lines.pop()
    else:
        lines[-1] = lines[-1].rstrip()[:-1].rstrip().rstrip(",")

    for key, value in values.items():
        line = '  "%s": "%s",' % (key, value)
        replaced = False
        for i in range(len(lines)):
            if key_of(lines[i]) == key:
                lines[i] = line
                replaced = True
                break
        if replaced:
            continue
        # 按字典序插入到第一个「键名大于本键」的行之前；没有则追加到末尾
        target = len(lines)
        for i in range(len(lines)):
            k = key_of(lines[i])
            if k is not None and k > key:
                target = i
                break
        lines.insert(target, line)

    # 收尾：最后一行去掉逗号，再补回收尾花括号
    while lines and lines[-1].strip() == "":
        lines.pop()
    if lines:
        lines[-1] = lines[-1].rstrip().rstrip(",")
    lines.append("}")

    out = "\n".join(lines)
    if text.endswith("\n"):
        out += "\n"  # 保留原文件末尾换行
    parsed = json.loads(out)  # 后置校验
    for key, value in values.items():
        if parsed.get(key) != value:
            raise SystemExit("写入后校验失败: %s -> %s" % (path, key))
    with io.open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(out)
    print("[patch] %s: %d keys ok" % (os.path.basename(path), len(values)))


def main():
    for locale, path in FILES.items():
        patch(path, {k: v[locale] for k, v in NEW_KEYS.items()})
    print("DONE")
    return 0


if __name__ == "__main__":
    sys.exit(main())
