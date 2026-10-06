"""把配方类型 tooltip 里的 ID 标签文案改得更明确（避免被误认为物品 ID）。

用法:
    python tools/patch_lang_recipe_id_label.py
"""

import json
from pathlib import Path
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


LANG_DIR = Path('src/main/resources/assets/rs_create_compat/lang')
KEY = 'gui.rs_create_compat.recipe_type.id_label'
NEW = {
    'zh_cn': '配方 ID：%s',
    'en_us': 'Recipe ID: %s',
}


def main():
    for locale, value in NEW.items():
        path = LANG_DIR / ('%s.json' % locale)
        data = json.loads(path.read_text(encoding='utf-8'))
        old = data.get(KEY)
        data[KEY] = value
        path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
        print('[OK ] %-8s %s: %r -> %r' % (locale, KEY, old, value))


if __name__ == '__main__':
    main()
