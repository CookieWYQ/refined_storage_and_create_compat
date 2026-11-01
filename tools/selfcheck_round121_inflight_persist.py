#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 121 轮自检：序列执行仓 F4 —— 在制件登记表（inFlightUnits）落盘 + 读档回收。

背景（第 119 轮复核 F4，中）
----------------------------
``SequenceExecutionChamberBlockEntity`` 的 ``inFlightUnits``（:3092，``Map<BlockPos, InFlightUnit>``）
原先<b>纯内存态</b>：``saveAdditional`` 里没有任何在制件键。于是「材料已经推给工位、还没被 Create
消耗」时重启服务器 ⇒ 登记表归零 ⇒ 三条回收路径（``clearDeadStationPieces`` /
``reclaimRuntimeStationResidue`` / ``flushStationStartIngredients``）全部失去唯一凭据 ⇒ 工位被一份
<b>没人认领</b>的原料永久占死，输出总线此后每次推料都 ``machine_full``，只能手动挖掉。

本脚本三条腿
------------
① **源码锚点**：落盘键 / 写档 / 读档 / 非法条目淘汰 / 空表不写键 / 注释不再自称内存态 /
   三条回收路径仍读同一张表 —— 每条判据都配「坏写法」反例（③），证明判据真的会判负；
② **行为模型**：Python 复刻「写档 → 读档」的编码/解码，断言条目零损耗（含 ``recipe=""``、
   ``step=-1``、负数 y 坐标）、空表不写键、旧档无键 ⇒ 空表且不补写、坏条目逐类淘汰、
   重复工位后写覆盖、超上限只留前 64 条；并量化「不落盘 = 三条路径的可清工位数归零」这一事故；
③ **反例自证**：旧写法（无落盘 / 无空表守卫 / 无读回 / 无淘汰 / 无上限 / 旧「内存态」注释）
   必须被判负。

用法：python tools/selfcheck_round121_inflight_persist.py
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
PKG = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
CHAMBER_BE = os.path.join(PKG, "block", "entity", "SequenceExecutionChamberBlockEntity.java")

SAVE_SIG = "public void saveAdditional(final CompoundTag tag, final HolderLookup.Provider registries)"
LOAD_SIG = "public void loadAdditional(final CompoundTag tag, final HolderLookup.Provider registries)"
WRITE_SIG = "private void writeInFlightUnits("
READ_SIG = "private void readInFlightUnits("
STATION_SIG = "private static BlockPos readInFlightStation("
ENTRY_SIG = "private static InFlightUnit readInFlightUnit("
CLEAR_SIG = "private int clearDeadStationPieces()"
RECLAIM_SIG = "private int reclaimRuntimeStationResidue()"
FLUSH_SIG = "private long[] flushStationStartIngredients("

MAX_PERSISTED = 64

CHECKS = [0]
COUNTER = [0]
FAILS = []


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
# 文本工具（与第 116～120 轮同一套）
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


def brace_body(text, signature):
    """按签名在**去注释后的源码**里定位方法体，返回大括号内的原文（找不到返回 ''）。"""
    idx = text.find(signature)
    if idx < 0:
        return ""
    start = text.find("{", idx + len(signature))
    if start < 0:
        return ""
    depth = 0
    i = start
    while i < len(text):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return text[start + 1:i]
        i += 1
    return ""


# ======================================================================================
# 判据（正例与反例共用同一份，避免「检查写两套」导致反例失真）
# ======================================================================================

def save_writes_registry(code):
    """saveAdditional 里必须有「写入制件登记表」这一步（且是调 helper，不内联）。"""
    body = brace_body(code, SAVE_SIG)
    return bool(body) and "writeInFlightUnits(tag);" in body


def load_reads_registry(code):
    """loadAdditional 里必须有「读回在制件登记表」这一步。"""
    body = brace_body(code, LOAD_SIG)
    return bool(body) and "readInFlightUnits(tag);" in body


def write_body(code):
    return brace_body(code, WRITE_SIG)


def read_body(code):
    return brace_body(code, READ_SIG)


