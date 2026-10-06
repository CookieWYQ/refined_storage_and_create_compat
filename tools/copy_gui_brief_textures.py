"""把绘图 AI 交付的 GUI 贴图从 tmp_textures/gui_brief 复制进 assets。

用法:
    python tools/copy_gui_brief_textures.py

校验:
    复制后用 PIL 读取目标文件，打印实际尺寸，便于和设计文档对照。
"""

import shutil
from pathlib import Path
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


SRC = Path('tmp_textures/gui_brief')
ASSETS = Path('src/main/resources/assets/rs_create_compat/textures')

# (源文件, 目标相对路径, 期望尺寸)
FILES = [
    ('sequence_pattern_terminal.png', 'gui/sequence_pattern_terminal.png', (256, 320)),
    ('collection_cache.png', 'gui/collection_cache.png', (256, 246)),
    ('advanced_remote_terminal_inactive.png', 'item/advanced_remote_terminal_inactive.png', (16, 16)),
    ('advanced_remote_terminal_charged_inactive.png', 'item/advanced_remote_terminal_charged_inactive.png', (16, 16)),
]


def main():
    from PIL import Image

    ok = True
    for name, rel, expect in FILES:
        src = SRC / name
        dst = ASSETS / rel
        if not src.exists():
            print('[MISS] %s 不存在，跳过' % src)
            ok = False
            continue
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(src, dst)
        size = Image.open(dst).size
        flag = 'OK ' if size == expect else 'BAD'
        if size != expect:
            ok = False
        print('[%s] %s -> %s  size=%s expect=%s' % (flag, name, dst, size, expect))

    print('[result] %s' % ('ALL OK' if ok else 'HAS PROBLEM'))


if __name__ == '__main__':
    main()
