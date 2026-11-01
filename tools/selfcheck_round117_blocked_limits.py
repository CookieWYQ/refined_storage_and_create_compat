#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 117 轮自检：归流缓存仓「阻塞名单」（具体 id + 按标签）的条数上限 / 原子拒绝 / 解码上限。

背景（第 22.6 节自己记下的账）
------------------------------
第 115 / 116 轮把「客户端给的数据会落盘 ⇒ 必须限体积 / 条数」这条纪律铺到了区域标记、两台定量
保持器、导入包、序列终端四个写入面。最后剩下的一类写入口是「名单 / 字符串列表」：

* ``SetCollectionBlockedPacket`` 的 ``tags`` 用 ``ByteBufCodecs.list()`` 解码 —— 条数完全由对端给；
* ``CollectionCacheBlockEntity#setBlocked / #setBlockedTags`` 直接 ``Set#add``，四个集合（物品 id /
  流体 id / 物品标签 / 流体标签）都能被「每包换一个新值」无限撑大，然后落盘 + 随同步包全量回发。

本脚本的验收口径 = **源码锚点 + 真代码 JVM 探针 + 行为模型 + 反例自证**，四条腿：

① ``CollectionBlockedLimits``：常量取值合理、纯 JDK（不引用任何 MC 类型，所以能单独 javac）；
② 网络层：包不再用 ``ByteBufCodecs.list()``；解码按声明条数消费字节但只暂存上限条；不按对端计数
   预分配；「名单已满」必须回动作栏提示（本工程硬规则：不许点了没反应）；
③ 方块实体：唯一写入口 ``applyBlockedEdit`` 在**任何写入之前**判两个上限；拒绝 = 一格不写（原子）；
   幂等（已存在的不占名额）；**只约束增长**，读档 ``loadBlocked`` 不许截断（超限旧档原样读回）；
④ 语言键 zh/en 成对，数值随常量走（文案里不许再写死 1024 / 256）。

⑧ 上一轮只读复核报出的 H1 / M2（同一轮修掉）：导入包里的配方 id 以前**完全没进预算**，
   而它会被逐张复制进每一步的单元样板（32 KB recipeId × 1024 步 ≈ 33 MB 落盘）；解码阶段
   被暂存上限截断时各列表互相错位却仍然成功导入（静默失效）。判据 = 配方 id 的单条限长 +
   按步数计入字符串合计 + 「截断过就整包拒绝」的源码锚点 + 行为模型。

反例自证（13 条）：把「坏写法」拼进源码副本 / 行为模型，断言判据真的判负。

