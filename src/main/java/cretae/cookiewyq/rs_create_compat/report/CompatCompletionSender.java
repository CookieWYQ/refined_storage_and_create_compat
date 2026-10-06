package cretae.cookiewyq.rs_create_compat.report;

import cretae.cookiewyq.rs_create_compat.network.CompletionBannerPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * 完成横幅的服务器端出口：把 {@link CompletionBannerPayload.Row} 列表发给指定半径内的玩家，
 * 客户端会以仿 RS 的 Toast 呈现（可含物品图标）。执行器/装填器/加农炮的完成汇报均经此广播。
 */
public final class CompatCompletionSender {
    private CompatCompletionSender() {
    }

    /** 把横幅发给以 center 为中心 radiusSq 平方格内的所有玩家。 */
    public static void sendToNearby(final ServerLevel level, final BlockPos center, final int radiusSq,
                                    final List<CompletionBannerPayload.Row> rows) {
        if (rows.isEmpty()) {
            return;
        }
        final CompletionBannerPayload payload = new CompletionBannerPayload(rows);
        for (final ServerPlayer player : level.players()) {
            if (player.distanceToSqr(center.getX() + 0.5, center.getY() + 0.5, center.getZ() + 0.5) <= radiusSq) {
                PacketDistributor.sendToPlayer(player, payload);
            }
        }
    }

    /** 生成一行“物品图标 + 文字”段序列（按给定顺序）。 */
    public static List<CompletionBannerPayload.Row> singleRow(final int color,
                                                              final List<ItemStack> icons,
                                                              final List<String> texts) {
        final List<CompletionBannerPayload.Row> rows = new ArrayList<>();
        rows.add(CompletionBannerPayload.Row.segments(color, icons, texts));
        return rows;
    }

    /** 把数量格式化为适合横幅展示的紧凑文本（千分位省略、上万用 x.x万）。 */
    public static String fmtCount(final long count) {
        if (count < 10000) {
            return Long.toString(count);
        }
        return (count / 10000) + "." + ((count % 10000) / 1000) + "万";
    }
}
