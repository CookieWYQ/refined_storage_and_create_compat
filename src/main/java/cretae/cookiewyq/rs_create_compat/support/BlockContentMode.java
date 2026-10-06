package cretae.cookiewyq.rs_create_compat.support;

import org.jetbrains.annotations.Nullable;

/**
 * 「拆方块时内容物去向」全局三档策略。
 * <ul>
 *     <li>{@link #NETWORK}：写回所在 RS 网络（无网络时自动降级为 {@link #BLOCK}，不爆出）；</li>
 *     <li>{@link #DROP}：以掉落物形式爆出；</li>
 *     <li>{@link #BLOCK}：写进方块物品的 NBT，重新放下时原样恢复。</li>
 * </ul>
 * <p><b>流体硬规则</b>：流体永远不走 {@link #DROP} —— 能回网就回网，否则一律
 * {@link #BLOCK}（见 {@link BlockContentReleaser}）。</p>
 * <p><b>样板特例</b>：样板永不回网，全局为 {@link #NETWORK} 时样板降级为 {@link #BLOCK}。</p>
 */
public enum BlockContentMode {
    NETWORK("network"),
    DROP("drop"),
    BLOCK("block");

    private final String id;

    BlockContentMode(final String id) {
        this.id = id;
    }

    /** 指令取值 / 反馈用的稳定 id（小写）。 */
    public String id() {
        return id;
    }

    /** 语言键后缀（message.rs_create_compat.blockcontent.mode.<id>）。 */
    public String langKey() {
        return "message.rs_create_compat.blockcontent.mode." + id;
    }

    /** 按指令取值解析；大小写不敏感，无法识别返回 null。 */
    @Nullable
    public static BlockContentMode byId(@Nullable final String raw) {
        if (raw == null) {
            return null;
        }
        for (final BlockContentMode mode : values()) {
            if (mode.id.equalsIgnoreCase(raw.trim())) {
                return mode;
            }
        }
        return null;
    }
}
