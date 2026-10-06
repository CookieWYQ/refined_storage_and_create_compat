package cretae.cookiewyq.rs_create_compat.support;

import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.storage.StorageNetworkComponent;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.common.autocrafting.autocrafter.AutocrafterBlockEntity;
import com.refinedmods.refinedstorage.common.support.resource.FluidResource;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 自动合成仓「内部存储开关」的公共逻辑（从原合成仓管理器界面按钮迁移而来，现由指令调用）。
 * <p>
 * 与原按钮的三条安全保证完全一致（<b>绝不销毁玩家资源</b>）：
 * <ol>
 *     <li>关闭前先用 {@link Action#SIMULATE} 检查仓内全部物品/流体能否一次性写回网络；</li>
 *     <li>任何一项放不下（或所在网络不可用）就<b>取消该网络分组的关闭</b>，内容原样留在仓内；</li>
 *     <li>确认可写后才 {@link Action#EXECUTE}：<b>先写网络、再按写入量扣内部存储</b>，
 *     最后复查仓内确实为空才把开关置为「关」。</li>
 * </ol>
 * 同一台仓的开关按「网络分组」整体处理：同一 RS 网络内的仓要么一起关，要么一起不关，避免半开半关。
 */
public final class AutocrafterStorageController {
    /** 单次指令最多作用的自动合成仓数量（防超大火柴盒半径卡服）。 */
    public static final int MAX_CRAFTERS = 512;

    private AutocrafterStorageController() {
    }

    /** 收集以 {@code center} 为中心、半径 {@code radius} 格内的全部自动合成仓（仅已加载区块）。 */
    public static List<AutocrafterBlockEntity> craftersNear(final ServerLevel level, final BlockPos center,
                                                           final double radius) {
        final AABB area = new AABB(center).inflate(Math.max(1.0, radius));
        final List<AutocrafterBlockEntity> result = new ArrayList<>();
        final int minChunkX = net.minecraft.core.SectionPos.blockToSectionCoord((int) Math.floor(area.minX));
        final int maxChunkX = net.minecraft.core.SectionPos.blockToSectionCoord((int) Math.ceil(area.maxX));
        final int minChunkZ = net.minecraft.core.SectionPos.blockToSectionCoord((int) Math.floor(area.minZ));
        final int maxChunkZ = net.minecraft.core.SectionPos.blockToSectionCoord((int) Math.ceil(area.maxZ));
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                final LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                if (chunk == null) {
                    continue;
                }
                for (final BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                    if (blockEntity instanceof AutocrafterBlockEntity crafter
                        && area.contains(crafter.getBlockPos().getX() + 0.5,
                        crafter.getBlockPos().getY() + 0.5,
                        crafter.getBlockPos().getZ() + 0.5)) {
                        result.add(crafter);
                        if (result.size() >= MAX_CRAFTERS) {
                            return result;
                        }
                    }
                }
            }
        }
        return result;
    }

    /** 该仓的内部存储开关是否开启。 */
    public static boolean isEnabled(final AutocrafterBlockEntity crafter) {
        return crafter instanceof RsccAutocrafterStorage storage && storage.rscc$isStorageEnabled();
    }

    /** 全部仓是否都已开启。 */
    public static boolean allEnabled(final List<AutocrafterBlockEntity> crafters) {
        for (final AutocrafterBlockEntity crafter : crafters) {
            if (!isEnabled(crafter)) {
                return false;
            }
        }
        return true;
    }

    /** 逐台开启（不去动仓内已有内容，绝不会丢东西）。 */
    public static void enableAll(final List<AutocrafterBlockEntity> crafters) {
        for (final AutocrafterBlockEntity crafter : crafters) {
            if (crafter instanceof RsccAutocrafterStorage storage) {
                storage.rscc$setStorageEnabled(true);
            }
        }
    }

    /**
     * 逐台关闭（按网络分组，先回写后关闭）。
     *
     * @return 成功关闭的仓数量；若某个网络分组回写失败，该组不会被关闭，也不计入返回值。
     */
    public static int disableAll(final List<AutocrafterBlockEntity> crafters) {
        final Map<Network, List<AutocrafterBlockEntity>> byNetwork = new LinkedHashMap<>();
        for (final AutocrafterBlockEntity crafter : crafters) {
            final Network network = crafter instanceof RsccAutocrafterStorage storage
                ? storage.rscc$getNetwork() : null;
            byNetwork.computeIfAbsent(network, key -> new ArrayList<>()).add(crafter);
        }
        int disabled = 0;
        for (final Map.Entry<Network, List<AutocrafterBlockEntity>> group : byNetwork.entrySet()) {
            if (disableGroup(group.getKey(), group.getValue())) {
                disabled += group.getValue().size();
            }
        }
        return disabled;
    }

    /** 关闭一个网络分组：先模拟、再执行、最后复查；任何一步不通过就整组保持开启。 */
    private static boolean disableGroup(@Nullable final Network network,
                                        final List<AutocrafterBlockEntity> crafters) {
        if (network == null) {
            return false; // 未接入网络：无法保证内容能安全回写，宁可不关
        }
        final StorageNetworkComponent storage = network.getComponent(StorageNetworkComponent.class);
        if (storage == null) {
            return false;
        }
        for (final AutocrafterBlockEntity crafter : crafters) {
            if (!canFlushAll(storage, crafter)) {
                return false;
            }
        }
        for (final AutocrafterBlockEntity crafter : crafters) {
            flushAll(storage, crafter);
        }
        for (final AutocrafterBlockEntity crafter : crafters) {
            if (!isEmpty(crafter)) {
                return false; // 理论不可达：确认空空如也才关，避免把残料困在没有网络的仓里
            }
        }
        for (final AutocrafterBlockEntity crafter : crafters) {
            if (crafter instanceof RsccAutocrafterStorage storageAccess) {
                storageAccess.rscc$setStorageEnabled(false);
            }
        }
        return true;
    }

    /** 模拟检查：内部存储里的全部物品/流体能否都写进网络。 */
    private static boolean canFlushAll(final StorageNetworkComponent storage,
                                       final AutocrafterBlockEntity crafter) {
        final RsccUnboundedItemStorage items = handlers(crafter).items();
        for (int slot = 0; slot < items.getSlots(); slot++) {
            final ItemStack stack = items.getStackInSlot(slot);
            if (stack.isEmpty()) {
                continue;
            }
            final long accepted = storage.insert(ItemResource.ofItemStack(stack), stack.getCount(),
                Action.SIMULATE, Actor.EMPTY);
            if (accepted < stack.getCount()) {
                return false;
            }
        }
        for (final FluidStack fluid : handlers(crafter).tank().getTanksSnapshot()) {
            if (fluid.isEmpty()) {
                continue;
            }
            final long accepted = storage.insert(new FluidResource(fluid.getFluid(), fluid.getComponentsPatch()),
                fluid.getAmount(), Action.SIMULATE, Actor.EMPTY);
            if (accepted < fluid.getAmount()) {
                return false;
            }
        }
        return true;
    }

    /** 真正写回网络：先 insert 成功，再按成功数量扣除内部存储，绝不先扣后写。 */
    private static void flushAll(final StorageNetworkComponent storage, final AutocrafterBlockEntity crafter) {
        final RsccUnboundedItemStorage items = handlers(crafter).items();
        for (int slot = 0; slot < items.getSlots(); slot++) {
            final ItemStack stack = items.getStackInSlot(slot);
            if (stack.isEmpty()) {
                continue;
            }
            final long accepted = storage.insert(ItemResource.ofItemStack(stack), stack.getCount(),
                Action.EXECUTE, Actor.EMPTY);
            if (accepted > 0) {
                items.extractItem(slot, (int) accepted, false);
            }
        }
        final RsccUnboundedFluidStorage tank = handlers(crafter).tank();
        for (final FluidStack fluid : tank.getTanksSnapshot()) {
            if (fluid.isEmpty()) {
                continue;
            }
            final long accepted = storage.insert(new FluidResource(fluid.getFluid(), fluid.getComponentsPatch()),
                fluid.getAmount(), Action.EXECUTE, Actor.EMPTY);
            if (accepted > 0) {
                tank.drain(fluid.copyWithAmount((int) accepted), IFluidHandler.FluidAction.EXECUTE);
            }
        }
    }

    /** 内部存储是否已空（物品 + 流体）。 */
    private static boolean isEmpty(final AutocrafterBlockEntity crafter) {
        final RsccUnboundedItemStorage items = handlers(crafter).items();
        for (int slot = 0; slot < items.getSlots(); slot++) {
            if (!items.getStackInSlot(slot).isEmpty()) {
                return false;
            }
        }
        return handlers(crafter).tank().isEmpty();
    }

    private record Handlers(RsccUnboundedItemStorage items, RsccUnboundedFluidStorage tank) {
    }

    private static Handlers handlers(final AutocrafterBlockEntity crafter) {
        final RsccAutocrafterStorage accessor = (RsccAutocrafterStorage) crafter;
        return new Handlers(accessor.rscc$getOutputStorage(), accessor.rscc$getOutputTank());
    }
}
