# -*- coding: utf-8 -*-
"""网络资源接收器 / 请求器 的「贴图 + 模型 + blockstate」自检（只读，可复跑）。退出码 0 = 全通过。

断言（每条都能复算，不靠肉眼）：
  A 文件清单     —— 18 张约定贴图（接收器 3 后缀 × 2 态、请求器 6 后缀 × 2 态）全部存在，
                    block/ 下没有同前缀的多余 / 旧命名文件；
  B 尺寸 / 模式  —— 每张 16×16、PNG 模式 RGBA、非纯色（唯一色数 ≥ 8）；
  C 调色板       —— **每一个像素**都精确落在「既有 RS 底图像素 ∪ 既有生成脚本四常量 ∪
                    本方块强调色的明/暗两档」之内（0 个色板外颜色）。
                    这条同时就是「硬边像素、无抗锯齿、无平滑渐变」的机器判据：
                    任何插值 / 羽化 / 外发光渐变都必然产生色板外的颜色。
  D 两态可区分   —— 同方块同面的 active / inactive 字节不同，且 active 的主强调色更亮；
  E 不撞图       —— ① 同方块同态下，**同后缀只有一张**、不同后缀两两字节不同；
                    ② 跨方块不存在字节完全相同的文件；
                    ③ **接收器关键区分**：`_side` / `_front` / `_back` 三张两两不同
                       （否则执行舱式模型会把同一张图铺到多个面 → 一图多用）；
  F 两方块可区分 —— 主强调色色相差 > 60°，且正面「主强调色字形」掩膜 Jaccard ≤ 0.5（收 / 发 互为负形）；
  G **接收器朝向手法 = 执行舱逐字一致**（本轮核心）：
                    ① blockstate 覆盖 **6 向 `facing`**（north/east/south/west/up/down）× active 两态；
                    ② 旋转表与 `tools/gen_face_split_models.py` 的 `CHAMBER_ROTATIONS` **逐字相等**，
                       且四向都带 `x: 90`；
                    ③ `tools/gen_network_io_models.RECEIVER_FACES` 与执行舱 `CHAMBER_FACES` 逐字相等
                       （`up` = `_front`、`down` = `_back`、四竖直侧面 = `_side`）；
                    ④ 模型六面显式指定且指向存在的 PNG（展开后逐面核对）；
                    ⑤ 四向变体都带 `x: 90`、`up` **不**旋转、`down` = `x: 180`；
  H **请求器无朝向**：
                    ① blockstate **完全不含 `facing`**（键里没有、值里也没有），只有 active 两态；
                    ② 模型/物品模型里**没有任何 `x`/`y`/`z` 旋转**；
                    ③ 请求器每个后缀的贴图**上下 / 左右都镜像对称**（无方向），且 `_front` 无
                       「一端只在一侧」的箭头式字形（用掩膜的对称性判据）；
  I 物品模型     —— 两个方块都有 `models/item/<id>.json` 且父指向 `..._active`（照执行舱写法）；
  J 无残留       —— block/ 与 tmp_textures/network_io/ 里没有上一版的接收器 5 面旧图。

用法：
    python tools/verify_network_io_textures.py
"""
import colorsys
import hashlib
import json
import os
import sys

from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gen_network_io_textures import (  # noqa: E402
    ACCENTS, BASE_SIDE, BLOCKS, DARK, DARK2, LAYOUTS, LIGHT, OUT, PREVIEW, RECEIVER,
    RECESS, REQUESTER, ROOT, STATES, SUFFIXES, accent_pair, allowed_colors,
    expected_textures, tex_name,
)
from gen_network_io_models import (  # noqa: E402
    NS, RECEIVER_FACES, RECEIVER_ROTATIONS, REQUESTER_FACES, REQUESTER_ORDER,
)

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ASSETS = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat")
MODELS = os.path.join(ASSETS, "models", "block")
ITEMS = os.path.join(ASSETS, "models", "item")
BLOCKSTATES = os.path.join(ASSETS, "blockstates")
TOOLS = os.path.join(ROOT, "tools")

failures = []
notes = []


def check(ok, msg):
    print("  [%s] %s" % ("PASS" if ok else "FAIL", msg))
    if not ok:
        failures.append(msg)
    return ok


def load(name):
    raw = Image.open(os.path.join(OUT, name))
    return raw, raw.convert("RGBA")


def pixels(img):
    return [img.getpixel((x, y)) for y in range(img.height) for x in range(img.width)]


