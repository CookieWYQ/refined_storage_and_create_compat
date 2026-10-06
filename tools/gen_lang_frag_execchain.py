# -*- coding: utf-8 -*-
"""生成 tools/lang_frag_execchain.json（本轮「序列执行舱 链/指向」的语言键）。

覆盖：
  * 主界面绑定状态行的「链 N 台」后缀 + 链归属 tooltip（链头 / 整链共享 / 独立）；
  * 「绑定配置」子界面的「链指向」滚轮控件、链状态行与 tooltip（含「射线不分叉 / 不成环」规则）；
  * 服务端拒绝链指向时的行动栏提示（目标不存在 / 成环 / 分叉 / 超长 / 非法）；
  * 旧存档分叉 / 环自愈后的提示（服务端日志 + 正在看界面的玩家一条短消息）；
  * 两处既有文案按新语义改写（配置入口 tip、配方类型锁定原因改为「链上任一台有样板」）。

按用户规则用 Python 脚本产出 json（不手写），再交给 tools/apply_lang_frag.py 幂等合并。
"""
import json
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "tools", "lang_frag_execchain.json")

LANG = "gui.rs_create_compat.sequence_execution_chamber."

FRAG = {
    # ===== 主界面：绑定状态行后缀（链上台数） =====
    LANG + "chain.suffix": {
        "en": "(chain: %s)",
        "zh": "（链 %s 台）",
    },
    # ===== 主界面 / 子界面：链归属 tooltip =====
    LANG + "chain.tip.standalone": {
        "en": "Not in a chain: this chamber holds its own name and recipe type.",
        "zh": "未接入链：本台独立持有一份名字与配方类型。",
    },
    LANG + "chain.tip.head": {
        "en": "Chain head: %s",
        "zh": "链头：%s",
    },
    LANG + "chain.tip.shared": {
        "en": "Name and recipe type are shared by the whole chain: editing any member applies to all.",
        "zh": "名字与配方类型整链共享：在链上任意一台修改，整条链一起生效。",
    },
    LANG + "chain.head.self": {
        "en": "this chamber (chain head)",
        "zh": "本台（链头）",
    },
    LANG + "chain.head.unknown": {
        "en": "unknown position",
        "zh": "未知坐标",
    },
    # ===== 子界面：链指向控件 =====
    LANG + "config.chain.link": {
        "en": "Chain link",
        "zh": "链指向",
    },
    LANG + "config.chain.link.hint": {
        "en": "Scroll to pick a side; pointing at another chamber links you into one chain.",
        "zh": "滚轮切换指向；指向另一台执行仓即串成一条链。",
    },
    LANG + "config.chain.link.none": {
        "en": "Not linked (standalone)",
        "zh": "不指向（独立执行仓）",
    },
    LANG + "config.chain.link.none.tip": {
        "en": "Leaves the chain: this chamber keeps its own name and recipe type (current value is kept).",
        "zh": "脱离链：本台自行持有名字与配方类型（保留当前值）。",
    },
    LANG + "config.chain.link.dir": {
        "en": "Pointing: %s",
        "zh": "指向：%s",
    },
    LANG + "config.chain.link.target": {
        "en": "Target: %s (rejected if no chamber is there)",
        "zh": "目标：%s（该方向上没有执行仓时会被拒绝）",
    },
    LANG + "config.chain.link.rule": {
        "en": "The link forms a directed ray: no fork, no loop; the endpoint is the chain head.",
        "zh": "指向连成一条有方向的射线：不分叉、不成环，端点即链首。",
    },
    LANG + "config.chain.status": {
        "en": "Chain: %s chambers, head %s",
        "zh": "链：%s 台 · 链头 %s",
    },
    LANG + "config.chain.status.standalone": {
        "en": "Chain: standalone (not connected to any chamber)",
        "zh": "链：独立（未与其它执行仓相连）",
    },
    LANG + "config.chain.shared.tip": {
        "en": "Name and recipe type are stored only on the chain head and shared by every member.",
        "zh": "名字与配方类型只存在链头那一份上，链上每台共用。",
    },
    LANG + "config.chain.head.tip": {
        "en": "The head is the chamber reached by following the chain links; there is no \"edit just this one\".",
        "zh": "链头 = 沿「链指向」走到的那一台；链上没有「只改这一台」。",
    },
    LANG + "config.chain.units.tip": {
        "en": "A chamber in this chain still holds unit patterns: the recipe type is locked until they are removed.",
        "zh": "本链上还有执行仓放着单元样板：配方类型已锁定，取走样板后才能改。",
    },
    # ===== 既有文案按新语义改写 =====
    LANG + "config.tip": {
        "en": "Open the config screen: chain link, recipe type and name (chain-wide, applied to every member)",
        "zh": "打开配置子界面：设置本执行仓的链指向、配方类型与名字（链上改动整链生效）",
    },
    # 注意：LANG + "config.recipe_type.locked" 由 lang_frag_spt3.json 持有，本片段不重复定义
    #（apply_lang_frag.py 按文件名排序合并，后写入者生效；此处定义会被 spt3 覆盖，故干脆不写）。
    LANG + "config.confirm.tip": {
        "en": "Send chain link / recipe type / name to the server; the authoritative value is sent back.",
        "zh": "把链指向 / 配方类型 / 名字发给服务端保存，服务端校验后回发权威值。",
    },
    # ===== 服务端拒绝链指向时的行动栏提示 =====
    "message.rs_create_compat.chamber_link_no_target": {
        "en": "No chamber on that side: cannot link.",
        "zh": "该方向上没有执行仓，无法指向。",
    },
    "message.rs_create_compat.chamber_link_cycle": {
        "en": "Cannot link: that would loop back to this chamber (the chain is a directed ray).",
        "zh": "不能指向：那会绕回自己（链是一条有方向的射线）。",
    },
    "message.rs_create_compat.chamber_link_fork": {
        "en": "That chamber is already pointed at by another one: a ray cannot fork.",
        "zh": "该仓已被另一台指向：射线不允许分叉。",
    },
    "message.rs_create_compat.chamber_link_too_long": {
        "en": "Chain limit reached (8 chambers): cannot link.",
        "zh": "链已达上限（8 台），无法再接。",
    },
    "message.rs_create_compat.chamber_link_not_ready": {
        "en": "Nearby chunks are not loaded yet: cannot verify the chain, try again in a moment.",
        "zh": "目标周围区块尚未加载，暂时无法确认链归属，请稍后再试。",
    },
    "message.rs_create_compat.chamber_link_invalid": {
        "en": "Invalid chain link.",
        "zh": "链指向非法，已忽略。",
    },
    # ===== 旧档分叉 / 环自愈后的可见提示（服务端日志 + 界面提示） =====
    "message.rs_create_compat.chamber_ray_fork_cleared": {
        "en": "Old save had a forked chain: this chamber's link was cleared (a ray cannot fork).",
        "zh": "旧存档里的链出现了分叉：已解除本仓的指向（射线不允许分叉）。",
    },
    "message.rs_create_compat.chamber_ray_cycle_cleared": {
        "en": "Old save had a chain loop: this chamber's link was cleared (the chain is a directed ray).",
        "zh": "旧存档里的链出现了环：已解除本仓的指向（链是一条有方向的射线）。",
    },
}


def main():
    with open(OUT, "w", encoding="utf-8") as handle:
        json.dump(FRAG, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    print("[OK] 已写出 %s（%d 键）" % (os.path.relpath(OUT, ROOT), len(FRAG)))


if __name__ == "__main__":
    main()
