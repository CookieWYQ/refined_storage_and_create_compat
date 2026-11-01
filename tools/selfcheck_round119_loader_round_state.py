#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 119 轮自检：蓝图装填器的「本轮蓝图身份落盘」(M2) 与「同 tick 集群 BFS 缓存」(M4)。

背景
----
* **M2（读档白跑一轮）** ``SchematicLoaderBlockEntity`` 的 ``attachRoundBlueprint``（本轮绑定的
  蓝图身份）只存在内存里：``saveAdditional`` 只写了 ``AttachRoundComplete``，``loadAdditional``
  也只读回它。于是读档 / 重载区块后 ``attachRoundBlueprint = EMPTY``，而加农炮槽里还是同一张
  蓝图 ⇒ ``doLoaderWork`` 判定「蓝图变了」⇒ ``recycleBlueprintMaterialsToNetwork``（已收集材料
  全部回流网络）+ ``resetRoundCollection`` ⇒ 再整份重拉。
* **M4（每 tick 多次 BFS）** ``collectCluster()`` 每次调用做一次六向 BFS，一次 server tick 里
  会被调用十几次（补料 / 计数 / 自动打印 / 集群判定）。同一 tick 内拓扑不会变，重复 BFS 是纯浪费。

四条腿（与第 116～119 轮同一套路）
----------------------------------
① 源码锚点：身份落盘 / 读回 / 分支顺序 / 缓存字段 / 失效点 / 不可变返回都在，且没有回退成坏写法；
② 行为模型：M2「读档 vs 换图」两条路径（+ 旧档缺 key、非法 key、空槽），
   M4「同 tick 只 BFS 一次 / 跨 tick 不复用 / 拓扑变化失效 / 返回副本」；
③ 反例自证：把坏写法拼进源码副本 / 模型，断言判据真的判负；
④ 对照：M2 的「身份缓存不得回填库存」这条不能靠行为模型证明，单独做静态锚点。

用法：python tools/selfcheck_round119_loader_round_state.py
退出码：0 = 全部通过。
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
BE = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq",
                  "rs_create_compat", "block", "entity", "SchematicLoaderBlockEntity.java")

CHECKS = [0]
COUNTER = [0]
FAILS = []

SAVE_KEY = 'tag.put("AttachRoundBlueprint", attachRoundBlueprint.save(registries));'
LOAD_KEY = 'ItemStack.parseOptional(registries, tag.getCompound("AttachRoundBlueprint"))'
CACHE_HIT = "if (clusterCache != null && now == clusterCacheTick)"
COPY_OF = "clusterCache = java.util.List.copyOf(finalizeCluster(all));"
BFS_LOOP = "while (!queue.isEmpty())"


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
    """去掉 // 与 /* */ 注释（字符串里的 //、/* 不误伤），用于「代码事实」类断言。"""
    out = []
    i = 0
    n = len(text)
    quote = None
    while i < n:
        two = text[i:i + 2]
        if quote is None and two == "//":
            end = text.find("\n", i)
            if end < 0:
                break
            i = end
            continue
        if quote is None and two == "/*":
            end = text.find("*/", i + 2)
            i = n if end < 0 else end + 2
            continue
        ch = text[i]
        if quote is None and ch in "\"'":
            quote = ch
        elif quote == ch and text[i - 1] != "\\":
            quote = None
        out.append(ch)
        i += 1
    return "".join(out)


def method_body(code, signature):
    """按大括号配对取出方法体（去注释后的源码）；找不到返回空串。"""
    start = code.find(signature)
    if start < 0:
        return ""
    brace = code.find("{", start)
    if brace < 0:
        return ""
    depth = 0
    for i in range(brace, len(code)):
        if code[i] == "{":
            depth += 1
        elif code[i] == "}":
            depth -= 1
            if depth == 0:
                return code[brace + 1:i]
    return code[brace + 1:]


# ======================================================================================
# ① 源码锚点（谓词写成函数：反例副本复用同一判据）
# ======================================================================================

def m2_save_ok(be):
    pos = be.find(SAVE_KEY)
    if pos < 0:
        return False
    return "if (!attachRoundBlueprint.isEmpty())" in be[max(0, pos - 160):pos]


def m2_load_ok(be):
    body = method_body(be, "public void loadAdditional(")
    return (LOAD_KEY in body
            and "attachRoundIdentityUnknown = true;" in body
            and "if (isValidBlueprintStack(savedRoundBlueprint))" in body)


def m2_work_region(be):
    work = method_body(be, "public void doLoaderWork(")
    # 用代码锚点（注释在 strip_comments 后已消失）定位「本轮蓝图身份」判断的起点
    start = work.find("final ItemStack currentCannonBlueprint = cannon.inventory.getStackInSlot(0);")
    return work[start:] if start >= 0 else ""


