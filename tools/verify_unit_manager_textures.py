# -*- coding: utf-8 -*-
"""单元样板管理舱（`unit_pattern_manager`）方块贴图 + 模型校验。

本轮修复的问题：**正面贴图看起来是透明的 / 像缺图**。
根因（先给结论）：贴图文件存在、模型的面引用也正确；根因是生成器 `tools/gen_unit_manager_resources.py`
照抄了 RS 自动合成管理舱的 `autocrafter_manager/front.png` —— 那张图**中间挖了 10×10 的全透明窗**
（16×16 里有 90 个 alpha=0 像素），RS 自己的模型用 `"render_type": "cutout"` 让这个窗**透出方块内部**
（RS 的 `cutout.json` 等父模型都声明了 cutout）；本方块模型是 `minecraft:block/cube` 且不声明
render_type，于是那 90 个透明像素要么被剔除（透空）要么按不透明绘制（白块），都不对。
修法：正面重绘成一块**不透明面板**（内凹暗边 + 屏幕 + 样板槽 + 指示灯），六面全部不透明。

本脚本断言（可重复执行）：
  A. 六面 × 两态共 12 张贴图都存在、16×16、**没有任何透明 / 半透明像素**；
     正面（本轮重绘）与 RS 源贴图不再相同，且 10×10 面板区确实画了内容（≥4 级灰度 + 指示灯）。
  B. 模型 / blockstate 的面引用都能解析到真实存在的贴图；模型不声明 render_type
     （不透明贴图 + 默认 solid → 结构上不可能再出现「透明像素被剔除」造成的透空）。

用法：python tools/verify_unit_manager_textures.py → 末行「问题总数: 0」
"""
import json
import os
import sys
from PIL import Image
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat")
TEXTURES = os.path.join(ASSETS, "textures", "block")
MODELS_BLOCK = os.path.join(ASSETS, "models", "block")
MODELS_ITEM = os.path.join(ASSETS, "models", "item")
BLOCKSTATES = os.path.join(ASSETS, "blockstates")
RS_FRONT = os.path.join(ROOT, "local_src", "external", "RefinedStorage", "refinedstorage-common",
                        "src", "main", "resources", "assets", "refinedstorage", "textures",
                        "block", "autocrafter_manager", "front.png")

BLOCK_ID = "unit_pattern_manager"
FACES = ("front", "back", "left", "right", "top", "bottom")
# RS 正面被挖空的那块窗（左闭右开）：本工程的正面必须把它补成不透明面板
FRONT_WINDOW = (3, 3, 13, 13)

problems = []


def check(condition, ok_msg, bad_msg=""):
    print("  [%s] %s" % ("OK" if condition else "X", ok_msg))
    if not condition:
        problems.append(bad_msg or ok_msg)


def texture_path(face, active):
    return os.path.join(TEXTURES, "%s_%s%s.png" % (BLOCK_ID, face, "_active" if active else ""))


print("===== A. 六面贴图（16×16 / 无透明像素）=====")
front_base = None
front_active = None
for face in FACES:
    for active in (False, True):
        path = texture_path(face, active)
        label = "%s_%s%s" % (BLOCK_ID, face, "_active" if active else "")
        if not os.path.exists(path):
            check(False, label + " 存在", label + " 不存在：" + path)
            continue
        image = Image.open(path).convert("RGBA")
        pixels = image.load()
        alphas = [pixels[x, y][3] for y in range(image.height) for x in range(image.width)]
        transparent = alphas.count(0)
        semi = len([a for a in alphas if 0 < a < 255])
        check(image.size == (16, 16) and transparent == 0 and not semi,
              "%s：16×16、0 个透明像素、无半透明（min_alpha=%d）" % (label, min(alphas)),
              "%s：size=%s 透明=%d 半透明=%d" % (label, image.size, transparent, semi))
        if face == "front":
            if active:
                front_active = image
            else:
                front_base = image

