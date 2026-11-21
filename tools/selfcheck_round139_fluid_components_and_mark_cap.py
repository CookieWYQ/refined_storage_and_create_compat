#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 139 轮自检：带组件流体的 drain/fill 对称 + 「>288 条」不再静默截断。

两个主题（对应第 138 轮「未做清单」里用户点名的两条）
-----------------------------------------------------
A. 第 118 轮复核 L3：两台定量保持器的流体能力里，`drain` 与 `fill` 的**组件编码上下文不对称**
   （`fill` 带 `level.registryAccess()`、`drain` 不带），且 `getFluidInTank` / `drainById`
   返回的 FluidStack **把数据组件补丁丢掉**。后果：外部管道拿到裸流体，再拿回来 drain 时按
   「无组件」查本机的带组件条目必然落空 ⇒ 静默回退网络 ⇒ 带组件流体「取不出来」（变体数据也被丢）。
   现在：所有返回值都带补丁、`fill`/`drain` 同一上下文（`registryHint()`）、网络回退要求
   「id + 组件 NBT 与直接标记逐字一致」。
B. 第 114/115 轮复核 L1：REPLACE 把旧几何的标记搬到新几何时，两块地合并后可能超过单区域上限
   `MATCH_CAPACITY = 288`，旧实现交给 `AreaMarks.put → sanitizeEntries` **静默截断**（尾部配置永久消失）。
   现在：合并结果先算好，超限就在**任何写入之前** `reject(REJECT_MARK_OVERFLOW)`（旧区域与全部标记
   一个字节都不动）；`AreaMarks.put` / `migrateByIndex` 这两条「数据层兜底」也在截断时打 WARN。

本脚本四条腿
------------
① 源码锚点：两个保持器里「带组件」的返回 / 同一上下文编码 / 严格回退；
② 行为模型：在 Python 里复刻「按键查本机条目」与「合并后是否超 288」的判定语义，对边界值断言；
③ 数据层兜底：AreaMarks 的截断告警 + REPLACE 的拒绝顺序（拒绝必须在 AreaSelection.set 之前）；
④ 反例自证：把旧写法拼回内存里的合成源码，判据必须判负 —— 证明判据不是「只对当前源码成立」。

用法：python tools/selfcheck_round139_fluid_components_and_mark_cap.py
退出码：0 = 全部通过。
"""

from __future__ import annotations

import io
import os
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA_REL = os.path.join("src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
JAVA = os.path.join(ROOT, JAVA_REL)
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")

KEEPER = os.path.join(JAVA, "block", "entity", "QuantityKeeperBlockEntity.java")
ADV_KEEPER = os.path.join(JAVA, "block", "entity", "AdvancedQuantityKeeperBlockEntity.java")
AREA_MARKS = os.path.join(JAVA, "support", "AreaMarks.java")
SELECTOR_PACKET = os.path.join(JAVA, "network", "AreaSelectorEditPacket.java")
STRIP_PANEL = os.path.join(JAVA, "client", "screen", "AreaStripPanel.java")

MATCH_CAPACITY = 288

_passed = 0
_failed = 0
_failures = []


def check(name, ok, detail=""):
    global _passed, _failed
    if ok:
        _passed += 1
        print("  OK   %s" % name)
    else:
        _failed += 1
        _failures.append((name, detail))
        print("  FAIL %s" % name)
        if detail:
            for line in str(detail).strip().splitlines()[:10]:
                print("       " + line)


def read(path):
    with io.open(path, "r", encoding="utf-8") as handle:
        return handle.read()


def strip_comments(text):
    """去掉 // 与 /* */ 注释（判据只看代码；注释里提到旧写法不算数）。"""
    out = []
    i = 0
    length = len(text)
    while i < length:
        char = text[i]
        if char == "/" and i + 1 < length and text[i + 1] == "/":
            while i < length and text[i] != "\n":
                i += 1
        elif char == "/" and i + 1 < length and text[i + 1] == "*":
            i += 2
            while i + 1 < length and not (text[i] == "*" and text[i + 1] == "/"):
                i += 1
            i += 2
        elif char == '"':
            out.append(char)
            i += 1
            while i < length:
                out.append(text[i])
                if text[i] == "\\" and i + 1 < length:
                    out.append(text[i + 1])
                    i += 2
                    continue
                if text[i] == '"':
                    i += 1
                    break
                i += 1
        else:
            out.append(char)
            i += 1
    return "".join(out)


