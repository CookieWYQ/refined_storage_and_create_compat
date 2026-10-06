package cretae.cookiewyq.rs_create_compat.support;

import org.jetbrains.annotations.Nullable;

/**
 * 序列执行仓「原料供应策略」全局二档（服务端权威，按存档持久化）。
 *
 * <ul>
 *     <li>{@link #MATERIALS} <b>只管输出原料</b>：不看概率、也不主动发起自动合成，
 *     只把 RS 网络里已有的、被输出总线选中的输入原料按「每批需求 × 1」搬进执行舱内部存储并导出；
 *     供应是否继续沿用既有的「自动合成门控」（网络里有进行中的任务就供应）。</li>
 *     <li>{@link #TARGET} <b>直到目标产物达标</b>（默认）：持续供应，且当某个输入原料在网络里
 *     不足 / 缺失时，按预估需求向 RS 自动合成发起请求补齐（{@code ensureTask}），
 *     因此只要最终产物的自动合成任务还没达标，原料就会被不停补齐；
 *     界面同时显示每个类别的<b>预估需求</b>并提示「因概率原因实际可能超出」。</li>
 * </ul>
 */
public enum RsccSupplyStrategy {
    MATERIALS("materials"),
    TARGET("target");

    private final String id;

    RsccSupplyStrategy(final String id) {
        this.id = id;
    }

    /** 指令取值 / 反馈用的稳定 id（小写）。 */
    public String id() {
        return id;
    }

    /** 语言键（message.rs_create_compat.supply.mode.<id>）。 */
    public String langKey() {
        return "message.rs_create_compat.supply.mode." + id;
    }

    /** 按指令取值解析；大小写不敏感，无法识别返回 null。 */
    @Nullable
    public static RsccSupplyStrategy byId(@Nullable final String raw) {
        if (raw == null) {
            return null;
        }
        for (final RsccSupplyStrategy strategy : values()) {
            if (strategy.id.equalsIgnoreCase(raw.trim())) {
                return strategy;
            }
        }
        return null;
    }
}