用法：python tools/selfcheck_round117_blocked_limits.py
退出码：0 = 全部通过。
"""

from __future__ import annotations

import io
import json
import os
import re
import shutil
import subprocess
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOOLS = os.path.dirname(os.path.abspath(__file__))
PKG = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
SUPPORT = os.path.join(PKG, "support")
NET = os.path.join(PKG, "network")
BE_DIR = os.path.join(PKG, "block", "entity")
MENU_DIR = os.path.join(PKG, "menu")
RES = os.path.join(ROOT, "src", "main", "resources")
LANG_DIR = os.path.join(RES, "assets", "rs_create_compat", "lang")
PROBE_DIR = os.path.join(ROOT, "build", "selfcheck_round117")

JAVAC = "D:/java21/bin/javac.exe"
JAVA = "D:/java21/bin/java.exe"
JAVAC_EN = ["-J-Duser.language=en", "-J-Duser.country=US"]

FILES = {
    "limits": os.path.join(SUPPORT, "CollectionBlockedLimits.java"),
    "charger_limits": os.path.join(SUPPORT, "ChargerFilterLimits.java"),
    "packet": os.path.join(NET, "SetCollectionBlockedPacket.java"),
    # 第 117 轮只读复核的 H1 / M2 在导入包上（同一轮的修复）
    "import_packet": os.path.join(NET, "SetSequenceImportPacket.java"),
    "be": os.path.join(BE_DIR, "CollectionCacheBlockEntity.java"),
    "menu": os.path.join(MENU_DIR, "CollectionCacheMenu.java"),
}
LANG_KEYS = [
    "message.rs_create_compat.collection_cache.blocked_ids_full",
    "message.rs_create_compat.collection_cache.blocked_tags_full",
]
CJK_RE = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]")

CHECKS = [0]
COUNTER = [0]
FAILS = []

MAX_IDS = 1024
MAX_TAGS = 256
MAX_PER_PACKET = 128
MAX_TAG_LEN = 128


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


def counterexample(name, ok):
    COUNTER[0] += 1
    check("反例 " + name, ok)


# ======================================================================================
# 通用文本工具（与 round99 / round116 同一套：去注释、取方法体、读 int 常量）
# ======================================================================================

def strip_comments(text):
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
    if not match:
        return None
    return int(match.group(1).replace("_", ""))


def line_of(text, needle):
    index = text.find(needle)
    if index < 0:
        return -1
    return text.count("\n", 0, index) + 1


# ======================================================================================
# ①～⑤ 源码判据（返回问题码列表；反例自证靠「问题码出现」证明判负）
# ======================================================================================

def audit(src):
    problems = []
    limits_raw = src["limits"]
    limits = strip_comments(limits_raw)
    packet = strip_comments(src["packet"])
    be = strip_comments(src["be"])
    menu = strip_comments(src["menu"])

    # ---------- ① 常量：存在、量级合理、纯 JDK ----------
    consts = {
        "MAX_BLOCKED_IDS": int_const(limits, "MAX_BLOCKED_IDS"),
        "MAX_BLOCKED_TAGS": int_const(limits, "MAX_BLOCKED_TAGS"),
        "MAX_TAGS_PER_PACKET": int_const(limits, "MAX_TAGS_PER_PACKET"),
        "MAX_TAG_NAME_LENGTH": int_const(limits, "MAX_TAG_NAME_LENGTH"),
    }
    if any(value is None for value in consts.values()):
        problems.append("limits-constants-missing")
    else:
        if consts["MAX_BLOCKED_IDS"] != MAX_IDS:
            problems.append("limits-ids-not-1024")
        if consts["MAX_BLOCKED_TAGS"] != MAX_TAGS:
            problems.append("limits-tags-not-256")
        if not (16 <= consts["MAX_TAGS_PER_PACKET"] <= consts["MAX_BLOCKED_TAGS"]):
            # 单包上限必须远大于「一个物品真实标签数（通常 < 30）」，否则会误伤正常客户端
            problems.append("limits-per-packet-nonsense")
        if not (40 <= consts["MAX_TAG_NAME_LENGTH"] <= 512):
            # 真实标签 id 通常 < 40 字符；太小会误伤「c:...」长命名空间，太大失去意义
            problems.append("limits-tag-name-length-nonsense")
        if consts["MAX_BLOCKED_TAGS"] > consts["MAX_BLOCKED_IDS"]:
            # 一条标签挡住一整族资源，名额理应比逐个 id 少
            problems.append("limits-tag-cap-larger-than-id-cap")
    if "final class" not in limits or "private CollectionBlockedLimits()" not in limits:
        problems.append("limits-not-final-or-instantiable")
    if re.search(r"^\s*import\s+(net\.minecraft|net\.neoforged|com\.mojang|com\.refinedmods)",
                 limits, re.M):
        problems.append("limits-impure-import")
    for word in ("net.minecraft", "net.neoforged", "BuiltInRegistries", "ItemStack", "ResourceLocation"):
        if word in limits:
            problems.append("limits-impure-ref:" + word)
    if "static <T> boolean fits(" not in limits:
        problems.append("limits-fits-missing")
    if "ChargerFilterLimits.clamp(" not in limits:
        # 有界截断的算法只准有一份：本类必须委托，不许再抄一遍循环
        problems.append("limits-clamp-not-delegated")

    # ---------- ② 网络层 ----------
    if "ByteBufCodecs.list()" in packet:
        problems.append("packet-still-uses-unbounded-list")
    tags_codec = body_of(packet, "TAGS_CODEC")
    if "MAX_TAGS_PER_PACKET" not in tags_codec:
        problems.append("packet-tags-codec-capless")
    decode = body_of(packet, "buf ->")
    if "Math.min(declared" not in decode and "Math.min(declared, CollectionBlockedLimits.MAX_TAGS_PER_PACKET)" not in decode:
        problems.append("packet-decode-no-keep-cap")
    if re.search(r"new ArrayList<>\(\s*declared\s*\)", decode):
        problems.append("packet-decode-prealloc-by-peer")
    if "for (int i = 0; i < declared; i++)" not in decode:
        # 必须按对端声明条数消费字节，否则流错位（后续字段全错）
        problems.append("packet-decode-not-consuming-declared")
    if "CollectionBlockedLimits.clamp(" not in tags_codec:
        problems.append("packet-encode-not-clamped")
    handle = body_of(packet, "public static void handle(")
    if "setBlocked(" not in handle:
        problems.append("packet-handle-no-setblocked")
    for name in ("REJECTED_IDS_FULL", "REJECTED_TAGS_FULL"):
        if name not in handle:
            problems.append("packet-handle-ignores:" + name)
    for key in LANG_KEYS:
        if key not in handle:
            problems.append("packet-handle-no-message:" + key)
    if "displayClientMessage(" not in handle:
        problems.append("packet-handle-silent")
    if "warnFull(" not in packet:
        problems.append("packet-no-warn-once")

    # ---------- ③ 方块实体 ----------
    if "public enum BlockEditResult" not in be:
        problems.append("be-result-enum-missing")
    for value in ("CHANGED", "UNCHANGED", "REJECTED_IDS_FULL", "REJECTED_TAGS_FULL"):
        if value not in be:
            problems.append("be-result-enum-incomplete:" + value)
    edit = body_of(be, "public BlockEditResult applyBlockedEdit(")
    if not edit:
        problems.append("be-apply-blocked-edit-missing")
    else:
        first_id_add = edit.find("idSet.add(")
        first_tag_add = edit.find("tagSet.add(")
        guard_ids = edit.find("MAX_BLOCKED_IDS")
        guard_tags = edit.find("MAX_BLOCKED_TAGS")
        if guard_ids < 0 or guard_tags < 0:
            problems.append("be-edit-no-cap-guard")
        else:
            if first_id_add >= 0 and guard_ids > first_id_add:
                problems.append("be-edit-id-cap-checked-after-write")
            if first_tag_add >= 0 and guard_tags > first_tag_add:
                problems.append("be-edit-tag-cap-checked-after-write")
        if "CollectionBlockedLimits.fits(" not in edit:
            problems.append("be-edit-does-not-use-fits")
        if edit.find("setChanged()") >= 0 and edit.find("setChanged()") < max(first_id_add, first_tag_add):
            problems.append("be-edit-setchanged-before-write")
        for name in ("REJECTED_IDS_FULL", "REJECTED_TAGS_FULL"):
            if name not in edit:
                problems.append("be-edit-no-early-return:" + name)
    if "MAX_TAG_NAME_LENGTH" not in body_of(be, "private static List<ResourceLocation> sanitizeBlockedTags("):
        problems.append("be-sanitize-no-length-guard")
    for method in ("public boolean setBlocked(", "public boolean setBlockedTags("):
        body = body_of(be, method)
        if "applyBlockedEdit(" not in body:
            problems.append("be-legacy-entry-not-delegated:" + method)
    load = body_of(be, "private void loadBlocked(")
    if not load:
        problems.append("be-loadblocked-missing")
    else:
        for token in ("MAX_BLOCKED_IDS", "MAX_BLOCKED_TAGS", "CollectionBlockedLimits", "break"):
            if token in load:
                # 读档截断 = 吞掉玩家「明确说过不要回流」的配置（第 116 轮同一条规则）
                problems.append("be-loadblocked-truncates:" + token)

    # ---------- ④ 菜单 ----------
    menu_set = body_of(menu, "public CollectionCacheBlockEntity.BlockEditResult setBlocked(")
    if not menu_set:
        problems.append("menu-setblocked-no-result")
    else:
        if "applyBlockedEdit(" not in menu_set:
            problems.append("menu-setblocked-not-atomic-entry")
        if "syncMarkers()" not in menu_set:
            problems.append("menu-setblocked-no-resync")
        for token in ("block.setBlocked(fluid, id, blocked)", "block.setBlockedTags(fluid, tags, blocked)"):
            if token in menu_set:
                # 两次独立写入 = 可能半写（id 成功、标签失败）
                problems.append("menu-setblocked-split-writes")

    # ---------- ⑤ 上限值的唯一出处（BE / 包都不许自己写死数字） ----------
    if not edit:
        pass
    elif ("CollectionBlockedLimits.MAX_BLOCKED_IDS" not in edit
          or "CollectionBlockedLimits.MAX_BLOCKED_TAGS" not in edit):
        problems.append("be-edit-cap-not-from-constants")
    if "CollectionBlockedLimits.MAX_TAGS_PER_PACKET" not in be:
        problems.append("be-sanitize-cap-not-from-constants")

    return problems


# ======================================================================================
# ⑥ 真代码 JVM 探针：CollectionBlockedLimits 是纯 JDK，单独编译执行
# ======================================================================================

PROBE_SRC = r"""
import cretae.cookiewyq.rs_create_compat.support.CollectionBlockedLimits;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class Probe {
    private static int ok;
    private static int bad;

    private static void check(String name, boolean cond) {
        if (cond) {
            ok++;
        } else {
            bad++;
            System.out.println("FAIL probe " + name);
        }
    }

    private static Set<String> set(String... values) {
        return new LinkedHashSet<>(Arrays.asList(values));
    }

    private static List<String> list(int count, String prefix) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(prefix + i);
        }
        return out;
    }

    public static void main(String[] args) {
        // ---- fits：边界 ----
        check("fits-empty", CollectionBlockedLimits.fits(set(), List.of("a"), 4));
        check("fits-exact", CollectionBlockedLimits.fits(set("a", "b", "c"), List.of("d"), 4));
        check("fits-over", !CollectionBlockedLimits.fits(set("a", "b", "c", "d"), List.of("e"), 4));
        check("fits-idempotent", CollectionBlockedLimits.fits(set("a", "b", "c", "d"), List.of("a"), 4));
        check("fits-null-candidate", CollectionBlockedLimits.fits(set("a", "b", "c", "d"),
            Arrays.asList("a", null), 4));
        check("fits-mixed-over", !CollectionBlockedLimits.fits(set("a", "b", "c"), List.of("d", "e"), 4));
        check("fits-cap-zero", !CollectionBlockedLimits.fits(set(), List.of("a"), 0));

        // ---- isFull ----
        check("isfull-true", CollectionBlockedLimits.isFull(4, 4));
        check("isfull-false", !CollectionBlockedLimits.isFull(3, 4));
        check("isfull-zero-cap", CollectionBlockedLimits.isFull(0, 0));

        // ---- clamp：有界截断 ----
        check("clamp-cap", CollectionBlockedLimits.clamp(list(200, "t"), 128).size() == 128);
        check("clamp-short", CollectionBlockedLimits.clamp(list(3, "t"), 128).size() == 3);
        check("clamp-null", CollectionBlockedLimits.clamp(null, 128).isEmpty());

        // ---- addBounded：塞满为止、幂等 ----
        Set<String> target = set("a");
        check("addbounded-added", CollectionBlockedLimits.addBounded(target, list(5, "x"), 3) == 2);
        check("addbounded-size", target.size() == 3);

        // ---- sanitizeTagNames：null / 空 / 超长 / 去重 / 上限 ----
        List<String> raw = new ArrayList<>();
        raw.add("minecraft:planks");
        raw.add(null);
        raw.add("");
        raw.add("minecraft:planks");
        StringBuilder longTag = new StringBuilder("x:");
        for (int i = 0; i < 300; i++) {
            longTag.append('y');
        }
        raw.add(longTag.toString());
        List<String> clean = CollectionBlockedLimits.sanitizeTagNames(raw);
        check("sanitize-drop", clean.size() == 1 && clean.get(0).equals("minecraft:planks"));
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            many.add("t:" + i);
        }
        check("sanitize-cap", CollectionBlockedLimits.sanitizeTagNames(many).size() == 128);

        System.out.println("probe ok=" + ok + " bad=" + bad);
        if (bad > 0) {
            System.exit(1);
        }
    }
}
"""


def run_probe():
    """编译并执行纯 JDK 的 CollectionBlockedLimits（连同它委托的 ChargerFilterLimits）。"""
    if not (os.path.isfile(JAVAC) and os.path.isfile(JAVA)):
        return None, "javac/java 不存在：%s" % JAVAC
    src_dir = os.path.join(PROBE_DIR, "src", "cretae", "cookiewyq", "rs_create_compat", "support")
    out_dir = os.path.join(PROBE_DIR, "classes")
    shutil.rmtree(PROBE_DIR, ignore_errors=True)
    os.makedirs(src_dir)
    os.makedirs(out_dir)
    for name in ("CollectionBlockedLimits.java", "ChargerFilterLimits.java"):
        shutil.copyfile(os.path.join(SUPPORT, name), os.path.join(src_dir, name))
    probe_path = os.path.join(PROBE_DIR, "Probe.java")
    with io.open(probe_path, "w", encoding="utf-8") as handle:
        handle.write(PROBE_SRC)
    compile_cmd = [JAVAC] + JAVAC_EN + ["-encoding", "UTF-8", "-d", out_dir,
                                        os.path.join(src_dir, "CollectionBlockedLimits.java"),
                                        os.path.join(src_dir, "ChargerFilterLimits.java"),
                                        probe_path]
    proc = subprocess.run(compile_cmd, cwd=ROOT, capture_output=True, text=True,
                          encoding="utf-8", errors="replace")
    if proc.returncode != 0:
        return None, "javac 失败：%s" % (proc.stderr or proc.stdout)[-800:]
    run = subprocess.run([JAVA, "-cp", out_dir, "Probe"], cwd=ROOT, capture_output=True,
                         text=True, encoding="utf-8", errors="replace")
    return run, (run.stderr or "")[-400:]


# ======================================================================================
# ⑦ 行为模型：applyBlockedEdit 的语义（与源码锚点分开，防止「锚点对但逻辑错」）
# ======================================================================================

class BlockedModel:
    """复刻 CollectionCacheBlockEntity#applyBlockedEdit 的语义（上限参数化，便于反例）。"""

    def __init__(self, max_ids=MAX_IDS, max_tags=MAX_TAGS):
        self.max_ids = max_ids
        self.max_tags = max_tags
        self.ids = set()
        self.tags = set()

    @staticmethod
    def _fits(current, candidates, cap):
        remaining = cap - len(current)
        for candidate in candidates:
            if candidate is None or candidate in current:
                continue
            if remaining <= 0:
                return False
            remaining -= 1
        return True

    def edit(self, resource_id, blocked, tags):
        clean = []
        for tag in tags or ():
            if tag is None or len(tag) > MAX_TAG_LEN or tag in clean:
                continue
            clean.append(tag)
            if len(clean) >= MAX_PER_PACKET:
                break
        if resource_id is None and not clean:
            return "UNCHANGED"
        if blocked:
            if resource_id is not None and not self._fits(self.ids, [resource_id], self.max_ids):
                return "REJECTED_IDS_FULL"
            if not self._fits(self.tags, clean, self.max_tags):
                return "REJECTED_TAGS_FULL"
        changed = False
        if resource_id is not None:
            if blocked:
                if resource_id not in self.ids:
                    self.ids.add(resource_id)
                    changed = True
            else:
                if resource_id in self.ids:
                    self.ids.remove(resource_id)
                    changed = True
        for tag in clean:
            if blocked:
                if tag not in self.tags:
                    self.tags.add(tag)
                    changed = True
            else:
                if tag in self.tags:
                    self.tags.remove(tag)
                    changed = True
        return "CHANGED" if changed else "UNCHANGED"


