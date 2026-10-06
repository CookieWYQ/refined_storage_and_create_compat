"""新增两条「用机械动力黄铜块替代下界合金锭」的精致存储网络发送器/接收器合成表。

说明：
- **只新增，不替换**。RS 原版配方（`refinedstorage:network_transmitter` /
  `network_receiver`，使用 `c:ingots/netherite`）保持不动，玩家两种配方都能用。
- 本模组配方 ID：`rs_create_compat:network_transmitter_brass` / `network_receiver_brass`。
- 原配方里的 `N`（下界合金锭）替换为 `create:brass_block`；其余形状与材料完全照抄 RS 原版
  （见 local_src/external/RefinedStorage/refinedstorage-common/src/main/resources/data/refinedstorage/recipe/）。

用法:
    python tools/gen_brass_network_recipes.py
"""

import json
from pathlib import Path
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


OUT_DIR = Path('src/main/resources/data/rs_create_compat/recipe')
CREATE_LANG = Path('local_src/external/Create/src/main/resources/assets/create/lang/en_us.json')

# 与原版逐条对齐，只有 N 换成黄铜块
COMMON_KEY = {
    'E': {'tag': 'c:ender_pearls'},
    'C': {'item': 'refinedstorage:construction_core'},
    'M': {'item': 'refinedstorage:machine_casing'},
    'D': {'item': 'refinedstorage:destruction_core'},
    'A': {'item': 'refinedstorage:advanced_processor'},
    'N': {'item': 'create:brass_block'},
}

RECIPES = [
    ('network_transmitter_brass', ['EEE', 'CMD', 'ANA'], 'refinedstorage:network_transmitter'),
    ('network_receiver_brass', ['ANA', 'CMD', 'EEE'], 'refinedstorage:network_receiver'),
]


def check_brass_block() -> None:
    """确认 create:brass_block 这个方块 id 在机械动力里真实存在（读它的语言文件键）。"""
    if not CREATE_LANG.exists():
        print('[WARN] 找不到 Create 语言文件，跳过 id 校验: %s' % CREATE_LANG)
        return
    data = json.loads(CREATE_LANG.read_text(encoding='utf-8'))
    for key in ('block.create.brass_block', 'block.create.brass_casing'):
        print('[CHECK] %-28s %s' % (key, '存在' if key in data else '缺失'))


def main():
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    check_brass_block()
    for name, pattern, result in RECIPES:
        data = {
            'type': 'minecraft:crafting_shaped',
            'pattern': pattern,
            'key': COMMON_KEY,
            'result': {'id': result},
        }
        path = OUT_DIR / ('%s.json' % name)
        path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
        print('[OK ] %s -> %s' % (path, result))
    print('[result] 完成（新增 2 条，未改动 RS 原版配方）')


if __name__ == '__main__':
    main()