def method_body(text, signature):
    index = text.find(signature)
    if index < 0:
        return ""
    start = text.find("{", index)
    if start < 0:
        return ""
    depth = 0
    for position in range(start, len(text)):
        char = text[position]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return text[start:position + 1]
    return ""


# ======================================================================
# ① 判据（正例 / 反例共用同一实现）
# ======================================================================

FLUID_STACK_BARE = "new FluidStack(fluid, (int) Math.min(Integer.MAX_VALUE,"


def handler_carries_components(text):
    """getFluidInTank 必须把条目里的组件补丁带出去（基础版 / 高级版写法不同，判据分开）。"""
    return (
        "return toFluidStack(fluid, entry.amount(), entry.nbt());" in text
        or "return toStack(entries.get(0));" in text
    )


def drain_uses_same_context(text):
    body = method_body(text, "public FluidStack drain(final FluidStack resource, final FluidAction action)")
    return (
        body != ""
        and "MarkerEntry.encodeComponents(resource.getComponentsPatch(), registryHint())" in body
        and "MarkerEntry.encodeComponents(resource.getComponentsPatch())" not in body
    )


def drain_result_carries_components(text, slot_based):
    body = method_body(text, "private FluidStack drainById(")
    if slot_based:
        return "return stackOf(id, nbt, extracted);" in body
    return "return toFluidStack(fluid, extracted, nbt);" in body


def helper_decodes_with_hint(text, helper_signature):
    body = method_body(text, helper_signature)
    return (
        body != ""
        and "MarkerEntry.decodeComponents(nbt, registryHint())" in body
        and "new FluidStack(BuiltInRegistries.FLUID.wrapAsHolder(fluid)," in body
    )


def base_strict_network_fallback(text):
    body = method_body(text, "public FluidStack drain(final FluidStack resource, final FluidAction action)")
    return (
        body != ""
        and "!nbt.equals(getFluidMarkerNbt())" in body
        and "toFluidStack(resource.getFluid(), fromNetwork, getFluidMarkerNbt())" in body
    )


def fill_drain_same_context_count(text):
    """fill 与 drain 两处都必须用 registryHint()（同上下文）——恰好 2 处。"""
    return text.count("MarkerEntry.encodeComponents(resource.getComponentsPatch(), registryHint())") == 2


def no_bare_fluidstack(text):
    return FLUID_STACK_BARE not in text


def area_marks_put_warns(text):
    body = method_body(text, "public static void put(")
    return (
        body != ""
        and "final int offered = countNonEmpty(entries);" in body
        and "if (offered > CollectionCacheBlockEntity.MATCH_CAPACITY) {" in body
        and "LOGGER.warn(" in body
        and body.index("if (offered > CollectionCacheBlockEntity.MATCH_CAPACITY) {")
        < body.index("update(stack, root -> {")
    )


def area_marks_migrate_warns(text):
    body = method_body(text, "public static MergeResult migrateByIndex(")
    return (
        body != ""
        and "final int offered = countNonEmpty(entry.getValue());" in body
        and "if (offered > CollectionCacheBlockEntity.MATCH_CAPACITY) {" in body
        and "LOGGER.warn(" in body
        and body.index("if (offered > CollectionCacheBlockEntity.MATCH_CAPACITY) {")
        < body.index("put(stack, area, clean);")
    )


def replace_rejects_before_write(text):
    body = method_body(text, "public static void handle(final AreaSelectorEditPacket packet, final ServerPlayer player)")
    if body == "":
        return False
    guard = "if (merged.size() > CollectionCacheBlockEntity.MATCH_CAPACITY) {"
    if guard not in body or "reject(rejectMenu, player, packet, REJECT_MARK_OVERFLOW);" not in body:
        return False
    if "AreaSelection.set(target, selection);" not in body:
        return False
    # 关键顺序：容量闸门 + 拒绝 必须都发生在 AreaSelection.set（唯一写入点）之前
    if body.index(guard) > body.index("AreaSelection.set(target, selection);"):
        return False
    # 旧写法（先 set、再让 put 截断）必须消失
    return (
        "merged.size() > CollectionCacheBlockEntity.MATCH_CAPACITY" in body
        and "已按上限截断" not in body
        and "AreaMarks.put(target, newArea, pendingAreaMarks);" in body
        and body.index("AreaMarks.put(target, newArea, pendingAreaMarks);")
        > body.index("AreaSelection.set(target, selection);")
    )


