package cretae.cookiewyq.rs_create_compat.client.widget;

import cretae.cookiewyq.rs_create_compat.client.BusInterferenceOverlay;
import cretae.cookiewyq.rs_create_compat.network.SetImporterAutoCollectPacket;
import cretae.cookiewyq.rs_create_compat.network.SetImporterExecutorCategoriesPacket;
import cretae.cookiewyq.rs_create_compat.network.SetImporterForceNormalPacket;
import cretae.cookiewyq.rs_create_compat.network.SyncImporterExecutorModePacket;
import cretae.cookiewyq.rs_create_compat.support.RsccBusCategory;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * 输入总线界面上「类别条」的数据源 —— {@link ExporterExecutorRowWidget} 被复用的第二个宿主。
 *
 * <p><b>控件的绘制 / 命中 / 滚动 / 搜索逻辑一行都没有复制</b>：这里只提供数据与文案（{@link Source} 模式），
 * 于是「输出总线的类别条」和「输入总线的类别条」长得完全一样、交互完全一样，只有语义与文案相反。</p>
 *
 * <p><b>与输出总线侧的差异（都在 {@link #visible()} 之外的返回值里）</b>：</p>
 * <ol>
 *     <li>{@link #autoCraftingEnabled()} 恒为 {@code true}：收回方向没有「自动合成」门控
 *     （那是输出总线「先有自动合成才谈得上喂料」的闸门），因此界面不画暗红描边也不提示门控；</li>
 *     <li>{@link #targetStrategy()} 恒为 {@code false}：收回方向不显示「每批投入量 / 预估需求」
 *     （服务端快照里那两个数就是 0，界面会自动跳过对应行）；</li>
 *     <li>{@link #hasAutoToggle()} 恒为 {@code true}：本界面提供「自动 / 手动」开关（输出总线没有
 *     这个概念）；{@link #autoCollect()} 取服务端同步来的权威值（默认自动）；</li>
 *     <li>{@link #langPrefix()} 换成输入总线自己的前缀，于是同一份控件说出的每句话都是
 *     「收回」语义而不是「导出」语义。</li>
 * </ol>
 */
public final class ImporterExecutorBarSource implements ExporterExecutorRowWidget.Source {
    private static final String LANG = "gui.rs_create_compat.importer_executor.";

    /** 本界面所属菜单的容器 id（用来校验 S2C 同步是不是发给自己的那一台）。 */
    private final int containerId;

    public ImporterExecutorBarSource(final int containerId) {
        this.containerId = containerId;
    }

    @Override
    public boolean visible() {
        return SyncImporterExecutorModePacket.isExecutorMode(containerId);
    }

    @Override
    public List<RsccBusCategory> categories() {
        return SyncImporterExecutorModePacket.getCategories(containerId);
    }

    @Override
    public boolean autoCraftingEnabled() {
        return true; // 收回方向没有「自动合成」门控，界面不做任何门控提示
    }

    @Override
    public String linkedChamber() {
        return SyncImporterExecutorModePacket.getLinkedChamber(containerId);
    }

    @Override
    public boolean targetStrategy() {
        return false; // 收回方向不显示预估需求
    }

    @Override
    public void toggleSelection(final List<String> selectedCategoryIds) {
        PacketDistributor.sendToServer(
            new SetImporterExecutorCategoriesPacket(containerId, selectedCategoryIds));
    }

    /**
     * 「详细配置」子界面的编辑起点 = <b>服务端下发的那份「玩家存下来的勾选表」</b>。
     * <p>刻意<b>不</b>用 {@link #categories()} 里的 {@code selected}：全自动模式下服务端把「非输入类」
     * 标成已选（那只是展示态），拿它当起点会让玩家点一次「确定」就把原来的手动勾选冲掉。</p>
     */
    @Override
    public List<String> configSelection() {
        return SyncImporterExecutorModePacket.getManualSelection(containerId);
    }

    // ---- 「归属未确定 → 延长型已停用」提示：与输出总线侧完全同一套数据源与行为 ----

    @Override
    public boolean disabled() {
        return BusInterferenceOverlay.isDisabled(containerId);
    }

    @Override
    public int disabledReachableCount() {
        return BusInterferenceOverlay.reachableCount(containerId);
    }

    @Override
    public List<net.minecraft.core.BlockPos> disabledChambers() {
        return BusInterferenceOverlay.chambers(containerId);
    }

    @Override
    public boolean disabledTruncated() {
        return BusInterferenceOverlay.isTruncated(containerId);
    }

    @Override
    public boolean overlayShown() {
        return BusInterferenceOverlay.isShown(containerId);
    }

    @Override
    public void toggleOverlay() {
        BusInterferenceOverlay.toggle(containerId);
    }

    // ---- 「强制普通总线」开关（本轮新增；与输出总线侧完全同一套数据源与行为） ----

    @Override
    public boolean forceNormal() {
        return SyncImporterExecutorModePacket.isForceNormalBus(containerId);
    }

    @Override
    public boolean convertible() {
        // 用户第 2 条：只要线缆真的够得到总线输出执行仓，就常驻「普通 / 总线」按钮（与输出总线侧同一套数据）。
        return SyncImporterExecutorModePacket.isConvertible(containerId);
    }

    @Override
    public void toggleForceNormal(final boolean force) {
        PacketDistributor.sendToServer(new SetImporterForceNormalPacket(containerId, force));
    }

    @Override
    public boolean autoCollect() {
        return SyncImporterExecutorModePacket.isAutoCollect(containerId);
    }

    @Override
    public boolean hasAutoToggle() {
        return true;
    }

    /**
     * 切换「自动 / 手动」。
     * <p>刻意走<b>单独的包</b>（{@link SetImporterAutoCollectPacket}）：它只写开关，不动勾选表，
     * 因此来回切几次也不会把玩家在手动模式下勾好的类别冲掉。</p>
     */
    @Override
    public void toggleAuto(final boolean auto) {
        PacketDistributor.sendToServer(new SetImporterAutoCollectPacket(containerId, auto));
    }

    @Override
    public String langPrefix() {
        return LANG;
    }

    @Override
    public String barTitle() {
        return LANG + "locked";
    }

    @Override
    public String barTitleTip() {
        return LANG + "locked.tip";
    }

    @Override
    public String emptyTextKey() {
        return LANG + "no_categories";
    }

    /**
     * 有候选类别、但玩家一个都没勾时的提示（H 组：条上只列已勾选）。
     * <p>与输入总线自己的前缀成对，因此这一句说的仍然是「收回」语义（去「详细配置…」里勾要收回的类别）。</p>
     */
    @Override
    public String selectedEmptyTextKey() {
        return LANG + "no_selected_categories";
    }

    /**
     * 空条提示的第二行（「去哪改」）：与第一行同一套「先算可用宽再截断」的几何（见控件的
     * {@code drawEmptyHint}），因此这里只说最短的一句 —— 条内可用带只有约 128px。
     */
    @Override
    public String selectedEmptyActionTextKey() {
        return LANG + "no_selected_action";
    }

    /**
     * 某个类别的类型说明：全自动与手动说的是两回事，因此按当前模式分别给键 ——
     * 自动模式下一律「输入类不收 / 非输入类自动收」，手动模式才说「勾选的那一类怎么收」。
     */
    @Override
    public String typeTipKey(final RsccBusCategory category) {
        if (autoCollect()) {
            return LANG + (category.isInput() ? "auto.skip.tip" : "auto.collect.tip");
        }
        if (category.isFluidInput()) {
            return LANG + "fluids.tip";
        }
        return LANG + (category.isInput() ? "inputs.tip" : "intermediates.tip");
    }
}
