package cretae.cookiewyq.rs_create_compat.compat.crafter;

import com.refinedmods.refinedstorage.common.autocrafting.autocrafter.AutocrafterBlockEntity;
import com.refinedmods.refinedstorage.common.support.AbstractDirectionalBlock;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;
import com.simibubi.create.content.processing.basin.BasinOperatingBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

/**
 * 「RS 自动合成仓 → 它正对着的那台机器」的解析器：把 RS 的朝向语义与 Create 的方块实体
 * 分别解析出来，供派发（{@link CreateCraftDispatch}）与产物采集（{@link OutputReclaim}）共用。
 *
 * <h2>为什么朝向要自己解析</h2>
 * <p>RS 在 {@code AutocrafterBlockEntity#initialize}（build/rssrc/.../autocrafter/AutocrafterBlockEntity.java:385-393）
 * 里用 {@code worldPosition.relative(direction)} 定位「它正对的那一格」，方向取自方块状态
 * （{@link AbstractDirectionalBlock#tryExtractDirection}）。本类复刻同一套语义，
 * 因此派发目标与 RS 原版 sink 的目标<b>逐格一致</b>，不会出现「我们喂 A、RS 喂 B」的分裂。</p>
 *
 * <h2>串联（chain）</h2>
 * <p>RS 允许最多 8 台自动合成仓串成一排，只有<b>链头</b>的 sink 有效
 * （{@code getChainingRoot(...)}，AutocrafterBlockEntity.java:181-195 / :520-561）。本类用同样的
 * 「沿朝向一路走到不再是自动合成仓」的规则求出链头，因此从链上任意一台都能得到同一台机器。</p>
 */
public final class CreateCraftTargets {
    /** 与 RS 一致的串联上限（AutocrafterBlockEntity.MAX_CHAINED_AUTOCRAFTERS）。 */
    private static final int MAX_CHAIN = 8;

    private CreateCraftTargets() {
    }

    /** 求串联链头；解析不出来时退回传入的这台（与 RS 的 origin 兜底一致）。 */
    public static AutocrafterBlockEntity resolveRoot(final ServerLevel level,
                                                     final AutocrafterBlockEntity start) {
        AutocrafterBlockEntity current = start;
        for (int depth = 0; depth < MAX_CHAIN; depth++) {
            final Direction direction = AbstractDirectionalBlock.tryExtractDirection(current.getBlockState());
            if (direction == null) {
                return current;
            }
            final BlockPos nextPos = current.getBlockPos().relative(direction);
            if (!level.isLoaded(nextPos)) {
                return current;
            }
            final BlockEntity next = level.getBlockEntity(nextPos);
            if (!(next instanceof AutocrafterBlockEntity nextAutocrafter)) {
                return current;
            }
            current = nextAutocrafter;
        }
        return current;
    }

    /** 链头正对着的那一格（可能为 null）。 */
    @Nullable
    public static BlockEntity resolveTarget(final ServerLevel level, final AutocrafterBlockEntity root) {
        final Direction direction = AbstractDirectionalBlock.tryExtractDirection(root.getBlockState());
        if (direction == null) {
            return null;
        }
        final BlockPos targetPos = root.getBlockPos().relative(direction);
        if (!level.isLoaded(targetPos)) {
            return null;
        }
        return level.getBlockEntity(targetPos);
    }

    /**
     * 把「正对的那一格」解析成<b>搅拌盆</b>。
     *
     * <p>用户可能把自动合成仓贴在盆侧面，也可能贴在搅拌机（Mechanical Mixer）侧面/上面；
     * 后者本身没有仓储能力，真正的输入/输出在它正下方 2 格的盆里
     * （Create 的 {@code BasinOperatingBlockEntity#getBasin()} 就是这么找的：
     * build/createsrc/.../basin/BasinOperatingBlockEntity.java:175-182）。</p>
     */
    @Nullable
    public static BasinBlockEntity resolveBasin(@Nullable final BlockEntity target) {
        if (target instanceof BasinBlockEntity basin) {
            return basin;
        }
        if (target instanceof BasinOperatingBlockEntity mixer) {
            if (mixer.getLevel() == null) {
                return null;
            }
            final BlockEntity below = mixer.getLevel().getBlockEntity(mixer.getBlockPos().below(2));
            return below instanceof BasinBlockEntity basin ? basin : null;
        }
        return null;
    }
}
