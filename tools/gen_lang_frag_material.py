# -*- coding: utf-8 -*-
"""维护「原料标记 / 防中间产物错误回流」这一组语言键的片段文件。

本轮（按步过滤 + 复用 Create 原版 tooltip）的决定：
  * 中间产物物品上<b>不再</b>挂自研文案（「未完成件 / 第 N 步 / 超过阈值禁止回流」等），
    改为直接复用 Create 原版序列装配的进度描述
    （{@code SequencedAssemblyRecipe#addToTooltip}，由 Create 自己的
    {@code ClientEvents#addToItemTooltip} 注册，对任何带
    {@code create:sequenced_assembly} 组件的物品生效）；
  * 因此原先那 3 条自研 tooltip 语言键全部删除（中英同步），并把片段改成「删除项」。

为什么用脚本写 json：
  * 语言文件对编码 / 缩进 / 转义有严格要求，手写容易出错；
  * 片段由 `tools/apply_lang_frag.py` 统一合并进中英语言文件（幂等），
    改成 delete 项后即使本脚本被再次运行，也不会把这 3 条文案加回来。

用法：
    python tools/gen_lang_frag_material.py
    python tools/apply_lang_frag.py
"""
import json
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "tools", "lang_frag_material.json")

LANG = "item.rs_create_compat."

# 键 -> 片段值。{"delete": true} = 从两份语言文件里移除（中英同时删）。
FRAG = {
    # 自研「原料标记」提示：删除（改由 Create 原版进度描述承担）
    LANG + "raw_material.tip": {"delete": True},
    # 自研「禁止回流步骤」提示：删除（该配置仍存在，只是不再在 tooltip 里讲解）
    LANG + "disallow_inputting_by_step.tip": {"delete": True},
    # 自研「未完成件」提示：删除（原版已有「组装进度 / 下一步：冲压」）
    LANG + "sequence_incomplete.tip": {"delete": True},
}


def main():
    with open(OUT, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(FRAG, handle, ensure_ascii=False, indent=2, sort_keys=True)
        handle.write("\n")
    print("[OK] 写入 %s（%d 键，全部为删除项）" % (OUT, len(FRAG)))


if __name__ == "__main__":
    main()
