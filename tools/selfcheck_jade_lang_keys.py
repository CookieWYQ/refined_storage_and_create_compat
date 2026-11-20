#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Jade 插件配置项的语言键守护（第 137 轮客户端测试发现）。

现场
----
`tools/verify_client_boot.ps1` 第一次真启动客户端时，在标题界面初始化阶段崩了：

    java.lang.AssertionError: Missing config translation: config.jade.plugin_rs_create_compat.proxy
        at snownee.jade.JadeClient.onGui(JadeClient.java:175)

这是 Jade 的**断言**：每个注册进 Jade 的 provider / 数据 provider 的 UID
（`ResourceLocation(rs_create_compat, <uid>)`）都必须有
`config.jade.plugin_rs_create_compat.<uid>` 的语言键。第 108 轮新增的「容器代理器」
两条 provider（`proxy` / `proxy_data`）只加了代码、没加语言键，于是：
* 开发环境（`-ea`，Gradle JavaExec 默认开断言）**直接崩客户端**；
* 正式环境不崩，但 Jade 配置界面会显示原始 key（玩家看到 `config.jade...`）。

判据
----
从 `client/jade/`、`compat/jade/`、`support/*Jade*.java` 里把**所有真实注册的 UID**
解析出来（支持直接字符串与常量引用），逐个断言 `en_us.json` / `zh_cn.json` 都有对应键。
这样以后再加 provider 忘了加语言键，自检就会红。

用法：python tools/selfcheck_jade_lang_keys.py
退出码：0 = 全部通过。
"""

from __future__ import annotations

import io
import json
import os
import re
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PKG = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")
JADE_DIRS = [os.path.join(PKG, "client", "jade"), os.path.join(PKG, "compat", "jade")]
KEY_PREFIX = "config.jade.plugin_rs_create_compat."

_passed = 0
_failed = 0
_failures = []


def check(name, ok, detail=""):
    global _passed, _failed
    if ok:
        _passed += 1
        print("  OK   %s" % name)
    else:
        _failed += 1
        _failures.append((name, detail))
        print("  FAIL %s" % name)
        if detail:
            for line in str(detail).strip().splitlines()[:10]:
                print("       " + line)


def read(path):
    with io.open(path, "r", encoding="utf-8") as handle:
        return handle.read()


def java_files():
    files = []
    for directory in JADE_DIRS:
        if not os.path.isdir(directory):
            continue
        for name in sorted(os.listdir(directory)):
            if name.endswith(".java"):
                files.append(os.path.join(directory, name))
    support = os.path.join(PKG, "support")
    if os.path.isdir(support):
        for name in sorted(os.listdir(support)):
            if name.endswith(".java") and "Jade" in name:
                files.append(os.path.join(support, name))
    return files


def constant_value(name, texts):
    """在 jade 相关源码里解析 `static final String NAME = "value";`。"""
    pattern = re.compile(r'\b' + re.escape(name) + r'\s*=\s*"([^"]+)"')
    for text in texts:
        match = pattern.search(text)
        if match:
            return match.group(1)
    return None


def registered_uids(texts):
    """解析 fromNamespaceAndPath(RS_Create_Compat.MODID, X) 里的真实 UID。"""
    uids = set()
    pattern = re.compile(r"fromNamespaceAndPath\(\s*RS_Create_Compat\.MODID\s*,\s*([^)]+?)\s*\)")
    for text in texts:
        for raw in pattern.findall(text):
            argument = raw.strip()
            if argument.startswith('"') and argument.endswith('"'):
                uids.add(argument[1:-1])
                continue
            # 常量引用：`InventoryProxyJadePayload.COMPONENT_ID` 或裸 `COMPONENT_ID`
            name = argument.split(".")[-1]
            value = constant_value(name, texts)
            if value:
                uids.add(value)
    return uids


def all_uids_covered(lang_obj, uids):
    """所有注册 UID 都有语言键（判据本体，正例 / 反例共用）。"""
    return all((KEY_PREFIX + uid) in lang_obj for uid in uids)


def main():
    print("Jade 插件配置项语言键自检")
    print("仓库：%s" % ROOT)
    files = java_files()
    check("J-1 找到 Jade 相关源码文件（>= 5 个）", len(files) >= 5, "files=%d" % len(files))
    if len(files) < 5:
        return 1
    texts = [read(path) for path in files]
    uids = registered_uids(texts)
    check("J-2 解析出真实注册的 Jade UID（>= 6 个）", len(uids) >= 6, "uids=%s" % sorted(uids))

    en_path = os.path.join(LANG_DIR, "en_us.json")
    zh_path = os.path.join(LANG_DIR, "zh_cn.json")
    en = read(en_path)
    zh = read(zh_path)
    try:
        en_obj = json.loads(en)
        zh_obj = json.loads(zh)
        check("J-3 两个语言文件都是合法 JSON", True)
    except Exception as exc:  # noqa: BLE001
        check("J-3 两个语言文件都是合法 JSON", False, str(exc))
        en_obj, zh_obj = {}, {}

    for uid in sorted(uids):
        key = KEY_PREFIX + uid
        check("J-4 en_us 有 %s" % key, key in en_obj)
        check("J-5 zh_cn 有 %s" % key, key in zh_obj)
    check("J-4b 全部 UID 在 en_us 都有语言键（聚合判据）", all_uids_covered(en_obj, uids),
          "缺少：%s" % [u for u in sorted(uids) if (KEY_PREFIX + u) not in en_obj])

    # 反例自证：把「容器代理器」那条语言键删掉（这正是本轮客户端崩溃的现场），聚合判据必须判负
    sample = "proxy"
    if sample in uids:
        broken = dict(en_obj)
        broken.pop(KEY_PREFIX + sample, None)
        check("J-6 反例：删掉 %s 后聚合判据判负" % (KEY_PREFIX + sample),
              not all_uids_covered(broken, uids))

    print("\n断言 %d 条，失败 %d 条" % (_passed + _failed, _failed))
    if _failed:
        print("\n失败明细：")
        for name, detail in _failures:
            print("  - %s" % name)
            if detail:
                print("      " + str(detail).splitlines()[0])
        return 1
    print("ALL_PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
