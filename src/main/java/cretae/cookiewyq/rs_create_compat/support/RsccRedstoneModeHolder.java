package cretae.cookiewyq.rs_create_compat.support;

import com.refinedmods.refinedstorage.common.support.RedstoneMode;

/**
 * 机器菜单的红石模式读取入口：界面每 tick 从这里取服务端同步下来的模式值刷新控件显示。
 *
 * <p>为什么需要它：本模组菜单继承原版 {@code AbstractContainerMenu}（不是 RS 的
 * {@code AbstractBaseContainerMenu}），RS 的属性同步机制不可用，故模式值随各机器既有的
 * {@code ContainerData} 数据槽一起下发；控件显示则复用 RS 原版控件
 * （{@code client.widget.RsccRedstoneModeButton}）。</p>
 */
public interface RsccRedstoneModeHolder {
    /** 当前红石模式（服务端权威值的客户端副本；服务端侧即实时值）。 */
    RedstoneMode rscc$getRedstoneMode();
}
