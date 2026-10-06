"""修正装配样板 tooltip 的输入文案口径：从「每次循环」改为「整份样板」。

背景：
    样板现在声明的是「一次完整自动合成」的总输入量
    （主原料 1 份 + 中间原料 × loops），所以 tooltip 文案必须同步，
    否则玩家会以为列出的数量是每次循环的量。

用法:
    python tools/patch_lang_pattern_inputs_text.py
"""

import json
from pathlib import Path
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


LANG_DIR = Path('src/main/resources/assets/rs_create_compat/lang')
KEY = 'item.rs_create_compat.sequence_assembly_pattern.inputs_header'
NEW = {
    'zh_cn': '所需输入（整份样板）：',
    'en_us': 'Inputs (per craft):',
}


def main():
    for locale, value in NEW.items():
        path = LANG_DIR / ('%s.json' % locale)
        data = json.loads(path.read_text(encoding='utf-8'))
        old = data.get(KEY)
        data[KEY] = value
        path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
        print('[OK ] %-8s %s: %r -> %r' % (locale, KEY, old, value))
    print('[result] 完成，键总数: %d' % len(data))


if __name__ == '__main__':
    main()
