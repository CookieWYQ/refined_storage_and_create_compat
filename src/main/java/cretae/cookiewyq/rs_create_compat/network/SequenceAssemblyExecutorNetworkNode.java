package cretae.cookiewyq.rs_create_compat.network;

import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.impl.node.patternprovider.PatternProviderNetworkNode;
import cretae.cookiewyq.rs_create_compat.block.entity.SequenceAssemblyExecutorBlockEntity;

import javax.annotation.Nullable;

/**
 * 序列装配样板库的 RS 网络节点：继承 {@link PatternProviderNetworkNode}，
 * 让「总样板」作为 EXTERNAL 样板注册进网络自动合成组件；
 * 任意终端对总样板产物的请求都会路由到此节点，任务步进调用方块实体的 sink。
 */
public class SequenceAssemblyExecutorNetworkNode extends PatternProviderNetworkNode {
    @Nullable
    private SequenceAssemblyExecutorBlockEntity blockEntity;

    public SequenceAssemblyExecutorNetworkNode() {
        super(10, cretae.cookiewyq.rs_create_compat.block.entity.SequenceAssemblyExecutorBlockEntity.PATTERN_SLOTS);
    }

    public void setBlockEntity(final SequenceAssemblyExecutorBlockEntity blockEntity) {
        this.blockEntity = blockEntity;
    }

    @Nullable
    public SequenceAssemblyExecutorBlockEntity getBlockEntity() {
        return blockEntity;
    }

    @Nullable
    public Network getNetworkOrNull() {
        return network;
    }
}