def m2_branch_ok(be):
    region = m2_work_region(be)
    if not region:
        return False
    empty = region.find("if (currentCannonBlueprint.isEmpty())")
    adopt = region.find("} else if (attachRoundIdentityUnknown) {")
    same = region.find("} else if (!ItemStack.isSameItemSameComponents(")
    recycle = region.find("recycleBlueprintMaterialsToNetwork(network);")
    if not (0 <= empty < adopt < same < recycle):
        return False
    adopt_seg = region[adopt:same]
    # 接管分支：只写身份缓存（copy），不回流、不复位
    return ("attachRoundBlueprint = currentCannonBlueprint.copy();" in adopt_seg
            and "recycleBlueprintMaterialsToNetwork" not in adopt_seg
            and "resetRoundCollection()" not in adopt_seg)


def m2_changed_ok(be):
    """真换图分支必须仍是旧行为：回流 + reset + 更新身份。"""
    region = m2_work_region(be)
    idx = region.find("} else if (!ItemStack.isSameItemSameComponents(")
    if idx < 0:
        return False
    seg = region[idx:idx + 600]
    return ("recycleBlueprintMaterialsToNetwork(network);" in seg
            and "resetRoundCollection();" in seg
            and "attachRoundBlueprint = currentCannonBlueprint.copy();" in seg)


def m2_no_writeback_ok(be):
    """身份缓存不得进入任何写库存 / 写网络的调用参数。"""
    tokens = ("insertIntoInventory(", "insertIntoCluster(", "setStackInSlot(", "insertItem(",
              "pushItemsToNetwork(", "returnToNetwork(", "extractItem(", "addItem(")
    for line in be.splitlines():
        if "attachRoundBlueprint" in line and any(token in line for token in tokens):
            return False
    return True


def m4_cache_ok(be):
    body = method_body(be, "public java.util.List<SchematicLoaderBlockEntity> collectCluster()")
    hit = body.find(CACHE_HIT)
    loop = body.find(BFS_LOOP)
    store = body.find(COPY_OF)
    level_null = body.find("return java.util.List.of(this);")
    return (0 <= level_null < hit < loop < store
            and "final long now = level.getGameTime();" in body
            and body.count(BFS_LOOP) == 1)


def m4_immutable_ok(be):
    body = method_body(be, "public java.util.List<SchematicLoaderBlockEntity> collectCluster()")
    return COPY_OF in body and "return finalizeCluster(all);" not in body


def m4_invalidate_ok(be):
    helper = method_body(be, "private void invalidateClusterCaches()")
    for field in ("clusterCache = null;", "clusterCacheTick = Long.MIN_VALUE;",
                  "guiOrderCache = null;", "guiOrderCacheTick = Long.MIN_VALUE;"):
        if field not in helper:
            return False
    for signature in ("public void rscc$clusterChanged(", "public void setRemoved()",
                      "public void loadAdditional("):
        if "invalidateClusterCaches();" not in method_body(be, signature):
            return False
    return True


def m4_static_field_ok(be):
    """缓存必须是实例字段（每台各自缓存），不能是 static（会跨机器/跨世界串味）。"""
    match = re.search(r"\n\s*(private|protected|public)\s+[^\n]*clusterCache\s*;", be)
    if match is None:
        return False
    return "static" not in match.group(0)