if front_active is not None:
    x0, y0, x1, y1 = FRONT_WINDOW
    px = front_active.load()
    levels = {px[x, y][:3] for y in range(y0, y1) for x in range(x0, x1)}
    grays = {c[0] for c in levels if c[0] == c[1] == c[2]}
    check(len(grays) >= 4,
          "正面 10×10 面板区确实画了内容（%d 级灰度，非纯色填充）" % len(grays),
          "正面面板区灰度级只有 %d 级，看起来像没画" % len(grays))
    led = [c for c in levels if c[1] > c[0] + 16 and c[1] > c[2] + 16]
    check(bool(led),
          "正面带「已接入」指示灯（绿色像素 %s，接入后点亮）" % (led[:3],),
          "正面缺少指示灯（已接入态看不出来）")

if front_base is not None and os.path.exists(RS_FRONT):
    rs = Image.open(RS_FRONT).convert("RGBA")
    differs = any(front_base.getpixel((x, y)) != rs.getpixel((x, y))
                  for y in range(16) for x in range(16))
    check(differs, "正面已**重绘**（与 RS 自动合成管理舱的挖空正面不再相同）",
          "正面仍是 RS 源贴图的拷贝（透明窗会再次出现）")
elif front_base is not None:
    print("  [skip] 未找到 RS 源贴图（local_src 变了？），跳过「正面已重绘」对比")

print("===== B. 模型 / blockstate 的面引用与 render_type =====")
model_ids = {"%s" % BLOCK_ID: os.path.join(MODELS_BLOCK, "%s.json" % BLOCK_ID),
             "%s_active" % BLOCK_ID: os.path.join(MODELS_BLOCK, "%s_active.json" % BLOCK_ID)}
for name, path in model_ids.items():
    if not os.path.exists(path):
        check(False, "模型 %s.json 存在" % name, "缺少模型：" + path)
        continue
    with open(path, "r", encoding="utf-8") as handle:
        model = json.load(handle)
    check(model.get("parent") == "minecraft:block/cube",
          "模型 %s.json 基于 minecraft:block/cube（六面独立贴图）" % name,
          "模型 %s.json 的 parent = %s" % (name, model.get("parent")))
    check("render_type" not in model,
          "模型 %s.json 未声明 render_type（贴图已全部不透明 → 默认 solid 即可）" % name,
          "模型 %s.json 声明了 render_type=%s（不透明贴图不需要）" % (name, model.get("render_type")))
    textures = model.get("textures", {})
    missing = []
    for slot, ref in textures.items():
        rel = ref.split(":", 1)[1] if ":" in ref else ref
        if not os.path.exists(os.path.join(ASSETS, "textures", rel + ".png")):
            missing.append("%s=%s" % (slot, ref))
    check(not missing and len(textures) >= 7,
          "模型 %s.json 的 %d 个面引用都能解析到真实贴图" % (name, len(textures)),
          "模型 %s.json 有无法解析的面引用：%s" % (name, missing))

with open(os.path.join(BLOCKSTATES, "%s.json" % BLOCK_ID), "r", encoding="utf-8") as handle:
    blockstate = json.load(handle)
bad_variants = []
for variant, entry in blockstate.get("variants", {}).items():
    ref = entry.get("model", "")
    rel = ref.split(":", 1)[1] if ":" in ref else ref
    if not os.path.exists(os.path.join(ASSETS, "models", rel + ".json")):
        bad_variants.append("%s -> %s" % (variant, ref))
check(not bad_variants and len(blockstate.get("variants", {})) == 8,
      "blockstate 的 8 个变体（4 朝向 × 2 接入态）都指向真实存在的模型",
      "blockstate 存在无效引用：%s" % bad_variants)

item_model = os.path.join(MODELS_ITEM, "%s.json" % BLOCK_ID)
with open(item_model, "r", encoding="utf-8") as handle:
    item = json.load(handle)
check(item.get("parent") == "rs_create_compat:block/%s" % BLOCK_ID,
      "物品模型继承方块模型（物品图标与方块正面一致）",
      "物品模型 parent = %s" % item.get("parent"))

print("=" * 60)
print("问题总数: %d" % len(problems))
for problem in problems:
    print("  [X] " + problem)
sys.exit(1 if problems else 0)
