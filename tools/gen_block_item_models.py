"""把本模组「方块物品」的 item 模型指向该方块的 active（发光）模型。

背景（与 Refined Storage 保持一致）：
    RS 的方块物品模型由 DataGen 硬编码 parent 到「激活态」block 模型
    （见 RS 的 ItemModelProviderImpl#registerGrids / #registerAutocrafters，
    其 parent 为 block/<name>/<color>，而 blockstates 里该模型对应 active=true）。
    方块自身默认状态是 inactive，放下后仍是暗的，接入网络才点亮。
    因此物品栏/创造物品栏/手持时看到的是「亮」的那版。

本脚本做的事：
    对每个方块，把 models/item/<id>.json 重写为
        {"parent": "rs_create_compat:block/<id>_active"}
    并校验目标 block 模型确实存在。

用法:
    python tools/gen_block_item_models.py
"""

import json
from pathlib import Path
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


MODEL_DIR = Path('src/main/resources/assets/rs_create_compat/models')
NS = 'rs_create_compat'

# 所有带 active/inactive 双态的方块
BLOCKS = [
    'advanced_schematic_loader',
    'collection_cache',
    'quantity_keeper',
    'range_charger',
    'schematic_loader',
    'sequence_assembly_executor',
    'sequence_execution_chamber',
    'sequence_pattern_terminal',
]


def main():
    ok = True
    for block in BLOCKS:
        active = MODEL_DIR / 'block' / ('%s_active.json' % block)
        inactive = MODEL_DIR / 'block' / ('%s.json' % block)
        item = MODEL_DIR / 'item' / ('%s.json' % block)

        if not active.exists():
            print('[MISS] active block 模型不存在: %s' % active)
            ok = False
            continue
        if not item.exists():
            print('[MISS] item 模型不存在: %s' % item)
            ok = False
            continue

        data = {'parent': '%s:block/%s_active' % (NS, block)}
        item.write_text(json.dumps(data, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
        has_inactive = 'yes' if inactive.exists() else 'no'
        print('[OK ] %-32s parent -> block/%s_active  (inactive 模型存在: %s)'
              % (item.name, block, has_inactive))

    print('[result] %s' % ('ALL OK' if ok else 'HAS PROBLEM'))


if __name__ == '__main__':
    main()
