"""检查 git 仓库里的文件路径在 Windows 上是否合法（用于排查 clone 检出失败）。

用法:
    python tools/check_invalid_paths.py <路径列表文件> [仓库根目录]

路径列表文件每行一个仓库内相对路径（可用 `git ls-tree -r --name-only HEAD > 列表.txt` 生成）。
"""

import os
import re
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


# Windows 文件名非法字符
BAD_CHARS = re.compile(r'[:*?"<>|]')
# Windows 保留设备名
RESERVED = {'CON', 'PRN', 'AUX', 'NUL'} | {'COM%d' % i for i in range(1, 10)} | {'LPT%d' % i for i in range(1, 10)}
MAX_PATH = 255


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 1
    list_file = sys.argv[1]
    root = os.path.abspath(sys.argv[2]) if len(sys.argv) > 2 else os.getcwd()

    total = 0
    bad = []
    long_paths = []
    with open(list_file, 'r', encoding='utf-8', errors='replace') as f:
        for line in f:
            rel = line.rstrip('\n').rstrip('\r')
            if not rel:
                continue
            total += 1
            reason = None
            for seg in rel.split('/'):
                if BAD_CHARS.search(seg):
                    reason = '非法字符'
                    break
                if seg.split('.')[0].upper() in RESERVED:
                    reason = '保留设备名'
                    break
                if seg.endswith('.') or seg.endswith(' '):
                    reason = '结尾是点/空格'
                    break
            if reason:
                bad.append((rel, reason))
            if len(os.path.join(root, rel.replace('/', os.sep))) > MAX_PATH:
                long_paths.append(rel)

    print('仓库文件总数: %d' % total)
    print('Windows 非法文件名: %d' % len(bad))
    for rel, reason in bad[:20]:
        print('   [%s] %s' % (reason, rel))
    print('超长路径(>%d): %d' % (MAX_PATH, len(long_paths)))
    for rel in long_paths[:5]:
        print('   %s' % rel)
    return 0


if __name__ == '__main__':
    sys.exit(main())