# ======================================================================
# ② 行为模型（与被测实现同一判定语义）
# ======================================================================

def minecraft_key(id_, nbt):
    return (id_, nbt)


def drain_hit_model(stored_entries, returned_nbt, encodes_with_same_context):
    """
    复刻「drain 按 (id, 组件 NBT) 查本机条目」：
      - returned_nbt = 外部管道拿到的 FluidStack 的组件 NBT（旧实现 = 空）；
      - encodes_with_same_context = drain 与 fill 是否同一编码上下文（旧实现 = 否）。
    返回是否命中本机条目（False ⇒ 静默回退网络）。
    """
    request = minecraft_key("minecraft:water", returned_nbt if encodes_with_same_context else None)
    return request in stored_entries


def merge_capacity_model(existing_ids, carried_ids):
    """复刻 REPLACE 合并：新几何标记在前，旧几何按 (类别, id) 去重后追加。"""
    merged = list(existing_ids)
    for entry in carried_ids:
        if entry not in merged:
            merged.append(entry)
    return len(merged), len(merged) > MATCH_CAPACITY


# ======================================================================
# ③ 正例
# ======================================================================

def group_base_keeper():
    print("\n=== ① 基础定量保持器：返回值带组件 + 同一编码上下文 + 严格回退 ===")
    check("①-1 QuantityKeeperBlockEntity.java 存在", os.path.isfile(KEEPER))
    if not os.path.isfile(KEEPER):
        return
    src = strip_comments(read(KEEPER))
    check("①-2 getFluidInTank 交出条目原文的组件补丁（toFluidStack(fluid, amount, nbt)）",
          handler_carries_components(src))
    check("①-3 drain(FluidStack) 与 fill 同一上下文编码组件（不再漏传 registryAccess）",
          drain_uses_same_context(src), "body=%s" % method_body(
              src, "public FluidStack drain(final FluidStack resource, final FluidAction action)")[:400])
    check("①-4 drainById 交还的仍是同一个组件补丁（toFluidStack(fluid, extracted, nbt)）",
          drain_result_carries_components(src, slot_based=False))
    check("①-5 toFluidStack 用 registryHint() 解码组件并真正写进 FluidStack",
          helper_decodes_with_hint(src, "private FluidStack toFluidStack("))
    check("①-6 网络回退要求「id + 组件 NBT 与直接标记逐字一致」",
          base_strict_network_fallback(src))
    check("①-7 fill / drain 两处都用 registryHint()（恰好 2 处，同一上下文）",
          fill_drain_same_context_count(src))
    check("①-8 旧写法（返回裸 new FluidStack(fluid, …)）已清零", no_bare_fluidstack(src))


def group_adv_keeper():
    print("\n=== ② 高级定量保持器：每个槽的 tank 同样带组件 ===")
    check("②-1 AdvancedQuantityKeeperBlockEntity.java 存在", os.path.isfile(ADV_KEEPER))
    if not os.path.isfile(ADV_KEEPER):
        return
    src = strip_comments(read(ADV_KEEPER))
    check("②-2 getFluidInTank 走 toStack（不再返回裸 FluidStack）",
          handler_carries_components(src))
    check("②-3 drain(FluidStack) 与 fill 同一上下文编码组件",
          drain_uses_same_context(src), "body=%s" % method_body(
              src, "public FluidStack drain(final FluidStack resource, final FluidAction action)")[:400])
    check("②-4 drainById 交还 stackOf(id, nbt, extracted)（带请求的组件补丁）",
          drain_result_carries_components(src, slot_based=True))
    check("②-5 stackOf 用 registryHint() 解码组件并真正写进 FluidStack",
          helper_decodes_with_hint(src, "private FluidStack stackOf("))
    check("②-6 网络回退同样带组件（stackOf(id, nbt, fromNetwork)）",
          "stackOf(id, nbt, fromNetwork)" in method_body(
              src, "public FluidStack drain(final FluidStack resource, final FluidAction action)"))
    check("②-7 fill / drain 两处都用 registryHint()（恰好 2 处，同一上下文）",
          fill_drain_same_context_count(src))
    check("②-8 旧写法（返回裸 new FluidStack(fluid, …)）已清零", no_bare_fluidstack(src))