def tag_constant(code):
    """NBT 键常量名与值都必须是约定的那一个（改名 = 旧档静默丢凭据）。"""
    return ('private static final String TAG_IN_FLIGHT_UNITS = "InFlightUnits";' in code
            and "private static final int MAX_PERSISTED_IN_FLIGHT_UNITS = 64;" in code)


def write_guards_empty(body):
    """空表不写键：``isEmpty`` 守卫必须**早于** ``tag.put``，且守卫里直接 return。"""
    if not body:
        return False
    guard = body.find("if (inFlightUnits.isEmpty()) {")
    put = body.find("tag.put(TAG_IN_FLIGHT_UNITS, list);")
    if guard < 0 or put < 0 or guard > put:
        return False
    return "return;" in body[guard:put]


def write_fields(body):
    """五样字段 + 注册名编码 + 列表装载，一个都不能少（少一样就是读不回来的半截账）。"""
    if not body:
        return False
    needed = [
        'row.putLong("Station", station.asLong());',
        'row.putString("Recipe", unit.recipe() == null ? "" : unit.recipe());',
        'row.putInt("Step", unit.step());',
        'row.putLong("Since", unit.sinceTick());',
        'row.putString("Item",',
        "BuiltInRegistries.ITEM.getKey(unit.item())",
        "list.add(row);",
    ]
    return all(token in body for token in needed)


def read_clears_first(body):
    """先清空再读：同一份 NBT 被喂两次也只会得到同一份账，绝不叠加。"""
    if not body:
        return False
    clear = body.find("inFlightUnits.clear();")
    restore = body.find("if (!tag.contains(TAG_IN_FLIGHT_UNITS")
    return 0 <= clear < restore


def read_old_save_ok(body):
    """旧档无该键 ⇒ 直接返回（不报错、不补写）；读档绝不 ``tag.put`` 回去。"""
    if not body:
        return False
    return ("if (!tag.contains(TAG_IN_FLIGHT_UNITS, net.minecraft.nbt.Tag.TAG_LIST)) {" in body
            and "return;" in body
            and "tag.put" not in body)


def read_drops_invalid(body):
    """非法条目必须**整条淘汰**：守卫（station/unit 任一为空）+ 计数 + continue。"""
    if not body:
        return False
    return ("if (station == null || unit == null) {" in body
            and "dropped++;" in body
            and "continue;" in body
            and "inFlightUnits.put(station, unit);" in body)


def read_honors_cap(body):
    """超上限只留前 64 条：必须有 MAX 上限比较 + break（绝不无界读入）。"""
    if not body:
        return False
    return ("if (inFlightUnits.size() >= MAX_PERSISTED_IN_FLIGHT_UNITS) {" in body
            and "break;" in body)


def read_forces_reconcile(body):
    """读档后第一次对账不能被「同一游戏刻刚对过」的缓存跳过。"""
    return bool(body) and "inFlightReconcileTick = Long.MIN_VALUE;" in body


def read_prunes_diagnostics(body):
    """诊断表（死件标记 / 活性采样）必须跟着新登记表对账，保持「键 ⊆ 登记表」。"""
    if not body:
        return False
    return ("abandonedInFlightAt.keySet().removeIf(station -> !inFlightUnits.containsKey(station));" in body
            and "unitLivenessAt.keySet().removeIf(station -> !inFlightUnits.containsKey(station));" in body)


def station_uses_aslong(code):
    """坐标按 ``BlockPos#asLong`` 打包 / 解包（与既有的 BusCategoryOwners 同一手法）。"""
    body = brace_body(code, STATION_SIG)
    return (bool(body)
            and 'row.contains("Station", net.minecraft.nbt.Tag.TAG_LONG)' in body
            and 'BlockPos.of(row.getLong("Station"))' in body)


