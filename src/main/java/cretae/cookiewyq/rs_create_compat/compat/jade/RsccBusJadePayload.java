package cretae.cookiewyq.rs_create_compat.compat.jade;

import cretae.cookiewyq.rs_create_compat.block.entity.SequenceExecutionChamberBlockEntity;
import cretae.cookiewyq.rs_create_compat.support.RsccBusCategory;
import cretae.cookiewyq.rs_create_compat.support.RsccExporterExecutorMode;
import cretae.cookiewyq.rs_create_compat.support.RsccImporterExecutorMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * <b>「序列装配总线该在 Jade 提示里显示什么」的唯一数据来源</b>（用户第 8 项 / 任务 A）。
 *
 * <h2>为什么要单独一个类（而不是把读写都写进 Jade 插件里）</h2>
 * <ol>
 *     <li><b>注册期不碰客户端类型</b>：{@code client/jade/RsccJadePlugin#register}（公共注册）在
 *     <b>专用服务端</b>也会被 Jade 调用，它只允许引用<b>本类</b>与
 *     {@link RsccBusJadeServerData}；两者都<b>不引用任何 {@code net.minecraft.client.*}</b>
 *     （本类只用 {@code Component} / 注册表 / NBT，全是公共类型）。文案与分组因此不可能把
 *     客户端专属类型拖进注册期 —— 这正是本工程刚修过一次的专服崩溃形态；</li>
 *     <li><b>一份格式、两端共用</b>：服务端 {@link #write} 写什么键、客户端 {@link #lines} 读什么键，
 *     都在本文件里；命名 / 结构一旦漂移，只有一处可改（不会出现「写 A 读 B」的静默空白）；</li>
 *     <li><b>可离线验证</b>：分组与文案是纯函数（NBT 进、{@code Component} 出），
 *     自检 {@code tools/selfcheck_round54_jade_bus.py} 能直接锚定这里的口径。</li>
 * </ol>
 *
 * <h2>三份数据的只读来源（服务端方块实体，客户端拿不到 ⇒ 由 Jade 自己带过来）</h2>
 * <table border="1">
 *     <caption>来源</caption>
 *     <tr><th>数据</th><th>唯一来源</th><th>客户端能否直读</th></tr>
 *     <tr><td>与哪台执行仓绑定</td><td>{@link RsccImporterExecutorMode#rscc$getLinkedExecutor()} /
 *     {@link RsccExporterExecutorMode#rscc$getLinkedExecutor()}（内部 20 tick 缓存 + 邻块事件失效）</td>
 *     <td><b>不能</b>：两个桥接接口在客户端一律返回 {@code null}（客户端不认识方块实体）</td></tr>
 *     <tr><td>负责的类别（含「原料 / 输入时原料」分流）</td>
 *     <td>{@code rscc$getCategorySnapshot()}（服务端权威类别快照）</td><td><b>不能</b>：同上</td></tr>
 *     <tr><td>输入总线是否全自动</td><td>{@link RsccImporterExecutorMode#rscc$isAutoCollect()}</td>
 *     <td><b>不能</b>：同上</td></tr>
 * </table>
 * <p>因此<b>不需要新增同步包</b>：Jade 官方的服务端数据通道（{@code IServerDataProvider} +
 * {@code BlockAccessor#getServerData()}）本来就只把「玩家正在看的那一格」的 NBT 发一次，
 * 契约、节流、目标校验都由 Jade 负责。这也是本项目既有的「伪装方块」那套接入的同一条路。</p>
 *
 * <h2>成链 = 一台逻辑执行仓</h2>
 * <p>绑定信息按<b>链</b>显示：名字取 {@code getChamberDisplayName()}（链委托，整条链同名），
 * 类别的「原料 / 输入时原料」分流按<b>定义该类别的链成员</b>（{@code chainCategories()} 的属主）
 * 判定 —— 与执行仓界面 / 总线界面看到的分类同一份数据，不另造链口径。</p>
 *
 * <h2>未绑定 = 一行都不加（用户第 79 轮）</h2>
 * <p>用户原话：「<i>输出总线那个地方，如果说他没有绑定序列执行舱，你就不要显示那些（就是）绑定之后
 * 才该显示的东西，也不要显示什么「未连接」什么「执行仓」之类的东西，否则怪怪的。</i>」
 * 因此本类的口径是：</p>
 * <ul>
 *     <li><b>服务端</b> {@link #write} 在归属执行仓为 {@code null} 时<b>一个键都不写</b>
 *     （连 {@link #ROOT} 都不写）—— 于是客户端 {@link #hasData} 为假，
 *     {@code RsccBusJadeProvider#appendTooltip} 在「按 Shift 显示」档位下也<b>不会</b>补
 *     「按住 Shift 查看总线状态」那行提示（没有内容可看时那句提示本身就是误导）；</li>
 *     <li><b>客户端</b> {@link #lines} 再按 {@link #LINKED} 做一次<b>防御性</b>判定：
 *     为假就<b>立刻返回空表</b>。因此旧存档 / 外部拼出来的载荷也不会冒出
 *     「未连接 / 未绑定执行仓 / 执行仓：—」这类<b>绑定后才有意义</b>的占位行；</li>
 *     <li>未绑定时玩家看到的只剩 <b>RS 原版普通总线</b>自己的提示（方块名 / 模组名等），
 *     它们由 RS / Jade 自己给，不经过本类 —— 这正是「只显示与未绑定状态真正相关的信息」。</li>
 * </ul>
 * <p>反过来，<b>一旦绑定</b>（唯一归属成立）就照常显示：归属名 → 全自动（仅输入总线）→ 方向 →
 * 六节类别（没有类别时才是「当前没有负责的类别」）。这条「已绑定分支」由自检
 * {@code tools/selfcheck_round79_bus_unbound_ui.py} 逐键断言，防止把功能改没了。</p>
 *
 * <h2>为什么六节的名字直接复用「类别详细配置」界面那六个键</h2>
 * <p>「原料 / 输入时原料 / 流体 / 成品 / 废料 / 中间产物」在工程里只能有<b>一套</b>说法与
 * <b>一套顺序</b>（那里是 {@code GROUP_ORDER}，顺序不可调换）。这里复用同一批键，
 * 因此玩家在总线界面与瞄准提示里看到的分节名逐字相同，不会出现「同一件事两个名字」。</p>
 */
public final class RsccBusJadePayload {
    /** 服务端数据里的根复合标签（本模组专用；别的 provider 的键不会与它相撞）。 */
    public static final String ROOT = "rscc_bus";
    /** 总线方向：{@value #KIND_IMPORTER} = 输入总线，{@value #KIND_EXPORTER} = 输出总线。 */
    private static final String KIND = "kind";
    public static final String KIND_IMPORTER = "importer";
    public static final String KIND_EXPORTER = "exporter";
    /** 是否绑定了执行仓（服务端只在绑定成功时才写本载荷，因此这里恒为 {@code true}；客户端仍按它做防御性判定）。 */
    private static final String LINKED = "linked";
    /** 输入总线是否处于全自动收回模式。 */
    private static final String AUTO = "auto";
    /** 归属执行仓的显示名（链委托名 / 坐标串）。 */
    private static final String CHAMBER = "chamber";
    /** 负责的类别分组列表：每项 = {@code {group, ids}}。 */
    private static final String GROUPS = "groups";
    /** 分组项里的组 id。 */
    private static final String GROUP = "group";
    /** 分组项里的代表物注册名列表（物品类别 = 物品，流体类别 = 流体）。 */
    private static final String IDS = "ids";

    // ==================== 六个分节（键与顺序都与「类别详细配置」界面同源） ====================
    /** 分节名语言键前缀（复用总线「类别详细配置」界面的那一批，见类注释）。 */
    private static final String GROUP_LANG = "gui.rs_create_compat.bus_config.group.";
    public static final String GROUP_MATERIALS = "materials";
    public static final String GROUP_FEEDSTOCK = "feedstock";
    public static final String GROUP_FLUIDS = "fluids";
    public static final String GROUP_PRODUCTS = "products";
    public static final String GROUP_SCRAP = "scrap";
    public static final String GROUP_INTERMEDIATES = "intermediates";

    /**
     * 六个分节的固定顺序（与「类别详细配置」界面的 {@code GROUP_ORDER} 逐字一致：
     * 原料 → 输入时原料 → 流体 → 成品 → 废料 → 中间产物）。
     */
    public static final List<String> GROUP_ORDER = List.of(
        GROUP_MATERIALS, GROUP_FEEDSTOCK, GROUP_FLUIDS, GROUP_PRODUCTS, GROUP_SCRAP,
        GROUP_INTERMEDIATES);

    /** 本类自己的文案键前缀（绑定 / 自动 / 方向 / 分组行 / 空态）。 */
    private static final String LANG = "gui.rs_create_compat.jade_bus.";

    /**
     * <b>每一组最多列几个代表物</b>（= 提示宽度的上限锚点）。
     * <p>为什么要有它：一条链的类别数量不设上限（附属模组可以把一个配方扩展到很多输入），
     * 若把每个代表物都铺在一行里，提示框会横向溢出界面。这里取 3 件 + 一个「…」收尾 ——
     * <b>只表示「还有更多」，不写数量</b>（用户硬要求：不枚举种类数）。
     * 完整清单仍然在总线界面的「详细配置」里，提示只负责一眼看清。</p>
     */
    public static final int MAX_NAMES_PER_GROUP = 3;

    private RsccBusJadePayload() {
    }

    // ==================== 服务端：写 ====================

    /**
     * 把一条<b>已绑定执行仓</b>的总线状态写进 Jade 的服务端数据（服务端调用；客户端 / 无方块实体时
     * 什么都不做）。
     *
     * <p><b>未绑定 ⇒ 一个键都不写</b>（连 {@link #ROOT} 都不写）：这是用户第 79 轮「没绑定就不要显示
     * 绑定后才有意义的东西」的落点。客户端因此 {@code hasData} 为假、一行都不加，
     * 与「不是本模组总线」走同一条短路 —— 玩家看到的只剩 RS 原版普通总线的提示。</p>
     *
     * <p><b>只读</b>：全程只调既有的只读入口（归属缓存报告 / 类别快照 / 链类别表），
     * 不写任何状态、不重装策略 —— 与「打开总线界面」同一条只读纪律。</p>
     */
    public static void write(final CompoundTag out, final Level level, final BlockPos pos,
                             @Nullable final BlockEntity blockEntity) {
        if (out == null || level == null || level.isClientSide() || pos == null || blockEntity == null) {
            return;
        }
        final CompoundTag root = new CompoundTag();
        // 输入总线与输出总线是两个互斥的桥接接口；各自只认自己那一个（同一台方块实体不会同时是两者）。
        if (blockEntity instanceof RsccImporterExecutorMode importer) {
            final SequenceExecutionChamberBlockEntity chamber = importer.rscc$getLinkedExecutor();
            if (chamber == null) {
                return; // 未绑定：一个键都不写（连根标签都不写）
            }
            root.putString(KIND, KIND_IMPORTER);
            root.putBoolean(AUTO, importer.rscc$isAutoCollect());
            root.putBoolean(LINKED, true); // 走到这里必然已绑定；客户端仍按它做防御性判定
            root.putString(CHAMBER, chamber.getChamberDisplayName());
            root.put(GROUPS, groups(importer.rscc$getCategorySnapshot(), chamber));
        } else if (blockEntity instanceof RsccExporterExecutorMode exporter) {
            final SequenceExecutionChamberBlockEntity chamber = exporter.rscc$getLinkedExecutor();
            if (chamber == null) {
                return; // 未绑定：一个键都不写（连根标签都不写）
            }
            root.putString(KIND, KIND_EXPORTER);
            // 输出总线没有「全自动」这一档：它的勾选永远显式（未显式时按默认集展示，见类别快照）
            root.putBoolean(AUTO, false);
            root.putBoolean(LINKED, true);
            root.putString(CHAMBER, chamber.getChamberDisplayName());
            root.put(GROUPS, groups(exporter.rscc$getCategorySnapshot(), chamber));
        } else {
            return; // 不是本模组的两条总线：一个键都不写（Jade 侧因此完全保持原样）
        }
        out.put(ROOT, root);
    }

    /**
     * 把类别快照按六个分节归组（<b>只收「本总线此刻真的负责」的类别</b>：{@code selected}）。
     *
     * <p>「原料 / 输入时原料」的分流判据 = <b>定义该类别的链成员</b>的既有只读入口
     * {@link SequenceExecutionChamberBlockEntity#isStartIngredient(Item)}（本仓「起步原料」表，
     * 与推料保护、在制名额读的是同一份），物品 ∈ 起步原料 ⇒ 原料，否则 ⇒ 输入时原料
     * （与「类别详细配置」界面的「起步原料优先」口径一致，见那里 {@code inputSectionOf} 的说明）。
     * 服务端不再自己解析 RecipeManager，因此不可能出现第二套「哪件是原料」的判定。</p>
     */
    private static ListTag groups(final List<RsccBusCategory> categories,
                                  @Nullable final SequenceExecutionChamberBlockEntity chamber) {
        final ListTag result = new ListTag();
        if (categories == null || categories.isEmpty()) {
            return result;
        }
        final Map<String, SequenceExecutionChamberBlockEntity> owners = ownersOf(chamber);
        final Map<String, LinkedHashSet<String>> grouped = new LinkedHashMap<>();
        for (final RsccBusCategory category : categories) {
            if (category == null || !category.selected() || category.isRecipeTab()) {
                continue; // 未勾选 / 「只喂标签页」的标记类别不是类别：不进任何一组
            }
            final SequenceExecutionChamberBlockEntity owner = owners.getOrDefault(category.id(), chamber);
            final String group = groupOf(category, owner);
            if (group.isEmpty()) {
                continue;
            }
            final String icon = representativeId(category);
            if (icon.isEmpty()) {
                continue; // 连代表物都给不出来（注册名解析不出）：宁可不显示，也不显示一行空的
            }
            grouped.computeIfAbsent(group, key -> new LinkedHashSet<>()).add(icon);
        }
        // 固定顺序输出（与界面分节顺序同源），因此提示里的行序在整条链上永远一致
        for (final String group : GROUP_ORDER) {
            final LinkedHashSet<String> ids = grouped.get(group);
            if (ids == null || ids.isEmpty()) {
                continue;
            }
            final ListTag list = new ListTag();
            for (final String id : ids) {
                list.add(StringTag.valueOf(id));
            }
            final CompoundTag entry = new CompoundTag();
            entry.putString(GROUP, group);
            entry.put(IDS, list);
            result.add(entry);
        }
        return result;
    }

    /** 链上「类别 id → 定义它的那台仓」表（唯一来源是既有 {@code chainCategories()}）。 */
    private static Map<String, SequenceExecutionChamberBlockEntity> ownersOf(
        @Nullable final SequenceExecutionChamberBlockEntity chamber) {
        final Map<String, SequenceExecutionChamberBlockEntity> owners = new LinkedHashMap<>();
        if (chamber == null) {
            return owners;
        }
        for (final SequenceExecutionChamberBlockEntity.ChainBusCategory entry : chamber.chainCategories()) {
            if (entry != null && entry.info() != null && entry.info().id() != null) {
                owners.putIfAbsent(entry.info().id(), entry.owner());
            }
        }
        return owners;
    }

    /** 一个类别落在六个分节里的哪一节（判据全部来自既有类别模型，不新造分类）。 */
    private static String groupOf(final RsccBusCategory category,
                                 @Nullable final SequenceExecutionChamberBlockEntity owner) {
        if (category.isFluidInput()) {
            return GROUP_FLUIDS;
        }
        if (category.isIntermediate()) {
            return GROUP_INTERMEDIATES;
        }
        if (category.isResult()) {
            return GROUP_PRODUCTS;
        }
        if (category.isScrap()) {
            return GROUP_SCRAP;
        }
        if (!category.isInput()) {
            return ""; // 既不是输入也不是产出：未知 / 标记类别，不进任何一组
        }
        // 物品输入：按「本配方的起步原料」分流（判据见 groups 的说明）
        final String itemId = category.inputItemId();
        final Item item = itemOf(itemId);
        if (item != null && owner != null && owner.isStartIngredient(item)) {
            return GROUP_MATERIALS;
        }
        return GROUP_FEEDSTOCK;
    }

    /** 一个类别的代表物注册名（流体类别给流体 id，其余给物品 id）。 */
    private static String representativeId(final RsccBusCategory category) {
        if (category.isFluidInput()) {
            return nullSafe(category.iconFluid());
        }
        final String item = nullSafe(category.iconItem());
        return item.isEmpty() ? nullSafe(category.iconFluid()) : item;
    }

    private static String nullSafe(@Nullable final String value) {
        return value == null ? "" : value;
    }

    // ==================== 客户端：读 + 文案 ====================

    /**
     * 服务端数据 → 提示行（<b>纯函数</b>：NBT 进、{@code Component} 出，可被自检直接锚定）。
     *
     * <p><b>空数据 / 未绑定 = 一行都不加</b>：{@code client/jade/RsccBusJadeProvider} 用
     * {@link #hasData(CompoundTag)} 先短路；即使拿到了旧存档 / 外部拼出来的载荷，
     * {@link #LINKED} 为假时本方法也<b>立刻返回空表</b> —— 未绑定时绝不出现
     * 「归属执行舱 / 方向 / 当前没有负责的类别」这类<b>绑定后才有意义</b>的行。</p>
     *
     * <p><b>已绑定</b>时的行序固定为：归属名 → 全自动（仅输入总线）→ 方向 → 六节类别
     * （一个类别都没有时才是「当前没有负责的类别」）。</p>
     */
    public static List<Component> lines(@Nullable final CompoundTag serverData) {
        final List<Component> out = new ArrayList<>();
        if (!hasData(serverData)) {
            return out;
        }
        final CompoundTag root = serverData.getCompound(ROOT);
        final boolean linked = root.getBoolean(LINKED);
        if (!linked) {
            // 未绑定 ⇒ 一行都不加：绑定后才有意义的行（归属名 / 方向 / 类别 / 空态）一律不出现。
            return out;
        }
        out.add(Component.translatable(LANG + "linked", root.getString(CHAMBER)));
        if (root.getBoolean(AUTO)) {
            out.add(Component.translatable(LANG + "auto"));
        }
        // 方向行只说「这条总线往哪个方向搬」，不重复任何界面上的操作说明
        out.add(Component.translatable(KIND_IMPORTER.equals(root.getString(KIND))
            ? LANG + "dir.importer" : LANG + "dir.exporter"));
        final ListTag groups = root.getList(GROUPS, Tag.TAG_COMPOUND);
        if (groups.isEmpty()) {
            out.add(Component.translatable(LANG + "none"));
            return out;
        }
        for (int i = 0; i < groups.size(); i++) {
            final Component line = groupLine(groups.getCompound(i));
            if (line != null) {
                out.add(line);
            }
        }
        return out;
    }

    /**
     * 服务端数据里有没有本模组那条根复合标签（客户端据此决定「加行」还是「完全放行」）。
     * <p><b>未绑定就一定是 {@code false}</b>：服务端 {@link #write} 在归属为 {@code null} 时
     * 连根标签都不写 —— 于是「按 Shift 显示」档位的提示行也不会为本模组总线冒出来。</p>
     */
    public static boolean hasData(@Nullable final CompoundTag serverData) {
        return serverData != null && !serverData.getCompound(ROOT).isEmpty();
    }

    /**
     * 一行分节：{@code <分节名>：<代表物 · 代表物>}。
     * <p>取不到任何可显示的名字（注册名全部解析不出）⇒ 返回 {@code null}（不画空行）。</p>
     */
    @Nullable
    private static Component groupLine(final CompoundTag group) {
        final String groupId = group.getString(GROUP);
        final ListTag ids = group.getList(IDS, Tag.TAG_STRING);
        final boolean fluid = GROUP_FLUIDS.equals(groupId);
        final MutableComponent joined = Component.empty();
        int shown = 0;
        for (int i = 0; i < ids.size() && shown < MAX_NAMES_PER_GROUP; i++) {
            final Component name = displayName(ids.getString(i), fluid);
            if (name == null) {
                continue; // 这一件解析不出（附属模组改过 id）：跳过，不占名额
            }
            if (shown > 0) {
                joined.append(Component.literal(" · "));
            }
            joined.append(name);
            shown++;
        }
        if (shown == 0) {
            return null;
        }
        if (ids.size() > shown) {
            joined.append(Component.literal(" · …")); // 只表示「还有」，不写数量（用户硬要求）
        }
        return Component.translatable(LANG + "line", Component.translatable(GROUP_LANG + groupId), joined);
    }

    /** 代表物注册名 → 显示名（客户端自己的语言；解析不出返回 {@code null}）。 */
    @Nullable
    private static Component displayName(final String id, final boolean fluid) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        final ResourceLocation key = ResourceLocation.tryParse(id);
        if (key == null) {
            return null;
        }
        if (fluid) {
            final Fluid resolved = BuiltInRegistries.FLUID.get(key);
            if (resolved == null || resolved == Fluids.EMPTY) {
                return null;
            }
            // 与 client/widget/GhostMarkerRenderer#fluidName 同源（RS 官方流体 tooltip = 流体类型描述）；
            // 这里刻意不引用那个客户端类：本类要能被专用服务端的注册期安全加载。
            return resolved.getFluidType().getDescription();
        }
        final Item item = itemOf(id);
        return item == null ? null : new ItemStack(item).getHoverName();
    }

    /** 物品注册名 → 物品（解析不出 / 空气返回 {@code null}；与界面侧同一口径）。 */
    @Nullable
    private static Item itemOf(@Nullable final String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        final ResourceLocation key = ResourceLocation.tryParse(id);
        if (key == null) {
            return null;
        }
        final Item item = BuiltInRegistries.ITEM.get(key);
        return item == null || item == Items.AIR ? null : item;
    }
}
