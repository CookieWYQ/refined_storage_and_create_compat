# -*- coding: utf-8 -*-
"""生成本轮（归流缓存仓 + 序列装配回流总线）的语言键片段 tools/lang_frag_cc.json。

为什么用脚本生成：语言键属于"有形式要求的 json"，一律由脚本产出（不手写整份 json），
本脚本只产出<b>片段</b>，再由 tools/apply_lang_frag.py 幂等合并进中英语言文件。

片段语义：
  {"<键>": {"en": "...", "zh": "..."}}  → 新增 / 覆盖
  {"<键>": {"delete": true}}            → 中英同时删除（本模块范围内不再需要的旧键）

用法：python tools/gen_lang_frag_cc.py
"""
import json
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "tools", "lang_frag_cc.json")

# ==================== 新增 / 覆盖 ====================
UPSERT = {
    # ---- 归流缓存仓：匹配条目的规则说明（合并进物品自身 tooltip 的那几行）----
    "gui.rs_create_compat.collection_cache.marker.amount_line": {
        "zh": "数量：%s",
        "en": "Amount: %s",
    },
    "gui.rs_create_compat.collection_cache.marker.match_nbt.on": {
        "zh": "匹配 NBT：开（数据组件需一致）",
        "en": "Match NBT: ON (components must match)",
    },
    "gui.rs_create_compat.collection_cache.marker.match_nbt.off": {
        "zh": "匹配 NBT：关（只看物品种类）",
        "en": "Match NBT: OFF (item type only)",
    },
    "gui.rs_create_compat.collection_cache.marker.tag_line": {
        "zh": "匹配标签 %s",
        "en": "Match tag %s",
    },
    "gui.rs_create_compat.collection_cache.marker.matched": {
        "zh": "可匹配到 %s 种物品（轮询显示）",
        "en": "Matches %s item type(s) (cycled on the slot)",
    },

    # ---- 归流缓存仓：匹配设置子窗口的「匹配标签」开关 + 多选列表 ----
    "gui.rs_create_compat.collection_cache.match_tags": {
        "zh": "匹配标签",
        "en": "Match tags",
    },
    "gui.rs_create_compat.collection_cache.match_tags.on": {
        "zh": "已启用",
        "en": "ON",
    },
    "gui.rs_create_compat.collection_cache.match_tags.off": {
        "zh": "未启用",
        "en": "OFF",
    },
    "gui.rs_create_compat.collection_cache.match_tags.tip": {
        "zh": "打开后即可从「示例物自带的标签」里勾选一个或多个：命中物必须同时带上全部选中标签"
              "（例如只勾 minecraft:logs 就会匹配所有原木）。标签、匹配 NBT、数量三者可同时生效。",
        "en": "Turn this on and pick one or more tags the sample item carries: a match must carry ALL "
              "selected tags (e.g. only minecraft:logs matches every log). Works together with NBT and amount.",
    },
    "gui.rs_create_compat.collection_cache.match_tags.hint": {
        "zh": "打开「匹配标签」后可从示例物的标签里多选",
        "en": "Enable 'Match tags' to multi-select from the sample's tags",
    },
    "gui.rs_create_compat.collection_cache.match_tags.preview": {
        "zh": "已选 %2$s 个标签，可匹配 %1$s 种物品",
        "en": "%2$s tag(s) selected, matching %1$s item type(s)",
    },
    "gui.rs_create_compat.collection_cache.match_tags.preview_none": {
        "zh": "尚未勾选标签",
        "en": "No tag selected yet",
    },
    "gui.rs_create_compat.collection_cache.match_tags.empty": {
        "zh": "该示例物身上没有任何标签",
        "en": "This sample carries no tags",
    },
    "gui.rs_create_compat.collection_cache.match_tags.selected": {
        "zh": "状态：已选中（左键取消）",
        "en": "State: selected (left-click to unselect)",
    },
    "gui.rs_create_compat.collection_cache.match_tags.unselected": {
        "zh": "状态：未选中（左键选中）",
        "en": "State: not selected (left-click to select)",
    },
    "gui.rs_create_compat.collection_cache.match_tags.row_click": {
        "zh": "左键：切换该标签的选中状态",
        "en": "Left-click: toggle this tag",
    },

    # ---- 序列装配回流总线：总样板 / 步骤 / 流体 / 文案 ----
    "gui.rs_create_compat.sequence_return_bus.upgrades": {
        "zh": "插件",
        "en": "Upgrades",
    },
    "gui.rs_create_compat.sequence_return_bus.pattern": {
        "zh": "总样板",
        "en": "Master pattern",
    },
    "gui.rs_create_compat.sequence_return_bus.step": {
        "zh": "回流步骤",
        "en": "Return step",
    },
    "gui.rs_create_compat.sequence_return_bus.fluid": {
        "zh": "流体缓冲 %s / %s",
        "en": "Fluid buffer %s / %s",
    },
    "gui.rs_create_compat.sequence_return_bus.upgrade_note": {
        "zh": "速度×%s 堆叠×%s 单次吞吐 %s",
        "en": "Speed x%s, Stack x%s, batch %s",
    },
    "gui.rs_create_compat.sequence_return_bus.auto_return.tip": {
        "zh": "自动回网：开启后缓冲里的物品 / 流体持续写回 RS 网络；关闭则只驻留缓冲（内容绝不销毁）。",
        "en": "Auto-return: contents of the buffer are pushed back into the RS network. "
              "When off they just stay buffered (nothing is ever destroyed).",
    },
    "gui.rs_create_compat.sequence_return_bus.destroy_waste.tip": {
        "zh": "销毁废料：仅当物品命中已绑定总样板声明的「废料池」时才会被销毁；其余物品一律按中间产物回网。",
        "en": "Destroy waste: only items matching the bound master pattern's scrap pool are destroyed; "
              "everything else is returned to the network as an intermediate.",
    },
    "gui.rs_create_compat.sequence_return_bus.step.title": {
        "zh": "回流步骤",
        "en": "Return step",
    },
    "gui.rs_create_compat.sequence_return_bus.step.hint": {
        "zh": "滚轮切换；左键点左半 / 右半 = 上一步 / 下一步。指「一次循环中的第几步」，与循环次数无关。",
        "en": "Scroll to switch; left-click the left/right half for previous/next. "
              "This is the step within ONE loop, unrelated to loop count.",
    },
    "gui.rs_create_compat.sequence_return_bus.step.none": {
        "zh": "未绑定总样板",
        "en": "No master pattern",
    },
    "gui.rs_create_compat.sequence_return_bus.step.unknown_machine": {
        "zh": "未知机器",
        "en": "unknown machine",
    },
    "gui.rs_create_compat.sequence_return_bus.step.entry": {
        "zh": "第%s步 · %s",
        "en": "Step %s - %s",
    },
    "gui.rs_create_compat.sequence_return_bus.face_config.button": {
        "zh": "面配置",
        "en": "Faces",
    },
    "gui.rs_create_compat.sequence_return_bus.face_config.tip": {
        "zh": "打开面配置：逐面设置「是否主动从相邻容器抽取物品 / 流体」进缓冲。",
        "en": "Open face config: per-face toggle for actively pulling items/fluids from adjacent blocks.",
    },
    "gui.rs_create_compat.sequence_return_bus.face_config.state": {
        "zh": "当前主动吸取的面：%s / 6",
        "en": "Faces actively pulling: %s / 6",
    },

    # ---- 序列装配回流总线：面配置子界面 ----
    "gui.rs_create_compat.sequence_return_bus.face_config.title": {
        "zh": "序列装配回流总线 · 面配置",
        "en": "Sequence Return Bus - Faces",
    },
    "gui.rs_create_compat.sequence_return_bus.face_config.close": {
        "zh": "关闭",
        "en": "Close",
    },
    "gui.rs_create_compat.sequence_return_bus.face_config.dir.up": {"zh": "上", "en": "Up"},
    "gui.rs_create_compat.sequence_return_bus.face_config.dir.down": {"zh": "下", "en": "Down"},
    "gui.rs_create_compat.sequence_return_bus.face_config.dir.north": {"zh": "北", "en": "North"},
    "gui.rs_create_compat.sequence_return_bus.face_config.dir.south": {"zh": "南", "en": "South"},
    "gui.rs_create_compat.sequence_return_bus.face_config.dir.west": {"zh": "西", "en": "West"},
    "gui.rs_create_compat.sequence_return_bus.face_config.dir.east": {"zh": "东", "en": "East"},
    "gui.rs_create_compat.sequence_return_bus.face_config.on": {"zh": "主动吸取", "en": "Pulling"},
    "gui.rs_create_compat.sequence_return_bus.face_config.off": {"zh": "不主动", "en": "Idle"},
    "gui.rs_create_compat.sequence_return_bus.face_config.current": {
        "zh": "当前：%s",
        "en": "Current: %s",
    },
    "gui.rs_create_compat.sequence_return_bus.face_config.state.on.tip": {
        "zh": "该面会主动从相邻容器抽取物品与流体进缓冲。",
        "en": "This face actively pulls items and fluids from the adjacent block into the buffer.",
    },
    "gui.rs_create_compat.sequence_return_bus.face_config.state.off.tip": {
        "zh": "该面不主动抽取；外部仍可用管道 / 漏斗把东西塞进来。",
        "en": "This face does not pull; pipes/hoppers may still push things in.",
    },
    "gui.rs_create_compat.sequence_return_bus.face_config.click": {
        "zh": "左键 / 右键：切换该面",
        "en": "Left/right-click: toggle this face",
    },
    "gui.rs_create_compat.sequence_return_bus.face_config.hint.1": {
        "zh": "绿色面：会主动从该方向的相邻容器抽取物品 / 流体。",
        "en": "Green face: actively pulls items/fluids from the neighbour on that side.",
    },
    "gui.rs_create_compat.sequence_return_bus.face_config.hint.2": {
        "zh": "灰色面：不主动抽取，仅被动接受管道塞入。",
        "en": "Grey face: no pulling, only accepts pushes from logistics.",
    },
    "gui.rs_create_compat.sequence_return_bus.face_config.hint.3": {
        "zh": "本机始终把缓冲里的内容回写 RS 网络（绑定总样板后按所选步骤判定产物 / 废料）。",
        "en": "Recycling into RS always happens (with a master pattern bound, classification follows the chosen step).",
    },
    "gui.rs_create_compat.sequence_return_bus.face_config.hint.tip": {
        "zh": "面配置说明：颜色即状态；点击方块面切换，保存由服务端权威生效",
        "en": "Face config: colour equals state; click a face to toggle, saved server-side",
    },

    # ---- 方块说明（去掉「速率×」表述，补上堆叠升级 / 流体 / 绑定总样板）----
    # 2026-09-19：方块已「玩家侧下线」（弃用，改用输入总线 + 线缆），说明开头补一句短文案
    "block.rs_create_compat.sequence_return_bus.help": {
        "zh": "（已弃用，请改用「RS 输入总线 + 线缆」承担回流；本方块保留供旧存档继续使用）"
              "接入 RS 网络，把 Create 序列装配产线吐出的物品与流体自动接回网络。"
              "可放入一张总样板并选择它是「一次循环中的第几步」，据此判定该收回哪些产物与废料"
              "（未绑定则按网络样板自动分类）。缓冲 54 格 + 256000 mB 流体；"
              "速度升级提高每 tick 处理组数，堆叠升级提高单次吞吐。",
        "en": "DEPRECATED - use an RS importer plus cables instead; the block is kept for existing saves. "
              "Connects to the RS network and returns items/fluids produced by Create sequenced assembly. "
              "Bind a master pattern and pick which step of one loop this bus handles to decide which "
              "products/scraps to pull back (without a pattern it classifies via network patterns). "
              "Buffer: 54 slots + 256000 mB of fluid; speed upgrades raise the per-tick groups, "
              "stack upgrades raise the batch size.",
    },
}