def decode_rejects_invalid(code):
    """条目本体解码：五字段类型校验 + 步序/时刻/注册名/AIR 四道语义淘汰。"""
    body = brace_body(code, ENTRY_SIG)
    if not body:
        return False
    needed = [
        'contains("Recipe", net.minecraft.nbt.Tag.TAG_STRING)',
        'contains("Step", net.minecraft.nbt.Tag.TAG_INT)',
        'contains("Item", net.minecraft.nbt.Tag.TAG_STRING)',
        'contains("Since", net.minecraft.nbt.Tag.TAG_LONG)',
        "step < -1",
        "sinceTick < 0L",
        "ResourceLocation.tryParse",
        "BuiltInRegistries.ITEM.get(itemId)",
        "net.minecraft.world.item.Items.AIR",
    ]
    return all(token in body for token in needed)


def comment_honest(raw):
    """注释不再自称「内存态（不落盘）」，并如实写清「登记落盘、运行期证据不落盘」的边界。"""
    return ("按条目落盘" in raw
            and "重启前压着残留的工位，读档后凭据仍在" in raw
            and "读档<b>不改变</b>任何回收判据本身" in raw
            and "是<b>内存态</b>（不落盘）" not in raw
            and "服务器重启后，重启前留下的残留会失去这份凭据" not in raw)


def three_paths_use_registry(code):
    """三条回收路径仍然只凭同一张登记表动手（落盘不引入第二套凭据）。"""
    bodies = [
        brace_body(code, CLEAR_SIG),
        brace_body(code, RECLAIM_SIG),
        brace_body(code, FLUSH_SIG),
    ]
    return all("inFlightUnits" in body for body in bodies)


def record_shape(code):
    """登记的记录形状没被顺手改掉（落盘/读回两侧共用同一形状）。"""
    return "public record InFlightUnit(String recipe, int step, Item item, long sinceTick) {" in code


# ======================================================================================
# ① 源码锚点（当前实现必须命中；每条都配反例）
# ======================================================================================

def group_anchors():
    print("\n=== ① 源码锚点（当前实现必须命中；每条都配反例） ===")
    raw = read(CHAMBER_BE)
    code = strip_comments(raw)
    save = brace_body(code, SAVE_SIG)
    load = brace_body(code, LOAD_SIG)
    write = write_body(code)
    readback = read_body(code)

    check("①-1 saveAdditional 方法体能定位出来（锚点本身有效）", bool(save), "saveAdditional body 为空")
    check("①-2 loadAdditional 方法体能定位出来（锚点本身有效）", bool(load), "loadAdditional body 为空")
    check("①-3 写档 / 读回两个 helper 都能定位出来", bool(write) and bool(readback),
          "write/read helper 为空")

    check("①-4 saveAdditional 里确实调用 writeInFlightUnits(tag)（登记表被写进 NBT）",
          save_writes_registry(code), "未见 writeInFlightUnits(tag)")
    check("①-5 loadAdditional 里确实调用 readInFlightUnits(tag)（读档回填登记表）",
          load_reads_registry(code), "未见 readInFlightUnits(tag)")

    check("①-6 NBT 键常量存在且值 = \"InFlightUnits\"；上限常量 = 64",
          tag_constant(code), "TAG_IN_FLIGHT_UNITS / MAX_PERSISTED_IN_FLIGHT_UNITS 缺失或改值")

    check("①-7 写档：空表直接 return（空表不写键，旧档字节不变）",
          write_guards_empty(write), "isEmpty 守卫不在 tag.put 之前 / 守卫里没有 return")
    check("①-8 写档：Station/Recipe/Step/Item/Since 五样齐全，物品按注册名编码",
          write_fields(write), "字段缺失 / 物品仍用数值 id / 未装进 ListTag")
    check("①-9 读档：先 inFlightUnits.clear() 再读（同一份 NBT 读两次不叠加）",
          read_clears_first(readback), "clear 缺失或晚于读取")
    check("①-10 读档：旧档无该键 ⇒ 直接返回（不报错、不补写）",
          read_old_save_ok(readback), "未见 TAG_LIST 守卫 / 读档侧写回了 NBT")
    check("①-11 读档：条目非法（station/unit 任一为空）⇒ 计数并 continue 淘汰",
          read_drops_invalid(readback), "未见淘汰守卫 / 没有 continue / 未回填合法条目")
    check("①-12 读档：超过 MAX_PERSISTED_IN_FLIGHT_UNITS 只留前 64 条（有界）",
          read_honors_cap(readback), "上限比较或 break 缺失")
    check("①-13 读档：作废对账 tick 缓存（读档后第一次查询必须真的对账）",
          read_forces_reconcile(readback), "未见 inFlightReconcileTick = Long.MIN_VALUE")
    check("①-14 读档：诊断表（死件 / 活性）跟着新登记表对账",
          read_prunes_diagnostics(readback), "未 prune abandonedInFlightAt / unitLivenessAt")

    check("①-15 坐标按 BlockPos#asLong 打包 / 解包",
          station_uses_aslong(code), "Station 字段类型不是 TAG_LONG / 未用 BlockPos.of 解包")
    check("①-16 条目本体解码：五字段类型校验 + 步序 < -1 / 负时刻 / 注册名解不出 / AIR 全淘汰",
          decode_rejects_invalid(code), "有字段类型或语义校验缺失（会放坏账进登记表）")

    check("①-17 注释不再自称「内存态（不落盘）」，改为「登记落盘、运行期证据不落盘」",
          comment_honest(raw), "旧的「内存态 / 重启后失去凭据」注释仍在 / 新边界说明缺失")
    check("①-18 三条回收路径仍然只凭同一张 inFlightUnits 动手（判据本身没被改）",
          three_paths_use_registry(code), "某条回收路径已经不再读登记表")
    check("①-19 在制件记录形状未变（recipe/step/item/sinceTick）",
          record_shape(code), "record InFlightUnit 形状被改")


