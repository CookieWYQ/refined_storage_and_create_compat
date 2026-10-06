# -*- coding: utf-8 -*-
"""把本模组机器方块物品的注册从 RS 的 BaseBlockItem 换成 RsccHelpBlockItem。

为什么：RS 的 BaseBlockItem 把帮助文本包成它自己的「常显」帮助组件；用户要求本模组附加的
tooltip 信息默认隐藏、按住 Shift 才展开。RsccHelpBlockItem 不把帮助文本交给父类，
改由 appendHoverText 走 RsccTooltipLayers（唯一实现）按 SHIFT 层追加。

本脚本是**机械替换**：只改构造类名，`DeferredItem<BaseBlockItem>` 声明不变（子类仍是 BaseBlockItem）。
每处替换都断言「恰好出现 1 次」，避免误伤 camouflage_frame / separation_frame（它们有各自的约束型实现）。
"""
import io
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MAIN = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat",
                    "RS_Create_Compat.java")
OLD = "new com.refinedmods.refinedstorage.common.support.BaseBlockItem("
NEW = "new cretae.cookiewyq.rs_create_compat.item.RsccHelpBlockItem("
EXPECTED = 11


def main():
    with io.open(MAIN, encoding="utf-8") as handle:
        source = handle.read()
    count = source.count(OLD)
    if count != EXPECTED:
        print("[X] 期望 %d 处 BaseBlockItem 构造，实为 %d —— 源码已变动，请人工核对后再改" % (EXPECTED, count))
        return 1
    # camouflage_frame / separation_frame 必须保持各自实现（既有自检 F11 / 分隔框架约束）
    for must_keep in ("item.CamouflageFrameItem(", "item.SeparationFrameItem("):
        if must_keep not in source:
            print("[X] 找不到应保留的实现：%s" % must_keep)
            return 1
    source = source.replace(OLD, NEW)
    if OLD in source:
        print("[X] 仍残留 BaseBlockItem 构造")
        return 1
    with io.open(MAIN, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(source)
    print("已替换 %d 处：BaseBlockItem → RsccHelpBlockItem（帮助文本改为按住 Shift 展开）" % count)
    print("问题总数: 0")
    return 0


if __name__ == "__main__":
    sys.exit(main())