def group_anchors():
    print("\n=== ① 源码锚点 ===")
    raw = read(BE)
    be = strip_comments(raw)

    # ---- M2：身份落盘 / 读回 / 分支 ----
    check("M2-1 saveAdditional 用新 key 落盘「本轮蓝图身份」完整 ItemStack（非空才写）", m2_save_ok(be))
    check("M2-2 loadAdditional 用 ItemStack.parseOptional 安全读回（非法 id 不抛异常）", m2_load_ok(be))
    check("M2-3 doLoaderWork 分支顺序：空槽复位 → 身份未知接管 → 同图跳过 → 换图回流", m2_branch_ok(be))
    check("M2-4 真换图分支仍保持旧行为（回流 + resetRoundCollection + 身份更新）", m2_changed_ok(be))
    check("M2-5 身份缓存只做比较：任何写库存/写网络调用都不使用 attachRoundBlueprint", m2_no_writeback_ok(be))
    check("M2-6 AttachRoundComplete 仍然落盘/读回（沿用存档判据）",
          'tag.putBoolean("AttachRoundComplete", attachRoundComplete);' in be
          and 'attachRoundComplete = tag.getBoolean("AttachRoundComplete");' in be)
    check("M2-7 「读档的同一张蓝图不得回流」这一判据确实用 isSameItemSameComponents",
          "ItemStack.isSameItemSameComponents(currentCannonBlueprint, attachRoundBlueprint)" in be)
    check("M2-8 旧档缺 key 时的兜底标志存在且只在 loadAdditional 里置 true",
          be.count("attachRoundIdentityUnknown = true;") == 1)

    # ---- M4：BFS 缓存 ----
    check("M4-1 collectCluster 命中条件带 gameTime（同 tick 复用、跨 tick 不复用）", m4_cache_ok(be))
    check("M4-2 缓存内容是 List.copyOf 的不可变副本（调用方改不到缓存内部）", m4_immutable_ok(be))
    check("M4-3 拓扑变化 / 读档 / 移除三处都会作废两级缓存", m4_invalidate_ok(be))
    check("M4-4 缓存是实例字段而非 static（每台机器各自缓存）", m4_static_field_ok(be))
    check("M4-5 只有一处真正的六向 BFS 循环（另一次调用命中缓存）", be.count(BFS_LOOP) == 1,
          "BFS 循环 %d 处" % be.count(BFS_LOOP))

    gui_body = method_body(be, "public java.util.List<SchematicLoaderBlockEntity> getClusterInGuiOrder()")
    gui_doc_start = raw.find("public java.util.List<SchematicLoaderBlockEntity> getClusterInGuiOrder()")
    gui_doc = raw[max(0, gui_doc_start - 1800):gui_doc_start]
    check("M4-6 getClusterInGuiOrder 注释写明与 collectCluster 的缓存关系（谁复用谁）",
          "与 {@link #collectCluster()} 的缓存关系" in gui_doc
          and "computeClusterInGuiOrder" in gui_doc,
          "注释缺少缓存关系说明")
    check("M4-7 getClusterInGuiOrder 仍用自己的同 tick 缓存（下游复用 collectCluster）",
          "if (guiOrderCache == null || now != guiOrderCacheTick)" in gui_body
          and "computeClusterInGuiOrder()" in gui_body)
    compute_body = method_body(be, "private java.util.List<SchematicLoaderBlockEntity> computeClusterInGuiOrder()")
    check("M4-8 computeClusterInGuiOrder 从 collectCluster 拷贝一份再排序/去重（不改缓存）",
          "new java.util.ArrayList<>(collectCluster())" in compute_body)


# ======================================================================================
# ② 行为模型
# ======================================================================================

class RoundModel:
    """doLoaderWork 里「本轮蓝图身份」分支的最小模型。

    身份 = (物品 id, components 元组)；``None`` = 空槽。
    """

    def __init__(self, identity=None, complete=False, unknown=False):
        self.identity = identity
        self.complete = complete
        self.unknown = unknown
        self.recycled = 0
        self.resets = 0
        self.actions = []

    def save(self, persist_identity=True):
        return {
            "identity": self.identity if persist_identity else None,
            "complete": self.complete,
        }

    @classmethod
    def load(cls, tag, identity_in_tag=True):
        if identity_in_tag and tag["identity"] is not None:
            return cls(tag["identity"], tag["complete"], unknown=False)
        # 旧档缺 key / key 非法：身份未知，但不代表「换图」
        return cls(None, tag["complete"], unknown=True)

    def tick(self, current):
        if current is None:
            self.identity = None
            self.unknown = False
            self.complete = False
            self.resets += 1
            self.actions.append("empty-reset")
            return "empty-reset"
        if self.unknown:
            self.identity = current  # 接管：不回流、不复位
            self.unknown = False
            self.actions.append("adopt")
            return "adopt"
        if current != self.identity:
            self.recycled += 1
            self.identity = current
            self.complete = False
            self.resets += 1
            self.actions.append("changed")
            return "changed"
        self.actions.append("same")
        return "same"


BLUEPRINT_A = ("create:schematic", (("create:schematic_file", "house.nbt"),))
BLUEPRINT_A_COPY = ("create:schematic", (("create:schematic_file", "house.nbt"),))
BLUEPRINT_A_OTHER_COMPONENT = ("create:schematic", (("create:schematic_file", "house.nbt"),
                                                   ("create:schematic_anchor", (1, 2, 3))))
BLUEPRINT_B = ("create:schematic", (("create:schematic_file", "tower.nbt"),))


class ClusterCacheModel:
    """collectCluster 的同 tick 缓存模型（tick_keyed=False 用来模拟坏写法）。"""

    def __init__(self, members, tick_keyed=True):
        self.members = tuple(members)
        self.tick = 0
        self.cache = None
        self.cache_tick = None
        self.bfs_runs = 0
        self.tick_keyed = tick_keyed

    def invalidate(self):
        self.cache = None
        self.cache_tick = None

    def collect(self):
        if self.cache is not None and (not self.tick_keyed or self.tick == self.cache_tick):
            return self.cache
        self.bfs_runs += 1
        self.cache = self.members  # tuple = 不可变
        self.cache_tick = self.tick
        return self.cache


