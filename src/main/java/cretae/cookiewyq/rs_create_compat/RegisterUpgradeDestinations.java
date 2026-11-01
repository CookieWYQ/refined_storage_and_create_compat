package cretae.cookiewyq.rs_create_compat;

import com.refinedmods.refinedstorage.common.api.RefinedStorageApi;
import com.refinedmods.refinedstorage.common.api.upgrade.UpgradeDestination;
import com.refinedmods.refinedstorage.common.api.upgrade.UpgradeRegistry;
import cretae.cookiewyq.rs_create_compat.menu.UpgradeSlot;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把本模组的机器注册为 RS 原版升级物品的"目的地"（destination）。
 * <p>RS 的升级物品（速度/堆叠/范围/自动合成）tooltip 会读取 UpgradeRegistry：
 * 对每个允许放入该升级的目的地渲染一行<b>方块图标 + 方块名称（数量上限）</b>，
 * 与本模组各机器 {@code UpgradeSlot} 的允许列表保持完全一致 ——
 * 因此升级物品悬停提示由 RS 原生渲染，外观与 RS 原版完全一致，无需自定义 tooltip 文本。
 *
 * <p><b>R4 修正（2026-10-17）</b>：本表此前把 {@code sequence_assembly_executor} 登记为吃
 * {@code speed_upgrade}(6) + {@code autocrafting_upgrade}(1)，但<b>该方块根本没有升级槽</b>
 * —— 它的容器只有样板槽（{@code SequenceAssemblyExecutorBlockEntity} 的 {@code SimpleContainer(PATTERN_SLOTS)}），
 * 菜单里只有 {@code PatternSlot} + 玩家槽（{@code SequenceAssemblyExecutorMenu}），
 * 界面里 {@code grep Upgrade} 零命中。后果是 RS 升级物品的 tooltip 会<b>谎报</b>一台
 * 玩家根本放不进去的机器（{@code docs/REUSE_SELECTION_UI_AND_UPGRADES.md} §3.2 坑 B）。
 * 因此那 3 行已删除。本表的<b>不变式</b>：<b>表里的每一台机器都必须真的有升级槽</b>。</p>
 */
public final class RegisterUpgradeDestinations {
    /** 机器注册名 → 该机器允许的升级（升级注册名 → 单种上限）。
     *  <p>上限与 {@link UpgradeSlot#maxPerUpgrade} 一致：自动合成/创造范围各 1，其余 6。 */
    private static final Map<String, Map<String, Integer>> MACHINE_UPGRADES = new LinkedHashMap<>();