def run_model_checks():
    results = []

    def expect(name, cond, detail=""):
        results.append((name, cond, detail))

    # 正常加入 / 幂等
    model = BlockedModel()
    expect("model-add", model.edit("minecraft:iron_ingot", True, []) == "CHANGED")
    expect("model-idempotent", model.edit("minecraft:iron_ingot", True, []) == "UNCHANGED")
    # 解除阻塞永远允许（哪怕名单已满）
    full = BlockedModel()
    for i in range(MAX_IDS):
        full.ids.add("id:%d" % i)
    expect("model-full-refuse", full.edit("id:new", True, []) == "REJECTED_IDS_FULL")
    expect("model-full-remove-allowed", full.edit("id:0", False, []) == "CHANGED")
    # 原子性：id 放得下但标签放不下 ⇒ 一格不写
    tag_full = BlockedModel()
    for i in range(MAX_TAGS):
        tag_full.tags.add("tag:%d" % i)
    before_ids = len(tag_full.ids)
    expect("model-atomic", tag_full.edit("id:fresh", True, ["tag:new"]) == "REJECTED_TAGS_FULL")
    expect("model-atomic-no-write", len(tag_full.ids) == before_ids)
    # 标签超长被丢弃后仍可加入
    over = BlockedModel()
    expect("model-overlong-tag-dropped",
           over.edit(None, True, ["x:" + "y" * 300]) == "UNCHANGED" and not over.tags)
    # 单包超过 MAX_PER_PACKET 只暂存前 N 条
    many = BlockedModel(max_tags=10000)
    tags = ["t:%d" % i for i in range(MAX_PER_PACKET + 50)]
    many.edit(None, True, tags)
    expect("model-per-packet-cap", len(many.tags) == MAX_PER_PACKET)
    return results