class GuiOrderModel:
    """getClusterInGuiOrder（下游）→ collectCluster（上游）的缓存模型。"""

    def __init__(self):
        self.cluster = ClusterCacheModel(["self", "a", "b"])
        self.gui_tick = None
        self.gui_cache = None
        self.gui_computes = 0

    def get(self):
        if self.gui_cache is None or self.gui_tick != self.cluster.tick:
            self.gui_computes += 1
            self.gui_cache = tuple(self.cluster.collect())
            self.gui_tick = self.cluster.tick
        return self.gui_cache

    def invalidate(self):
        self.gui_cache = None
        self.gui_tick = None
        self.cluster.invalidate()


def group_models():
    print("\n=== ② 行为模型 ===")

    # ---- M2：读档后仍是同一张蓝图 ----
    saved = RoundModel(identity=BLUEPRINT_A, complete=True)
    tag = saved.save()
    reloaded = RoundModel.load(tag)
    for _ in range(20):
        reloaded.tick(BLUEPRINT_A_COPY)
    check("M2-M1 读档后 20 tick 都是同一张蓝图 → 回流 0 次、复位 0 次、attachRoundComplete 保持 true",
          reloaded.recycled == 0 and reloaded.resets == 0 and reloaded.complete is True
          and set(reloaded.actions) == {"same"},
          "recycled=%d resets=%d actions=%r" % (reloaded.recycled, reloaded.resets, reloaded.actions[:3]))

    # 组件不同 = 不同身份（isSameItemSameComponents 的口径）
    compare = RoundModel(identity=BLUEPRINT_A, complete=True)
    compare.tick(BLUEPRINT_A_OTHER_COMPONENT)
    check("M2-M2 同名但 components 不同 → 视为换图（isSameItemSameComponents 口径，不是只看物品 id）",
          compare.recycled == 1 and compare.resets == 1)

    # ---- M2：读档后真的换了蓝图 ----
    changed = RoundModel.load(tag)
    changed.tick(BLUEPRINT_B)
    changed_after = changed.tick(BLUEPRINT_B)
    check("M2-M3 读档后换图：回流 1 次 + 复位 1 次（与旧行为一致），之后同一张不再回流",
          changed.recycled == 1 and changed.resets == 1 and changed_after == "same",
          "recycled=%d resets=%d" % (changed.recycled, changed.resets))

    # ---- M2：旧档没有 key / key 非法 ----
    legacy = RoundModel.load(tag, identity_in_tag=False)
    legacy_actions = [legacy.tick(BLUEPRINT_A) for _ in range(5)]
    check("M2-M4 旧档缺 key：首个 tick 以当前蓝图接管（不回流、不复位），complete 沿用存档",
          legacy.recycled == 0 and legacy.resets == 0 and legacy.complete is True
          and legacy_actions[0] == "adopt" and set(legacy_actions[1:]) == {"same"},
          "actions=%r" % (legacy_actions,))

    legacy_unfinished = RoundModel.load({"identity": BLUEPRINT_A, "complete": False},
                                        identity_in_tag=False)
    legacy_unfinished.tick(BLUEPRINT_A)
    check("M2-M5 旧档缺 key 且 complete=false：接管后仍可继续本轮收集（complete 不被误复位）",
          legacy_unfinished.recycled == 0 and legacy_unfinished.resets == 0
          and legacy_unfinished.complete is False and legacy_unfinished.unknown is False)

    # ---- M2：加农炮槽空 / 之后重新放入 ----
    empty = RoundModel.load(tag)
    empty.tick(None)
    check("M2-M6 读档时加农炮槽为空：只复位、不回流（旧行为一致）",
          empty.recycled == 0 and empty.resets == 1 and empty.identity is None)
    empty.tick(BLUEPRINT_A_COPY)
    check("M2-M7 空槽之后再放入蓝图 = 真正的新一轮：回流 + reset（行为与现在一致）",
          empty.recycled == 1 and empty.resets == 2)

    # ---- M2：身份不得回填库存 ----
    inventory = ["collected-material"]
    no_writeback = RoundModel.load(tag)
    no_writeback.tick(BLUEPRINT_A)
    check("M2-M8 身份比较不触碰库存（不复制/不移动任何物品）",
          inventory == ["collected-material"])

    # 坏写法（旧实现）：读档后身份恒为空，且没有「身份未知」接管分支 ⇒ 同一张蓝图也被判成换图
    old_reload = RoundModel(identity=None, complete=True, unknown=False)
    old_reload.tick(BLUEPRINT_A)
    counterexample("M2-C1 旧实现模型（身份不落盘且无接管分支）：读档后同一张蓝图被误判换图 → 回流 1 次"
                   "（本修复要消灭的现象）",
                   old_reload.recycled == 1 and old_reload.resets == 1)

    # ---- M4：同 tick 只 BFS 一次 ----
    cache = ClusterCacheModel(["self", "a", "b"])
    for _ in range(12):
        cache.collect()
    check("M4-M1 同一 tick 调用 12 次 → BFS 只执行 1 次", cache.bfs_runs == 1,
          "bfs=%d" % cache.bfs_runs)

    cache.tick += 1
    for _ in range(5):
        cache.collect()
    check("M4-M2 跨 tick 不复用 → 下一 tick 第一次调用重新 BFS（累计 2 次）", cache.bfs_runs == 2,
          "bfs=%d" % cache.bfs_runs)

    cache.invalidate()  # rscc$clusterChanged
    cache.collect()
    check("M4-M3 拓扑变化（rscc$clusterChanged）作废缓存 → 同 tick 内再调用会重新 BFS（累计 3 次）",
          cache.bfs_runs == 3, "bfs=%d" % cache.bfs_runs)

    for _ in range(3):
        cache.collect()
    check("M4-M4 作废后的重算结果又会在本 tick 内被复用（累计仍 3 次）", cache.bfs_runs == 3)

    # 返回副本：tuple 不可 append / 不可改元素
    members = cache.collect()
    immutable = False
    try:
        members.append("intruder")  # type: ignore[attr-defined]
    except AttributeError:
        immutable = True
    check("M4-M5 返回的列表不可变：调用方 append 会失败（改不到缓存内部）", immutable)

    # ---- M4：下游复用上游（GUI 顺序） ----
    gui = GuiOrderModel()
    for _ in range(12):
        gui.get()
    check("M4-M6 同一 tick 12 次 getClusterInGuiOrder → BFS 1 次、GUI 顺序只算 1 次",
          gui.cluster.bfs_runs == 1 and gui.gui_computes == 1,
          "bfs=%d gui=%d" % (gui.cluster.bfs_runs, gui.gui_computes))
    gui.invalidate()
    gui.get()
    check("M4-M7 拓扑变化后 GUI 缓存与 BFS 缓存一起失效（各重算 1 次）",
          gui.cluster.bfs_runs == 2 and gui.gui_computes == 2)

    # 坏写法：缓存不带 gameTime ⇒ 跨 tick 也不重算（会把区块加载边界的错误拓扑固化）
    broken = ClusterCacheModel(["self"], tick_keyed=False)
    for _ in range(5):
        broken.tick += 1
        for _ in range(3):
            broken.collect()
    counterexample("M4-C1 坏写法模型（缓存不看 gameTime）：5 个 tick 只 BFS 1 次（本修复必须避免）",
                   broken.bfs_runs == 1)