    static {
        register("schematic_loader",
            "speed_upgrade", UpgradeSlot.MAX_PER_UPGRADE,
            "stack_upgrade", UpgradeSlot.MAX_PER_UPGRADE,
            "autocrafting_upgrade", 1);
        register("advanced_schematic_loader",
            "speed_upgrade", UpgradeSlot.MAX_PER_UPGRADE,
            "stack_upgrade", UpgradeSlot.MAX_PER_UPGRADE,
            "autocrafting_upgrade", 1);
        register("quantity_keeper",
            "speed_upgrade", UpgradeSlot.MAX_PER_UPGRADE,
            "stack_upgrade", UpgradeSlot.MAX_PER_UPGRADE,
            "autocrafting_upgrade", 1);
        register("range_charger",
            "speed_upgrade", UpgradeSlot.MAX_PER_UPGRADE,
            "stack_upgrade", UpgradeSlot.MAX_PER_UPGRADE,
            "range_upgrade", UpgradeSlot.MAX_PER_UPGRADE,
            "creative_range_upgrade", 1);
        register("collection_cache",
            "speed_upgrade", UpgradeSlot.MAX_PER_UPGRADE,
            "stack_upgrade", UpgradeSlot.MAX_PER_UPGRADE,
            "range_upgrade", UpgradeSlot.MAX_PER_UPGRADE,
            "creative_range_upgrade", 1);
        // 高级归流缓存仓：升级槽与允许清单<b>整份继承</b>基础版
        // （{@code AdvancedCollectionCacheMenu} → {@code CollectionCacheMenu}
        // 的 ALLOWED_UPGRADES / UpgradeSlot.forContainer，自己没有第二份名单）。
        // 因此这里必须与基础版【成对登记】：漏一台的后果不是功能坏掉，而是 RS 原版升级物品的
        // 悬停提示里不列出高级版 —— 玩家会以为「升级放不进高级仓」，而实际上放得进。
        register("advanced_collection_cache",
            "speed_upgrade", UpgradeSlot.MAX_PER_UPGRADE,
            "stack_upgrade", UpgradeSlot.MAX_PER_UPGRADE,
            "range_upgrade", UpgradeSlot.MAX_PER_UPGRADE,
            "creative_range_upgrade", 1);
        // 网络资源接收器：6 个升级槽，只收速度 + 叠加（与 NetworkResourceReceiverMenu
        // 里 UpgradeSlot 的白名单、以及 RsccSpeedStackUpgradeContainer 的第 ② 层校验逐字一致）。
        register("network_resource_receiver",
            "speed_upgrade", UpgradeSlot.MAX_PER_UPGRADE,
            "stack_upgrade", UpgradeSlot.MAX_PER_UPGRADE);
        // 第 131 轮：网络资源请求器的 6 个升级槽已按用户要求整条删除
        //（用户原话「发送器功能保留，但不需要插件槽：红石激活的速度决定发送速度」）。
        // 这里**必须不再登记**它 —— 本表是 RS 原生升级物品悬停提示的「哪些机器吃这个升级」来源，
        // 留着就是误导性死数据（玩家会以为请求器还能插速度/叠加升级，实际没有槽可放）。
        // 降解室发电机（第 123 轮重做才有的 6 个升级槽 = 3 列 × 2 行）：
        // 与 DecompositionGeneratorMenu.ALLOWED_UPGRADES、以及 RsccSpeedStackUpgradeContainer 的
        // 第 ② 层校验【三处逐字一致】。语义见 DecompositionGeneratorSpec：
        //   速度升级 → 耗时右移（base >> n），**单件发电量不变**（用户硬要求）；
        //   堆叠升级 → 每格输入上限 64 * (1 + n)（与采集仓 / 接收器同一口径）。
        // 这条登记决定 RS 原版升级物品悬停提示里会不会列出这台机器；漏了不影响功能，
        // 但玩家会以为「升级放不进去」（docs/REUSE_SELECTION_UI_AND_UPGRADES.md §3.2 坑 B）。
        register("decomposition_generator",
            "speed_upgrade", UpgradeSlot.MAX_PER_UPGRADE,
            "stack_upgrade", UpgradeSlot.MAX_PER_UPGRADE);
    }

    private static void register(final String machine,
                                 final String upgrade,
                                 final int max,
                                 final Object... more) {
        final Map<String, Integer> upgrades = new LinkedHashMap<>();
        upgrades.put(upgrade, max);
        for (int i = 0; i + 1 < more.length; i += 2) {
            upgrades.put((String) more[i], (Integer) more[i + 1]);
        }
        MACHINE_UPGRADES.put(machine, upgrades);
    }

    private RegisterUpgradeDestinations() {
    }

    /** 在 FMLCommonSetup 时调用（物品已全部注册，RS API 已注入）。 */
    public static void registerAll() {
        final UpgradeRegistry registry = RefinedStorageApi.INSTANCE.getUpgradeRegistry();
        for (final Map.Entry<String, Map<String, Integer>> machineEntry : MACHINE_UPGRADES.entrySet()) {
            final String machine = machineEntry.getKey();
            final Item machineItem = item(RS_Create_Compat.MODID, machine);
            if (machineItem == null) {
                continue;
            }
            final UpgradeDestination destination = new UpgradeDestination() {
                @Override
                public Component getName() {
                    return machineItem.getDescription();
                }

                @Override
                public ItemStack getStackRepresentation() {
                    return new ItemStack(machineItem);
                }
            };
            var builder = registry.forDestination(destination);
            for (final Map.Entry<String, Integer> upgradeEntry : machineEntry.getValue().entrySet()) {
                final Item upgradeItem = item("refinedstorage", upgradeEntry.getKey());
                if (upgradeItem != null) {
                    builder = builder.add(upgradeItem, upgradeEntry.getValue());
                }
            }
        }
    }

    private static Item item(final String namespace, final String path) {
        final Item item = BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath(namespace, path));
        return item == net.minecraft.world.item.Items.AIR ? null : item;
    }
}
