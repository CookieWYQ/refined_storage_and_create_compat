# -*- coding: utf-8 -*-
"""第 118 轮补丁：保持器目标数量走两个 short + 网络抽取重入保护。"""
import io
import os

os.chdir(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

BQ = "src/main/java/cretae/cookiewyq/rs_create_compat/block/entity/QuantityKeeperBlockEntity.java"
QF = "src/main/java/cretae/cookiewyq/rs_create_compat/menu/QuantityKeeperMenu.java"
AQ = "src/main/java/cretae/cookiewyq/rs_create_compat/block/entity/AdvancedQuantityKeeperBlockEntity.java"
AF = "src/main/java/cretae/cookiewyq/rs_create_compat/menu/AdvancedQuantityKeeperMenu.java"


def load(p):
    return io.open(p, encoding="utf-8").read()


def save(p, s):
    io.open(p, "w", encoding="utf-8", newline="\n").write(s)


def rep(s, old, new, path):
    assert old in s, "anchor missing in %s: %r" % (path, old[:140])
    return s.replace(old, new, 1)


GUARD_ITEM = """    private long extractMarkerItemFromNetwork(final long amount, final boolean simulate) {
        // 第 118 轮（只读复核 H2）：同线程重入保护 —— 见 KeeperNetworkReadGuard 的类注释。
        if (!cretae.cookiewyq.rs_create_compat.support.KeeperNetworkReadGuard.enter()) {
            return 0L;
        }
        try {
            return extractMarkerItemFromNetworkRaw(amount, simulate);
        } finally {
            cretae.cookiewyq.rs_create_compat.support.KeeperNetworkReadGuard.exit();
        }
    }

    private long extractMarkerItemFromNetworkRaw(final long amount, final boolean simulate) {"""

GUARD_FLUID = """    private long extractMarkerFluidFromNetwork(final long amount, final boolean simulate) {
        // 第 118 轮（只读复核 H2）：同线程重入保护 —— 见 KeeperNetworkReadGuard 的类注释。
        if (!cretae.cookiewyq.rs_create_compat.support.KeeperNetworkReadGuard.enter()) {
            return 0L;
        }
        try {
            return extractMarkerFluidFromNetworkRaw(amount, simulate);
        } finally {
            cretae.cookiewyq.rs_create_compat.support.KeeperNetworkReadGuard.exit();
        }
    }

    private long extractMarkerFluidFromNetworkRaw(final long amount, final boolean simulate) {"""

GUARD_ADV_ITEM = """    private long extractNetworkItem(final int slot, final long amount, final boolean simulate) {
        // 第 118 轮（只读复核 H2）：同线程重入保护 —— 见 KeeperNetworkReadGuard 的类注释。
        if (!cretae.cookiewyq.rs_create_compat.support.KeeperNetworkReadGuard.enter()) {
            return 0L;
        }
        try {
            return extractNetworkItemRaw(slot, amount, simulate);
        } finally {
            cretae.cookiewyq.rs_create_compat.support.KeeperNetworkReadGuard.exit();
        }
    }

    private long extractNetworkItemRaw(final int slot, final long amount, final boolean simulate) {"""

GUARD_ADV_FLUID = """    private long extractNetworkFluid(final int slot, final ResourceLocation id, final CompoundTag nbt,
                                     final long amount, final boolean simulate) {
        // 第 118 轮（只读复核 H2）：同线程重入保护 —— 见 KeeperNetworkReadGuard 的类注释。
        if (!cretae.cookiewyq.rs_create_compat.support.KeeperNetworkReadGuard.enter()) {
            return 0L;
        }
        try {
            return extractNetworkFluidRaw(slot, id, nbt, amount, simulate);
        } finally {
            cretae.cookiewyq.rs_create_compat.support.KeeperNetworkReadGuard.exit();
        }
    }

    private long extractNetworkFluidRaw(final int slot, final ResourceLocation id, final CompoundTag nbt,
                                        final long amount, final boolean simulate) {"""


def patch_basic_be():
    s = load(BQ)
    s = rep(s, """    /** 同类存储：流体/气体总容量（mB，128 桶）。 */
    public static final long FLUID_STORAGE_CAPACITY = 128_000L;""",
            """    /** 同类存储：流体/气体总容量（mB，128 桶）。 */
    public static final long FLUID_STORAGE_CAPACITY = 128_000L;

    /**
     * ContainerData 里「目标数量」的<b>低 16 位</b>下标（第 118 轮；原来是唯一的 index 0）。
     * <p>为什么必须拆成两个：MC 1.21.1 的 {@code ClientboundContainerSetDataPacket} 线上就是
     * <b>short</b>（{@code writeShort(value)} / {@code readShort()}）。目标数量是 int，
     * 一旦 > 32767 就会被截断 —— 流体目标按 <b>mB</b> 计，> 32.7 桶就中招：界面回填 0，
     * 玩家再点 ± 就会把服务端目标静默改小（开「过量销毁」时 = 按错误目标销毁真实资源）。</p>
     */
    public static final int DATA_TARGET_LOW = 0;

    /** ContainerData 里「目标数量」的<b>高 16 位</b>下标（第 118 轮新增；追加在末尾，不动既有下标）。 */
    public static final int DATA_TARGET_HIGH = 9;

    /** ContainerData 总长度（0..9）。 */
    public static final int DATA_COUNT = DATA_TARGET_HIGH + 1;""", BQ)
    s = rep(s, """    /** 供菜单同步的实时数据：0 = 目标数量，1 = 销毁开关，2 = 速度升级数，3 = 自动合成升级数，
     *  4 = 标记形态（0 物品 / 1 流体 / 2 气体），5 = 自动合成开关，6 = 是否有直接流体标记，
     *  7 = 是否堵塞。 */""",
            """    /** 供菜单同步的实时数据：0 = 目标数量的低 16 位，1 = 销毁开关，2 = 速度升级数，3 = 自动合成升级数，
     *  4 = 标记形态（0 物品 / 1 流体 / 2 气体），5 = 自动合成开关，6 = 是否有直接流体标记，
     *  7 = 是否堵塞，8 = 红石模式，9 = 目标数量的高 16 位（第 118 轮：绕开 ContainerData 的 short 截断）。 */""", BQ)
    s = rep(s, """                    case 8 -> com.refinedmods.refinedstorage.common.support.RedstoneModeSettings
                        .getRedstoneMode(getRedstoneMode());
                    default -> 0;""",
            """                    case 8 -> com.refinedmods.refinedstorage.common.support.RedstoneModeSettings
                        .getRedstoneMode(getRedstoneMode());
                    // 第 118 轮：目标数量的高 16 位（低位仍在 index 0；两者拼回完整 int）
                    case DATA_TARGET_HIGH -> targetAmount >>> 16;
                    default -> 0;""", BQ)
    s = rep(s, """            @Override
            public int getCount() {
                return 9;
            }""",
            """            @Override
            public int getCount() {
                return DATA_COUNT;
            }""", BQ)
    s = rep(s, "    private long extractMarkerItemFromNetwork(final long amount, final boolean simulate) {",
            GUARD_ITEM, BQ)
    s = rep(s, "    private long extractMarkerFluidFromNetwork(final long amount, final boolean simulate) {",
            GUARD_FLUID, BQ)
    save(BQ, s)


def patch_basic_menu():
    s = load(QF)
    s = rep(s, "this.data = keeper != null ? keeper.getContainerData() : new SimpleContainerData(9);",
            "this.data = keeper != null ? keeper.getContainerData()\n"
            "            : new SimpleContainerData(QuantityKeeperBlockEntity.DATA_COUNT);", QF)
    s = rep(s, """    public int getTargetAmount() {
        return data.get(0);
    }""",
            """    /**
     * 目标数量（第 118 轮：由低 / 高两个 16 位拼回完整 int）。
     * <p>为什么不能直接 {@code data.get(0)}：ContainerData 走 {@code ClientboundContainerSetDataPacket}
     * 是 <b>short</b>，> 32767 会被截断 ⇒ 客户端读到 0 / 负值 ⇒ 界面回填 0，点 ± 时把服务端目标改小。
     * 两个半段都按无符号 16 位掩码拼回，与服务端 {@code DATA_TARGET_HIGH} 口径一致。</p>
     */
    public int getTargetAmount() {
        final int low = data.get(QuantityKeeperBlockEntity.DATA_TARGET_LOW) & 0xFFFF;
        final int high = data.get(QuantityKeeperBlockEntity.DATA_TARGET_HIGH) & 0xFFFF;
        return (high << 16) | low;
    }""", QF)
    save(QF, s)


def patch_adv_be():
    s = load(AQ)
    s = rep(s, "    public static final int DATA_SPEED_UPGRADES = SLOT_COUNT * DATA_PER_SLOT;",
            """    /**
     * 每槽「目标数量」高 16 位的起始下标（第 118 轮；紧跟每槽 6 个常规下标之后，整块新增）。
     * <p>MC 1.21.1 的 ContainerData 线上是 short ⇒ int 目标数量 > 32767 会被截断（流体按 mB 计，
     * > 32.7 桶就中招：界面回填 0，点 ± 会把服务端目标静默改小，开「过量销毁」时按错目标销毁）。</p>
     */
    public static final int DATA_TARGET_HIGH_BASE = SLOT_COUNT * DATA_PER_SLOT;

    public static final int DATA_SPEED_UPGRADES = DATA_TARGET_HIGH_BASE + SLOT_COUNT;""", AQ)
    s = rep(s, """                return switch (index) {
                    case DATA_SPEED_UPGRADES -> getSpeedUpgradeCount();""",
            """                if (index >= DATA_TARGET_HIGH_BASE && index < DATA_TARGET_HIGH_BASE + SLOT_COUNT) {
                    // 第 118 轮：目标数量的高 16 位（低位仍在每槽的 DATA_SLOT_TARGET）
                    return targets[index - DATA_TARGET_HIGH_BASE] >>> 16;
                }
                return switch (index) {
                    case DATA_SPEED_UPGRADES -> getSpeedUpgradeCount();""", AQ)
    s = rep(s, "    private long extractNetworkItem(final int slot, final long amount, final boolean simulate) {",
            GUARD_ADV_ITEM, AQ)
    s = rep(s, """    private long extractNetworkFluid(final int slot, final ResourceLocation id, final CompoundTag nbt,
                                     final long amount, final boolean simulate) {""",
            GUARD_ADV_FLUID, AQ)
    save(AQ, s)


def patch_adv_menu():
    s = load(AF)
    s = rep(s, """    public int getTarget(final int row) {
        return data.get(row * AdvancedQuantityKeeperBlockEntity.DATA_PER_SLOT
            + AdvancedQuantityKeeperBlockEntity.DATA_SLOT_TARGET);
    }""",
            """    /**
     * 某行目标数量（第 118 轮：低 / 高两个 16 位拼回完整 int）。
     * <p>ContainerData 线上是 short，int 目标 > 32767 会被截断（流体按 mB，> 32.7 桶即中招）。</p>
     */
    public int getTarget(final int row) {
        final int low = data.get(row * AdvancedQuantityKeeperBlockEntity.DATA_PER_SLOT
            + AdvancedQuantityKeeperBlockEntity.DATA_SLOT_TARGET) & 0xFFFF;
        final int high = data.get(AdvancedQuantityKeeperBlockEntity.DATA_TARGET_HIGH_BASE + row) & 0xFFFF;
        return (high << 16) | low;
    }""", AF)
    save(AF, s)


patch_basic_be()
patch_basic_menu()
patch_adv_be()
patch_adv_menu()
print("keeper target sync + reentrancy guard patched")
