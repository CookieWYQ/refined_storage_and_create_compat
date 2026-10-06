package cretae.cookiewyq.rs_create_compat.support;

import com.refinedmods.refinedstorage.common.support.RedstoneMode;
import com.refinedmods.refinedstorage.common.support.RedstoneModeSettings;

/**
 * 本模组机器共用的「红石模式」约定。
 *
 * <p>语义与 RS 原版机器<b>完全一致</b>：{@link RedstoneMode#IGNORE 忽略} /
 * {@link RedstoneMode#HIGH 高电平工作} / {@link RedstoneMode#LOW 低电平工作}。
 * 因此这里不重复实现任何逻辑，只做两件事：</p>
 * <ul>
 *     <li>模式<b>存储与判定</b>直接复用 RS 的
 *     {@code com.refinedmods.refinedstorage.common.support.network.AbstractBaseNetworkNodeContainerBlockEntity}：
 *     字段 {@code redstoneMode}（默认 {@code IGNORE}），NBT 键 {@code rm}（经
 *     {@code writeConfiguration/readConfiguration} 随存档持久化），由 {@code RedstoneModeSettings}
 *     在枚举与 0/1/2 之间映射；</li>
 *     <li>模式<b>生效</b>由 RS 基类的 {@code calculateActive()} 完成：红石条件不满足 → 节点
 *     {@code active=false} → 所有机器的 {@code doWork()} 在开头直接返回（<b>停机但不销毁任何资源</b>）。</li>
 * </ul>
 *
 * <p>界面控件同样复用 RS 原版 {@code RedstoneModeSideButtonWidget}（含其贴图与本地化键），
 * 见 {@code client.widget.RsccRedstoneModeButton}。</p>
 */
public final class RsccRedstoneMode {
    /**
     * 红石模式切换用的菜单按钮 id：走原版 {@code AbstractContainerMenu#clickMenuButton} 通道，
     * <b>服务端权威</b>（切换后由服务端写回数据槽同步给客户端）。取值远离各机器既有按钮 id（0..31）。
     */
    public static final int BUTTON_ID = 900;

    private RsccRedstoneMode() {
    }

    /** 数据槽里存放的原始值（0/1/2）→ 枚举；越界值按 RS 规则回退为 {@code IGNORE}。 */
    public static RedstoneMode fromRawValue(final int rawValue) {
        return RedstoneModeSettings.getRedstoneMode(rawValue);
    }
}
