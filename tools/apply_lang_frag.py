# -*- coding: utf-8 -*-
"""语言键片段合并器（幂等，可重复执行）。

把 tools/lang_frag_*.json 里的所有片段合并进中英语言文件：
  src/main/resources/assets/rs_create_compat/lang/zh_cn.json
  src/main/resources/assets/rs_create_compat/lang/en_us.json

片段格式（每个分片文件一个小字典）：
  {
    "<语言键>": {"en": "...", "zh": "..."},      # 新增 / 覆盖
    "<语言键>": {"delete": true}                 # 删除（中英同时删）
  }
  * 也可用 null 值表示删除：{"<语言键>": null}

约定：
  * 一律用本脚本写 json（不手改整份 json），保证 UTF-8 + 2 空格缩进 + 无尾逗号 + 既有键顺序不变；
  * 幂等：重复运行结果完全一致（已存在的键按片段值覆盖；删除键不存在时跳过）；
  * 结束时逐键复核：片段里"应存在"的键在中英两份里都在，缺任何一个即报错退出（exit 1）。

用法：
  python tools/apply_lang_frag.py            # 合并全部片段
  python tools/apply_lang_frag.py --verify   # 只校验（不写文件）
"""
import glob
import json
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOOLS_DIR = os.path.join(ROOT, "tools")
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")

TARGETS = {
    "zh": os.path.join(LANG_DIR, "zh_cn.json"),
    "en": os.path.join(LANG_DIR, "en_us.json"),
}


def load_fragments():
    """收集全部片段：返回 (upserts{key: {en, zh}}, deletes:set)。"""
    upserts = {}
    deletes = set()
    for path in sorted(glob.glob(os.path.join(TOOLS_DIR, "lang_frag_*.json"))):
        with open(path, "r", encoding="utf-8") as handle:
            data = json.load(handle)
        if not isinstance(data, dict):
            print("[X] 片段不是对象: %s" % path)
            continue
        for key, value in data.items():
            if value is None or (isinstance(value, dict) and value.get("delete") is True):
                deletes.add(key)
                upserts.pop(key, None)
                continue
            if not isinstance(value, dict) or "en" not in value or "zh" not in value:
                print("[X] 片段键缺少 en/zh: %s -> %r (%s)" % (key, value, path))
                continue
            upserts[key] = {"en": value["en"], "zh": value["zh"]}
        print("[片段] %s: %d 键" % (os.path.relpath(path, ROOT), len(data)))
    return upserts, deletes


def read_lang(path):
    with open(path, "r", encoding="utf-8") as handle:
        return json.load(handle)


def write_lang(path, data):
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(data, handle, ensure_ascii=False, indent=2)
        handle.write("\n")


def main():
    verify_only = "--verify" in sys.argv
    upserts, deletes = load_fragments()
    print("[汇总] 待写入 %d 键 / 待删除 %d 键" % (len(upserts), len(deletes)))

    problems = []
    for lang, path in TARGETS.items():
        data = read_lang(path)
        added = updated = dropped = 0
        for key in deletes:
            if key in data:
                del data[key]
                dropped += 1
        for key, value in upserts.items():
            if key in data and data[key] != value[lang]:
                updated += 1
            elif key not in data:
                added += 1
            data[key] = value[lang]
        if not verify_only:
            write_lang(path, data)
        # 复核：片段里应存在的键必须都在
        missing = [k for k in upserts if k not in data]
        for key in missing:
            problems.append("%s 缺少键: %s" % (lang, key))
        # 复核：应删除的键必须都不在
        leftover = [k for k in deletes if k in data]
        for key in leftover:
            problems.append("%s 未删除键: %s" % (lang, key))
        print("[%s] 新增 %d / 覆盖 %d / 删除 %d，共 %d 键"
              % (os.path.basename(path), added, updated, dropped, len(data)))

    if problems:
        for problem in problems:
            print("[X] %s" % problem)
        print("结果: %d 个问题" % len(problems))
        return 1
    print("结果: 全部键已就位（幂等，可重复执行）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