def group_behavior_model():
    print("\n=== ③ 行为模型：带组件流体真的能取出来（旧写法真的取不出来）===")
    stored = {minecraft_key("minecraft:water", "patchA")}
    new_path = drain_hit_model(stored, "patchA", encodes_with_same_context=True)
    old_drop_components = drain_hit_model(stored, "", encodes_with_same_context=True)
    old_context_mismatch = drain_hit_model(stored, "patchA", encodes_with_same_context=False)
    check("③-1 新实现：getFluidInTank 交还 patchA ⇒ drain 按 (water, patchA) 命中本机条目", new_path is True)
    check("③-2 旧实现 A：返回值丢组件（请求按空 NBT）⇒ 查不到 ⇒ 静默回退网络（取不出来）",
          old_drop_components is False)
    check("③-3 旧实现 B：drain 与 fill 编码上下文不一致 ⇒ 键不同 ⇒ 查不到", old_context_mismatch is False)
    check("③-4 无组件流体（空补丁）仍然正常命中（空 == 空，不误伤普通流体）",
          drain_hit_model({minecraft_key("minecraft:water", "")}, "", True) is True)


def group_mark_capacity():
    print("\n=== ④ >288 条：数据层不再静默截断 + REPLACE 先拒绝再写 ===")
    check("④-1 AreaMarks.java / AreaSelectorEditPacket.java / AreaStripPanel.java 存在",
          os.path.isfile(AREA_MARKS) and os.path.isfile(SELECTOR_PACKET) and os.path.isfile(STRIP_PANEL))
    if not (os.path.isfile(AREA_MARKS) and os.path.isfile(SELECTOR_PACKET)):
        return
    marks = strip_comments(read(AREA_MARKS))
    check("④-2 AreaMarks.put：超过 288 条时打 WARN（保留前 N 条 + 丢了 N 条），且在任何写入之前",
          area_marks_put_warns(marks))
    check("④-3 AreaMarks.migrateByIndex：旧表某区域超过 288 条时打 WARN（迁移是一次性消费，不能静默）",
          area_marks_migrate_warns(marks))
    check("④-4 countNonEmpty 助手存在（空的 ghost 槽不算「丢配置」）",
          "private static int countNonEmpty(" in marks)

    sel = strip_comments(read(SELECTOR_PACKET))
    check("④-5 REJECT_MARK_OVERFLOW = 6（网络契约只增不改）",
          "public static final int REJECT_MARK_OVERFLOW = 6;" in sel)
    check("④-6 REPLACE：合并结果先算好，超上限在 AreaSelection.set 之前 reject + return",
          replace_rejects_before_write(sel))
    check("④-7 mergeAreaMarks 是纯函数（先去空、新几何在前、旧几何按 (类别, id) 去重追加）",
          "private static List<MarkerEntry> mergeAreaMarks(" in sel
          and "kept.id().equals(entry.id())" in method_body(sel, "private static List<MarkerEntry> mergeAreaMarks(")
          and "kept.fluid() == entry.fluid()" in method_body(sel, "private static List<MarkerEntry> mergeAreaMarks("))
    check("④-8 拒绝原因有客户端文案通道（rejectMessageKey + AreaStripPanel 映射 + zh/en 两份键）",
          "REJECT_MARK_OVERFLOW -> \"gui.rs_create_compat.area_selector.err.reject.mark_overflow\"" in sel
          and "REJECT_MARK_OVERFLOW" in read(STRIP_PANEL)
          and "area_selector.err.reject.mark_overflow" in read(os.path.join(LANG_DIR, "zh_cn.json"))
          and "area_selector.err.reject.mark_overflow" in read(os.path.join(LANG_DIR, "en_us.json")))

    merged_300, reject_300 = merge_capacity_model(list(range(200)), list(range(200, 300)))
    merged_280, reject_280 = merge_capacity_model(list(range(200)), list(range(200, 280)))
    merged_288, reject_288 = merge_capacity_model(list(range(200)), list(range(200, 288)))
    check("④-9 模型：200+100=300 ⇒ 拒绝；200+80=280 ⇒ 放行；恰好 288 ⇒ 放行",
          merged_300 == 300 and reject_300 is True
          and merged_280 == 280 and reject_280 is False
          and merged_288 == 288 and reject_288 is False)
    dup_merged, dup_reject = merge_capacity_model(list(range(288)), list(range(288)))
    check("④-10 模型：两块地标记完全相同 ⇒ 去重后 288 条 ⇒ 放行（不会因为「相加」误拒）",
          dup_merged == 288 and dup_reject is False)