# ======================================================================================
# ③ 反例自证（源码副本）
# ======================================================================================

def group_counterexamples():
    print("\n=== ③ 反例自证（源码副本） ===")
    raw = read(BE)

    broken_save = raw.replace(SAVE_KEY, 'tag.putBoolean("AttachRoundBlueprint", false);', 1)
    counterexample("M2-C2 源码副本：不写身份 key 后，M2-1 判负",
                   not m2_save_ok(strip_comments(broken_save)))

    broken_load = raw.replace(LOAD_KEY, "ItemStack.EMPTY", 1)
    counterexample("M2-C3 源码副本：读回改为 EMPTY 后，M2-2 判负",
                   not m2_load_ok(strip_comments(broken_load)))

    broken_branch = raw.replace("} else if (attachRoundIdentityUnknown) {",
                                "} else if (false) {", 1)
    counterexample("M2-C4 源码副本：删掉「身份未知接管」分支后，M2-3 判负（旧档读档会误回流）",
                   not m2_branch_ok(strip_comments(broken_branch)))

    broken_changed = raw.replace(
        "            recycleBlueprintMaterialsToNetwork(network);\n"
        "            attachRoundBlueprint = currentCannonBlueprint.copy();",
        "            attachRoundBlueprint = currentCannonBlueprint.copy();", 1)
    counterexample("M2-C5 源码副本：真换图分支不再回流后，M2-4 判负",
                   not m2_changed_ok(strip_comments(broken_changed)))

    broken_writeback = raw.replace(
        "            attachRoundIdentityUnknown = false;\n            setChanged();",
        "            attachRoundIdentityUnknown = false;\n"
        "            inventory.setStackInSlot(0, attachRoundBlueprint);\n"
        "            setChanged();", 1)
    counterexample("M2-C6 源码副本：把身份缓存写回库存后，M2-5 判负（复制物品）",
                   not m2_no_writeback_ok(strip_comments(broken_writeback)))

    broken_copy = raw.replace(COPY_OF, "clusterCache = finalizeCluster(all);", 1)
    counterexample("M4-C2 源码副本：缓存改存可变列表后，M4-2 判负",
                   not m4_immutable_ok(strip_comments(broken_copy)))

    broken_tick = raw.replace(CACHE_HIT, "if (clusterCache != null)", 1)
    counterexample("M4-C3 源码副本：命中条件去掉 gameTime 后，M4-1 判负（跨 tick 复用）",
                   not m4_cache_ok(strip_comments(broken_tick)))

    broken_invalidate = raw.replace(
        "        invalidateClusterCaches();\n        refreshClusterHasAdvanced();",
        "        refreshClusterHasAdvanced();", 1)
    counterexample("M4-C4 源码副本：rscc$clusterChanged 不再作废缓存后，M4-3 判负",
                   not m4_invalidate_ok(strip_comments(broken_invalidate)))


