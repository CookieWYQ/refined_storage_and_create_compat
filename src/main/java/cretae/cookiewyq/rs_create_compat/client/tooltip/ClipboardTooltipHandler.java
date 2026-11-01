package cretae.cookiewyq.rs_create_compat.client.tooltip;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.content.equipment.clipboard.ClipboardContent;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.compat.jade.RsccBusJadePayload;
import cretae.cookiewyq.rs_create_compat.support.RsccBusCategory;
import cretae.cookiewyq.rs_create_compat.support.RsccBusClipboard;
import cretae.cookiewyq.rs_create_compat.support.RsccBusConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * <b>机械动力剪贴板的「复制了什么配置」附加提示</b>（用户原话：「他并没有在这个剪贴板的这个 tooltip
 * 上面能够显示出来他复制了什么配置……需要你能够给这个 tooltip 增加一些附加显示，就是可以显示出它复制了
 * 什么方块的什么配置」）。
 *
 * <h2>数据从哪来（客户端到底能不能读到）—— 能，而且不用包</h2>
 * <p>剪贴板内容是<b>物品数据组件</b> {@code create:clipboard_content}
 * （{@link ClipboardContent} 的 {@code copied_values} 子标签），不是系统剪贴板
 * （{@code Minecraft#keyboardHandler.getClipboard()} 与它毫无关系）。证据有两条：</p>
 * <ol>
 *     <li>Create 自己就在客户端读它：{@code ClipboardValueSettingsHandler#clientTick} 里
 *     {@code mc.player.getMainHandItem().get(AllDataComponents.CLIPBOARD_CONTENT)}；</li>
 *     <li>本模组的复制 / 粘贴路径读写的是同一个组件：{@code RsccBusClipboard#payloadOf} 与
 *     {@code #writeToClipboard}（服务端写入，组件的 {@code STREAM_CODEC} 会随物品同步到客户端，
 *     见 {@link ClipboardContent} 的 {@code CODEC} / {@code STREAM_CODEC}）。</li>
 * </ol>
 * <p>因此本类只在<b>客户端</b>读那份已经同步下来的物品组件，不需要新增任何网络包；专用服务端上
 * 通过 {@code @EventBusSubscriber(value = Dist.CLIENT)} 完全不加载本类。</p>
 *
 * <h2>显示什么（概括常显 + Shift 详情）</h2>
 * <p><b>常显</b>只有 1~2 行概括：总线配置一行 —— 已绑定执行舱时是「输入总线 · 归属=仓A · 3 类 · 自动开」
 * （输出总线没有自动这一档），未绑定 / 旧载荷时退回「过滤条数 + 模糊 + 自动」那种只讲配置本身的写法；
 * 别的格式则是「机械动力配置 · N 项」/「其他模组配置 · N 项」。内容再杂也最多两行。
 * <b>详情</b>（完整的分段列表）只在玩家<b>按住 Shift</b> 时展开 —— 走工程既有的分层唯一实现
 * {@link RsccTooltipLayers}（{@code Layer.SHIFT}），不另起一套按键判断。</p>
 * <p>用户原话：「剪贴板的这个 tooltip 显示的信息太有限了，你可以显示个概括的信息，
 * 但是必须能让玩家看到更为详细的信息，比如说按下 shift 键。」</p>
 *
 * <h2>Shift 详情必须「至少对标 Jade」——靠复用 Jade 那一份载荷，而不是另写一套（第 87 轮）</h2>
 * <p>用户原话：「你这个说实话就太简陋了，还不如我直接看这个 Jade 的信息更高效。所以说你起码得对标
 * Jade 的信息显示吧。」Jade 对一条已绑定执行舱的总线显示：<b>归属执行舱 → 自动模式（仅输入总线）→
 * 方向 → 六个分节（原料 / 输入时原料 / 流体 / 成品 / 废料 / 中间产物，每节给代表物）</b>，
 * 一个类别都没有时显示「当前没有负责的类别」。</p>
 * <p>本类<b>不重写</b>这套判定：{@link RsccBusClipboard} 在复制时直接把
 * {@link RsccBusJadePayload} 那一份服务端载荷（同一个 {@code RsccBusJadePayload#write}）写进剪贴板，
 * 这里用 {@link RsccBusJadePayload#lines} 原样展开 —— 因此「六个类别」的定义、代表物的解析、
 * 文案键、行序、以及「未绑定就一行都不显示」的口径，与玩家瞄准总线时看到的 Jade 提示<b>逐字同源</b>。
 * 工程里因此只有一份「总线该显示什么」，不存在两套会漂移的分类口径。</p>
 *
 * <h2>显示什么（三类来源，同一份 tooltip）</h2>
 * <ol>
 *     <li><b>本模组的总线配置</b>（{@code rs_create_compat:bus_config}）：方块类型（输入 / 输出总线）
 *     + 来源总线状态（上面那批 Jade 行）+ 过滤器条数与<b>过滤条目</b> + 模糊模式 + 类别（自动 / 默认 /
 *     勾选计数）+ 自动收回 + 强制普通总线；</li>
 *     <li><b>机械动力自己的复制格式</b>（{@code ClipboardCloneable#getClipboardKey()} 写进
 *     {@code copied_values} 的那几段，如 {@code Filtering} / {@code Settings} / {@code Speed} /
 *     {@code Frequencies} / {@code Timings} / {@code Chassis} / 各种模式枚举名）：
 *     逐段翻成人类可读的名字；过滤段如果带着过滤物品，就显示物品名；</li>
 *     <li><b>别的模组</b>：它们的段名（键）原样列出 —— 本模组不可能预知每个模组的键含义，
 *     所以<b>只承诺「能看见它复制了哪几段」</b>，不假装能解释每一段的内容。</li>
 * </ol>
 * <p><b>绝不挤掉别人的行</b>：本类只做 {@code tooltip.add / addAll}（经 {@link RsccTooltipLayers}），
 * 从不清空 / 删除 / 替换已有行 —— 机械动力自己给剪贴板加的描述、别的模组的附加提示都原样留在前面，
 * 本模组的内容永远只是<b>追加</b>。</p>
 *
 * <h2>Create 的格式里没有「源方块」</h2>
 * <p>{@code ClipboardContent} 只记录 {@code type / pages / read_only / previously_opened_page /
 * copied_values} 五个字段，{@code copied_values} 里只有「配置段名 → 配置内容」，
 * <b>没有方块 id</b>。因此对 Create 机器只能显示「复制了哪些配置段」，<b>不能</b>显示「从哪台机器复制的」
 * —— 这是数据格式本身的边界，本类不猜（猜错比不显示更糟）。本模组自己的总线配置则记录了
 * {@code kind}，所以能准确说出是输入总线还是输出总线。</p>
 *
 * <h2>性能：tooltip 每帧重建，绝不每帧解析 NBT</h2>
 * <p>原版在鼠标悬停时每帧都会重新收集 tooltip 行，而 {@code copied_values} 是任意模组可写的一张表。
 * 这里用<b>两层一行的身份缓存</b>：概括行与详情行各有一份「上次看到的内容实例 → 构造好的行」，
 * 命中时直接返回，本帧一个 NBT 键都不读。两层都只在<b>内容实例真的换了</b>时才解析一次
 * （{@link ClipboardContent} 是不可变记录，物品组件只在复制 / 编辑时被替换成新实例，
 * 所以「同一个实例 ⇒ 同一份内容」用 {@code ==} 判定，不调用 {@code equals} 去深比较整棵 NBT 树）。</p>
 * <p><b>详情只在按住 Shift 时构造</b>：{@link #onItemTooltip} 先加常显的概括行，然后
 * 问 {@link RsccTooltipLayers#held} —— 没按 Shift 时只补一条提示行，<b>不会</b>调用
 * {@link #linesFor}（详情构造入口）。因此不按 Shift 的帧既不拼全量字符串、也不读详情缓存。</p>
 *
 * <h2>安全降级</h2>
 * <p>空剪贴板 / 只有文字页 / 别的模组的格式 / 损坏的 NBT 都必须安静地什么都不做：
 * 缺少组件、{@code copied_values} 为空、段类型不对、解析抛异常，全部落到「没有附加行」；
 * 任何异常都不会冒到 tooltip 渲染链上（{@link #linesFor} 的 try/catch 是最后一道闸）。</p>
 */
@EventBusSubscriber(modid = RS_Create_Compat.MODID, value = Dist.CLIENT)
public final class ClipboardTooltipHandler {
    /** 附加块标题：只在确实读出了内容时出现。 */
    private static final String KEY_HEADER = "gui.rs_create_compat.clipboard_tooltip.header";
    /**
     * 开关被玩家关掉时的<b>一行</b>提示：告诉他敲哪条指令才能看到配置详情。
     * <p><b>为什么开关关掉还要加这一行</b>：用户实机反馈第 11 条「剪贴板 tooltip 没有任何区别」——
     * 根因就是这个开关默认关、而且关掉之后界面上<b>没有任何线索</b>说明「有个开关」。
     * 于是这里做最小的可发现性：只有「剪贴板里确实有本模组的总线配置」时才追加这一行（不是所有剪贴板），
     * 且只一行灰色文字 —— 玩家主动关掉的偏好仍然被尊重（配置详情依然不显示）。</p>
     */
    private static final String KEY_HINT_OFF = "gui.rs_create_compat.clipboard_tooltip.hint.off";

    // ---------- 常显的概括行（1~2 行；详情按 Shift 才展开）----------
    /**
     * 输入总线的概括：「输入总线 · %s 条过滤 · 模糊%s · 自动%s」。
     * <p>刻意比详情行短：概括只回答「复制了哪种配置、大致什么模式」，
     * 精确字段（类别小计 / 强制普通 / 收回归属）留在 Shift 详情里。</p>
     */
    private static final String KEY_SUMMARY_IMPORTER =
        "gui.rs_create_compat.clipboard_tooltip.summary.importer";
    /** 输出总线的概括：「输出总线 · %s · 模糊%s」。 */
    private static final String KEY_SUMMARY_EXPORTER =
        "gui.rs_create_compat.clipboard_tooltip.summary.exporter";
    /** 概括里的过滤器条数：「%s 条过滤」（%s = 过滤段 {@code rf} 的键数）。 */
    private static final String KEY_SUMMARY_FILTER_COUNT =
        "gui.rs_create_compat.clipboard_tooltip.summary.filter.count";
    /** 概括里的过滤器：「无过滤」（过滤段存在但一个槽都没有）。 */
    private static final String KEY_SUMMARY_FILTER_EMPTY =
        "gui.rs_create_compat.clipboard_tooltip.summary.filter.empty";
    /** 概括里的过滤器：「过滤未知」（载荷里没有过滤段；不假装是空过滤器）。 */
    private static final String KEY_SUMMARY_FILTER_UNKNOWN =
        "gui.rs_create_compat.clipboard_tooltip.summary.filter.unknown";
    /** 概括里的开 / 关（模糊模式、自动模式的取值）。 */
    private static final String KEY_SUMMARY_ON = "gui.rs_create_compat.clipboard_tooltip.summary.on";
    /** 概括里的关。 */
    private static final String KEY_SUMMARY_OFF = "gui.rs_create_compat.clipboard_tooltip.summary.off";
    /** 只有 Create 配置时的概括：「机械动力配置 · %s 项」。 */
    private static final String KEY_SUMMARY_CREATE =
        "gui.rs_create_compat.clipboard_tooltip.summary.create";
    /** 只有别的模组配置时的概括：「其他模组配置 · %s 项」。 */
    private static final String KEY_SUMMARY_OTHER =
        "gui.rs_create_compat.clipboard_tooltip.summary.other";
    /** 概括第二行（还有别的内容没展开）：「另有 %s 项」。 */
    private static final String KEY_SUMMARY_MORE =
        "gui.rs_create_compat.clipboard_tooltip.summary.more";
    /** 载荷版本比本模组新 / 种类未知：概括只诚实地说「格式不识别」，详情仍给出原因行。 */
    private static final String KEY_SUMMARY_UNKNOWN =
        "gui.rs_create_compat.clipboard_tooltip.summary.unknown";
    /**
     * 已绑定执行舱时的输入总线概括：「输入总线 · 归属=%s · %s 类 · 自动%s」。
     * <p>第 87 轮（用户要求「对标 Jade 的信息量」）：概括也要能一眼看到<b>归属</b>与<b>类别数</b>，
     * 而不只是过滤 / 模式；Jade 常显里最有用的两件事就是「这条总线归哪台执行仓」和「它负责几类东西」。</p>
     */
    private static final String KEY_SUMMARY_IMPORTER_LINKED =
        "gui.rs_create_compat.clipboard_tooltip.summary.importer_linked";
    /** 已绑定执行舱时的输出总线概括：「输出总线 · 归属=%s · %s 类」（输出总线没有自动这一档）。 */
    private static final String KEY_SUMMARY_EXPORTER_LINKED =
        "gui.rs_create_compat.clipboard_tooltip.summary.exporter_linked";

    // ---------- 本模组总线配置 ----------
    /** 「方块：%s」。 */
    private static final String KEY_BUS_BLOCK = "gui.rs_create_compat.clipboard_tooltip.bus.block";
    /** 输入总线。 */
    private static final String KEY_KIND_IMPORTER = "gui.rs_create_compat.clipboard_tooltip.bus.kind.importer";
    /** 输出总线。 */
    private static final String KEY_KIND_EXPORTER = "gui.rs_create_compat.clipboard_tooltip.bus.kind.exporter";
    /** 更新版本的载荷：本模组不认识（不猜字段）。%s = 版本号。 */
    private static final String KEY_BUS_UNSUPPORTED = "gui.rs_create_compat.clipboard_tooltip.bus.unsupported";
    /** 段存在但种类不是输入 / 输出总线。%s = 原始种类字符串。 */
    private static final String KEY_BUS_UNKNOWN = "gui.rs_create_compat.clipboard_tooltip.bus.unknown";
    /** 过滤器为空。 */
    private static final String KEY_FILTER_EMPTY = "gui.rs_create_compat.clipboard_tooltip.bus.filter.empty";
    /** 「过滤器：%s 项 · 模糊模式：%s」。 */
    private static final String KEY_FILTER_COUNT = "gui.rs_create_compat.clipboard_tooltip.bus.filter.count";
    /** 模糊模式：开。 */
    private static final String KEY_FUZZY_ON = "gui.rs_create_compat.clipboard_tooltip.bus.fuzzy.on";
    /** 模糊模式：关。 */
    private static final String KEY_FUZZY_OFF = "gui.rs_create_compat.clipboard_tooltip.bus.fuzzy.off";
    /** 输入总线自动收回：开。 */
    private static final String KEY_AUTO_ON = "gui.rs_create_compat.clipboard_tooltip.bus.auto.on";
    /** 输入总线自动收回：关。 */
    private static final String KEY_AUTO_OFF = "gui.rs_create_compat.clipboard_tooltip.bus.auto.off";
    /** 自动模式下的类别：不按勾选（非输入类全部收回）。 */
    private static final String KEY_CATEGORIES_AUTO = "gui.rs_create_compat.clipboard_tooltip.bus.categories.auto";
    /** 输出总线从未显式勾选：默认全选输入性产物。 */
    private static final String KEY_CATEGORIES_DEFAULT =
        "gui.rs_create_compat.clipboard_tooltip.bus.categories.default";
    /** 一个都不勾。 */
    private static final String KEY_CATEGORIES_EMPTY = "gui.rs_create_compat.clipboard_tooltip.bus.categories.empty";
    /** 「类别：%s 项（%s）」。 */
    private static final String KEY_CATEGORIES_COUNT = "gui.rs_create_compat.clipboard_tooltip.bus.categories.count";
    /** 类别分组「输入 %s」。 */
    private static final String KEY_GROUP_INPUT = "gui.rs_create_compat.clipboard_tooltip.bus.categories.input";
    /** 类别分组「流体 %s」。 */
    private static final String KEY_GROUP_FLUID = "gui.rs_create_compat.clipboard_tooltip.bus.categories.fluid";
    /** 类别分组「中间产物 %s」。 */
    private static final String KEY_GROUP_INTERMEDIATE =
        "gui.rs_create_compat.clipboard_tooltip.bus.categories.intermediate";
    /** 类别分组「成品 %s」。 */
    private static final String KEY_GROUP_RESULT = "gui.rs_create_compat.clipboard_tooltip.bus.categories.result";
    /** 类别分组「废料 %s」。 */
    private static final String KEY_GROUP_SCRAP = "gui.rs_create_compat.clipboard_tooltip.bus.categories.scrap";
    /** 类别分组「其他 %s」。 */
    private static final String KEY_GROUP_OTHER = "gui.rs_create_compat.clipboard_tooltip.bus.categories.other";
    /** 强制普通总线：是。 */
    private static final String KEY_FORCE_ON = "gui.rs_create_compat.clipboard_tooltip.bus.force.on";
    /** 强制普通总线：否。 */
    private static final String KEY_FORCE_OFF = "gui.rs_create_compat.clipboard_tooltip.bus.force.off";
    /** 过滤条目里实际列出的物品 / 流体名：「过滤条目：%s」。 */
    private static final String KEY_FILTER_ITEMS = "gui.rs_create_compat.clipboard_tooltip.bus.filter.items";
    /** 过滤条目超过上限时的收尾：「…另有 %s 项」。 */
    private static final String KEY_FILTER_ITEMS_MORE =
        "gui.rs_create_compat.clipboard_tooltip.bus.filter.items_more";

    // ---------- 「来源总线」块（与 Jade 完全同源，第 87 轮） ----------
    /**
     * 详情里的小标题：「来源总线（复制时）」。
     * <p>为什么需要它：下面那几行描述的是<b>复制那一刻</b>源总线的状态（归属 / 方向 / 六节类别），
     * 不是「粘贴后会变成什么」。总线的实时状态随时间变化，而剪贴板冻结的是复制时的快照 ——
     * 不加这句小标题，玩家会以为「归属执行舱」是粘贴目标的实时状态。</p>
     */
    private static final String KEY_DETAILS_SOURCE =
        "gui.rs_create_compat.clipboard_tooltip.details.source";

    /**
     * <b>Jade 服务端载荷的键名镜像</b>（{@link RsccBusJadePayload} 里全是 {@code private} 常量）。
     * <p>只读两个用途：概括行取「归属名 / 自动 / 类别数」，以及判断「有没有绑定」。
     * <p>为什么可以镜像而不改那个类：那个文件本轮<b>不在写入范围内</b>；而这些键是**载荷格式**的一部分、
     * 已经随 Jade 服务端数据与剪贴板数据一起落盘，改名等于破坏兼容。为防止两边漂移，
     * 自检 {@code selfcheck_round81_clipboard_tooltip} 会把这里的字面量与
     * {@code RsccBusJadePayload} 的私有常量逐字比对（改名而不同步 ⇒ 判负）。
     */
    private static final String JADE_LINKED = "linked";
    private static final String JADE_CHAMBER = "chamber";
    private static final String JADE_AUTO = "auto";
    private static final String JADE_GROUPS = "groups";
    private static final String JADE_IDS = "ids";
    /** 过滤条目最多列几个名字（再多折成「…另有 N 项」）；防止一份大过滤器把 tooltip 撑爆。 */
    private static final int MAX_FILTER_NAMES = 6;

    // ---------- 机械动力自己的配置段 ----------
    /** 「机械动力：%s」。 */
    private static final String KEY_CREATE_HEADER = "gui.rs_create_compat.clipboard_tooltip.create.header";
    /** 过滤槽（空）。 */
    private static final String KEY_CREATE_FILTERING =
        "gui.rs_create_compat.clipboard_tooltip.create.section.filtering";
    /** 「过滤槽（%s）」。 */
    private static final String KEY_CREATE_FILTERING_ITEM =
        "gui.rs_create_compat.clipboard_tooltip.create.section.filtering_item";
    /** 数值设置。 */
    private static final String KEY_CREATE_SETTINGS =
        "gui.rs_create_compat.clipboard_tooltip.create.section.settings";
    /** 转速。 */
    private static final String KEY_CREATE_SPEED = "gui.rs_create_compat.clipboard_tooltip.create.section.speed";
    /** 频率。 */
    private static final String KEY_CREATE_FREQUENCIES =
        "gui.rs_create_compat.clipboard_tooltip.create.section.frequencies";
    /** 定时。 */
    private static final String KEY_CREATE_TIMINGS = "gui.rs_create_compat.clipboard_tooltip.create.section.timings";
    /** 底盘。 */
    private static final String KEY_CREATE_CHASSIS = "gui.rs_create_compat.clipboard_tooltip.create.section.chassis";
    /** 红石二极管反相状态（Create 的段名就叫 {@code Block}）。 */
    private static final String KEY_CREATE_BLOCK = "gui.rs_create_compat.clipboard_tooltip.create.section.block";
    /** 辊压模式。 */
    private static final String KEY_CREATE_ROLLING =
        "gui.rs_create_compat.clipboard_tooltip.create.section.rolling";
    /** 钟表指针模式。 */
    private static final String KEY_CREATE_CLOCK_HANDS =
        "gui.rs_create_compat.clipboard_tooltip.create.section.clock_hands";
    /** 旋转模式。 */
    private static final String KEY_CREATE_ROTATION =
        "gui.rs_create_compat.clipboard_tooltip.create.section.rotation";
    /** 旋转方向。 */
    private static final String KEY_CREATE_ROTATION_DIRECTION =
        "gui.rs_create_compat.clipboard_tooltip.create.section.rotation_direction";
    /** 矿车模式。 */
    private static final String KEY_CREATE_CART = "gui.rs_create_compat.clipboard_tooltip.create.section.cart";
    /** 运动模式。 */
    private static final String KEY_CREATE_MOVEMENT =
        "gui.rs_create_compat.clipboard_tooltip.create.section.movement";
    /** 选择模式。 */
    private static final String KEY_CREATE_SELECTION =
        "gui.rs_create_compat.clipboard_tooltip.create.section.selection";

    // ---------- 别的模组 ----------
    /** 「其他模组配置：%s」。 */
    private static final String KEY_OTHER_HEADER = "gui.rs_create_compat.clipboard_tooltip.other.header";
    /** 「…另有 %s 项」。 */
    private static final String KEY_OTHER_MORE = "gui.rs_create_compat.clipboard_tooltip.other.more";

    /**
     * Create 的配置段名 → 语言键。
     * <p>键名来自 Create 6.0.10 的 {@code ClipboardCloneable} 实现（已按真实源码逐个核对）：
     * {@code ValueSettingsBehaviour} = {@code Settings}、{@code FilteringBehaviour} = {@code Filtering}、
     * {@code KineticScrollValueBehaviour} = {@code Speed}、{@code LinkBehaviour} = {@code Frequencies}、
     * {@code BrassDiodeScrollValueBehaviour} = {@code Timings}、{@code BrassDiodeBlockEntity} = {@code Block}、
     * {@code ChassisBlockEntity} = {@code Chassis}，而 {@code ScrollOptionBehaviour} 用的是
     * 「枚举类的 simple name」（{@code RollingMode} / {@code ClockHands} / {@code RotationMode} /
     * {@code RotationDirection} / {@code CartMovementMode} / {@code MovementMode} / {@code SelectionMode}）。
     * <p>用 {@link LinkedHashMap} 固定展示顺序：tooltip 行的顺序不该随 HashMap 的迭代顺序漂移。</p>
     */
    private static final Map<String, String> CREATE_SECTIONS = new LinkedHashMap<>();
    /** 其他模组段名最多列几个（再多就折成「另有 N 项」）；防止别的模组的巨型表把 tooltip 撑爆。 */
    private static final int MAX_OTHER_KEYS = 4;

    static {
        CREATE_SECTIONS.put("Filtering", KEY_CREATE_FILTERING);
        CREATE_SECTIONS.put("Settings", KEY_CREATE_SETTINGS);
        CREATE_SECTIONS.put("Speed", KEY_CREATE_SPEED);
        CREATE_SECTIONS.put("Frequencies", KEY_CREATE_FREQUENCIES);
        CREATE_SECTIONS.put("Timings", KEY_CREATE_TIMINGS);
        CREATE_SECTIONS.put("Chassis", KEY_CREATE_CHASSIS);
        CREATE_SECTIONS.put("Block", KEY_CREATE_BLOCK);
        CREATE_SECTIONS.put("RollingMode", KEY_CREATE_ROLLING);
        CREATE_SECTIONS.put("ClockHands", KEY_CREATE_CLOCK_HANDS);
        CREATE_SECTIONS.put("RotationMode", KEY_CREATE_ROTATION);
        CREATE_SECTIONS.put("RotationDirection", KEY_CREATE_ROTATION_DIRECTION);
        CREATE_SECTIONS.put("CartMovementMode", KEY_CREATE_CART);
        CREATE_SECTIONS.put("MovementMode", KEY_CREATE_MOVEMENT);
        CREATE_SECTIONS.put("SelectionMode", KEY_CREATE_SELECTION);
    }

    /**
     * 缓存：上次看到的剪贴板内容实例，以及为它构造好的行。
     * <p>只在客户端渲染线程读写，因此不加锁；{@code cachedContent == content} 是 O(1) 的身份比较。
     * 详情那一层（{@link #cachedContent} / {@link #cachedLines}）只会在按住 Shift 的帧被读取；
     * 概括那一层（{@link #cachedSummaryContent} / {@link #cachedSummaryLines}）每帧都可能被读到。</p>
     */
    private static ClipboardContent cachedContent;
    private static List<Component> cachedLines = List.of();
    /** 概括行的身份缓存（与详情缓存相互独立：两者构造时机不同）。 */
    private static ClipboardContent cachedSummaryContent;
    private static List<Component> cachedSummaryLines = List.of();

    private ClipboardTooltipHandler() {
    }

    /**
     * 物品 tooltip 挂点（客户端）：<b>概括常显 + Shift 详情</b>。
     *
     * <h2>两条分支（结构上只可能有一条生效）</h2>
     * <ol>
     *     <li><b>常显</b>：{@link #summaryFor} 给出 1~2 行概括，无条件加入（这就是玩家不看按键也能读到的信息）；</li>
     *     <li><b>详情</b>：先问 {@link RsccTooltipLayers#held}（{@code Layer.SHIFT}）——
     *     按住才调 {@link #showDetails}（详情构造入口 {@link #linesFor} 只在它里面被调用）；
     *     没按住只补一条「按住 Shift 查看更多」的提示行，<b>详情一个字符都不拼</b>。</li>
     * </ol>
     *
     * <h2>开关关闭时</h2>
     * <p>这里是唯一出口：配置行一行都不加；只有当「剪贴板里确实有本模组的总线配置」时，
     * 追加<b>一行</b>灰色提示告诉他怎么打开（见 {@link #KEY_HINT_OFF}）——
     * 目的是让「关掉了」这件事本身可见，而不是让玩家以为功能坏了。</p>
     */
    @SubscribeEvent
    public static void onItemTooltip(final ItemTooltipEvent event) {
        // ① 开关（指令 /rs_create_compat clipboard-tooltip on|off，默认开）：
        //    关 ⇒ 配置行一行都不出现，只在「确有本模组配置」时留一行「怎么打开」的提示。
        if (!ClipboardTooltipSetting.isEnabled()) {
            addDisabledHint(event);
            return;
        }
        final ItemStack stack = event.getItemStack();
        if (stack == null || stack.isEmpty() || !AllBlocks.CLIPBOARD.isIn(stack)) {
            return; // 不是剪贴板：本挂点与它无关（这一步只查物品，不读 NBT）
        }
        final Player player = event.getEntity();
        if (player == null) {
            return; // 搜索树构建期（player == null）：不读注册表、不构造行
        }
        // ② 常显概括：不进任何按键分支（玩家不按 Shift 也必须看得到「复制了什么」）。
        final List<Component> summary = summaryFor(stack);
        if (summary.isEmpty()) {
            return; // 空剪贴板 / 只有文字页 / 别的格式：安静地什么都不加（与改动前一致）
        }
        final List<Component> tooltip = event.getToolTip();
        tooltip.addAll(summary);
        // ③ 详情只在按住 Shift 时构造；没按只加提示行（详情构造入口 linesFor 不在这一支里）。
        if (RsccTooltipLayers.held(RsccTooltipLayers.Layer.SHIFT)) {
            showDetails(tooltip, stack, event.getContext().registries());
        } else {
            addShiftHint(tooltip);
        }
    }

    /**
     * 把详情（完整的分段列表）按 <b>SHIFT 层</b> 追加到 tooltip —— 复用 {@link RsccTooltipLayers}，
     * 不在这里自己读按键、也不自己拼「按住 Shift」的提示行。
     * <p>调用点已在 {@link #onItemTooltip} 里用 {@link RsccTooltipLayers#held} 判定过，因此这里
     * 只负责「取详情（带身份缓存）+ 交给分层实现」。</p>
     */
    private static void showDetails(final List<Component> tooltip, final ItemStack stack,
                                    final HolderLookup.Provider registries) {
        final List<Component> details = linesFor(stack, registries);
        if (details.isEmpty()) {
            return;
        }
        RsccTooltipLayers.append(tooltip, RsccTooltipLayers.allShift(details));
    }

    /**
     * 没按 Shift 时的提示行：直接问 {@link RsccTooltipLayers#hintLine}（同一套文案 / 同一套按键名），
     * 不构造详情。等价于 {@code append} 在「有 SHIFT 内容但没按住」时会加的那一行。
     */
    private static void addShiftHint(final List<Component> tooltip) {
        final Component hint = RsccTooltipLayers.hintLine(
            java.util.EnumSet.of(RsccTooltipLayers.Layer.SHIFT),
            java.util.EnumSet.noneOf(RsccTooltipLayers.Layer.class));
        if (hint != null) {
            tooltip.add(hint);
        }
    }

    /**
     * 开关关闭时的可发现性提示（只一行，且只在「剪贴板里确实有本模组配置」时加）。
     *
     * <p>为什么单独一个方法、而不写在 {@link #onItemTooltip} 里：</p>
     * <ol>
     *     <li><b>性能口径不变</b>：NBT 读取仍然<b>不在</b>每帧都走的那个方法体里；本方法只在关闭状态
     *     被调用，且只做「有没有 {@code rs_create_compat:bus_config} 这一段」这一个 O(1) 判断；</li>
     *     <li>开启状态下的行为一个字都不变（走的仍是身份缓存那条路）。</li>
     * </ol>
     */
    private static void addDisabledHint(final ItemTooltipEvent event) {
        final ItemStack stack = event.getItemStack();
        if (stack == null || stack.isEmpty() || !AllBlocks.CLIPBOARD.isIn(stack)) {
            return;
        }
        final ClipboardContent content = stack.get(AllDataComponents.CLIPBOARD_CONTENT);
        if (content == null) {
            return;
        }
        final CompoundTag copied = content.copiedValues().orElse(null);
        if (copied == null || !copied.contains(RsccBusClipboard.clipboardKey(), Tag.TAG_COMPOUND)) {
            return; // 这份剪贴板没有本模组的配置：关掉开关时连提示都不加（别人的剪贴板不受影响）
        }
        event.getToolTip().add(Component.translatable(KEY_HINT_OFF).withStyle(ChatFormatting.DARK_GRAY));
    }

    /**
     * 取这份剪贴板要<b>常显</b>的概括行（带身份缓存）。
     * <p>只回答「复制了什么、大致什么模式」：1~2 行，绝不把完整分段列表摊在常显区。
     * 缓存命中时一个 NBT 键都不读；内容实例变了才走 {@link #buildSummary} 解析一次。</p>
     */
    private static List<Component> summaryFor(final ItemStack stack) {
        final ClipboardContent content = stack.get(AllDataComponents.CLIPBOARD_CONTENT);
        if (content == null) {
            return List.of(); // 没有组件（理论上剪贴板物品总有，但不做假设）
        }
        if (content == cachedSummaryContent) {
            return cachedSummaryLines; // 命中：本帧不碰 NBT
        }
        List<Component> built;
        try {
            built = buildSummary(content);
        } catch (final RuntimeException exception) {
            built = List.of(); // 非法 / 别的格式：安全降级为「没有概括行」
        }
        cachedSummaryContent = content;
        cachedSummaryLines = built;
        return built;
    }

    /**
     * 解析一份剪贴板内容 → <b>常显概括</b>（最多两行）。
     *
     * <h2>优先级</h2>
     * <ol>
     *     <li>有本模组总线配置 ⇒ 第一行就是它（输入 / 输出总线 + 过滤 + 模糊 + 自动）；
     *     再有余下的 Create / 别的模组内容 ⇒ 第二行「另有 N 项」；</li>
     *     <li>没有总线配置 ⇒ 机械动力配置一行；还有别的模组内容 ⇒ 第二行「另有 N 项」；</li>
     *     <li>只有别的模组配置 ⇒ 「其他模组配置 · N 项」一行。</li>
     * </ol>
     * <p>字段级的细节（类别小计 / 强制普通 / 每个 Create 段名 / 其他模组段名）<b>不在这里</b>：
     * 它们属于 {@link #linesFor} 的 Shift 详情，避免常显区变回「一长串」。</p>
     */
    private static List<Component> buildSummary(final ClipboardContent content) {
        final CompoundTag copied = content.copiedValues().orElse(null);
        if (copied == null || copied.isEmpty()) {
            return List.of(); // 空剪贴板 / 只有文字页：没有可概括的配置
        }
        final List<Component> lines = new ArrayList<>(2);
        if (copied.contains(RsccBusClipboard.clipboardKey(), Tag.TAG_COMPOUND)) {
            lines.add(summaryBus(copied.getCompound(RsccBusClipboard.clipboardKey())));
            final int rest = countCreateSections(copied) + countOtherKeys(copied);
            if (rest > 0) {
                lines.add(Component.translatable(KEY_SUMMARY_MORE, rest)
                    .withStyle(ChatFormatting.DARK_GRAY));
            }
            return List.copyOf(lines);
        }
        final int create = countCreateSections(copied);
        if (create > 0) {
            lines.add(Component.translatable(KEY_SUMMARY_CREATE, create).withStyle(ChatFormatting.GRAY));
        }
        final int other = countOtherKeys(copied);
        if (other > 0) {
            lines.add(lines.isEmpty()
                ? Component.translatable(KEY_SUMMARY_OTHER, other).withStyle(ChatFormatting.GRAY)
                : Component.translatable(KEY_SUMMARY_MORE, other).withStyle(ChatFormatting.DARK_GRAY));
        }
        return lines.isEmpty() ? List.of() : List.copyOf(lines);
    }

    /**
     * 本模组总线配置的概括行。
     * <p>版本比本模组新 / 种类未知 ⇒ 只诚实地说「格式不识别」（详情里仍有具体原因行），
     * 不猜字段内容 —— 与粘贴侧 {@code acceptsKind} / 详情构建的版本闸门同一口径。</p>
     */
    private static Component summaryBus(final CompoundTag payload) {
        final String kind = payload.getString(RsccBusConfig.KEY_KIND);
        final int version = payload.getInt(RsccBusConfig.KEY_VERSION);
        final boolean importer = RsccBusConfig.KIND_IMPORTER.equals(kind);
        final boolean exporter = RsccBusConfig.KIND_EXPORTER.equals(kind);
        if (version > RsccBusConfig.VERSION || (!importer && !exporter)) {
            return Component.translatable(KEY_SUMMARY_UNKNOWN).withStyle(ChatFormatting.GRAY);
        }
        // 已绑定执行舱 ⇒ 用「总线类型 · 归属 · 类别数 · 自动」这一档（对标 Jade 常显里最有用的两件事）。
        // 未绑定 / 旧载荷 ⇒ 保持只讲配置本身（过滤 / 模糊 / 自动），绝不编造归属。
        final CompoundTag jade = linkedJade(payload);
        if (jade != null) {
            final Component chamber = Component.literal(jade.getString(JADE_CHAMBER));
            final int categories = jadeCategoryCount(jade);
            if (importer) {
                return Component.translatable(KEY_SUMMARY_IMPORTER_LINKED, chamber, categories,
                    state(jade.getBoolean(JADE_AUTO))).withStyle(ChatFormatting.GRAY);
            }
            return Component.translatable(KEY_SUMMARY_EXPORTER_LINKED, chamber, categories)
                .withStyle(ChatFormatting.GRAY);
        }
        final Component filter = summaryFilter(payload);
        final boolean hasFilter = payload.contains(RsccBusConfig.KEY_FILTER, Tag.TAG_COMPOUND);
        final boolean fuzzy = hasFilter
            && payload.getCompound(RsccBusConfig.KEY_FILTER).getBoolean("fm");
        if (!importer) {
            // 输出总线：类别选择在详情里（默认 / 显式勾选），概括只保留「过滤 + 模糊」两个特征。
            return Component.translatable(KEY_SUMMARY_EXPORTER, filter, state(fuzzy))
                .withStyle(ChatFormatting.GRAY);
        }
        final boolean auto = payload.getBoolean(RsccBusConfig.KEY_AUTO);
        return Component.translatable(KEY_SUMMARY_IMPORTER, filter, state(fuzzy), state(auto))
            .withStyle(ChatFormatting.GRAY);
    }

    /**
     * 取「<b>已绑定执行舱</b>」的 Jade 子载荷；未绑定 / 旧载荷 / 被篡改 ⇒ {@code null}。
     *
     * <p>为什么用 {@link RsccBusJadePayload#hasData} 而不是自己判断完整性：那个方法就是
     * Jade 侧「有没有本模组总线数据」的唯一口径；这里再叠一条 {@link #JADE_LINKED}，
     * 于是「未绑定」与「没有这份数据」都落到同一个 {@code null}，调用方不必分两种写法。</p>
     */
    @Nullable
    private static CompoundTag linkedJade(final CompoundTag payload) {
        if (!RsccBusJadePayload.hasData(payload)) {
            return null;
        }
        final CompoundTag root = payload.getCompound(RsccBusJadePayload.ROOT);
        return root.getBoolean(JADE_LINKED) ? root : null;
    }

    /**
     * 六个分节里一共几个类别（各节 {@code ids} 条数之和）。
     * <p>数据就是 Jade 那一份分组结果，因此这个数字与玩家瞄准总线时看到的分节内容严格一致
     * （不会出现「概括说 3 类、详情只有 2 节」这种自相矛盾）。</p>
     */
    private static int jadeCategoryCount(final CompoundTag root) {
        final ListTag groups = root.getList(JADE_GROUPS, Tag.TAG_COMPOUND);
        int count = 0;
        for (int i = 0; i < groups.size(); i++) {
            count += groups.getCompound(i).getList(JADE_IDS, Tag.TAG_STRING).size();
        }
        return count;
    }

    /** 概括里的过滤器短语（条数 / 空 / 未知三态，与详情 {@link #addFilterLine} 同一判据）。 */
    private static Component summaryFilter(final CompoundTag payload) {
        if (!payload.contains(RsccBusConfig.KEY_FILTER, Tag.TAG_COMPOUND)) {
            return Component.translatable(KEY_SUMMARY_FILTER_UNKNOWN);
        }
        final CompoundTag filter = payload.getCompound(RsccBusConfig.KEY_FILTER);
        final int count = filter.contains("rf", Tag.TAG_COMPOUND) ? filter.getCompound("rf").size() : 0;
        return count <= 0
            ? Component.translatable(KEY_SUMMARY_FILTER_EMPTY)
            : Component.translatable(KEY_SUMMARY_FILTER_COUNT, count);
    }

    /** 概括里的开 / 关（模糊模式、自动模式共用同一对键）。 */
    private static Component state(final boolean on) {
        return Component.translatable(on ? KEY_SUMMARY_ON : KEY_SUMMARY_OFF);
    }

    /** 有内容的 Create 段数（与 {@link #addCreateLines} 同一判据：必须确实是 compound）。 */
    private static int countCreateSections(final CompoundTag copied) {
        int count = 0;
        for (final String key : CREATE_SECTIONS.keySet()) {
            if (copied.contains(key, Tag.TAG_COMPOUND)) {
                count++;
            }
        }
        return count;
    }

    /** 既不是本模组段、也不是已识别的 Create 段的键数（概括的「其他模组配置」）。 */
    private static int countOtherKeys(final CompoundTag copied) {
        int count = 0;
        for (final String key : copied.getAllKeys()) {
            if (key == null || key.isEmpty() || displayedElsewhere(copied, key)) {
                continue;
            }
            count++;
        }
        return count;
    }

    /**
     * 取这份剪贴板要显示的附加行（带身份缓存）。
     * <p><b>缓存命中时一个 NBT 键都不读</b>；只有内容实例变了才走 {@link #build} 解析一次。
     * 异常在这里被兜住：tooltip 绝不允许因为一份来路不明的剪贴板数据而崩。</p>
     */
    private static List<Component> linesFor(final ItemStack stack, final HolderLookup.Provider registries) {
        final ClipboardContent content = stack.get(AllDataComponents.CLIPBOARD_CONTENT);
        if (content == null) {
            return List.of(); // 没有组件（理论上剪贴板物品总有，但不做假设）
        }
        if (content == cachedContent) {
            return cachedLines; // 命中：本帧不碰 NBT（性能判据见 selfcheck_round81）
        }
        List<Component> built;
        try {
            built = build(content, registries);
        } catch (final RuntimeException exception) {
            built = List.of(); // 非法 / 别的格式：安全降级为「没有附加行」
        }
        cachedContent = content;
        cachedLines = built;
        return built;
    }

    /** 解析一份剪贴板内容 → 附加行（只在缓存未命中时调用）。 */
    private static List<Component> build(final ClipboardContent content,
                                         final HolderLookup.Provider registries) {
        final CompoundTag copied = content.copiedValues().orElse(null);
        if (copied == null || copied.isEmpty()) {
            return List.of(); // 空剪贴板 / 只有文字页：没有可显示的配置
        }
        final List<Component> lines = new ArrayList<>();
        if (copied.contains(RsccBusClipboard.clipboardKey(), Tag.TAG_COMPOUND)) {
            addBusLines(lines, copied.getCompound(RsccBusClipboard.clipboardKey()));
        }
        try {
            addCreateLines(lines, copied, registries);
        } catch (final RuntimeException ignored) {
            // 某一段 Create 数据坏了（或物品栈解析失败）：只丢这一段，已经解析出的总线行照旧显示
        }
        addOtherLines(lines, copied);
        if (lines.isEmpty()) {
            return List.of();
        }
        lines.add(0, Component.translatable(KEY_HEADER).withStyle(ChatFormatting.GOLD));
        return List.copyOf(lines);
    }

    // ------------------------------------------------------------------
    // 本模组总线配置
    // ------------------------------------------------------------------

    private static void addBusLines(final List<Component> lines, final CompoundTag payload) {
        final String kind = payload.getString(RsccBusConfig.KEY_KIND);
        final int version = payload.getInt(RsccBusConfig.KEY_VERSION);
        if (version > RsccBusConfig.VERSION) {
            // 比本模组认识的版本更新：不猜字段（与粘贴侧 acceptsKind 的版本闸门同一口径）
            lines.add(Component.translatable(KEY_BUS_UNSUPPORTED, version).withStyle(ChatFormatting.GRAY));
            return;
        }
        final boolean importer = RsccBusConfig.KIND_IMPORTER.equals(kind);
        final boolean exporter = RsccBusConfig.KIND_EXPORTER.equals(kind);
        if (!importer && !exporter) {
            lines.add(Component.translatable(KEY_BUS_UNKNOWN, kind).withStyle(ChatFormatting.GRAY));
            return;
        }
        lines.add(Component.translatable(KEY_BUS_BLOCK,
                Component.translatable(importer ? KEY_KIND_IMPORTER : KEY_KIND_EXPORTER))
            .withStyle(ChatFormatting.GRAY));
        // 来源总线：与 Jade 完全同一批行（归属 / 自动 / 方向 / 六个分节），见 addJadeLines。
        addJadeLines(lines, payload);
        addFilterLine(lines, payload);
        addFilterItemsLine(lines, payload);
        if (importer) {
            addImporterLines(lines, payload);
        } else {
            addExporterLines(lines, payload);
        }
        lines.add(Component.translatable(payload.getBoolean(RsccBusConfig.KEY_FORCE_NORMAL)
            ? KEY_FORCE_ON : KEY_FORCE_OFF).withStyle(ChatFormatting.GRAY));
    }

    /**
     * <b>来源总线状态</b>：直接把 Jade 那一份载荷展开成行（归属执行舱 → 自动模式 → 方向 →
     * 六个分节及代表物；一个类别都没有时是「当前没有负责的类别」）。
     *
     * <p><b>为什么是「原样展开」而不是「照着抄一份」</b>：用户要求「至少对标 Jade 的信息量」。
     * 抄一份的下场是两份分类口径迟早漂移（同一个类别在 Jade 里算「原料」、在剪贴板里算「输入时原料」），
     * 而那正是最难查的一类 bug。这里调用的是 Jade 客户端<b>同一个</b> {@link RsccBusJadePayload#lines}，
     * 数据来自复制时写入的<b>同一份</b> {@link RsccBusJadePayload#write} 载荷 —— 一份定义、两处显示。</p>
     *
     * <p><b>未绑定 ⇒ 一行都不加</b>：与 Jade 第 79 轮的口径逐字一致（未绑定时不显示
     * 「归属执行舱 / 方向 / 当前没有负责的类别」这些绑定后才有意义的东西）。</p>
     */
    private static void addJadeLines(final List<Component> lines, final CompoundTag payload) {
        if (linkedJade(payload) == null) {
            return;
        }
        final List<Component> jade = RsccBusJadePayload.lines(payload);
        if (jade.isEmpty()) {
            return;
        }
        lines.add(Component.translatable(KEY_DETAILS_SOURCE).withStyle(ChatFormatting.DARK_AQUA));
        for (final Component line : jade) {
            // 统一灰：Jade 的行本身没有样式，直接放进 tooltip 会是刺眼的纯白，与上面几行不成套。
            lines.add(line.copy().withStyle(ChatFormatting.GRAY));
        }
    }

    /** 过滤器：条数取 RS 自己写下的 {@code rf} 子标签的键数（{@code ResourceContainerImpl#toTag} 只写非空槽）。 */
    private static void addFilterLine(final List<Component> lines, final CompoundTag payload) {
        if (!payload.contains(RsccBusConfig.KEY_FILTER, Tag.TAG_COMPOUND)) {
            // 没有过滤段（旧 / 损坏载荷）：宁可明说「未知」，也不假装「空过滤器」。
            lines.add(Component.translatable(KEY_SUMMARY_FILTER_UNKNOWN).withStyle(ChatFormatting.GRAY));
            return;
        }
        final CompoundTag filter = payload.getCompound(RsccBusConfig.KEY_FILTER);
        final int count = filter.contains("rf", Tag.TAG_COMPOUND) ? filter.getCompound("rf").size() : 0;
        final Component fuzzy = Component.translatable(filter.getBoolean("fm") ? KEY_FUZZY_ON : KEY_FUZZY_OFF);
        // 第 87 轮：空过滤器也要带模糊状态 —— 模糊模式是逐条的属性，空过滤时它仍然是一个有效的玩家配置。
        lines.add(count <= 0
            ? Component.translatable(KEY_FILTER_EMPTY, fuzzy).withStyle(ChatFormatting.GRAY)
            : Component.translatable(KEY_FILTER_COUNT, count, fuzzy).withStyle(ChatFormatting.GRAY));
    }

    /**
     * <b>过滤条目</b>：把 RS 过滤器里实际配着的物品 / 流体名列出来（最多 {@link #MAX_FILTER_NAMES} 个）。
     *
     * <p><b>为什么值得单独一行</b>：用户要的是「对标 Jade 的信息量」，而过滤槽是总线配置里最常改、
     * 也最难凭记忆核对的一栏 —— 只给条数（上一版）等于让人去开界面数。Jade 不显示过滤条目
     * （它讲的是「这条总线此刻负责什么」），这里补上这一栏，信息量只会比 Jade 多、不会少。</p>
     *
     * <p><b>解析口径</b>：{@code rf} 是 RS 自己的 {@code ResourceContainer} 落盘格式，每个非空槽是
     * {@code s<index>} → {@code ResourceAmount} 复合标签（{@code ResourceCodecs.AMOUNT_CODEC}）。
     * 客户端不重建 RS 的资源类型系统，只在槽标签里找 {@code item} / {@code fluid} 这两个注册名
     * （深度受限的搜索，RS 在里面再套一层 {@code resource} 也照样能读到）。这是<b>只读显示</b>：
     * 解析不出名字的槽不占名额、也不影响粘贴 —— 粘贴走的仍是 RS 自己的 {@code filter.load}。</p>
     */
    private static void addFilterItemsLine(final List<Component> lines, final CompoundTag payload) {
        if (!payload.contains(RsccBusConfig.KEY_FILTER, Tag.TAG_COMPOUND)) {
            return; // 没有过滤段：上一行已经说了「未知」
        }
        final CompoundTag filter = payload.getCompound(RsccBusConfig.KEY_FILTER);
        if (!filter.contains("rf", Tag.TAG_COMPOUND)) {
            return; // 空过滤器：没有条目可列
        }
        final CompoundTag resources = filter.getCompound("rf");
        if (resources.isEmpty()) {
            return;
        }
        final List<Component> names = new ArrayList<>(MAX_FILTER_NAMES);
        int hidden = 0;
        for (final String key : sortedSlotKeys(resources)) {
            final Component name = resourceName(resources.getCompound(key));
            if (name == null) {
                continue;
            }
            if (names.size() < MAX_FILTER_NAMES) {
                names.add(name);
            } else {
                hidden++;
            }
        }
        if (names.isEmpty()) {
            return;
        }
        lines.add(Component.translatable(KEY_FILTER_ITEMS, join(names)).withStyle(ChatFormatting.GRAY));
        if (hidden > 0) {
            lines.add(Component.translatable(KEY_FILTER_ITEMS_MORE, hidden).withStyle(ChatFormatting.GRAY));
        }
    }

    /** 槽位键按数字序排（{@code s2} 在 {@code s10} 之前）；解析不出数字的排在最后（保持字典序、稳定）。 */
    private static List<String> sortedSlotKeys(final CompoundTag resources) {
        final List<String> keys = new ArrayList<>(resources.getAllKeys());
        keys.removeIf(key -> key == null || key.length() < 2 || key.charAt(0) != 's');
        keys.sort(Comparator.comparingInt(ClipboardTooltipHandler::slotIndex));
        return keys;
    }

    /** {@code s12} → 12；非数字后缀 → {@link Integer#MAX_VALUE}（排到最后，不抛异常）。 */
    private static int slotIndex(final String key) {
        try {
            return Integer.parseInt(key.substring(1));
        } catch (final NumberFormatException ignored) {
            return Integer.MAX_VALUE;
        }
    }

    /**
     * 一个过滤槽 → 显示名（物品名，或流体类型描述）；解析不出返回 {@code null}（不画、不占名额）。
     * <p>与 {@link RsccBusJadePayload} 的代表物解析同一套客户端注册表来源（{@code BuiltInRegistries}）。
     */
    @Nullable
    private static Component resourceName(final CompoundTag slot) {
        final String itemId = stringValue(slot, "item", 2);
        if (itemId != null) {
            final ResourceLocation key = ResourceLocation.tryParse(itemId);
            final Item item = key == null ? null : BuiltInRegistries.ITEM.get(key);
            if (item != null && item != Items.AIR) {
                return new ItemStack(item).getHoverName();
            }
        }
        final String fluidId = stringValue(slot, "fluid", 2);
        if (fluidId != null) {
            final ResourceLocation key = ResourceLocation.tryParse(fluidId);
            final Fluid fluid = key == null ? null : BuiltInRegistries.FLUID.get(key);
            if (fluid != null && fluid != Fluids.EMPTY) {
                return fluid.getFluidType().getDescription();
            }
        }
        return null;
    }

    /**
     * 在复合标签里找某个<b>字符串</b>键（深度受限；先看本层，再递归子复合标签）。
     * <p>为什么不用固定路径：RS 的 {@code ResourceAmount} 在 2.0.x 里已经是
     * {@code resource: { ... }} 这种可扩展结构，固定路径会在小版本里静默断掉；
     * 而注册名键名（{@code item} / {@code fluid}）是资源类型的稳定契约。</p>
     */
    @Nullable
    private static String stringValue(final CompoundTag tag, final String name, final int depth) {
        if (tag == null) {
            return null;
        }
        if (tag.contains(name, Tag.TAG_STRING)) {
            return tag.getString(name);
        }
        if (depth <= 0) {
            return null;
        }
        for (final String key : tag.getAllKeys()) {
            if (tag.contains(key, Tag.TAG_COMPOUND)) {
                final String found = stringValue(tag.getCompound(key), name, depth - 1);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** 输入总线：自动收回开关 + 类别（自动模式下类别不参与判定，见 Importer 的语义）。 */
    private static void addImporterLines(final List<Component> lines, final CompoundTag payload) {
        final boolean auto = payload.getBoolean(RsccBusConfig.KEY_AUTO);
        lines.add(Component.translatable(auto ? KEY_AUTO_ON : KEY_AUTO_OFF).withStyle(ChatFormatting.GRAY));
        if (auto) {
            lines.add(Component.translatable(KEY_CATEGORIES_AUTO).withStyle(ChatFormatting.GRAY));
            return;
        }
        lines.add(categoryLine(RsccBusConfig.readCategories(payload)));
    }

    /** 输出总线：显式勾选过就列计数，从未勾过就是「默认全选输入性产物」。 */
    private static void addExporterLines(final List<Component> lines, final CompoundTag payload) {
        if (!payload.getBoolean(RsccBusConfig.KEY_EXPLICIT)) {
            lines.add(Component.translatable(KEY_CATEGORIES_DEFAULT).withStyle(ChatFormatting.GRAY));
            return;
        }
        lines.add(categoryLine(RsccBusConfig.readCategories(payload)));
    }

    /** 类别行：总数 + 按族分组的小计（不把一长串内部 id 直接糊到玩家脸上）。 */
    private static Component categoryLine(final List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Component.translatable(KEY_CATEGORIES_EMPTY).withStyle(ChatFormatting.GRAY);
        }
        int input = 0;
        int fluid = 0;
        int intermediate = 0;
        int result = 0;
        int scrap = 0;
        int other = 0;
        int counted = 0;
        for (final String id : ids) {
            if (id == null || id.isEmpty()) {
                continue;
            }
            counted++;
            if (id.startsWith(RsccBusCategory.INPUT_PREFIX)) {
                input++;
            } else if (id.startsWith(RsccBusCategory.FLUID_PREFIX)) {
                fluid++;
            } else if (RsccBusCategory.INTERMEDIATE.equals(id)
                || id.startsWith(RsccBusCategory.INTERMEDIATE_PREFIX + ":")) {
                intermediate++;
            } else if (id.startsWith(RsccBusCategory.RESULT_PREFIX)) {
                result++;
            } else if (id.startsWith(RsccBusCategory.SCRAP_PREFIX)) {
                scrap++;
            } else {
                other++; // 未来新增的类别：照样计数，不丢
            }
        }
        final List<Component> groups = new ArrayList<>(6);
        if (input > 0) {
            groups.add(Component.translatable(KEY_GROUP_INPUT, input));
        }
        if (fluid > 0) {
            groups.add(Component.translatable(KEY_GROUP_FLUID, fluid));
        }
        if (intermediate > 0) {
            groups.add(Component.translatable(KEY_GROUP_INTERMEDIATE, intermediate));
        }
        if (result > 0) {
            groups.add(Component.translatable(KEY_GROUP_RESULT, result));
        }
        if (scrap > 0) {
            groups.add(Component.translatable(KEY_GROUP_SCRAP, scrap));
        }
        if (other > 0) {
            groups.add(Component.translatable(KEY_GROUP_OTHER, other));
        }
        return Component.translatable(KEY_CATEGORIES_COUNT, counted, join(groups))
            .withStyle(ChatFormatting.GRAY);
    }

    // ------------------------------------------------------------------
    // 机械动力的复制格式
    // ------------------------------------------------------------------

    /**
     * Create 的配置段（{@code ClipboardCloneable} 写进 {@code copied_values} 的那几段）。
     * <p>只认「确实是 compound 的已知段」；别的段（包括别的模组的）由 {@link #addOtherLines} 兜底。
     */
    private static void addCreateLines(final List<Component> lines, final CompoundTag copied,
                                       final HolderLookup.Provider registries) {
        final List<Component> sections = new ArrayList<>();
        for (final Map.Entry<String, String> entry : CREATE_SECTIONS.entrySet()) {
            final String key = entry.getKey();
            if (!copied.contains(key, Tag.TAG_COMPOUND)) {
                continue; // 段不存在 / 类型不是 compound：不硬解
            }
            sections.add(createSection(key, copied.getCompound(key), registries));
        }
        if (sections.isEmpty()) {
            return;
        }
        lines.add(Component.translatable(KEY_CREATE_HEADER, join(sections)).withStyle(ChatFormatting.GRAY));
    }

    /** 单个 Create 段 → 可读名字；过滤段尽量带上过滤物品的名字。 */
    private static Component createSection(final String key, final CompoundTag tag,
                                           final HolderLookup.Provider registries) {
        if (!"Filtering".equals(key)) {
            return Component.translatable(CREATE_SECTIONS.get(key));
        }
        final ItemStack filter = tag.contains("Filter", Tag.TAG_COMPOUND) && registries != null
            ? ItemStack.parseOptional(registries, tag.getCompound("Filter"))
            : ItemStack.EMPTY;
        return filter.isEmpty()
            ? Component.translatable(KEY_CREATE_FILTERING)
            : Component.translatable(KEY_CREATE_FILTERING_ITEM, filter.getHoverName());
    }

    // ------------------------------------------------------------------
    // 别的模组
    // ------------------------------------------------------------------

    /**
     * 本模组与 Create 之外的段：原样列出键名（最多 {@link #MAX_OTHER_KEYS} 个），其余折成「另有 N 项」。
     * <p>这些键的含义本模组无从得知，所以<b>不解释内容</b>，只如实列出「剪贴板里还带了哪几段」。
     * 这既覆盖了别的模组的 {@code ClipboardCloneable} 数据，也覆盖 Create 将来新增的段名。</p>
     */
    private static void addOtherLines(final List<Component> lines, final CompoundTag copied) {
        final TreeSet<String> shown = new TreeSet<>();
        int hidden = 0;
        for (final String key : copied.getAllKeys()) {
            if (key == null || key.isEmpty() || displayedElsewhere(copied, key)) {
                continue;
            }
            if (shown.size() < MAX_OTHER_KEYS) {
                shown.add(key);
            } else {
                hidden++;
            }
        }
        if (shown.isEmpty()) {
            return;
        }
        lines.add(Component.translatable(KEY_OTHER_HEADER, joinNames(shown)).withStyle(ChatFormatting.GRAY));
        if (hidden > 0) {
            lines.add(Component.translatable(KEY_OTHER_MORE, hidden).withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    /** 这一段是否已经由上面两类显示过（本模组段 / 已知且确实是 compound 的 Create 段）。 */
    private static boolean displayedElsewhere(final CompoundTag copied, final String key) {
        if (RsccBusClipboard.clipboardKey().equals(key)) {
            return true;
        }
        return copied.contains(key, Tag.TAG_COMPOUND) && CREATE_SECTIONS.containsKey(key);
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    /** 把若干段用「 · 」拼起来（分隔符刻意用纯 ASCII，中英文都读得顺）。 */
    private static MutableComponent join(final List<Component> parts) {
        final MutableComponent out = Component.empty();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                out.append(Component.literal(" · "));
            }
            out.append(parts.get(i));
        }
        return out;
    }

    /** 段名（字符串）用逗号拼起来；这里只用于「其他模组配置」一行。 */
    private static Component joinNames(final Set<String> names) {
        final MutableComponent out = Component.empty();
        boolean first = true;
        for (final String name : names) {
            if (!first) {
                out.append(Component.literal(", "));
            }
            out.append(Component.literal(name));
            first = false;
        }
        return out;
    }
}
