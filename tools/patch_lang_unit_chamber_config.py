# -*- coding: utf-8 -*-
# 用 Python 规范化更新 lang JSON（避免手写 JSON 出错）。
# 覆盖：单元样板新三要素 tooltip、单元样板配置子界面、执行仓绑定配置子界面与状态行。
import json
import io
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = r"d:\MODS\refined_storage_and_create_compat\src\main\resources\assets\rs_create_compat\lang"

zh = {
    # ===== 单元样板物品 tooltip（改读新语义三要素） =====
    "item.rs_create_compat.sequence_unit_pattern.display_name": "名字：%s",
    "item.rs_create_compat.sequence_unit_pattern.unnamed": "（未命名）",
    "item.rs_create_compat.sequence_unit_pattern.recipe_type": "配方类型：%s",
    "item.rs_create_compat.sequence_unit_pattern.unset": "（未设置）",
    "item.rs_create_compat.sequence_unit_pattern.requires_input": "需要输入原料：%s",
    "item.rs_create_compat.sequence_unit_pattern.yes": "是",
    "item.rs_create_compat.sequence_unit_pattern.no": "否",
    "item.rs_create_compat.sequence_unit_pattern.input": "输入物：%s",

    # ===== 生成单元样板配置子界面 =====
    "gui.rs_create_compat.unit_pattern_config.title": "生成单元样板",
    "gui.rs_create_compat.unit_pattern_config.name": "名字",
    "gui.rs_create_compat.unit_pattern_config.name.hint": "输入单元样板的名字",
    "gui.rs_create_compat.unit_pattern_config.name.tip": "单元样板的名字（必填，不能为空）",
    "gui.rs_create_compat.unit_pattern_config.recipe_type": "配方类型",
    "gui.rs_create_compat.unit_pattern_config.recipe_type.hint": "配方类型 id，例如 create:pressing",
    "gui.rs_create_compat.unit_pattern_config.recipe_type.tip": "配方类型 id（必填），例如 create:pressing / minecraft:smelting",
    "gui.rs_create_compat.unit_pattern_config.picker": "取 %s/%s",
    "gui.rs_create_compat.unit_pattern_config.picker.none": "取",
    "gui.rs_create_compat.unit_pattern_config.picker.tip": "从当前网络执行仓已绑定的配方类型（去重）里循环填入，当前 %s/%s",
    "gui.rs_create_compat.unit_pattern_config.picker.empty": "当前网络内没有已绑定配方类型的执行仓",
    "gui.rs_create_compat.unit_pattern_config.requires_input": "需要输入原料：%s",
    "gui.rs_create_compat.unit_pattern_config.requires_input.tip": "切换该单元样板是否需要输入原料；选「是」时会把终端输入槽的物品标记为输入物",
    "gui.rs_create_compat.unit_pattern_config.yes": "是",
    "gui.rs_create_compat.unit_pattern_config.no": "否",
    "gui.rs_create_compat.unit_pattern_config.input": "输入物",
    "gui.rs_create_compat.unit_pattern_config.input.none": "（输入槽为空）",
    "gui.rs_create_compat.unit_pattern_config.input.missing": "需要输入原料，但终端输入槽为空：请先放入物品",
    "gui.rs_create_compat.unit_pattern_config.confirm": "确定",
    "gui.rs_create_compat.unit_pattern_config.confirm.tip": "生成单元样板并写入单元样板库的第一个空位",
    "gui.rs_create_compat.unit_pattern_config.confirm.disabled": "三项信息未填全：名字 / 配方类型不能为空；需要输入原料时输入槽不能为空",
    "gui.rs_create_compat.unit_pattern_config.cancel": "取消",
    "gui.rs_create_compat.unit_pattern_config.cancel.tip": "放弃本次生成并返回终端界面",

    # ===== 生成按钮提示（单元页改为打开配置子界面） =====
    "gui.rs_create_compat.sequence_pattern_terminal.generate_unit.tip": "打开单元样板配置界面：填写名字、配方类型、是否需要输入原料，确定后写入单元样板库第一个空位",

    # ===== 执行仓界面：配置入口 + 绑定状态 =====
    "gui.rs_create_compat.sequence_execution_chamber.config": "配置",
    "gui.rs_create_compat.sequence_execution_chamber.config.tip": "打开配置子界面：设置本执行仓的配方类型与名字",
    "gui.rs_create_compat.sequence_execution_chamber.config.title": "执行仓绑定配置",
    "gui.rs_create_compat.sequence_execution_chamber.config.recipe_type": "配方类型",
    "gui.rs_create_compat.sequence_execution_chamber.config.recipe_type.tip": "本执行仓负责的配方类型 id，例如 create:pressing（留空 = 解除绑定）",
    "gui.rs_create_compat.sequence_execution_chamber.config.name": "名字",
    "gui.rs_create_compat.sequence_execution_chamber.config.name.tip": "本执行仓的显示名（留空 = 显示为坐标）",
    "gui.rs_create_compat.sequence_execution_chamber.config.confirm": "确定",
    "gui.rs_create_compat.sequence_execution_chamber.config.confirm.tip": "发送给服务端保存本执行仓的绑定（服务端校验坐标与距离）",
    "gui.rs_create_compat.sequence_execution_chamber.config.cancel": "取消",
    "gui.rs_create_compat.sequence_execution_chamber.config.cancel.tip": "放弃修改并返回执行仓界面",
    "gui.rs_create_compat.sequence_execution_chamber.syncing": "绑定同步中…",
    "gui.rs_create_compat.sequence_execution_chamber.unbound": "未绑定",
    "gui.rs_create_compat.sequence_execution_chamber.bound": "配方类型：%s  名字：%s",
    "gui.rs_create_compat.sequence_execution_chamber.status.tip": "本执行仓当前绑定的配方类型与名字（由服务端同步）",
}