def group_print_sentinels():
    """④ 「打印完成」哨兵落盘（本轮顺手修：读档后完成横幅会重复播一遍）。

    事实链：``lastCannonEmptySchematicCount``（上次看到的输出槽空白蓝图数量）与
    ``lastPrintingDone`` 以前都**不落盘**。读档后两者归零，而输出槽里可能还躺着上次没回收的
    空白蓝图 ⇒ 第一个 work tick 就 ``emptySchematicCount > lastCannonEmptySchematicCount``
    ⇒ 判成「打印刚完成」⇒ 完成横幅（以及成就触发点）再播一遍。
    """
    print("\n=== ④ 打印完成哨兵落盘（读档后不重复播报） ===")
    raw = read(BE)
    code = strip_comments(raw)

    save_body = method_body(code, "public void saveAdditional(")
    load_body = method_body(code, "public void loadAdditional(")
    work_body = method_body(code, "public void doLoaderWork(")

    check("④-1 saveAdditional 写 LastEmptySchematicCount",
          'tag.putInt("LastEmptySchematicCount", lastCannonEmptySchematicCount)' in save_body)
    check("④-2 saveAdditional 写 LastPrintingDone",
          'tag.putBoolean("LastPrintingDone", lastPrintingDone)' in save_body)
    check("④-3 loadAdditional 读 LastEmptySchematicCount",
          'tag.getInt("LastEmptySchematicCount")' in load_body)
    check("④-4 loadAdditional 读 LastPrintingDone",
          'tag.getBoolean("LastPrintingDone")' in load_body)
    check("④-5 loadAdditional 用 key 是否存来决定「基线是否可信」"
          "（旧档没有 key ⇒ 不能直接相信 0）",
          'printBaselinePrimed = tag.contains("LastEmptySchematicCount")' in load_body)
    check("④-6 doLoaderWork 有「首 tick 重建基线」分支，且重建那一 tick 不判刚完成",
          "printBaselineKnown" in work_body and "printBaselinePrimed = true" in work_body
          and "emptySchematicCount > lastCannonEmptySchematicCount" in work_body)
    check("④-7 「刚完成」判据被基线可信标志门控（printBaselineKnown && ...）",
          "printBaselineKnown && emptySchematicCount > lastCannonEmptySchematicCount" in work_body)
    check("④-8 哨兵字段有「为什么必须落盘」的注释（防止后人删掉）",
          "第 119 轮修" in raw and "重复" in raw)

    # ---- 行为模型：读档 / 旧档 / 正常打印三种路径 ----
    def simulate(primed_initial, saved_count, ticks, saved_done=False):
        """返回 (横幅播报次数, 最终 lastPrintingDone)。ticks = 每 tick 输出槽里的空白蓝图数量。"""
        state = {"primed": primed_initial, "count": saved_count if primed_initial else 0,
                 "done": saved_done if primed_initial else False, "banners": 0}
        for observed in ticks:
            known = state["primed"]
            if not state["primed"]:
                state["primed"] = True
                state["count"] = observed
            just_finished = known and observed > state["count"]
            state["count"] = observed
            if just_finished and not state["done"]:
                state["banners"] += 1
            state["done"] = just_finished
        return state["banners"], state["done"]

    # 新档：打印完成后输出槽 1 张，横幅播一次；存档（count=1, done=True）后重进不再播
    banners, done = simulate(False, 0, [0, 1])
    check("④-9 新档正常打印：横幅播 1 次", banners == 1, "banners=%d" % banners)
    check("④-10 新档正常打印后结论为「已完成」（供落盘）", done is True)
    banners_reload, _ = simulate(True, 1, [1, 1, 1], saved_done=True)
    check("④-11 新档读档（count=1, done=true）重进：横幅 0 次（本修复要保证的）",
          banners_reload == 0, "banners=%d" % banners_reload)
    banners_old_save, _ = simulate(False, 0, [1, 1])
    check("④-12 旧档读档（无 key，输出槽残留 1 张）：横幅 0 次（首 tick 重建基线）",
          banners_old_save == 0, "banners=%d" % banners_old_save)
    banners_second_print, _ = simulate(True, 1, [1, 2], saved_done=True)
    check("④-13 读档后再打印一张：横幅 1 次（修复不能把正常播报也吞掉）",
          banners_second_print == 1, "banners=%d" % banners_second_print)

    # ---- 反例自证：把修复拆掉，判据必须判负 ----
    broken_save = raw.replace('        tag.putInt("LastEmptySchematicCount", lastCannonEmptySchematicCount);\n', "", 1)
    counterexample("④-C1 源码副本：不落盘哨兵后，④-1 判负",
                   'tag.putInt("LastEmptySchematicCount", lastCannonEmptySchematicCount)' not in
                   method_body(strip_comments(broken_save), "public void saveAdditional("))

    broken_load = raw.replace('printBaselinePrimed = tag.contains("LastEmptySchematicCount");',
                             "printBaselinePrimed = true;", 1)
    counterexample("④-C2 源码副本：旧档也当基线可信（primed 恒 true）后，④-5 判负",
                   'tag.contains("LastEmptySchematicCount")' not in
                   method_body(strip_comments(broken_load), "public void loadAdditional("))

    broken_work = raw.replace(
        "final boolean printJustFinished = printBaselineKnown && emptySchematicCount > lastCannonEmptySchematicCount;",
        "final boolean printJustFinished = emptySchematicCount > lastCannonEmptySchematicCount;", 1)
    counterexample("④-C3 源码副本：去掉基线门控后，④-7 判负（旧档读档会补播）",
                   "printBaselineKnown && emptySchematicCount >" not in
                   method_body(strip_comments(broken_work), "private void doLoaderWork("))

    # 模型层面的反例：不落盘的旧实现在「读档时输出槽有残留」时必然补播一次
    def simulate_old(ticks, read_from_zero=True):
        count = 0
        done = False
        banners = 0
        for observed in ticks:
            just_finished = observed > count
            count = observed
            if just_finished and not done:
                banners += 1
            done = just_finished
        return banners

    counterexample("④-C4 旧实现模型：读档后输出槽残留 1 张时横幅补播 1 次（本修复要消灭的现象）",
                   simulate_old([1, 1]) == 1)

    # ---- ④b：GUI 顺序缓存同样不可变（A 复核时的顺手发现） ----
    print("\n=== ④b GUI 顺序缓存写入时即不可变 ===")
    gui_body = method_body(code, "public java.util.List<SchematicLoaderBlockEntity> getClusterInGuiOrder(")
    check("④b-1 缓存写入点用 List.copyOf 包住（调用方 sort/removeIf 也污染不到缓存）",
          "guiOrderCache = java.util.List.copyOf(computeClusterInGuiOrder())" in gui_body)
    check("④b-2 每次读取不拷贝（本方法每帧按槽位反复调用，逐次拷贝会变成新热点）",
          "return guiOrderCache;" in gui_body
          and "return java.util.List.copyOf(guiOrderCache)" not in gui_body)
    menu_dir = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq",
                            "rs_create_compat", "menu")
    callers = [
        ("SchematicLoaderBlockEntity.java", BE),
        ("SchematicLoaderMenu.java", os.path.join(menu_dir, "SchematicLoaderMenu.java")),
        ("AdvancedSchematicLoaderMenu.java", os.path.join(menu_dir, "AdvancedSchematicLoaderMenu.java")),
    ]
    mutating = []
    for label, path in callers:
        text = strip_comments(read(path)) if os.path.isfile(path) else ""
        for pattern in ("getClusterInGuiOrder().add", "getClusterInGuiOrder().sort",
                        "getClusterInGuiOrder().remove", "getClusterInGuiOrder().clear",
                        "getClusterInGuiOrder().set"):
            if pattern in text:
                mutating.append("%s: %s" % (label, pattern))
    check("④b-3 所有调用点都只读（没有 add/sort/remove/clear/set 直接作用在返回值上）",
          not mutating, "; ".join(mutating))

    broken_gui = raw.replace("guiOrderCache = java.util.List.copyOf(computeClusterInGuiOrder());",
                             "guiOrderCache = computeClusterInGuiOrder();", 1)
    counterexample("④b-C1 源码副本：缓存改回可变列表后，④b-1 判负",
                   "java.util.List.copyOf(computeClusterInGuiOrder())" not in
                   method_body(strip_comments(broken_gui),
                               "public java.util.List<SchematicLoaderBlockEntity> getClusterInGuiOrder("))


