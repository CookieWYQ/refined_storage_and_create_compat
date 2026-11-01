#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 100 轮自检：充电过滤「玩家名单」的头像必须是**真实皮肤裁出来的头**。

用户原话（对已交付的 1.2.0-beta.6 的实机反馈）
------------------------------------------------
「你这个什么雷霆诡异的这个就玩家这个白名单，他这个头像什么鬼啊？你直接用玩家的那个嗯头像……
应该就是皮肤裁剪的那个头部不就行了吗？或者说可以直接获得也可以，你这个自己画的太诡异了。」

第 98 轮交付时，玩家行左侧画的是手绘占位头（肤色底 + 头发 + 两只眼睛 + 在线角标绿块），
理由写在当时的 javadoc 里（“离线玩家取不到皮肤”）。本轮把它换成原版
`PlayerFaceRenderer` 的皮肤裁头，并把「画回五官」做成**反例判负**。

断言清单
--------
A. 手绘五官**彻底消失**：三个色块常量（皮肤/头发/眼睛）不存在；`drawPlayerHead` 方法体内
   `fill` 调用 ≤1 次、且没有任何眼睛位置的几何（`x + 3` / `x + 11` 这类）；
   方法签名从 `boolean online` 改成 `@Nullable ResourceLocation texture`。
B. **真实皮肤路径**：导入了 `PlayerSkin` / `DefaultPlayerSkin` / `PlayerInfo` /
   `ClientPacketListener` / `PlayerFaceRenderer`；解析方法里按
   `getPlayerInfo(uuid)` → `getSkin()` → `texture()` 走在线路径；
   绘制走 `PlayerFaceRenderer.draw(..., ROW_ICON_SIZE)`（= 16×16，2 倍放大原版 8×8 头部），
   **不允许自己手搓 blit 的 UV**（那正是容易把 (8,8)/(40,8) 写错的地方）。
C. **离线回退**：`DefaultPlayerSkin.get(id)`（1.21.1 真实方法名）在在线路径**之后**兜底；
   两条都拿不到 ⇒ 只画中性灰底 + 槽框，**不再画眼睛/头发**。
D. **按 UUID 缓存**：`Map<UUID, ResourceLocation> headTextures` + `refreshHeadTextures()` 在
   `rebuildRows()` 里重建；渲染路径（`render` / `drawList`）**不得**出现
   `getPlayerInfo` / `getSkin` / `DefaultPlayerSkin` / `resolveHeadTexture`，只能走一次 map 查询
   `headTextureOf(...)`；另有低频兜底（20 tick）让异步加载好的皮肤能补上，但**不能每 tick 都查**
   （常数不得 ≤1）。
E. **不新增贴图、渲染循环里不做 IO**：本文件不得出现 `new ResourceLocation` /
   `fromNamespaceAndPath` / `withDefaultNamespace`，不得出现 `getResourceManager` /
   `TextureManager` / `InputStream` / `java.io.File`。
F. **在线角标不再压脸**：`drawPlayerHead` 体内不得出现 `COLOR_ONLINE`；
   在线状态改由行尾文字上色（`drawList` 里出现 `COLOR_ONLINE`）——信息不丢。

反例自证（12 条）：把「第 98 轮的手绘实现 / 每帧查皮肤 / 删掉离线兜底 / 每 tick 刷新 /
手搓 blit / 在线角标画回脸上 / 新增贴图 / 渲染里做 IO / 老签名」逐一拼进源码副本，
断言对应判据真的判负 —— 否则本脚本只是「碰巧绿」。

