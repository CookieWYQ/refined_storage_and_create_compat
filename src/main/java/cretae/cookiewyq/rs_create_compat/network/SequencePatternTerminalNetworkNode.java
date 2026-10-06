package cretae.cookiewyq.rs_create_compat.network;

import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.impl.node.AbstractNetworkNode;
import cretae.cookiewyq.rs_create_compat.block.entity.SequencePatternTerminalBlockEntity;

import javax.annotation.Nullable;

/**
 * 序列装配样板终端的 RS 网络节点：仅用于读取网络中的自动合成仓列表
 * （单元样板制作需选择目标自动合成仓），不执行逻辑。
 */
public class SequencePatternTerminalNetworkNode extends AbstractNetworkNode {
    @Nullable
    private SequencePatternTerminalBlockEntity blockEntity;

    public void setBlockEntity(final SequencePatternTerminalBlockEntity blockEntity) {
        this.blockEntity = blockEntity;
    }

    /** 本节点所属的终端方块实体（未绑定返回 null）。 */
    @Nullable
    public SequencePatternTerminalBlockEntity getBlockEntity() {
        return blockEntity;
    }

    @Nullable
    public Network getNetworkOrNull() {
        return network;
    }

    @Override
    public long getEnergyUsage() {
        return 1;
    }

    @Override
    public void doWork() {
        // 终端无需每 tick 逻辑
    }
}