def group_l1_l3_cleanup():
    """⑤ 第 118 轮复核留下的 L1 / L2 清理，以及 L3 的**勘误**。

    * **L2（真死代码，已删）**：`isCannonAdjacent` 旧实现
      `level.getBlockEntity(worldPosition) == cannon || 曼哈顿距离 == 1`，
      而 `worldPosition` 是本机坐标 ⇒ 那里的方块实体恒为本装填器，恒假分支。
    * **L1（注释不符，已澄清）**：完成横幅注释原写「绝不回退到 cannon.checklist」，
      但 `cacheRequirementsFromBlueprint` 在「解析不出材料清单」时确实会**临时**兜底成 checklist 拷贝。
    * **L3（复核误报，本轮勘误）**：第 118 轮说「注释称速度 / 堆叠升级各最多 6 个，实现按槽保留 1 个」，
      实测 `UpgradeSlot.MAX_PER_UPGRADE == 6` 且 `dropBeyondKindCap` 保留前 `max` 个 ⇒ **注释是对的**。
    """
    print("\n=== ⑤ 第 118 轮 L1 / L2 清理 + L3 勘误 ===")
    raw = read(BE)
    code = strip_comments(raw)

    adjacent = method_body(code, "protected boolean isCannonAdjacent(")
    check("⑤-1 isCannonAdjacent 的死分支（level.getBlockEntity(worldPosition) == cannon）已删除",
          "getBlockEntity(worldPosition) == cannon" not in adjacent)
    check("⑤-2 只剩「曼哈顿距离 == 1」这一条判据（行为与旧实现等价）",
          "distManhattan(worldPosition) == 1" in adjacent)
    check("⑤-3 删除处留了「为什么它恒假」的注释（防止后人再拼回去）",
          "恒假分支" in raw or "死代码" in raw)

    banner = raw[raw.find("打印已完成 => 材料必然齐过"):] if "打印已完成 => 材料必然齐过" in raw else ""
    banner = banner[:1600]
    check("⑤-4 完成横幅注释不再声称「绝不回退到 cannon.checklist」（与实现一致）",
          "绝不回退到 cannon.checklist" not in banner)
    check("⑤-5 横幅注释写清「快照优先来自蓝图文件、解析失败时临时兜底 checklist」",
          "兜底成 checklist" in banner or "临时" in banner)

    # L3 勘误：常量与保留逻辑都必须对得上「各最多 6 个」
    upgrade_slot = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq",
                                "rs_create_compat", "menu", "UpgradeSlot.java")
    upgrade_text = read(upgrade_slot) if os.path.isfile(upgrade_slot) else ""
    check("⑤-6 UpgradeSlot.MAX_PER_UPGRADE == 6（因此「各最多 6 个」的注释是对的）",
          "MAX_PER_UPGRADE = 6" in upgrade_text)
    keep = method_body(code, "private void dropBeyondKindCap(")
    check("⑤-7 dropBeyondKindCap 保留前 max 个（不是只保留 1 个）",
          "kept >= max" in keep and "kept++" in keep)

    broken_adjacent = raw.replace(
        "return cannon.getBlockPos().distManhattan(worldPosition) == 1;",
        "return level.getBlockEntity(worldPosition) == cannon\n"
        "            || cannon.getBlockPos().distManhattan(worldPosition) == 1;", 1)
    counterexample("⑤-C1 源码副本：把死分支拼回去后，⑤-1 判负",
                   "getBlockEntity(worldPosition) == cannon" in
                   method_body(strip_comments(broken_adjacent), "protected boolean isCannonAdjacent("))

    broken_banner = raw.replace("兜底成 checklist 的拷贝", "绝不回退到 cannon.checklist", 1)
    if broken_banner == raw:
        broken_banner = raw.replace("**横幅自身绝不直接读 cannon.checklist**",
                                    "绝不回退到 cannon.checklist", 1)
    counterexample("⑤-C2 源码副本：把「绝不回退」的错误说法写回去后，⑤-4 判负",
                   "绝不回退到 cannon.checklist" in
                   (broken_banner[broken_banner.find("打印已完成 => 材料必然齐过"):][:1600]))


def main():
    print("第 119 轮自检：装填器「本轮蓝图身份落盘」(M2) + 「同 tick 集群 BFS 缓存」(M4)"
          " + 「打印完成哨兵落盘」")
    group_anchors()
    group_models()
    group_counterexamples()
    group_print_sentinels()
    group_l1_l3_cleanup()
    print("\n断言 %d 条（其中反例 %d 条），失败 %d 条" % (CHECKS[0], COUNTER[0], len(FAILS)))
    if FAILS:
        print("FAILED:")
        for name in FAILS:
            print("  - " + name)
        return 1
    print("ALL_PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
