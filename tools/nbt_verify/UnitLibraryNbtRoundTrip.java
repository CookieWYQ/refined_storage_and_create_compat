import cretae.cookiewyq.rs_create_compat.block.entity.SequencePatternTerminalBlockEntity.GrowingUnitLibrary;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.fml.loading.LoadingModList;

import java.util.List;
import java.util.Map;

/**
 * 离线（不启动游戏）验证单元样板库的 NBT 落盘往返：
 * 使用 Bootstrap 初始化物品注册表后，直接驱动真实的 {@link GrowingUnitLibrary}。
 *
 * <p>覆盖：新格式结构、序列化→反序列化条目一致、旧格式 {@code {Slot, Item:<stack>}} 迁移、
 * 坏条目只丢自己不清空整库。
 */
public final class UnitLibraryNbtRoundTrip {
    private static int failures = 0;

    public static void main(final String[] args) {
        // 纯离线 bootstrap：NeoForge 的特性开关加载需要一个非 null 的 LoadingModList，
        // 这里注入一个空的模组列表，避免依赖 FML 启动流程。
        LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        SharedConstants.tryDetectVersion();
        try {
            Bootstrap.bootStrap();
        } catch (final Throwable t) {
            // 无游戏资源（en_us.json 等）时，Bootstrap 末尾的 CreativeModeTabs 校验会失败；
            // 但物品/方块/数据组件注册表在此之前已完成，NBT 往返不需要这些资源，忽略即可。
            System.out.println("[warn] 忽略 bootstrap 收尾失败: " + t);
        }
        final HolderLookup.Provider provider =
            RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);

        newFormatShape(provider);
        roundTrip(provider);
        legacyMigration(provider);
        malformedEntryDoesNotClearLibrary(provider);

        if (failures == 0) {
            System.out.println("===== NBT VERIFY OK (all checks passed) =====");
        } else {
            System.out.println("===== NBT VERIFY FAILED: " + failures + " check(s) =====");
            System.exit(1);
        }
    }

    /** 新格式：entry 顶层同时含 Slot 与 id，且不再嵌套 Item。 */
    private static void newFormatShape(final HolderLookup.Provider provider) {
        final GrowingUnitLibrary lib = new GrowingUnitLibrary(4);
        lib.setStackInSlot(0, new ItemStack(Items.DIAMOND, 3));
        lib.setStackInSlot(2, new ItemStack(Items.STICK, 5));
        final ListTag items = lib.serializeNBT(provider).getList("Items", Tag.TAG_COMPOUND);
        check(items.size() == 2, "新格式：Items 条目数 = 2（实际 " + items.size() + "）");
        for (int i = 0; i < items.size(); i++) {
            final CompoundTag entry = items.getCompound(i);
            check(entry.contains("Slot", Tag.TAG_INT), "新格式：entry[" + i + "] 含 Slot");
            check(entry.contains("id", Tag.TAG_STRING), "新格式：entry[" + i + "] 顶层含 id（而非嵌套 Item）");
            check(!entry.contains("Item"), "新格式：entry[" + i + "] 不含嵌套 Item");
        }
    }

    /** 序列化 → 反序列化：条目数量与内容一致。 */
    private static void roundTrip(final HolderLookup.Provider provider) {
        final GrowingUnitLibrary source = new GrowingUnitLibrary(4);
        source.setStackInSlot(0, new ItemStack(Items.DIAMOND, 3));
        source.setStackInSlot(2, new ItemStack(Items.STICK, 5));
        final CompoundTag saved = source.serializeNBT(provider);

        final GrowingUnitLibrary loaded = new GrowingUnitLibrary(1);
        loaded.deserializeNBT(provider, saved);
        check(loaded.getStackInSlot(0).getItem() == Items.DIAMOND && loaded.getStackInSlot(0).getCount() == 3,
            "往返：槽0 = 3x 钻石（实际 " + loaded.getStackInSlot(0) + "）");
        check(loaded.getStackInSlot(2).getItem() == Items.STICK && loaded.getStackInSlot(2).getCount() == 5,
            "往返：槽2 = 5x 木棍（实际 " + loaded.getStackInSlot(2) + "）");
        check(loaded.getSlots() > 2, "往返：读档后保留末尾空槽（getSlots=" + loaded.getSlots() + "）");
    }

    /** 旧格式 {Slot, Item:<stack>} 必须被识别并迁移，绝不丢弃。 */
    private static void legacyMigration(final HolderLookup.Provider provider) {
        final CompoundTag oldStack = new CompoundTag();
        oldStack.putString("id", "minecraft:diamond");
        oldStack.putInt("count", 7);
        final CompoundTag entry = new CompoundTag();
        entry.putInt("Slot", 1);
        entry.put("Item", oldStack);
        final ListTag list = new ListTag();
        list.add(entry);
        final CompoundTag legacy = new CompoundTag();
        legacy.putInt("Size", 4);
        legacy.put("Items", list);

        final GrowingUnitLibrary lib = new GrowingUnitLibrary(1);
        lib.deserializeNBT(provider, legacy);
        check(lib.getStackInSlot(1).getItem() == Items.DIAMOND && lib.getStackInSlot(1).getCount() == 7,
            "旧格式迁移：槽1 = 7x 钻石（实际 " + lib.getStackInSlot(1) + "）");
    }

    /** 个别坏条目只丢自己，同一批里的其它条目仍要读入。 */
    private static void malformedEntryDoesNotClearLibrary(final HolderLookup.Provider provider) {
        final CompoundTag good = new CompoundTag();
        good.putInt("Slot", 0);
        good.putString("id", "minecraft:stone");
        good.putInt("count", 2);
        final CompoundTag bad = new CompoundTag();
        bad.putInt("Slot", 1);
        bad.putString("garbage", "x");
        final ListTag list = new ListTag();
        list.add(good);
        list.add(bad);
        final CompoundTag tag = new CompoundTag();
        tag.putInt("Size", 4);
        tag.put("Items", list);

        final GrowingUnitLibrary lib = new GrowingUnitLibrary(1);
        lib.deserializeNBT(provider, tag);
        check(lib.getStackInSlot(0).getItem() == Items.STONE && lib.getStackInSlot(0).getCount() == 2,
            "坏条目不连坐：槽0 = 2x 石头仍读入");
    }

    private static void check(final boolean ok, final String msg) {
        if (ok) {
            System.out.println("[ OK ] " + msg);
        } else {
            failures++;
            System.out.println("[FAIL] " + msg);
        }
    }
}
