# -*- coding: utf-8 -*-
"""伪装框架「裹在线缆外面 + 可取下」这一轮的中英文语言键。

为什么要用脚本：语言文件是严格格式的 JSON（不能有尾逗号、必须 UTF-8、按键名排序），
手改一次写坏就会让整份语言文件静默失效（历史上出过一次）。本脚本：
  1. 用 json.load 读进来（顺带验证现有文件本身是合法 JSON）；
  2. 只更新本轮的键（其余键原样保留）；
  3. 按键名排序后写回（2 空格缩进 + 结尾换行，与仓库其它语言文件一致）；
  4. 立刻回读校验（写坏了当场报错，而不是等游戏加载才发现）。

本轮语言键的来历（与上一版的差别）：
  * help 改写：上一版描述的是「在管道旁边放一个伪装板方块」，实测用户理解不了「怎么收回」；
    现在描述的是「右键线缆/管道把那一格裹起来 + 潜行右键取下 + 拆掉线缆也会掉回」。
  * 新增 wrapped / remove_hint / unwrapped / shelled / take_off_hint：分别对应
    「裹上了」「已裹住请潜行取下」「取下了」「外壳材质已套上」「已裹住（怎么取下）」
    这五条动作栏反馈（键名与 item/CamouflageFrameItem 与 support/RsccCamouflageInteraction 完全一致）。
  * occupied 改写：现在它的语义是「已经是这种外壳了，不重复扣方块」。
  * hint.full_only 保留：仍被「旧存档里放下的伪装框架方块」那条 Create 流程使用。

用法: python tools/add_camouflage_wrap_lang.py
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

PREFIX = "block.rs_create_compat.camouflage_frame"

ZH = {
    PREFIX + ".help":
        "伪装框架：右键线缆/管道把它裹起来（只改外观，不影响连接与网络）；"
        "手持完整方块右键可换外壳；潜行右键取下并归还；拆掉被裹的线缆也会掉回框架与外壳。",
    PREFIX + ".hint.wrapped": "已裹上伪装（手持完整方块右键可选外观）",
    PREFIX + ".hint.remove_hint": "这一格已经裹着伪装：潜行右键可取下并归还",
    PREFIX + ".hint.unwrapped": "已取下伪装，框架与外壳已归还",
    PREFIX + ".hint.shelled": "已套上外壳外观",
    PREFIX + ".hint.take_off_hint": "已裹着伪装：潜行右键（空手/框架/扳手都行）可取下",
    PREFIX + ".hint.occupied": "已经是这种外壳了（潜行右键先取下再更换）",
}

EN = {
    PREFIX + ".help":
        "Camouflage Frame: right-click a cable or pipe to wrap it (looks only - connections and "
        "networks are untouched); right-click with a full block to pick the shell; sneak-right-click "
        "to take it off and get it back; breaking the wrapped cable also drops the frame and shell.",
    PREFIX + ".hint.wrapped": "(hint) Wrapped - right-click with a full block to pick a shell",
    PREFIX + ".hint.remove_hint": "(hint) Already wrapped - sneak-right-click to take it off",
    PREFIX + ".hint.unwrapped": "(hint) Unwrapped - the frame and shell were returned",
    PREFIX + ".hint.shelled": "(hint) Shell applied",
    PREFIX + ".hint.take_off_hint": "(hint) Wrapped - sneak-right-click to take it off",
    PREFIX + ".hint.occupied": "(hint) That shell is already applied - sneak-right-click to take it off first",
}

FILES = {"zh_cn.json": ZH, "en_us.json": EN}


def update(path, entries):
    with io.open(path, "r", encoding="utf-8") as handle:
        data = json.load(handle)  # 回读校验：现有文件本身必须合法
    before = set(data)
    data.update(entries)
    with io.open(path, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(dict(sorted(data.items())), handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    with io.open(path, "r", encoding="utf-8") as handle:
        check = json.load(handle)  # 写入后立刻校验
    added = sorted(set(entries) - before)
    updated = sorted(set(entries) & before)
    print("[OK  ] %s：新增 %d 键 %s / 更新 %d 键 %s，合计 %d 键"
          % (os.path.basename(path), len(added), added, len(updated), updated, len(check)))


def main():
    rc = 0
    for name, entries in FILES.items():
        path = os.path.join(LANG_DIR, name)
        if not os.path.exists(path):
            print("[FAIL] 找不到语言文件：%s" % path)
            rc = 1
            continue
        update(path, entries)
    print("[result] %s" % ("语言键更新完成" if rc == 0 else "存在缺失文件"))
    return rc


if __name__ == "__main__":
    sys.exit(main())
