#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""终端与充电器修复。"""
import io

def fix(path, rules):
    with open(path, 'r', encoding='utf-8') as f:
        src = f.read()
    for old, new in rules:
        if old not in src:
            print(f"[WARN] 未找到 in {path}: {old[:70]!r}")
            continue
        src = src.replace(old, new)
    with open(path, 'w', encoding='utf-8') as f:
        f.write(src)
    print(f"[OK] {path}")

base = r'd:\MODS\refined_storage_and_create_compat\src\main\java\cretae\cookiewyq\rs_create_compat'

# 1. 同模式不重开
fix(base + r'\network\SwitchTerminalModePacket.java', [
    ("""            final ItemStack stack = stackOpt.get();
            AdvancedRemoteTerminalItem.setMode(stack, packet.mode());
            item.openModeScreen(player, stack, packet.slotReference());""",
     """            final ItemStack stack = stackOpt.get();
            // 已是目标模式则不重开（避免点击当前 Tab 导致界面关闭重开）
            if (AdvancedRemoteTerminalItem.getMode(stack) == packet.mode()) {
                return;
            }
            AdvancedRemoteTerminalItem.setMode(stack, packet.mode());
            item.openModeScreen(player, stack, packet.slotReference());"""),
])

# 2. 满电版：直接写 Energy 组件
fix(base + r'\item\AdvancedRemoteTerminalItem.java', [
    ("""    @Override
    public ItemStack getDefaultInstance() {
        // 满电版 / 创造版默认满电；普通版默认无电
        if (type != Type.NORMAL) {
            final ItemStack stack = super.getDefaultInstance();
            final EnergyStorage energy = createEnergyStorage(stack);
            energy.receive(energy.getCapacity(), com.refinedmods.refinedstorage.api.core.Action.EXECUTE);
            return stack;
        }
        return super.getDefaultInstance();
    }""",
     """    @Override
    public ItemStack getDefaultInstance() {
        // 满电版 / 创造版默认满电（直接写 RS Energy 数据组件，避免 receive 链问题）；普通版默认无电
        final ItemStack stack = super.getDefaultInstance();
        if (type != Type.NORMAL) {
            final long capacity = type == Type.CREATIVE
                ? Integer.MAX_VALUE
                : Config.advancedRemoteTerminalEnergyCapacity;
            stack.set(com.refinedmods.refinedstorage.common.content.DataComponents.INSTANCE.getEnergy(), capacity);
        }
        return stack;
    }"""),
])

print("done")
