package cretae.cookiewyq.rs_create_compat.support;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.content.equipment.clipboard.ClipboardContent;
import com.simibubi.create.content.equipment.clipboard.ClipboardOverrides.ClipboardType;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * <b>用机械动力的剪贴板批量安排输入 / 输出总线的配置</b>（用户第 7 条：「一个一个手动配置这一个
 * 输入输出总线太麻烦了……得想一个办法，使它更加便于快速大量的给他安排相同的配置」）。
 *
 * <h2>为什么走 Create 剪贴板这条既有链路（而不是自己发明一套「复制 / 粘贴」按钮）</h2>
 * <p>Create 的剪贴板本来就有一套成熟的<b>「右击复制配置 / 左击粘贴配置」</b>约定
 * （{@code ClipboardValueSettingsHandler}），它的物品提示里写的就是「复制并在其他地方应用方块的某些配置，
 * 比如过滤槽」。因此总线的过滤槽 / 类别勾选顺理成章应该走同一个手势 —— 玩家不需要学新东西，
 * 也不需要本模组再加一套界面控件。</p>
 *
 * <h2>为什么本类要自己写一份挂点（不能直接实现 Create 的 {@code ClipboardCloneable}）</h2>
 * <p>Create 自己那条链路要求目标方块实体是它的 {@code SmartBlockEntity}
 * （{@code ClipboardValueSettingsHandler#interact} 第一句就是 {@code instanceof SmartBlockEntity}），
 * 而 RS 的总线方块实体继承的是 RS 自己的 {@code AbstractCableLikeBlockEntity} ——
 * <b>结构上不可能</b>变成 SmartBlockEntity（那等于换掉 RS 的整个方块实体体系）。
 * 因此这里用<b>同一套手势、同一份剪贴板数据格式</b>（{@code copied_values} 里的 {@code copiedValues} 子标签），
 * 只是挂点自己写：既不改 RS、也不改 Create，两个模组各自的存储格式都不动。</p>
 *
 * <h2>手势（与 Create 的剪贴板约定一致，潜行是「范围档」）</h2>
 * <ul>
 *     <li><b>手持剪贴板右击总线</b> → 复制这条总线的全部玩家配置（RS 过滤槽 + 模糊模式 +
 *     本模组类别勾选 + 「全自动收回」+「强制普通总线」，见 {@link RsccBusConfig}）；</li>
 *     <li><b>手持剪贴板左击总线</b> → 把配置粘贴到<b>这一条</b>总线；</li>
 *     <li><b>潜行 + 左击总线</b> → 粘贴到<b>同一条线缆簇里的全部同种总线</b>（「快速大量」那一档）。
 *     范围边界见 {@link #pasteCluster}：同一线缆簇（扳手断开的接缝 / 被分隔框架冻结的接缝都算断）、
 *     同一种总线、最多 {@link #BULK_MAX_BUSES} 台，绝不跨簇扩散。</li>
 * </ul>
 *
 * <h2>服务端权威</h2>
 * <p>两端都只做一件事：<b>取消事件</b>（否则左击会顺手把方块挖了、右击会打开总线界面）。
 * 读剪贴板、校验、写方块实体、报文案<b>全部只在服务端</b>执行（{@code instanceof ServerLevel} 之后）。
 * 因此客户端无法凭自己那份数据改写世界状态，也不存在「客户端改了一半、服务端没改」的分裂 ——
 * 连一个自定义网络包都不需要（左击 / 右击本来就是原版会发给服务端的动作）。</p>
 *
 * <h2>为什么不破坏别的模组复制过的内容</h2>
 * <p>写入时只<b>替换本模组那一段键</b>（{@link #CLIPBOARD_KEY}），其余段落原样保留 ——
 * Create 自己的复制路径是「整份重建」，本模组刻意不这么做：玩家可能先复制了某个 Create 机器的配置，
 * 再来复制总线，那次复制不应该把前一份静默抹掉。</p>
 *
 * <h2>专用服务端安全</h2>
 * <p>本类只引用两端都存在的类型（Create 的剪贴板物品与数据组件、原版事件、本模组的 support 类），
 * 不含任何 {@code net.minecraft.client.*} 引用，因此在专用服务端上加载安全。</p>
 */
@EventBusSubscriber(modid = RS_Create_Compat.MODID)
public final class RsccBusClipboard {
    /**
     * 剪贴板 {@code copied_values} 里属于本模组的那一段。
     * <p>带命名空间是刻意的：{@code copied_values} 是所有模组共用的一张表
     * （Create 的 {@code getClipboardKey()} 也是同样做法），段名冲突就等于互相覆盖配置。
     */
    private static final String CLIPBOARD_KEY = RS_Create_Compat.MODID + ":bus_config";

    /**
     * 整簇粘贴的台数上限。
     * <p>边界的一部分：即使玩家把几百台总线接成一整张线网，一次潜行左击最多也只改这么多台
     * （线缆簇本身的搜索是「每 tick 预算 + 跨 tick 续扫」，见 {@link RsccWireLinkSearch}
     * 里的 {@code BUS_LINK_TICK_BUDGET}；撞上异常大线网时由 {@code BUS_LINK_HARD_CAP} 兜底）。
     */
    public static final int BULK_MAX_BUSES = 64;

    /** 已复制：{@code %s} = 方块名。 */
    private static final String MSG_COPIED = "message.rs_create_compat.bus_clipboard.copied";
    /** 已粘贴到一条总线：{@code %s} = 方块名。 */
    private static final String MSG_PASTED = "message.rs_create_compat.bus_clipboard.pasted";
    /** 已粘贴到多条总线：{@code %s} = 台数。 */
    private static final String MSG_PASTED_MANY = "message.rs_create_compat.bus_clipboard.pasted_many";
    /** 种类不一致（剪贴板里是另一种总线的配置）。 */
    private static final String MSG_KIND_MISMATCH = "message.rs_create_compat.bus_clipboard.kind_mismatch";
    /** 校验通过但写入没成功（方块实体已不是那条总线：极罕见的状态错位）。 */
    private static final String MSG_FAILED = "message.rs_create_compat.bus_clipboard.failed";

    private RsccBusClipboard() {
    }

    /**
     * 右击 = <b>复制</b>这条总线的配置。
     *
     * <p><b>为什么潜行时完全不接管</b>：与 Create 自己的剪贴板挂点同一个取舍
     * （{@code ClipboardValueSettingsHandler#interact} 里 {@code player.isShiftKeyDown()} 就返回）——
     * 潜行是别的模组 / 别的功能常用的「精确档」，本模组不抢它。</p>
     */
    @SubscribeEvent(priority = EventPriority.NORMAL)
    public static void onRightClickBlock(final PlayerInteractEvent.RightClickBlock event) {
        if (event.isCanceled()) {
            return; // 已经被更外层的处理（伪装框架 / 分隔框架 / 扳手断缝）结算：一次右键只做一件事
        }
        final Player player = event.getEntity();
        if (player == null || player.isShiftKeyDown()) {
            return;
        }
        final ItemStack clipboard = event.getItemStack();
        if (!AllBlocks.CLIPBOARD.isIn(clipboard)) {
            return;
        }
        final Level level = event.getLevel();
        final BlockPos pos = event.getPos();
        final String kind = kindAt(level, pos);
        if (kind == null) {
            return; // 不是输入 / 输出总线：连事件都不取消，让 Create / RS / 原版照旧处理
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (!(level instanceof final ServerLevel serverLevel)) {
            return; // 客户端只取消事件：物品与世界状态一律由服务端那份数据决定
        }
        final BlockEntity blockEntity = serverLevel.getBlockEntity(pos);
        final CompoundTag payload = new CompoundTag();
        if (blockEntity instanceof final RsccImporterExecutorMode importer
            && RsccBusConfig.KIND_IMPORTER.equals(kind)) {
            importer.rscc$writeBusConfig(payload, serverLevel.registryAccess());
        } else if (blockEntity instanceof final RsccExporterExecutorMode exporter
            && RsccBusConfig.KIND_EXPORTER.equals(kind)) {
            exporter.rscc$writeBusConfig(payload, serverLevel.registryAccess());
        } else {
            return; // 方块状态说它是总线、方块实体却不是：宁可什么都不做（绝不写一份空配置进剪贴板）
        }
        writeToClipboard(clipboard, payload);
        player.displayClientMessage(
            Component.translatable(MSG_COPIED, nameOf(level, pos)), true);
    }

    /**
     * 左击 = <b>粘贴</b>（潜行 = 整簇粘贴）。
     *
     * <p><b>为什么必须在两端都取消</b>：左击在原版里的默认语义是「开始挖这个方块」。
     * 若只在一端取消，客户端会一直挖、服务端不理会，玩家看到的就是「粘贴的同时方块在裂」；
     * 两端一致取消才是干净的「这一下是粘贴，不是挖」。</p>
     *
     * <p><b>为什么不接管「剪贴板上没有本模组配置」的情形</b>：那样玩家拿着剪贴板挖总线会被无端拦住 ——
     * 只有确实复制过总线配置（载荷存在且能通过校验）时才接管。</p>
     */
    @SubscribeEvent(priority = EventPriority.NORMAL)
    public static void onLeftClickBlock(final PlayerInteractEvent.LeftClickBlock event) {
        if (event.isCanceled()) {
            return;
        }
        final ItemStack clipboard = event.getItemStack();
        if (!AllBlocks.CLIPBOARD.isIn(clipboard)) {
            return;
        }
        final CompoundTag payload = payloadOf(clipboard);
        if (payload == null) {
            return; // 剪贴板上没有本模组的配置：照常挖方块
        }
        final Level level = event.getLevel();
        final BlockPos pos = event.getPos();
        final String kind = kindAt(level, pos);
        if (kind == null) {
            return; // 不是输入 / 输出总线：不接管（照常挖）
        }
        event.setCanceled(true);
        if (!(level instanceof final ServerLevel serverLevel)) {
            return; // 客户端只取消事件
        }
        final Player player = event.getEntity();
        if (!RsccBusConfig.acceptsKind(payload, kind)) {
            // 校验不过就一个字都不写：这是「不要误改到不相关的总线」那条硬要求的落点
            player.displayClientMessage(Component.translatable(MSG_KIND_MISMATCH), true);
            return;
        }
        final int applied = player.isShiftKeyDown()
            ? pasteCluster(serverLevel, pos, kind, payload)
            : (pasteOne(serverLevel, pos, kind, payload) ? 1 : 0);
        if (applied <= 0) {
            player.displayClientMessage(Component.translatable(MSG_FAILED), true);
            return;
        }
        player.displayClientMessage(applied == 1
            ? Component.translatable(MSG_PASTED, nameOf(level, pos))
            : Component.translatable(MSG_PASTED_MANY, applied), true);
    }

    // ------------------------------------------------------------------
    // 载荷读写
    // ------------------------------------------------------------------

    /** 取剪贴板上本模组那一段载荷；没有（或为空）返回 {@code null}。 */
    @Nullable
    private static CompoundTag payloadOf(final ItemStack clipboard) {
        final CompoundTag copied = clipboard
            .getOrDefault(AllDataComponents.CLIPBOARD_CONTENT, ClipboardContent.EMPTY)
            .copiedValues().orElse(null);
        if (copied == null || !copied.contains(CLIPBOARD_KEY, net.minecraft.nbt.Tag.TAG_COMPOUND)) {
            return null;
        }
        final CompoundTag payload = copied.getCompound(CLIPBOARD_KEY);
        return payload.isEmpty() ? null : payload;
    }

    /**
     * 把载荷写进剪贴板：<b>只替换本模组这一段</b>，其余段落（Create 自己的、别的模组的）原样保留。
     * <p>写的是 {@code copied_values} 子标签（Create 复制配置时用的同一格），并把剪贴板类型置成
     * {@link ClipboardType#WRITTEN} —— 与 Create 自己复制配置后的表现一致（物品外观也会跟着变）。</p>
     */
    private static void writeToClipboard(final ItemStack clipboard, final CompoundTag payload) {
        final ClipboardContent content = clipboard
            .getOrDefault(AllDataComponents.CLIPBOARD_CONTENT, ClipboardContent.EMPTY);
        final CompoundTag merged = content.copiedValues().map(CompoundTag::copy).orElseGet(CompoundTag::new);
        merged.put(CLIPBOARD_KEY, payload);
        clipboard.set(AllDataComponents.CLIPBOARD_CONTENT,
            content.setType(ClipboardType.WRITTEN).setCopiedValues(merged));
    }

    // ------------------------------------------------------------------
    // 目标识别与写入
    // ------------------------------------------------------------------

    /**
     * 这一格是哪一种总线（{@link RsccBusConfig#KIND_IMPORTER} / {@link RsccBusConfig#KIND_EXPORTER}），
     * 不是总线则 {@code null}。
     * <p>只读方块状态（{@link RsccWireBlocks} 的类别判定，绝不取方块实体），因此这一步永远廉价、
     * 也不会因为「探测一个无关方块」而强制加载它的方块实体。</p>
     */
    @Nullable
    private static String kindAt(final Level level, final BlockPos pos) {
        final BlockState state = level.getBlockState(pos);
        if (RsccWireBlocks.isImporterBus(state)) {
            return RsccBusConfig.KIND_IMPORTER;
        }
        if (RsccWireBlocks.isExporterBus(state)) {
            return RsccBusConfig.KIND_EXPORTER;
        }
        return null;
    }

    /** 把载荷写到这一条总线；方块实体类型与种类不符时返回 {@code false}（一个字节都不写）。 */
    private static boolean pasteOne(final ServerLevel level, final BlockPos pos, final String kind,
                                    final CompoundTag payload) {
        final BlockEntity blockEntity = level.getBlockEntity(pos);
        if (RsccBusConfig.KIND_IMPORTER.equals(kind)
            && blockEntity instanceof final RsccImporterExecutorMode importer) {
            importer.rscc$readBusConfig(payload, level.registryAccess());
            return true;
        }
        if (RsccBusConfig.KIND_EXPORTER.equals(kind)
            && blockEntity instanceof final RsccExporterExecutorMode exporter) {
            exporter.rscc$readBusConfig(payload, level.registryAccess());
            return true;
        }
        return false;
    }

    /**
     * <b>整簇粘贴</b>（潜行 + 左击）：把载荷写到「同一条线缆簇里的全部同种总线」，返回真正写入的台数。
     *
     * <h2>范围边界（硬要求：不要误改到不相关的总线）</h2>
     * <ol>
     *     <li><b>同一线缆簇</b>：复用 {@link RsccWireLinkSearch#searchChamberLink} 的唯一一趟展开
     *     —— 它同时尊重「扳手断开的接缝」（{@link RsccCableCuts}）与「被分隔框架冻结的接缝」
     *     （{@link SeparationFrameGuard}），并且有步数 / 格子数上限。因此断开的那一段产线<b>不会</b>
     *     被顺着摸过去；这里刻意不另写一套 BFS，否则「哪些总线算一簇」迟早会出现两种口径；</li>
     *     <li><b>同一种总线</b>：只改与目标同类的那些格子（输入总线不会把配置糊到输出总线上
     *     —— 那也正是载荷种类校验要拦的事，这里再按方块状态收一次口）；</li>
     *     <li><b>台数上限</b>：{@link #BULK_MAX_BUSES}，到顶即停；</li>
     *     <li><b>不跨维度</b>：整趟展开只在一个 {@link ServerLevel} 里进行。</li>
     * </ol>
     * <p>兜底：展开被重入守卫拦下（另一趟搜链正在进行）时簇可能是空的，
     * 此时至少把配置贴到玩家点的那一条总线上 —— 潜行左击在任何情况下都不会变成「什么都没发生」。</p>
     */
    private static int pasteCluster(final ServerLevel level, final BlockPos origin, final String kind,
                                    final CompoundTag payload) {
        final List<BlockPos> cluster = RsccWireLinkSearch.searchChamberLink(level, origin).cluster();
        int applied = 0;
        for (final BlockPos pos : cluster) {
            if (applied >= BULK_MAX_BUSES) {
                break;
            }
            if (!kind.equals(kindAt(level, pos))) {
                continue;
            }
            if (pasteOne(level, pos, kind, payload)) {
                applied++;
            }
        }
        if (applied == 0 && pasteOne(level, origin, kind, payload)) {
            applied = 1;
        }
        return applied;
    }

    /** 方块名（只读方块状态，不碰方块实体）。 */
    private static Component nameOf(final Level level, final BlockPos pos) {
        return level.getBlockState(pos).getBlock().getName();
    }
}
