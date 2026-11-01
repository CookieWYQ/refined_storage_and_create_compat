package cretae.cookiewyq.rs_create_compat.support;

import cretae.cookiewyq.rs_create_compat.block.entity.CollectionCacheBlockEntity;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * <b>ghost 过滤标记的体积预算</b>（第 114 轮；常量口径第 114 轮复核后按实测重标定）。
 *
 * <h2>为什么需要「总量」而不仅是「单条」上限</h2>
 * <p>第 113 轮给区域标记加了单条上限，但那个上限只挡「一条特别大的标记」：
 * {@code MATCH_CAPACITY = 288} 条 × 每条都接近上限，仍然可能把一个组件撑到 MB 级。
 * 而选定器的标记写在物品 {@code CUSTOM_DATA} 里，<b>整件物品随背包 / 菜单槽 / 手持一起网络同步</b>
 * （已核到源码：{@code DataComponents.CUSTOM_DATA} 未声明 {@code networkSynchronized} 时会回退成
 * {@code fromCodecWithRegistries} 的双向同步），所以真正要管的是「整张表」的体积。</p>
 *
 * <h2>⚠ 口径：{@link net.minecraft.nbt.Tag#sizeInBytes()} 是 <b>JVM 堆估算</b>，不是线上字节</h2>
 * <p>MC 的 {@link CompoundTag#sizeInBytes()} 按堆上占用估算（48 基数；每个键 {28 + 2·名字长度 + 36}；
 * Int 12 / Long 16 / Byte 9 / String {36 + 2·长度} / List {37 + 4·元素数}…），实测约是
 * <b>NBT 线上字节的 8 倍</b>（一条普通标记：堆 648 B vs 线上 79 B）。这里保留这个便宜的度量
 * （不用为每次判定真的序列化一遍），但<b>常量按「线上字节」的意图 × {@link #WIRE_RATIO} 重标定</b>，
 * 并单独暴露 {@code *_WIRE_BYTES} 供文案 / 日志显示玩家看得懂的数字。</p>
 *
 * <h2>量级参考（据此选定常量，别拍脑袋改）</h2>
 * <ul>
 *     <li>普通物品标记（无数据组件）：堆约 <b>648 B</b>/条（线上约 79 B）；288 条 ≈ <b>188 KB 堆</b>
 *     （线上约 23 KB）⇒ 远低于 {@link #MAX_TOTAL_BYTES}，<b>正常配置不会被拦</b>；</li>
 *     <li>带少量组件（附魔 / 药水 / 自定义名）：线上约 100–300 B/条；</li>
 *     <li>成书 / 带 {@code minecraft:container} 的潜影盒：<b>不一定</b>被单条上限挡住，别把
 *     {@link #MAX_ENTRY_BYTES} 当成「凡是大组件物品都会被拦」的保证。实测口径：
 *     装满 27 格的潜影盒堆约 5 KB（线上约 0.7 KB）⇒ <b>会通过</b>；成书因为每页都是字符串，
 *     堆与线上几乎 1:1，要写到约 28 页才会碰到 2 KB 堆这一关；</li>
 *     <li>{@link #MAX_TOTAL_BYTES} ≈ 64 KB 线上：约等于 <b>800 条普通标记</b>，或几十条重组件标记。</li>
 * </ul>
 *
 * <h2>纪律</h2>
 * <ul>
 *     <li><b>只拦「新增 / 放大」</b>：读档、旧档迁移、换区域搬运既有标记<b>一律不删数据</b>
 *     （宁可超限也不静默毁玩家的配置；迁移路径的取舍见 {@code AreaMarks#migrateByIndex}）；</li>
 *     <li>所有写入口共用这里的一套常量与算法（{@link AreaMarks#isOverBudget}、
 *     {@link CollectionCacheBlockEntity#addMarkerForUi}、机器侧 48 格、服务端包处理），
 *     避免「客户端拦了、服务端没拦」这类口径漂移。</li>
 * </ul>
 */
public final class MarkerBudget {
    /**
     * 堆估算 → NBT 线上字节的经验倍率（第 114 轮复核实测：普通标记 648 / 79 ≈ 8.2）。
     * <p>只用于「把线上字节意图换算成 sizeInBytes 阈值」以及文案显示；<b>不是</b>精确换算，
     * 内容里字符串 / 列表占比不同会漂移：</p>
     * <ul>
     *     <li><b>数字主导</b>的负载（普通物品标记，多 Int/Long）→ 实际倍率 ≈ 8，用它换算合理；</li>
     *     <li><b>字符串主导</b>的负载（成书 / 长名字 / 配方 id）→ 堆与线上几乎 1:1（{@code 36 + 2n}
     *     两边同形），<b>倍率接近 1</b>。这种情况下 {@code MAX_ENTRY_BYTES / WIRE_RATIO}
     *     是「线上字节」的<b>下界</b>而不是上界：真正的线上体积可能比它小得多
     *     （第 117 轮独立复核指出，第 116 轮把它当上界写进注释是错的）。</li>
     * </ul>
     * <p>因此本常量只用于「给玩家一个量级」；阈值本身以堆估算为准，且文案里写的是「≈」。</p>
     */
    public static final int WIRE_RATIO = 8;

    /**
     * 单条标记的<b>堆估算</b>上限 = 线上约 2 KB（第 113 轮的口径，第 114 轮按 {@link #WIRE_RATIO} 重标定）。
     * <p>第 113 轮原值 2048（堆）实际只相当于线上约 256 B —— 连一本十几页的成书都会被拒，
     * 比当初要挡的「成书 / 带内容物容器撑爆同步」严格了近 8 倍。本轮按**真实线上意图**改成
     * {@code 2048 × 8}，语义与第 113 轮交付说明里对玩家说的「单条 2 KB」一致。</p>
     */
    public static final int MAX_ENTRY_BYTES = 2048 * WIRE_RATIO;

    /**
     * <b>整张标记表</b>的<b>堆估算</b>上限 = 线上约 64 KB（第 114 轮新增）。
     * <p>按「一次网络同步要带上的那份数据」算：高级版 = 选定器物品上<b>所有区域</b>的标记；
     * 基础版 = 该机器匹配区的所有条目。对 288 条普通标记（≈188 KB 堆）留了 2.8 倍余量，
     * 同时把「每条都接近单条上限 × 288」这种病态配置挡在门外。</p>
     */
    public static final int MAX_TOTAL_BYTES = 65536 * WIRE_RATIO;

    /** 单条标记的<b>线上字节</b>上限（文案 / 日志 / 文档用，= {@link #MAX_ENTRY_BYTES} / {@link #WIRE_RATIO}）。 */
    public static final int MAX_ENTRY_WIRE_BYTES = MAX_ENTRY_BYTES / WIRE_RATIO;

    /** 整张表的<b>线上字节</b>上限（文案 / 日志 / 文档用，= {@link #MAX_TOTAL_BYTES} / {@link #WIRE_RATIO}）。 */
    public static final int MAX_TOTAL_WIRE_BYTES = MAX_TOTAL_BYTES / WIRE_RATIO;

    private MarkerBudget() {
    }

    /** 一条标记编码后的堆估算字节数（{@code null} / 空条目 = 0）。 */
    public static int entryBytes(@Nullable final MarkerEntry entry, final int slot) {
        if (entry == null || entry.isEmpty()) {
            return 0;
        }
        // encodeMarkerTag 对 id==null 会 NPE，上面已早退
        final CompoundTag tag = CollectionCacheBlockEntity.encodeMarkerTag(entry, Math.max(0, slot));
        return tag == null ? 0 : Math.max(0, tag.sizeInBytes());
    }

    /** 一组标记（不含区域记录头）编码后的堆估算字节数合计。 */
    public static int entriesBytes(@Nullable final List<MarkerEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (int i = 0; i < entries.size(); i++) {
            total += entryBytes(entries.get(i), i);
        }
        return total;
    }

    /**
     * 「单条」判据的<b>唯一</b>比较实现（第 117 轮，独立复核 M4）。
     *
     * <p>以前 {@link #isStackTooBig} / {@link #isFluidTooBig} / {@link #isEntryTooBig} /
     * {@link #isTagTooBig} 各自写了一遍 {@code > MAX_ENTRY_BYTES}，而 {@code SetSequenceImportPacket}
     * 的整包预算里又写了一笔 —— 同一个阈值四份比较，改一处漏三处只是时间问题。
     * 现在全部走这里；调用方只负责<b>算出</b>字节数，比较只有一份。</p>
     */
    public static boolean isEntryBytesTooBig(final int bytes) {
        return bytes > MAX_ENTRY_BYTES;
    }

    /** 这条标记是否超过单条上限（{@code null} / 空条目 = 不超）。 */
    public static boolean isEntryTooBig(@Nullable final MarkerEntry entry) {
        return isEntryBytesTooBig(entryBytes(entry, 0));
    }

    /**
     * <b>任意</b> NBT（不是 {@link MarkerEntry}）按同一把单条尺判超限（第 115 轮）。
     * <p>用于「定量保持器」的流体 / 气体标记：那条路径的负载是客户端发来的
     * {@code CompoundTag}（{@code SetQuantityFluidMarkerPacket}），以前直接 {@code nbt.copy()}
     * 落盘、并原样随 {@code SyncQuantityFluidMarkerPacket} 回传 —— 与区域标记是<b>同一类风险</b>
     * （改包塞 KB 级 NBT ⇒ 存档膨胀 + 每次开界面重发），只是之前没轮到这台机器。
     * 只挡「新增 / 放大」：{@code null} / 空标签永远通过。</p>
     */
    public static boolean isTagTooBig(@Nullable final CompoundTag tag) {
        return tag != null && !tag.isEmpty() && isEntryBytesTooBig(tag.sizeInBytes());
    }

    /**
     * <b>整件物品</b>保存成 NBT 之后的<b>堆估算</b>字节数（第 116 轮从 {@link #isStackTooBig} 里抽出）。
     *
     * <p>抽出来是为了让「整包合计」也能用同一把尺算（{@code SetSequenceImportPacket} 一次导入会带
     * 几十条物品 + 每步的候选组）：单条上限只挡「一条特别大的」，而真正决定落盘 / 同步体积的是
     * <b>整个写入面</b> —— 与第 114 轮区域标记「整表预算」同一条推理。</p>
     *
     * @param registries 注册表上下文（{@code null} / 空物品 / 保存失败 ⇒ 返回 0，即「判不了不误拒」）
     */
    public static int stackBytes(@Nullable final ItemStack stack,
                                 @Nullable final HolderLookup.Provider registries) {
        if (stack == null || stack.isEmpty() || registries == null) {
            return 0;
        }
        try {
            return Math.max(0, stack.save(registries).sizeInBytes());
        } catch (final RuntimeException broken) {
            // 保存失败（坏数据组件）不是本判据要处理的事：交给原来的写入路径去记录 / 兜底。
            return 0;
        }
    }

    /**
     * <b>整件物品</b>按同一把单条尺判超限（第 115 轮）。
     * <p>用于「把物品设为标记」的 C2S 路径（{@code SetQuantityMarkerPacket} /
     * {@code SetAdvKeeperMarkerPacket}）：标记槽只关心「是哪件物品」，但物品带着<b>全部数据组件</b>
     * 落盘并随槽位同步 / 每次开界面重发；改包可以把一本几百页的成书塞进标记，此后每次同步都要带上它。
     * 判据是「保存成 NBT 之后的堆估算」，与区域标记同一把尺（不是凭空收紧：普通物品远低于上限）。</p>
     *
     * <p>第 116 轮起序列装配终端家族的 4 个 C2S 写入面（{@code SetSequenceGhostPacket} /
     * {@code SetSequenceImportPacket} / {@code CreateUnitPatternPacket} 的物品、
     * {@code SetStepFluidPacket} 的流体）也走这里 —— 那些数据同样会落进方块实体 NBT
     * 并随每次同步回发，属于<b>同一类风险</b>。</p>
     *
     * @param registries 注册表上下文（{@code null} ⇒ 判不了，按「不超」处理，绝不因此误拒）
     */
    public static boolean isStackTooBig(@Nullable final ItemStack stack,
                                        @Nullable final HolderLookup.Provider registries) {
        return isEntryBytesTooBig(stackBytes(stack, registries));
    }

    /**
     * <b>流体栈</b>保存成 NBT 之后的<b>堆估算</b>字节数（第 116 轮）。
     * <p>1.21.1 的 {@code FluidStack} 同样带 {@code DataComponentPatch}（可含任意数据组件），
     * 因此「流体标记」和物品标记是同一类风险：{@code SetStepFluidPacket} 把客户端给的流体写进
     * 单元样板的 {@code CustomData} 并永久落盘。</p>
     */
    public static int fluidBytes(@Nullable final FluidStack fluid,
                                 @Nullable final HolderLookup.Provider registries) {
        if (fluid == null || fluid.isEmpty() || registries == null) {
            return 0;
        }
        try {
            return Math.max(0, fluid.saveOptional(registries).sizeInBytes());
        } catch (final RuntimeException broken) {
            return 0;
        }
    }

    /** 流体栈按同一把单条尺判超限（第 116 轮；{@code null} / 空流体永远通过）。 */
    public static boolean isFluidTooBig(@Nullable final FluidStack fluid,
                                        @Nullable final HolderLookup.Provider registries) {
        return isEntryBytesTooBig(fluidBytes(fluid, registries));
    }

    /** 这个（堆估算的）字节数是否已经超过整表上限。 */
    public static boolean isTotalTooBig(final long bytes) {
        return bytes > MAX_TOTAL_BYTES;
    }
}