# ==================== 删除（本模块范围内不再需要的旧键） ====================
REMOVED = [
    # 回流总线：主动吸取面改由「面配置」子界面承担，主界面不再有这两组文案
    "gui.rs_create_compat.sequence_return_bus.pull_faces",
    "gui.rs_create_compat.sequence_return_bus.face.down",
    "gui.rs_create_compat.sequence_return_bus.face.up",
    "gui.rs_create_compat.sequence_return_bus.face.north",
    "gui.rs_create_compat.sequence_return_bus.face.south",
    "gui.rs_create_compat.sequence_return_bus.face.west",
    "gui.rs_create_compat.sequence_return_bus.face.east",
    # 归流缓存仓：按要求不再标注收集范围上限 / 范围升级数量
    "gui.rs_create_compat.collection_cache.range.limit",
    "gui.rs_create_compat.collection_cache.range.info",
    # 归流缓存仓：旧的「手填标签名 + 应用/清除」整套 UI 已被「匹配标签开关 + 多选列表」取代
    "gui.rs_create_compat.collection_cache.tag_filter",
    "gui.rs_create_compat.collection_cache.tag_filter.hint",
    "gui.rs_create_compat.collection_cache.tag_filter.tip",
    "gui.rs_create_compat.collection_cache.tag_filter.on",
    "gui.rs_create_compat.collection_cache.tag_filter.off",
    "gui.rs_create_compat.collection_cache.apply",
    "gui.rs_create_compat.collection_cache.apply.tip",
    "gui.rs_create_compat.collection_cache.clear",
    "gui.rs_create_compat.collection_cache.clear.tip",
    "gui.rs_create_compat.collection_cache.match_fuzzy",
    "gui.rs_create_compat.collection_cache.match_tag",
    "gui.rs_create_compat.collection_cache.match_tag.tip",
    "gui.rs_create_compat.marker.match_tag",
]


def main():
    fragment = {}
    for key, value in UPSERT.items():
        fragment[key] = value
    for key in REMOVED:
        fragment[key] = {"delete": True}
    with open(OUT, "w", encoding="utf-8") as handle:
        json.dump(fragment, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    print("[OK] 写出 %s：新增/覆盖 %d 键，删除 %d 键"
          % (os.path.relpath(OUT, ROOT), len(UPSERT), len(REMOVED)))


if __name__ == "__main__":
    main()