用法：python tools/selfcheck_round99_charger_avatar.py
退出码：0 = 全部通过；1 = 有问题。
"""

from __future__ import annotations

import io
import os
import re
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCREEN = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq",
                      "rs_create_compat", "client", "screen", "ChargerFilterScreen.java")

CHECKS = [0]
COUNTER = [0]
FAILS = []


# ======================================================================================
# 通用文本工具
# ======================================================================================

def read(path):
    with io.open(path, "r", encoding="utf-8", errors="replace") as handle:
        return handle.read()


def check(name, ok, detail=""):
    CHECKS[0] += 1
    if ok:
        print("PASS " + name)
    else:
        FAILS.append(name)
        print("FAIL " + name + (("  <- " + detail) if detail else ""))


def counterexample(name, ok, detail=""):
    COUNTER[0] += 1
    check("反例 " + name, ok, detail)


def strip_comments(text):
    """去掉 // 与 /*...*/ 注释，**保持长度不变**（这样 body_of 的下标仍然对得上）。

    判「代码里有没有调用 X」必须用去注释后的文本：本轮 javadoc 里为了讲清历史，
    会（诚实地）提到 `COLOR_HEAD_SKIN / _HAIR / _EYE`，那不是代码。
    """
    out = []
    i = 0
    n = len(text)
    quote = None
    while i < n:
        two = text[i:i + 2]
        if quote is None and two == "//":
            end = text.find("\n", i)
            end = n if end < 0 else end
            out.append(" " * (end - i))
            i = end
            continue
        if quote is None and two == "/*":
            end = text.find("*/", i + 2)
            end = n if end < 0 else end + 2
            out.append("".join("\n" if c == "\n" else " " for c in text[i:end]))
            i = end
            continue
        char = text[i]
        if quote is None and char in "\"'":
            quote = char
        elif quote is not None and char == "\\":
            out.append(text[i:i + 2])
            i += 2
            continue
        elif quote is not None and char == quote:
            quote = None
        out.append(char)
        i += 1
    return "".join(out)


def body_of(text, needle):
    """返回 `needle` 之后第一对花括号里的内容（找不到 ⇒ 空串）。"""
    start = text.find(needle)
    if start < 0:
        return ""
    brace = text.find("{", start + len(needle))
    if brace < 0:
        return ""
    depth = 0
    for i in range(brace, len(text)):
        char = text[i]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return text[brace + 1:i]
    return ""


def int_const(text, name):
    match = re.search(r"int\s+" + re.escape(name) + r"\s*=\s*([0-9_]+)\s*;", text)
    return None if not match else int(match.group(1).replace("_", ""))


# ======================================================================================
# 判据（返回问题码列表；反例自证就是「改坏的副本里必须出现对应问题码」）
# ======================================================================================

HEAD_METHOD = "private static void drawPlayerHead("
RESOLVE_METHOD = "private static ResourceLocation resolveHeadTexture("
CACHE_METHOD = "private void refreshHeadTextures("
TICK_METHOD = "public void tick("

# 渲染路径上的方法：这些方法体内**绝对不许**出现皮肤查询（每帧都跑）
RENDER_PATH = {
    "render(": "render",
    "private void drawList(": "drawlist",
    "private void renderTooltips(": "tooltips",
    "private void renderRowTooltip(": "rowtooltip",
}
SKIN_QUERY_CALLS = [
    "getPlayerInfo(",
    "getSkin()",
    "DefaultPlayerSkin.",
    "resolveHeadTexture(",
    "DefaultPlayerSkin.get(",
]


def audit(text):
    """text = ChargerFilterScreen.java 全文（未去注释；内部自己去）。返回问题码列表。"""
    problems = []
    code = strip_comments(text)

    # ---------------- 定位关键片段 ----------------
    head_body = body_of(code, HEAD_METHOD)
    resolve_body = body_of(code, RESOLVE_METHOD)
    cache_body = body_of(code, CACHE_METHOD)
    rebuild_body = body_of(code, "private void rebuildRows(")
    tick_body = body_of(code, TICK_METHOD)
    lookup_body = body_of(code, "private ResourceLocation headTextureOf(")
    list_body = body_of(code, "private void drawList(")
    render_body = body_of(code, "public void render(")

    if not head_body:
        problems.append("head-method-missing")
    if not resolve_body:
        problems.append("resolve-method-missing")
    if not cache_body:
        problems.append("cache-method-missing")
    if not tick_body:
        problems.append("tick-method-missing")
    if not lookup_body:
        problems.append("lookup-method-missing")

    # ---------------- A. 手绘五官彻底消失 ----------------
    for dead in ("COLOR_HEAD_SKIN", "COLOR_HEAD_HAIR", "COLOR_HEAD_EYE"):
        if dead in code:
            problems.append("head-handdrawn-const:" + dead)

    # 老签名：drawPlayerHead(..., boolean online) 就是「按在线状态调色块」的入口
    head_decl = code[code.find(HEAD_METHOD):code.find(HEAD_METHOD) + 400]
    if "boolean online" in head_decl:
        problems.append("head-old-signature")
    if "ResourceLocation texture" not in head_decl:
        problems.append("head-signature-not-texture")

    fills = re.findall(r"guiGraphics\.fill\(", head_body)
    if len(fills) > 1:
        problems.append("head-fill-count-too-many(count=%d)" % len(fills))
    for eye_geom in ("x + 3", "x + 11", "x + 5", "x + 13"):
        if eye_geom in head_body:
            problems.append("head-eye-fill-geometry:" + eye_geom)

    # ---------------- B. 真实皮肤路径 ----------------
    for imp in ("net.minecraft.client.resources.PlayerSkin",
                "net.minecraft.client.resources.DefaultPlayerSkin",
                "net.minecraft.client.multiplayer.PlayerInfo",
                "net.minecraft.client.multiplayer.ClientPacketListener",
                "net.minecraft.client.gui.components.PlayerFaceRenderer"):
        if ("import " + imp + ";") not in code:
            problems.append("skin-import-missing:" + imp.rsplit(".", 1)[-1])

    for needle in ("getPlayerInfo(", "getSkin()", ".texture()"):
        if needle not in resolve_body:
            problems.append("online-skin-path-missing:" + needle)
    if "skin.texture() != null" not in resolve_body:
        problems.append("skin-texture-null-not-checked")

    if "PlayerFaceRenderer.draw(" not in head_body:
        problems.append("face-renderer-missing")
    if "ROW_ICON_SIZE" not in head_body:
        problems.append("face-renderer-size-not-16")
    # 自己手搓 blit 的 UV：(8,8)/(40,8) 写错就是「裁到帽子/腿」的经典 bug —— 交给原版
    if "blit(" in head_body:
        problems.append("face-manual-blit")

    # ---------------- C. 离线回退 ----------------
    if "DefaultPlayerSkin.get(" not in resolve_body:
        problems.append("offline-fallback-missing")
    else:
        online_at = resolve_body.find("getPlayerInfo(")
        offline_at = resolve_body.find("DefaultPlayerSkin.get(")
        if online_at < 0 or offline_at < online_at:
            problems.append("offline-fallback-order-wrong")
    # null（连默认皮肤都没有）⇒ 中性灰底 + return，绝不能画脸
    if "texture == null" not in head_body:
        problems.append("head-null-branch-missing")
    if "COLOR_HEAD_NEUTRAL" not in head_body:
        problems.append("head-fallback-not-neutral")
    if "if (texture == null)" in head_body and "return;" not in head_body:
        problems.append("head-null-branch-no-return")

    # ---------------- D. 按 UUID 缓存 ----------------
    if not re.search(
        r"Map<\s*UUID\s*,\s*ResourceLocation\s*>\s+headTextures\s*=\s*new\s+HashMap<>\s*\(\s*\)",
        code):
        problems.append("cache-field-missing")
    if "headTextures.clear()" not in cache_body:
        problems.append("cache-rebuild-not-cleared")
    if "headTextures.put(" not in cache_body or "resolveHeadTexture(" not in cache_body:
        problems.append("cache-rebuild-not-resolving")
    if "refreshHeadTextures();" not in rebuild_body:
        problems.append("cache-not-rebuilt-on-rows")

    # 渲染路径不许查询；只许 headTextureOf 一次 map 查询
    for needle, label in RENDER_PATH.items():
        body = body_of(code, needle)
        if not body:
            continue
        for call in SKIN_QUERY_CALLS:
            if call in body:
                problems.append("render-queries-skin:%s:%s" % (label, call))
    if "headTextureOf(" not in list_body:
        problems.append("render-not-using-cache")
    if "headTextures.get(" not in lookup_body:
        problems.append("lookup-not-map-get")
    for call in SKIN_QUERY_CALLS:
        if call in lookup_body:
            problems.append("lookup-does-query:%s" % call)

    # 低频兜底：不能每 tick 都查（≤1 等于每帧级频率），也不能没有
    refresh_ticks = int_const(code, "HEAD_CACHE_REFRESH_TICKS")
    if refresh_ticks is None:
        problems.append("head-cache-interval-missing")
    elif refresh_ticks <= 1:
        problems.append("head-cache-per-frame")
    elif refresh_ticks > 200:
        problems.append("head-cache-too-lazy")
    if "HEAD_CACHE_REFRESH_TICKS" not in tick_body or "refreshHeadTextures();" not in tick_body:
        problems.append("head-cache-no-lowfreq-refresh")

    # ---------------- E. 不新增贴图 / 渲染循环不做 IO ----------------
    for banned in ("new ResourceLocation(", "ResourceLocation.fromNamespaceAndPath(",
                   "ResourceLocation.withDefaultNamespace("):
        if banned in code:
            problems.append("new-texture-introduced:" + banned)
    for banned in ("getResourceManager(", "TextureManager", "InputStream", "java.io.File"):
        if banned in code:
            problems.append("io-in-render-path:" + banned)

    # ---------------- F. 在线角标不再压脸，改由文字上色 ----------------
    if "COLOR_ONLINE" in head_body:
        problems.append("online-badge-on-face")
    if "COLOR_ONLINE" not in list_body:
        problems.append("online-color-lost")
    if "COLOR_ONLINE" not in code:
        problems.append("online-constant-removed")

    return problems


# ======================================================================================
# 主流程
# ======================================================================================

def main():
    source = read(SCREEN)

    print("=" * 78)
    print("[1] 判据基线（真源码必须 0 问题码）")
    print("=" * 78)
    problems = audit(source)
    if problems:
        for item in problems:
            print("  PROBLEM " + item)
    check("真源码通过全部判据（问题码 0 条）", not problems,
          "问题码: " + ", ".join(problems))

    # ---- 逐条把「断言」讲成人话（便于失败时定位，也证明判据不是空转）----
    code = strip_comments(source)
    head_body = body_of(code, HEAD_METHOD)
    resolve_body = body_of(code, RESOLVE_METHOD)
    cache_body = body_of(code, CACHE_METHOD)
    list_body = body_of(code, "private void drawList(")
    tick_body = body_of(code, TICK_METHOD)

    print("")
    print("[2] A. 手绘五官已彻底删除")
    for dead in ("COLOR_HEAD_SKIN", "COLOR_HEAD_HAIR", "COLOR_HEAD_EYE"):
        check("A1 代码里不再有 %s（只在 javadoc 里作为历史被提及）" % dead,
              dead not in code)
    check("A2 drawPlayerHead 体内 fill 调用 = 1 次（只可能是中性灰底）",
          len(re.findall(r"guiGraphics\.fill\(", head_body)) == 1,
          "实际 %d 次" % len(re.findall(r"guiGraphics\.fill\(", head_body)))
    check("A3 drawPlayerHead 内没有眼睛位置几何（x+3 / x+11 / x+5 / x+13）",
          not any(g in head_body for g in ("x + 3", "x + 11", "x + 5", "x + 13")))
    head_decl = code[code.find(HEAD_METHOD):code.find(HEAD_METHOD) + 400]
    check("A4 签名改为 @Nullable ResourceLocation texture（不再是 boolean online）",
          "boolean online" not in head_decl and "ResourceLocation texture" in head_decl)

    print("")
    print("[3] B. 真实皮肤路径（原版 PlayerFaceRenderer 的 (8,8) 8×8 头部 + (40,8) 帽子层）")
    for imp in ("net.minecraft.client.resources.PlayerSkin",
                "net.minecraft.client.resources.DefaultPlayerSkin",
                "net.minecraft.client.multiplayer.PlayerInfo",
                "net.minecraft.client.multiplayer.ClientPacketListener",
                "net.minecraft.client.gui.components.PlayerFaceRenderer"):
        check("B1 import %s" % imp.rsplit(".", 1)[-1], ("import " + imp + ";") in code)
    for needle in ("getPlayerInfo(", "getSkin()", ".texture()"):
        check("B2 在线路径含 %s" % needle, needle in resolve_body)
    check("B3 在线路径对 skin / texture 做了 null 检查", "skin.texture() != null" in resolve_body)
    check("B4 绘制走 PlayerFaceRenderer.draw（原版裁头，不是自绘）",
          "PlayerFaceRenderer.draw(" in head_body)
    check("B5 绘制尺寸 = ROW_ICON_SIZE（16×16 ⇒ 8×8 皮肤像素放大 2 倍）",
          "ROW_ICON_SIZE" in head_body)
    check("B6 没有自己手搓 blit 的 UV（8/40 这种坐标交给原版）", "blit(" not in head_body)

    print("")
    print("[4] C. 离线回退：DefaultPlayerSkin.get(uuid) 在在线路径之后兜底")
    check("C1 resolveHeadTexture 里有 DefaultPlayerSkin.get(", "DefaultPlayerSkin.get(" in resolve_body)
    online_at = resolve_body.find("getPlayerInfo(")
    offline_at = resolve_body.find("DefaultPlayerSkin.get(")
    check("C2 离线回退在在线路径之后（0 <= online < offline）",
          0 <= online_at < offline_at,
          "online_at=%d offline_at=%d" % (online_at, offline_at))
    check("C3 纹理为 null 时只画中性灰底（COLOR_HEAD_NEUTRAL）",
          "texture == null" in head_body and "COLOR_HEAD_NEUTRAL" in head_body)

    print("")
    print("[5] D. 按 UUID 缓存（渲染循环里没有皮肤查询）")
    check("D1 字段 Map<UUID, ResourceLocation> headTextures = new HashMap<>()",
          re.search(r"Map<\s*UUID\s*,\s*ResourceLocation\s*>\s+headTextures\s*=\s*new\s+HashMap<>\s*\(\s*\)",
                    code) is not None)
    check("D2 refreshHeadTextures 先 clear 再 put(resolveHeadTexture(...))",
          "headTextures.clear()" in cache_body and "headTextures.put(" in cache_body
          and "resolveHeadTexture(" in cache_body)
    check("D3 rebuildRows 末尾调用 refreshHeadTextures()",
          "refreshHeadTextures();" in body_of(code, "private void rebuildRows("))
    render_bodies = {label: body_of(code, needle) for needle, label in RENDER_PATH.items()}
    dirty = ["%s:%s" % (label, call)
             for label, body in render_bodies.items()
             for call in SKIN_QUERY_CALLS if call in body]
    check("D4 render / drawList / tooltip 方法体内没有任何皮肤查询", not dirty, str(dirty))
    check("D5 drawList 通过 headTextureOf(...) 取缓存", "headTextureOf(" in list_body)
    check("D6 headTextureOf 只做一次 headTextures.get(...)",
          "headTextures.get(" in body_of(code, "private ResourceLocation headTextureOf(")
          and not any(call in body_of(code, "private ResourceLocation headTextureOf(")
                      for call in SKIN_QUERY_CALLS))
    refresh_ticks = int_const(code, "HEAD_CACHE_REFRESH_TICKS")
    check("D7 低频兜底间隔 = %s tick（>1 ⇒ 不是每 tick 查，≤200 ⇒ 不会很久不更新）"
          % refresh_ticks,
          refresh_ticks is not None and 1 < refresh_ticks <= 200)
    check("D8 tick() 到点才 refreshHeadTextures()（异步皮肤能补上，但频率可控）",
          "HEAD_CACHE_REFRESH_TICKS" in tick_body and "refreshHeadTextures();" in tick_body)

    print("")
    print("[6] E. 不新增贴图 / 渲染循环不做 IO")
    for banned in ("new ResourceLocation(", "ResourceLocation.fromNamespaceAndPath(",
                   "ResourceLocation.withDefaultNamespace("):
        check("E1 源码里没有 %s" % banned, banned not in code)
    for banned in ("getResourceManager(", "TextureManager", "InputStream", "java.io.File"):
        check("E2 源码里没有 %s" % banned, banned not in code)

    print("")
    print("[7] F. 在线角标不再压在真脸上")
    check("F1 drawPlayerHead 体内没有 COLOR_ONLINE（不再盖脸）", "COLOR_ONLINE" not in head_body)
    check("F2 在线状态改由行尾文字上色（drawList 里用 COLOR_ONLINE）",
          "COLOR_ONLINE" in list_body)
    check("F3 常量 COLOR_ONLINE 仍被使用（不是死代码）", "COLOR_ONLINE" in code)

    print("")
    print("[8] 反例自证：把实现改坏，判据必须判负")
    neutral_fill = "guiGraphics.fill(x, y, x + ROW_ICON_SIZE, y + ROW_ICON_SIZE, COLOR_HEAD_NEUTRAL);"
    neutral_decl = "private static final int COLOR_HEAD_NEUTRAL = 0xFF7A7A7A;"
    face_call = "PlayerFaceRenderer.draw(guiGraphics, texture, x, y, ROW_ICON_SIZE);"
    blend_call = "RenderSystem.enableBlend();"
    old_face = ("guiGraphics.fill(x, y, x + ROW_ICON_SIZE, y + 5, COLOR_HEAD_HAIR);\n"
                "            guiGraphics.fill(x + 3, y + 7, x + 5, y + 9, COLOR_HEAD_EYE);\n"
                "            guiGraphics.fill(x + 11, y + 7, x + 13, y + 9, COLOR_HEAD_EYE);\n"
                "            " + neutral_fill)

    def mutate(*pairs):
        """按 (old, new) 顺序替换；任一锚点缺失 ⇒ None（说明源码结构变了，本脚本要同步）。"""
        text = source
        for old, new in pairs:
            if old not in text:
                return None
            text = text.replace(old, new, 1)
        return text

    cases = []

    # ① 第 98 轮的手绘头像（肤色底 + 头发 + 两眼）
    mutated = mutate(
        (neutral_decl,
         neutral_decl + "\n    private static final int COLOR_HEAD_SKIN = 0xFFB08A62;"
         "\n    private static final int COLOR_HEAD_HAIR = 0xFF3A2D1E;"
         "\n    private static final int COLOR_HEAD_EYE = 0xFF1E1E1E;"),
        (neutral_fill, old_face))
    cases.append(("① 把头像改回手绘（皮肤底 + 头发 + 两只眼睛）", mutated,
                  ["head-handdrawn-const:COLOR_HEAD_HAIR", "head-fill-count-too-many",
                   "head-eye-fill-geometry:x + 3"]))

    # ② 老签名：按 boolean online 决定画法
    mutated = mutate((("private static void drawPlayerHead(final GuiGraphics guiGraphics, "
                       "final int x, final int y,"),
                      "private static void drawPlayerHead(final GuiGraphics guiGraphics, "
                      "final int x, final int y, final boolean online) {"))
    cases.append(("② 把签名改回 boolean online（按在线状态画色块）", mutated,
                  ["head-old-signature"]))

    # ③ 渲染里每帧查皮肤（去掉缓存）
    mutated = mutate(("headTextureOf(data.playerId())", "resolveHeadTexture(data.playerId())"))
    cases.append(("③ drawList 里直接 resolveHeadTexture（每帧查连接 / 皮肤管理器）", mutated,
                  ["render-queries-skin:drawlist:resolveHeadTexture(",
                   "render-not-using-cache"]))

    # ④ 删掉离线回退（离线玩家只能灰底，本该走默认皮肤）
    mutated = mutate((("final PlayerSkin fallback = DefaultPlayerSkin.get(id);\n"
                       "        return fallback == null ? null : fallback.texture();"),
                      "return null;"))
    cases.append(("④ 删掉 DefaultPlayerSkin 离线回退", mutated, ["offline-fallback-missing"]))

    # ⑤ 离线优先（顺序反了：缓存/默认皮肤盖过在线真皮肤）
    mutated = mutate((("final ClientPacketListener connection = minecraft.getConnection();"),
                      "final PlayerSkin early = DefaultPlayerSkin.get(id);\n"
                      "        if (early != null && early.texture() != null) {\n"
                      "            return early.texture();\n"
                      "        }\n"
                      "        final ClientPacketListener connection = minecraft.getConnection();"))
    cases.append(("⑤ 默认皮肤抢在在线皮肤前面（顺序反）", mutated,
                  ["offline-fallback-order-wrong"]))

    # ⑥ 每 tick 都刷新（等于每帧级查询）
    mutated = mutate(("HEAD_CACHE_REFRESH_TICKS = 20", "HEAD_CACHE_REFRESH_TICKS = 1"))
    cases.append(("⑥ 兜底间隔改成 1 tick（频率退化成每 tick 查）", mutated,
                  ["head-cache-per-frame"]))

    # ⑦ 行重建时不再刷新快照（切页签后头像全是灰的）
    mutated = mutate(("        this.rows = List.copyOf(next);\n"
                      "        refreshHeadTextures();\n",
                      "        this.rows = List.copyOf(next);\n"))
    cases.append(("⑦ rebuildRows 不再刷新纹理快照", mutated, ["cache-not-rebuilt-on-rows"]))

    # ⑧ 手搓 blit 的 UV（容易写成 (0,0)/(8,8) 裁到帽子或腿）
    mutated = mutate((face_call,
                      "guiGraphics.blit(texture, x, y, 16, 16, 8.0F, 8.0F, 8, 8, 64, 64);"))
    cases.append(("⑧ 不用 PlayerFaceRenderer，自己手搓 blit 的 UV", mutated,
                  ["face-renderer-missing", "face-manual-blit"]))

    # ⑨ 在线角标画回脸上（会盖住真脸的眼睛）
    mutated = mutate((blend_call, blend_call + "\n        guiGraphics.fill(x, y, x + 3, y + 3, COLOR_ONLINE);"))
    cases.append(("⑨ 在线绿角标画回头像左上角（压脸）", mutated, ["online-badge-on-face"]))

    # ⑩ 新增贴图（用户明确要求用现成皮肤，不许再造图）
    mutated = mutate((face_call,
                      'final ResourceLocation custom = '
                      'ResourceLocation.fromNamespaceAndPath("rs_create_compat", '
                      '"textures/gui/head.png");\n        ' + face_call.replace("texture", "custom")))
    cases.append(("⑩ 新增一张自绘头像贴图", mutated, ["new-texture-introduced:"
                                                  "ResourceLocation.fromNamespaceAndPath("]))

    # ⑪ 渲染路径里做 IO（读资源管理器）
    mutated = mutate((neutral_fill,
                      neutral_fill + "\n            Minecraft.getInstance().getResourceManager();"))
    cases.append(("⑪ 渲染路径里读资源管理器（IO）", mutated,
                  ["io-in-render-path:getResourceManager("]))

    # ⑫ 纹理为 null 时改画一块彩色（而不是中性灰），等于又回到自绘
    mutated = mutate((neutral_fill, neutral_fill.replace("COLOR_HEAD_NEUTRAL", "COLOR_TEXT")))
    cases.append(("⑫ 拿不到纹理时改画彩色块（不是中性灰）", mutated,
                  ["head-fallback-not-neutral"]))

    for name, mutated, expected in cases:
        if mutated is None:
            counterexample(name, False, "锚点缺失：源码结构已变，本脚本需要同步（不是实现的问题）")
            continue
        found = audit(mutated)
        # 问题码可能带参数（例如 head-fill-count-too-many(count=4)），按前缀匹配
        missing = [code_name for code_name in expected
                   if not any(item == code_name or item.startswith(code_name)
                              for item in found)]
        counterexample(name, not missing,
                       "期望问题码 %s，实际 %s" % (expected, found))

    # 基准必须仍然干净（防止「反例把基线也污染」）
    check("反例自证后，真源码仍然 0 问题码", not audit(source))

    print("")
    print("=" * 78)
    print("断言总数 %d（含反例自证 %d 条）" % (CHECKS[0], COUNTER[0]))
    if FAILS:
        print("SELFCHECK FAILED: " + ", ".join(FAILS))
        return 1
    print("SELFCHECK OK (%d checks, %d counterexamples)" % (CHECKS[0], COUNTER[0]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
