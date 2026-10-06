# -*- coding: utf-8 -*-
"""本模组「共用贴图」审计：找出「不同用途共用同一张 PNG」的情况。

用户硬规则：贴图不许一图多用（不同方块 / 不同用途不得共用同一张 PNG；
GUI 背景不得复用方块贴图）。唯一例外是**同一台机器的四个竖直侧面**可以共用一张。

本脚本做三件事（只读，不改文件）：
  1. 解析 assets/rs_create_compat/models/**.json 的 texture 引用并展开成「面 -> PNG」，
     同一张 PNG 落在**多个不同的面**上即记为一条「共用」问题。
     例外：恰好落在 north/east/south/west（四个竖直侧面）上 —— 用户明确允许。
     父模型是原版模型（orientable[_with_bottom] / cube[_all] …）时按 VANILLA_FACES 展开面。
  2. 检测内容完全相同的 PNG（字节级重复）—— 只是提示，不计入退出码。
  3. 检测引用但不存在的 PNG（模型漏图），只统计本命名空间的贴图。

输出结构化中文报告；发现「共用 / 缺失」时退出码 1。
"""
import json
import os
import sys
import hashlib
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat")
MODELS = os.path.join(ASSETS, "models")
TEX_DIR = os.path.join(ASSETS, "textures")
NS = "rs_create_compat:"

# 四个竖直侧面：同一台机器共用是刻意的（用户明确允许）
VERTICAL_SIDES = {"north", "east", "south", "west"}
# 这些键不是「面」，不参与共用判定（粒子贴图按惯例引用某个面的贴图）
NON_FACE_KEYS = {"particle"}

# 原版父模型的「面 -> texture 键」映射（父模型不在本命名空间，必须显式展开才能看到共用）
VANILLA_FACES = {
    "minecraft:block/cube": {"north": "north", "east": "east", "south": "south",
                             "west": "west", "up": "up", "down": "down"},
    "minecraft:block/cube_all": {f: "all" for f in
                                 ("north", "east", "south", "west", "up", "down")},
    "minecraft:block/orientable": {"north": "front", "south": "side", "east": "side",
                                   "west": "side", "up": "top"},
    "minecraft:block/orientable_with_bottom": {"north": "front", "south": "side",
                                               "east": "side", "west": "side",
                                               "up": "top", "down": "bottom"},
    "minecraft:block/cube_bottom_top": {"up": "top", "down": "bottom", "north": "side",
                                        "east": "side", "south": "side", "west": "side"},
    "minecraft:block/cube_column": {"up": "end", "down": "end", "north": "side",
                                    "east": "side", "south": "side", "west": "side"},
    "minecraft:block/cube_top": {"up": "top", "down": "side", "north": "side",
                                 "east": "side", "south": "side", "west": "side"},
}


# 硬规则：下列方块的贴图由其它几路并行修改，本任务**不得触碰** —— 审计只报告、不计入退出码。
EXCLUDED = {
    "block/quantity_keeper_active.png": "保持器贴图（另一路在改）",
    "block/quantity_keeper_inactive.png": "保持器贴图（另一路在改）",
    "block/sequence_pattern_terminal_active.png": "SPT 贴图（另一路在改）",
    "block/sequence_pattern_terminal_inactive.png": "SPT 贴图（另一路在改）",
}


def load_json(path):
    with open(path, "r", encoding="utf-8") as handle:
        return json.load(handle)


def collect_models():
    models = {}
    for dirpath, _dirs, files in os.walk(MODELS):
        for name in files:
            if not name.endswith(".json"):
                continue
            full = os.path.join(dirpath, name)
            rel = os.path.relpath(full, MODELS).replace(os.sep, "/")[:-5]
            data = load_json(full)
            models[rel] = {"textures": data.get("textures", {}) or {},
                           "parent": data.get("parent")}
    return models


def parent_id_of(model_id, models):
    """父模型 id（去掉本命名空间前缀）；父模型不在本模组则原样返回。"""
    entry = models.get(model_id)
    if entry is None or not entry["parent"]:
        return None
    parent = entry["parent"]
    return parent[len(NS):] if parent.startswith(NS) else parent


