package cretae.cookiewyq.rs_create_compat.support;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.network.RefreshCamouflagePacket;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentHolder;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.attachment.IAttachmentSerializer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 「伪装外壳」的<b>载体</b>：一份挂在<b>被裹方块自己的方块实体</b>上的 NeoForge 数据附件
 * （{@link AttachmentType}），随方块实体的 NBT 一起序列化 / 反序列化。
 *
 * <h2>为什么必须换掉旧的「服务端 SavedData + 世界坐标键」</h2>
 * <p>用户反馈的原话：「框架<b>只会保留在原地</b>……用权杖把那个物理化的线缆<b>拖走</b>之后，
 * 框架贴图<b>仍然留在原地</b>，并且也不知道怎么搞掉；如果在那里再重新放上一根线缆，它就消失了。
 * 并没有正确地跟随这个线缆」。</p>
 * <p>旧实现的病灶是<b>载体本身就是世界坐标</b>：伪装记录存在服务端 {@code SavedData} 里、键是
 * {@code BlockPos}，渲染器再拿这个坐标回头查世界。于是只要那一格里东西被搬走
 * （Create 装配 / 物理化是用 {@code Level#setBlock(..., AIR, ...)} 把方块抹掉的，既不触发
 * {@code BreakEvent} 也不会有方块更新），记录就永远留在原坐标 → <b>残影</b>；
 * 而被搬走的那一份因为「新坐标上没有记录」→ <b>不跟随</b>。</p>
 * <p>Create 的伪装板（本轮的实现模板，见 {@code CopycatBlockEntity}）不是这么做的：
 * 材质存在<b>方块实体自己的字段里</b>（{@code private BlockState material}），
 * 由 {@code BlockEntity#saveAdditional} 写进 NBT —— 方块实体的 NBT 走到哪，材质就跟到哪。
 * 本类就是同一套机制在本模组的落地：<b>材质挂在被裹方块的方块实体上，随它的 NBT 走</b>。</p>
 *
 * <h2>为什么用数据附件而不是 Mixin 进每一个被裹的方块实体类</h2>
 * <p>被裹对象跨越两个模组（RS 的线缆 / 输入总线 / 输出总线，Create 的流体管道族 / 传动杆），
 * 逐个 Mixin 进它们的方块实体等于写 N 份重复实现，任何一个新增变体都会漏。</p>
 * <p>NeoForge 的 {@code BlockEntity} 本身就实现了 {@code AttachmentHolder}，且
 * {@code BlockEntity#saveAdditional} / {@code loadAdditional} <b>无条件</b>读写
 * {@code "neoforge:attachments"} 这一节（见 NeoForge 源码
 * {@code BlockEntity.java} 的 {@code serializeAttachments / deserializeAttachments}）——
 * 也就是说附件<b>天然参与方块实体的 NBT 往返</b>，不需要我们改写任何 RS / Create 的类。
 * 这一点对本轮最关键的目标直接成立：</p>
 * <ul>
 *     <li><b>存档往返</b>：方块实体保存时带上附件 → 读档后材质还在；</li>
 *     <li><b>被搬运</b>：Create 的装置装配用
 *     {@code Contraption#getBlockEntityNBT → blockEntity.saveWithFullMetadata(...)}
 *     抄走方块实体的完整 NBT（含附件），装置实体渲染时又通过
 *     {@code VirtualRenderWorld#getBlockEntity / #getModelData} 把它交还给模型 ——
 *     于是外壳<b>跟着方块一起被画出来</b>；</li>
 *     <li><b>不留残影</b>：方块被抹掉 → 方块实体被移除 → 附件随之消失，
 *     渲染再也不会有任何「按坐标反查出来的壳」。</li>
 * </ul>
 *
 * <h2>同步</h2>
 * <p>{@link AttachmentType.Builder#sync(StreamCodec)} 让 NeoForge 自己在
 * 「区块被玩家观察 / {@code BlockEntity#setData}」时把附件下发给观察者
 * （{@code AttachmentSync}），因此客户端天然拿得到材质 —— 不再需要本模组自己维护
 * 一份「坐标 → 材质」的 S2C 镜像（那正是旧架构的另一半病灶）。</p>
 *
 * <p><b>2026-09-30 补：那条链路有两个洞，各补一刀</b>（物理化后外壳消失的根因就在这里）</p>
 * <ul>
 *     <li><b>「区块是谁发的」不由 NeoForge 决定</b>：附件同步只挂在
 *     {@code ChunkWatchEvent.Sent}（原版 {@code ChunkMap} 发包）上，第三方自建发送链路
 *     （Sable 的子关卡）一次都不会触发 → 用 {@link #writeIntoUpdateTag}
 *     把材质并进区块数据包自带的方块实体 NBT（见 {@code mixin/blockentity/BlockEntityUpdateTagMixin}）；</li>
 *     <li><b>「方块实体被重新落地」不占任何一条链路</b>：子关卡拆解回世界时客户端那格是空方块实体
 *     → 方块更新广播之后补一次（见 {@link RsccCamouflage#repushIfCamouflaged} 与
 *     {@code mixin/level/ServerLevelBlockUpdateMixin}）。</li>
 * </ul>
 *
 * <p><b>本类只负责「怎么存 / 怎么传」</b>，语义（什么时候算裹住、怎么归还物品）全部在
 * {@link RsccCamouflage}。</p>
 */
public final class RsccCamouflageAttachment {
    /** 附件注册表（{@code neoforge:attachment_types}）。 */
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
        DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, RS_Create_Compat.MODID);

    /** 附件里用到的三个 NBT 键（只写非默认值：空壳不占字节）。 */
    private static final String KEY_MATERIAL = "material";
    private static final String KEY_ITEM = "item";
    private static final String KEY_FRAME = "frame";

    /** 注册到模组事件总线（由 {@code RS_Create_Compat} 的构造函数调用一次）。 */
    public static void register(final IEventBus modEventBus) {
        ATTACHMENT_TYPES.register(modEventBus);
    }

    /**
     * 区块刚刚发给某个玩家时，把这一块里<b>所有被裹格</b>的坐标一次性「请重烘」通知给他。
     *
     * <h2>为什么首帧也要补这一下</h2>
     * <p>附件本身由 NeoForge 自动随区块下发（{@code AttachmentSync#onChunkSent}），但那一步
     * <b>只把数据塞回客户端方块实体、不会重烘网格</b>；而外壳的几何是<b>烘焙产物</b>。
     * 客户端加载新区块时的烘焙时机与附件包到达时机是两个独立的时间点，一旦烘焙先跑，
     * 这一块就会一直看不到外壳（直到因为别的原因重烘）。这里在「区块发给玩家」这个确定的时间点上
     * 补一条重烘信号，把这条竞态彻底消掉（也给「重登后外壳还在」加了硬保证）。</p>
     *
     * <p>代价只有一次方块实体表的遍历：区块里没有被裹格时一个包都不发。</p>
     */
    public static void refreshChunkFor(final ServerPlayer player, final LevelChunk chunk) {
        List<Long> packed = null;
        for (final BlockEntity be : chunk.getBlockEntities().values()) {
            if (be.getExistingDataOrNull(CAMOUFLAGE.get()) == null) {
                continue; // 绝大多数区块在这里就跳过（连一次坐标装箱都不做）
            }
            if (packed == null) {
                packed = new ArrayList<>(4);
            }
            packed.add(be.getBlockPos().asLong());
        }
        if (packed != null) {
            PacketDistributor.sendToPlayer(player, new RefreshCamouflagePacket(List.copyOf(packed)));
        }
    }

    /**
     * 把这一格的伪装<b>并进「要发给客户端的方块实体 NBT」</b>
     * （唯一调用方：{@code mixin/blockentity/BlockEntityUpdateTagMixin}，挂在
     * {@code BlockEntity#getUpdateTag} 的返回处）。
     *
     * <h2>为什么必须补这一步（2026-09-30 实测：物理化后伪装直接消失）</h2>
     * <p>材质平时由 {@code AttachmentType.Builder#sync(StreamCodec)} 下发，而 NeoForge 那条链路
     * <b>只有一个触发点</b>：{@code AttachmentSync#onChunkSent}（{@code ChunkWatchEvent.Sent}）——
     * 它只在<b>原版 {@code ChunkMap} 把区块发给某个玩家</b>的那一刻触发。
     * 只要发送方不是原版 {@code ChunkMap}，这条链路<b>一次都不会跑</b>：
     * Sable（物理化模组）的子关卡区块就是这样发的 ——
     * {@code dev.ryanhcode.sable.sublevel.system.SubLevelTrackingSystem#sendFullSync} 自己拼
     * {@code player.connection.send(new ClientboundBundlePacket(...))}，包裹体里那一包就是
     * {@code SubLevelPlayerChunkSender#sendChunk} 新建的原版
     * {@code ClientboundLevelChunkWithLightPacket}；整个 Sable / Simulated / Aeronautics 里
     * <b>没有任何一处引用 {@code ChunkWatchEvent}</b>（实测：三个 jar 全量二进制扫描，0 命中）。</p>
     * <p>后果：客户端按区块数据包把方块实体建了出来，但<b>附件没跟着来</b> ——
     * 外壳渲染取数（{@code RsccCamouflage.get}）拿到 {@code null}，于是<b>一物理化外壳就消失</b>。</p>
     *
     * <h2>为什么改 {@code getUpdateTag} 就能修好（与「谁发区块」无关）</h2>
     * <p>区块数据包给每个方块实体带的 NBT 来自 {@code BlockEntity#getUpdateTag(Provider)}
     * （{@code ClientboundLevelChunkPacketData$BlockEntityInfo#create} 里那一句），
     * 而客户端会把这半段 NBT 原样喂给 {@code BlockEntity#loadWithComponents → loadAdditional}；
     * NeoForge 在 {@code BlockEntity#loadAdditional} 里<b>无条件</b>读
     * {@code neoforge:attachments}（{@code AttachmentHolder#deserializeAttachments}）。
     * 也就是说：只要把这半段 NBT 里补上我们这一项，材质就<b>搭区块数据包的顺风车</b>到达客户端，
     * 无论区块是原版发的还是别的模组发的。</p>
     * <p>RS 系的线缆 / 输入总线 / 输出总线（{@code AbstractNetworkNodeContainerBlockEntity}）
     * 并没有覆写 {@code getUpdateTag}，走的是 {@code BlockEntity} 的默认实现 ——
     * 1.21.1 的默认实现返回<b>空 CompoundTag</b>（实测 javap：直接 {@code new CompoundTag()}），
     * 所以物理化后它们的方块实体连一个字节的数据都没有。Create 系的管道 / 传动杆走
     * {@code SyncedBlockEntity#getUpdateTag → writeClient → saveAdditional}，而 NeoForge 的
     * {@code saveAdditional} 本来就会写 {@code neoforge:attachments}，所以它们<b>本来就没这个问题</b>
     * —— 这也正是「只有 RS 线缆 / 总线消失」的原因。</p>
     *
     * <h2>为什么直接写 {@code neoforge:attachments} 这一节、而不是自造一个键</h2>
     * <p>客户端那一侧的读取代码在 NeoForge 里（{@code BlockEntity#loadAdditional}），
     * 用同一把钥匙就能直接复用，不必再挂一个客户端 Mixin 去解析自造键；
     * 而且这里用的就是本类<b>存档用的同一个序列化器</b>，格式与存档逐字一致
     * （不会出现「存档能存下、网络读不回」这种半通不通的状态）。</p>
     *
     * @param holder 方块实体本身（{@code BlockEntity} 就是 {@link IAttachmentHolder}）
     * @param tag    {@code getUpdateTag} 的返回值（就地补写；没有材质时<b>一个字节都不改</b>）
     */
    public static void writeIntoUpdateTag(final IAttachmentHolder holder, final CompoundTag tag,
                                          final HolderLookup.Provider provider) {
        final RsccCamouflage.Camo camo = holder.getExistingDataOrNull(CAMOUFLAGE.get());
        if (camo == null) {
            return; // 绝大多数方块实体（没被裹住的那些）在这句就返回：连注册表查名都不做
        }
        final ResourceLocation key = NeoForgeRegistries.ATTACHMENT_TYPES.getKey(CAMOUFLAGE.get());
        if (key == null) {
            return; // 理论上不会发生（没注册进注册表的附件类型不可能持有值）
        }
        // 与其它附件共存：这里是「并进去」而不是「整节覆盖」
        final CompoundTag attachments = tag.getCompound(AttachmentHolder.ATTACHMENTS_NBT_KEY);
        attachments.put(key.toString(), SERIALIZER.write(camo, provider));
        tag.put(AttachmentHolder.ATTACHMENTS_NBT_KEY, attachments);
    }

    /**
     * 存档序列化：与旧 {@code SavedData} 的条目<b>逐字同构</b>（材质 NBT / 物品 NBT / 一个布尔位），
     * 因此「读档后仍然裹着」「创造模式扣没扣过」这两条既有语义一字未改。
     *
     * <p>任何一段读不出来都退化成「默认值」而不是抛异常：材质方块被卸载的模组移除时
     * （存档里留下一个本端不认识的方块名），整份方块实体数据仍然装得进去。</p>
     */
    private static final IAttachmentSerializer<CompoundTag, RsccCamouflage.Camo> SERIALIZER =
        new IAttachmentSerializer<>() {
            @Override
            public RsccCamouflage.Camo read(final IAttachmentHolder holder, final CompoundTag tag,
                                            final HolderLookup.Provider provider) {
                BlockState material = null;
                if (tag.contains(KEY_MATERIAL)) {
                    try {
                        material = NbtUtils.readBlockState(provider.lookupOrThrow(Registries.BLOCK),
                            tag.getCompound(KEY_MATERIAL));
                    } catch (final RuntimeException ignored) {
                        material = null; // 材质方块已被移除：按「空壳」处理，绝不因此读不出整份存档
                    }
                }
                final ItemStack consumed = tag.contains(KEY_ITEM)
                    ? ItemStack.parse(provider, tag.get(KEY_ITEM)).orElse(ItemStack.EMPTY)
                    : ItemStack.EMPTY;
                return new RsccCamouflage.Camo(material, consumed, tag.getBoolean(KEY_FRAME));
            }

            @Override
            public CompoundTag write(final RsccCamouflage.Camo camo, final HolderLookup.Provider provider) {
                final CompoundTag tag = new CompoundTag();
                if (camo.material() != null) {
                    tag.put(KEY_MATERIAL, NbtUtils.writeBlockState(camo.material()));
                }
                if (!camo.consumed().isEmpty()) {
                    tag.put(KEY_ITEM, camo.consumed().save(provider));
                }
                // 只写 true（缺键 = false）：创造模式裹上的那些记录不必占字节
                if (camo.frameConsumed()) {
                    tag.putBoolean(KEY_FRAME, true);
                }
                return tag;
            }
        };

    /**
     * 网络序列化（与存档同一套字段）：材质同样走 {@code BlockState} 的官方 NBT 形式，
     * <b>不传方块状态的注册表数字</b> —— 那个数字只是本端注册顺序的产物，两端模组列表不一致时会错位。
     * 解析需要方块注册表，而 {@link RegistryFriendlyByteBuf#registryAccess()} 正好提供它。</p>
     */
    private static final StreamCodec<RegistryFriendlyByteBuf, RsccCamouflage.Camo> STREAM_CODEC =
        StreamCodec.of(
            (buf, camo) -> {
                buf.writeBoolean(camo.material() != null);
                if (camo.material() != null) {
                    buf.writeNbt(NbtUtils.writeBlockState(camo.material()));
                }
                buf.writeBoolean(!camo.consumed().isEmpty());
                if (!camo.consumed().isEmpty()) {
                    buf.writeNbt(camo.consumed().save(buf.registryAccess()));
                }
                buf.writeBoolean(camo.frameConsumed());
            },
            buf -> {
                BlockState material = null;
                if (buf.readBoolean()) {
                    material = NbtUtils.readBlockState(
                        buf.registryAccess().lookupOrThrow(Registries.BLOCK), readRequiredNbt(buf));
                }
                final ItemStack consumed = buf.readBoolean()
                    ? ItemStack.parseOptional(buf.registryAccess(), readRequiredNbt(buf))
                    : ItemStack.EMPTY;
                return new RsccCamouflage.Camo(material, consumed, buf.readBoolean());
            });

    /** 读一个「写的时候一定非空」的 NBT：协议由本类自己写，空值只可能来自损坏的封包，按空处理即可。 */
    private static CompoundTag readRequiredNbt(final RegistryFriendlyByteBuf buf) {
        final CompoundTag tag = buf.readNbt();
        return tag == null ? new CompoundTag() : tag;
    }

    /**
     * 伪装附件本体：值 = {@link RsccCamouflage.Camo}（外壳材质 + 被消耗的方块 + 「框架是否真的扣过」）。
     *
     * <p>三项能力各司其职：{@code serialize} = 参与方块实体 NBT 往返（存档 / 装置搬运）；
     * {@code sync} = 由 NeoForge 下发给观察该区块的客户端（渲染取数）。</p>
     *
     * <p>（声明位置刻意排在上面那两个序列化器<b>之后</b>：Java 不允许字段初始化表达式前向引用
     * 后面声明的静态字段。）</p>
     */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<RsccCamouflage.Camo>> CAMOUFLAGE =
        ATTACHMENT_TYPES.register("camouflage", () -> AttachmentType
            .builder(() -> new RsccCamouflage.Camo(null, ItemStack.EMPTY, false))
            .serialize(SERIALIZER)
            .sync(STREAM_CODEC)
            .build());

    private RsccCamouflageAttachment() {
    }
}