# ======================================================================
# ⑧ 第 117 轮只读复核的 H1 / M2：配方 id 的放大 + 解码截断的静默失效
# ======================================================================

# 与 SequencePatternTerminalBlockEntity 的两条常量同步（脚本里只做「口径」模型，值从源码读）
IMPORT_TOTAL_BYTES = 256 * 1024 * 8          # MAX_IMPORT_TOTAL_WIRE_BYTES(256 KB) × WIRE_RATIO(8)
IMPORT_STRING_CAP = 256                       # MAX_IMPORT_STRING_LENGTH


def budget_strings(recipe_len, steps, machine_lens, cap=IMPORT_STRING_CAP, per_step=True):
    """复刻 budgetViolation 的字符串部分（第 117 轮 H1 修正后）。

    @return None = 通过；否则是原因码。
    """
    if cap is not None and recipe_len > cap:
        return "recipe-id-too-long"
    total = 0
    for length in machine_lens:
        if length is None:
            continue
        if cap is not None and length > cap:
            return "machine-too-long"
        total += 36 + 2 * length
        if total > IMPORT_TOTAL_BYTES:
            return "machine-total"
    total += (36 + 2 * recipe_len) * (max(1, steps) if per_step else 1)
    if total > IMPORT_TOTAL_BYTES:
        return "string-total"
    return None


