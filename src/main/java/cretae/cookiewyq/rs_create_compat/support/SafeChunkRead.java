package cretae.cookiewyq.rs_create_compat.support;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.jetbrains.annotations.Nullable;

/**
 * 「<b>绝不加载区块</b>」的安全读：只读取已经到达 {@link ChunkStatus#FULL} 的区块。
 *
 * <h2>为什么必须有这个类（第 138 轮）</h2>
 * <p>1.21.1 的 {@code Level#isLoaded(pos)} 用的是 {@code ChunkSource#hasChunk}，判断的是
 * <b>「holder 存在」</b>——一个<b>正在生成／加载流水线里、还没到 FULL</b> 的区块同样返回 {@code true}。
 * 紧跟其后的 {@code getBlockState / getBlockEntity / getChunkAt / getChunk(cx,cz)} 全部是
 * <b>阻塞读</b>：区块没到 FULL 时会在主线程同步等待它生成（还可能顺带加票据继续拉区块）。
 * 玩家登录／跨维度／区块批量加载时，这一组合会把主线程卡在区块生成上，表现为
 * 「几百 ms/tick」甚至被看门狗判定为服务器卡死（第 137 轮崩溃报告即此类）。</p>
 *
 * <h2>本类的判据</h2>
 * <ul>
 *     <li>{@link #chunkNow} 用 {@code getChunk(x, z, FULL, false)}（{@code requireChunk = false}）：
 *     <b>只返回已存在且已到 FULL 的区块</b>，否则 {@code null}。不加载、不加票、不阻塞；</li>
 *     <li>{@link #blockStateNow} / {@link #blockEntityNow} 在拿不到 FULL 区块时返回 {@code null}
 *     （方块实体用 {@code EntityCreationType.CHECK}，已存在才返回、绝不创建）；</li>
 *     <li>{@link #hasChunkHolder} 是「区块在不在加载范围／流水线里」的<b>非阻塞</b>查询，
 *     只用来区分「未加载（跳过即可）」与「加载中（本 tick 先当看不见，下一 tick 再算）」。</li>
 * </ul>
 *
 * <p><b>用法约定</b>：所有「逐 tick 读邻块 / 范围内方块」的路径都必须走本类；读到 {@code null}
 * 一律按「这一 tick 看不见」处理，<b>不许</b>为了拿到值而退回 {@code isLoaded + getBlockState}。
 * 客户端渲染路径同理（{@code RenderChunkRegion} 等自有读法的除外）。</p>
 */
public final class SafeChunkRead {
    private SafeChunkRead() {
    }

    /**
     * 该坐标所在区块<b>已到 FULL</b> 时返回它，否则 {@code null}。
     * <p>不加载区块：{@code requireChunk = false}；再叠加 {@code instanceof LevelChunk} 兜底，
     * 防止任何实现返回「状态不足 FULL 的部分区块（ProtoChunk）」。</p>
     */
    @Nullable
    public static LevelChunk chunkNow(@Nullable final Level level, @Nullable final BlockPos pos) {
        if (level == null || pos == null) {
            return null;
        }
        final ChunkAccess chunk = level.getChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FULL, false);
        return chunk instanceof LevelChunk full ? full : null;
    }

    /**
     * 该坐标的区块是否<b>在加载范围里</b>（holder 存在；可能还没到 FULL）。<b>不加载、不阻塞</b>。
     * <p>用途：区分「真的没加载（跳过）」与「正在加载（记成 partial，下一 tick 再算）」。</p>
     */
    public static boolean hasChunkHolder(@Nullable final Level level, @Nullable final BlockPos pos) {
        return level != null && pos != null && level.hasChunkAt(pos);
    }

    /** 只读已 FULL 区块上的方块状态；区块未就绪返回 {@code null}（绝不触发加载）。 */
    @Nullable
    public static BlockState blockStateNow(@Nullable final Level level, @Nullable final BlockPos pos) {
        final LevelChunk chunk = chunkNow(level, pos);
        return chunk == null ? null : chunk.getBlockState(pos);
    }

    /** 只读已 FULL 区块上<b>已存在</b>的方块实体（{@code CHECK}，绝不创建）；区块未就绪返回 {@code null}。 */
    @Nullable
    public static BlockEntity blockEntityNow(@Nullable final Level level, @Nullable final BlockPos pos) {
        final LevelChunk chunk = chunkNow(level, pos);
        return chunk == null ? null : chunk.getBlockEntity(pos, LevelChunk.EntityCreationType.CHECK);
    }
}
