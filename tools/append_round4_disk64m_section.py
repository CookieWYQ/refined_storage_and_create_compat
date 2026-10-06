# -*- coding: utf-8 -*-
"""把本轮「通用储存磁盘最高生存档 32M → 64M」章节**幂等**追加进 docs/DESIGN_DECISIONS_ROUND4.md。

为什么用脚本：该文档被多个并行子 agent 共同追加，手改整份文件容易冲突；
脚本按「标记存在即跳过」的方式追加，重复执行结果一致。

用法：
    python tools/append_round4_disk64m_section.py
"""
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DOC = os.path.join(ROOT, "docs", "DESIGN_DECISIONS_ROUND4.md")

MARK = "## 通用储存磁盘最高生存档：32M → 64M（翻倍）+ 配方改为 4 合 1（本轮）"

SECTION = """

***

""" + MARK + """

> 来源：用户本轮需求「最大的那个生存模式下可制作的那个磁盘容量翻倍，对应的配方也改成四个的那个合成的」。
> 只动这一档磁盘（容量 / 配方 / 文案），其它物品方块一律未动。

### 1. 现状核对（改之前先定位，全部为真实路径）

* **物品与容量的唯一来源**：`src/main/java/cretae/cookiewyq/rs_create_compat/RS_Create_Compat.java`
  里的 `UNIVERSAL_STORAGE_DISK_*`（`ITEMS.register("<id>", () -> new UniversalStorageDiskItem(<容量>L))`）。
  磁盘物品类 `item/UniversalStorageDiskItem.java` 只负责「拿到容量 → 建存储 / 显示容量」，**不含任何容量数字**；
  `Config.java` 只有 `universalDiskAllowMixedTypes` 这类开关，**没有容量项** —— 所以容量只有注册处一个来源。
* 各级真实容量（单位 = 物品位，1 bucket = 1 物品位）：
  `1k=1024`、`4k=4096`、`16k=16384`、`64k=65536`、`256k=262144`、`1M=1048576`、`4M=4194304`、
  `16M=16777216`、**`32M=33554432`（= 32 × 1024 × 1024，即 32768k，本轮改造对象）**、
  `creative=0`（≤0 → `UniversalStorageType.create(capacity=null)` → `Long.MAX_VALUE`，**无限**）。
* **配方**：`src/main/resources/data/rs_create_compat/recipe/universal_storage_disk_<档位>.json`。
  既有惯例：`1k/4k/16k/64k` = 有序（`GBG/RPR/EEE`，`P` = 对应原版 `refinedstorage:<档>_storage_part`）；
  `256k/1M/4M/16M` = **无序 4 个低一档通用盘**；`32M` 原本是唯一例外（无序 **2 个** 16M）。

### 2. 本轮改动

| 项 | 改前 | 改后 |
| --- | --- | --- |
| 物品 id | `rs_create_compat:universal_storage_disk_32m` | `rs_create_compat:universal_storage_disk_64m` |
| Java 常量 | `UNIVERSAL_STORAGE_DISK_32M` | `UNIVERSAL_STORAGE_DISK_64M` |
| 容量 | `33554432L` | **`67108864L`**（= 64 × 1024 × 1024，即 65536k，**严格 ×2**） |
| 配方 | 无序 `2 × 16M → 32M` | 无序 **`4 × 16M → 64M`**（对齐 256k..16M 的 4 合 1 惯例） |
| 模型 / 贴图 | `..._32m.json` / `..._32m.png` | 同名改为 `..._64m`（同一档改名，不是新画一档） |
| 语言键 | `..._32m` = 32M/32M 通用储存磁盘 | 删旧键 + 新 `..._64m` = 64M |

**「四个」指什么 / 判定依据**：

1. 本模组自身惯例：`256k = 4 × 64k`、`1M = 4 × 256k`、`4M = 4 × 1M`、`16M = 4 × 4M`
   —— 从 256k 起一律「4 个低一档」，只有原 `32M = 2 × 16M` 是例外；
2. RS 原版同款设计：`refinedstorage-neoforge-2.0.0.jar` 内
   `data/refinedstorage/recipe/64k_storage_part.json` 的图案 `PEP/SRS/PSP` 中 **S = 4 个 `16k_storage_part`**
   （`16k_storage_part.json` 同理用 4 个 `4k_storage_part`）—— 官方升档也是「4 个低一档」；
3. 4 个 16M 正好等于翻倍后的 64M，容量与材料自洽。

因此本档取**无序 4 个 16M → 1 个 64M**（沿用原文件既有的 `minecraft:crafting_shapeless` 写法，
不强行改成有序；`1k..64k` 那三档仍保持「原版 storage part 有序合成」不动）。

**为什么连 id 一起改名**：本系列 id 内嵌容量数字（`_16m` / `_32m`），
若只改容量而 id 仍叫 `_32m`，会立刻出现「id 说 32M、tooltip 说 64M」的自相矛盾。
改名代价：旧存档里的 32M 磁盘物品 id 失效（该档本身即用户报告的异常档），无兼容映射表。

### 3. 与「创造无限档」的关系（无倒挂）

`universal_storage_disk_creative` 容量 0 → 判定为**无限**（`Long.MAX_VALUE`），
严格大于 67108864，因此**不存在「低级比高级还大」的倒挂**：
`...16M < 64M < 创造(无限)`。

### 4. 同步的其它位置

* `client/ClientInit.java`：磁盘物品模型注册列表里的常量名同步（否则编译不过）。
* `RS_Create_Compat.java` 创造模式标签页 `displayItems` 与物品区注释同步。
* `item/UniversalStorageDiskItem.java` 类注释的档位列表 `.../16M/64M/无限`。
* tooltip：容量由 RS 的 `AbstractStorageContainerItem#formatAmount` 按实际容量格式化，
  **没有写死数字**，因此容量改完 tooltip 自动显示 64M；唯一写死容量的是物品名语言键（已同步）。

### 5. 本轮新增 / 修改文件

新增：`tools/gen_universal_disk_64m.py`（幂等产出配方 / 模型 / 贴图改名）、
`tools/gen_lang_frag_disk64m.py` + `tools/lang_frag_disk64m.json`、
`tools/append_round4_disk64m_section.py`（本追加脚本）。

修改：`RS_Create_Compat.java`、`client/ClientInit.java`、`item/UniversalStorageDiskItem.java`、
`data/rs_create_compat/recipe/universal_storage_disk_64m.json`（旧 32m 配方删除）、
`assets/.../models/item/universal_storage_disk_64m.json`、`assets/.../textures/item/universal_storage_disk_64m.png`、
`assets/.../lang/{zh_cn,en_us}.json`（经 `apply_lang_frag.py`）。

**刻意未动**：其它任何物品 / 方块的配方与容量、`UniversalStorageType` / `UniversalLimitedStorage`（存储实现）、
创造无限档、GUI / 菜单 / 布局。
"""


def main():
    with open(DOC, "r", encoding="utf-8") as handle:
        text = handle.read()
    if MARK in text:
        print("[跳过] 章节已存在: %s" % MARK)
        return 0
    with open(DOC, "a", encoding="utf-8", newline="\n") as handle:
        handle.write(SECTION)
    print("[追加] %s（+%d 字符）" % (os.path.relpath(DOC, ROOT), len(SECTION)))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
