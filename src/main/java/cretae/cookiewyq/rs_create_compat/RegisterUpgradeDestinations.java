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
        register("sequence_assembly_executor",
            "speed_upgrade", UpgradeSlot.MAX_PER_UPGRADE,
            "autocrafting_upgrade", 1);
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