en = {
    # ===== unit pattern item tooltip (new v4 semantics) =====
    "item.rs_create_compat.sequence_unit_pattern.display_name": "Name: %s",
    "item.rs_create_compat.sequence_unit_pattern.unnamed": "(unnamed)",
    "item.rs_create_compat.sequence_unit_pattern.recipe_type": "Recipe type: %s",
    "item.rs_create_compat.sequence_unit_pattern.unset": "(unset)",
    "item.rs_create_compat.sequence_unit_pattern.requires_input": "Requires input: %s",
    "item.rs_create_compat.sequence_unit_pattern.yes": "Yes",
    "item.rs_create_compat.sequence_unit_pattern.no": "No",
    "item.rs_create_compat.sequence_unit_pattern.input": "Input: %s",

    # ===== unit pattern config screen =====
    "gui.rs_create_compat.unit_pattern_config.title": "Create Unit Pattern",
    "gui.rs_create_compat.unit_pattern_config.name": "Name",
    "gui.rs_create_compat.unit_pattern_config.name.hint": "Enter the unit pattern name",
    "gui.rs_create_compat.unit_pattern_config.name.tip": "Display name of the unit pattern (required)",
    "gui.rs_create_compat.unit_pattern_config.recipe_type": "Recipe type",
    "gui.rs_create_compat.unit_pattern_config.recipe_type.hint": "Recipe type id, e.g. create:pressing",
    "gui.rs_create_compat.unit_pattern_config.recipe_type.tip": "Recipe type id (required), e.g. create:pressing / minecraft:smelting",
    "gui.rs_create_compat.unit_pattern_config.picker": "Pick %s/%s",
    "gui.rs_create_compat.unit_pattern_config.picker.none": "Pick",
    "gui.rs_create_compat.unit_pattern_config.picker.tip": "Cycle through the distinct recipe types bound by chambers in this network (current %s/%s)",
    "gui.rs_create_compat.unit_pattern_config.picker.empty": "No chamber in this network has a bound recipe type yet",
    "gui.rs_create_compat.unit_pattern_config.requires_input": "Requires input: %s",
    "gui.rs_create_compat.unit_pattern_config.requires_input.tip": "Toggle whether this unit pattern consumes an input; when ON the terminal input slot item is stored as the input",
    "gui.rs_create_compat.unit_pattern_config.yes": "Yes",
    "gui.rs_create_compat.unit_pattern_config.no": "No",
    "gui.rs_create_compat.unit_pattern_config.input": "Input",
    "gui.rs_create_compat.unit_pattern_config.input.none": "(input slot empty)",
    "gui.rs_create_compat.unit_pattern_config.input.missing": "Input required but the terminal input slot is empty: place an item first",
    "gui.rs_create_compat.unit_pattern_config.confirm": "Confirm",
    "gui.rs_create_compat.unit_pattern_config.confirm.tip": "Create the unit pattern into the first empty library slot",
    "gui.rs_create_compat.unit_pattern_config.confirm.disabled": "Not ready: name and recipe type are required; when input is required the input slot must not be empty",
    "gui.rs_create_compat.unit_pattern_config.cancel": "Cancel",
    "gui.rs_create_compat.unit_pattern_config.cancel.tip": "Discard and return to the terminal",

    # ===== generate button tooltip (unit tab now opens the config screen) =====
    "gui.rs_create_compat.sequence_pattern_terminal.generate_unit.tip": "Open the unit pattern config screen: fill in name, recipe type and whether an input is required; on confirm it is written to the first empty library slot",

    # ===== chamber screen: config entry + binding status =====
    "gui.rs_create_compat.sequence_execution_chamber.config": "Config",
    "gui.rs_create_compat.sequence_execution_chamber.config.tip": "Open the config screen to set this chamber's recipe type and name",
    "gui.rs_create_compat.sequence_execution_chamber.config.title": "Chamber Binding Config",
    "gui.rs_create_compat.sequence_execution_chamber.config.recipe_type": "Recipe type",
    "gui.rs_create_compat.sequence_execution_chamber.config.recipe_type.tip": "Recipe type id this chamber handles, e.g. create:pressing (empty = unbound)",
    "gui.rs_create_compat.sequence_execution_chamber.config.name": "Name",
    "gui.rs_create_compat.sequence_execution_chamber.config.name.tip": "Display name of this chamber (empty = coordinates)",
    "gui.rs_create_compat.sequence_execution_chamber.config.confirm": "Confirm",
    "gui.rs_create_compat.sequence_execution_chamber.config.confirm.tip": "Send to the server and save this chamber's binding (server validates position and distance)",
    "gui.rs_create_compat.sequence_execution_chamber.config.cancel": "Cancel",
    "gui.rs_create_compat.sequence_execution_chamber.config.cancel.tip": "Discard changes and return to the chamber screen",
    "gui.rs_create_compat.sequence_execution_chamber.syncing": "Syncing binding…",
    "gui.rs_create_compat.sequence_execution_chamber.unbound": "Unbound",
    "gui.rs_create_compat.sequence_execution_chamber.bound": "Recipe type: %s  Name: %s",
    "gui.rs_create_compat.sequence_execution_chamber.status.tip": "Recipe type and name currently bound to this chamber (synced from the server)",
}


def patch(path, updates):
    with io.open(path, "r", encoding="utf-8") as f:
        data = json.load(f)
    data.update(updates)
    with io.open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print("patched", path, "+%d keys" % len(updates))


patch(ROOT + r"\zh_cn.json", zh)
patch(ROOT + r"\en_us.json", en)
