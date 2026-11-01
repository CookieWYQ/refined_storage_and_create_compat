package cretae.cookiewyq.rs_create_compat.block.entity;

import com.mojang.logging.LogUtils;
import com.refinedmods.refinedstorage.common.api.storage.StorageContainerItem;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.ProxyFaceRole;
import cretae.cookiewyq.rs_create_compat.support.InventoryProxyHandlers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.BundleItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 容器代理器（{@code inventory_proxy}）的方块实体：<b>纯能力提供者，不是 RS 网络节点</b>。
 *
 * <h2>它做什么</h2>
 * <ul>
 *     <li>一个「代理对象槽」：放一个自带库存的物品（潜影盒 / Sophisticated Backpack / RS 磁盘…）。</li>
 *     <li>六个面各有一个 {@link ProxyFaceRole}，逐面存 NBT（默认 NORTH=主内容物、SOUTH=代理对象、其余禁用）。
 *     扳手右键点哪一面就循环哪一面；界面里也能逐面切换（{@code clickMenuButton}）。</li>
 *     <li>对象面 → 暴露「代理对象本身」（1 槽）；主内容物面 → 暴露它的 {@code ItemHandler} /
 *     {@code FluidHandler}（完整读写）；备内容物面 → 只进不出（见下）；禁用面 → {@code null}。</li>
 * </ul>
 *
 * <h2>为什么不做 RS 网络节点（源码级硬约束）</h2>
 * <p>设计文档 {@code docs/CONTAINER_PROXY_DESIGN.md} §4.4.4 / R7：如果本方块同时是网络节点（存储 = 内容物）
 * 又把同一内容物通过能力暴露出去，同一批物品会被「节点存储」与「外部存储」各计一次 ⇒ 真正的自指环
 * （RS 2.0.9 只特判 {@code InterfaceBlock}，不会保护第三方方块）。所以本类<b>不继承</b>
 * {@code AbstractNetworkNodeContainerBlockEntity}、也<b>不注册</b> {@code NetworkNodeContainerProvider} 能力。</p>
 *
 * <h2>面与「不双计」（第 96 轮用户第 3 条：接两个 RS 外部存储 ⇒ 终端数量翻倍）</h2>
 * <p>RS 2.0.9 不按源方块去重：每个 RS 外部存储方块各自建一个 {@code ExternalStorage}，网络把各 storage
 * 的库存<b>求和</b>（{@code api/network/impl/node/externalstorage/ExternalStorageNetworkNode} +
 * {@code api/storage/external/ExternalStorage}；库存快照来自 handler 的 {@code getStackInSlot} /
 * {@code getFluidInTank}，见 {@code CapabilityCache#getItemAmountIterator} /
 * {@code #getFluidAmountIterator}）。所以「同一个底层库存暴露在两个面上、两个面各贴一个 RS 外部存储」
 * 会让终端数量翻倍 —— 不是复制物品（真实抽出量永不超过实际数量），而是同一份库存被数了两遍。</p>
 * <p>本类的解法是把 {@code ProxyFaceRole.CONTENTS} 拆成两档（口径由 Lead 拍板，见
 * {@code docs/ROUND96_USER_FEEDBACK.md} 第 3 条）：</p>
 * <ol>
 *     <li><b>主内容物面</b>（{@link ProxyFaceRole#CONTENTS}）：完整读写，RS 看得见内容物；</li>
 *     <li><b>备内容物面</b>（{@link ProxyFaceRole#CONTENTS_INPUT}）：只进不出 ——
 *     {@code insertItem} / {@code fill} 真实写进同一个底层库存（守恒），但 {@code getStackInSlot} /
 *     {@code getFluidInTank} / {@code extractItem} / {@code drain} 一律空 ⇒ RS 在备面读到的库存恒为 0，
 *     不参与网络求和；漏斗 / 溜槽 / 管道仍能往里推。</li>
 * </ol>
 * <p>同一份底层库存最多只有一个主面：{@link #normalizeFaces()} 在每次换面 / 读档后把
 * {@link Direction#ordinal()} 最小的内容物面留作主面、其余内容物面降为备面（老存档里可能有两个
 * {@code "contents"}，读档即归一；不丢配置、不崩存档）。</p>
 * <p><b>代理对象面也只能有一个</b>（第 96 轮独立复核补充）：每个 {@code PROXY_OBJECT} 面返回的都是
 * 同一个 {@link InventoryProxyHandlers.ObjectView}（1 槽 = 代理槽里那件容器物品），两个对象面各贴
 * 一个 RS 外部存储 ⇒ 同一件代理对象被两份 {@code ExternalStorage} 各读一次，终端上连它都翻倍 ——
 * 与内容物那条完全同源。所以 {@link #normalizeFaces()} 同样只保留 ordinal 最小的对象面，其余降为
 * {@link ProxyFaceRole#DISABLED}（{@link #normalizeObjectFaces()}）；降成禁用而不是「对象备面」，
 * 是为了不给「从机器侧取出代理对象」造第二条路径。</p>
 *
 * <h2>缓存与失效</h2>
 * <p>五个对外视图（对象面 / 主内容物物品 / 主内容物流体 / 备内容物物品 / 备内容物流体）都是
 * <b>稳定实例</b>（{@link InventoryProxyHandlers}）；内层 handler 按
 * {@code ItemStack} 身份缓存（{@code innerFor != proxyStack} 才重解析）。换代理对象只清内层，
 * 外部缓存（RS 的 {@code BlockCapabilityCache}）仍持有同一个外层实例并读到新内层。
 * 只有「面的角色变了」才 {@link #invalidateCapabilities()} —— 那一面可能从 {@code null} 变成有、
 * 或从有变成 {@code null}，也可能从「主（完整读写）」变成「备（只进不出）」或反之；不通知的话
 * 外部缓存会一直用旧 handler（例如 RS 的外部存储会继续按「主」去数备面的库存）。</p>
 *
 * <h2>「两路能力必须各自独立、且退化态不许粘住」是第 108 轮修复的根因</h2>
 * <p>用户反馈（1.2.0-beta.7 实机）：「有一个背包被正常识别了（没装储罐升级）；另一个装了储罐升级、
 * 里面有经验（流体）还有很多物品。把装了储罐升级的那个放进去，用 Jade 查看只显示流体，物品不显示。」
 * 反编译 Sophisticated Backpacks 3.26.3.2158 + Sophisticated Core 1.5.17 逐条核过之后，
 * 「物品」和「流体」在 SB 里是<b>两条互不相干的能力</b>（{@code ModItems.lambda$registerCapabilities$81}
 * 物品、{@code lambda$registerCapabilities$82} 流体），<b>装储罐升级不会让物品那路消失</b>；</p>
 * <p>真正能把「物品 0 格 + 流体 N 罐」同时造出来的只有两种形态，本类必须对两种都如实处理：</p>
 * <ol>
 *     <li><b>物品那路返回 0 格</b>：SB 的物品 provider 是
 *     {@code getContentsUuid().isEmpty() ? EmptyItemHandler.INSTANCE : getInventoryForInputOutput()}
 *     —— 背包的 <b>{@code storage UUID} 一旦不在物品上，物品面就恒为 0 格；而流体那路
 *     （{@code getFluidHandler()}）在 wrapper 里是 <b>懒建 + 缓存</b>
 *     （{@code fluidHandlerInitialized}），一旦建过就一直可读。⇒ <b>只显示流体的现象</b>。
 *     SB 自己会把 {@code storage UUID} 从「输掉去重的那一个」上摘掉
 *     （{@code UUIDDeduplicator.isFirstBackpackPreferred} 比的是
 *     {@code getInventoryHandler().getSlots()}，而 {@code BackpackWrapper.getInventoryHandler()}
 *     建的是 {@code slots - rows * getColumnsTaken()}，<b>储罐升级 {@code getInventoryColumnsTaken()==2}</b>
 *     ⇒ 装了储罐升级的同款背包在重复 UUID 去重里必定输掉）；</li>
 *     <li><b>物品那路抛异常</b>（第三方容器初始化中 / 存档数据未就绪）：旧实现的
 *     {@code resolveInner()} 在解析<b>之前</b>就把 {@code innerFor = proxyStack} 写死了，异常一抛，
 *     「物品 null + 流体根本没解析」被永久粘住 —— 换回正常物品也不会恢复。</li>
 * </ol>
 * <p>本轮的修法只有两条，都不改别人的模组：①两路能力<b>各查各的、各自 try/catch</b>，一路失败绝不
 * 连坐另一路；②<b>退化态（抛异常 / 只读到一路）按 {@link #RESOLVE_RETRY_TICKS} tick 自动重试</b>，
 * 一旦容器恢复可读，代理器<b>不换方块、不重放对象</b>就自动恢复；③状态（{@link InnerState}）与
 * 两路格数随数据槽下发，界面 / Jade 一律报<b>事实</b>，不再出现「看着有流体所以应该有物品」的谎报。</p>
 *
     * <h2>「不静默丢东西」是硬约束（本轮修复的根因）</h2>
 * <p>代理槽里的对象可能是一整包东西（背包 / 磁盘），任何一条路径都不允许把它<b>悄悄清掉</b>。
 * 因此本类的写路径只有四种，且每一种都遵守同一条规则：</p>
 * <ol>
 *     <li>{@link #setProxyStack} —— <b>替换</b>语义，绝不写 {@code EMPTY} 再写新值；旧对象由调用方
 *     （原版菜单：先 {@code getItem()} 交给光标/背包）持有；</li>
 *     <li>{@link #takeProxyStackForDrop} —— <b>先取后清</b>，返回值由方块弹到世界；</li>
 *     <li>{@link #ejectStack} —— 放不下的 / 被替换的 / 读档时冒出来的，一律<b>弹出到世界</b>
 *     （没有服务端世界时入队并落盘，见 {@link #TAG_PENDING_EJECT}）；</li>
 *     <li>{@link #loadAdditional} —— 读档若会覆盖内存里已有的对象，<b>先</b>把它弹出。</li>
 * </ol>
 * <p><b>绝不再出现</b>：{@code clearContent()}、无条件的 {@code setProxyStack(ItemStack.EMPTY)}、
 * 「先清空再写新的」（写入失败时旧对象就没了）。</p>
 */
public class InventoryProxyBlockEntity extends BlockEntity {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** 代理对象槽在 NBT 里的键（只存这一个 stack；内容由物品自己负责落盘）。 */
    private static final String TAG_PROXY = "Proxy";
    /** 「已离开代理槽、但当时没有服务端世界可以弹出」的那批 stack 的 NBT 键（不许丢东西的兜底）。 */
    private static final String TAG_PENDING_EJECT = "PendingEject";
    /** 六面配置的复合标签键。 */
    private static final String TAG_FACES = "Faces";
    /**
     * 界面数据槽个数，下标含义：
     * <ul>
     *     <li>0..5 = 六面角色序号（{@link ProxyFaceRole#ordinal()}）；</li>
     *     <li>6 = 是否有代理对象；</li>
     *     <li>7 = 内层库存是否就绪（{@code 物品 or 流体 至少一路非空}，兼容旧界面口径）；</li>
     *     <li>8 = {@link InnerState} 的 {@code ordinal()}（第 108 轮新增：两路各自的状态）；</li>
     *     <li>9 = 物品面格数（0..255，超出按 255 报）；</li>
     *     <li>10 = 流体面罐数（0..255，超出按 255 报）。</li>
     * </ul>
     * <p>为什么状态与两个「格数」要下发：用户第 108 条投诉的现象是「只显示流体、物品不显示」，
     * 界面必须能<b>直说</b>「物品 0 格 / 流体 1 罐」，而不是把「有内容物」当成一句笼统的“就绪”。
     * 这些值全部由服务端算（客户端不查能力），见 {@code menu/InventoryProxyMenu}。</p>
     */
    public static final int DATA_SLOTS = 11;
    /** 数据槽：{@link InnerState} 序号。 */
    public static final int DATA_INNER_STATE = 8;
    /** 数据槽：物品面格数。 */
    public static final int DATA_ITEM_SLOTS = 9;
    /** 数据槽：流体面罐数。 */
    public static final int DATA_FLUID_TANKS = 10;
    /**
     * 退化态（解析抛异常 / 只读到一路）的自动重试间隔（tick）。
     *
     * <p>为什么需要重试而不是「解析一次就定终身」：内层 handler 是别人的模组对象，可能因为
     * 「存档数据还没就绪 / 升级刚装上 / 存储 UUID 被去重摘掉后又恢复」而在不同时刻给出不同答案
     * （第 108 轮根因，见类注释）。40 tick（2 秒）的成本只是 1~2 次
     * {@code ItemStack#getCapability}，而收益是「玩家把背包放回去 / 重新打开一次之后，
     * 代理器自己就好了」——不需要拆掉方块或换代理对象。</p>
     */
    private static final int RESOLVE_RETRY_TICKS = 40;
    /** 数据槽里的格数 / 罐数上限（{@code ContainerData} 是 int，但没必要下发大数）。 */
    private static final int DATA_COUNT_CAP = 255;

    private ItemStack proxyStack = ItemStack.EMPTY;
    /**
     * 已经被「换下来」但还没能交给世界的 stack。正常游戏路径恒为空：只有 {@code level} 还没设上
     * （读档过程中）时才会进这里，随后 {@link #setLevel} / {@link #onLoad} 立刻冲到世界上，
     * 并且随 BE 落盘 —— 所以即使这一步失败也不会变成「内存里没人引用的一堆物品」。
     */
    private final List<ItemStack> pendingEject = new ArrayList<>();
    /** 下标 = {@link Direction#ordinal()}（DOWN/UP/NORTH/SOUTH/WEST/EAST）。 */
    private final ProxyFaceRole[] faces = new ProxyFaceRole[6];
    /** 内层缓存的身份判据：与 {@link #proxyStack} 不是同一个实例时重解析。 */
    @Nullable
    private ItemStack innerFor;
    @Nullable
    private IItemHandler innerItem;
    @Nullable
    private IFluidHandler innerFluid;
    /** 上一次解析出来的物品面格数 / 流体面罐数（{@code null} 的 handler 记 0）。 */
    private int innerItemSlots;
    private int innerFluidTanks;
    /** 上一次解析里有任何一路抛过异常（⇒ 需要尽快重试，而不是把 null 粘住）。 */
    private boolean innerResolveFailed;
    /** 下一次允许重试的 gameTime（只在退化态下生效，见 {@link #needsReprobe(long)}）。 */
    private long innerRetryAt;
    /** 退化态的日志只打一次（避免每 2 秒刷屏，用户最反感的一类日志）。 */
    private boolean innerWarned;

    private final InventoryProxyHandlers.ObjectView objectView;
    private final InventoryProxyHandlers.ContentsItemView contentsItemView;
    private final InventoryProxyHandlers.ContentsFluidView contentsFluidView;
    /** 备内容物面（只进不出）：读恒空 ⇒ RS 不把它算进网络总量；写真实落到底层库存。 */
    private final InventoryProxyHandlers.ContentsInputItemView contentsInputItemView;
    private final InventoryProxyHandlers.ContentsInputFluidView contentsInputFluidView;

    public InventoryProxyBlockEntity(final BlockPos pos, final BlockState state) {
        super(RS_Create_Compat.INVENTORY_PROXY_BLOCK_ENTITY.get(), pos, state);
        final Map<Direction, ProxyFaceRole> defaults = ProxyFaceRole.defaults();
        for (final Direction direction : Direction.values()) {
            faces[direction.ordinal()] = defaults.get(direction);
        }
        this.objectView = new InventoryProxyHandlers.ObjectView(this);
        this.contentsItemView = new InventoryProxyHandlers.ContentsItemView(this);
        this.contentsFluidView = new InventoryProxyHandlers.ContentsFluidView(this);
        this.contentsInputItemView = new InventoryProxyHandlers.ContentsInputItemView(this);
        this.contentsInputFluidView = new InventoryProxyHandlers.ContentsInputFluidView(this);
    }

    // ==================== 能力注册（MOD 总线） ====================

    /**
     * 只注册<b>方块能力</b>：按面返回不同的 handler；<b>不</b>注册任何网络节点容器能力。
     * <p>{@code direction == null}（无面查询）按内容物面处理（设计文档 §4.2：unsided 必须有确定语义，
     * 且大量调用方会先查 {@code null} 面）。</p>
     */
    public static void registerCapabilities(final RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(
            Capabilities.ItemHandler.BLOCK,
            RS_Create_Compat.INVENTORY_PROXY_BLOCK_ENTITY.get(),
            (blockEntity, direction) -> blockEntity.itemHandler(direction)
        );
        event.registerBlockEntity(
            Capabilities.FluidHandler.BLOCK,
            RS_Create_Compat.INVENTORY_PROXY_BLOCK_ENTITY.get(),
            (blockEntity, direction) -> blockEntity.fluidHandler(direction)
        );
    }

    // ==================== 面 → 能力 ====================

    /** 某个查询面实际扮演的角色；{@code side == null}（无面查询）恒按主内容物面处理。 */
    public ProxyFaceRole roleFor(@Nullable final Direction side) {
        return side == null ? ProxyFaceRole.CONTENTS : faces[side.ordinal()];
    }

    @Nullable
    public IItemHandler itemHandler(@Nullable final Direction side) {
        return switch (roleFor(side)) {
            case PROXY_OBJECT -> objectView;
            case CONTENTS -> contentsItemView;
            case CONTENTS_INPUT -> contentsInputItemView;
            case DISABLED -> null;
        };
    }

    @Nullable
    public IFluidHandler fluidHandler(@Nullable final Direction side) {
        // 对象面只表达「容器物品本身」，不暴露流体；两个内容物面各有一套视图：
        // 主面完整读写（RS 数得到），备面只 fill 不 drain / 读恒空（RS 数不到 ⇒ 不双计）。
        return switch (roleFor(side)) {
            case CONTENTS -> contentsFluidView;
            case CONTENTS_INPUT -> contentsInputFluidView;
            default -> null;
        };
    }

    /** 对象面视图（GUI 的槽位与背面/对象面共用同一个实例，见设计文档 §5.2.1）。 */
    public IItemHandler getObjectView() {
        return objectView;
    }

    public ProxyFaceRole getFace(final Direction direction) {
        return faces[direction.ordinal()];
    }

    /**
     * 设置某一面的角色。
     *
     * <p>角色变化<b>必须</b> {@link #invalidateCapabilities()}：那一面可能从 {@code null} 变成有 / 反之，
     * 也可能在「主（完整读写）」与「备（只进不出）」之间切换 —— 外部缓存会拿着旧 handler
     * （RS 的 {@code BlockCapabilityCache} 缓存的就是 handler 实例），不通知的话它还会按「主」去数
     * 已经变成备面的库存，表现为「切了面、终端数量还是两倍」。</p>
     *
     * <p>写完立即 {@link #normalizeFaces()}：主内容物面、代理对象面各自只允许有一个。</p>
     */
    public void setFace(final Direction direction, final ProxyFaceRole role) {
        if (faces[direction.ordinal()] == role) {
            return;
        }
        faces[direction.ordinal()] = role;
        setChanged();
        invalidateFaceCapabilities();
        // 归一会改动「别的面」（把多余的主内容物面降为备面、把唯一的内容物面升为主面，
        // 或把多余的对象面降为禁用）。上面的 setChanged / invalidateCapabilities 已经覆盖
        // 这一整块方块实体（整块失效，不分面），所以这里不需要再逐个面通知。
        normalizeFaces();
    }

    /**
     * 扳手 / 界面按钮：该面循环到下一档，返回<b>本次操作后真实生效的角色</b>。
     *
     * <h2>为什么返回结果要重读 {@code faces}</h2>
     * <p>{@link #normalizeFaces()} 可能在换面后立刻改写角色（把多余的主面降为备面、
     * 把唯一的内容物面升为主面、把多余的对象面降为禁用）。actionbar / 按钮文案必须报归一之后
     * 的真实角色，不能报一个没生效的值。</p>
     *
     * <h2>为什么「主面」这一档直接跳到禁用（不经过备面）</h2>
     * <p>归一规则是「{@link Direction#ordinal()} 最小的内容物面<b>永远</b>是主面」。主面降成备面后，
     * 六面里就一个主面都没有了，归一立刻会把它升回主面 ⇒ 按下扳手「点了没反应」。
     * 所以主面的循环是 主 → 禁用 → 代理对象 → 内容物（主）。<b>备面</b>通过把<b>另一个</b>面设成
     * 内容物来获得：那个面的 ordinal 更大，归一就会把它留作备面（actionbar 会明确说
     * 「设为内容物（备·仅进）」）。这样两档都能到达，且没有任何一次点击是空转的。</p>
     *
     * <h2>为什么「禁用 → 代理对象」有时直接跳到内容物（主）</h2>
     * <p>对象面同样唯一（ordinal 最小的那个保留）。若本面之前已经有一个 ordinal 更小的对象面，
     * 本面切到 {@code PROXY_OBJECT} 会被归一立刻降回禁用 ⇒ 又是一次「点了没反应」。
     * 所以这一档直接越过，切到 {@code CONTENTS}（那个一定会生效：要么成为新的主面，
     * 要么降为备面）。要移动对象面，先把现役对象面切走即可。</p>
     */
    public ProxyFaceRole cycleFace(final Direction direction) {
        ProxyFaceRole next = faces[direction.ordinal()].next();
        if (faces[direction.ordinal()] == ProxyFaceRole.CONTENTS && next == ProxyFaceRole.CONTENTS_INPUT) {
            // 主面不能降成备面：当归一会立刻把它升回来（见方法注释）。跳过这一档。
            next = ProxyFaceRole.DISABLED;
        }
        if (next == ProxyFaceRole.PROXY_OBJECT && fixedObjectFaceBefore(direction)) {
            // 对象面唯一：这一档会被归一立刻降回禁用（点了没反应）。跳过这一档。
            next = ProxyFaceRole.CONTENTS;
        }
        setFace(direction, next);
        return faces[direction.ordinal()];
    }

    /** 是否已经有一个 ordinal 更小的对象面（归一会保留它、把本面降为禁用）。 */
    private boolean fixedObjectFaceBefore(final Direction direction) {
        for (final Direction other : Direction.values()) {
            if (other.ordinal() < direction.ordinal()
                && faces[other.ordinal()] == ProxyFaceRole.PROXY_OBJECT) {
                return true;
            }
        }
        return false;
    }

    /**
     * 归一入口：让「主内容物面」与「代理对象面」各自唯一。
     *
     * <p>两类角色都必须唯一，原因相同：每个内容物面 / 对象面返回的都是同一份底层库存 / 同一件代理对象，
     * 而 RS 2.0.9 把每个 {@code ExternalStorage} 的库存各自求和、<b>不按源方块去重</b> ⇒
     * 同一个面角色出现两次，玩家在两个面各贴一个 RS 外部存储时终端数量就翻倍。</p>
     * <ul>
     *     <li>内容物面：{@link #normalizeContentsFaces()}（主面唯一 + 退化态升回主面）；</li>
     *     <li>代理对象面：{@link #normalizeObjectFaces()}（对象面唯一，多余降为禁用）。</li>
     * </ul>
     * <p>调用点：每次 {@link #setFace} 之后（扳手 / 界面按钮）、{@link #loadAdditional} 读完六面配置
     * 之后（老存档里可能有两个 {@code "contents"} 或两个 {@code "proxy_object"}）。</p>
     *
     * @return 是否改动过某一面（读档路径据此标脏 / 作废能力缓存）
     */
    private boolean normalizeFaces() {
        boolean changed = normalizeContentsFaces();
        changed |= normalizeObjectFaces();
        return changed;
    }

    /**
     * 归一：<b>内容物面里 ordinal 最小的那个是主面</b>（{@link ProxyFaceRole#CONTENTS}），
     * 其余内容物面一律是备面（{@link ProxyFaceRole#CONTENTS_INPUT}）。
     *
     * <h2>为什么必须归一（第 96 轮用户第 3 条）</h2>
     * <p>RS 2.0.9 的每个外部存储都自建一个 {@code ExternalStorage}，网络把各 storage 的库存求和，
     * <b>不按源方块 / handler 实例去重</b>。两个「主内容物面」各贴一个 RS 外部存储 ⇒
     * 同一份底层库存被数两遍 ⇒ 终端数量翻倍。所以主面必须唯一。</p>
     *
     * <h2>规则（Lead 本轮拍的口径）</h2>
     * <ol>
     *     <li>有主内容物面（{@link ProxyFaceRole#CONTENTS}）时：{@link Direction#values()} 顺序即 ordinal
     *     顺序（DOWN/UP/NORTH/SOUTH/WEST/EAST），第一个命中的就是主面，其余 {@code CONTENTS} 降为
     *     {@link ProxyFaceRole#CONTENTS_INPUT}；</li>
     *     <li>一个主内容物面都没有、但存在备面时：把 ordinal 最小的内容物面<b>升</b>为主面 ——
     *     否则这份底层库存没有任何面能被 RS 读到，玩家会以为东西丢了（备面只能进、不能读）。
     *     这也让「主面循环」永远不会落进一个没有主面的死状态。</li>
     * </ol>
     *
     * <p>不丢配置：被降档的面仍然是「内容物面」，只是变成只进不出；升档只发生在
     * 「一个主面都没有」这个退化状态。</p>
     *
     * @return 是否改动过某一面
     */
    private boolean normalizeContentsFaces() {
        boolean changed = false;
        Direction primary = primaryContentsFace();
        if (primary == null) {
            primary = firstContentsFace();
            if (primary == null) {
                return false;
            }
            // 只剩备面（退化状态）：把 ordinal 最小的那个升回主面。
            faces[primary.ordinal()] = ProxyFaceRole.CONTENTS;
            changed = true;
        }
        for (final Direction direction : Direction.values()) {
            if (direction != primary && faces[direction.ordinal()] == ProxyFaceRole.CONTENTS) {
                faces[direction.ordinal()] = ProxyFaceRole.CONTENTS_INPUT;
                changed = true;
            }
        }
        return changed;
    }

    /**
     * 归一：<b>代理对象面也只能有一个</b>（第 96 轮独立复核补充）。
     *
     * <h2>为什么</h2>
     * <p>每个 {@link ProxyFaceRole#PROXY_OBJECT} 面返回的都是<b>同一个</b>
     * {@link InventoryProxyHandlers.ObjectView}（1 槽，内容就是代理槽里那件容器物品，
     * 见 {@link #getObjectView()}）。与内容物面完全同源：两个对象面各贴一个 RS 外部存储，
     * 同一件代理对象就会被两份 {@code ExternalStorage} 各读一次 ⇒ 终端上连它都翻倍。</p>
     *
     * <h2>规则</h2>
     * <p>{@link Direction#ordinal()} 最小的对象面保留，其余对象面降为 {@link ProxyFaceRole#DISABLED}。
     * 降成禁用（而不是造一种「对象备面」）是有意的：不引入第三种对象面视图，就不会给
     * 「从机器侧取出 / 写入代理对象」造出第二条路径 —— 代理槽只有一个，任何一侧能取都会和
     * 界面 / 拆除路径抢同一件东西。</p>
     * <p>代理对象本身不受影响：它存在方块实体的代理槽里，与面配置无关。</p>
     *
     * @return 是否把某个多余的对象面降成了禁用
     */
    private boolean normalizeObjectFaces() {
        boolean changed = false;
        boolean kept = false;
        for (final Direction direction : Direction.values()) {
            if (faces[direction.ordinal()] != ProxyFaceRole.PROXY_OBJECT) {
                continue;
            }
            if (!kept) {
                kept = true;                 // 第一个（ordinal 最小）对象面留下
                continue;
            }
            faces[direction.ordinal()] = ProxyFaceRole.DISABLED;
            changed = true;
        }
        return changed;
    }

    /** 六面里 ordinal 最小的主内容物面；没有任何 {@link ProxyFaceRole#CONTENTS} 时返回 {@code null}。 */
    @Nullable
    private Direction primaryContentsFace() {
        for (final Direction direction : Direction.values()) {
            if (faces[direction.ordinal()] == ProxyFaceRole.CONTENTS) {
                return direction;
            }
        }
        return null;
    }

    /** 六面里 ordinal 最小的内容物面（主面或备面都算）；一个内容物面都没有时返回 {@code null}。 */
    @Nullable
    private Direction firstContentsFace() {
        for (final Direction direction : Direction.values()) {
            if (faces[direction.ordinal()].isContents()) {
                return direction;
            }
        }
        return null;
    }

    private void invalidateFaceCapabilities() {
        if (level != null) {
            invalidateCapabilities();
        }
    }

    // ==================== 代理对象槽 ====================

    public ItemStack proxyStack() {
        return proxyStack;
    }

    /**
     * 唯一写入口：存 copy（防入参别名，R19），并立即让内层缓存失效（R1）。
     * <p>外层视图实例<b>不</b>替换，因此外部缓存的 handler 仍然有效。</p>
     *
     * <p><b>这是「替换」而不是「先清空再写」</b>：方法里没有任何 {@code proxyStack = ItemStack.EMPTY}
     * 的中间态，因此只要赋值语句执行，槽里就一定有新对象 —— 不存在「清空成功、写入失败」的半截状态。
     * 旧对象由调用方负责：菜单路径由原版 {@code Slot} 先取给光标，拆方块路径走
     * {@link #takeProxyStackForDrop()}，其余路径走 {@link #ejectStack(ItemStack)}。</p>
     */
    public void setProxyStack(final ItemStack stack) {
        this.proxyStack = stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1);
        clearInnerCache();
        setChanged();
    }

    /** 方块被拆掉时把代理对象取出来（取走后槽为空，避免重复掉落）。 */
    public ItemStack takeProxyStackForDrop() {
        final ItemStack out = proxyStack;
        this.proxyStack = ItemStack.EMPTY;
        clearInnerCache();
        return out;
    }

    /**
     * 丢掉内层解析缓存（身份 / 两路 handler / 两个格数 / 退化标志）。
     *
     * <p>所有「代理对象换了 / 读档 / 进世界」的路径都必须走这里：第 108 轮起缓存里还多了
     * 「格数 / 罐数 / 是否失败 / 下次重试时刻」，漏清一个字段就会出现「换了背包还按旧格数报」或
     * 「明明恢复正常却因为旧的 retryAt 一直不重试」这类幽灵状态。</p>
     */
    private void clearInnerCache() {
        this.innerFor = null;
        this.innerItem = null;
        this.innerFluid = null;
        this.innerItemSlots = 0;
        this.innerFluidTanks = 0;
        this.innerResolveFailed = false;
        this.innerRetryAt = Long.MIN_VALUE;
        this.innerWarned = false;
    }

    /**
     * 把一个 stack <b>交给世界</b>：能弹就立刻弹，弹不了先入队（并随 BE 落盘）—— <b>绝不静默丢弃</b>。
     *
     * <p>谁会走到这里：</p>
     * <ul>
     *     <li>菜单写入超过单槽容量（正常原版路径不会，取 {@code getSlotLimit()} 的上限）；</li>
     *     <li>读档时发现内存里已经有一个代理对象（正常读档时恒为空，纯防御）；</li>
     *     <li>以后新增任何「换掉代理对象」的路径。</li>
     * </ul>
     * <p>为什么需要队列：{@code loadAdditional} 可能发生在 {@code level} 设上之前，那时
     * {@link Block#popResource} 没有世界可用；丢掉就等于把玩家的背包变成内存垃圾。</p>
     */
    public void ejectStack(final ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        if (level != null && !level.isClientSide()) {
            Block.popResource(level, worldPosition, stack.copy());
            return;
        }
        pendingEject.add(stack.copy());
    }

    /** 把入队的 stack 真正弹出去（只在服务端世界下执行，客户端队列不物化）。 */
    private void flushPendingEject() {
        if (level == null || level.isClientSide() || pendingEject.isEmpty()) {
            return;
        }
        for (final ItemStack stack : pendingEject) {
            if (!stack.isEmpty()) {
                Block.popResource(level, worldPosition, stack.copy());
            }
        }
        pendingEject.clear();
    }

    /**
     * 方块被拆掉前的兜底：把 {@link #TAG_PENDING_EJECT} 队列冲出去。
     * <p>正常路径上这个队列在 {@link #setLevel} / {@link #onLoad} 就已经清空了（理论上拆方块时恒为空），
     * 但「任何路径都不许丢东西」的兜底链不该在这里缺一环：拆方块会让 BE 很快被
     * {@code setRemoved} / 丢弃，若此时队列里还有东西，之后再也没人负责弹出。
     * 拆方块走的是服务端 {@code onRemove}，所以这里一定弹得出去。</p>
     */
    public void flushPendingEjectBeforeRemoval() {
        flushPendingEject();
    }

    @Override
    public void setLevel(final Level level) {
        super.setLevel(level);
        flushPendingEject();
    }

    /**
     * 这个物品能不能当代理对象？
     * <p>判据（设计文档 §2.5 的唯一通用判据 + 磁盘适配器 §3.7.2 + 第 95 轮收纳袋）：</p>
     * <ol>
     *     <li>有 {@code ItemHandler.ITEM}（潜影盒 / 背包…）；</li>
     *     <li>或只有 {@code FluidHandler.ITEM}（流体舱类物品，内容物面走流体视图）；</li>
     *     <li>或是 {@link StorageContainerItem}（RS 原生盘 / 本模组通用盘）。即使尚未格式化，
     *     也先收下 —— 否则玩家永远没机会看到「未格式化，先拿在背包里 tick 一次」的提示；</li>
     *     <li>或是收纳袋（{@code minecraft:bundle}）：它的内容物在 {@code minecraft:bundle_contents}
     *     <b>数据组件</b>里、不在 {@code ItemHandler} 里，所以上面三条都判不出来。
     *     这类物品由 {@link InventoryProxyHandlers.BundleContentsView} 以组件式 handler 暴露
     *     （读 / 写 / 取出守恒），见 {@link #resolveInner()}。</li>
     * </ol>
     */
    public static boolean isProxyable(final ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (stack.getCapability(Capabilities.ItemHandler.ITEM) != null) {
            return true;
        }
        if (stack.getCapability(Capabilities.FluidHandler.ITEM) != null) {
            return true;
        }
        return stack.getItem() instanceof StorageContainerItem || hasBundleContents(stack);
    }

    /**
     * 组件式容器（目前是原版收纳袋）判据：物品是 {@code BundleItem}，或身上带着
     * {@code minecraft:bundle_contents} 组件（别的模组的收纳袋变体走后者）。
     * <p>不能只判组件：1.21.1 的空收纳袋不一定带这个组件（默认值没有注册在组件类型上），
     * 只判组件会让「空收纳袋放不进代理槽」。</p>
     */
    private static boolean hasBundleContents(final ItemStack stack) {
        return stack.getItem() instanceof BundleItem
            || stack.has(DataComponents.BUNDLE_CONTENTS);
    }

    /**
     * 内容物面「实际认到了什么」的四态（第 108 轮新增，服务端算出、随数据槽下发）。
     *
     * <p>为什么需要它：用户看到的「只显示流体」在旧实现里是一条<b>不可见的歧义</b> ——
     * 界面只知道「内容物就绪/未就绪」，Jade 只知道「Jade 自己的物品组是空的」。四态把
     * 「只有一路」变成<b>可以显示、可以断言</b>的事实，界面与 Jade 才有东西可报。</p>
     */
    public enum InnerState {
        /** 代理槽为空，或该物品没有任何物品/流体库存。 */
        NONE,
        /** 只有物品库存（例如潜影盒、收纳袋、RS 磁盘）。 */
        ITEM,
        /** <b>只有流体库存</b> —— 用户第 108 条「物品不显示」的症状态，必须如实暴露而不是静默。 */
        FLUID,
        /** 两路都有（装了储罐升级的精妙背包正常态）。 */
        BOTH;

        /** 数据槽里传的整数（{@code ordinal()}，与存档无关，只在同版本在线同步）。 */
        public int id() {
            return ordinal();
        }

        /** 越界一律回落 {@link #NONE}：数据槽是「服务端写、同版本客户端读」的在线值，不落盘。 */
        public static InnerState byId(final int id) {
            final InnerState[] values = values();
            return id >= 0 && id < values.length ? values[id] : NONE;
        }

        /** 至少有一路可读（物品或流体）。 */
        public boolean hasAny() {
            return this != NONE;
        }
    }

    /**
     * 按 {@code ItemStack} 身份解析内层物品库存。
     * <p><b>不要按 handler 身份缓存</b>：{@code ItemCapability#getCapability} 没有缓存，每次都可能新建实例
     * （设计文档 §2.1 / R10）。{@code ItemStackHandler#getStackInSlot} 返回实际实例，所以身份比较可行。</p>
     * <p>第 108 轮起：身份缓存<b>不等于</b>「一次定终身」——退化态会在
     * {@link #RESOLVE_RETRY_TICKS} tick 后自动重试（见 {@link #needsReprobe(long)}）。</p>
     */
    @Nullable
    public IItemHandler innerItem() {
        resolveInner();
        return innerItem;
    }

    @Nullable
    public IFluidHandler innerFluid() {
        resolveInner();
        return innerFluid;
    }

    /** 内容物面的四态（服务端权威；客户端一律读数据槽，不调这个方法）。 */
    public InnerState innerState() {
        resolveInner();
        final boolean item = innerItemSlots > 0;
        final boolean fluid = innerFluidTanks > 0;
        if (item && fluid) {
            return InnerState.BOTH;
        }
        if (item) {
            return InnerState.ITEM;
        }
        return fluid ? InnerState.FLUID : InnerState.NONE;
    }

    /** 物品面格数（{@code 0} = 该物品此刻没有可读的物品库存）。 */
    public int innerItemSlots() {
        resolveInner();
        return innerItemSlots;
    }

    /** 流体面罐数（{@code 0} = 该物品此刻没有可读的流体库存）。 */
    public int innerFluidTanks() {
        resolveInner();
        return innerFluidTanks;
    }

    /** 上一次解析是否抛过异常（只用于诊断 / 界面提示，不参与任何计数）。 */
    public boolean innerResolveFailed() {
        resolveInner();
        return innerResolveFailed;
    }

    /**
     * 这一次是不是「退化态」（抛过异常，或只读到一路）——只有退化态才值得按 tick 重试。
     *
     * @param now 当前 {@code gameTime}（没有世界时传 {@link Long#MIN_VALUE} 也表示可以重试）
     */
    private boolean needsReprobe(final long now) {
        // 两路都通的容器已经是最优解：不再追问（这是热路径，RS / Jade 每 tick 会问很多次）。
        if (!innerResolveFailed && innerItemSlots > 0 && innerFluidTanks > 0) {
            return false;
        }
        return now >= innerRetryAt || now == Long.MIN_VALUE;
    }

    /**
     * 解析内层两路能力。
     *
     * <h2>为什么这里必须「两路各查各的 + 各自 try/catch + 失败不写身份」（第 108 轮根因修复）</h2>
     * <p>旧实现是这三行，三个问题叠在一起：</p>
     * <pre>
     * innerFor = proxyStack;                                   // ① 先写身份
     * innerItem = proxyStack.getCapability(ITEM);              // ② 抛了 ⇒ 物品 null
     * innerFluid = proxyStack.getCapability(FLUID);            // ③ 根本执行不到 ⇒ 流体也丢了
     * </pre>
     * <ol>
     *     <li><b>一路抛异常连坐另一路</b>：第三方容器的 provider 在「存档未就绪 / 正在迁移」时抛异常，
     *     流体那路就永远没机会被解析；</li>
     *     <li><b>失败被永久粘住</b>：①在②之前，异常一抛身份就记死了 ⇒ 之后每次调用都在第一行
     *     {@code return}，代理器此后恒报「没有内容物」，换代理对象才能恢复；</li>
     *     <li><b>0 格也算「解析成功」</b>：SB 的背包在 {@code storage UUID} 不在物品上时返回
     *     {@code EmptyItemHandler}（0 格），旧实现把这个合法但退化的结果和「真没有库存」混为一谈，
     *     并且永不重试。</li>
     * </ol>
     * <p>现在：两路分别包 try/catch（异常只记一个标志 + 一次日志），两路都问完之后才写
     * {@code innerFor}；退化态由 {@link #needsReprobe(long)} 按 tick 重试，所以「容器稍后恢复可读」
     * 时代理器会自己好起来。</p>
     */
    private void resolveInner() {
        final Level level = this.level;
        final long now = level == null ? Long.MIN_VALUE : level.getGameTime();
        if (innerFor == proxyStack && !needsReprobe(now)) {
            return;
        }
        final ItemStack stack = proxyStack;
        IItemHandler item = null;
        IFluidHandler fluid = null;
        boolean failed = false;
        if (!stack.isEmpty()) {
            // ① 物品那路：独立 try/catch —— 它抛了也绝不影响下面流体那路。
            try {
                item = stack.getCapability(Capabilities.ItemHandler.ITEM);
            } catch (final Throwable failure) {
                failed = true;
                warnOnce("物品", failure);
            }
            // 组件式容器（收纳袋）：没有 ItemHandler 能力，内容物在 minecraft:bundle_contents 组件里。
            // 这一步是「读得到 / 取得出 / 放得进」的唯一挂点 —— ContentsItemView 之后每次都问 innerItem()。
            if (item == null && !failed && hasBundleContents(stack)) {
                item = new InventoryProxyHandlers.BundleContentsView(this);
            }
            // ② 流体那路：同样独立。SB 的储罐升级走的就是这一路，与物品那路没有任何互斥关系。
            try {
                fluid = stack.getCapability(Capabilities.FluidHandler.ITEM);
            } catch (final Throwable failure) {
                failed = true;
                warnOnce("流体", failure);
            }
        }
        // ③ 两路都问完才写身份：任何一路抛异常 ⇒ 下一 tick 立刻重试，而不是把半截结果粘住。
        this.innerFor = stack;
        this.innerItem = item;
        this.innerFluid = fluid;
        // ④ 连「问格数」这一步也要兜住：第三方 handler 的 getSlots() / getTanks() 同样可能是
        //   「数据还没就绪就直接抛」的写法，而这两个方法会被 RS / Jade 每 tick 调很多次 ——
        //   让异常从这里漏出去，等于把一次第三方初始化失败升级成整条外部存储读数崩掉。
        this.innerItemSlots = slotsOf(item);
        this.innerFluidTanks = tanksOf(fluid);
        this.innerResolveFailed = failed;
        this.innerRetryAt = now == Long.MIN_VALUE ? Long.MIN_VALUE : now + RESOLVE_RETRY_TICKS;
        // 退化态（只读到一路）打一次日志：现场排查「只显示流体」时这是唯一能证明「物品面确实 0 格」的证据。
        if (!failed && !innerWarned && stack.getCount() > 0
            && (innerItemSlots == 0) != (innerFluidTanks == 0)) {
            innerWarned = true;
            LOGGER.info("[rscc-proxy] 容器代理器 @{}：代理对象 {} 只读到一路内容物"
                    + "（物品 {} 格 / 流体 {} 罐）。这是对方模组此刻的真实状态，"
                    + "不是代理器漏读：物品面 0 格通常意味着该容器的物品库存没有初始化"
                    + "（例如精妙背包的 storage UUID 不在物品上），代理器会在 {} tick 后自动重查。",
                worldPosition, stack.getItem(), innerItemSlots, innerFluidTanks, RESOLVE_RETRY_TICKS);
        }
    }


    /**
     * 问一个 {@code IItemHandler} 的格数，<b>异常一律按 0 处理</b>。
     *
     * <p>为什么不在外面判断 {@code handler == null} 就完事：第三方 handler 完全可能在
     * 「存档 / 存储数据还没就绪」时让 {@code getSlots()} 抛异常。代理器把格数暴露给
     * RS 的 {@code CapabilityCache#getItemAmountIterator} 与 Jade 的采集器，异常从这两处漏出去
     * 会变成「整个外部存储读不出来」，而正确语义只是「这一路此刻读不到、下一窗口重试」。</p>
     */
    private static int slotsOf(@Nullable final IItemHandler handler) {
        if (handler == null) {
            return 0;
        }
        try {
            return Math.max(0, handler.getSlots());
        } catch (final Throwable failure) {
            return 0;
        }
    }

    /** 同 {@link #slotsOf(IItemHandler)}，流体的罐数版本。 */
    private static int tanksOf(@Nullable final IFluidHandler handler) {
        if (handler == null) {
            return 0;
        }
        try {
            return Math.max(0, handler.getTanks());
        } catch (final Throwable failure) {
            return 0;
        }
    }

    /** 解析异常只打一次日志（每个方块实体一条），避免第三方容器每 tick 抛一次时刷屏。 */
    private void warnOnce(final String channel, final Throwable failure) {
        if (innerWarned) {
            return;
        }
        innerWarned = true;
        LOGGER.warn("[rscc-proxy] 容器代理器 @{}：解析代理对象的{}能力时抛异常，"
            + "本 tick 该路按「不可读」处理、另一路照常；{} tick 后自动重试。",
            worldPosition, channel, RESOLVE_RETRY_TICKS, failure);
    }

    // ==================== GUI 数据同步 ====================

    /**
     * 服务端权威数据槽（客户端菜单用 {@code SimpleContainerData} 承接）：
     * 0..5 = 六面角色序号，6 = 是否有代理对象，7 = 内层库存是否就绪，
     * 8 = {@link InnerState} 序号，9 = 物品面格数，10 = 流体面罐数。
     *
     * <p>第 108 轮新增后三格：用户的实际投诉是「只显示流体、物品不显示」，界面必须能直接说出
     * 「物品 0 格 / 流体 1 罐」，而不是用一句「内容物已就绪」把缺失掩盖过去。格数超过
     * {@link #DATA_COUNT_CAP} 时按上限报（界面只用来判断「有没有 / 大致多少」）。</p>
     */
    public ContainerData getContainerData() {
        return new ContainerData() {
            @Override
            public int get(final int index) {
                if (index >= 0 && index < 6) {
                    return faces[index].ordinal();
                }
                if (index == 6) {
                    return proxyStack.isEmpty() ? 0 : 1;
                }
                if (index == 7) {
                    return innerItem() != null || innerFluid() != null ? 1 : 0;
                }
                if (index == DATA_INNER_STATE) {
                    return innerState().id();
                }
                if (index == DATA_ITEM_SLOTS) {
                    return Math.min(DATA_COUNT_CAP, innerItemSlots());
                }
                if (index == DATA_FLUID_TANKS) {
                    return Math.min(DATA_COUNT_CAP, innerFluidTanks());
                }
                return 0;
            }

            @Override
            public void set(final int index, final int value) {
                // 服务端权威：客户端改不动配置；配置只能走 clickMenuButton / 扳手。
            }

            @Override
            public int getCount() {
                return DATA_SLOTS;
            }
        };
    }

    // ==================== 持久化 ====================

    @Override
    protected void saveAdditional(final CompoundTag tag, final HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (!proxyStack.isEmpty()) {
            tag.put(TAG_PROXY, proxyStack.save(registries));
        }
        // 还没弹出去的（只在 level 尚未设上时可能出现）必须跟着 BE 一起落盘，否则重进世界就丢了。
        if (!pendingEject.isEmpty()) {
            final ListTag pending = new ListTag();
            for (final ItemStack stack : pendingEject) {
                if (!stack.isEmpty()) {
                    pending.add(stack.save(registries));
                }
            }
            if (!pending.isEmpty()) {
                tag.put(TAG_PENDING_EJECT, pending);
            }
        }
        final CompoundTag facesTag = new CompoundTag();
        for (final Direction direction : Direction.values()) {
            facesTag.putString(ProxyFaceRole.nbtKey(direction), faces[direction.ordinal()].id());
        }
        tag.put(TAG_FACES, facesTag);
    }

    @Override
    protected void loadAdditional(final CompoundTag tag, final HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        // 先读「上一轮没弹出去的」队列，再读代理对象槽：缓存里可能还有会被这次读档覆盖掉的旧对象。
        final ListTag pendingTag = tag.getList(TAG_PENDING_EJECT, Tag.TAG_COMPOUND);
        final List<ItemStack> restoredPending = new ArrayList<>();
        for (int i = 0; i < pendingTag.size(); i++) {
            final ItemStack stack = ItemStack.parseOptional(registries, pendingTag.getCompound(i));
            if (!stack.isEmpty()) {
                restoredPending.add(stack);
            }
        }
        pendingEject.clear();
        pendingEject.addAll(restoredPending);

        final ItemStack previous = proxyStack;
        proxyStack = tag.contains(TAG_PROXY)
            ? ItemStack.parseOptional(registries, tag.getCompound(TAG_PROXY))
            : ItemStack.EMPTY;
        // 读档**不允许**静默覆盖内存里已经存在的代理对象：先把它弹出去再换。
        // 正常读档时 previous 恒为空（BE 是新建的），所以这一条只是「任何路径都不吞」的兜底。
        if (!previous.isEmpty() && !ItemStack.matches(previous, proxyStack)) {
            ejectStack(previous);
        }
        // 读档后一律重解析内层（旧 handler 绑定的是旧的 stack 实例）。
        clearInnerCache();
        final Map<Direction, ProxyFaceRole> defaults = ProxyFaceRole.defaults();
        final CompoundTag facesTag = tag.getCompound(TAG_FACES);
        for (final Direction direction : Direction.values()) {
            final String key = ProxyFaceRole.nbtKey(direction);
            faces[direction.ordinal()] = facesTag.contains(key)
                ? ProxyFaceRole.byId(facesTag.getString(key))
                : defaults.get(direction);
        }
        // 老存档里可能有两个「主内容物面」，也可能有两个「代理对象面」（加对应档位之前的版本允许）：
        // 立即归一成「一个主面 + 若干备面 + 一个对象面」。不能拖到第一次换面才归一 ——
        // 在那之前 RS 已经把同一份库存 / 同一件代理对象数两遍了（就是用户反馈的翻倍现象）。
        if (normalizeFaces()) {
            setChanged();                 // 内存配置已与磁盘上的 NBT 不同：下次保存落成归一后的值
            invalidateFaceCapabilities(); // 读档时通常 level 还没设上，该方法自带 null 保护
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        // 区块卸载 / 重载后旧的内层 handler 可能已经悬空，一律重解析。
        clearInnerCache();
        // 读档期间没法弹出去的，进世界后立刻弹（不允许留在内存里等下一次覆盖）。
        flushPendingEject();
    }
}
