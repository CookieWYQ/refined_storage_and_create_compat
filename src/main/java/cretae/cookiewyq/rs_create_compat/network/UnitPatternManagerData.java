package cretae.cookiewyq.rs_create_compat.network;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.FriendlyByteBuf;

/**
 * 「单元样板管理舱」的界面结构数据（S2C，随 {@code openMenu} 的额外数据下发）。
 *
 * <p><b>为什么需要它</b>：本界面照抄 RS 自动合成管理器的「拉伸 + 分组」布局，
 * 槽位个数取决于网络里有多少台执行舱（每台 54 格），客户端<b>无法自行推导</b>；
 * 而原版槽位同步要求客户端与服务端的槽位<b>下标顺序完全一致</b>。
 * 因此把「分组标题 + 该组槽位数 + 是否只出不进」随开界面一起发给客户端，
 * 由客户端按同一套规则重建镜像槽位（内容由原版同步写入）。</p>
 *
 * <p>本数据在界面打开时固定不变（不随网络变化重发），避免槽位数中途变化造成同步错位。</p>
 *
 * @param sections 分组结构（标题 + 槽位数 + 只出不进）
 * @param active   本节点是否已接入并通电
 */
public record UnitPatternManagerData(List<Section> sections, boolean active) {
    /** 空数据（客户端缺数据时的兜底）。 */
    public static final UnitPatternManagerData EMPTY =
        new UnitPatternManagerData(List.of(), false);

    /**
     * 一个分组 = 一行标题 + 紧随其后的槽位网格。
     *
     * @param name        分组标题（执行舱名字；合并视图为固定翻译键）
     * @param nameIsKey   true = {@code name} 是<b>翻译键</b>，由客户端翻译（避免服务端解析翻译得到裸键名）
     * @param recipeType  本组的配方类型 id（{@code create:deploying} 等；空串 = 未绑定 / 合并视图）。
     *                    由客户端翻译成<b>配方名称</b>显示，并在悬停提示里附原始 id。
     * @param slotCount   槽位数（服务端来自真实容器）
     * @param extractOnly true = 只出不进（跨仓合并视图：可取出、不可放入；合并映射无法唯一定位「放入哪台仓」）
     * @param hiddenSlots 该组里<b>不显示</b>的槽位下标（服务端按物品类型算出的「序列装配总样板」所在格）。
     *                    <b>只影响位置与可见性</b>：这些下标照旧各建一个槽位（挪到裁剪区外），
     *                    因此两端槽位下标一一对应、容器内容与已存数据不受影响。
     *                    <p>为什么随数据下发而不是各自判断：客户端的镜像容器在界面 init 时还没收到
     *                    原版槽位同步包（此刻是空的），自己按内容判断必然漏判 —— 服务端权威下发才能首帧生效。</p>
     */
    public record Section(String name, boolean nameIsKey, String recipeType,
                          int slotCount, boolean extractOnly, List<Integer> hiddenSlots) {
        /** 归一化：越界 / 重复 / 乱序的隐藏下标一律清掉（坏数据不得让界面摆出矛盾的网格）。 */
        public Section {
            final List<Integer> sanitized = new ArrayList<>();
            if (hiddenSlots != null) {
                for (final Integer index : hiddenSlots) {
                    if (index != null && index >= 0 && index < slotCount && !sanitized.contains(index)) {
                        sanitized.add(index);
                    }
                }
                sanitized.sort(java.util.Comparator.naturalOrder());
            }
            hiddenSlots = List.copyOf(sanitized);
        }
    }

    /** 写入额外数据缓冲（字段顺序必须与 {@link #read} 完全一致）。 */
    public void write(final FriendlyByteBuf buf) {
        buf.writeBoolean(active);
        buf.writeVarInt(sections.size());
        for (final Section section : sections) {
            buf.writeUtf(section.name());
            buf.writeBoolean(section.nameIsKey());
            buf.writeUtf(section.recipeType());
            buf.writeVarInt(section.slotCount());
            buf.writeBoolean(section.extractOnly());
            buf.writeVarInt(section.hiddenSlots().size());
            for (final int hidden : section.hiddenSlots()) {
                buf.writeVarInt(hidden);
            }
        }
    }

    /** 读取额外数据缓冲（字段顺序必须与 {@link #write} 完全一致）。 */
    public static UnitPatternManagerData read(final FriendlyByteBuf buf) {
        final boolean active = buf.readBoolean();
        final int count = buf.readVarInt();
        final List<Section> sections = new ArrayList<>(Math.max(0, count));
        for (int i = 0; i < count; i++) {
            final String name = buf.readUtf();
            final boolean nameIsKey = buf.readBoolean();
            final String recipeType = buf.readUtf();
            final int slotCount = buf.readVarInt();
            final boolean extractOnly = buf.readBoolean();
            final int hiddenCount = buf.readVarInt();
            // 预留容量按 slotCount 夹一次：归一化只会留下 [0, slotCount) 的下标，没必要按声明值开大数组
            final List<Integer> hidden =
                new ArrayList<>(Math.max(0, Math.min(hiddenCount, Math.max(0, slotCount))));
            for (int k = 0; k < hiddenCount; k++) {
                hidden.add(buf.readVarInt());
            }
            // 下标越界 / 重复由 Section 的归一化挡掉（服务端与客户端同一条规则）
            sections.add(new Section(name, nameIsKey, recipeType, slotCount, extractOnly, hidden));
        }
        return new UnitPatternManagerData(sections, active);
    }
}
