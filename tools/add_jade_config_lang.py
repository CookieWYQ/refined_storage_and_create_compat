# -*- coding: utf-8 -*-
"""补齐 Jade 插件配置缺少的翻译键（`run/logs/latest.log` 里的真实 ERROR）。

现场（用户指的那份日志）
------------------------
```
[Render thread/ERROR] [net.neoforged.bus.EventBus/EVENTBUS]:
    Exception caught during firing event: Missing config translation:
    config.jade.plugin_rs_create_compat.camouflage
java.lang.AssertionError: Missing config translation: config.jade.plugin_rs_create_compat.camouflage
    at .../snownee.jade.JadeClient.onGui(JadeClient.java:175)
```
后果：每次界面初始化都会抛一次 AssertionError，紧接着
`Caught error loading resourcepacks, removing all selected resourcepacks` —— 玩家的资源包被整体丢弃。

根因
----
`RsccJadePlugin#registerClient` 用 `registration.registerBlockComponent(new CamouflageProvider(), ...)`
注册了 UID `rs_create_compat:camouflage`，Jade 会为它建一个**配置项**，而配置项的显示名固定取
`config.jade.plugin_<modid>.<path>`。这个键从来没写过，于是 Jade 在校验配置翻译时断言失败。
补一句即可（中文 ≤ 40 字，中英成对）。

用法：`python tools/add_jade_config_lang.py`
"""

import io
import json
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")
LIMIT = 40

ZH = {
    "config.jade.plugin_rs_create_compat.camouflage": "伪装方块提示（Jade）",
}

EN = {
    "config.jade.plugin_rs_create_compat.camouflage": "Camouflaged block tooltip (Jade)",
}


def apply(lang_file, table):
    path = os.path.join(LANG_DIR, lang_file)
    with io.open(path, "r", encoding="utf-8") as handle:
        data = json.loads(handle.read())
    sorted_before = list(data) == sorted(data)
    for key, value in table.items():
        data[key] = value
    if sorted_before:
        data = {key: data[key] for key in sorted(data)}
    with io.open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(json.dumps(data, ensure_ascii=False, indent=2) + "\n")
    with io.open(path, "r", encoding="utf-8") as handle:
        loaded = json.load(handle)
    for key, value in table.items():
        assert loaded.get(key) == value, "%s 回读不一致：%r" % (key, loaded.get(key))
        if lang_file.startswith("zh"):
            assert len(value) <= LIMIT, "%s 中文 %d 字（上限 %d）" % (key, len(value), LIMIT)
    print("[OK] %-11s 已写入 %d 个键（原文件%s排序）"
          % (lang_file, len(table), "本就有序，保持" if sorted_before else "未排序，保持原序"))


def main():
    apply("zh_cn.json", ZH)
    apply("en_us.json", EN)
    print("Jade 配置翻译键补齐完成（json.load 回读一致；中文单条 ≤ %d 字）" % LIMIT)
    return 0


if __name__ == "__main__":
    sys.exit(main())