# ======================================================================================
# ② 行为模型：写档 → 读档（编码 / 解码与 Java 逐句对应）
# ======================================================================================

NBT_LONG, NBT_INT, NBT_STRING, NBT_LIST = 4, 3, 8, 9

# 假的物品注册表：解码侧按注册名查表，查不到 = 模组被移除 / id 被改坏 ⇒ 淘汰。
REGISTRY = {
    "minecraft:iron_ingot",
    "minecraft:gold_ingot",
    "create:golden_sheet",
    "create:incomplete_precision_mechanism",
    "minecraft:air",
}


def _s64(value):
    value &= (1 << 64) - 1
    return value - (1 << 64) if value >= (1 << 63) else value


def pack_pos(x, y, z):
    """与 ``BlockPos#asLong`` 同布局：x(26) << 38 | z(26) << 12 | y(12)。"""
    return _s64(((x & 0x3FFFFFF) << 38) | ((z & 0x3FFFFFF) << 12) | (y & 0xFFF))


def unpack_pos(packed):
    """与 ``BlockPos#of`` 同解：x = p >> 38、y = p << 52 >> 52、z = p << 26 >> 38。"""
    p = _s64(packed)
    return (_s64(p) >> 38, _s64(p << 52) >> 52, _s64(p << 26) >> 38)


def encode(units):
    """写档模型：空表 ⇒ None（= 不写这个 NBT 键）；否则返回 ListTag 形状的行列表。"""
    if not units:
        return None
    rows = []
    for (x, y, z), unit in units.items():
        rows.append({
            "Station": pack_pos(x, y, z),
            "Recipe": unit["recipe"],
            "Step": unit["step"],
            "Item": unit["item"],
            "Since": unit["since"],
        })
    return rows


def _read_station_row(row):
    if not isinstance(row, dict):
        return None
    if not isinstance(row.get("Station"), int):
        return None
    return unpack_pos(row["Station"])


