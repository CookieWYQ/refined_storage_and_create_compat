package cretae.cookiewyq.rs_create_compat.client;

import cretae.cookiewyq.rs_create_compat.support.RsccShortagePolicy;

/**
 * 客户端只读镜像：<b>序列装配缺料处置策略</b>（服务端权威，见 {@link RsccShortagePolicy}）。
 *
 * <p>自动合成监视器界面上的「缺料处置」开关按钮就渲染这一个值 —— 客户端<b>从不</b>自己改它，
 * 只做两件事：① 点一下 → 发 {@code SetShortageModePacket}；② 收到 {@code SyncShortageModePacket}
 * 就把它写进来。因此单机 / 联机下的显示都与服务端存档一致，不存在「点了没反应却看着像生效」。</p>
 *
 * <p>默认值与服务端默认档一致（{@link RsccShortagePolicy.Mode#SUSPEND}），
 * 因此快照还没到之前按钮显示的就是服务端实际行为，不会出现「显示等待、实际挂起」的错位。</p>
 */
public final class ShortageModeClient {
    private static volatile int mode = RsccShortagePolicy.Mode.SUSPEND.ordinal();

    private ShortageModeClient() {
    }

    /** S2C 收包后写入只读镜像。 */
    public static void set(final int ordinal) {
        if (resolve(ordinal) != null) {
            mode = ordinal;
        }
    }

    /** 当前策略（快照缺席时 = 服务端默认档）。 */
    public static RsccShortagePolicy.Mode mode() {
        final RsccShortagePolicy.Mode resolved = resolve(mode);
        return resolved == null ? RsccShortagePolicy.Mode.SUSPEND : resolved;
    }

    /** 序号 → 枚举（越界按「不认识」处理，绝不抛异常）。 */
    private static RsccShortagePolicy.Mode resolve(final int ordinal) {
        final RsccShortagePolicy.Mode[] values = RsccShortagePolicy.Mode.values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : null;
    }
}
