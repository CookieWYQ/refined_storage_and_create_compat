# -*- coding: utf-8 -*-
"""给用户自绘的 ``models/block/separation_frame.json`` 补上「方块标准父模型」并统一半透明渲染类型。

为什么要用脚本而不是手改（用户规则：JSON 一律用 python 写）：
  * 这是**用户用 Blockbench 自绘**的模型，脚本<b>只做两处最小改动</b>，
    其余键（``elements`` / ``textures`` / ``groups`` …）连字符都不动 —— 因此这里用的是
    「文本定点插入 + 回读校验」，而不是 ``json.dump`` 整份重写（那会把用户的排版也一起改掉）。

两处改动及其理由：
  1. 加 ``"parent": "block/block"``：用户反馈「拿在手上太大、物品栏显示成一个平面」。
     根因就是模型没有父模型 → 没有 ``display`` 变换：物品栏按「平面图标」渲染、手持没有缩放。
     ``block/block`` 恰好提供 gui / thirdperson / firstperson / ground / fixed 全套 display
     变换（gui 是 3D 方块视角、thirdperson 缩放到正常方块大小），因此物品栏变立体、手持尺寸正常。
  2. ``render_type``：``cutout`` → ``translucent``。用户自绘的贴图是 16×16 ARGB 半透明贴图，
     cutout 会把带 alpha 的像素裁成「完全不透明 / 完全透明」，玻璃质感会丢。
     物品侧走的也是这条 ``render_type``（NeoForge 的
     ``RenderTypeHelper#getFallbackItemRenderType`` 会读方块模型的渲染层集合），
     因此物品栏 / 手持 / 掉落物同样变成半透明。

幂等：已经加过 parent / 已是 translucent 时只打印「已是目标状态」，重复执行不会改坏文件。

用法: python tools/add_separation_frame_model_parent.py
退出码：0 = 已是目标状态；1 = 改坏了（回读或元素校验失败）
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
MODEL = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "models", "block",
                     "separation_frame.json")

# 只应该改这两个键；其余内容必须逐字不变（用户的 Blockbench 成果）
PRESERVED_KEYS = ("elements", "textures", "groups", "format_version", "credit")


def read_text(path):
    with io.open(path, "r", encoding="utf-8") as handle:
        return handle.read()


def load(text):
    """回读校验：解析失败直接抛，绝不留半成品。"""
    return json.loads(text)


def main():
    original_text = read_text(MODEL)
    before = load(original_text)
    text = original_text
    changed = []

    # 1) parent：加在对象第一个键之前（Blockbench 的 tab 缩进保持原样）
    if "parent" not in before:
        marker = "{\n"
        if not text.startswith(marker):
            print("[FAIL] 模型首行不是预期的 `{\\n`，为安全起见不做任何改动：%s" % MODEL)
            return 1
        text = marker + '\t"parent": "block/block",\n' + text[len(marker):]
        changed.append("parent=block/block")
    else:
        print("[skip] 已有 parent：%s" % before["parent"])

    # 2) render_type：cutout -> translucent（半透明贴图必须用半透明渲染）
    if '"render_type": "cutout"' in text:
        text = text.replace('"render_type": "cutout"', '"render_type": "translucent"', 1)
        changed.append("render_type=translucent")
    elif '"render_type": "translucent"' in text:
        print("[skip] render_type 已是 translucent")
    else:
        # 没有任何 render_type 时补一个（NeoForge 的合法写法，值必须是 minecraft:translucent 的短名）
        text = text.replace("{\n", '{\n\t"render_type": "translucent",\n', 1)
        changed.append("render_type=translucent(新增)")

    if not changed:
        print("[result] 已是目标状态（parent + translucent），未改动文件：%s"
              % os.path.relpath(MODEL, ROOT).replace("\\", "/"))
        return 0

    with io.open(MODEL, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(text)

    # 回读 + 元素/贴图逐字校验：用户自绘的部分必须一个字符都没变
    after = load(read_text(MODEL))
    for key in PRESERVED_KEYS:
        if before.get(key) != after.get(key):
            print("[FAIL] 关键内容被改动了：%s（已写盘，请用版本控制还原后重试）" % key)
            return 1
    if after.get("parent") != "block/block" or after.get("render_type") != "translucent":
        print("[FAIL] 目标键没有正确落盘：parent=%r render_type=%r"
              % (after.get("parent"), after.get("render_type")))
        return 1
    print("[OK  ] %s" % os.path.relpath(MODEL, ROOT).replace("\\", "/"))
    print("       改动：%s；elements/textures/groups 逐字未变（用户自绘内容完整保留）" % ", ".join(changed))
    print("[result] 已补 parent: block/block + render_type: translucent")
    return 0


if __name__ == "__main__":
    sys.exit(main())