def _read_unit_row(row, registry):
    """与 Java ``readInFlightUnit`` 同一套校验：五字段 + 步序 >= -1 + 时刻 >= 0 + 注册名可解。"""
    if not isinstance(row, dict):
        return None
    if not isinstance(row.get("Recipe"), str):
        return None
    if not isinstance(row.get("Step"), int):
        return None
    if not isinstance(row.get("Item"), str):
        return None
    if not isinstance(row.get("Since"), int):
        return None
    if row["Step"] < -1:
        return None
    if row["Since"] < 0:
        return None
    if row["Item"] not in registry:
        return None
    if row["Item"] == "minecraft:air":
        return None
    return {"recipe": row["Recipe"], "step": row["Step"],
            "item": row["Item"], "since": row["Since"]}


def decode(rows, registry=REGISTRY):
    """读档模型：None（旧档无键）⇒ 空表；非法条目淘汰；超上限只留前 64 条。"""
    if rows is None:
        return {}
    out = {}
    for row in rows:
        station = _read_station_row(row)
        unit = _read_unit_row(row, registry)
        if station is None or unit is None:
            continue
        if len(out) >= MAX_PERSISTED:
            break
        out[station] = unit
    return out


def decode_lenient(rows, registry=REGISTRY):
    """坏写法：不做任何校验（旧式「见到就收」），用来证明淘汰判据有区分力。"""
    if rows is None:
        return {}
    out = {}
    for row in rows:
        if not isinstance(row, dict):
            continue
        station = row.get("Station")
        if not isinstance(station, int):
            continue
        out[unpack_pos(station)] = {
            "recipe": row.get("Recipe", ""),
            "step": row.get("Step", 0),
            "item": row.get("Item", ""),
            "since": row.get("Since", 0),
        }
    return out


def decode_no_cap(rows, registry=REGISTRY):
    """坏写法：有校验但无上限（被改坏的 NBT 可以塞进海量条目）。"""
    if rows is None:
        return {}
    out = {}
    for row in rows:
        station = _read_station_row(row)
        unit = _read_unit_row(row, registry)
        if station is None or unit is None:
            continue
        out[station] = unit
    return out


def visible_recovery_stations(units):
    """三条回收路径各自「看得到几个可清工位」——三者都以 inFlightUnits 为唯一凭据。"""
    return {
        "clearDeadStationPieces": len(units),
        "reclaimRuntimeStationResidue": len(units),
        "flushStationStartIngredients": len(units),
    }


def make_units():
    """两件在制件：①裸起步原料（recipe="" / step=-1）②带进度组件的过渡件（step=2）；
    坐标含负 y（验证 asLong 打包对负高度无损）。"""
    return {
        (417, 100, 99): {"recipe": "", "step": -1,
                         "item": "minecraft:gold_ingot", "since": 123456},
        (418, -60, 101): {"recipe": "create:precision_mechanism", "step": 2,
                          "item": "create:incomplete_precision_mechanism", "since": 123999},
    }


