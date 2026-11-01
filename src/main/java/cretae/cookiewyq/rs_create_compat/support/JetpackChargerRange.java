package cretae.cookiewyq.rs_create_compat.support;

import cretae.cookiewyq.rs_create_compat.block.entity.RangeChargerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;

/**
 * 「玩家此刻是否落在<b>某台范围充电器的范围框内</b>」的<b>服务端几何判定</b>（HUD 的「范围内」标注）。
 *
 * <h2>为什么由服务端算</h2>
 * <p>客户端不允许查方块实体（既不可信、也不保证区块已加载）；而范围框的大小来自充电器自己的
 * {@code rangeX/Y/Z} 与范围升级，只有服务端是权威。因此这里在服务端算一个布尔值，随
 * {@code network/JetpackStatusPacket} 下发，客户端只负责显示。</p>
 *
 * <h2>判定口径（与充电器完全同一套几何）</h2>
 * <p>复刻 {@code RangeChargerBlockEntity#scanPlayers} 的包围盒：
 * {@code halfX = rangeX/2} 后取 {@code [x-halfX, x+halfX+1]}（Y / Z 同理），再用
 * <b>玩家碰撞箱与它相交</b>（{@link AABB#intersects(AABB)}）—— 与充电器用
 * {@code level.getEntitiesOfClass(..., box, ...)} 挑玩家的判据逐字节一致，不会出现
 * 「HUD 说在范围内、充电器却不充」这种两套几何打架。
 * 另加两条与充电器一致的前置：<b>旁观者不算</b>；带 {@code creative_range_upgrade}
 * （{@link RangeChargerBlockEntity#hasInfiniteRange()}）的充电器对同维度任何玩家都算在范围内。</p>
 *
 * <h2>本判定<b>只回答「在不在范围里」</b>，不回答「会不会被充电」</h2>
 * <p>充电器是否真的给这件物品充能还取决于：它有没有电、红石是否允许、玩家的充电过滤是否拦下、
 * 单 tick 目标数是否已满、物品是否已满电…… 这些语义<b>不在这里复刻</b>（复刻一定会在某处说谎）。
 * 「是否正在被充能」是另一条独立判据：<b>本 tick 背包能量比上一 tick 变大</b>
 * （见 {@link JetpackFlightHandler} 与 {@code network/JetpackStatusPacket} 的类注释）。
 * HUD 上两个标记因此分开显示：充电时「范围内 · 充电中」，只在范围内时「范围内」。</p>
 *
 * <h2>扫描范围与已知边界</h2>
 * <p>为了绝不主动加载区块、也不做全维度扫描，这里只在玩家<b>周围 {@link #SCAN_CHUNK_RADIUS} 个区块</b>
 * （默认 4 ⇒ 半径 64 格）内、<b>已经加载</b>的区块里找充电器方块实体（
 * {@code getChunkSource().getChunkNow(...)} 不会触发加载）。
 * 因此「充电器在 64 格外、但范围升级后能覆盖到玩家」这一种情形下，几何判定会漏报
 * （显示「」而不是「范围内」）；但<b>充电判定不受影响</b>（它读的是物品能量本身），
 * 那种情况下玩家仍会看到「充电中」。这个取舍写在调用点也写在完成回报的「未验证 / 已知限制」里。</p>
 */
public final class JetpackChargerRange {
    /** 扫描半径（区块）：4 ⇒ 64 格。只查已加载区块，绝不触发区块加载。 */
    public static final int SCAN_CHUNK_RADIUS = 4;

    private JetpackChargerRange() {
    }

    /** 玩家是否在任意一台范围充电器的范围框内（服务端权威；旁观者恒 {@code false}）。 */
    public static boolean inRange(final ServerPlayer player) {
        if (player == null || player.isSpectator()) {
            return false;
        }
        if (!(player.level() instanceof ServerLevel level)) {
            return false;
        }
        final int centerChunkX = player.chunkPosition().x;
        final int centerChunkZ = player.chunkPosition().z;
        final AABB playerBox = player.getBoundingBox();
        for (int dx = -SCAN_CHUNK_RADIUS; dx <= SCAN_CHUNK_RADIUS; dx++) {
            for (int dz = -SCAN_CHUNK_RADIUS; dz <= SCAN_CHUNK_RADIUS; dz++) {
                final LevelChunk chunk = level.getChunkSource().getChunkNow(centerChunkX + dx, centerChunkZ + dz);
                if (chunk == null) {
                    continue; // 未加载：跳过（不主动加载区块）
                }
                for (final BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                    if (!(blockEntity instanceof RangeChargerBlockEntity charger)) {
                        continue;
                    }
                    // 无限范围升级：同维度任何玩家都算「在范围内」（与充电器的扫描口径一致）
                    if (charger.hasInfiniteRange()) {
                        return true;
                    }
                    if (playerBox.intersects(rangeBox(charger))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 充电器给「玩家」用的范围框 —— 逐字复刻 {@code RangeChargerBlockEntity#scanPlayers}
     * 里构造的那个 {@link AABB}（含 {@code +1}，两个口径必须一致）。
     */
    private static AABB rangeBox(final RangeChargerBlockEntity charger) {
        final BlockPos pos = charger.getBlockPos();
        final int halfX = charger.getRangeX() / 2;
        final int halfY = charger.getRangeY() / 2;
        final int halfZ = charger.getRangeZ() / 2;
        return new AABB(
            pos.getX() - halfX, pos.getY() - halfY, pos.getZ() - halfZ,
            pos.getX() + halfX + 1, pos.getY() + halfY + 1, pos.getZ() + halfZ + 1
        );
    }
}
