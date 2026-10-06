package cretae.cookiewyq.rs_create_compat.support;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * 「生成单元样板时自动跳过重复样板」的默认档载体（服务端权威；<b>已无界面入口，仅作内部默认来源</b>）。
 *
 * <h2>为什么保留它而不删</h2>
 * <p>① 生成路径（{@code CreateUnitPatternPacket#handle}）仍要读这个档位来决定「新建时要不要查重」；
 * ② <b>不破坏旧存档</b>：玩家以前用管理舱的全局开关关掉过查重（存档里 {@code skipDuplicates=false}），
 * 删掉这份数据会把它悄悄恢复成「开」，属于行为回归。</p>
 *
 * <p><b>为什么不再有写入入口</b>：管理舱里的那个全局勾选框与序列装配终端的<b>「每一步一个跳过重复」</b>
 * 开关重复（用户原话：「加了这个开关之后不是和序列装配终端的那个重复了吗？」），因此<b>界面被删除</b>；
 * 新步骤的默认档改为固定「开」（见 {@code SequencePatternTerminalBlockEntity#defaultStepSkipDuplicate()}），
 * 每步的开关仍由终端逐步骤保存与切换，本类不再参与。</p>
 *
 * <p>存档位置与读取口径不变：主世界 {@code DimensionDataStorage}，数据名
 * {@code rs_create_compat_unit_pattern_dedupe}；只在服务端取真值，客户端 / 取不到服务器时回落默认档。</p>
 */
public final class UnitPatternDedupePolicy extends SavedData {
    /** 存档内的数据名。 */
    private static final String DATA_NAME = "rs_create_compat_unit_pattern_dedupe";
    private static final String TAG_SKIP_DUPLICATES = "skipDuplicates";

    /** 默认档：开 = 发现语义完全相同的样板就不再生成（可随时关闭）。 */
    public static final boolean DEFAULT_SKIP_DUPLICATES = true;

    private boolean skipDuplicates = DEFAULT_SKIP_DUPLICATES;

    /** 取（或首次创建）主世界里的开关数据。 */
    public static UnitPatternDedupePolicy get(final MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(UnitPatternDedupePolicy::new, UnitPatternDedupePolicy::load),
            DATA_NAME
        );
    }

    private static UnitPatternDedupePolicy load(final CompoundTag tag, final HolderLookup.Provider registries) {
        final UnitPatternDedupePolicy data = new UnitPatternDedupePolicy();
        if (tag.contains(TAG_SKIP_DUPLICATES)) {
            data.skipDuplicates = tag.getBoolean(TAG_SKIP_DUPLICATES);
        }
        return data;
    }

    /**
     * 当前是否「自动跳过重复样板」；只在服务端有效
     * （客户端 / 取不到服务器时回落到默认档，与 {@link RsccShortagePolicy#mode(Level)} 同一口径：
     * 绝不在客户端缓存真值，避免单机 / 联机下客户端读到过期值）。
     */
    public static boolean skipDuplicates(final Level level) {
        if (level instanceof ServerLevel server) {
            final MinecraftServer mc = server.getServer();
            if (mc != null) {
                return get(mc).skipDuplicates;
            }
        }
        return DEFAULT_SKIP_DUPLICATES;
    }

    @Override
    public CompoundTag save(final CompoundTag tag, final HolderLookup.Provider registries) {
        tag.putBoolean(TAG_SKIP_DUPLICATES, skipDuplicates);
        return tag;
    }
}