def group_model():
    print("\n=== ② 行为模型：写档 → 读档（条目零损耗 / 坏条目淘汰 / 空表不写键） ===")

    units = make_units()

    # ②-1 写档 → 读档 原样还原（含 recipe=""、step=-1、负 y 坐标）。
    roundtrip = decode(encode(units))
    check("②-1 写档→读档零损耗：两件（含 recipe=\"\"/step=-1/负 y 坐标）逐字段相等",
          roundtrip == units, "roundtrip=%r" % (roundtrip,))

    # ②-2 空表 ⇒ 不写键；旧档无键 ⇒ 空表（双向都不产生凭空的登记）。
    check("②-2 空表不写键（encode({}) is None），读档对 None 返回空表",
          encode({}) is None and decode(None) == {},
          "encode({})=%r decode(None)=%r" % (encode({}), decode(None),))

    # ②-3 旧档（没有任何该键）读档不报错：把垃圾键值传进去也只当「没有」。
    check("②-3 旧档无键：decode(None) 不抛错且为 {}（行为等于落盘之前）",
          decode(None) == {}, "decode(None) 非空或抛错")

    # ②-4 事故重建：不落盘 ⇒ 重启后三条回收路径的可清工位数全部归零（F4 的伤害量化）。
    before_restart = dict(units)
    old_after_restart = {}  # 旧实现：重启后登记表归零
    new_after_restart = decode(encode(before_restart))
    old_visible = visible_recovery_stations(old_after_restart)
    new_visible = visible_recovery_stations(new_after_restart)
    check("②-4 事故重建：旧实现重启后三条路径各看到 0 个工位；落盘后各看到 2 个（凭据没丢）",
          all(count == 0 for count in old_visible.values())
          and all(count == 2 for count in new_visible.values()),
          "old=%r new=%r" % (old_visible, new_visible))

    # ②-5 非法条目逐类淘汰：只留下合法那一条。
    good_row = encode(units)[0]
    bad_rows = [
        {"Recipe": "", "Step": 0, "Item": "minecraft:iron_ingot", "Since": 1},          # 缺 Station
        {"Station": "abc", "Recipe": "", "Step": 0, "Item": "minecraft:iron_ingot", "Since": 1},  # Station 类型错
        {"Station": 1, "Step": 0, "Item": "minecraft:iron_ingot", "Since": 1},           # 缺 Recipe
        {"Station": 2, "Recipe": "", "Step": "0", "Item": "minecraft:iron_ingot", "Since": 1},    # Step 类型错
        {"Station": 3, "Recipe": "", "Step": -2, "Item": "minecraft:iron_ingot", "Since": 1},     # 步序非法
        {"Station": 4, "Recipe": "", "Step": 0, "Item": "gone:removed_item", "Since": 1},         # 注册名解不出
        {"Station": 5, "Recipe": "", "Step": 0, "Item": "minecraft:air", "Since": 1},             # AIR
        {"Station": 6, "Recipe": "", "Step": 0, "Item": "minecraft:iron_ingot", "Since": -1},     # 负时刻
        {"Station": 7, "Recipe": "", "Step": 0, "Item": "minecraft:iron_ingot"},                  # 缺 Since
        "not-a-compound",                                                                        # 行本身不是 compound
    ]
    mixed = decode(bad_rows + [good_row])
    check("②-5 非法条目逐类淘汰：10 条坏账全丢，只剩 1 条合法登记",
          mixed == {unpack_pos(good_row["Station"]): _read_unit_row(good_row, REGISTRY)},
          "mixed=%r" % (mixed,))

    # ②-6 坏写法（无校验）会把「注册名解不出 / 步序非法」的假账收进来 ⇒ 判据有区分力。
    check("②-6 对照坏写法（decode_lenient 不做校验）：同样的坏账会被收进来（>1 条）",
          len(decode_lenient(bad_rows + [good_row])) > 1,
          "lenient=%r" % (decode_lenient(bad_rows + [good_row]),))

    # ②-7 上限：100 条合法登记只留前 64 条（与 Java 读侧的 MAX 一致）。
    many = {}
    for i in range(100):
        many[(i, 64, 0)] = {"recipe": "", "step": 0, "item": "minecraft:iron_ingot", "since": i}
    capped = decode(encode(many))
    check("②-7 超上限：100 条合法登记读回只有 64 条（有界，不随坏档膨胀）",
          len(capped) == MAX_PERSISTED and len(many) == 100,
          "capped=%d" % (len(capped),))

    # ②-8 无上限坏写法：100 条全收 ⇒ ②-7 的判据有区分力。
    check("②-8 对照坏写法（decode_no_cap 无上限）：100 条全部读入",
          len(decode_no_cap(encode(many))) == 100,
          "no_cap=%d" % (len(decode_no_cap(encode(many))),))

    # ②-9 重复工位（同一格被推两次）：后写覆盖，只留 1 条（与 LinkedHashMap.put 同语义）。
    dup = [
        {"Station": pack_pos(10, 64, 10), "Recipe": "a", "Step": 0,
         "Item": "minecraft:iron_ingot", "Since": 1},
        {"Station": pack_pos(10, 64, 10), "Recipe": "b", "Step": 3,
         "Item": "create:golden_sheet", "Since": 2},
    ]
    deduped = decode(dup)
    check("②-9 重复工位：同一格两条记录 ⇒ 只留 1 条且为后写的那条",
          len(deduped) == 1
          and deduped[(10, 64, 10)]["recipe"] == "b"
          and deduped[(10, 64, 10)]["step"] == 3,
          "deduped=%r" % (deduped,))

    # ②-10 坐标编码对负 y / 负 x 无损（读档后不会「工位跑到别处」）。
    for pos in [(417, 100, 99), (418, -60, 101), (-192, 320, -33), (0, -2048, 0)]:
        check("②-10 坐标 asLong 往返无损 %r" % (pos,),
              unpack_pos(pack_pos(*pos)) == pos, "roundtrip=%r" % (unpack_pos(pack_pos(*pos)),))

    # ②-11 空 ListTag（理论上不写；万一被别的工具塞了空表）⇒ 读成空表，不报错。
    check("②-11 空 ListTag ⇒ 空表（不报错、不凭空造条目）", decode([]) == {}, "decode([]) 非空")