def sha(path):
    with open(path, "rb") as handle:
        return hashlib.sha256(handle.read()).hexdigest()


def hue_of(c):
    h, _s, _v = colorsys.rgb_to_hsv(c[0] / 255.0, c[1] / 255.0, c[2] / 255.0)
    return h * 360.0


def brightest_accent(img, cands):
    present = set(pixels(img))
    hits = [c for c in cands if c in present]
    if not hits:
        return None
    return max(hits, key=lambda c: 0.299 * c[0] + 0.587 * c[1] + 0.114 * c[2])


def load_json(path):
    with open(path, "r", encoding="utf-8") as handle:
        return json.load(handle)


# ---------------------------------------------------------------- 执行舱（参照物）
def chamber_reference():
    """从 `tools/gen_face_split_models.py` **源码**解析执行舱的朝向表，避免"抄错"抄成常量。"""
    import ast
    with open(os.path.join(TOOLS, "gen_face_split_models.py"), "r", encoding="utf-8") as handle:
        tree = ast.parse(handle.read())
    found = {}
    for node in tree.body:
        if isinstance(node, ast.Assign):
            for target in node.targets:
                if isinstance(target, ast.Name) and target.id in (
                        "CHAMBER_FACES", "CHAMBER_ROTATIONS"):
                    found[target.id] = ast.literal_eval(node.value)
    return found.get("CHAMBER_FACES", {}), found.get("CHAMBER_ROTATIONS", {})


def resolve_model(block_id, active):
    """读取并展开本模组的方块模型 -> {模型面: 贴图引用}；只认 `minecraft:block/cube` 父。"""
    path = os.path.join(MODELS, "%s%s.json" % (block_id, "_active" if active else ""))
    data = load_json(path)
    tex = data.get("textures", {})
    parent = data.get("parent")
    faces = {}
    if parent == "minecraft:block/cube":
        for face in ("north", "east", "south", "west", "up", "down"):
            if face in tex:
                faces[face] = tex[face]
    return data, tex, faces