# ======================================================================
# ⑤ 反例自证
# ======================================================================

def group_counter_examples():
    print("\n=== ⑤ 反例自证：旧写法必须被判负 ===")

    old_base = (
        "private FluidStack toFluidStack(final Fluid fluid, final long amount, final CompoundTag nbt) {\n"
        "    return new FluidStack(fluid, (int) Math.min(Integer.MAX_VALUE, amount));\n"
        "}\n"
        "public FluidStack drain(final FluidStack resource, final FluidAction action) {\n"
        "    final CompoundTag nbt = MarkerEntry.encodeComponents(resource.getComponentsPatch());\n"
        "    return new FluidStack(resource.getFluid(), (int) Math.min(Integer.MAX_VALUE, 1));\n"
        "}\n"
        "public FluidStack getFluidInTank(final int tank) {\n"
        "    final Fluid fluid = BuiltInRegistries.FLUID.get(entry.id());\n"
        "    return new FluidStack(fluid, (int) Math.min(Integer.MAX_VALUE, entry.amount()));\n"
        "}\n"
    )
    check("⑤-1 旧基础版（drain 漏上下文 + 返回裸 FluidStack）⇒ ① 判据判负",
          not handler_carries_components(old_base)
          and not drain_uses_same_context(old_base)
          and not no_bare_fluidstack(old_base))

    old_adv = (
        "private FluidStack toStack(final MultiFluidCache.Entry entry) {\n"
        "    final Fluid fluid = BuiltInRegistries.FLUID.get(entry.id());\n"
        "    return new FluidStack(fluid, (int) Math.min(Integer.MAX_VALUE, entry.amount()));\n"
        "}\n"
    )
    check("⑤-2 旧高级版（toStack 丢组件）⇒ ②-5 / ②-8 判据判负",
          not helper_decodes_with_hint(old_adv, "private FluidStack toStack(")
          and not no_bare_fluidstack(old_adv))

    old_put = (
        "public static void put(final ItemStack stack, final Area area, final List<MarkerEntry> entries) {\n"
        "    final List<MarkerEntry> clean = sanitizeEntries(entries);\n"
        "    update(stack, root -> { });\n"
        "}\n"
    )
    check("⑤-3 旧 AreaMarks.put（截断且一个字都不打）⇒ ④-2 判据判负", not area_marks_put_warns(old_put))

    old_replace = (
        "public static void handle(final AreaSelectorEditPacket packet, final ServerPlayer player) {\n"
        "    AreaSelection.set(target, selection);\n"
        "    if (merged.size() > CollectionCacheBlockEntity.MATCH_CAPACITY) {\n"
        "        LOGGER.warn(\"已按上限截断\");\n"
        "    }\n"
        "    AreaMarks.put(target, newArea, merged);\n"
        "}\n"
    )
    check("⑤-4 旧 REPLACE（先 set、再 WARN + 截断写入）⇒ ④-6 判据判负",
          not replace_rejects_before_write(old_replace))

    ok_sel = strip_comments(read(SELECTOR_PACKET))
    broken_sel = ok_sel.replace(
        "if (merged.size() > CollectionCacheBlockEntity.MATCH_CAPACITY) {", "if (false) {", 1)
    check("⑤-5 去掉容量闸门 ⇒ ④-6 判据判负（又变成静默丢配置）",
          broken_sel != ok_sel and not replace_rejects_before_write(broken_sel))

    ok_marks = strip_comments(read(AREA_MARKS))
    broken_marks = ok_marks.replace(
        "if (offered > CollectionCacheBlockEntity.MATCH_CAPACITY) {", "if (false) {", 1)
    check("⑤-6 去掉 AreaMarks.put 的截断告警 ⇒ ④-2 判据判负",
          broken_marks != ok_marks and not area_marks_put_warns(broken_marks))


def main():
    print("第 139 轮自检：带组件流体 drain/fill 对称 + >288 条不再静默截断")
    group_base_keeper()
    group_adv_keeper()
    group_behavior_model()
    group_mark_capacity()
    group_counter_examples()
    print("\n结果：%d 通过 / %d 失败" % (_passed, _failed))
    if _failed:
        print("失败项：")
        for name, detail in _failures:
            print("  - %s" % name)
        return 1
    print("全部通过（组件补丁 / 同一编码上下文 / 严格回退 / 拒绝写入 / 截断告警 + 反例自证）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
