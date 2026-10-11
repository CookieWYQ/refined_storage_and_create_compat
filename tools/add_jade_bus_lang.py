# -*- coding: utf-8 -*-
"""写入「Jade 总线提示」（用户第 8 项 / 任务 A）需要的语言键（中英成对，幂等）。

覆盖三类键：
  1. Jade 自己按 provider UID 拼出来的**配置项翻译键**（`config.jade.plugin_<modid>.<path>`）：
     Jade 在标题界面会遍历它建过的全部插件配置键、逐个断言 `I18n.exists(...)`
     （见 `JadeClient#onGui`），缺一个就抛 `AssertionError: Missing config translation`
     并连带丢掉玩家资源包 —— 本工程已经踩过一次（伪装方块那条），因此这里两条都必须写：
       * `...bus`      = 客户端文本 provider（`rs_create_compat:bus`）；
       * `...bus_data` = 服务端数据 provider（`rs_create_compat:bus_data`）。
         它当前**不会**被建进插件配置（公共注册路径不碰 `PluginConfig`，见
         `snownee/jade/impl/CommonRegistrationSession`），写上只是为了「将来 Jade 若给它建键
         也不会炸」—— 多一条用不到的翻译没有任何副作用。
  2. `gui.rs_create_compat.jade_bus.*`：提示行本身（绑定 / 自动 / 方向 / 分组行 / 空态 / 提示行）。
  3. 六个分节名（原料 / 输入时原料 / 流体 / 成品 / 废料 / 中间产物）**刻意复用**
     `gui.rs_create_compat.bus_config.group.*`：同一件事在工程里只能有一套说法，
     因此这里一个新键都不加。

用法：`python tools/add_jade_bus_lang.py`
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
    "config.jade.plugin_rs_create_compat.bus": "序列装配总线状态（Jade）",
    "config.jade.plugin_rs_create_compat.bus_data": "序列装配总线状态数据（Jade）",
    "gui.rs_create_compat.jade_bus.linked": "归属执行舱：%s",
    "gui.rs_create_compat.jade_bus.unlinked": "未绑定序列执行仓",
    "gui.rs_create_compat.jade_bus.plain": "已改为普通总线：不按执行仓工作",
    "gui.rs_create_compat.jade_bus.auto": "自动模式：输入原料不收，其余自动收回",
    "gui.rs_create_compat.jade_bus.dir.importer": "方向：把执行仓与它供料的机器的产出按类别收进网络",
    "gui.rs_create_compat.jade_bus.dir.exporter": "方向：把执行仓内部存储按类别推给本机面对的机器",
    "gui.rs_create_compat.jade_bus.line": "%s：%s",
    "gui.rs_create_compat.jade_bus.none": "当前没有负责的类别",
    "gui.rs_create_compat.jade_bus.hold": "按住 Shift 查看总线状态",
}

EN = {
    "config.jade.plugin_rs_create_compat.bus": "Sequence assembly bus status (Jade)",
    "config.jade.plugin_rs_create_compat.bus_data": "Sequence assembly bus status data (Jade)",
    "gui.rs_create_compat.jade_bus.linked": "Bound executor: %s",
    "gui.rs_create_compat.jade_bus.unlinked": "No executor chamber bound",
    "gui.rs_create_compat.jade_bus.plain": "Plain bus: not driven by an executor chamber",
    "gui.rs_create_compat.jade_bus.auto": "Auto mode: inputs kept, everything else collected",
    "gui.rs_create_compat.jade_bus.dir.importer":
        "Direction: collects the executor chamber and its machines into the network, by category",
    "gui.rs_create_compat.jade_bus.dir.exporter":
        "Direction: pushes the executor chamber storage to the machines this bus faces, by category",
    "gui.rs_create_compat.jade_bus.line": "%s: %s",
    "gui.rs_create_compat.jade_bus.none": "No category assigned yet",
    "gui.rs_create_compat.jade_bus.hold": "Hold Shift to see the bus status",
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
    assert sorted(ZH) == sorted(EN), "中英键集合必须完全一致"
    apply("zh_cn.json", ZH)
    apply("en_us.json", EN)
    print("Jade 总线提示语言键写入完成（json.load 回读一致；中文单条 ≤ %d 字；键集合中英一致）" % LIMIT)
    return 0


if __name__ == "__main__":
    sys.exit(main())
