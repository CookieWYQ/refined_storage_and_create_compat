package cretae.cookiewyq.rs_create_compat.block.entity;

import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.common.api.support.resource.PlatformResourceKey;
import com.refinedmods.refinedstorage.common.support.network.AbstractBaseNetworkNodeContainerBlockEntity;
import com.refinedmods.refinedstorage.common.support.resource.FluidResource;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import com.refinedmods.refinedstorage.neoforge.api.RefinedStorageNeoForgeApi;
import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.node.GraphNetworkComponent;
import com.refinedmods.refinedstorage.api.network.node.container.NetworkNodeContainer;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.NetworkResourceReceiverBlock;
import cretae.cookiewyq.rs_create_compat.network.NetworkResourceReceiverNetworkNode;
import cretae.cookiewyq.rs_create_compat.network.ReceiverCacheEntry;
import cretae.cookiewyq.rs_create_compat.support.RsccAssemblyDebug;
import cretae.cookiewyq.rs_create_compat.support.RsccChamberImportStrategy;
import cretae.cookiewyq.rs_create_compat.support.RsccGenericResourceTarget;
import cretae.cookiewyq.rs_create_compat.support.RsccRateLimiter;
import cretae.cookiewyq.rs_create_compat.support.RsccResourceKeys;
import cretae.cookiewyq.rs_create_compat.support.RsccSpeedStackUpgradeContainer;
import cretae.cookiewyq.rs_create_compat.support.RsccUnboundedFluidStorage;
import cretae.cookiewyq.rs_create_compat.support.RsccUnboundedItemStorage;
import cretae.cookiewyq.rs_create_compat.support.ViewOnlyHandlers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 「网络资源接收器」方块实体：一个<b>带名字的落地点 + 物品/流体缓存</b>，外加 6 个升级槽。
 *
 * <h2>它负责什么</h2>
 * <ol>
 *     <li><b>名字</b>：{@code receiverName} 存在自己的 NBT 里；<b>空名 = 完全不可用</b>
 *     （{@link #isProperlyConfigured()} 为 false）；同网络唯一性判据
 *     {@link #isNameTakenInNetwork(String)} <b>逐字照搬执行舱</b>（见各方法 javadoc）；</li>
 *     <li><b>缓存</b>：{@link RsccUnboundedItemStorage} + {@link RsccUnboundedFluidStorage}
 *     （设计文档 §3.3 明确推荐这两个：种类无上限、按 RS 的 {@code ResourceKey} 语义对齐；
 *     <b>不要</b>用绑在执行舱语义上的 {@code RsccChamberItemStorage}）；</li>
 *     <li><b>任意资源类型（直通，不缓存）</b>：见下面《任意资源类型》一节；</li>
 *     <li><b>升级槽</b>：{@link RsccSpeedStackUpgradeContainer}（6 槽，只收速度 + 叠加，
 *     第 ② 层校验在容器里，见该类 javadoc 的三层说明）：<b>速度升级 = 每 tick 几批</b>、
 *     <b>叠加升级 = 每批多少</b>，两个旋钮都只在容器里换算；</li>
 *     <li><b>接入网络</b>：{@link #registerCapabilities} 注册网络节点容器能力 ——
 *     「必须插线缆才在网络上」这条语义因此由 RS 网络图全权负责。</li>
 * </ol>
 *
 * <h2>它<b>不</b>负责什么</h2>
 * <ul>
 *     <li><b>不作为网络存储源</b>（不实现 {@code StorageProvider}）：设计文档 §7.6 已定 ——
 *     否则自动合成 / 其它机器可以绕过请求器直接往里写，请求器就失去意义。
 *     缓存是<b>私有缓冲</b>：写入只有「请求器投递」与「相邻容器推入」（第 96 轮新增，
 *     见 {@link #insertItemFromExternal}，两者都走 {@link #applyResource} 同一份容量账），
 *     抽出只有「玩家在界面里取」、「本方块朝 {@code FACING} 输出」与「相邻容器抽取」
 *     （第 101 轮新增，见 {@link #extractItemFromExternal}：Create 溜槽对上方容器只抽不推，
 *     不放开这一条就等于「跟溜槽完全不交互」）；</li>
 *     <li><b>不实现红石模式</b>：{@link #hasRedstoneMode()} 返回 false（与执行舱 / 缓存仓同口径），
 *     否则界面右侧会多出一个对本方块毫无作用的红石按钮。</li>
 * </ul>
 *
 * <h2>节流入口 + 输出到朝向容器</h2>
 * <p>「每 tick 做几批」由 {@link RsccSpeedStackUpgradeContainer#throttleBatchesPerTick()} 给出，
 * 「每批多少」由 {@link RsccSpeedStackUpgradeContainer#throttleBatch()} 给出 —— 两者都在升级容器里，
 * 都走 {@link RsccRateLimiter}（本模组唯一的「升级 → 批数 / 批量」换算工具），
 * 本类与界面<b>只读、不自己算</b>。{@link RsccSpeedStackUpgradeContainer#throttleIntervalTicks()}
 * 恒为 1（闸门每 tick 都放行）：间隔的物理下限就是 1 tick，要更快只能「每 tick 多做几批」，
 * 这也正是速度升级在本方块上的语义。RS 原版的 {@code UpgradeContainer} ticker 在 6 个速度升级时会算出
 * {@code 9 - 2*6 = -3}（负节拍），因此<b>一次都没有用过</b>（见 {@link RsccRateLimiter} 类文档）。</p>
 * <p>{@link #doWork()} 在节流放行后把缓存按「每 tick 批数 × 单批量」输出到 {@code FACING} 朝向的相邻容器
 * （物品 → {@code IItemHandler}，流体 → {@code IFluidHandler}）。<b>守恒铁律</b>：先向目标询问
 * 「能收多少」/ 真正写入，再按「目标确实收下的量」从缓存扣减；目标满 / 无容器 / 未加载时
 * <b>一个字节都不扣</b>。空名（{@link #isProperlyConfigured()} 为 false）⇒ 完全不动。</p>
 *
 * <h2>任意资源类型（气体等）：直通，不缓存</h2>
 * <p>请求器不再只认物品 / 流体：{@link #canHandle(ResourceKey)} 与
 * {@link #applyResource(ResourceKey, long, boolean)} 接受<b>任意</b> {@link PlatformResourceKey}。</p>
 * <ul>
 *     <li><b>物品 / 流体</b>：与改前<b>逐字相同</b> —— 进上面两个缓存，由 {@link #doWork()} 输出；</li>
 *     <li><b>其它类型</b>（装了官方 Mekanism 联动后的气体等）：<b>不落缓存，直接转发</b>到
 *     {@code FACING} 朝向的相邻方块。目标容器通过 RS 自己的
 *     {@code RefinedStorageApi#getExternalStorageProviderFactories()} 链查找
 *     （见 {@link RsccGenericResourceTarget}，与 RS 外部存储方块的判据同源）——
 *     装了联动就能自动找到气体容器，我们<b>不认识</b>气体、也<b>不</b>依赖任何联动类。</li>
 *     <li><b>为什么不给第三方类型加缓存</b>：拆方块时的内容物结算入口
 *     （{@code BlockContentReleaser}）只有「物品 / 流体」两类分类，第三方类型的缓存没有归宿，
 *     会违反本工程的「绝不销毁」铁律；直通则根本不产生「躺在方块里的第三方资源」，
 *     守恒边界与改前完全一致。代价是第三方类型<b>不缓冲</b>：朝向没有能收它的容器时，
 *     请求器会明确提示「没有通道」，而不是先收下再慢慢等容器出现（与物品 / 流体「缓存可以攒着」不同）。</li>
 * </ul>
 * <p><b>未识别 / 不可持久化的键</b>（不实现 {@link PlatformResourceKey} 的 {@link ResourceKey}）：
 * RS 的落盘 / 同步 codec 本身不接受它，因此 {@link #canHandle(ResourceKey)} 判 false，
 * 请求器会给出「不支持」提示 —— 这是<b>判据</b>而不是硬编码的类型白名单：
 * 任何联动注册的类型只要实现了 {@code PlatformResourceKey} 就自动进入「支持」一侧。</p>
 *
 * <h2>对外可见性（用户硬规则：只暴露接口，显示交给 Jade）</h2>
 * <p>缓存必须能被「指着看」：{@link #registerCapabilities} 暴露
 * {@code Capabilities.ItemHandler.BLOCK} / {@code Capability.FluidHandler.BLOCK}
 * （见 {@link ViewOnlyHandlers}）。<b>Jade 的内容行完全由这两个能力驱动</b>：
 * Jade 的 Universal 插件把 {@code ItemStorageProvider$ForBlock} /
 * {@code FluidStorageProvider$ForBlock} 注册给 {@code Block.class}（= 所有方块），
 * 并在<b>服务端</b>用 {@code Level#getCapability(..., null)}（side = null）取容器内容
 * （字节码证据：{@code libs/jade-15.10.5+neoforge.jar} 的 {@code UniversalPlugin} 与
 * {@code CommonProxy}）。本模组因此<b>不再自己写「按住 Shift 看明细」</b>
 * （用户原话：「这玩意 Jade 已经帮你实现了」），Jade provider 只补 Jade 结构上拿不到的一行名字。
 * <b>本能力与查询方向无关</b>（用户原话：「作为一个 inventory 提供者时不应该有方向限制」）：
 * 管道 / 漏斗 / 其它模组的相邻扫描 / Jade 的 side=null 查询拿到的都是同一份视图。
 * {@code FACING} 只用于<b>成链</b> —— 「缓存 → 朝向容器」的输出，以及第三方资源直通朝向容器
 * —— 与「谁能看见这份 inventory」无关。</p>
 *
 * <h2>第 96 / 101 轮：对外暴露的是<b>两向包装</b>（可推入 + 可抽出）</h2>
 * <p>用户第 96 轮实测原话：「<i>网络资源接收器还是不能跟溜槽之类的进行交互</i>」。第 96 轮把暴露出去的
 * {@code insertItem} / {@code fill} 从「原样退回」改成真实写入（见
 * {@link #insertItemFromExternal} / {@link #fillFluidFromExternal}）。</p>
 * <p><b>第 101 轮用户仍实测「不交互」</b>（原话：「<i>还是无法正常跟溜槽交互；与溜槽交互的时候它是
 * 无视方向的 —— 你就是写错了</i>」）。根因是第 96 轮只答对了<b>一半</b>：
 * Create 6.0.10 的 {@code ChuteBlockEntity} 对<b>上方</b>容器<b>根本不「推」</b> ——
 * 空的普通溜槽每 tick 走 {@code handleInputFromAbove()} → {@code grabCapability(Direction.UP)}
 * → {@code ItemHelper.extract(inv, canAccept, UPTO, 16, false)}，也就是<b>从上方容器里抽</b>
 * （{@code build/createsrc/com/simibubi/create/content/logistics/chute/ChuteBlockEntity.java:337-364}
 * 与 {@code foundation/item/ItemHelper.java:183-229}）。它唯一「推」进相邻容器的路径是
 * {@code handleDownwardOutput}（溜槽自己手里已经有货时往下倒）。因此「把溜槽放在接收器下面，
 * 让它把东西搬走」这个最自然的摆法只会用到 {@code extractItem}，而第 96 轮把它写死成恒空 ⇒
 * <b>先在 SIMULATE 阶段就被判定「抽不出东西」，之后连 EXECUTE 都不会发生</b>，
 * 玩家看到的正是「无论溜槽摆在哪个方向，都一点反应没有」（用户说的「无视方向」）。</p>
 * <p>修正：{@link ViewOnlyHandlers#writableItem} /
 * {@link ViewOnlyHandlers#writableFluid} 新增显式的<b>外抽落点</b>
 * （{@link ViewOnlyHandlers.WritableItem#withExtraction} /
 * {@link ViewOnlyHandlers.WritableFluid#withDrain}），本仓在 {@link #registerCapabilities} 里接上
 * {@link #extractItemFromExternal} / {@link #drainFluidFromExternal}：
 * <b>读照旧委托真实缓存</b>（Jade / 相邻扫描照常看得见）、<b>推入落到
 * {@link #applyResource}</b>（请求器投递的同一个入口：容量上限与守恒只有一套账）、
 * <b>抽出落到缓存自身的扣减</b>（缓存里没有的抽不出来，抽走即真实减少 + {@code setChanged()} 落盘）。
 * 两向都遵守 {@code simulate} 契约：SIMULATE 只回答、不改任何状态；只有 EXECUTE 才写缓存。</p>
 * <p><b>与查询方向无关</b>（用户原话：「<i>网络资源接收器在作为一个 inventory 提供者时不应该有方向限制，
 * 并且方向只针对于成链机制</i>」）：{@code direction == null} 与任意 {@code Direction} 都返回
 * <b>同一个</b>视图。</p>
 */
public class NetworkResourceReceiverBlockEntity
    extends AbstractBaseNetworkNodeContainerBlockEntity<NetworkResourceReceiverNetworkNode> {

    /** 名字在方块实体 NBT 里的键。 */
    private static final String TAG_NAME = "ReceiverName";
    /** 升级槽容器在 NBT 里的键（与各机器统一的写法）。 */
    private static final String TAG_UPGRADES = "Upgrades";
    /** 物品缓存在 NBT 里的键。 */
    private static final String TAG_ITEM_CACHE = "ItemCache";
    /** 流体缓存在 NBT 里的键。 */
    private static final String TAG_FLUID_CACHE = "FluidCache";

    /** 输出搬运的日志器（失败绝不静默：受 {@code devLogs} 总开关控制）。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(NetworkResourceReceiverBlockEntity.class);
    /** 输出搬运日志的统一前缀（便于 grep）。 */
    private static final String LOG_PREFIX = "[rscc-receiver-output]";

    /**
     * 物品缓存的基础容量（件）。
     * <p><b>为什么是 1728</b>：= 27 格 × 64，正好是「<b>一个单箱</b>」的物品容量。
     * 选一个玩家一眼能理解的口径（而不是拍一个奇怪的大数），后续叠加升级的放大倍率也就有了参照
     * （与 {@code SchematicLoaderBlockEntity#storageSlotCapacity} 的「{@code 64 × (1 + 叠加数)}」同源）。</p>
     */
    public static final int BASE_ITEM_CAPACITY = 27 * 64;
    /** 流体缓存的基础容量（mB）：2000 mB = 2 桶，同样是一个「一眼能理解」的口径。 */
    public static final int BASE_FLUID_CAPACITY = 2000;

    /** 接收器名字（空串 = 未命名 = 不可用）。 */
    private String receiverName = "";

    /**
     * 节流闸门的倒计时（tick）：&gt; 0 时本 tick 不动作，减到 0 才放行并把
     * {@link RsccSpeedStackUpgradeContainer#throttleIntervalTicks()} 重新装进来。
     * <p>该间隔<b>恒为 1</b>（闸门每 tick 都放行）：「更快」由
     * {@link RsccSpeedStackUpgradeContainer#throttleBatchesPerTick()} 的每 tick 批数表达，
     * 而不是缩短间隔（物理下限就是 1 tick）。计数器保留「先自减再判」的写法是为了让
     * 以后真有「基础节拍 &gt; 1」的需求时不需要改任何调用点。
     * 不落盘：节拍是「此刻的机器状态」，重进存档从头起算即可（落盘一个可能说谎的计数器
     * 反而会引入「读档后卡住 N tick」这类看不见的偏差）。</p>
     */
    private int throttleCooldown;

    /** 6 个升级槽（只收速度 + 叠加；第 ② 层校验在容器内部）。 */
    private final RsccSpeedStackUpgradeContainer upgradeContainer =
        new RsccSpeedStackUpgradeContainer(new RsccSpeedStackUpgradeContainer.Host() {
            @Override
            public void dropStack(final ItemStack stack) {
                RsccSpeedStackUpgradeContainer.dropIntoWorld(getLevel(), worldPosition, stack);
            }

            @Override
            public void onUpgradesChanged() {
                setChanged();
            }
        },
            // 「没有服务端世界就不动任何东西」的前置由 lambda <b>现问</b>给出：方块实体构造期必然还没有
            // level（真正的服务端世界由 setLevel 稍后注入），定死布尔会让第 ② 层永远不生效。
            // 判据与 RangeChargerBlockEntity#validateUpgradeSlots 逐字同源。
            () -> getLevel() != null && !getLevel().isClientSide());

    /** 物品缓存（种类无上限，总容量由 {@link #getItemCapacity()} 给出）。 */
    private final RsccUnboundedItemStorage itemCache = new RsccUnboundedItemStorage(BASE_ITEM_CAPACITY);
    /** 流体缓存（种类无上限）。 */
    private final RsccUnboundedFluidStorage fluidCache = new RsccUnboundedFluidStorage(BASE_FLUID_CAPACITY);

    /**
     * 通用写入通道的缓存（第三方资源直通用）：<b>必须缓存</b>，因为每个 provider 内部持有
     * {@code BlockCapabilityCache}，每次重建都会往方块注册监听。失效判据 = 目标格 / 目标方块状态 /
     * 能力侧任一变化（见 {@link #genericTarget()}）。
     */
    private RsccGenericResourceTarget.Target genericTarget;
    private BlockPos genericTargetPos;
    private BlockState genericTargetState;
    private Direction genericTargetSide;

    public NetworkResourceReceiverBlockEntity(final BlockPos pos, final BlockState state) {
        super(RS_Create_Compat.NETWORK_RESOURCE_RECEIVER_BLOCK_ENTITY.get(), pos, state,
            new NetworkResourceReceiverNetworkNode());
        this.mainNetworkNode.setBlockEntity(this);
    }

    // ==================== 访问器 ====================

    /** 本仓的网络节点（供能力注册与调试使用）。 */
    public NetworkResourceReceiverNetworkNode getNode() {
        return mainNetworkNode;
    }

    /** 升级槽容器（界面与第 ② 层校验共用同一份；<b>不是</b>拷贝）。 */
    public RsccSpeedStackUpgradeContainer getUpgradeContainer() {
        return upgradeContainer;
    }

    /** 物品缓存（拆方块时由 {@code BlockContentReleaser} 结算，绝不销毁）。 */
    public RsccUnboundedItemStorage getItemCache() {
        return itemCache;
    }

    /** 流体缓存（同上）。 */
    public RsccUnboundedFluidStorage getFluidCache() {
        return fluidCache;
    }

    // ==================== 缓存快照（只服务界面 / Jade 的显示，绝不改任何状态） ====================

    /** 物品缓存里的<b>种类数</b>（非空槽位数；空槽 = 0）。 */
    public int getItemTypeCount() {
        int types = 0;
        for (int slot = 0; slot < itemCache.getSlots(); slot++) {
            if (!itemCache.getStackInSlot(slot).isEmpty()) {
                types++;
            }
        }
        return types;
    }

    /** 物品缓存里的<b>总件数</b>。 */
    public int getItemTotalCount() {
        return itemCache.getTotalCount();
    }

    /** 流体缓存里的<b>总 mB 数</b>。 */
    public int getFluidTotalAmount() {
        return fluidCache.getTotalAmount();
    }

    /**
     * 流体缓存的<b>只读快照</b>（每种流体一条，只含非空罐；副本）。
     * <p>顺序 = 存储内部顺序（同流体同组件已在存储里合并）。界面用它渲染流体条目，
     * 并把<b>下标</b>回传给服务端做「取哪一条」的选择 —— 服务端在点击时用
     * 「上次同步给这个界面的那一份快照」重新核对身份，因此下标永远不会取错流体
     * （见 {@code NetworkResourceReceiverMenu#lastSentFluids}）。</p>
     */
    public java.util.List<FluidStack> getFluidSnapshot() {
        final java.util.List<FluidStack> out = new java.util.ArrayList<>();
        for (final FluidStack stack : fluidCache.getTanksSnapshot()) {
            if (!stack.isEmpty()) {
                out.add(stack.copy());
            }
        }
        return out;
    }

    /** 流体缓存里的<b>种类数</b>（非空罐数；同一「流体 + 数据组件」在存储里已合并）。 */
    public int getFluidTypeCount() {
        int types = 0;
        for (final FluidStack stack : fluidCache.getTanksSnapshot()) {
            if (!stack.isEmpty()) {
                types++;
            }
        }
        return types;
    }

    /**
     * 资源列表的<b>权威全量快照</b>（物品 + 流体，按「资源身份」聚合，供界面分页）。
     *
     * <h2>为什么要聚合（而不是直接按内部槽位逐格发）</h2>
     * <p>缓存是 {@link RsccUnboundedItemStorage} / {@link RsccUnboundedFluidStorage}：<b>种类无上限</b>。
     * 同一种「物品 + 数据组件」可能横跨<b>多个内部槽位</b>（每格最多 64、总容量 1728），
     * 而界面要显示的是「<b>这一种</b>资源总共多少」。因此这里用 RS 自己的资源键
     * （{@link ItemResource#ofItemStack} / {@link FluidResource}）当聚合身份：
     * <ul>
     *     <li>同一身份 ⇒ 数量相加；</li>
     *     <li>顺序 = 首次出现顺序（{@link java.util.LinkedHashMap}）⇒ 同一次同步内稳定，
     *     界面不会因为「每次重新排序」而跳动；</li>
     *     <li>物品在前、流体在后（与旧界面的阅读顺序一致）。</li>
     * </ul>
     *
     * <p><b>为什么用资源键而不是 {@code ItemStack.isSameItemSameComponents} 两两比较</b>：
     * 种类多时两两比较是 O(n²)（1728 种 ⇒ 约 150 万次比较）；资源键是现成的
     * {@code equals/hashCode}（与 RS 网络里「什么算同一种资源」逐字同源），O(n)。</p>
     *
     * <p><b>成本</b>：只在菜单需要重建快照时调用（缓存指纹变化 / 翻页 / 打开界面），
     * 每次遍历全部槽位一次；<b>不</b>在渲染循环、也不在 {@link #doWork()} 里调用。</p>
     */
    public java.util.List<ReceiverCacheEntry> cacheEntries() {
        final java.util.List<ReceiverCacheEntry> out = new java.util.ArrayList<>();
        // 物品：资源键 → 累计件数；同时记住「第一次见到的那个栈」当图标
        final java.util.LinkedHashMap<ItemResource, ItemStack> itemIcons = new java.util.LinkedHashMap<>();
        final java.util.LinkedHashMap<ItemResource, Long> itemAmounts = new java.util.LinkedHashMap<>();
        for (int slot = 0; slot < itemCache.getSlots(); slot++) {
            final ItemStack stored = itemCache.getStackInSlot(slot);
            if (stored.isEmpty()) {
                continue;
            }
            final ItemResource key = ItemResource.ofItemStack(stored);
            itemIcons.putIfAbsent(key, stored.copyWithCount(1));
            itemAmounts.merge(key, (long) stored.getCount(), Long::sum);
        }
        for (final java.util.Map.Entry<ItemResource, Long> entry : itemAmounts.entrySet()) {
            out.add(ReceiverCacheEntry.ofItem(itemIcons.get(entry.getKey()), entry.getValue()));
        }
        // 流体：同「流体 + 数据组件」一罐（存储内部已合并，这里再聚合一次只是防未来改动）
        final java.util.LinkedHashMap<FluidResource, FluidStack> fluidIcons = new java.util.LinkedHashMap<>();
        final java.util.LinkedHashMap<FluidResource, Long> fluidAmounts = new java.util.LinkedHashMap<>();
        for (final FluidStack tank : fluidCache.getTanksSnapshot()) {
            if (tank.isEmpty()) {
                continue;
            }
            final FluidResource key = new FluidResource(tank.getFluid(), tank.getComponentsPatch());
            fluidIcons.putIfAbsent(key, tank.copyWithAmount(1));
            fluidAmounts.merge(key, (long) tank.getAmount(), Long::sum);
        }
        for (final java.util.Map.Entry<FluidResource, Long> entry : fluidAmounts.entrySet()) {
            out.add(ReceiverCacheEntry.ofFluid(fluidIcons.get(entry.getKey()), entry.getValue()));
        }
        return out;
    }

    // ==================== 外部容器推入（第 96 轮：溜槽 / 漏斗 / 管道） ====================
    //
    // 两条入口（{@link #insertItemFromExternal} / {@link #fillFluidFromExternal}）都由
    // ViewOnlyHandlers 的可写包装在**服务端**调用；它们唯一的动作就是转交 applyResource(...)，
    // 因此外部推入与请求器投递共用同一份容量上限 / 守恒账（见类注释《对外可见性》第二节）。

    /**
     * 物品：外部容器（Create 溜槽 / 漏斗 / 管道 / 其它模组的自动化）往缓存里推，
     * 返回<b>真正受理的件数</b>；{@code simulate} 时只回答、绝不改任何状态。
     *
     * <p><b>为什么返回值必须是「受理件数」而不是「收下了」布尔</b>：NeoForge 的
     * {@code IItemHandler#insertItem} 用「返回余量」表达部分受理，Create 的溜槽正是靠
     * 「余量 == 原数量吗」判断能不能交出去（{@code ItemHandlerHelper.insertItemStacked}
     * 的 SIMULATE 与 EXECUTE 两次调用必须给出同一个答案）。</p>
     *
     * <p><b>客户端不做任何事</b>：能力只在服务端有写语义；客户端即使被查询也只由
     * {@code ViewOnlyHandlers} 委托读（缓存对象在客户端本来就不同步）。</p>
     */
    public int insertItemFromExternal(final ItemStack stack, final boolean simulate) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        if (getLevel() == null || getLevel().isClientSide()) {
            return 0; // 只有服务端有权威缓存：客户端即使拿到能力也一律「收不下」，绝不在本地写
        }
        final int requested = stack.getCount();
        final long accepted = applyResource(ItemResource.ofItemStack(stack), requested, simulate);
        final int taken = (int) Math.max(0L, Math.min(requested, accepted));
        if (!simulate && taken > 0) {
            setChanged();
        }
        if (!simulate) {
            logExternalPush(taken >= requested ? INPUT_STATE_FULL_ACCEPT
                : (taken > 0 ? INPUT_STATE_PARTIAL : INPUT_STATE_REJECTED),
                "外部容器推入物品 {} x{} → 受理 {} / 请求 {}（缓存 {} 件 / 上限 {} 件）",
                stack.getItem(), requested, taken, requested,
                itemCache.getTotalCount(), itemCache.getCapacity());
        }
        return taken;
    }

    /**
     * 流体：外部容器往缓存里灌，返回<b>真正受理的 mB 数</b>（口径与
     * {@link #insertItemFromExternal} 逐条对称）。
     */
    public int fillFluidFromExternal(final FluidStack stack, final boolean simulate) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        if (getLevel() == null || getLevel().isClientSide()) {
            return 0; // 同 insertItemFromExternal：客户端不做任何权威写入
        }
        final int requested = stack.getAmount();
        final long accepted = applyResource(
            new FluidResource(stack.getFluid(), stack.getComponentsPatch()), requested, simulate);
        final int taken = (int) Math.max(0L, Math.min(requested, accepted));
        if (!simulate && taken > 0) {
            setChanged();
        }
        if (!simulate) {
            logExternalPush(taken >= requested ? INPUT_STATE_FULL_ACCEPT
                : (taken > 0 ? INPUT_STATE_PARTIAL : INPUT_STATE_REJECTED),
                "外部容器灌入流体 {} x{} mB → 受理 {} / 请求 {}（缓存 {} mB / 上限 {} mB）",
                stack.getFluid(), requested, taken, requested,
                fluidCache.getTotalAmount(), fluidCache.getCapacity());
        }
        return taken;
    }

    // ---------- 外部抽出的观测点（状态化：同状态最多 30s 一条，状态一变立刻打） ----------
    /** 外部抽出日志前缀（与推入侧 {@code [rscc-receiver-input]} 分开，便于 grep）。 */
    private static final String EXTRACT_LOG_PREFIX = "[rscc-receiver-extract]";
    /** 抽出状态：还没打过。 */
    private static final int PULL_STATE_NONE = Integer.MIN_VALUE;
    /** 抽出状态：请求多少抽走多少。 */
    private static final int PULL_STATE_ALL = 0;
    /** 抽出状态：只抽走一部分（缓存里就这么多）。 */
    private static final int PULL_STATE_PARTIAL = 1;
    /** 抽出状态：一点都没抽走（缓存空 / 该槽为空）。 */
    private static final int PULL_STATE_EMPTY = 2;
    private int lastPullState = PULL_STATE_NONE;
    private long lastPullLogTick = Long.MIN_VALUE;

    /**
     * 物品：外部容器（Create 溜槽 / 漏斗 / 传送带 / 其它模组的自动化）<b>从缓存里抽</b>，
     * 返回真正被抽走的栈（{@code simulate} 时只回答、绝不改任何状态）。
     *
     * <h2>为什么第 101 轮必须补这个入口（根因）</h2>
     * <p>Create 6.0.10 的 {@code ChuteBlockEntity} 对<b>上方</b>容器是「抽」而不是「推」：
     * 空的普通溜槽每 tick 在 {@code tick()} 里走 {@code handleInputFromAbove()} →
     * {@code grabCapability(Direction.UP)}（查询侧别 = {@code side.getOpposite() = DOWN}，<b>非空</b>）
     * → {@code ItemHelper.extract(inv, canAccept, ExtractionCountMode.UPTO, 16, false)}
     * （{@code build/createsrc/.../chute/ChuteBlockEntity.java:337-364}、{@code ItemHelper.java:183-229}）。
     * 它唯一会「推」进相邻容器的路径是 {@code handleDownwardOutput}（溜槽自己手里已经有货时往下倒）。
     * 第 96 轮把外抽写死成恒空 ⇒ 只要玩家把溜槽放在接收器<b>下面</b>（最自然的「把东西搬走」摆法），
     * 溜槽每 tick 都会来问一次、每次都拿到空，于是「放在哪个方向都没反应」。
     * 抽出口是<b>唯一</b>能让溜槽从本仓取料的入口，因此它必须真实存在。</p>
     *
     * <p><b>守恒口径</b>：抽取直接落到 {@link #itemCache}（与界面取出、朝向输出是同一份缓存），
     * {@code simulate} 与 {@code execute} 走同一条 {@code ItemStackHandler#extractItem} 路径、答案一致；
     * 真正抽出后 {@code setChanged()}（否则重进存档会把已抽走的物品「变回来」= 复制）。
     * 缓存里没有的东西抽不出来 ⇒ 外部自动化<b>不可能</b>凭空多拿。</p>
     *
     * <p><b>为什么不经过 {@link #applyResource}</b>：那条账是「写入」的唯一入口（容量上限 + 请求器投递）；
     * 抽取只是从同样的缓存里扣，扣减本身就是守恒的另一半，共用同一个存储对象即只有一套账。</p>
     */
    public ItemStack extractItemFromExternal(final int slot, final int amount,
                                             final boolean simulate) {
        if (amount <= 0) {
            return ItemStack.EMPTY;
        }
        if (getLevel() == null || getLevel().isClientSide()) {
            return ItemStack.EMPTY; // 同推入：只有服务端有权威缓存
        }
        if (slot < 0 || slot >= itemCache.getSlots()) {
            return ItemStack.EMPTY; // 越界槽位一律抽不出（不给未定义行为）
        }
        final ItemStack taken = itemCache.extractItem(slot, amount, simulate);
        if (!simulate && !taken.isEmpty()) {
            setChanged();
            logExternalPull(taken.getCount() >= amount ? PULL_STATE_ALL
                : (taken.getCount() > 0 ? PULL_STATE_PARTIAL : PULL_STATE_EMPTY),
                "外部容器抽出物品 {} x{} → 实抽 {} / 请求 {}（缓存 {} 件 / 上限 {} 件）",
                taken.getItem(), amount, taken.getCount(), amount,
                itemCache.getTotalCount(), itemCache.getCapacity());
        }
        return taken;
    }

    /**
     * 流体：外部容器从缓存里<b>排</b>，返回真正被排走的流体（口径与
     * {@link #extractItemFromExternal} 逐条对称，单位 mB）。
     * <p>{@code resource} 为空栈 = 调用方走 {@code drain(int maxDrain, ...)} 那一支
     * （「随便什么流体，抽这么多」）；非空 = 按流体 + 组件精确匹配。</p>
     */
    public FluidStack drainFluidFromExternal(final FluidStack resource, final int maxDrain,
                                             final boolean simulate) {
        if (maxDrain <= 0) {
            return FluidStack.EMPTY;
        }
        if (getLevel() == null || getLevel().isClientSide()) {
            return FluidStack.EMPTY;
        }
        final IFluidHandler.FluidAction action = simulate
            ? IFluidHandler.FluidAction.SIMULATE : IFluidHandler.FluidAction.EXECUTE;
        final FluidStack drained = resource == null || resource.isEmpty()
            ? fluidCache.drain(maxDrain, action)          // 「随便什么流体」分支
            : fluidCache.drain(resource, action);          // 精确匹配分支
        if (!simulate && !drained.isEmpty()) {
            setChanged();
            logExternalPull(drained.getAmount() >= maxDrain ? PULL_STATE_ALL
                : (drained.getAmount() > 0 ? PULL_STATE_PARTIAL : PULL_STATE_EMPTY),
                "外部容器排出流体 {} x{} mB → 实排 {} / 请求 {}（缓存 {} mB / 上限 {} mB）",
                drained.getFluid(), maxDrain, drained.getAmount(), maxDrain,
                fluidCache.getTotalAmount(), fluidCache.getCapacity());
        }
        return drained;
    }

    /**
     * 外部抽出的状态化日志：与推入侧 {@link #logExternalPush} 逐条同构
     * （同一状态最多每 {@link #DEBUG_THROTTLE_TICKS} 一条，状态一变立刻打）。
     * <p>为什么必须节流：溜槽是<b>每 tick</b> 来抽的（{@code handleInput} 在空溜槽上每 tick 跑一次），
     * 不节流就是每秒 20 条。</p>
     */
    private void logExternalPull(final int state, final String format, final Object... args) {
        if (!RsccAssemblyDebug.isEnabled()) {
            return;
        }
        final long now = getLevel() != null ? getLevel().getGameTime() : 0L;
        if (state == lastPullState && now - lastPullLogTick < DEBUG_THROTTLE_TICKS) {
            return;
        }
        lastPullState = state;
        lastPullLogTick = now;
        LOGGER.info(EXTRACT_LOG_PREFIX + " " + worldPosition.toShortString() + " | " + format, args);
    }

    // ---------- 外部推入的观测点（状态化：同状态最多 30s 一条，状态一变立刻打） ----------
    /** 外部推入日志前缀（与输出侧 {@code [rscc-receiver-output]} 分开，便于 grep）。 */
    private static final String INPUT_LOG_PREFIX = "[rscc-receiver-input]";
    /** 上一次外部推入的状态（{@link #INPUT_STATE_NONE} = 还没打过）。 */
    private static final int INPUT_STATE_NONE = Integer.MIN_VALUE;
    /** 全部受理。 */
    private static final int INPUT_STATE_FULL_ACCEPT = 0;
    /** 部分受理（缓存快满了）。 */
    private static final int INPUT_STATE_PARTIAL = 1;
    /** 一件都没收（缓存已满 / 类型不认识）。 */
    private static final int INPUT_STATE_REJECTED = 2;
    private int lastInputState = INPUT_STATE_NONE;
    private long lastInputLogTick = Long.MIN_VALUE;

    /**
     * 外部推入的状态化日志：<b>同一状态最多每 {@link #DEBUG_THROTTLE_TICKS}（30 秒）一条，
     * 状态一变立刻打</b>。
     * <p>为什么按「状态」而不是按「这次的数字」指纹：溜槽是连续推的，每次受理量都可能不同 ——
     * 用数字当指纹等于每次推送都打一条，正是第 95 轮刚修掉的刷屏。这里只关心
     * 「能收 / 收一半 / 收不下」这三种状态何时翻转，因此指纹只有三个值。</p>
     * <p>受 {@code devLogs} 总开关控制（与输出侧同一个开关），默认零输出。</p>
     */
    private void logExternalPush(final int state, final String format, final Object... args) {
        if (!RsccAssemblyDebug.isEnabled()) {
            return;
        }
        final long now = getLevel() != null ? getLevel().getGameTime() : 0L;
        if (state == lastInputState && now - lastInputLogTick < DEBUG_THROTTLE_TICKS) {
            return;
        }
        lastInputState = state;
        lastInputLogTick = now;
        LOGGER.info(INPUT_LOG_PREFIX + " " + worldPosition.toShortString() + " | " + format, args);
    }

    /**
     * 本仓的<b>输出面</b>（blockstate 的 {@code FACING}；取不到时退回 {@code NORTH}）。
     * <p>界面的状态行会照实显示它 —— 因为「缓存为什么没被输出到容器」的答案几乎总是
     * 「朝向的那一面没有容器 / 朝向摆错了」，而客户端读不到客户端方块状态以外的任何权威值。</p>
     */
    public Direction getFacing() {
        final BlockState state = getBlockState();
        return state.hasProperty(NetworkResourceReceiverBlock.FACING)
            ? state.getValue(NetworkResourceReceiverBlock.FACING)
            : Direction.NORTH;
    }

    // ==================== 任意资源类型（请求器投递的唯一入口） ====================

    /**
     * 本接收器是否<b>有能力</b>受理这种资源（与「此刻有没有空间」无关，后者由
     * {@link #applyResource(ResourceKey, long, boolean)} 的返回值回答）。
     *
     * <p>判据只有三条，<b>没有任何类型白名单</b>：</p>
     * <ol>
     *     <li>物品 / 流体 ⇒ true（既有缓存永远接得住）；</li>
     *     <li>不是 {@link PlatformResourceKey} ⇒ false —— RS 的落盘 / 同步 codec 本身就不接受它，
     *     收下来只会变成「存不进存档的资源」，那是数据丢失，不是支持；</li>
     *     <li>其余 {@link PlatformResourceKey}（气体等）⇒ 问朝向格：RS 的外部存储提供器链里
     *     是否有东西能收它（探针 = 模拟写入 1 单位）。能 ⇒ true；朝向没有对应容器 / 联动没装 ⇒ false。</li>
     * </ol>
     *
     * <p><b>与「装满」的区别</b>：这里只判「通道是否存在」，因此调用方在真正投递失败时
     * 必须再看返回值，才能区分「不支持这种类型」与「目标已满」（见请求器的提示分流）。</p>
     */
    public boolean canHandle(@Nullable final ResourceKey resource) {
        if (resource == null) {
            return false;
        }
        if (RsccResourceKeys.isItemOrFluid(resource)) {
            return true;
        }
        if (!(resource instanceof PlatformResourceKey)) {
            return false;
        }
        final RsccGenericResourceTarget.Target target = genericTarget();
        return target != null && target.insert(resource, 1L, Action.SIMULATE) > 0L;
    }

    /**
     * 把 {@code amount} 个 {@code resource} 写进本接收器，返回<b>实际受理量</b>；
     * {@code simulate = true} 时只询问、不写任何东西。
     *
     * <p>这是请求器投递的<b>唯一</b>入口（请求器侧不再认识物品 / 流体）：物品 → 物品缓存，
     * 流体 → 流体缓存，其它 {@link PlatformResourceKey} → 直通朝向容器。三条路径都满足
     * 「返回量 == 目标真正收下的量」，因此请求器既有的守恒结算（取多少 = 写入 + 回滚 + 暂存）
     * 对任意资源类型自动成立。</p>
     */
    public long applyResource(@Nullable final ResourceKey resource, final long amount,
                              final boolean simulate) {
        if (resource == null || amount <= 0L) {
            return 0L;
        }
        if (resource instanceof ItemResource item) {
            return applyToItemCache(item, amount, simulate);
        }
        if (resource instanceof FluidResource fluid) {
            return applyToFluidCache(fluid, amount, simulate);
        }
        if (resource instanceof PlatformResourceKey) {
            final RsccGenericResourceTarget.Target target = genericTarget();
            return target == null
                ? 0L
                : target.insert(resource, amount, simulate ? Action.SIMULATE : Action.EXECUTE);
        }
        return 0L; // 不可持久化的键：canHandle 已经判 false，这里只是防线
    }

    /** 物品：沿用改前的 {@code insertItem(0, ...)}（槽位被 {@code RsccUnboundedItemStorage} 忽略）。 */
    private long applyToItemCache(final ItemResource resource, final long amount,
                                  final boolean simulate) {
        // ItemStack 的 count 是 int；缓存总容量本来就远小于 int 上限，夹一次只是防溢出
        final int probeCount = (int) Math.min(amount, Integer.MAX_VALUE);
        final ItemStack probe = resource.toItemStack(probeCount);
        if (probe.isEmpty()) {
            return 0L;
        }
        final ItemStack remainder = itemCache.insertItem(0, probe, simulate);
        return probeCount - remainder.getCount();
    }

    /** 流体：沿用改前的 {@code fill(...)}（SIMULATE / EXECUTE 语义由 {@code IFluidHandler} 给出）。 */
    private long applyToFluidCache(final FluidResource resource, final long amount,
                                   final boolean simulate) {
        final int probeAmount = (int) Math.min(amount, Integer.MAX_VALUE);
        final FluidStack probe = new FluidStack(
            BuiltInRegistries.FLUID.wrapAsHolder(resource.fluid()), probeAmount, resource.components());
        return fluidCache.fill(probe,
            simulate ? IFluidHandler.FluidAction.SIMULATE : IFluidHandler.FluidAction.EXECUTE);
    }

    /**
     * 通用写入通道：{@code FACING} 朝向的相邻格、能力侧 = 朝向的反面
     * （与 RS 外部存储方块 {@code AbstractExternalStorageBlockEntity#loadStorage} 同口径）。
     * <p>结果按「目标格 + 目标方块状态 + 能力侧」缓存（provider 内部含能力缓存，不能每 tick 重建）；
     * 任一项变化就重建，读档 / 放置后由 {@link #onLoad()} 主动清空。</p>
     */
    @Nullable
    private RsccGenericResourceTarget.Target genericTarget() {
        if (!(getLevel() instanceof ServerLevel server)) {
            return null; // 能力查询只能服务端做；客户端永远没有写入语义
        }
        final BlockState state = getBlockState();
        if (!state.hasProperty(NetworkResourceReceiverBlock.FACING)) {
            return null;
        }
        final Direction facing = state.getValue(NetworkResourceReceiverBlock.FACING);
        final BlockPos targetPos = getBlockPos().relative(facing);
        final Direction side = facing.getOpposite();
        final BlockState targetState = server.getBlockState(targetPos);
        if (genericTarget != null && targetPos.equals(genericTargetPos)
            && targetState == genericTargetState && side == genericTargetSide) {
            return genericTarget;
        }
        genericTarget = RsccGenericResourceTarget.resolve(server, targetPos, side);
        genericTargetPos = targetPos;
        genericTargetState = targetState;
        genericTargetSide = side;
        return genericTarget;
    }

    /** 丢掉通用通道缓存（读档 / 区块重载后必须重建：旧 provider 绑的是旧的能力缓存）。 */
    private void invalidateGenericTarget() {
        genericTarget = null;
        genericTargetPos = null;
        genericTargetState = null;
        genericTargetSide = null;
    }

    /** 本机耗电（FE/t）：纯落地点 + 转发，给一个明显低于执行舱的值。 */
    public long getEnergyUsage() {
        return 2;
    }

    // ==================== 节流入口（唯一数值来源 = 升级容器 → RsccRateLimiter） ====================

    /**
     * 节流闸门：返回「本 tick 要不要动作」，并推进倒计时。
     *
     * <p><b>间隔恒为 1</b>（{@link RsccSpeedStackUpgradeContainer#throttleIntervalTicks()} 写死
     * 1 tick）：间隔的物理下限就是「每 tick 一次 {@code doWork}」，所以「间隔」只能表达变慢，
     * 表达不了变快。速度升级的实际旋钮是 {@link RsccSpeedStackUpgradeContainer#throttleBatchesPerTick()}
     * ——放行后本类按那个批数循环（见 {@link #doWork()}）。</p>
     *
     * <p><b>先自减再判</b>：初值 0 ⇒ 第一次调用 {@code --0 = -1} 直接放行；此后每 tick 放行一次，
     * 因此「间隔 = 1」与「每 tick 都放行」逐 tick 等价。</p>
     */
    public boolean throttleTick() {
        if (--throttleCooldown > 0) {
            return false;
        }
        // 间隔一律来自容器（→ 恒为 1）：本类绝不自己写 `<<` / `*` 公式。
        throttleCooldown = upgradeContainer.throttleIntervalTicks();
        return true;
    }

    /** 每 tick 批数（速度升级旋钮）：与节拍同源（都来自升级容器）。 */
    public int getThrottleBatchesPerTick() {
        return upgradeContainer.throttleBatchesPerTick();
    }

    /** 单批搬运量（件）：与节拍同源（都来自升级容器 → {@link RsccRateLimiter}）。 */
    public long getThrottleBatch() {
        return upgradeContainer.throttleBatch();
    }

    /**
     * 方块实体侧的 tick 入口：抽电（父类）→ 节流闸门 → <b>把缓存按「每 tick 批数 × 单批量」
     * 输出到 facing 朝向的相邻容器</b>。
     * <p>{@code !mainNetworkNode.isActive()} 时不推进闸门（与充电器同一口径）：离网 / 没电的机器
     * 不应该攒下「欠下的批次」，重新上线后从干净的节拍开始。</p>
     * <p><b>顺序</b>：① 未命名 ⇒ 直接返回（缓存原样留着）；② 查一次朝向格（{@code facing}）的
     * 物品 / 流体能力；③ 循环 {@link #getThrottleBatchesPerTick()} 次，每轮物品 / 流体各自最多搬
     * {@link #getThrottleBatch()} 个（物品件 / 流体 mB）。某一轮物品与流体都没搬动 ⇒ 本轮提前结束
     * （目标满 / 缓存空时不再空转剩余批次）。能力查询<b>每 tick 至多各一次</b>，循环里绝不再查。</p>
     */
    @Override
    public void doWork() {
        super.doWork();
        if (!mainNetworkNode.isActive()) {
            return;
        }
        if (!throttleTick()) {
            return;
        }
        // 空名 = 完全不可用：不输出、不扣减，缓存里的东西原样留着（见 isProperlyConfigured 的语义边界）
        if (!isProperlyConfigured()) {
            return;
        }
        final Level level = getLevel();
        if (level == null || level.isClientSide()) {
            return; // 服务端世界才有搬运语义（客户端没有权威容器）
        }
        // 缓存全空 ⇒ 连能力查询都不做（每 tick 零成本）
        if (itemCache.getTotalCount() <= 0 && fluidCache.getTotalAmount() <= 0) {
            return;
        }
        final BlockState state = getBlockState();
        if (!state.hasProperty(NetworkResourceReceiverBlock.FACING)) {
            return; // 取不到朝向 ⇒ 不猜（正常不会发生：本方块状态恒有 facing）
        }
        // 输出面 = facing 指向的相邻格（与执行舱「朝向面」同一口径）
        final BlockPos targetPos =
            getBlockPos().relative(state.getValue(NetworkResourceReceiverBlock.FACING));
        // 【性能】每 tick 至多各查一次容器能力，绝不在逐槽 / 逐罐循环里重复查找
        final IItemHandler itemTarget = RsccChamberImportStrategy.itemHandlerAt(level, targetPos);
        final IFluidHandler fluidTarget = RsccChamberImportStrategy.fluidHandlerAt(level, targetPos);
        if (itemTarget == null && fluidTarget == null) {
            debug("朝向 {} 的相邻格 {} 没有物品 / 流体容器：缓存原样保留（物品 {} 件，流体 {} mB）",
                state.getValue(NetworkResourceReceiverBlock.FACING), targetPos,
                itemCache.getTotalCount(), fluidCache.getTotalAmount());
            return;
        }
        // 批数 / 批量同源：一律读升级容器（→ RsccRateLimiter），这里绝不另算一遍
        final long batch = getThrottleBatch();
        final int batches = getThrottleBatchesPerTick();
        if (batch <= 0 || batches <= 0) {
            return;
        }
        // 物品与流体各自最多搬「每 tick 批数」批（两类资源互不抢占额度）
        for (int round = 0; round < batches; round++) {
            final long movedItems = outputItems(itemTarget, batch);
            final long movedFluids = outputFluids(fluidTarget, batch);
            if (movedItems <= 0L && movedFluids <= 0L) {
                // 这一轮一点都没搬动（目标满 / 缓存已空）⇒ 提前结束，不让剩余批次空转
                break;
            }
        }
    }

    /**
     * 把物品缓存按批量输出到目标容器（{@code target == null} ⇒ 什么都不扣）。
     *
     * <p><b>守恒顺序（最重要的判据）</b>：</p>
     * <ol>
     *     <li>先 {@code insertItem(..., true)} <b>询问</b>目标最多能收多少（simulate，不写也不扣）；</li>
     *     <li>{@code accepted = 送出的 - 退回的}，<b>只有目标承诺能收的量</b>才从缓存取出；</li>
     *     <li>真正 {@code insertItem(..., false)} 写入，按「目标<b>确实收下</b>的量」结算；</li>
     *     <li>若 execute 阶段只收了一部分，差额原样放回缓存。</li>
     * </ol>
     * <p>于是「缓存扣减量 == 目标接收量」恒成立：目标满 / 无容器 / 未加载 ⇒ 一个零件都不扣。</p>
     *
     * @return 本次调用真正写进目标容器的件数（0 = 一点没动，调用方据此提前结束本轮批次循环）
     */
    private long outputItems(@Nullable final IItemHandler target, final long batch) {
        if (target == null) {
            if (itemCache.getTotalCount() > 0) {
                debug("没有物品目标容器：物品缓存 {} 件原样保留", itemCache.getTotalCount());
            }
            return 0L;
        }
        long budget = batch;
        int moved = 0;
        for (int slot = 0; slot < itemCache.getSlots() && budget > 0; slot++) {
            final ItemStack stored = itemCache.getStackInSlot(slot);
            if (stored.isEmpty()) {
                continue;
            }
            final int offerCount = (int) Math.min(stored.getCount(), budget);
            final ItemStack offer = stored.copyWithCount(offerCount);
            // ① 先问「目标最多能收多少」：simulate 不写任何东西，也绝不扣缓存。
            // 目标容器可能抛异常：此时缓存一个零件都没动，跳过这一格即可（异常不能掀翻 tick）。
            final ItemStack simulatedLeft;
            try {
                simulatedLeft = insertInto(target, offer, true);
            } catch (final RuntimeException e) {
                LOGGER.error(LOG_PREFIX + " 模拟插入 {} 时目标容器抛异常，已跳过该格",
                    offer.getItem(), e);
                continue;
            }
            final int accepted = offer.getCount() - simulatedLeft.getCount();
            if (accepted <= 0) {
                debug("物品目标拒收 {} x{}（目标已满）：不扣缓存", offer.getItem(), offerCount);
                continue;
            }
            // ② 只取出「目标承诺能收的量」
            final ItemStack extracted = itemCache.extractItem(slot, accepted, false);
            final int taken = extracted.getCount();
            if (taken <= 0) {
                continue; // 缓存没给出（理论上不可能）：不写目标、不扣任何东西
            }
            // ③ 真正写入，并按「目标确实收下的量」结算。
            // 目标在 execute 阶段抛异常 ⇒ 按「一件都没收下」处理并把抽出的量放回缓存：
            // 宁可极端情况下重复，也绝不销毁（守恒铁律）。
            final ItemStack leftover;
            try {
                leftover = insertInto(target, extracted, false);
            } catch (final RuntimeException e) {
                itemCache.insertItem(0, extracted, false);
                LOGGER.error(LOG_PREFIX + " 真正插入 {} 时目标容器抛异常，已把 {} 件放回缓存",
                    extracted.getItem(), taken, e);
                continue;
            }
            final int landed = taken - leftover.getCount();
            if (landed < taken) {
                // ④ execute 阶段只收了一部分：差额原样放回缓存 ⇒ 扣减量 == 接收量
                itemCache.insertItem(0, extracted.copyWithCount(taken - landed), false);
                debug("物品目标只收下 {} / {}，差额已放回缓存", landed, taken);
            }
            if (landed > 0) {
                budget -= landed;
                moved += landed;
                setChanged();
            }
        }
        if (moved > 0) {
            debug("输出物品 {} 件到朝向容器", moved);
        }
        return moved;
    }

    /**
     * 逐槽把 {@code stack} 塞进目标，返回<b>塞不下的余量</b>（{@code simulate=true} 时只询问、不写入）。
     * <p>为什么逐槽：{@code IItemHandler#insertItem(slot, ...)} 只作用于调用方给的那一个槽位，
     * 固定传 0 会让多槽容器（箱子 / 料斗）只有第一格能收 —— 那是必须避免的第二套口径。</p>
     */
    private static ItemStack insertInto(final IItemHandler target, final ItemStack stack,
                                        final boolean simulate) {
        int remaining = stack.getCount();
        for (int slot = 0; slot < target.getSlots() && remaining > 0; slot++) {
            final ItemStack left = target.insertItem(slot, stack.copyWithCount(remaining), simulate);
            remaining = left.getCount();
        }
        return remaining <= 0 ? ItemStack.EMPTY : stack.copyWithCount(remaining);
    }

    /**
     * 把流体缓存按批量输出到目标容器（{@code target == null} ⇒ 什么都不扣）。
     * <p>守恒顺序与 {@link #outputItems} 逐条对称：先 {@code fill(SIMULATE)} 问能收多少，
     * 只取出「承诺能收的量」，再 {@code fill(EXECUTE)} 写入并按<b>实际收下</b>的量结算，
     * 差额 {@code fill} 回缓存。数量单位 mB。</p>
     *
     * @return 本次调用真正写进目标容器的 mB 数（0 = 一点没动，调用方据此提前结束本轮批次循环）
     */
    private long outputFluids(@Nullable final IFluidHandler target, final long batch) {
        if (target == null) {
            if (fluidCache.getTotalAmount() > 0) {
                debug("没有流体目标容器：流体缓存 {} mB 原样保留", fluidCache.getTotalAmount());
            }
            return 0L;
        }
        long budget = batch;
        int moved = 0;
        // 先取快照：边遍历边从缓存扣减会改内部列表（与 RsccUnboundedFluidStorage 的说明同一理由）
        for (final FluidStack stored : fluidCache.getTanksSnapshot()) {
            if (budget <= 0) {
                break;
            }
            if (stored.isEmpty()) {
                continue;
            }
            final int offerAmount = (int) Math.min(stored.getAmount(), budget);
            final FluidStack offer = stored.copyWithAmount(offerAmount);
            // ① 先问「目标最多能收多少」：SIMULATE 不写任何东西，也绝不扣缓存。
            // 目标容器可能抛异常：缓存一个字节都没动，跳过该罐即可。
            final int accepted;
            try {
                accepted = target.fill(offer, IFluidHandler.FluidAction.SIMULATE);
            } catch (final RuntimeException e) {
                LOGGER.error(LOG_PREFIX + " 模拟填充 {} 时目标容器抛异常，已跳过该罐",
                    stored.getFluid(), e);
                continue;
            }
            if (accepted <= 0) {
                debug("流体目标拒收 {} x{} mB（目标已满）：不扣缓存",
                    stored.getFluid(), offerAmount);
                continue;
            }
            // ② 只取出「目标承诺能收的量」
            final FluidStack drained =
                fluidCache.drain(offer.copyWithAmount(accepted), IFluidHandler.FluidAction.EXECUTE);
            final int taken = drained.getAmount();
            if (taken <= 0) {
                continue;
            }
            // ③ 真正写入，并按「目标确实收下的量」结算。
            // 目标在 execute 阶段抛异常 ⇒ 把抽出的量放回缓存（宁可重复，绝不销毁）。
            final int landed;
            try {
                landed = target.fill(drained, IFluidHandler.FluidAction.EXECUTE);
            } catch (final RuntimeException e) {
                fluidCache.fill(drained, IFluidHandler.FluidAction.EXECUTE);
                LOGGER.error(LOG_PREFIX + " 真正填充 {} 时目标容器抛异常，已把 {} mB 放回缓存",
                    drained.getFluid(), taken, e);
                continue;
            }
            final int rejected = taken - landed;
            if (rejected > 0) {
                // ④ 差额原样放回缓存（多罐存储会并入同流体罐）⇒ 扣减量 == 接收量
                fluidCache.fill(drained.copyWithAmount(rejected), IFluidHandler.FluidAction.EXECUTE);
                debug("流体目标只收下 {} / {} mB，差额已放回缓存", landed, taken);
            }
            if (landed > 0) {
                budget -= landed;
                moved += landed;
                setChanged();
            }
        }
        if (moved > 0) {
            debug("输出流体 {} mB 到朝向容器", moved);
        }
        return moved;
    }

    /** 上一次 debug 的内容指纹；用于「同一状态不重复刷」。 */
    private int lastDebugHash;
    /** 上一次 debug 的 gameTime；用于节流。 */
    private long lastDebugTick = Long.MIN_VALUE;
    /**
     * 同一状态最多每 600 tick（30 秒）打一条；<b>内容一变立刻打</b>（= 「状态变化才记」）。
     * <p>第 95 轮修：旧值是 100 tick（5 秒）。用户实机日志里 {@code [rscc-receiver-output]}
     * 从 23:07:49 起每 5 秒一条、连刷整晚（「朝向 up 的相邻格没有容器」是常态）——
     * 5 秒仍然算刷屏，而且这条信息的价值只在「状态变化」那一下。现在 30 秒只是
     * 「状态一直不变」时的最长沉默上限，保证玩家开着 devLogs 时仍能确认机器还在跑。</p>
     */
    private static final long DEBUG_THROTTLE_TICKS = 600L;

    /**
     * 输出失败 / 部分接收的观测点：受 {@code devLogs} 总开关控制。
     * <p><b>为什么还要再节流</b>：这条路径每 tick 都会被调用（「朝向格没有容器」是常态），
     * 而玩家很可能开完 {@code /rs_create_compat devlogs on} 就忘了关 —— 实测服务器上
     * {@code [rscc-receiver-output]} 每 5 秒一条连刷整晚。所以：<b>内容指纹一变（状态真的变了）
     * 立刻打一条</b>；指纹不变时最多每 30 秒一条。既看得见状态变化，又不会刷屏。</p>
     */
    private void debug(final String format, final Object... args) {
        if (!RsccAssemblyDebug.isEnabled()) {
            return;
        }
        final int hash = java.util.Arrays.deepHashCode(new Object[] {format, args});
        final long now = getLevel() != null ? getLevel().getGameTime() : 0L;
        if (hash == lastDebugHash && now - lastDebugTick < DEBUG_THROTTLE_TICKS) {
            return;
        }
        lastDebugHash = hash;
        lastDebugTick = now;
        LOGGER.info(LOG_PREFIX + " " + worldPosition.toShortString() + " | " + format, args);
    }

    /**
     * 供菜单同步的实时数据（与 {@code RangeChargerBlockEntity#getContainerData()} 同一写法）。
     * <p>索引：0 = <b>每 tick 批数</b>（速度升级旋钮），1 = 单批搬运量（件，叠加升级旋钮）。
     * 只有这两个值参与搬运、也只有这两个值会被界面显示，因此只同步这两格
     * （升级个数本身由升级槽里的物品直接可见，不需要再同步一份只能白算的副本）。</p>
     * <p><b>为什么界面必须读同步值而不是自己算</b>：客户端根本没有方块实体
     * （{@code NetworkResourceReceiverMenu} 的两参构造器给的就是 {@code null}），
     * 任何自算都会与方块实体分叉；而「自算」正是范围充电器界面显示负数的根因。</p>
     */
    public net.minecraft.world.inventory.ContainerData getContainerData() {
        return new net.minecraft.world.inventory.ContainerData() {
            @Override
            public int get(final int index) {
                return switch (index) {
                    // 0 = 每 tick 批数（速度升级旋钮）。旧实现这里是「节拍间隔」，但间隔物理下限
                    // 就是 1 tick，速度升级永远无法让它变小 ⇒ 那个字段永远撒谎。
                    case 0 -> upgradeContainer.throttleBatchesPerTick();
                    // 批量是 long（饱和乘法可以到 Long.MAX_VALUE），同步通道只有 int：
                    // 夹到 Integer.MAX_VALUE 只影响「界面极端显示」，不影响任何实际搬运量。
                    case 1 -> (int) Math.min(upgradeContainer.throttleBatch(), Integer.MAX_VALUE);
                    default -> 0;
                };
            }

            @Override
            public void set(final int index, final int value) {
                // 升级数与节流都是服务端权威值，客户端不得改写
            }

            @Override
            public int getCount() {
                return 2;
            }
        };
    }

    /**
     * 本方块<b>没有</b>红石模式：{@link #hasRedstoneMode()} 的用途是「界面右侧要不要显示 RS 原版
     * 红石按钮」，而本方块的行为与红石无关（触发请求器的是红石，不是接收器）。
     * 与 {@code IntermediateCacheBlockEntity} / {@code SequenceExecutionChamberBlockEntity} 同口径。
     */
    @Override
    protected boolean hasRedstoneMode() {
        return false;
    }

    @Override
    public Component getName() {
        return getBlockState().getBlock().getName();
    }

    // ==================== 名字：逐字照搬执行舱的命名与唯一性语义 ====================

    /** 本仓的接收器名字（可能为空串 = 未命名）。 */
    public String getReceiverName() {
        return receiverName;
    }

    /**
     * 设置名字（{@code null} 归一为 {@code ""}）。
     * <p>这里做的是「写值 + 落盘」，<b>不做</b>唯一性拒绝：拒绝属于「界面提交改名」那条路径的职责
     * （服务端在应用前先问 {@link #isNameTakenInNetwork(String)}），把两者分开是为了让
     * <b>唯一判据只有一处</b>（见 {@link #isNameTakenInNetwork(String)} 的 javadoc）。</p>
     */
    public void setReceiverName(@Nullable final String name) {
        final String value = name == null ? "" : name;
        if (value.equals(this.receiverName)) {
            return;
        }
        this.receiverName = value;
        setChanged();
    }

    /**
     * 只读：本仓是否<b>配置完成</b>（口径与执行舱 {@code isProperlyConfigured()} 一致）。
     *
     * <p><b>用户原话</b>：「这个名字必须要设定才可以用，否则他就无法使用」。
     * 因此空名 ⇒ 本仓<b>完全不工作</b>：{@link #doWork()} 的「drain 到朝向容器」与请求器的
     * 「作为投递目标被解析」（{@code resolveReceiversByName} 只收已配置的接收器）两处都先问这个方法。注意语义边界 —— <b>不工作 ≠ 清空</b>：
     * 缓存里的东西在未命名期间<b>原样留着</b>，绝不因为没名字就被吐出来或销毁。</p>
     *
     * <p>执行舱的原实现是 {@code name 非空 && recipeType 非空}（{@code SequenceExecutionChamberBlockEntity}
     * 里的 {@code isProperlyConfigured()}）。本仓<b>没有</b>配方类型这一维，因此判据只剩名字一项
     * —— 这是<b>同一口径在少一维时的自然退化</b>，不是第二套规则。</p>
     */
    public boolean isProperlyConfigured() {
        return receiverName != null && !receiverName.isEmpty();
    }

    /**
     * 本仓所在网络内是否已有<b>另一台</b>接收器使用了同名（空名视为不冲突，未接入网络时无从比较 ⇒ 放行）。
     *
     * <p><b>逐字照搬执行舱</b>（{@code SequenceExecutionChamberBlockEntity#isNameTakenInNetwork}）：
     * 判据就是 {@link #occupiedReceiverNamesInNetwork()} 的集合成员关系 —— <b>一处口径</b>，
     * 界面侧的实时禁用与这里的最终拒绝永远得出同一个结论。</p>
     */
    public boolean isNameTakenInNetwork(@Nullable final String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        return occupiedReceiverNamesInNetwork().contains(name);
    }

    /**
     * 本仓所在网络内<b>已经占用了的名字集合</b>（去重；<b>不含本台</b>；未接入网络时为空集）。
     *
     * <p><b>为什么单独抽出来</b>（照搬执行舱 {@code occupiedChamberNamesInNetwork} 的理由）：
     * 客户端没有 {@link Network} 对象、也读不到别人的方块实体，因此这份集合要随包下发一次，
     * 让界面在玩家输入时<b>实时</b>判重并禁用「确定」；服务端仍然<b>最终再判一次</b>。
     * 两侧读的是同一个方法 ⇒ 不可能出现「客户端放行、服务端拒绝」或反之
     * （大小写 / trim / 空名的处理自然一致）。</p>
     *
     * <p><b>它已经随包下发</b>：{@code SyncReceiverConfigPacket#occupiedNames} 在「打开界面 /
     * 改名」时把这份集合发给客户端做即时提示（见 {@code NetworkResourceReceiverMenu#sendConfigSync}）。</p>
     *
     * <p><b>与执行舱的一处必要差异（不是第二套规则）</b>：执行舱在收集时会把「同一条链」的成员
     * 全部跳过，因为整条链<b>共享同一个名字</b>，不跳过就会自己和自己冲突。
     * 接收器<b>没有链</b>这个概念（一台就是一个独立的落地点），因此这里只跳过自己
     * —— 于是「把名字改成它现在本来就叫的那个名字」会被判成冲突，这由调用方在
     * 「名字其实没变 ⇒ 直接成功返回」那一步消化掉（执行舱是同一种处理方式）。</p>
     */
    public Set<String> occupiedReceiverNamesInNetwork() {
        final Set<String> names = new LinkedHashSet<>();
        for (final NetworkResourceReceiverBlockEntity other : receiversInNetwork()) {
            if (other == this) {
                continue; // 自己不算冲突
            }
            final String name = other.getReceiverName();
            if (name != null && !name.isEmpty()) {
                names.add(name);
            }
        }
        return names;
    }

    /**
     * 本仓所在网络内的全部接收器（含自身）；未接入网络返回空列表。
     * <p>与执行舱 {@code chambersInNetwork()} 同源：走 RS 自己的网络图
     * （{@code GraphNetworkComponent#getContainers()}），因此天然满足
     * 「扳手断开线缆」与「分隔框架阻断」两道闸门，且<b>绝不重复实现连接判定</b>。</p>
     */
    private java.util.List<NetworkResourceReceiverBlockEntity> receiversInNetwork() {
        final Network network = getNode().getNetworkOrNull();
        if (network == null) {
            return java.util.List.of();
        }
        final java.util.List<NetworkResourceReceiverBlockEntity> found = new java.util.ArrayList<>();
        for (final NetworkNodeContainer container : network.getComponent(GraphNetworkComponent.class)
            .getContainers()) {
            if (container.getNode() instanceof NetworkResourceReceiverNetworkNode node
                && node.getBlockEntity() != null) {
                found.add(node.getBlockEntity());
            }
        }
        return found;
    }

    /**
     * 显示名：有名字就用名字，否则回退为坐标 {@code "x,y,z"}（与执行舱 {@code getChamberDisplayName}
     * 同一口径，玩家在列表里总能区分「哪一台」）。
     */
    public String getReceiverDisplayName() {
        final String name = getReceiverName();
        if (name != null && !name.isEmpty()) {
            return name;
        }
        return worldPosition.getX() + "," + worldPosition.getY() + "," + worldPosition.getZ();
    }

    // ==================== 持久化 ====================

    @Override
    public void saveAdditional(final CompoundTag tag, final HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putString(TAG_NAME, receiverName == null ? "" : receiverName);
        tag.put(TAG_UPGRADES, upgradeContainer.serializeNBT(registries));
        tag.put(TAG_ITEM_CACHE, itemCache.serializeNBT(registries));
        // 流体缓存：多罐列表（键 Fluids），见 RsccUnboundedFluidStorage#writeToNBT
        tag.put(TAG_FLUID_CACHE, fluidCache.writeToNBT(registries, new CompoundTag()));
    }

    @Override
    public void loadAdditional(final CompoundTag tag, final HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        receiverName = tag.getString(TAG_NAME);
        // 读档期间不校验中间状态，读完统一校验一次（历史存档里的非法 / 超限升级在这一刻被弹出）
        upgradeContainer.beginLoading();
        try {
            if (tag.contains(TAG_UPGRADES)) {
                upgradeContainer.deserializeNBT(registries, tag.getCompound(TAG_UPGRADES));
            }
            if (tag.contains(TAG_ITEM_CACHE)) {
                itemCache.deserializeNBT(registries, tag.getCompound(TAG_ITEM_CACHE));
            }
            if (tag.contains(TAG_FLUID_CACHE)) {
                fluidCache.readFromNBT(registries, tag.getCompound(TAG_FLUID_CACHE));
            }
        } finally {
            upgradeContainer.finishLoading();
        }
    }

    /** 区块加载完成：再校验一次（与 {@code onLoad} 时机一致的兜底，成本是 6 次槽位读取）。 */
    @Override
    public void onLoad() {
        super.onLoad();
        upgradeContainer.validate();
        // 区块重载 / 读档后旧 provider 绑的能力缓存已经作废：下一次用到时重建
        invalidateGenericTarget();
    }

    /**
     * 网络节点容器能力：让本方块成为 RS 网络成员（线缆接得上、能被枚举到）。
     * <p>与其它机器完全同一套注册方式（{@code IntermediateCacheBlockEntity#registerCapabilities} 同款），
     * 因此「尊重扳手断开与分隔框架的阻断」由 RS 网络图全权负责。</p>
     *
     * <h2>物品 / 流体能力（用户硬规则：所有带缓存的方块都必须对外暴露 Inventory）</h2>
     * <p>用户原话：「<i>以后所有「储存物品 / 带缓存」的方块或设备，都必须对外暴露 Inventory</i>……
     * 例如资源接收器：鼠标指针指向它时，应能显示其内部存储」。</p>
     * <ul>
     *     <li><b>两向包装（可推入 + 可抽出）</b>（{@link ViewOnlyHandlers#writableItem} /
     *     {@link ViewOnlyHandlers#writableFluid} + {@code withExtraction} / {@code withDrain}）：
     *     {@code insertItem} / {@code fill} 落到 {@link #insertItemFromExternal} /
     *     {@link #fillFluidFromExternal} —— 也就是请求器投递的<b>同一个</b>
     *     {@link #applyResource} 入口（容量上限 + 守恒只有一套账）；
     *     {@code extractItem} / {@code drain} 落到 {@link #extractItemFromExternal} /
     *     {@link #drainFluidFromExternal} —— 直接从同一份缓存扣减（缓存里没有的抽不出来）。
     *     <p><b>第 96 轮为什么反转 insert</b>：用户实测「<i>网络资源接收器还是不能跟溜槽之类的进行交互</i>」。
     *     Create 6.0.10 {@code ChuteBlockEntity#grabCapability} 用 {@code side.getOpposite()} 的
     *     <b>非空</b>侧别查询能力（我们注册时忽略侧别 ⇒ 溜槽看得见），随后
     *     {@code ItemHandlerHelper.insertItemStacked(capBelow, item, simulate)} 先 SIMULATE 后 EXECUTE；
     *     旧实现一律「原样退回」⇒ 溜槽判定交不出去 ⇒ 玩家看到「完全不交互」。</p>
     *     <p><b>第 101 轮为什么连 extract 也放开</b>：第 96 轮只答对了「推入」那一半。Create 溜槽对
     *     <b>上方</b>容器<b>不推只抽</b>（{@code ChuteBlockEntity#handleInputFromAbove} →
     *     {@code grabCapability(Direction.UP)} → {@code ItemHelper.extract(...)}，见
     *     {@code build/createsrc/.../chute/ChuteBlockEntity.java:337-364}）；把溜槽放在接收器下面
     *     这个最自然的「搬走」摆法只走 {@code extractItem}，恒空 ⇒ 溜槽在<b>任何</b>方向都没反应
     *     （用户原话「与溜槽交互的时候它是无视方向的」）。两向都遵守 {@code simulate} 契约：
     *     SIMULATE 不改任何状态，只有 EXECUTE 才写缓存并 {@code setChanged()}。</p></li>
     *     <li><b>与查询方向无关</b>（用户原话：「<i>网络资源接收器在作为一个 inventory 提供者时
     *     不应该有方向限制，并且方向只针对于成链机制</i>」）：{@code direction == null} 与任意
     *     {@code Direction} 都返回<b>同一个</b>视图。管道 / 漏斗 / 溜槽 / Jade 按面查询因此
     *     都能看见这份 content、也都能往里推 / 从里抽；</li>
     *     <li><b>方向只用于成链</b>：{@code FACING} 决定「缓存 → 朝向相邻容器」的输出
     *     （{@link #doWork()}）与第三方资源直通（{@link #genericTarget()}），
     *     与「这份 inventory 挂在哪些面」无关。</li>
     * </ul>
     * <p><b>Jade 的内容行就靠这两条能力</b>：Jade 的 Universal 插件把物品 / 流体存储 provider
     * 注册给<b>所有方块</b>，并在服务端以 {@code side = null} 查询能力、把内容随 Jade 自己的
     * 服务端数据通道下发（不是客户端读方块实体）。因此本模组的 Jade provider 只剩
     * 「名字 / 未命名不可用」这一行 Jade 结构上拿不到的信息（见
     * {@code compat/jade/RsccReceiverJadePayload}）。既然能力已经覆盖内容显示，
     * 就<b>不再自己写「按住 Shift 看明细」</b> —— 用户明确要求「只暴露接口」。</p>
     */
    public static void registerCapabilities(final RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(
            RefinedStorageNeoForgeApi.INSTANCE.getNetworkNodeContainerProviderCapability(),
            RS_Create_Compat.NETWORK_RESOURCE_RECEIVER_BLOCK_ENTITY.get(),
            (blockEntity, direction) -> blockEntity.getContainerProvider()
        );
        // 物品缓存：任何方向都返回同一个**两向包装**（读委托真实缓存 / 插入走缓存唯一入口 /
        // 抽取走缓存自身扣减）—— 用户原话「作为 inventory 提供者时不应该有方向限制」。
        // 方向（FACING）只用于成链：缓存 → 朝向容器；它不参与「谁能看见这份 inventory」。
        // 第 96 轮：insert 放行（用户「溜槽之类的不能交互」）；
        // 第 101 轮：extract 也放行 —— Create 溜槽对上方容器**只抽不推**（handleInputFromAbove →
        // ItemHelper.extract），只放行 insert 等于「把溜槽放在哪个方向都没反应」。
        event.registerBlockEntity(
            Capabilities.ItemHandler.BLOCK,
            RS_Create_Compat.NETWORK_RESOURCE_RECEIVER_BLOCK_ENTITY.get(),
            (blockEntity, direction) ->
                ViewOnlyHandlers.writableItem(blockEntity.itemCache,
                    blockEntity::insertItemFromExternal)
                    .withExtraction(blockEntity::extractItemFromExternal)
        );
        // 流体缓存：与物品同一口径（任何方向都返回同一份两向包装：fill 落缓存唯一入口、drain 真排）
        event.registerBlockEntity(
            Capabilities.FluidHandler.BLOCK,
            RS_Create_Compat.NETWORK_RESOURCE_RECEIVER_BLOCK_ENTITY.get(),
            (blockEntity, direction) ->
                ViewOnlyHandlers.writableFluid(blockEntity.fluidCache,
                    blockEntity::fillFluidFromExternal)
                    .withDrain(blockEntity::drainFluidFromExternal)
        );
    }

}