def main():
    print("=" * 92)
    print("网络资源接收器 / 请求器 贴图 + 模型 + blockstate 自检"
          "    （tools/verify_network_io_textures.py）")
    print("=" * 92)

    expected = expected_textures()
    palette = allowed_colors()

    print("\n[A] 文件清单（18 张 = 接收器 3 后缀 × 2 态 + 请求器 6 后缀 × 2 态）")
    print("  允许色板大小 = %d（RS 底图像素 + 4 常量 + 2 方块 × 2 态 × 主/柔）" % len(palette))
    print("  接收器后缀 = %s（照执行舱：四竖直侧面共用 side）" % ", ".join(SUFFIXES[RECEIVER]))
    print("  请求器后缀 = %s（无朝向：6 面各自独立）" % ", ".join(SUFFIXES[REQUESTER]))
    missing = [n for n in expected if not os.path.exists(os.path.join(OUT, n))]
    check(not missing, "18 个约定文件全部存在（缺 %d）" % len(missing))
    for n in missing:
        print("      - 缺 %s" % n)
    extra = sorted(n for n in os.listdir(OUT)
                   if n.endswith(".png")
                   and n.startswith(("network_resource_receiver", "network_resource_requester"))
                   and n not in expected)
    check(not extra, "block/ 下没有同前缀的多余 / 旧命名文件（多 %d）" % len(extra))
    for n in extra:
        print("      - 多余 %s" % n)

    print("\n[B/C] 逐文件 尺寸 / 模式 / 唯一色数 / 调色板命中 / 字节")
    print("  %-52s %-7s %-6s %-6s %-8s %-8s %s"
          % ("文件", "尺寸", "模式", "色数", "色板外", "字节", "主强调色"))
    stats = {}
    for name in expected:
        if name in missing:
            continue
        raw, img = load(name)
        px = pixels(img)
        uniq = set(px)
        outside = uniq - palette
        stats[name] = uniq
        block = RECEIVER if name.startswith(RECEIVER) else REQUESTER
        active = name.endswith("_active.png")
        main, soft = accent_pair(block, active)
        acc = brightest_accent(img, (main, soft))
        nbytes = os.path.getsize(os.path.join(OUT, name))
        print("  %-52s %-7s %-6s %-6d %-8d %-8d %s" % (
            name, "%dx%d" % img.size, raw.mode, len(uniq), len(outside), nbytes,
            "#%02X%02X%02X" % acc[:3] if acc else "—"))
        if img.size != (16, 16):
            failures.append("%s 尺寸不是 16×16" % name)
        if raw.mode != "RGBA":
            failures.append("%s PNG 模式不是 RGBA" % name)
        if len(uniq) < 8:
            failures.append("%s 唯一色数 %d < 8（疑似纯色）" % (name, len(uniq)))
        if outside:
            failures.append("%s 有 %d 个色板外颜色：%s" % (name, len(outside), sorted(outside)[:4]))
    check(all(load(n)[1].size == (16, 16) for n in expected if n not in missing), "全部 16×16")
    check(all(Image.open(os.path.join(OUT, n)).mode == "RGBA"
              for n in expected if n not in missing), "全部 PNG 模式 RGBA")
    check(all(len(stats[n]) >= 8 for n in stats), "全部非纯色（唯一色数 ≥ 8）")
    outside_total = sum(len(stats[n] - palette) for n in stats)
    check(outside_total == 0,
          "调色板 100%% 命中既有色板（色板外像素合计 %d）→ 等价于「无抗锯齿/无平滑渐变」"
          % outside_total)

    print("\n[D] 两态（inactive / active）可区分")
    for block in BLOCKS:
        for suffix in SUFFIXES[block]:
            ni, na = tex_name(block, suffix, False), tex_name(block, suffix, True)
            if ni in missing or na in missing:
                continue
            same = sha(os.path.join(OUT, ni)) == sha(os.path.join(OUT, na))
            lum = lambda c: 0.299 * c[0] + 0.587 * c[1] + 0.114 * c[2]
            mi, si = accent_pair(block, False)
            ma, sa = accent_pair(block, True)
            bi = brightest_accent(load(ni)[1], (mi, si))
            ba = brightest_accent(load(na)[1], (ma, sa))
            check((not same) and bi and ba and lum(ba) > lum(bi),
                  "%s_%s：active 与 inactive 不同且 active 更亮" % (block, suffix))

    print("\n[E] 不撞图（用户硬规则：以后写贴图不要全部共用一个贴图）")
    for block in BLOCKS:
        for active in STATES:
            hs = {}
            for suffix in SUFFIXES[block]:
                n = tex_name(block, suffix, active)
                if n not in missing:
                    hs[suffix] = sha(os.path.join(OUT, n))
            keys = list(hs)
            dup = [(x, y) for i, x in enumerate(keys) for y in keys[i + 1:] if hs[x] == hs[y]]
            check(not dup, "%s（%s）：%d 张后缀贴图两两不同%s" % (
                block, "active" if active else "inactive", len(keys),
                "" if not dup else " 重复=%s" % dup))
    digests = {}
    for n in expected:
        if n not in missing:
            digests.setdefault(sha(os.path.join(OUT, n)), []).append(n)
    cross = {h: v for h, v in digests.items()
             if len({RECEIVER if n.startswith(RECEIVER) else REQUESTER for n in v}) > 1}
    check(not cross, "跨方块没有字节完全相同的文件%s" % (
        "" if not cross else "  重复=%s" % list(cross.values())))
    side_front_back = [tex_name(RECEIVER, s, True) for s in ("side", "front", "back")]
    check(len({sha(os.path.join(OUT, n)) for n in side_front_back
               if n not in missing}) == 3,
          "接收器 side / front / back 三张两两不同（执行舱式模型不会把同一张铺到多个不同面）")

    print("\n[F] 两个方块可区分")
    acc_act = {b: accent_pair(b, True)[0] for b in BLOCKS}
    hh = {b: hue_of(acc_act[b]) for b in BLOCKS}
    dh = abs(hh[RECEIVER] - hh[REQUESTER])
    dh = min(dh, 360 - dh)
    check(dh > 60, "主强调色色相差 %.1f° > 60°（%s %s vs %s %s）" % (
        dh, RECEIVER, "#%02X%02X%02X" % acc_act[RECEIVER][:3],
        REQUESTER, "#%02X%02X%02X" % acc_act[REQUESTER][:3]))
    masks = {}
    for b in BLOCKS:
        img = load(tex_name(b, "front", True))[1]
        main = accent_pair(b, True)[0]
        masks[b] = {(x, y) for y in range(16) for x in range(16)
                    if img.getpixel((x, y)) == main}
    inter = len(masks[RECEIVER] & masks[REQUESTER])
    union = len(masks[RECEIVER] | masks[REQUESTER])
    jac = inter / union if union else 1.0
    check(jac < 0.5, "front 面「主强调色字形」掩膜 Jaccard = %.3f < 0.5（收/发 形状不同）" % jac)
    notes.append("front 字形像素数：%s=%d（实心方块 + 四角触点），%s=%d（亮芯 + 四向等长射线）"
                 % (RECEIVER, len(masks[RECEIVER]), REQUESTER, len(masks[REQUESTER])))

    print("\n[G] 接收器朝向手法：与执行舱逐字一致（前端面 = 模型 UP + x:90 系列）")
    ref_faces, ref_rot = chamber_reference()
    check(ref_faces == RECEIVER_FACES,
          "接收器模型面 -> 贴图后缀 与执行舱 CHAMBER_FACES 逐字相等：%s" % RECEIVER_FACES)
    check(ref_rot == {k: v for k, v in RECEIVER_ROTATIONS.items() if k in ref_rot},
          "四向旋转与执行舱 CHAMBER_ROTATIONS 逐字相等")
    check(ref_rot.get("north") == {"x": 90} and ref_rot.get("east") == {"x": 90, "y": 90}
          and ref_rot.get("south") == {"x": 90, "y": 180} and ref_rot.get("west") == {"x": 90, "y": 270},
          "执行舱四向旋转确认带 x:90 系列：%s" % ref_rot)
    check(RECEIVER_FACES.get("up") == "front" and RECEIVER_FACES.get("down") == "back"
          and {RECEIVER_FACES[f] for f in ("north", "east", "south", "west")} == {"side"},
          "接收器：up=_front（前端面烘焙在 UP）、down=_back、四竖直侧面=_side")

    bs = load_json(os.path.join(BLOCKSTATES, RECEIVER + ".json"))
    variants = bs.get("variants", {})
    want_keys = ["facing=%s,active=%s" % (f, a)
                 for f in ("north", "east", "south", "west", "up", "down")
                 for a in ("false", "true")]
    check(sorted(variants) == sorted(want_keys),
          "blockstate 覆盖 6 向 facing × active 两态 = %d 个变体（实际 %d）"
          % (len(want_keys), len(variants)))
    rot_ok, rot_bad = True, []
    for facing in ("north", "east", "south", "west", "up", "down"):
        for a in ("false", "true"):
            key = "facing=%s,active=%s" % (facing, a)
            entry = variants.get(key, {})
            got = {k: v for k, v in entry.items() if k != "model"}
            if got != RECEIVER_ROTATIONS[facing]:
                rot_ok = False
                rot_bad.append("%s: 期望 %s，实际 %s" % (key, RECEIVER_ROTATIONS[facing], got))
    check(rot_ok, "每个变体的旋转与接收器旋转表完全一致%s" % ("" if rot_ok else "：" + "; ".join(rot_bad)))
    four = all(variants.get("facing=%s,active=%s" % (f, a), {}).get("x") == 90
               for f in ("north", "east", "south", "west") for a in ("false", "true"))
    check(four, "四个水平朝向的 8 个变体全部带 x: 90（朝向既在侧面可见，也在顶面可见）")
    check("x" not in variants.get("facing=up,active=false", {})
          and "y" not in variants.get("facing=up,active=false", {}),
          "facing=up 不旋转（前端面本来就是模型 UP）")
    check(variants.get("facing=down,active=false", {}).get("x") == 180,
          "facing=down 旋转 x: 180")
    check(all(variants["facing=%s,active=%s" % (f, a)]["model"]
              == NS + RECEIVER + ("_active" if a == "true" else "")
              for f in ("north", "east", "south", "west", "up", "down")
              for a in ("false", "true")),
          "每个变体都指向 base / _active 两份模型")

    face_ok, face_bad = True, []
    for active in STATES:
        _data, tex, faces = resolve_model(RECEIVER, active)
        if len(faces) != 6:
            face_ok = False
            face_bad.append("active=%s 只展开了 %d 个面" % (active, len(faces)))
            continue
        for face, suffix in RECEIVER_FACES.items():
            want = NS + RECEIVER + "_" + suffix + ("_active" if active else "_inactive")
            if faces.get(face) != want:
                face_ok = False
                face_bad.append("active=%s %s: 期望 %s，实际 %s" % (active, face, want, faces.get(face)))
    check(face_ok, "接收器模型六面显式指定且与 side/front/back 的对应关系一致%s"
          % ("" if face_ok else "：" + "; ".join(face_bad)))
    refs = {v for active in STATES for v in resolve_model(RECEIVER, active)[2].values()}
    check(all(os.path.exists(os.path.join(OUT, r[len(NS):] + ".png")) for r in refs),
          "接收器模型引用的 %d 张贴图全部存在" % len(refs))

    print("\n[H] 请求器无朝向")
    bs2 = load_json(os.path.join(BLOCKSTATES, REQUESTER + ".json"))
    variants2 = bs2.get("variants", {})
    check(sorted(variants2) == ["active=false", "active=true"],
          "blockstate 只有 active 两态：%s" % sorted(variants2))
    raw2 = json.dumps(bs2, ensure_ascii=False)
    check("facing" not in raw2, "blockstate 原文里完全不含 facing（键与值都没有）")
    for active in STATES:
        data, _tex, faces = resolve_model(REQUESTER, active)
        check(not any(k in data for k in ("x", "y", "z", "uvlock")),
              "请求器模型 active=%s 无任何旋转字段" % active)
        check(len(faces) == 6 and set(faces) == set(REQUESTER_ORDER),
              "请求器模型 active=%s 六面显式指定（%d 面）" % (active, len(faces)))
    for active in STATES:
        for face, suffix in REQUESTER_FACES.items():
            want = NS + REQUESTER + "_" + suffix + ("_active" if active else "_inactive")
            got = resolve_model(REQUESTER, active)[2].get(face)
            if got != want:
                failures.append("请求器 active=%s %s 期望 %s 实际 %s" % (active, face, want, got))
    check(not any(f.startswith("请求器 active=") for f in failures),
          "请求器模型六面 -> front/back/left/right/top/bottom 映射正确")

    # 无方向判据（**两条，都可复算**）：
    #
    #  (1) **强调色掩膜必须精确镜像闭合** —— 本脚本的全部笔迹都只用主/柔强调色两个常量
    #      （`tools/gen_network_io_textures.py` 只写这两个色 + 四个机壳常量），而这两个常量
    #      **在任何底图像素上都找不到**，所以掩膜 = 笔迹。掩膜关于 16px 画布真中线 7.5 的
    #      镜像必须闭合：
    #        * 请求器 6 面：左右 + 上下都闭合（图形不可能表达朝向）；
    #        * 接收器 `_side`：左右闭合、**上下不闭合**，且不闭合像素必须全部落在折线箭嘴那
    #          3 行内（方向感只由箭嘴承担）；
    #        * 接收器 `_front` / `_back`：左右 + 上下都闭合（方向只在侧面表达）。
    #
    #  (2) **整图逐像素差异只允许出现在"底图自带的抖动"位置** ——
    #      RS 机壳底图 `tmp_rs_ref2/block/side.png` 自己就不是严格镜像的（灰阶 ±1~8 抖动，
    #      例 (9,3)=122 ↔ (6,3)=114）。这个噪声集合**可以从底图直接算出来**，因此
    #      「差异 ⊆ 底图噪声」是一条可复算的硬断言：只要把结构画在镜像对上就必然满足。
    INNER = tuple(range(3, 13))
    # 折线箭嘴的 3 行**及其镜像行**（掩膜"上下不闭合"的像素必然成对出现在这两侧）
    ARROW = {(x, y) for row in (6, 7, 8) for x in range(5, 11) for y in (row, 15 - row)}
    TOL = 2

    base_px = Image.open(BASE_SIDE).convert("RGBA").load()

    def near(c, t, tol=TOL):
        return all(abs(c[i] - t[i]) <= tol for i in range(3))

    def base_noise(axis):
        """底图自身在该轴上镜像不闭合的像素（本脚本无法控制，故豁免）。"""
        out = set()
        for y in INNER:
            for x in INNER:
                q = (15 - x, y) if axis == "h" else (x, 15 - y)
                if base_px[x, y] != base_px[q[0], q[1]]:
                    out.add((x, y))
        return out

    def accent_mask(block, img):
        main, soft = accent_pair(block, True)
        return {(x, y) for y in INNER for x in INNER
                if any(near(img.getpixel((x, y)), w) for w in (main, soft))}

    def mirrored(pts, axis):
        if axis == "h":
            return {(15 - x, y) for (x, y) in pts}
        return {(x, 15 - y) for (x, y) in pts}

    for block in BLOCKS:
        for suffix in SUFFIXES[block]:
            img = load(tex_name(block, suffix, True))[1]
            mask = accent_mask(block, img)
            dh, dv = mask ^ mirrored(mask, "h"), mask ^ mirrored(mask, "v")
            if not mask:
                failures.append("%s 没有强调色笔迹（无法判方向）" % suffix)
                continue
            if block == REQUESTER:
                check(not dh and not dv,
                      "请求器 %s：强调色掩膜精确镜像闭合（不闭合 %d / %d；掩膜 %d px）"
                      "⇒ 图形不指任何方向" % (suffix, len(dh), len(dv), len(mask)))
            elif suffix == "side":
                check(not dh,
                      "接收器 _side：掩膜左右精确闭合（不闭合 %d）⇒ 箭嘴居中不偏心" % len(dh))
                check(dv and dv <= ARROW,
                      "接收器 _side：掩膜上下**不**闭合（%d px）且全部落在折线箭嘴那 3 行内"
                      "（箭嘴共 %d px）⇒ 方向感只由箭嘴承担" % (len(dv), len(ARROW)))
            else:
                check(not dh and not dv,
                      "接收器 _%s：掩膜左右 + 上下精确闭合（不闭合 %d / %d）"
                      "—— 方向只由 _side 表达" % (suffix, len(dh), len(dv)))

    print("\n[H2] 整图逐像素：结构差异与底图抖动分离")
    for block in BLOCKS:
        for suffix in SUFFIXES[block]:
            img = load(tex_name(block, suffix, True))[1]
            detail = []
            for axis in ("h", "v"):
                noise = base_noise(axis)
                for y in INNER:
                    for x in INNER:
                        q = (15 - x, y) if axis == "h" else (x, 15 - y)
                        if img.getpixel((x, y)) == img.getpixel(q):
                            continue
                        if (x, y) in noise and q in noise:
                            continue          # 两侧都还是"底图自己抖" → 不算结构差异
                        detail.append("%s(%d,%d)" % (axis, x, y))
            if suffix == "side" and block == RECEIVER:
                outside = [d for d in detail
                           if d[2:] not in {"(%d,%d)" % (x, y) for (x, y) in ARROW}]
                check(not outside,
                      "接收器 _side：结构差异只出现在折线箭嘴区（越界 %d 处%s）"
                      % (len(outside), "" if not outside else "：" + ", ".join(outside[:4])))
            else:
                check(not detail,
                      "%s 的 %s：整图差异全部落在底图自带抖动位置（越界 %d 处%s）"
                      % ("接收器" if block == RECEIVER else "请求器", suffix, len(detail),
                         "" if not detail else "：" + ", ".join(detail[:4])))


    # 旁证：箭嘴的强调色像素必须"上重下轻"——箭嘴 3 行宽 6 / 4 / 2，上部合计必然多于下部。
    side = load(tex_name(RECEIVER, "side", True))[1]
    side_main = accent_pair(RECEIVER, True)[0]
    rows = {y: sum(1 for x in range(16) if side.getpixel((x, y)) == side_main) for y in range(16)}
    top3, bot3 = sum(rows[y] for y in (6, 7, 8)), sum(rows[y] for y in (8, 9, 10))
    check(top3 > bot3,
          "接收器 _side 折线箭嘴强调色像素上重下轻（行 6-8 = %d > 行 8-10 = %d）"
          " ⇒ 箭嘴确实朝模型 UP = 前端面" % (top3, bot3))

    print("\n[I] 物品模型（照既有机器：父指向 _active）")
    for block in BLOCKS:
        path = os.path.join(ITEMS, "%s.json" % block)
        ok = os.path.exists(path) and load_json(path).get("parent") == "rs_create_compat:block/%s_active" % block
        check(ok, "models/item/%s.json 存在且父指向 block/%s_active" % (block, block))

    print("\n[J] 无上一版残留")
    stale = ["network_resource_receiver_%s_%s.png" % (f, s)
             for f in ("bottom", "left", "right", "top") for s in ("inactive", "active")]
    left_assets = [n for n in stale if os.path.exists(os.path.join(OUT, n))]
    left_prev = [n for n in stale if os.path.exists(os.path.join(PREVIEW, n))]
    check(not left_assets, "assets/block/ 无旧命名残留（剩 %d）" % len(left_assets))
    check(not left_prev, "tmp_textures/network_io/ 无旧命名残留（剩 %d）" % len(left_prev))

    print("\n" + "=" * 92)
    for n in notes:
        print("  · %s" % n)
    if failures:
        print("FAILED：%d 项未通过" % len(failures))
        for f in failures:
            print("  - %s" % f)
        return 1
    print("ALL PASS：18 张贴图 + 6 个模型 + 2 个 blockstate + 2 个物品模型，"
          "A~J 全部断言通过，退出码 0")
    return 0


if __name__ == "__main__":
    sys.exit(main())
