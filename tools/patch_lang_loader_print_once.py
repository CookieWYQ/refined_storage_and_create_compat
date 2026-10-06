# -*- coding: utf-8 -*-
"""把「手动打印一次 / 按钮与开关 tooltip」新增的语言键写入 zh_cn.json 与 en_us.json。

约定（与工程其它 patch_lang_*.py 一致）：
  * 只用 python 写入，不做手工编辑；
  * 保持既有行序与两空格缩进，只插入 / 替换对应行，其余行原样不动；
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

NEW_KEYS = {
    # 按钮文案（基础 / 高级共用）
    "gui.rs_create_compat.schematic_loader.print_once": {
        "zh_cn": "打印一次",
        "en_us": "Print once",
    },
    # 三个开关的 tooltip（与「自动打印 / 自动回收蓝图 / 自动填火药」一一对应）
    "gui.rs_create_compat.schematic_loader.tip.print": {
        "zh_cn": "开：资源齐后自动打印；关：只收集资源，需手动打印",
        "en_us": "On: auto-print when ready; Off: manual print only",
    },
    "gui.rs_create_compat.schematic_loader.tip.recycle": {
        "zh_cn": "开：打印出的空白蓝图自动回流网络",
        "en_us": "On: printed empty schematics return to the network",
    },
    "gui.rs_create_compat.schematic_loader.tip.gunpowder": {
        "zh_cn": "开：自动为紧贴的加农炮补充火药",
        "en_us": "On: auto-refill gunpowder in the cannon",
    },
    "gui.rs_create_compat.schematic_loader.tip.print_once": {
        "zh_cn": "资源就绪时立即打印当前蓝图一次",
        "en_us": "Print the current blueprint once when ready",
    },
    # 高级版「开始 / 停止」按钮 tooltip
    "gui.rs_create_compat.advanced_schematic_loader.tip.start": {
        "zh_cn": "开始：为当前蓝图收集资源（不打印）；停止：立即停止收集",
        "en_us": "Start: collect resources; Stop: halt collection now",
    },
    # 「打印一次」的行动栏提示（服务端下发）
    "block.rs_create_compat.schematic_loader.print_once.no_cannon": {
        "zh_cn": "没有紧贴的蓝图加农炮",
        "en_us": "No schematicannon attached",
    },
    "block.rs_create_compat.schematic_loader.print_once.no_blueprint": {
        "zh_cn": "加农炮里没有蓝图",
        "en_us": "No blueprint in the cannon",
    },
    "block.rs_create_compat.schematic_loader.print_once.not_ready": {
        "zh_cn": "资源尚未就绪，未触发打印",
        "en_us": "Resources not ready, nothing printed",
    },
    "block.rs_create_compat.schematic_loader.print_once.started": {
        "zh_cn": "已触发打印",
        "en_us": "Printing triggered",
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

    while lines and lines[-1].strip() == "":
        lines.pop()
    if lines:
        lines[-1] = lines[-1].rstrip().rstrip(",")
    lines.append("}")

    out = "\n".join(lines)
    if text.endswith("\n"):
        out += "\n"
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