def audit_import_amplification(src):
    """H1 / M2 的源码锚点（判据名与 main() 里打印的一致）。"""
    problems = []
    packet = strip_comments(src["import_packet"])
    # ---- H1：recipeId 必须有单条限长，并按步数计入字符串合计
    budget = body_of(packet, "static String budgetViolation(")
    if "recipeId" not in budget:
        problems.append("h1-recipe-not-budgeted")
    if "recipeId.length() >" not in budget:
        problems.append("h1-recipe-no-length-cap")
    if "* steps" not in budget and "* steps;" not in budget:
        problems.append("h1-recipe-not-multiplied-by-steps")
    # ---- M2：解码截断必须带标记，且 handle 在写入之前整包拒绝
    if "boolean truncated" not in packet:
        problems.append("m2-no-truncated-flag")
    if "staged[1] = 1" not in packet:
        problems.append("m2-decode-does-not-flag-truncation")
    if "staged[1] != 0" not in packet:
        problems.append("m2-decode-does-not-pass-flag")
    handle = body_of(packet, "public static void handle(")
    if "packet.truncated()" not in handle:
        problems.append("m2-handle-ignores-truncated")
    elif handle.find("packet.truncated()") > handle.find("budgetViolation("):
        problems.append("m2-handle-checks-truncated-after-budget")
    # encode 不许把 truncated 写上线（否则线格式与客户端构造不一致）
    encode = body_of(packet, "private static void encode(")
    if "truncated" in encode:
        problems.append("m2-encode-writes-truncated")
    return problems