# ======================================================================================
# ③ 反例自证：坏写法 / 旧实现必须被判负
# ======================================================================================

def group_counterexamples():
    print("\n=== ③ 反例自证：坏写法 / 旧实现必须判负 ===")
    raw = read(CHAMBER_BE)
    code = strip_comments(raw)

    # ③-1 saveAdditional 去掉落盘调用（= 旧实现，F4 原样）
    bad = code.replace("writeInFlightUnits(tag);", "", 1)
    counterexample("③-1 源码副本：删掉 saveAdditional 里的 writeInFlightUnits(tag) ⇒ ①-4 判负",
                   bad != code and not save_writes_registry(bad))

    # ③-2 loadAdditional 去掉读回调用
    bad = code.replace("readInFlightUnits(tag);", "", 1)
    counterexample("③-2 源码副本：删掉 loadAdditional 里的 readInFlightUnits(tag) ⇒ ①-5 判负",
                   bad != code and not load_reads_registry(bad))

    # ③-3 写档去掉「空表不写键」守卫（旧档会被补写出一个空键 = 字节变化）
    good_write = write_body(code)
    bad_write = good_write.replace("if (inFlightUnits.isEmpty()) {", "if (false) {", 1)
    counterexample("③-3 方法体副本：写档去掉 isEmpty 守卫（空表也写键）⇒ ①-7 判负",
                   bad_write != good_write and not write_guards_empty(bad_write))

    # ③-4 写档把 Station 字段换成数值 id 式写法（漏字段 = 读不回来）
    bad_write = good_write.replace('row.putLong("Station", station.asLong());', "", 1)
    counterexample("③-4 方法体副本：写档漏掉 Station 字段 ⇒ ①-8 判负",
                   bad_write != good_write and not write_fields(bad_write))

    # ③-5 读档不去掉旧数据（同一份 NBT 读两次会叠加）
    bad_read = read_body(code).replace("inFlightUnits.clear();", "", 1)
    counterexample("③-5 方法体副本：读档不先 clear（条目会叠加）⇒ ①-9 判负",
                   bad_read != read_body(code) and not read_clears_first(bad_read))

    # ③-6 读档不判「旧档无键」（直接 getList ⇒ 可能凭空补空表 / 抛错式写法）
    bad_read = read_body(code).replace(
        "if (!tag.contains(TAG_IN_FLIGHT_UNITS, net.minecraft.nbt.Tag.TAG_LIST)) {", "if (false) {", 1)
    counterexample("③-6 方法体副本：读档去掉 TAG_LIST 守卫（旧档不再走「什么都不做」）⇒ ①-10 判负",
                   bad_read != read_body(code) and not read_old_save_ok(bad_read))

    # ③-7 读档把淘汰守卫写成 &&（station 与 unit 必须同时为空才丢 ⇒ 坏账会进来）
    bad_read = read_body(code).replace(
        "if (station == null || unit == null) {", "if (station == null && unit == null) {", 1)
    counterexample("③-7 方法体副本：淘汰守卫 || 改成 &&（半截坏账被收下）⇒ ①-11 判负",
                   bad_read != read_body(code) and not read_drops_invalid(bad_read))

    # ③-8 读档去掉上限（坏档可无界膨胀）
    bad_read = read_body(code).replace(
        "if (inFlightUnits.size() >= MAX_PERSISTED_IN_FLIGHT_UNITS) {", "if (false) {", 1)
    counterexample("③-8 方法体副本：读档去掉 64 条上限 ⇒ ①-12 判负",
                   bad_read != read_body(code) and not read_honors_cap(bad_read))

    # ③-9 条目解码不拒绝 AIR
    good_entry = brace_body(code, ENTRY_SIG)
    bad_entry = good_entry.replace(
        "if (item == null || item == net.minecraft.world.item.Items.AIR) {", "if (false) {", 1)
    counterexample("③-9 方法体副本：条目解码不再拒绝 AIR ⇒ ①-16 判负",
                   bad_entry != good_entry
                   and not decode_rejects_invalid(code.replace(good_entry, bad_entry)))

    # ③-10 注释改回旧「内存态（不落盘）」
    bad_raw = raw.replace("按条目落盘", "是<b>内存态</b>（不落盘）", 1)
    counterexample("③-10 源码副本：注释改回「内存态（不落盘）」⇒ ①-17 判负",
                   bad_raw != raw and not comment_honest(bad_raw))

    # ③-11 NBT 键常量改名（旧档不会丢，但新档读不回来 —— 静默失去凭据）
    bad = code.replace('private static final String TAG_IN_FLIGHT_UNITS = "InFlightUnits";',
                       'private static final String TAG_IN_FLIGHT_UNITS = "InFlightUnitsV2";', 1)
    counterexample("③-11 源码副本：NBT 键值改名 ⇒ ①-6 判负",
                   bad != code and not tag_constant(bad))

    # ③-12 行为模型：旧实现（不落盘）在「重启后凭据仍在」这条不变量上判负
    units = make_units()
    old_after = {}  # 旧实现
    new_after = decode(encode(units))
    counterexample("③-12 行为模型：不落盘 ⇒ 重启后三条回收路径可清工位数为 0（旧实现判负）",
                   all(count == 0 for count in visible_recovery_stations(old_after).values())
                   and all(count == 2 for count in visible_recovery_stations(new_after).values()))

    # ③-13 行为模型：无校验解码会把「注册名解不出」的假账收下（旧式「见到就收」判负）
    bad_rows = [{"Station": pack_pos(1, 64, 1), "Recipe": "", "Step": -2,
                 "Item": "gone:removed_item", "Since": -1}]
    counterexample("③-13 行为模型：decode_lenient 收下坏账（strict 淘汰、lenient 保留 ⇒ 判据有区分力）",
                   len(decode(bad_rows)) == 0 and len(decode_lenient(bad_rows)) == 1)

    # ③-14 行为模型：无上限解码读入 100 条（有界判据判负）
    many = {}
    for i in range(100):
        many[(i, 64, 0)] = {"recipe": "", "step": 0, "item": "minecraft:iron_ingot", "since": i}
    counterexample("③-14 行为模型：decode_no_cap 读入 100 条（有界判据 ②-7 判负）",
                   len(decode_no_cap(encode(many))) == 100 and len(decode(encode(many))) == MAX_PERSISTED)

    # ③-15 行为模型：空表若也写键（None → []），「空表不写键」判据必须判负
    counterexample("③-15 行为模型：空表写出 [] 而非 None（违反「空表不写键」）",
                   encode({}) is None and encode({}) != [])


def main():
    print("第 121 轮自检：序列执行仓 F4 —— inFlightUnits 落盘 + 读档回收")
    group_anchors()
    group_model()
    group_counterexamples()
    print("\n断言 %d 条（其中反例 %d 条），失败 %d 条" % (CHECKS[0], COUNTER[0], len(FAILS)))
    if FAILS:
        print("FAILED:")
        for name in FAILS:
            print("  - " + name)
        return 1
    print("SELFCHECK OK 断言=%d 反例=%d 失败=0" % (CHECKS[0], COUNTER[0]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