def resolve(model_id, models, trail=None):
    """返回 (合并后的 textures, 面映射)。面映射 = {面: png}（未知父模型时按面键名直取）。"""
    if trail is None:
        trail = set()
    if model_id in trail:
        return {}, {}
    trail = trail | {model_id}
    entry = models.get(model_id)
    if entry is None:
        return {}, {}
    parent = parent_id_of(model_id, models)
    inherited, inherited_faces = {}, {}
    if parent in models:
        inherited, inherited_faces = resolve(parent, models, trail)
    merged = dict(inherited)
    merged.update(entry["textures"])
    if parent in VANILLA_FACES:
        faces = {face: merged.get(key) for face, key in VANILLA_FACES[parent].items()}
        return merged, {f: v for f, v in faces.items() if v}
    if inherited_faces:
        faces = dict(inherited_faces)
        # 子模型只覆盖了自己声明的键：把面映射里指向被覆盖键的项刷新
        for face, ref in list(faces.items()):
            if ref in entry["textures"]:
                faces[face] = entry["textures"][ref]
        return merged, faces
    return merged, {k: v for k, v in merged.items() if k not in NON_FACE_KEYS}


def our_png(ref):
    """本命名空间的贴图引用 -> 相对 textures/ 的路径；非本命名空间返回 None。"""
    if not isinstance(ref, str) or ref.startswith("#"):
        return None
    if ":" in ref and not ref.startswith(NS):
        return None
    rid = ref[len(NS):] if ref.startswith(NS) else ref
    if "/" not in rid and rid.startswith("block"):
        return None
    return rid + ".png"


def main():
    models = collect_models()
    print("=" * 92)
    print("共用贴图审计：共解析 %d 个模型" % len(models))
    print("=" * 92)

    usage = {}          # png -> [(模型, 面)]
    missing = []        # (模型, 面, 引用)
    for model_id in sorted(models):
        _tex, faces = resolve(model_id, models)
        for face, ref in faces.items():
            png = our_png(ref)
            if png is None:
                continue
            usage.setdefault(png, []).append((model_id, face))
            if not os.path.exists(os.path.join(TEX_DIR, *png.split("/"))):
                missing.append((model_id, face, ref))

    print("\n--- 【1】同张 PNG 落在多个「不同的面」上（一图多用） ---")
    problems = []
    excluded_hits = []
    for png in sorted(usage):
        users = usage[png]
        faces = sorted({f for _m, f in users})
        if len(faces) <= 1:
            continue
        if set(faces) == VERTICAL_SIDES:
            print("  [豁免] %-56s 恰好是同一台机器的四个竖直侧面: %s"
                  % (png, ", ".join(faces)))
            continue
        if png in EXCLUDED:
            excluded_hits.append(png)
            print("  [排除] %-56s 原因: %s" % (png, EXCLUDED[png]))
            continue
        problems.append(png)
        print("  [问题] %-56s 面=%s" % (png, ", ".join(faces)))
        for model_id, face in users:
            print("          %-52s <- %s" % (model_id, face))

    print("\n--- 【2】内容完全相同的 PNG（字节级重复；仅提示） ---")
    digest = {}
    for dirpath, _dirs, files in os.walk(TEX_DIR):
        for name in files:
            if not name.lower().endswith(".png"):
                continue
            full = os.path.join(dirpath, name)
            with open(full, "rb") as handle:
                h = hashlib.sha1(handle.read()).hexdigest()
            digest.setdefault(h, []).append(
                os.path.relpath(full, TEX_DIR).replace(os.sep, "/"))
    dup = {h: v for h, v in digest.items() if len(v) > 1}
    for h in sorted(dup, key=lambda k: dup[k][0]):
        print("  [重复] %s" % ", ".join(sorted(dup[h])))
    if not dup:
        print("  [OK] 无重复")

    print("\n--- 【3】引用但缺失的 PNG（本命名空间） ---")
    if missing:
        for model_id, face, ref in missing:
            print("  [缺失] %s 的面 %s -> %s" % (model_id, face, ref))
    else:
        print("  [OK] 无缺失")

    total = len(problems) + len(missing)
    print("\n" + "=" * 92)
    print("一图多用问题: %d 项（另 %d 项按硬规则排除，未计）；字节级重复: %d 组；缺失贴图: %d 项"
          % (len(problems), len(excluded_hits), len(dup), len(missing)))
    print("=" * 92)
    return 1 if total else 0


if __name__ == "__main__":
    sys.exit(main())