# ======================================================================
# 反例自证：把坏写法拼进源码副本，断言 audit() 真的能判负
# ======================================================================

def counterexample_selftests(src):
    base = audit(src)
    if base:
        print("FAIL 反例基线（干净源码应当 0 问题）：%s" % base)
        FAILS.append("counterexample-baseline")
        return

    def mutate_and_expect(name, key, old, new, problem_code):
        broken = dict(src)
        text = broken[key]
        if old not in text:
            counterexample(name + "（变异点不存在）", False)
            return
        broken[key] = text.replace(old, new, 1)
        problems = audit(broken)
        counterexample(name, problem_code in problems)

    # 1. 包退回无上限 list()
    mutate_and_expect("包退回 ByteBufCodecs.list()", "packet",
                      "TAGS_CODEC, SetCollectionBlockedPacket::tags,",
                      "ResourceLocation.STREAM_CODEC.apply(ByteBufCodecs.list()), SetCollectionBlockedPacket::tags,",
                      "packet-still-uses-unbounded-list")
    # 2. 解码按对端计数预分配
    mutate_and_expect("解码按对端计数预分配", "packet",
                      "final List<ResourceLocation> kept = new ArrayList<>(keep);",
                      "final List<ResourceLocation> kept = new ArrayList<>(declared);",
                      "packet-decode-prealloc-by-peer")
    # 3. 解码不消费剩余字节
    mutate_and_expect("解码不按声明条数消费字节", "packet",
                      "for (int i = 0; i < declared; i++) {",
                      "for (int i = 0; i < keep; i++) {",
                      "packet-decode-not-consuming-declared")
    # 4. handle 不再提示「名单已满」
    mutate_and_expect("handle 静默丢弃满员拒绝", "packet",
                      'if (result == CollectionCacheBlockEntity.BlockEditResult.REJECTED_IDS_FULL) {',
                      'if (false) {',
                      "packet-handle-ignores:REJECTED_IDS_FULL")
    # 5. 读档截断（吞玩家配置）
    mutate_and_expect("读档截断超限名单", "be",
                      "private void loadBlocked(final CompoundTag tag) {",
                      "private void loadBlocked(final CompoundTag tag) { if (blockedItems.size() >= "
                      "CollectionBlockedLimits.MAX_BLOCKED_IDS) { return; } ",
                      "be-loadblocked-truncates:CollectionBlockedLimits")
    # 6. 先写 id 再判上限（半写）
    mutate_and_expect("先写 id 后判上限（半写）", "be",
                      "if (id != null && !CollectionBlockedLimits.fits(",
                      "if (id != null) { idSet.add(id); }\n            if (id != null && !CollectionBlockedLimits.fits(",
                      "be-edit-id-cap-checked-after-write")
    # 7. 菜单拆成两次独立写入
    mutate_and_expect("菜单两次独立写入", "menu",
                      "final CollectionCacheBlockEntity.BlockEditResult result =\n            block.applyBlockedEdit(fluid, id, blocked, tags);",
                      "block.setBlocked(fluid, id, blocked);\n        block.setBlockedTags(fluid, tags, blocked);\n        final CollectionCacheBlockEntity.BlockEditResult result = null;",
                      "menu-setblocked-split-writes")
    # 8. 上限值被写死进 BE（口径分叉：以后改常量这里不会跟着变）
    mutate_and_expect("BE 把上限值写死（不引用常量）", "be",
                      "idSet, List.of(id), CollectionBlockedLimits.MAX_BLOCKED_IDS)) {",
                      "idSet, List.of(id), 1024)) {",
                      "be-edit-cap-not-from-constants")

    # 9. 行为模型反例：把「已存在的条目不占名额」（幂等）这条丢掉 ⇒ 满员时重复阻塞会被误判为拒绝
    class NonIdempotentModel(BlockedModel):
        @staticmethod
        def _fits(current, candidates, cap):
            if len(current) >= cap:
                return False  # 坏写法：满了就连「已在名单里」的也拒
            return BlockedModel._fits(current, candidates, cap)

    broken = NonIdempotentModel()
    for i in range(MAX_IDS):
        broken.ids.add("id:%d" % i)
    counterexample("满员时不再幂等应被判负（重复阻塞同一件必须继续成功）",
                   broken.edit("id:0", True, []) != "UNCHANGED")

    # 10. 语言键反例：缺失 / 不成对（直接查文件）
    zh_text = read(os.path.join(LANG_DIR, "zh_cn.json"))
    en_text = read(os.path.join(LANG_DIR, "en_us.json"))
    counterexample("语言键缺失应被判负", not lang_keys_ok(zh_text.replace(LANG_KEYS[0], "x"), en_text))

    # 11. H1 反例：两道闸都去掉 ⇒ 32 KB recipeId × 1024 步就漏过去了
    counterexample("H1 两道闸都去掉时 32 KB 配方 id 会漏过（证明 ⑧-1 / ⑧-2 都有判别力）",
                   budget_strings(32700, 1024, [], cap=None, per_step=False) is None)

    # 12. M2 反例：handle 不再看 truncated 标记
    broken = dict(src)
    packet_src = broken["import_packet"]
    if "if (packet.truncated()) {" in packet_src:
        broken["import_packet"] = packet_src.replace("if (packet.truncated()) {", "if (false) {", 1)
        counterexample("M2：handle 忽略解码截断标记应被判负",
                       "m2-handle-ignores-truncated" in audit_import_amplification(broken))
    else:
        counterexample("M2：handle 忽略解码截断标记应被判负（变异点不存在）", False)

    # 13. H1 反例：recipeId 不再做单条限长（把 `>` 改成 `<`，等于永不触发）
    broken = dict(src)
    broken["import_packet"] = broken["import_packet"].replace(
        "recipeId.length() >", "recipeId.length() <", 1)
    counterexample("H1：配方 id 去掉单条限长应被判负",
                   "h1-recipe-no-length-cap" in audit_import_amplification(broken))


