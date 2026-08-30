#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
edit_lang.py - Minecraft 模组语言文件编辑工具

统一通过本工具修改 lang JSON（自动处理 UTF-8、无尾逗号、排序），
避免手写 JSON 导致格式错误（尾逗号/编码问题会使整个语言文件失效）。

用法：
    # 添加或更新单个键（键不存在则新增，存在则覆盖）
    python edit_lang.py zh_cn.json set item.rs_create_compat.xxx "中文文本"

    # 删除键
    python edit_lang.py zh_cn.json remove item.rs_create_compat.xxx

    # 批量设置（一次调用修改多个键）
    python edit_lang.py zh_cn.json batch "key1=值1" "key2=值2"

    # 打印当前键值
    python edit_lang.py zh_cn.json get item.rs_create_compat.xxx
"""
import argparse
import json
import sys
from pathlib import Path


def load(path: Path) -> dict:
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def save(path: Path, data: dict) -> None:
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
        f.write("\n")


def main() -> int:
    parser = argparse.ArgumentParser(description="Minecraft 语言文件编辑工具")
    parser.add_argument("file", help="语言文件路径（如 zh_cn.json）")
    parser.add_argument("action", choices=["set", "remove", "get", "batch"])
    parser.add_argument("args", nargs="*", help="set/remove/get: key [value]；batch: key=value ...")
    args = parser.parse_args()

    path = Path(args.file)
    if not path.exists():
        print(f"文件不存在: {path}")
        return 1
    data = load(path)

    if args.action == "get":
        if not args.args:
            print(json.dumps(data, ensure_ascii=False, indent=2))
            return 0
        key = args.args[0]
        print(data.get(key, f"<键不存在: {key}>"))
        return 0

    if args.action == "remove":
        if not args.args:
            print("remove 需要 key 参数")
            return 1
        key = args.args[0]
        if key in data:
            del data[key]
            save(path, data)
            print(f"已删除: {key}")
        else:
            print(f"键不存在，无需删除: {key}")
        return 0

    if args.action == "set":
        if len(args.args) < 2:
            print("set 需要 key 和 value 参数")
            return 1
        key, value = args.args[0], args.args[1]
        data[key] = value
        save(path, data)
        print(f"已设置: {key} = {value}")
        return 0

    if args.action == "batch":
        if not args.args:
            print("batch 需要 key=value 参数")
            return 1
        for item in args.args:
            if "=" not in item:
                print(f"无效参数（应为 key=value）: {item}")
                return 1
            key, value = item.split("=", 1)
            data[key] = value
        save(path, data)
        print(f"批量设置完成，共 {len(args.args)} 个键")
        return 0

    return 0


if __name__ == "__main__":
    sys.exit(main())
