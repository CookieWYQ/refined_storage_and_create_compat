package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.CollectionCacheBlockEntity;
import cretae.cookiewyq.rs_create_compat.menu.AdvancedCollectionCacheMenu;
import cretae.cookiewyq.rs_create_compat.support.Area;
import cretae.cookiewyq.rs_create_compat.support.AreaMarks;
import cretae.cookiewyq.rs_create_compat.support.AreaSelection;
import cretae.cookiewyq.rs_create_compat.support.AreaSelectorTargets;
import cretae.cookiewyq.rs_create_compat.support.MarkerEntry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * C2S：<b>区域选定器的唯一写入通道</b>（增 / 改 / 删 / 选中 / 改绑维度 / 复制 / 重命名）。
 *
 * <h2>为什么客户端不直接改物品组件</h2>
 * <p>客户端手上的 {@link ItemStack} 是服务端同步下来的副本；在客户端改它的数据组件既不会落盘，
 * 也会被下一次同步覆盖（更别说可被作弊者伪造）。因此界面 / 世界手势只发<b>意图</b>，
 * 由服务端校验后写入，再由菜单 / 背包同步回客户端。</p>
 *
 * <h2>落点优先级（照设计文档 §3.4 C）</h2>
 * <ol>
 *     <li>{@code containerId >= 0}（菜单路径）⇒ 必须<b>正是</b>玩家当前打开的
 *     {@link AdvancedCollectionCacheMenu} 且 {@code containerId} 一致、{@code stillValid}
 *     （方块还在 + 距离）才改它的「区域选定器槽」；否则<b>丢包</b>（不退回主手，避免陈旧包改到别的物品）；</li>
 *     <li>{@code containerId < 0}（世界手势路径）⇒ 只认玩家<b>自己的主手</b>选定器；</li>
 *     <li>都不是 ⇒ <b>丢包</b>（防作弊：不能凭空改别人 / 别的槽位的物品）。</li>
 * </ol>
 *
 * <h2>服务端校验（写入前）</h2>
 * <ul>
 *     <li>玩家：旁观者一律拒绝（旁观者能发包但不应改物品数据）；</li>
 *     <li>力度（距离 / 方块存在性）：菜单路径看 {@code stillValid(player)}（方块仍在 + 8 格内）；</li>
 *     <li>维度：选定器已绑定维度 ≠ 当前维度 ⇒ 只接受 {@link #ACTION_REBIND}，其余一律拒绝；</li>
 *     <li>区域个数（只在 {@code ADD} 时拒绝新增）：{@link AreaSelection#maxAreas()}（Config）；</li>
 *     <li>区域几何（只在 {@code ADD / REPLACE} 时校验新几何）：{@link Area#invalidReason(long)}
 *     （单边 ≤128、体积 ≤ Config、坐标在世界边界内）；</li>
 *     <li>下标：{@code 0 <= index < areas.size()}（{@code SELECT} 允许 {@code -1} = 取消选中）。</li>
 * </ul>
 *
 * <h2>写回<b>绝不</b>调用 {@link AreaSelection#sanitize}（本轮修掉的数据丢失）</h2>
 * <p>旧写法每次写回都先 {@code sanitize(selection)}。而 {@code sanitize} 会按<b>当前</b>
 * {@code Config.areaSelectorMaxAreas / areaSelectorMaxVolume} 截断 —— 于是管理员把上限调小后，
 * 玩家哪怕只是<b>点选一行</b>（{@link #ACTION_SELECT}，几何根本没变），物品上超限的历史区域也会被
 * 永久切掉，与 {@code Config} 的承诺（「改小后物品上的数据保留，放回大值即恢复」）正好相反。
 * 现在的口径是：</p>
 * <ul>
 *     <li><b>写入</b>只做「把本次动作应用到不可变记录上」；上限只在 {@code ADD / REPLACE} 时
 *     对<b>新数据</b>把关；</li>
 *     <li><b>消费</b>侧（高级归流缓存仓的 {@code resolveSelection} / {@code getAreaStatus}）
 *     才 {@code sanitize}：超限区域照旧<b>不参与扫描</b>、界面状态如实显示，
 *     但物品上的数据一个字节都不少 —— 配置调回大值即全部恢复参与。</li>
 * </ul>
 * <p>线程：处理体只碰服务端类型（菜单 / 玩家 / 物品）。<b>正确说法</b>：本包在注册时用的是 NeoForge
 * {@code PayloadRegistrar} 的默认 {@code HandlerThread.MAIN}，由 {@code MainThreadPayloadHandler}
 * 在主线程调用 —— 代码里<b>没有</b> {@code ctx.enqueueWork}（旧注释那么写是错的，这里更正）。</p>
 */
public record AreaSelectorEditPacket(int containerId, int action, int index, Area area, String name)
    implements CustomPacketPayload {
    public static final int ACTION_ADD = 0;
    public static final int ACTION_REPLACE = 1;
    public static final int ACTION_DELETE = 2;
    public static final int ACTION_SELECT = 3;
    /** 改绑到当前维度：清空全部区域并以当前维度重新绑定（跨维度时的唯一逃生口）。 */
    public static final int ACTION_REBIND = 4;
    /** 复制第 {@code index} 个区域（服务端算「同尺寸 + 沿 +X 平移直到不撞」的新几何）。 */
    public static final int ACTION_COPY = 5;
    /** 重命名第 {@code index} 个区域（{@code name} 空白 = 清除名字；名字不参与判等）。 */
    public static final int ACTION_RENAME = 6;
    /** DELETE / SELECT 时没有几何：填一个占位区域（编解码不接受 null）。 */
    public static final Area PLACEHOLDER = Area.single(net.minecraft.core.BlockPos.ZERO);

    private static final org.slf4j.Logger LOGGER =
        org.slf4j.LoggerFactory.getLogger("rs_create_compat/AreaSelectorEditPacket");

    // ==================== 第 106 轮：拒绝原因回传（用户第 5 条） ====================

    /**
     * 服务端对最近一次区域写入动作的<b>拒绝原因</b>（0 = 没有被拒绝）。
     *
     * <h2>为什么必须回传原因</h2>
     * <p>旧实现所有「校验不过」的分支都是 <b>静默 {@code return}</b>：玩家点了一个服务端认为
     * 不存在 / 维度不符的区域，界面没有任何反馈，只有「标记怎么没生效」。用户原话：
     * 「有的时候还会选那个根本不存在的区域，就导致我标记不了，我还以为是坏了」。
     * 现在每次拒绝都会把原因记到菜单上，随 {@code AdvancedAreaSnapshotPacket} 一起下发，
     * 区域列表界面据此给一句明确的话，并<b>按服务端最新列表自动刷新</b>。</p>
     *
     * <p>码值只增不改（网络契约的一部分）：0 无 / 1 区域不存在 / 2 维度不符 / 3 已达上限 /
     * 4 几何重复 / 5 几何非法（越界 / 超单边 / 超体积）。</p>
     */
    public static final int REJECT_NONE = 0;
    public static final int REJECT_NO_SUCH_AREA = 1;
    public static final int REJECT_DIMENSION = 2;
    public static final int REJECT_LIMIT = 3;
    public static final int REJECT_DUPLICATE = 4;
    public static final int REJECT_INVALID_AREA = 5;

    /** 四字段便捷构造（几何类动作；名字留空）。既有调用点逐字兼容。 */
    public AreaSelectorEditPacket(final int containerId, final int action, final int index, final Area area) {
        this(containerId, action, index, area, "");
    }

    public static final Type<AreaSelectorEditPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "area_selector_edit"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AreaSelectorEditPacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, AreaSelectorEditPacket::containerId,
            ByteBufCodecs.VAR_INT, AreaSelectorEditPacket::action,
            ByteBufCodecs.VAR_INT, AreaSelectorEditPacket::index,
            Area.STREAM_CODEC, AreaSelectorEditPacket::area,
            ByteBufCodecs.STRING_UTF8, AreaSelectorEditPacket::name,
            AreaSelectorEditPacket::new
        );

    public static void handle(final AreaSelectorEditPacket packet, final IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer player)) {
            return;
        }
        handle(packet, player);
    }

    public static void handle(final AreaSelectorEditPacket packet, final ServerPlayer player) {
        // 第 106 轮：拒绝原因要记到<b>菜单</b>上才有回传通道（主手路径没有菜单 ⇒ 靠界面本地校验兜底）
        final AdvancedCollectionCacheMenu rejectMenu = menuOf(packet.containerId(), player);
        // 旁观者：能发 C2S 包但不该改任何物品数据（服务端权威的第一道闸）。
        // 第 124 轮（用户 2-1「看着改了、其实没写」）：这里以前是<b>完全静默 return</b> ——
        // 客户端乐观副本还在，玩家看不到任何「没写进去」的信号。现在一样走 reject()：
        // 菜单路径补发全量槽位 + 拒绝序号（界面显示一句原因），主手路径补一次全量背包 + 一条聊天提示。
        if (player.isSpectator()) {
            reject(rejectMenu, player, packet, REJECT_NO_SUCH_AREA);
            return;
        }
        final ItemStack target = resolveTarget(packet.containerId(), player);
        if (target.isEmpty()) {
            // 同上：目标解析不出来（菜单已关 / 槽位被换走 / 主手不是选定器）也是静默丢弃过，
            // 客户端那份乐观改的名会一直留着 ⇒ 必须让客户端把物品刷新回权威值并说一句。
            reject(rejectMenu, player, packet, REJECT_NO_SUCH_AREA);
            return;
        }
        final ResourceLocation dimension = player.level().dimension().location();
        AreaSelection selection = AreaSelection.get(target);
        // 维度闸门：已绑定的选定器只允许在它自己的维度里被编辑
        if (selection.dimension() != null && !selection.dimension().equals(dimension)
            && packet.action() != ACTION_REBIND) {
            reject(rejectMenu, player, packet, REJECT_DIMENSION);
            return;
        }
        if (packet.action() == ACTION_REBIND) {
            final AreaSelection rebound = new AreaSelection(dimension, java.util.List.of(), -1);
            AreaSelection.set(target, rebound);
            // 第 112 轮：过滤标记存在物品上 ⇒ 区域没了，标记也要一起剪掉（否则物品 NBT 里留幽灵条目）
            AreaMarks.prune(target, rebound);
            sync(player, packet.containerId());
            return;
        }
        if (selection.dimension() == null) {
            selection = selection.withDimension(dimension);
        }
        final int index = packet.index();
        // 第 113 轮：REPLACE 换的是<b>几何</b>，而每区域过滤标记以几何为键（{@code AreaMarks}）——
        // 不把旧几何的标记搬过去，就等于「改一下框就把这个区域的过滤清空」（beta.13 键=序号时不会丢）。
        List<MarkerEntry> carriedMarks = List.of();
        switch (packet.action()) {
            case ACTION_ADD -> {
                if (selection.areaCount() >= AreaSelection.maxAreas()) {
                    reject(rejectMenu, player, packet, REJECT_LIMIT);
                    return;
                }
                final Area area = packet.area();
                if (area == null || area.invalidReason(AreaSelection.maxVolume()) != null) {
                    reject(rejectMenu, player, packet, REJECT_INVALID_AREA);
                    return;
                }
                // 第 96 轮：去重（同维度 + 同坐标 = 同一个区域）。服务端是最终权威：
                // 客户端也拦一层只是为了「立刻提示」，绕过客户端伪造的包在这里被丢掉。
                if (selection.hasGeometry(area, -1)) {
                    reject(rejectMenu, player, packet, REJECT_DUPLICATE);
                    return;
                }
                selection = selection.append(area);
            }
            case ACTION_REPLACE -> {
                if (index < 0 || index >= selection.areaCount()) {
                    reject(rejectMenu, player, packet, REJECT_NO_SUCH_AREA);
                    return;
                }
                final Area area = packet.area();
                if (area == null || area.invalidReason(AreaSelection.maxVolume()) != null) {
                    reject(rejectMenu, player, packet, REJECT_INVALID_AREA);
                    return;
                }
                // 去重：改成「已有另一块地」的几何同样拒绝（excludeIndex = 自己，自己跟自己不算重复）
                if (selection.hasGeometry(area, index)) {
                    reject(rejectMenu, player, packet, REJECT_DUPLICATE);
                    return;
                }
                final Area previous = selection.areas().get(index);
                carriedMarks = AreaMarks.get(target, previous,
                    player.level().registryAccess());
                // 第 124 轮（用户 2-1）：REPLACE **只换几何，不许顺手把名字抹掉**。
                // 客户端 flushApply 发来的是 readAreaFromFields 拼出来的无名 Area（只有六个坐标），
                // 旧代码直接 replace ⇒ 「改一下坐标 / 尺寸，这个区域的名字就没了」；而客户端乐观副本
                // 已经用 withName(旧名字) 先显示了新名字 ⇒ 玩家看到「改了」，重开（服务端权威）才发现没名字。
                // 名字的唯一写入口是 ACTION_RENAME，因此包里的 Area 没带名字时沿用旧名字。
                selection = selection.replace(index,
                    area.name() == null ? area.withName(previous.name()) : area);
            }
            case ACTION_DELETE -> {
                if (index < 0 || index >= selection.areaCount()) {
                    reject(rejectMenu, player, packet, REJECT_NO_SUCH_AREA);
                    return;
                }
                selection = selection.remove(index);
            }
            case ACTION_SELECT -> {
                if (index < -1 || index >= selection.areaCount()) {
                    // 第 106 轮：这一步以前是静默 return ——「选了不存在的区域」一点反馈都没有
                    reject(rejectMenu, player, packet, REJECT_NO_SUCH_AREA);
                    return;
                }
                selection = selection.select(index);
            }
            case ACTION_COPY -> {
                if (selection.areaCount() >= AreaSelection.maxAreas()) {
                    reject(rejectMenu, player, packet, REJECT_LIMIT);
                    return;
                }
                final AreaSelection duplicated = selection.duplicate(index);
                if (duplicated == null) {
                    reject(rejectMenu, player, packet, REJECT_DUPLICATE); // 没有可用的平移落点（全都撞上已有区域）
                    return;
                }
                selection = duplicated;
            }
            case ACTION_RENAME -> {
                if (index < 0 || index >= selection.areaCount()) {
                    reject(rejectMenu, player, packet, REJECT_NO_SUCH_AREA);
                    return;
                }
                // 名字不参与判等 ⇒ 重命名永远不会被去重拒绝；空白 = 清除名字（Area 的紧凑构造器归一化）
                selection = selection.rename(index, packet.name());
            }
            default -> {
                return;
            }
        }
        // 写回**不带** sanitize：见类注释「写回绝不调用 sanitize」。超限历史区域由消费侧（扫描 / 状态）
        // 排除，而不是在这里被截掉 —— 否则「只是点选一行」就会把配置改小前的区域永久删掉。
        AreaSelection.set(target, selection);
        // 第 113 轮：REPLACE 换几何 ⇒ 旧几何的过滤标记搬到新几何（否则改一下框过滤就没了）。
        // 新几何上可能已经有标记（这块地以前被删过又改回来）⇒ 按 (类别, id) 去重后合并，绝不覆盖。
        if (!carriedMarks.isEmpty()) {
            final Area newArea = packet.area();
            if (newArea != null) {
                final List<MarkerEntry> existing = AreaMarks.get(target, newArea,
                    player.level().registryAccess());
                if (existing.isEmpty()) {
                    AreaMarks.put(target, newArea, carriedMarks);
                } else {
                    final List<MarkerEntry> merged = new java.util.ArrayList<>(existing);
                    for (final MarkerEntry entry : carriedMarks) {
                        if (entry == null || entry.isEmpty()) {
                            continue;
                        }
                        boolean seen = false;
                        for (final MarkerEntry kept : merged) {
                            if (kept != null && !kept.isEmpty() && kept.id().equals(entry.id())
                                && kept.fluid() == entry.fluid()) {
                                seen = true;
                                break;
                            }
                        }
                        if (!seen) {
                            merged.add(entry);
                        }
                    }
                    if (merged.size() > CollectionCacheBlockEntity.MATCH_CAPACITY) {
                        // 第 115 轮（第 114 轮复核 L1）：两块地的标记按 (类别, id) 去重后仍超过单区域上限时，
                        // AreaMarks.put → sanitizeEntries 会静默截到 MATCH_CAPACITY 条。
                        // 正常玩法极难触发（要单区域 >288 条、且新几何上也有标记），但「静默丢配置」不能只靠运气：
                        // 这里明确告警并说明保留的是哪一部分（existing 在前、保留下来的尾部 carried 会丢）。
                        LOGGER.warn("[rs_create_compat] 区域选定器：REPLACE 把旧几何的 {} 条标记搬到新几何，"
                                + "与那块地上已有的 {} 条合并后共 {} 条，超过单区域上限 {} 条 ⇒ 已按上限截断"
                                + "（保留新几何原有标记在前），被截掉的需手动重新添加（玩家 {}）",
                            carriedMarks.size(), existing.size(), merged.size(),
                            CollectionCacheBlockEntity.MATCH_CAPACITY, player.getName().getString());
                    }
                    AreaMarks.put(target, newArea, merged);
                }
            }
        }
        // 第 112 轮：每区域过滤标记存在物品上（AreaMarks，键 = 区域几何）。
        // 删除区域 / 改绑之后把不再存在的几何剪掉；改名 / 重排 / 选中 / REPLACE（已先搬运）都不丢。
        AreaMarks.prune(target, selection);
        sync(player, packet.containerId());
    }

    /**
     * 客户端给的 containerId 对应的<b>高级仓菜单</b>（服务端才认；主手路径 / 陈旧 id ⇒ {@code null}）。
     * <p>第 112 轮起判据收敛到 {@link AreaSelectorTargets#menuOf}：区域包与标记包必须逐字同源。</p>
     */
    @Nullable
    private static AdvancedCollectionCacheMenu menuOf(final int containerId, final ServerPlayer player) {
        return AreaSelectorTargets.menuOf(containerId, player);
    }

    /**
     * 拒绝一次区域写入：<b>把原因记到菜单上并立刻同步</b>（快照会把原因带回客户端），
     * 不再像旧实现那样静默丢弃。
     * <p><b>两条落点都必须发「全量」状态</b>：客户端在发包前是<b>乐观</b>改了自己那份物品副本
     * （{@code AreaSelection.set}），被服务端拒绝后如果不同步回来，那份「改过的」副本会一直留着 ——
     * 接着配标记时会按一个服务端根本不存在的几何发 {@code AreaMarkEditPacket}，被静默丢包，
     * 界面却弹「已加入标记」。</p>
     * <ul>
     *     <li>没有菜单（主手编辑）⇒ 没有快照通道，直接 {@code player.inventoryMenu.broadcastFullState()}，
     *     再补一条聊天提示（第 124 轮：用户 2-1 的「看着改了、其实没写」不能是「谁都不知道」的状态；
     *     SELECT 除外，见方法体注释）；</li>
     *     <li>有菜单 ⇒ {@code sync(...)} 里的 {@code broadcastChanges()} 只发「槽内容与上次快照不同」的槽，
     *     而拒绝时服务端物品<b>本来就没变</b> ⇒ 一个槽包都不发。所以这里额外补一次
     *     {@code menu.broadcastFullState()}（第 119 轮 C-1）。<b>只在拒绝时调一次</b>，
     *     不要挪进 {@link AreaSelectorTargets#sync}（那条路径每次成功写入也走，没必要全量发）。</li>
     * </ul>
     */
    private static void reject(@Nullable final AdvancedCollectionCacheMenu menu, final ServerPlayer player,
                               final AreaSelectorEditPacket packet, final int reason) {
        if (menu == null) {
            player.inventoryMenu.broadcastFullState();
            // 第 124 轮（用户 2-1）：主手路径<b>没有</b>快照回包通道，因此旧实现在这里只有「悄悄把物品刷回去」——
            // 玩家看到的仍是自己那份曾经「看起来改好了」的记忆，而且下一条假记录还会继续发（去重判据读的
            // 是客户端乐观副本）。现在补一条聊天提示：界面关掉后仍在聊天记录里，且与界面里显示的是同一句文案
            // （复用 gui.*.err.* 既有语言键，本轮不新增语言键）。
            // SELECT 只是「切到哪一行」的导航动作（界面自己已经用本地判据说明了维度不符），
            // 每一次点行都弹一条会是刷屏 —— 不提示它，其余动作（真的想改数据）一律提示。
            if (packet.action() != ACTION_SELECT) {
                player.displayClientMessage(Component.translatable(rejectMessageKey(reason)), false);
            }
            return;
        }
        menu.noteAreaReject(reason);
        sync(player, packet.containerId());
        // 菜单路径的 sync 只调 `broadcastChanges()`；被拒时槽内容没变 ⇒ 不发槽包 ⇒ 客户端乐观副本残留。
        // 补一次全量回发（ClientboundContainerSetContentPacket）把客户端副本纠回服务端权威值。
        menu.broadcastFullState();
    }

    /** 拒绝原因 → 界面同款文案的语言键（{@code gui.rs_create_compat.area_selector.err.*}，均为既有键）。 */
    private static String rejectMessageKey(final int reason) {
        return switch (reason) {
            case REJECT_DIMENSION -> "gui.rs_create_compat.area_selector.err.dimension";
            case REJECT_LIMIT -> "gui.rs_create_compat.area_selector.err.limit";
            case REJECT_DUPLICATE -> "gui.rs_create_compat.area_selector.err.duplicate";
            case REJECT_INVALID_AREA -> "gui.rs_create_compat.area_selector.err.reject.invalid";
            default -> "gui.rs_create_compat.area_selector.err.reject.no_such_area";
        };
    }

    /**
     * 落点解析（服务端权威，不信任客户端给的 containerId 之外的任何东西）。
     * <p>第 112 轮起收敛到 {@link AreaSelectorTargets#resolve}（与标记包 {@code AreaMarkEditPacket}
     * 同一套判据）：手持 ⇒ 主手选定器；菜单 ⇒ 当前打开且 {@code stillValid} 的高级仓菜单槽位；
     * 其余丢包（菜单路径<b>绝不</b>回退主手，否则陈旧包能改到别的物品）。</p>
     */
    private static ItemStack resolveTarget(final int containerId, final ServerPlayer player) {
        return AreaSelectorTargets.resolve(containerId, player);
    }

    /** 写入后把新数据推回客户端（槽位 / 背包两条路径），并让方块实体标脏 + 让扫描游标失效。 */
    private static void sync(final ServerPlayer player, final int containerId) {
        AreaSelectorTargets.sync(player, containerId);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