def load_lang():
    def load(path):
        with io.open(path, "r", encoding="utf-8") as handle:
            return json.load(handle)
    return load(os.path.join(LANG_DIR, "zh_cn.json")), load(os.path.join(LANG_DIR, "en_us.json"))


def lang_keys_ok(zh_text=None, en_text=None):
    try:
        if zh_text is None or en_text is None:
            zh, en = load_lang()
        else:
            zh, en = json.loads(zh_text), json.loads(en_text)
    except Exception:
        return False
    for key in LANG_KEYS:
        if key not in zh or key not in en:
            return False
        if "%s" not in zh[key] or "%s" not in en[key]:
            return False
        if not CJK_RE.search(zh[key]):
            return False
        if CJK_RE.search(en[key]):
            return False
    return True


def main():
    src = {}
    for key, path in FILES.items():
        if not os.path.isfile(path):
            print("FAIL 文件不存在：%s" % path)
            sys.exit(1)
        src[key] = read(path)

    print("=" * 78)
    print("第 117 轮：归流缓存仓阻塞名单条数上限 / 原子拒绝 / 解码上限")
    print("=" * 78)

    # ---------- 源码判据 ----------
    problems = audit(src)
    check("源码判据全部成立（干净源码必须 0 问题）", not problems, ", ".join(problems))

    # ---------- 单独断言几条最容易退化的锚点（失败时报出判据名） ----------
    packet = strip_comments(src["packet"])
    check("包：TAGS_CODEC 解码 keep 用 min(声明, 上限)",
          "Math.min(declared, CollectionBlockedLimits.MAX_TAGS_PER_PACKET)" in packet)
    check("包：拒绝满员时走动作栏提示（不静默）",
          packet.count("displayClientMessage(") >= 2)
    be = strip_comments(src["be"])
    edit_body = body_of(be, "public BlockEditResult applyBlockedEdit(")
    check("BE：两个上限都在写入之前判", edit_body.find("MAX_BLOCKED_IDS") < edit_body.find("idSet.add(")
          and edit_body.find("MAX_BLOCKED_TAGS") < edit_body.find("tagSet.add("))
    check("BE：拒绝分支在写入之前 return",
          edit_body.find("return BlockEditResult.REJECTED_IDS_FULL") < edit_body.find("idSet.add(")
          and edit_body.find("return BlockEditResult.REJECTED_TAGS_FULL") < edit_body.find("tagSet.add("))
    load_body = body_of(be, "private void loadBlocked(")
    check("BE：读档不判上限（超限旧档原样读回）",
          "CollectionBlockedLimits" not in load_body and "break" not in load_body)
    menu = strip_comments(src["menu"])
    check("菜单：setBlocked 返回结果并 resync", "BlockEditResult setBlocked(" in menu)

    # ---------- 语言键 ----------
    check("语言键 zh/en 成对、含 %s、en 无中文", lang_keys_ok())

    # ---------- 真代码 JVM 探针 ----------
    probe, err = run_probe()
    if probe is None:
        check("JVM 探针（CollectionBlockedLimits 真跑）", False, err or "")
    else:
        check("JVM 探针（CollectionBlockedLimits 真跑）",
              probe.returncode == 0 and "bad=0" in probe.stdout,
              (probe.stdout or "")[-500:] + (probe.stderr or "")[-300:])
        print("      " + " | ".join(l for l in (probe.stdout or "").splitlines() if l.strip())[-200:])

    # ---------- 行为模型 ----------
    for name, cond, detail in run_model_checks():
        check("行为模型 " + name, cond, detail)

    # ---------- ⑧ 上一轮复核的 H1 / M2 ----------
    import_problems = audit_import_amplification(src)
    check("⑧ 导入包 H1（配方 id 限长 + 按步数计入）/ M2（截断整包拒绝）锚点全部成立",
          not import_problems, ", ".join(import_problems))
    check("⑧-1 H1：单个 32700 字符配方 id 被「单条限长」独立挡住",
          budget_strings(32700, 1, [], per_step=False) == "recipe-id-too-long")
    check("⑧-2 H1：1024 步 × 1100 字符配方 id 被「按步数计入合计」独立挡住",
          budget_strings(1100, 1024, [], cap=None) == "string-total")
    check("⑧-3 H1 不误伤正常导入：create:pressing（15 字符）× 20 步 + 20 组候选通过",
          budget_strings(15, 20, [20] * 20) is None)

    # ---------- 反例自证 ----------
    counterexample_selftests(src)
    print("-" * 78)
    print("断言 %d / 反例 %d" % (CHECKS[0], COUNTER[0]))
    if FAILS:
        print("round117 FAILED: " + ", ".join(FAILS))
        sys.exit(1)
    print("round117 OK 断言 %d / 反例 %d" % (CHECKS[0], COUNTER[0]))
    sys.exit(0)


if __name__ == "__main__":
    main()
