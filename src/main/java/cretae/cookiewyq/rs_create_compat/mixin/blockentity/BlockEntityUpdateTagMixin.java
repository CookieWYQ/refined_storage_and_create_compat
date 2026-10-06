package cretae.cookiewyq.rs_create_compat.mixin.blockentity;

import cretae.cookiewyq.rs_create_compat.support.RsccCamouflageAttachment;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 把「这一格的伪装材质」<b>并进区块数据包要带走的方块实体 NBT</b>
 * —— 修「物理化的一瞬间外壳直接消失」的那个洞。
 *
 * <h2>洞在哪（2026-09-30 实测取证）</h2>
 * <p>外壳材质存在被裹方块自己的方块实体上（{@link RsccCamouflageAttachment}），
 * 平时由 NeoForge 的附件同步下发。而 NeoForge 的下发<b>只有一个触发点</b>：
 * {@code AttachmentSync#onChunkSent} 订阅的 {@code ChunkWatchEvent.Sent} ——
 * 它只在<b>原版 {@code ChunkMap} 发送区块</b>时触发。</p>
 * <p>Sable（物理化模组）的子关卡<b>不走原版 ChunkMap</b>：
 * {@code SubLevelTrackingSystem#sendFullSync} 自己拼
 * {@code player.connection.send(new ClientboundBundlePacket(...))}，其中一包是
 * {@code SubLevelPlayerChunkSender#sendChunk} 新建的原版
 * {@code ClientboundLevelChunkWithLightPacket}；
 * 对 Sable / Simulated / Aeronautics 三个 jar 做全量二进制扫描，<b>{@code ChunkWatchEvent} 零命中</b>。
 * 于是物理化后客户端的方块实体<b>拿不到附件</b> → 外壳取数为 {@code null} → <b>外壳消失</b>。</p>
 *
 * <h2>为什么改 {@code getUpdateTag} 就能修好</h2>
 * <p>区块数据包给每个方块实体带的 NBT 就是
 * {@code ClientboundLevelChunkPacketData$BlockEntityInfo#create(BlockEntity)} 里那一句
 * {@code be.getUpdateTag(provider)}；客户端会把这半段 NBT 交给
 * {@code BlockEntity#loadWithComponents → loadAdditional}，而 NeoForge 在
 * {@code loadAdditional} 里<b>无条件</b>读 {@code neoforge:attachments}
 * （{@code AttachmentHolder#deserializeAttachments}）。因此把材质补进
 * {@code getUpdateTag} 的返回值，它就会跟着<b>任何</b>发送方发出去的区块一起到达客户端。</p>
 *
 * <h2>为什么注入在基类上就够</h2>
 * <p>被裹族里只有 RS 系（线缆 / 输入总线 / 输出总线，{@code AbstractNetworkNodeContainerBlockEntity}）
 * 没有覆写 {@code getUpdateTag}，走的就是本类的默认实现（1.21.1 实测：直接返回空 CompoundTag）——
 * 它们正是「一物理化就消失」的那一批。Create 系的管道 / 传动杆由
 * {@code SyncedBlockEntity#getUpdateTag} 覆写，而它内部调 {@code saveAdditional}，
 * NeoForge 的 {@code saveAdditional} 本来就会写 {@code neoforge:attachments}，
 * 因此那一族无需本注入、也不会被本注入影响（覆写的方法体里根本不会执行到这里）。</p>
 *
 * <h2>代价</h2>
 * <p>每个方块实体、每次区块发包各一次 {@code getExistingDataOrNull}：
 * 没被裹住的方块实体只是一次 {@code attachments == null} 判空，随即返回，
 * 不会多写一个字节。</p>
 */
@Mixin(BlockEntity.class)
public abstract class BlockEntityUpdateTagMixin {

    /**
     * {@code getUpdateTag} 返回前补写伪装：没有材质时原样返回（连 NBT 都不碰）。
     *
     * <p>就地修改返回的那份 {@link CompoundTag} 即可 —— 调用方拿到的就是同一个实例，
     * 所以不需要 {@code cancellable} / {@code setReturnValue}，
     * 也就不会干扰任何覆写者后续再往同一个 tag 里追加自己的数据。</p>
     */
    @Inject(method = "getUpdateTag(Lnet/minecraft/core/HolderLookup$Provider;)Lnet/minecraft/nbt/CompoundTag;",
        at = @At("RETURN"))
    private void rscc$carryCamouflage(final HolderLookup.Provider registries,
                                      final CallbackInfoReturnable<CompoundTag> callback) {
        RsccCamouflageAttachment.writeIntoUpdateTag((IAttachmentHolder) (Object) this,
            callback.getReturnValue(), registries);
    }
}
