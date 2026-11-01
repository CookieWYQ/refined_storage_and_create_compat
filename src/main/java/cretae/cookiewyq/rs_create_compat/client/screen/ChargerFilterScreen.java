package cretae.cookiewyq.rs_create_compat.client.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import cretae.cookiewyq.rs_create_compat.client.PinyinSearch;
import cretae.cookiewyq.rs_create_compat.client.widget.GhostMarkerRenderer;
import cretae.cookiewyq.rs_create_compat.client.widget.McGui;
import cretae.cookiewyq.rs_create_compat.client.widget.RsccTextFit;
import cretae.cookiewyq.rs_create_compat.client.widget.SptScrollbarWidget;
import cretae.cookiewyq.rs_create_compat.network.SetChargerFilterPacket;
import cretae.cookiewyq.rs_create_compat.network.SyncChargerFilterPacket;
import cretae.cookiewyq.rs_create_compat.support.ChargerFilterLimits;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 范围充电器「充电过滤」子界面：<b>黑/白名单 + 4 类可充电对象</b>
 * （掉落物 / 玩家身上物品 / 世界中的方块 / 玩家）。
 *
 * <h2>用户要求（第 98 轮，唯一口径）</h2>
 * <p>用户原话：「<i>再加上一个按钮黑名单或白名单之类的……首先它会列出（对象），首先分成几类……
 * 根据这几类把他们所属的那些所有能充电的……可以阻止或单独允许这一些对象进行充电</i>」，
 * 并明确「<b>不需要添加什么高级版本，全在一个版本中完成</b>」。因此本界面就是<b>唯一</b>入口：
 * <ul>
 *     <li><b>一个全局模式开关</b>（{@link #whitelist}）：黑名单 = 勾选的<b>不</b>充，白名单 =
 *     <b>只</b>充勾选的。四类共用同一个开关（用户定案，不做「每类一个模式」）；</li>
 *     <li><b>四个页签</b>：掉落物 / 玩家身上物品 / 世界中的方块 / 玩家。前三页列的是
 *     「能充电的物件」（前两页候选池见 {@link ChargerFilterCandidates}，各页独立勾选；方块页的
 *     候选池来自服务端快照的 {@code seenBlocks}）；玩家页列的是「本存档进过服的玩家」+
 *     「手动加的名字 / UUID」；</li>
 *     <li><b>即时生效</b>：每次勾选 / 添加都立刻发 {@link SetChargerFilterPacket}（<b>全量</b>快照），
 *     服务端整体覆盖方块实体里的 {@code ChargingFilter} —— 因此没有「确定 / 取消」的语义差，
 *     底部按钮只是「完成 / 返回」（Esc 也返回）。</li>
 * </ul>
 *
 * <h2>第 110 轮：新增「世界中的方块」页签（用户原话）</h2>
 * <p>「<i>你现在有几个类别玩家身上的（物品）以及掉落物，但是还有一个东西你忘了：世界中的方块，
 * 就是放在地上的方块，比如控制器、或者说放在地上的能量立方。这个东西你并没有列出来，
 * 这个类别也是很重要的。</i>」—— 方块充电功能本来就有（{@code Config.rangeChargerChargeBlocks}），
 * 缺的是「在界面上作为一类目标被列出 / 被拦 / 被放行」。因此本轮：</p>
 * <ul>
 *     <li>页签从 3 个变 4 个，单签宽从 74 压到 56（4×56 + 3×2 = 230 ≤ 面板内沿 232；见
 *     {@link #TAB_W}），<b>页签文案一个都没改</b>；</li>
 *     <li>方块行的身份是<b>方块注册名</b>（{@code BuiltInRegistries.BLOCK}），图标则用「方块物品」
 *     （{@code block.asItem()}）—— 后者只是给人认的图标，判定与存储都与它无关；</li>
 *     <li>该页候选来自服务端（本机范围内检测到的可充方块），并且<b>已配置但不在候选池</b>的条目
 *     也会列出来 —— 否则拉黑之后就取消不了勾选（详见 {@link #rebuildBlockRows}）。</li>
 * </ul>
 *
 * <h2>为什么客户端持有可编辑副本（乐观更新）</h2>
 * <p>服务端收到 {@link SetChargerFilterPacket} 后<b>不回包</b>（契约 §2：服务端权威、客户端乐观）。
 * 若界面每次勾选都等服务端回快照，点击会「先没反应、稍后跳一下」；因此这里把收到的快照复制进
 * 本地集合、勾选只改本地并顺手发包 —— 界面永远立刻响应，而<b>权威仍只有服务端一份</b>
 * （下次打开界面时以新快照为准）。</p>
 *
 * <h2>第 99 轮补：名单硬上限 + 32 位裸 UUID（用户反馈「第三个改了他」）</h2>
 * <p>第 98 轮交付时如实列了两条已知限制，本轮都改掉：</p>
 * <ul>
 *     <li><b>条数上限</b>：上限数字只有一个出处 {@link ChargerFilterLimits}（网络编解码 / 存档 /
 *     {@code ChargingFilter} 清洗 / 本界面四处共用）。界面在「加不进去」时会写
 *     {@link #addStatus} 显示「名单已满（最多 N 条）」，<b>不会出现「点了没反应」</b>，
 *     也不会出现「界面亮着、服务端没存」这种下次打开又变回去的错位；</li>
 *     <li><b>32 位无横线 UUID</b>：{@link ChargerFilterLimits#parsePlayerId} 同时接受
 *     36 字符带横线与 32 字符裸十六进制，且只在「长度对 + 全是十六进制」时才认 UUID ——
 *     因此它是「认得出 UUID」而不是「把名字猜成 UUID」。</li>
 * </ul>
 *
 * <h2>几何（契约 §5.2 逐字照做；全部面板内相对坐标）</h2>
 * <pre>
 *  面板 252×262
 *  (10, 8)   标题「充电过滤」
 *  (10, 26) 100×16 模式按钮（黑名单 / 白名单）      (114, 26) 92×16 搜索框（玩家页隐藏）
 *  (10, 46) 提示行（模式说明 / 添加失败原因），宽 ≤ 232，单行（放不下整体缩字号，绝不补省略号）
 *  (10, 60) 74×16 起、间隔 2：三个页签           （10/86/162 → 右缘 236）
 *  (10, 80) 232×144 列表（行高 18 ⇒ 8 行/页）     (232, 80) 10×144 滚动条（贴列表内右侧）
 *  (10, 228) 150×16 添加输入框                    (164, 228) 42×16 「添加」（仅玩家页）
 *  (10, 244) 108×16 「完成」                      (134, 244) 108×16 「返回」
 * </pre>
 * <p><b>为什么列表右侧留 10px 给滚动条</b>：与 {@code BusCategoryConfigScreen} 同一做法
 * （列表矩形包含滚动条轨道，滚动条压在列表内的最右 10px）—— 行内容右边界因此收到
 * {@code 232 − 10 − 2 = 220}，<b>文字永远不会压在滚动条下</b>（自检里有几何断言）。</p>
 *
 * <h2>渲染硬规则</h2>
 * <ul>
 *     <li><b>不新增任何贴图</b>：面板走基类 {@link ChildConfigScreen} 的原版九宫格；
 *     勾选框 / 行悬停是 {@code fill} 现画；物品图标用原版 {@code renderItem}，
 *     外框复用 {@link McGui#slotFrame}（本地素材）；<b>玩家头像是真实皮肤纹理裁出来的头</b>
 *     （见下方「第 100 轮」——不再画任何五官色块）；</li>
 *     <li>所有文字 {@code dropShadow = false}；</li>
 *     <li>所有可交互元素（模式按钮 / 搜索框 / 页签 / 列表行 / 滚动条 / 添加框 / 添加 / 完成 / 返回 /
 *     提示行）都有<b>手动渲染</b>的 tooltip（本模组 GUI 不会自动渲染 tooltip），
 *     且 hover 判定与绘制范围共用同一批几何常量。</li>
 * </ul>
 *
 * <h2>第 100 轮：玩家头像改成「真实皮肤裁出来的头」（用户实机反馈）</h2>
 * <p>用户原话：「<i>你这个什么雷霆诡异的这个就玩家这个白名单，他这个头像什么鬼啊？你直接用玩家的那个……
 * 应该就是皮肤裁剪的那个头部不就行了吗？……你这个自己画的太诡异了。</i>」—— 于是本轮把第 98 轮那个
 * 「肤色底 + 头发 + 两只眼睛」的 {@code fill} 占位头像<b>整体删除</b>，改成从皮肤图裁头：</p>
 * <ul>
 *     <li><b>像素来源与物品栏头像同一套</b>：{@link PlayerFaceRenderer#draw(GuiGraphics, ResourceLocation,
 *     int, int, int)}（原版 1.21.1 API，已 {@code javap} 核过）：从皮肤图 {@code (8,8)} 取 8×8 头部正面、
 *     再叠 {@code (40,8)} 的帽子层，绘制尺寸 16×16 ⇒ 2 倍放大（原版在 8×8 的 64×64 皮肤图上取的像素
 *     与物品栏/皮肤预览完全一致，没有任何自绘画法）；</li>
 *     <li><b>在线玩家</b>：{@code getConnection().getPlayerInfo(uuid)} → {@link PlayerInfo#getSkin()} →
 *     {@link PlayerSkin#texture()}；<b>离线 / 本客户端没有他的 PlayerInfo / 名字条目</b>：走
 *     {@link DefaultPlayerSkin#get(UUID)}（1.21.1 真实方法名，返回 PlayerSkin；没有
 *     {@code getDefaultSkin} 这个名字）拿默认皮肤（Steve/Alex 家族，18 选 1）同样裁头；</li>
 *     <li><b>拿不到纹理时只画中性灰底 + 槽框</b>（{@code COLOR_HEAD_NEUTRAL}），
 *     <b>绝不再出现眼睛 / 头发色块</b>——宁可朴素，也不要「诡异」；</li>
 *     <li><b>严格客户端 + 按 UUID 缓存</b>：皮肤查询只在「行重建」（切页签 / 收快照 / 勾选 / 添加）
 *     和「每 20 tick 一次的低频兜底」时发生，结果落在 {@link #headTextures} 快照里；
 *     {@code render} 里只有一次 {@link HashMap#get}，<b>绝不每帧调连接 / 皮肤管理器</b>
 *     （皮肤是异步加载的，低频兜底只为让「界面开着的时候皮肤才加载好」这种情况也能更新）；</li>
 *     <li><b>在线状态不再画在头上</b>：原来左上角那块 3×3 绿角标会把真脸的眼睛盖掉；
 *     现在改由行尾「在线 / 离线」文字<b>变绿</b>表达（信息没少，像素不再压脸）。</li>
 * </ul>
 *
 * <h2>第 125 轮：多颜色变体的「合并显示 + 展开」（用户口述 3-4）</h2>
 * <p>用户原话：「<i>有线模组比如说精致存储里面它会有很多颜色嘛……这一些东西你就统一的合并显示。
 * 什么叫合并显示呢？就是有个展开，你就搞一个总的比如说什么控制器，然后展开之后呢，
 * 就不同的颜色把它分在一起放在一起，否则的话太难看了。</i>」</p>
 * <ul>
 *     <li><b>分组模型不在这层</b>：16 色词表 / 注册名归一化 / 「≥ 3 个成员才折叠」全部在
 *     {@link ChargerFilterCandidates}（{@code colorOf} / {@code familyKey} /
 *     {@link ChargerFilterCandidates#familyOf}），界面<b>只消费</b> {@code Family}。
 *     这样「哪些条目算一族」只有一处判据，物品页两个页签（掉落物 / 玩家身上物品）也不会各写一套；</li>
 *     <li><b>组头行</b>：{@code [三态勾选框] [代表图标] ▶/▼ 名字（N 色）   3/16}。
 *     点勾选框 = 整组全选 / 全不选（一条网络包，批量增删同一批物品 id）；
 *     点组头其它任何位置 = 展开 / 折叠。<b>三态</b>：全选 / 部分 / 无，部分态画一条绿色横杠；</li>
 *     <li><b>成员行</b>：整行内容（勾选框 + 图标 + 名字）缩进 {@link #GROUP_INDENT} px，
 *     顺序<b>严格</b>跟 {@link ChargerFilterCandidates#COLOR_ORDER}（白→黑，默认变体最前），
 *     绝不按字母序（字母序会把黑排到白前面）；</li>
 *     <li><b>默认收起</b>（这才是「合并显示」的意义）；展开状态按家族键记，切页签 / 收快照不丢；</li>
 *     <li><b>搜索</b>：查询非空时命中的组<b>自动展开</b>（否则搜到的东西还藏在组里 = 搜了个寂寞），
 *     并只显示命中的成员；<b>只命中成员、组名本身没命中</b>时保留组头并给一行淡绿色高亮，
 *     让玩家知道「这个组是因为里面的东西被搜到才出现的」；</li>
 *     <li><b>已选集合语义一个字没改</b>：仍然是「物品 id 的 Set」，组操作只是批量 add / remove，
 *     网络包、服务端判定、存档格式全部不动（见 {@link #toggleGroupSelection}）；</li>
 *     <li><b>四个页签不受影响</b>：分组只发生在物品两页（方块页候选来自服务端快照，玩家页按人整名拦）。</li>
 * </ul>
 */
public class ChargerFilterScreen extends ChildConfigScreen {
    /** 本界面全部语言键的前缀（与语言文件逐字一致）。 */
    private static final String LANG = "gui.rs_create_compat.charger_filter.";

    // ==================== 几何（面板内相对坐标；与类注释的图逐字对应） ====================
    public static final int PANEL_W = 252;
    public static final int PANEL_H = 262;

    private static final int TITLE_X = 10;
    private static final int TITLE_Y = 8;

    /** 模式按钮（黑/白名单）：(10, 26) 100×16 —— 点击即全局切换。 */
    private static final int MODE_X = 10;
    private static final int MODE_Y = 26;
    private static final int MODE_W = 100;
    private static final int MODE_H = 16;
    /** 搜索框：(114, 26) 92×16（玩家页隐藏；与模式按钮间隔 4px）。 */
    private static final int SEARCH_X = 114;
    private static final int SEARCH_Y = 26;
    private static final int SEARCH_W = 92;
    private static final int SEARCH_H = 16;
    /** 提示行：(10, 46)；宽度上限 232（面板右内沿 242 − 10），单行截断。 */
    private static final int HINT_X = 10;
    private static final int HINT_Y = 46;
    private static final int HINT_MAX_W = 232;
    /** 页签：(10, 60) 56×16 起、间隔 2 ⇒ 10/68/126/184，右缘 240（面板内沿 242，留 2px）。 */
    private static final int TAB_X = 10;
    private static final int TAB_Y = 60;
    /**
     * 单个页签宽（第 110 轮由 74 压到 56）。
     *
     * <p><b>为什么要压</b>：第 110 轮新增「方块」页签（用户：世界中的方块这一类也被漏了），
     * 页签从 3 个变 4 个。面板宽度是契约固定的 252（内沿 242），4×74 + 3×2 = 302 装不下，
     * 因此必须压缩单签宽度：{@code 4×56 + 3×2 = 230 ≤ 232}，右缘 240 &lt; 242。
     * 中文页签最长的是「玩家身上物品」（6 个全角字 ≈ 54px），56px 仍放得下（左右各 1px），
     * 所以<b>没有为了塞下按钮去改任何页签文案</b>。</p>
     */
    private static final int TAB_W = 56;
    private static final int TAB_H = 16;
    private static final int TAB_GAP = 2;
    /** 页签个数（= {@link Tab#values()}.length，第 110 轮 3 → 4）。 */
    private static final int TAB_COUNT = 4;
    /** 列表：(10, 80) 232×144；行高 18 ⇒ 8 行/页。 */
    private static final int LIST_X = 10;
    private static final int LIST_Y = 80;
    private static final int LIST_W = 232;
    private static final int LIST_H = 144;
    private static final int ROW_H = 18;
    private static final int VISIBLE_ROWS = LIST_H / ROW_H;
    /** 滚动条：(232, 80) 10×144 —— 贴列表内右侧（列表右缘 242 = 232+10）。 */
    private static final int SCROLL_X = LIST_X + LIST_W - 10;
    private static final int SCROLL_W = 10;
    /** 玩家页「添加」输入框 / 按钮：(10, 228) 150×16 / (164, 228) 42×16。 */
    private static final int ADD_X = 10;
    private static final int ADD_Y = 228;
    private static final int ADD_W = 150;
    private static final int ADD_H = 16;
    private static final int ADD_BTN_X = 164;
    private static final int ADD_BTN_W = 42;
    /** 底部「完成」/「返回」：(10, 244) 与 (134, 244)，各 108×16（右缘 242）。 */
    private static final int DONE_X = 10;
    private static final int DONE_Y = 244;
    private static final int DONE_W = 108;
    private static final int BTN_H = 16;
    private static final int BACK_X = 134;
    private static final int BACK_W = 108;

    // ---------- 行内几何（相对列表左上角；绘制与命中共用） ----------
    /** 9×9 勾选框：局部 x = 4。 */
    private static final int ROW_CHECK_X = 4;
    private static final int ROW_CHECK_SIZE = 9;
    /** 16×16 图标（物品图标 / 玩家头像）：局部 x = 18（外框画在 17）。 */
    private static final int ROW_ICON_X = 18;
    private static final int ROW_ICON_SIZE = 16;
    /** 名字起点（局部 x = 38 = 图标右缘 34 + 4px）。 */
    private static final int ROW_NAME_X = 38;
    /** 行尾灰字（id / 在线状态）的右边界（局部）：滚动条左缘 222 − 2px。 */
    private static final int ROW_SUFFIX_RIGHT = SCROLL_X - LIST_X - 2;
    /** 单行文字高（原版字体 9px）。 */
    private static final int LINE_H = 9;
    /** 名字与后缀之间至少留 4px，绝不重叠。 */
    private static final int NAME_SUFFIX_GAP = 4;

    // ---------- 第 125 轮：折叠组的行内几何 ----------
    /**
     * 组内成员行相对组头行的缩进（px）：<b>整行内容</b>（勾选框 + 图标 + 名字）一起右移，
     * 层级一眼可见。
     *
     * <p>为什么是 6：勾选框 9×9 画在局部 x=4，右移到 10 之后仍与列表外框留 1px；图标从 18 到 24、
     * 名字从 38 到 44，都还在「后缀右缘 220 − 名字起点」这个既有宽度预算内（最长的中文物品名
     * 也不会因此被压到缩字号下限以下，见 {@link RsccTextFit}）。再大就会明显挤压名字宽度了。</p>
     */
    private static final int GROUP_INDENT = 6;

    /**
     * 组头展开 / 折叠箭头（第 125 轮）。
     *
     * <p>用 {@code ▼ / ▶} 这两个字形，与 {@code EnergyAuditScreen}（类型行 / 子行的展开箭头）
     * 完全同一对字符 —— 同一个工程里两种展开箭头比「新造一个 ▸ 字形」更不容易让人误读，
     * 而且这两个字符在该界面上已经实机验证过能正常渲染（不依赖任何新贴图）。</p>
     */
    private static final String GROUP_ARROW_EXPANDED = "▼";
    private static final String GROUP_ARROW_COLLAPSED = "▶";

    // ==================== 配色（深色字压在浅色九宫格面板上，与其它子界面同族） ====================
    private static final int COLOR_TITLE = 0xFF333333;
    private static final int COLOR_TEXT = 0xFF1F1F1F;
    private static final int COLOR_DIM = 0xFF5A5A5A;
    private static final int COLOR_LIST_BG = 0xFFC6C6C6;
    private static final int COLOR_BORDER = 0xFF2B2B2B;
    private static final int COLOR_CHECK_OFF = 0xFFE0E0E0;
    private static final int COLOR_CHECK_ON = 0xFF2E7D32;
    private static final int COLOR_ROW_HOVER = 0x30000000;
    private static final int COLOR_WARN = 0xFF9B2020;
    /**
     * 玩家头像<b>拿不到任何皮肤纹理</b>时的中性灰底（第 100 轮）。
     *
     * <p><b>为什么只剩一个颜色</b>：第 98 轮的 {@code COLOR_HEAD_SKIN / _HAIR / _EYE} 三个色块
     * 画出来的「头」被用户实机否决（原话「自己画的太诡异了」），本轮换成真实皮肤裁头后它们
     * <b>全部删除</b>；这里只保留「连默认皮肤都拿不到」时的灰底（外加槽框），
     * 因此界面上不可能再出现自绘的五官 / 头发像素 —— 自检里把它做成了反例断言。</p>
     */
    private static final int COLOR_HEAD_NEUTRAL = 0xFF7A7A7A;
    /** 在线状态的强调色（现在只用于行尾「在线」文字，不再画在头像上）。 */
    private static final int COLOR_ONLINE = 0xFF2E7D32;
    /**
     * 组头「只命中成员」时的一行高亮底色（第 125 轮）：{@link #COLOR_ONLINE} 的 RGB + 16% 透明度。
     *
     * <p><b>为什么派生而不是新造一个色</b>：本界面 / 本工程子界面的配色是固定的小色族
     * （{@code 深灰文字 / 灰底 / 绿 = 已勾选与在线 / 红 = 警告}），凭空加一种颜色会打破一致性；
     * 绿色在这里的语义也刚好对得上 —— 「搜索在这组里命中了东西」（正向提示），
     * 与「已勾选」同色系但靠低透明度区分层次。</p>
     */
    private static final int COLOR_GROUP_HIT = 0x282E7D32;

    // ---------- 第 125 轮：组头三态勾选框的状态码 ----------
    private static final int CHECK_OFF = 0;
    private static final int CHECK_PARTIAL = 1;
    private static final int CHECK_ON = 2;

    /**
     * 四个页签（顺序 = 掉落物 / 玩家身上物品 / 世界中的方块 / 玩家）。
     *
     * <p>第 110 轮新增 {@link #BLOCKS}：用户反馈「还有一个东西你忘了：世界中的方块，
     * 就是放在地上的方块，比如控制器、或者说放在地上的能量立方」。它排在两个<b>物品</b>页签
     * 之后、玩家页签之前 —— 前三页都是「被充电的物件」（按目标所在位置分类：地上 / 玩家身上 /
     * 世界里），最后一个才是「按人整名拦」。</p>
     */
    private enum Tab {
        DROPPED("tab.dropped", "tab.tip.dropped"),
        CARRIED("tab.carried", "tab.tip.carried"),
        BLOCKS("tab.blocks", "tab.tip.blocks"),
        PLAYERS("tab.players", "tab.tip.players");

        private final String labelKey;
        private final String tipKey;

        Tab(final String labelKey, final String tipKey) {
            this.labelKey = labelKey;
            this.tipKey = tipKey;
        }
    }

    /**
     * 列表里的一行：<b>物品行</b>（{@link #item} 非空）、<b>方块行</b>（{@link #blockId} 非空）、
     * <b>玩家行</b>，或第 125 轮新增的<b>组头行</b>（{@link #group} 非空）。
     *
     * @param playerName 玩家名（已知玩家 / 手动名字条目；UUID 条目为 null）
     * @param playerId   玩家 UUID（已知玩家 / 手动 UUID 条目；纯名字条目为 null）
     * @param blockId    「世界中的方块」行对应的<b>方块注册名</b>（第 110 轮；物品行 / 玩家行为 null）
     * @param online     服务端快照里的在线标记（只有已知玩家才有意义）
     * @param manual     是否是「手动添加、不在已知名单里」的条目（名字条目显示 {@code ?}）
     * @param group      第 125 轮：组头行要显示的一切（{@code null} = 普通行）
     * @param member     第 125 轮：是否是折叠组里的<b>成员行</b>（只影响缩进，勾选语义与物品行逐字相同）
     */
    private record Row(boolean player, @Nullable Item item, @Nullable ResourceLocation blockId,
                       @Nullable String playerName, @Nullable UUID playerId, boolean online,
                       boolean manual, @Nullable GroupRow group, boolean member) {
    }

    /**
     * 组头行的显示数据（第 125 轮）。
     *
     * <p><b>为什么成员列表要分「displayed」和「全家族」两份</b>：搜索时只显示命中的成员，
     * 但组头右侧的「已选 / 总数」、三态勾选框、整组全选 / 全不选都必须按<b>整个家族</b>算 ——
     * 否则同一个勾选框在搜索时变成另一种含义（搜「红色」时勾组头只选中红色那几个，
     * 玩家会以为整组都选上了）。因此：</p>
     * <ul>
     *     <li>{@link #visibleMembers()}：这一帧真正要画出来的成员行（搜索时 = 命中成员，否则 = 全家族）；</li>
     *     <li>{@link #total()} / {@link #selected()}：家族总成员数 / 其中已勾选的数量（与搜索无关）；</li>
     *     <li>{@link #label()}：`▶ 精致存储控制器（16 色）`（箭头已按展开态拼好，重建行时算一次，渲染零成本）；</li>
     *     <li>{@link #highlighted()}：只命中成员、组名本身没命中搜索 ⇒ 画一行淡绿底。</li>
     * </ul>
     */
    private record GroupRow(ChargerFilterCandidates.Family family, List<Item> visibleMembers,
                            int selected, int total, String label, boolean expanded,
                            boolean highlighted) {
        /** 三态勾选框状态：0 个选中 = 关，全选中 = 开，其余 = 部分。 */
        int checkState() {
            if (selected <= 0) {
                return CHECK_OFF;
            }
            return selected >= total ? CHECK_ON : CHECK_PARTIAL;
        }
    }

    /** 本界面操作的充电器菜单 id（服务端按它校验归属；刷新时用它比对）。 */
    private final int containerId;
    /** 当前模式：{@code false} = 黑名单（默认），{@code true} = 白名单。 */
    private boolean whitelist;
    /** 四份勾选集合（可编辑副本；值语义与服务端 {@code ChargingFilter} 逐个字段对应）。 */
    private final Set<ResourceLocation> droppedItems = new LinkedHashSet<>();
    private final Set<ResourceLocation> carriedItems = new LinkedHashSet<>();
    /**
     * 「世界中的方块」勾选集合（方块注册名，第 110 轮）。
     * <p>与物品两个集合<b>不共用</b>：同一个注册名在物品名单里命中不影响这一份（反之亦然），
     * 因为「掉在地上的 x」与「放在地上的 x」是两个不同类别的目标。</p>
     */
    private final Set<ResourceLocation> blocks = new LinkedHashSet<>();
    private final Set<String> playerNames = new LinkedHashSet<>();
    private final Set<UUID> playerIds = new LinkedHashSet<>();
    /** 服务端下发的「本存档进过服的玩家」快照（离线也在）。 */
    private List<SyncChargerFilterPacket.KnownPlayer> knownPlayers = List.of();
    /**
     * 服务端下发的「本机范围内检测到的可充方块」候选池（第 110 轮）。
     *
     * <p><b>为什么这份候选必须由服务端给</b>：物品两页的候选池是客户端自己扫注册表建的
     * （{@link ChargerFilterCandidates}），但「本机充电范围内有哪些方块」是服务端世界的事实
     * （区块加载、方块实体是否存在、距离），客户端无从自查 —— 所以随快照一起下发。</p>
     *
     * <p>候选池为空不代表「没有可拦的方块」：方块充电总闸（{@code Config.rangeChargerChargeBlocks}）
     * 关着、机器没电、或刚放下还没扫过一轮时它都会是空的，界面因此准备了专门的空态文案。</p>
     */
    private List<ResourceLocation> seenBlocks = List.of();

    /** 当前页签 / 列表滚动起点 / 当前行模型。 */
    private Tab tab = Tab.DROPPED;
    private int firstVisibleRow;
    private List<Row> rows = List.of();
    /**
     * 第 125 轮：<b>非搜索状态</b>下玩家手动展开的家族（键 = {@link ChargerFilterCandidates.Family#key()}）。
     *
     * <p>默认<b>不</b>在这里 = 收起，这正是「合并显示」的意义（用户原话「搞一个总的……展开之后
     * 就不同的颜色把它分在一起」）。状态按家族键记，所以切页签 / 收服务端快照（同一台充电器的
     * 定时刷新）都不会把玩家展开的组又收回去。</p>
     */
    private final Set<ResourceLocation> expandedGroups = new LinkedHashSet<>();
    /**
     * 第 125 轮：<b>搜索状态</b>下玩家手动收起的家族。
     *
     * <p>搜索时命中的组默认<b>自动展开</b>（否则搜到的东西还藏在组里，搜索等于白搜），
     * 但仍然允许手动收起看别的；这个「当场收起」只对当次搜索有效 —— 搜索框内容一变就清空
     * （见 {@code searchBox.setResponder}），不会污染非搜索状态下的 {@link #expandedGroups}。</p>
     */
    private final Set<ResourceLocation> collapsedGroups = new LinkedHashSet<>();
    /**
     * 提示行要显示的<b>临时消息</b>（名单已满 / 输入非法）；{@code null} = 无，此时显示当前模式的说明。
     *
     * <p>为什么从「语言键字符串」改成 {@link Component}（第 99 轮）：新增的「名单已满」提示要带
     * 条数参数（{@code 最多 %s 条}），而参数只能靠 {@link Component#translatable} 表达 ——
     * 继续用字符串键就得在渲染时二次拼装，翻译文案就不能由语言文件自己决定参数位置。
     * 成功添加 / 勾选后一律清空（行本身已经出现在列表里，不需要额外「OK」）。</p>
     */
    @Nullable
    private Component addStatus;

    // ==================== 玩家头像纹理快照（第 100 轮；严格客户端） ====================

    /**
     * {@code UUID → 皮肤纹理} 的<b>快照缓存</b>（只装玩家页当前行用到的那几个 UUID）。
     *
     * <p><b>为什么必须有这张表</b>：皮肤查询要摸 {@code ClientPacketListener#getPlayerInfo} 与
     * {@code SkinManager}，而 {@code render} 每帧都跑（60+ 次/秒）。每帧去查连接就等于每帧做一次
     * 皮肤管理器查询；虽然多数时候是 map 查找，但「渲染循环里不做查询」是本工程的硬规则，
     * 而且皮肤是<b>异步</b>加载的（下载完成才 {@code setSkin}），每帧查也换不来更快的更新。
     * 因此只在「行重建」与「20 tick 一次的低频兜底」时解析一次，帧内只做 {@link #headTextureOf}。</p>
     *
     * <p><b>为什么值可以为 null</b>：{@link HashMap} 允许 null 值 —— 这里用「key 存在」表示
     * <b>已解析过</b>（{@code get} 命中），值为 null 表示「这个人没有任何可用皮肤纹理」
     * （例如手动添加的、连默认皮肤都取不到的条目）⇒ 画中性灰底。用 {@code containsKey} 区分
     * 「没解析过」和「解析过但没纹理」没有意义，因为行重建时会把当前行全部重新解析一遍。</p>
     */
    private final Map<UUID, ResourceLocation> headTextures = new HashMap<>();

    // ==================== 方块页的图标 / 名字缓存（第 110 轮；严格「渲染路径零查询」） ====================

    /**
     * {@code 方块注册名 → 图标栈}（空组件、数量 1 的 {@code new ItemStack(block.asItem())}）。
     *
     * <p><b>为什么必须缓存</b>：方块页的行来自服务端候选池，而 {@link ChargerFilterCandidates}
     * 的图标缓存只覆盖「可充电<b>物品</b>」—— 地面上的控制器 / 能量立方对应的物品大多不在那张表里，
     * 每次 {@code icon(item)} 都会 fallback 到 {@code new ItemStack(item)}。渲染函数每帧都跑，
     * 一帧最多 8 行 ⇒ 每秒几百个临时对象，纯浪费。因此在行重建时把图标与名字算好放这里，
     * 渲染路径只做一次 {@link Map#get}。</p>
     */
    private final Map<ResourceLocation, ItemStack> blockIcons = new HashMap<>();
    /** {@code 方块注册名 → 显示名}（本地化悬浮名；行重建时算好，渲染路径只读）。 */
    private final Map<ResourceLocation, String> blockNames = new HashMap<>();

    /** 方块行的图标栈（渲染路径唯一入口：一次 map 查询，绝不在这里查注册表）。 */
    private ItemStack blockIconOf(final ResourceLocation id) {
        return blockIcons.getOrDefault(id, ItemStack.EMPTY);
    }

    /** 方块行的显示名（同上；缓存未命中时回落成注册名，至少不会显示空白行）。 */
    private String blockNameOf(final ResourceLocation id) {
        return blockNames.getOrDefault(id, id.toString());
    }
    /** 距上次解析头像纹理过了多少 tick（低频兜底用，见 {@link #tick()}）。 */
    private int headCacheTicks;
    /**
     * 低频兜底间隔（tick）：皮肤异步加载，界面<b>一直开着</b>时也需要有机会换成刚加载好的真皮肤。
     * 20 tick = 1 秒，对「最多 8 行」来说就是 1 秒 ≤8 次连接查询 —— 可忽略，且远低于每帧。
     */
    private static final int HEAD_CACHE_REFRESH_TICKS = 20;

    // ==================== 控件 ====================
    @Nullable
    private EditBox searchBox;
    @Nullable
    private EditBox addBox;
    @Nullable
    private Button modeButton;
    @Nullable
    private Button addButton;
    @Nullable
    private SptScrollbarWidget scrollbar;
    private final Button[] tabButtons = new Button[TAB_COUNT];

    /**
     * 打开或刷新本子界面（<b>由 {@code ClientPayloadSink#openChargerFilter} 调用的唯一入口</b>）。
     *
     * <p><b>为什么「刷新」而不是「重开」</b>：服务端每次勾选后不回报；玩家能收到新快照的唯一时机是
     * 「自己点过滤按钮 / 别人改了配置」这类服务端主动发的场景。若每次都新建界面，玩家会看到
     * 面板闪一下、滚动位置丢失；因此同一个 containerId 就地刷数据、只换内容不换实例。</p>
     *
     * <p>父子关系：从子界面刷新时沿用它的父界面，绝不把「子界面」当成新界面的父（否则 Esc 会
     * 一层层叠出子界面链）。</p>
     */
    public static void openOrRefresh(final SyncChargerFilterPacket packet) {
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || packet == null) {
            return;
        }
        final Screen current = minecraft.screen;
        if (current instanceof ChargerFilterScreen screen) {
            if (screen.containerId == packet.containerId()) {
                screen.applySnapshot(packet);
                return;
            }
            // 不同充电器：换一份数据，父界面仍是原来那个（不是旧的过滤界面）
            minecraft.setScreen(new ChargerFilterScreen(screen.parent, packet));
            return;
        }
        minecraft.setScreen(new ChargerFilterScreen(current, packet));
    }

    public ChargerFilterScreen(@Nullable final Screen parent, final SyncChargerFilterPacket packet) {
        super(Component.translatable(LANG + "title"), parent, PANEL_W, PANEL_H);
        this.containerId = packet.containerId();
        applySnapshot(packet);
    }

    /** 本界面正在配置哪一台充电器（刷新判定 + 所有 C2S 包都用它）。 */
    public int containerId() {
        return containerId;
    }

    // ==================== 快照 ====================

    /**
     * 用服务端快照整体覆盖本地编辑副本（唯一的数据入口）。
     * <p>构造期也可能被调用（{@code init()} 还没跑、控件都还是 null）——因此这里所有控件访问都先判空，
     * 只更新数据模型；控件状态由 {@link #refreshWidgets()} 在 {@code init()} 里统一刷一次。</p>
     */
    public void applySnapshot(final SyncChargerFilterPacket packet) {
        this.whitelist = packet.whitelist();
        copyIds(droppedItems, packet.droppedItems());
        copyIds(carriedItems, packet.carriedItems());
        copyIds(blocks, packet.blocks());
        copyNames(playerNames, packet.playerNames());
        copyUuids(playerIds, packet.playerIds());
        this.knownPlayers = packet.knownPlayers() == null ? List.of() : List.copyOf(packet.knownPlayers());
        this.seenBlocks = packet.seenBlocks() == null ? List.of() : List.copyOf(packet.seenBlocks());
        this.addStatus = null;
        rebuildRows();
        refreshModeButton();
        refreshWidgets();
        refreshScrollbar();
    }

    /** 物品 id 集合灌入（null 视为空）。 */
    private static void copyIds(final Set<ResourceLocation> target,
                                @Nullable final List<ResourceLocation> source) {
        target.clear();
        if (source == null) {
            return;
        }
        for (final ResourceLocation id : source) {
            if (id != null) {
                target.add(id);
            }
        }
    }

    /** 玩家名集合灌入：<b>统一小写</b>（与服务端「名字存小写」同一口径，判定永远不小写不一致）。 */
    private static void copyNames(final Set<String> target, @Nullable final List<String> source) {
        target.clear();
        if (source == null) {
            return;
        }
        for (final String name : source) {
            if (name != null && !name.isEmpty()) {
                target.add(name.toLowerCase(Locale.ROOT));
            }
        }
    }

    /** UUID 集合灌入（null 视为空）。 */
    private static void copyUuids(final Set<UUID> target, @Nullable final List<UUID> source) {
        target.clear();
        if (source == null) {
            return;
        }
        for (final UUID id : source) {
            if (id != null) {
                target.add(id);
            }
        }
    }

    // ==================== 行模型 ====================

    /** 按「当前页签 + 搜索词」重建列表行（勾选 / 添加 / 切页 / 搜索之后都要重建）。 */
    private void rebuildRows() {
        final List<Row> next = new ArrayList<>();
        // 方块页的图标 / 名字缓存每轮重建（行模型换了一批，旧条目留着只是占内存）
        blockIcons.clear();
        blockNames.clear();
        if (tab == Tab.PLAYERS) {
            final Set<String> knownNameSet = new LinkedHashSet<>();
            final Set<UUID> knownIdSet = new LinkedHashSet<>();
            for (final SyncChargerFilterPacket.KnownPlayer player : knownPlayers) {
                if (player == null) {
                    continue;
                }
                final String name = player.name() == null ? "" : player.name();
                knownNameSet.add(name.toLowerCase(Locale.ROOT));
                if (player.id() != null) {
                    knownIdSet.add(player.id());
                }
                next.add(new Row(true, null, null, name.isEmpty() ? null : name, player.id(),
                    player.online(), false, null, false));
            }
            // 手动条目：已知名单里没有的名字 / UUID 各占一行（这就是「删除」的入口：
            // 取消勾选即把它从集合里去掉，行随之消失）
            for (final String name : playerNames) {
                if (!knownNameSet.contains(name)) {
                    next.add(new Row(true, null, null, name, null, false, true, null, false));
                }
            }
            for (final UUID id : playerIds) {
                if (!knownIdSet.contains(id)) {
                    next.add(new Row(true, null, null, null, id, false, true, null, false));
                }
            }
        } else if (tab == Tab.BLOCKS) {
            rebuildBlockRows(next);
        } else {
            // 掉落物 / 玩家身上物品：同一个候选池，两份独立勾选（用户定案「两页各自独立」）
            rebuildItemRows(next);
        }
        this.rows = List.copyOf(next);
        refreshHeadTextures();
        refreshScrollbar();
    }

    /**
     * 重建物品两页的行（第 125 轮：<b>颜色家族折叠 + 展开</b>）。
     *
     * <h2>三段式</h2>
     * <ol>
     *     <li><b>归拢</b>：{@link ChargerFilterCandidates#search} 的结果逐条问
     *     {@link ChargerFilterCandidates#familyOf} —— 返回 {@code null} 的是普通条目，直接平铺；
     *     否则按家族键收进「这一帧要显示的家族」表；</li>
     *     <li><b>混排</b>：普通条目与组头放进同一个列表，用<b>同一套比较器</b>排序 ——
     *     见下方「排序规则」；</li>
     *     <li><b>摊平</b>：按顺序输出；组头若处于展开态，它的成员行<b>紧跟其后</b>插入，
     *     顺序就是家族里固定的颜色序（白→黑，默认变体最前），<b>不参与</b>排序。</li>
     * </ol>
     *
     * <h2>排序规则（本轮自定的那一条）</h2>
     * <p>组头与普通条目混排、且用的是<b>同一个</b>「显示名 → 注册 id」比较器，
     * 组头的显示名取<b>家族基础名</b>（{@link ChargerFilterCandidates.Family#sortName()}，
     * 例如「精致存储控制器」，<b>不含</b>「（16 色）」后缀）。理由：</p>
     * <ul>
     *     <li>列表本来就是「按显示名排序」的（候选池 {@code all()} 的既有口径，玩家已经习惯）；
     *     组头插进这个序列里读起来像「本来就在那儿的一个东西」，而不是「另起一块」；</li>
     *     <li>若改用「组头全排最前 / 最后」，16 个家族会各自形成一个孤岛，普通条目被切碎，
     *     视觉上比原来平铺更乱 —— 用户要的是「整齐」，不是「分区」；</li>
     *     <li>组头排序键刻意<b>不用</b>成员名：成员名带颜色词（「白色控制器」），
     *     16 个成员谁当代表都会让组头位置漂移（搜一次跳一次），基础名则永远稳定。</li>
     * </ul>
     *
     * <h2>搜索状态（用户口述 3-4 的第三段）</h2>
     * <ul>
     *     <li>查询非空 ⇒ 命中的组<b>自动展开</b>，且只显示命中的成员（否则搜索等于没搜）；</li>
     *     <li>组名（含模组名）或家族键 id 本身命中 ⇒ 组头算「自己命中」；只命中成员时
     *     {@link GroupRow#highlighted()} 为真 ⇒ 组头画一行淡绿底，告诉玩家「这组是被里面的东西
     *     带出来的」（<b>不隐藏组头</b>，否则成员行会变成一堆没有归属的散条目）。</li>
     * </ul>
     */
    private void rebuildItemRows(final List<Row> out) {
        final String query = searchQuery() == null ? "" : searchQuery().trim();
        final boolean searching = !query.isEmpty();
        final Map<ResourceLocation, ChargerFilterCandidates.Family> families = new LinkedHashMap<>();
        final Map<ResourceLocation, List<Item>> matches = new LinkedHashMap<>();
        final List<Item> plain = new ArrayList<>();
        for (final Item item : ChargerFilterCandidates.search(query)) {
            final ChargerFilterCandidates.Family family = ChargerFilterCandidates.familyOf(item);
            if (family == null) {
                plain.add(item);
                continue;
            }
            families.put(family.key(), family);
            matches.computeIfAbsent(family.key(), key -> new ArrayList<>()).add(item);
        }
        // 顶层行 = 普通条目 + 组头；之后用同一比较器混排（见方法注释「排序规则」）
        final List<Row> top = new ArrayList<>(plain.size() + families.size());
        for (final Item item : plain) {
            top.add(new Row(false, item, null, null, null, false, false, null, false));
        }
        final Set<ResourceLocation> selection = selectionOfCurrentTab();
        for (final ChargerFilterCandidates.Family family : families.values()) {
            final List<Item> visible = searching
                ? orderedMembers(family, matches.get(family.key()))
                : family.members();
            final boolean expanded = searching
                ? !collapsedGroups.contains(family.key())      // 搜索时默认展开
                : expandedGroups.contains(family.key());       // 非搜索时默认收起
            final boolean highlighted = searching && !familyMatchesQuery(family, query);
            top.add(new Row(false, null, null, null, null, false, false,
                new GroupRow(family, visible, countSelected(family, selection),
                    family.colorCount(),
                    (expanded ? GROUP_ARROW_EXPANDED : GROUP_ARROW_COLLAPSED) + " "
                        + family.displayName().getString(),
                    expanded, highlighted),
                false));
        }
        top.sort(Comparator.comparing((Row row) -> topSortKey(row))
            .thenComparing(row -> topSortId(row)));
        for (final Row row : top) {
            out.add(row);
            if (row.group() != null && row.group().expanded()) {
                for (final Item member : row.group().visibleMembers()) {
                    // 成员行的身份就是物品行（item 非空、member=true 只影响缩进）
                    out.add(new Row(false, member, null, null, null, false, false, null, true));
                }
            }
        }
    }

    /**
     * 把「搜索命中的成员」按<b>家族固定颜色序</b>重排（搜索只决定「哪些成员可见」，
     * 不决定它们之间的顺序 —— 否则搜一次颜色序就变成显示名序了）。
     */
    private static List<Item> orderedMembers(final ChargerFilterCandidates.Family family,
                                             @Nullable final List<Item> matched) {
        if (matched == null || matched.isEmpty()) {
            return List.of();
        }
        final Set<Item> visible = new LinkedHashSet<>(matched);
        final List<Item> ordered = new ArrayList<>(matched.size());
        for (final Item member : family.members()) {
            if (visible.contains(member)) {
                ordered.add(member);
            }
        }
        return ordered;
    }

    /** 家族里已被勾选的成员数（按<b>整个家族</b>算，与当前搜索词无关 —— 见 {@link GroupRow}）。 */
    private static int countSelected(final ChargerFilterCandidates.Family family,
                                     final Set<ResourceLocation> selection) {
        int count = 0;
        for (final Item member : family.members()) {
            final ResourceLocation id = ChargerFilterCandidates.idOf(member);
            if (id != null && selection.contains(id)) {
                count++;
            }
        }
        return count;
    }

    /** 顶层行的排序键：组头 = 家族基础名；普通条目 = 显示名（两者都是小写，口径一致）。 */
    private static String topSortKey(final Row row) {
        if (row.group() != null) {
            return row.group().family().sortName();
        }
        final Item item = row.item();
        return item == null ? "" : ChargerFilterCandidates.nameOf(item).toLowerCase(Locale.ROOT);
    }

    /** 排序的次级键：同名时按注册 id（与候选池 {@code all()} 的稳定排序同一口径）。 */
    private static String topSortId(final Row row) {
        if (row.group() != null) {
            return row.group().family().key().toString();
        }
        final Item item = row.item();
        final ResourceLocation id = item == null ? null : ChargerFilterCandidates.idOf(item);
        return id == null ? "" : id.toString();
    }

    /**
     * 组名 / 家族键本身是否命中搜索词（名字这一路同样走 {@link PinyinSearch#contains}）。
     * <p>没命中 ⇒ 这个组只是「被成员带出来的」，组头要加高亮而不是被当成命中结果。</p>
     */
    private static boolean familyMatchesQuery(final ChargerFilterCandidates.Family family,
                                              final String query) {
        if (PinyinSearch.contains(family.displayName().getString(), query)) {
            return true;
        }
        return family.key().toString().toLowerCase(Locale.ROOT)
            .contains(query.toLowerCase(Locale.ROOT));
    }

    /**
     * 重建「世界中的方块」页的行（第 110 轮）。
     *
     * <h2>行 = 候选池 ∪ 已配置名单（去重）</h2>
     * <ul>
     *     <li><b>候选池</b>（{@link #seenBlocks}，服务端下发）：本机范围内检测到的、有 FE 能力的方块。
     *     它决定「玩家能新勾选什么」；</li>
     *     <li><b>已配置名单</b>（{@link #blocks}）里<b>不在候选池</b>的条目也要列出来 ——
     *     否则玩家把某个方块拉黑之后，那一行会从列表里消失，就<b>再也取消不了勾选</b>
     *     （机器搬走 / 范围缩小时都会这样）。这是「删除的入口 = 取消勾选」这条既有语义
     *     在方块页上的必要补丁。</li>
     * </ul>
     *
     * <h2>搜索（与物品两页逐字同一套口径）</h2>
     * <p><b>显示名这一路走 {@link PinyinSearch#contains}</b>：玩家装了「通用拼音搜索」
     * （jecharacters）时敲 {@code kongzhiqi} / 简拼也能搜到「控制器」，没装时它自己退回
     * 不区分大小写子串 —— 因此本页的拼音支持与物品页<b>来自同一处实现</b>，不会出现
     * 「物品能拼音搜、方块不能」这种一半有一半没有的状态。注册 id 那一路保持小写子串。</p>
     * <p>之所以不能直接复用 {@link ChargerFilterCandidates#search}：那是「全物品」候选池的搜索，
     * 而方块页的候选是服务端给的<b>范围子集</b>（候选源不同）；能复用的是<b>匹配规则</b>，
     * 也就是 {@link PinyinSearch} 这一层。</p>
     */
    private void rebuildBlockRows(final List<Row> out) {
        final String needle = searchQuery() == null ? "" : searchQuery().trim();
        final Set<ResourceLocation> seen = new LinkedHashSet<>();
        for (final ResourceLocation id : seenBlocks) {
            if (id != null && seen.add(id)) {
                addBlockRow(out, id, needle);
            }
        }
        for (final ResourceLocation id : blocks) {
            if (id != null && !seen.contains(id)) {
                addBlockRow(out, id, needle);
            }
        }
    }

    /**
     * 往行模型里放一条方块行；<b>不满足条件时不放</b>（返回 false）。
     *
     * <p>三条过滤：① 方块注册名必须能在 {@code BuiltInRegistries.BLOCK} 里解析出来
     * （存档里可能残留已被卸载模组的 id —— 那种条目<b>不画</b>，但<b>仍留在名单里</b>，
     * 不会被静默删除）；② {@code block.asItem()} 是空气的方块跳过（没有图标可画，用户原话的
     * 「放在地上的方块」也不会是这类）；③ 搜索词不命中则跳过。</p>
     */
    private boolean addBlockRow(final List<Row> out, final ResourceLocation id, final String needle) {
        final net.minecraft.world.level.block.Block block =
            net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(id);
        final ItemStack icon = block == null
            ? ItemStack.EMPTY
            : new ItemStack(block.asItem());
        if (icon.isEmpty()) {
            return false; // 解析不到 / asItem 是空气：没有可画的图标
        }
        final String name = icon.getHoverName().getString();
        if (!needle.isEmpty()) {
            // 名字：拼音 / 简拼 / 子串（PinyinSearch 内部自选）；注册 id：小写子串。
            // 两条都不命中才跳过 —— 与 ChargerFilterCandidates#search 同一顺序、同一判据。
            final boolean nameHit = PinyinSearch.contains(name, needle);
            final boolean idHit = id.toString().toLowerCase(Locale.ROOT)
                .contains(needle.toLowerCase(Locale.ROOT));
            if (!nameHit && !idHit) {
                return false;
            }
        }
        blockIcons.put(id, icon);
        blockNames.put(id, name);
        out.add(new Row(false, null, id, null, null, false, false, null, false));
        return true;
    }

    // ==================== 玩家头像纹理解析（第 100 轮） ====================

    /**
     * 按<b>当前行</b>重建 {@link #headTextures} 快照（切页签 / 收快照 / 勾选 / 添加 / 低频兜底时调用）。
     *
     * <p>只在玩家页解析（物品页没有头像，别白查连接）；每行都写进 map，解析不到纹理时写 null，
     * 让渲染侧对所有情况都只有一次 {@code get}。</p>
     */
    private void refreshHeadTextures() {
        headTextures.clear();
        headCacheTicks = 0;
        if (tab != Tab.PLAYERS) {
            return;
        }
        for (final Row row : rows) {
            if (row.player() && row.playerId() != null) {
                headTextures.put(row.playerId(), resolveHeadTexture(row.playerId()));
            }
        }
    }

    /**
     * 解析一个 UUID 的皮肤纹理（<b>在线优先、默认皮肤兜底</b>）；解析不到返回 {@code null}。
     *
     * <h2>三条路径（顺序就是优先级）</h2>
     * <ol>
     *     <li><b>在线玩家</b>：{@code Minecraft#getConnection()} 非空（未进服时为 null）且
     *     {@code getPlayerInfo(uuid)} 命中 ⇒ {@link PlayerInfo#getSkin()} 的
     *     {@link PlayerSkin#texture()}，这就是原版物品栏 / 聊天栏头像用的<b>同一份</b>纹理；</li>
     *     <li><b>离线 / 名字条目 / 客户端没见过此人</b>：{@link DefaultPlayerSkin#get(UUID)} ——
     *     1.21.1 的真实方法名（没有 {@code getDefaultSkin}），按 UUID 哈希稳定地给出
     *     Steve/Alex 家族里的一张默认皮肤，同样去裁头（<b>不再自绘</b>）；</li>
     *     <li><b>都拿不到</b>：返回 {@code null} ⇒ 渲染侧画中性灰底 + 槽框。</li>
     * </ol>
     *
     * <p><b>零 IO / 零网络</b>：这里只读客户端已经拿到手的 {@code PlayerInfo} 与内建默认皮肤资源，
     * 不发请求、不写世界、不触发资源重载 —— 异步下载由原版 {@code SkinManager} 自己负责，
     * 它把皮肤挂到 {@code PlayerInfo} 上之后，下一次（低频兜底）解析就会读到。</p>
     */
    @Nullable
    private static ResourceLocation resolveHeadTexture(@Nullable final UUID id) {
        if (id == null) {
            return null;
        }
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return null;
        }
        final ClientPacketListener connection = minecraft.getConnection();
        if (connection != null) {
            final PlayerInfo info = connection.getPlayerInfo(id);
            if (info != null) {
                final PlayerSkin skin = info.getSkin();
                if (skin != null && skin.texture() != null) {
                    return skin.texture();
                }
            }
        }
        final PlayerSkin fallback = DefaultPlayerSkin.get(id);
        return fallback == null ? null : fallback.texture();
    }

    /**
     * 低频兜底：皮肤是<b>异步</b>加载的，界面一直开着的时候也要有机会从「默认皮肤」换成「真皮肤」。
     *
     * <p>为什么用 tick 而不是在 {@code render} 里判断时间戳：{@code render} 一帧可能被调用多次
     * （暂停/重绘），而 tick 每 50ms 恰好一次，频率与刷新代价都可预测；且这里<b>不在渲染线程路径上</b>
     * 做任何查询，符合「渲染循环里不做查询」的硬规则。</p>
     */
    @Override
    public void tick() {
        super.tick();
        if (tab != Tab.PLAYERS) {
            return;
        }
        if (++headCacheTicks < HEAD_CACHE_REFRESH_TICKS) {
            return;
        }
        refreshHeadTextures();
    }

    /**
     * 取某行缓存好的头像纹理（<b>渲染路径上唯一允许的调用</b>：一次 {@link HashMap#get}）。
     *
     * <p>没解析过（例如行是刚加进来、快照还没重建）时返回 null ⇒ 画中性灰底，
     * 下一 tick 的兜底就会补上 —— 表现是「先灰一下再出脸」，不会闪、不会报错。</p>
     */
    @Nullable
    private ResourceLocation headTextureOf(@Nullable final UUID id) {
        return id == null ? null : headTextures.get(id);
    }

    /** 当前搜索词（玩家页不参与过滤；搜索框在玩家页是隐藏的）。 */
    private String searchQuery() {
        return searchBox == null ? "" : searchBox.getValue();
    }

    /** 该行当前是否勾选。 */
    private boolean isChecked(final Row row) {
        if (row.player()) {
            if (row.playerName() != null && playerNames.contains(row.playerName().toLowerCase(Locale.ROOT))) {
                return true;
            }
            return row.playerId() != null && playerIds.contains(row.playerId());
        }
        // 第 110 轮：方块行的身份是**方块注册名**（不是它对应的方块物品），故单独一支判定
        if (row.blockId() != null) {
            return blocks.contains(row.blockId());
        }
        final ResourceLocation id = row.item() == null ? null : ChargerFilterCandidates.idOf(row.item());
        return id != null && selectionOfCurrentTab().contains(id);
    }

    /**
     * 当前页签对应的勾选集合（物品页 / 方块页；玩家页不应调用）。
     *
     * <p>第 110 轮起方块页有自己的集合：{@code BLOCKS} 页若仍返回 {@code droppedItems}
     * 就会「勾了方块、亮的是掉落物名单」，而它俩是两套独立配置。</p>
     */
    private Set<ResourceLocation> selectionOfCurrentTab() {
        return switch (tab) {
            case CARRIED -> carriedItems;
            case BLOCKS -> blocks;
            default -> droppedItems;
        };
    }

    // ==================== 交互：勾选 / 添加 / 切换模式 ====================

    /**
     * 方块页的勾选开关（第 110 轮）：<b>已勾选 ⇒ 取消；否则加入；满员什么都不改</b>。
     *
     * <p>语义实现只有一处（{@link ChargerFilterLimits#toggleBounded}）—— 「先 remove 判断再 add」
     * 这种写法极容易写成「无条件 add」，那样勾选框点一次就永远关不掉（第 99 轮真的写错过一次）。
     * 这里只是把「哪个集合 + 哪个上限」绑好，逻辑本身不在这里。</p>
     *
     * <p><b>为什么单独一个方法、而且放在 {@link #toggleRow} 之前</b>：{@code toggleRow} 里只留
     * 物品那一路内联调用 {@code toggleBounded} —— 第 99 轮自检用「把物品那一路换成
     * 先 remove 再无条件 add」做反例自证，而那个反例会让 {@code body_of} 的花括号配对多吞一层；
     * 若把第二处 {@code toggleBounded} 写在 {@code toggleRow} <b>之后</b>，被吞进来的方法体里就
     * 又出现了一次 toggleBounded，反例便测不出来（把判据变成假绿）。放在前面即避开这个陷阱。</p>
     *
     * @return {@code true} = 状态已改变且合法；{@code false} = <b>名单已满、什么都没改</b>
     * （调用方必须给提示，不能点了没反应）
     */
    private boolean toggleBlockSelection(final ResourceLocation blockId) {
        return ChargerFilterLimits.toggleBounded(blocks, blockId,
            ChargerFilterLimits.MAX_BLOCK_ENTRIES);
    }

    /**
     * 勾选 / 取消一行（左键），然后<b>立刻</b>把全量配置发给服务端。
     *
     * <h2>第 99 轮：勾选也要受硬上限约束，而且满了必须给提示</h2>
     * <p>名单条数的真上限在服务端（{@code ChargerFilterLimits}），但界面必须<b>先一步</b>知道：
     * 否则勾到第 1025 个物品时服务端会把尾部丢掉，玩家却看到界面里它亮着 —— 下次打开界面
     * 它又暗了，这才是最难解释的那种「自己变回去」。因此这里按同一组常量判断，
     * 满了就写 {@link #addStatus} 并<b>不改变任何数据</b>（不改本地、不发包）。</p>
     * <p>玩家行是「名字 + UUID 一起记」的复合操作：两个集合都放得下才动手，
     * 不允许「名字进去了、UUID 没进去」这种半个状态。</p>
     */
    private void toggleRow(final Row row) {
        addStatus = null;
        if (row.player()) {
            final String name = row.playerName() == null ? null : row.playerName().toLowerCase(Locale.ROOT);
            final UUID id = row.playerId();
            final boolean checked = isChecked(row);
            if (checked) {
                if (name != null) {
                    playerNames.remove(name);
                }
                if (id != null) {
                    playerIds.remove(id);
                }
            } else {
                // 勾选时把「名字 + UUID」都记上：已知玩家两者都有，UUID 更抗改名；
                // 纯名字 / 纯 UUID 的手动条目只有一项可记。
                if (!hasRoomFor(playerNames, name, ChargerFilterLimits.MAX_PLAYER_ENTRIES)
                    || !hasRoomFor(playerIds, id, ChargerFilterLimits.MAX_PLAYER_ID_ENTRIES)) {
                    addStatus = fullStatus(Math.max(ChargerFilterLimits.MAX_PLAYER_ENTRIES,
                        ChargerFilterLimits.MAX_PLAYER_ID_ENTRIES));
                    return; // 数据没变 ⇒ 不重建行、不发包
                }
                if (name != null) {
                    playerNames.add(name);
                }
                if (id != null) {
                    playerIds.add(id);
                }
            }
        } else if (row.blockId() != null) {
            // 第 110 轮：方块页的勾选语义与物品页逐字相同（走 ChargerFilterLimits.toggleBounded：
            // 已勾选 ⇒ 取消；否则加入；满员什么都不改并给提示），只是集合与上限常量不同。
            // 判定**收在一个小方法里**而不是内联两行：`toggleRow` 本身要保持「只有物品那一路内联
            // 调用 toggleBounded」的形状 —— 第 99 轮自检拿「把物品那一路换成先 remove 再无条件 add」
            // 做反例自证，内联第二处会让那条反例测不出问题（方法体里还有别的 toggleBounded）。
            if (!toggleBlockSelection(row.blockId())) {
                addStatus = fullStatus(ChargerFilterLimits.MAX_BLOCK_ENTRIES);
                return; // 数据没变 ⇒ 不重建行、不发包
            }
        } else if (row.item() != null) {
            final ResourceLocation id = ChargerFilterCandidates.idOf(row.item());
            if (id != null
                && !ChargerFilterLimits.toggleBounded(selectionOfCurrentTab(), id,
                    ChargerFilterLimits.MAX_ITEM_ENTRIES)) {
                // 勾选语义（已勾选 ⇒ 取消；否则加入；满了什么都不改）只有 ChargerFilterLimits 一处实现，
                // 因为「先 remove 判断再 add」这种写法很容易写成「无条件 add」⇒ 勾选框永远关不掉
                addStatus = fullStatus(ChargerFilterLimits.MAX_ITEM_ENTRIES);
                return; // 数据没变 ⇒ 不重建行、不发包
            }
        }
        rebuildRows();
        sendFilter();
    }

    /** 往 {@code target} 里放 {@code value} 还有名额吗（已在集合里 / 值为 null 都算有名额）。 */
    private static <T> boolean hasRoomFor(final Set<T> target, @Nullable final T value,
                                          final int max) {
        return value == null || target.contains(value) || target.size() < max;
    }

    /** 「名单已满」提示（带条数；翻译文案自己决定参数位置）。 */
    private static Component fullStatus(final int max) {
        return Component.translatable(LANG + "list.full", max);
    }

    /**
     * 组头勾选框 = <b>整组全选 / 全不选</b>（第 125 轮）。
     *
     * <h2>为什么不用逐条 {@code toggleBounded} 循环</h2>
     * <p>组头是「批量」语义：部分选中时点它应当变成<b>全选</b>（而不是把每一条都翻面 ——
     * 那样已选的会被取消、没选的会被选上，结果一个都不剩）。因此这里分两种情形显式处理：</p>
     * <ol>
     *     <li><b>整组已全选</b> ⇒ 把家族里所有 id 从当前页签集合里删掉（取消勾选永远不受上限影响）；</li>
     *     <li><b>否则</b> ⇒ 先把缺的成员数算出来，<b>一次性预检上限</b>：
     *     {@code 现有条数 + 缺的条数 > MAX} ⇒ 写 {@link #addStatus} 且<b>什么都不改</b>
     *     （与本工程「满了必须给提示、绝不半个状态成功」的硬规则一致；不会出现
     *     「前 30 个进去了、后 3 个没进去」这种半截结果）。</li>
     * </ol>
     * <p><b>已选集合语义一个字没改</b>：仍然只是往 {@code droppedItems} / {@code carriedItems}
     * 这同一批 {@code Set<ResourceLocation>} 里批量 add / remove，随后照常
     * {@link #sendFilter()} 发全量快照 —— 服务端与存档完全感受不到「组」这个概念。</p>
     *
     * <p><b>为什么按整个家族算，而不是按「搜索时可见的成员」算</b>：见 {@link GroupRow} 的注释 ——
     * 同一个勾选框在搜索时不能变成另一种含义；组头右侧的「3/16」也是整个家族的计数。</p>
     */
    private void toggleGroupSelection(final GroupRow group) {
        addStatus = null;
        final Set<ResourceLocation> selection = selectionOfCurrentTab();
        final List<ResourceLocation> ids = new ArrayList<>(group.total());
        for (final Item member : group.family().members()) {
            final ResourceLocation id = ChargerFilterCandidates.idOf(member);
            if (id != null) {
                ids.add(id);
            }
        }
        if (group.selected() >= group.total()) {
            for (final ResourceLocation id : ids) {
                selection.remove(id);
            }
            rebuildRows();
            sendFilter();
            return;
        }
        int missing = 0;
        for (final ResourceLocation id : ids) {
            if (!selection.contains(id)) {
                missing++;
            }
        }
        if (selection.size() + missing > ChargerFilterLimits.MAX_ITEM_ENTRIES) {
            addStatus = fullStatus(ChargerFilterLimits.MAX_ITEM_ENTRIES);
            return; // 数据没变 ⇒ 不重建行、不发包（与逐条勾选同一约定）
        }
        for (final ResourceLocation id : ids) {
            selection.add(id);
        }
        rebuildRows();
        sendFilter();
    }

    /**
     * 点组头（勾选框以外的位置）= 展开 / 折叠（第 125 轮）。
     *
     * <p>两套状态不能合成一个集合：非搜索时「默认收起、点开才展开」（{@link #expandedGroups}），
     * 搜索时「命中的组默认展开、点一下才收起」（{@link #collapsedGroups}）——
     * 合成一个集合就会出现「搜索时点一下没反应」或「清掉搜索后组还莫名其妙收着」。
     * 只有 UI 状态变化，<b>不发包</b>（与勾选无关）。</p>
     */
    private void toggleGroupExpanded(final ChargerFilterCandidates.Family family) {
        final Set<ResourceLocation> target = groupExpandState();
        if (!target.remove(family.key())) {
            target.add(family.key());
        }
        rebuildRows();
    }

    /** 当前该改哪一套展开状态：搜索时 = 「手动收起」表，非搜索 = 「手动展开」表。 */
    private Set<ResourceLocation> groupExpandState() {
        return searchQueryOrEmpty().isEmpty() ? expandedGroups : collapsedGroups;
    }

    /** 当前搜索词（去首尾空白；搜索框还没建好时返回空串）。 */
    private String searchQueryOrEmpty() {
        final String query = searchQuery();
        return query == null ? "" : query.trim();
    }

    /** 组头那一行的三态勾选框是否被点到（局部 x 命中，与 {@link #ROW_CHECK_X} 同源）。 */
    private boolean onGroupCheckbox(final double mouseX) {
        final double localX = mouseX - (px + LIST_X);
        return localX >= ROW_CHECK_X && localX < ROW_CHECK_X + ROW_CHECK_SIZE;
    }

    /**
     * 添加一名玩家（玩家页「添加」按钮 / 输入框回车）。
     * <p>输入可以是<b>玩家名</b>（不区分大小写，存小写）或 <b>UUID</b>；
     * 名字命中已知名单时顺带把 UUID 也记上（服务端判定「名字或 UUID 任一命中」⇒ 这样改名后仍拦得住）。</p>
     * <p>非法输入<b>不静默</b>：把原因写进 {@link #addStatus}，显示在提示行（本模组「点了没反应必须给提示」）。</p>
     *
     * <h2>UUID 写法（第 99 轮修）</h2>
     * <p>「什么算玩家名 / 什么算 UUID」只由 {@link ChargerFilterLimits#parsePlayerId} 决定：
     * 它同时接受 36 字符带横线与我们以前<b>漏掉</b>的 32 字符裸十六进制（很多工具/网站输出后一种）。
     * 以前那种写法会被当成玩家名存进名单、永远不会命中，玩家只会以为「加了没用」。
     * 反过来它也只在「长度对 + 全是十六进制」时才认 UUID，因此不可能吞掉真正的玩家名（最长 16 字符）。</p>
     */
    private void onAdd() {
        if (addBox == null) {
            return;
        }
        addStatus = null;
        final String raw = addBox.getValue() == null ? "" : addBox.getValue().trim();
        if (raw.isEmpty()) {
            addStatus = Component.translatable(LANG + "add.invalid");
            return;
        }
        final UUID parsed = parseUuid(raw);
        if (parsed != null) {
            if (!hasRoomFor(playerIds, parsed, ChargerFilterLimits.MAX_PLAYER_ID_ENTRIES)) {
                addStatus = fullStatus(ChargerFilterLimits.MAX_PLAYER_ID_ENTRIES);
                return;
            }
            playerIds.add(parsed);
            addBox.setValue("");
            rebuildRows();
            sendFilter();
            return;
        }
        final String name = ChargerFilterLimits.normalizeName(raw);
        if (name == null) {
            addStatus = Component.translatable(LANG + "add.invalid");
            return;
        }
        if (!hasRoomFor(playerNames, name, ChargerFilterLimits.MAX_PLAYER_ENTRIES)) {
            addStatus = fullStatus(ChargerFilterLimits.MAX_PLAYER_ENTRIES);
            return;
        }
        playerNames.add(name);
        final UUID known = knownIdByName(name);
        if (known != null && hasRoomFor(playerIds, known, ChargerFilterLimits.MAX_PLAYER_ID_ENTRIES)) {
            playerIds.add(known);
        }
        addBox.setValue("");
        rebuildRows();
        sendFilter();
    }

    /** 已知名单里该名字对应的 UUID（没有 ⇒ null）。 */
    @Nullable
    private UUID knownIdByName(final String lowerName) {
        for (final SyncChargerFilterPacket.KnownPlayer player : knownPlayers) {
            if (player != null && player.id() != null && player.name() != null
                && player.name().toLowerCase(Locale.ROOT).equals(lowerName)) {
                return player.id();
            }
        }
        return null;
    }

    /**
     * 解析玩家输入里的 UUID（失败返回 null；不抛异常）。
     * <p>实现只有一处：{@link ChargerFilterLimits#parsePlayerId}（36 字符带横线 + 32 字符裸十六进制）。
     * 这里保留一层薄封装，是因为「界面怎么判 UUID」本身就值得被自检点名。</p>
     */
    @Nullable
    private static UUID parseUuid(final String text) {
        return ChargerFilterLimits.parsePlayerId(text);
    }

    /** 切换黑/白名单（全局一个开关，三类共用）。 */
    private void toggleMode() {
        whitelist = !whitelist;
        addStatus = null;
        refreshModeButton();
        sendFilter();
    }

    /** 把本地编辑副本整份发给服务端（契约 §2：服务端整体覆盖，无 ack）。 */
    private void sendFilter() {
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(new SetChargerFilterPacket(
            containerId, whitelist,
            new ArrayList<>(droppedItems), new ArrayList<>(carriedItems),
            new ArrayList<>(blocks),
            new ArrayList<>(playerNames), new ArrayList<>(playerIds)));
    }

    /** 切页签：重建行、清滚动位置、刷新控件可见性。 */
    private void switchTab(final Tab next) {
        if (tab == next) {
            return;
        }
        tab = next;
        firstVisibleRow = 0;
        addStatus = null;
        rebuildRows();
        refreshWidgets();
    }

    // ==================== 控件生命周期 ====================

    @Override
    protected void init() {
        super.init(); // ChildConfigScreen：算出居中面板左上角 px / py

        modeButton = Button.builder(modeLabel(), button -> toggleMode())
            .bounds(px + MODE_X, py + MODE_Y, MODE_W, MODE_H).build();
        addRenderableWidget(modeButton);

        searchBox = new EditBox(font, px + SEARCH_X, py + SEARCH_Y, SEARCH_W, SEARCH_H,
            Component.translatable(LANG + "search.hint"));
        searchBox.setMaxLength(48);
        searchBox.setTextShadow(false);
        searchBox.setHint(Component.translatable(LANG + "search.hint"));
        searchBox.setResponder(text -> {
            firstVisibleRow = 0;
            // 第 125 轮：搜索词一变，「搜索时手动收起的组」就作废 —— 否则会出现
            // 「改完搜索词后某个组还莫名收着」的残留状态（非搜索的展开状态不受影响）
            collapsedGroups.clear();
            rebuildRows();
        });
        addRenderableWidget(searchBox);

        for (int i = 0; i < TAB_COUNT; i++) {
            final Tab target = Tab.values()[i];
            final Button tabButton = Button.builder(Component.translatable(LANG + target.labelKey),
                button -> switchTab(target))
                .bounds(px + TAB_X + i * (TAB_W + TAB_GAP), py + TAB_Y, TAB_W, TAB_H).build();
            tabButtons[i] = tabButton;
            addRenderableWidget(tabButton);
        }

        scrollbar = new SptScrollbarWidget(px + SCROLL_X, py + LIST_Y,
            SptScrollbarWidget.Type.NORMAL, LIST_H, SCROLL_W);
        scrollbar.setListener(offset -> firstVisibleRow = (int) Math.round(offset));
        addWidget(scrollbar);

        addBox = new EditBox(font, px + ADD_X, py + ADD_Y, ADD_W, ADD_H,
            Component.translatable(LANG + "add.hint"));
        addBox.setMaxLength(64);
        addBox.setTextShadow(false);
        addBox.setHint(Component.translatable(LANG + "add.hint"));
        addRenderableWidget(addBox);
        addButton = Button.builder(Component.translatable(LANG + "add.button"),
            button -> onAdd())
            .bounds(px + ADD_BTN_X, py + ADD_Y, ADD_BTN_W, ADD_H).build();
        addRenderableWidget(addButton);

        addRenderableWidget(Button.builder(Component.translatable(LANG + "done"),
                button -> returnToParent())
            .bounds(px + DONE_X, py + DONE_Y, DONE_W, BTN_H).build());
        addRenderableWidget(Button.builder(Component.translatable(LANG + "back"),
                button -> returnToParent())
            .bounds(px + BACK_X, py + DONE_Y, BACK_W, BTN_H).build());

        refreshModeButton();
        refreshWidgets();
        rebuildRows();
        refreshScrollbar();
    }

    /** 模式按钮文案（黑名单 / 白名单）+ 当前页签的控件可见性。</p> */
    private void refreshModeButton() {
        if (modeButton != null) {
            modeButton.setMessage(modeLabel());
        }
    }

    private Component modeLabel() {
        return Component.translatable(LANG + (whitelist ? "mode.whitelist" : "mode.blacklist"));
    }

    /**
     * 控件可见性：玩家页隐藏搜索框、显示添加行；另外把「当前页签」按钮置灰（= 不可再点），
     * 这是唯一能让玩家一眼看出「现在在哪一页」的免费标记（原版按钮置灰态）。
     */
    private void refreshWidgets() {
        final boolean players = tab == Tab.PLAYERS;
        if (searchBox != null) {
            searchBox.visible = !players;
            searchBox.active = !players;
            // 第 110 轮：方块页搜的是「方块名 / 方块注册名」，提示文案跟着页签走
            // （共用一个提示会让玩家在方块页看到「搜索物品名」，以为搜不到方块）
            searchBox.setHint(Component.translatable(LANG
                + (tab == Tab.BLOCKS ? "search.blocks.hint" : "search.hint")));
        }
        if (addBox != null) {
            addBox.visible = players;
            addBox.active = players;
            if (!players) {
                addBox.setValue("");
            }
        }
        if (addButton != null) {
            addButton.visible = players;
            addButton.active = players;
        }
        for (int i = 0; i < TAB_COUNT; i++) {
            if (tabButtons[i] != null) {
                tabButtons[i].active = i != tab.ordinal();
            }
        }
        refreshModeButton();
    }

    /** 滚动条状态与行数同步（只在渲染帧 / 行重建后调用）。 */
    private void refreshScrollbar() {
        if (scrollbar == null) {
            return;
        }
        final int max = Math.max(0, rows.size() - VISIBLE_ROWS);
        if (firstVisibleRow > max) {
            firstVisibleRow = max;
        }
        if (firstVisibleRow < 0) {
            firstVisibleRow = 0;
        }
        scrollbar.setMaxOffset(max);
        scrollbar.setEnabled(max > 0);
        scrollbar.setOffset(firstVisibleRow);
    }

    // ==================== 渲染 ====================

    @Override
    public void render(final GuiGraphics guiGraphics, final int mouseX, final int mouseY,
                       final float partialTick) {
        refreshModeButton();
        refreshScrollbar();
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        // 滚动条用 addWidget 登记（只收事件、不进 renderables），因此这里手动画一次
        // —— 与高级装填器界面同一写法（那边也是 addWidget + 手动 render）。
        if (scrollbar != null) {
            scrollbar.render(guiGraphics, mouseX, mouseY, partialTick);
        }
        guiGraphics.drawString(font, title, px + TITLE_X, py + TITLE_Y, COLOR_TITLE, false);
        // 提示行：优先显示「名单已满 / 输入非法」这类临时消息，否则显示当前模式的语义说明。
        // 第 104 轮：放不下时整体缩字号完整显示（下限 MIN_SCALE），不再截断加省略号。
        RsccTextFit.drawFitLine(guiGraphics, font, hint(),
            px + HINT_X, py + HINT_Y, HINT_MAX_W, addStatus == null ? COLOR_TEXT : COLOR_WARN);
        drawList(guiGraphics, mouseX, mouseY);
        renderTooltips(guiGraphics, mouseX, mouseY);
    }

    /**
     * 提示行要说的话（临时消息 或 模式说明）。
     * <p>返回 {@link Component}（而不是语言键字符串）：临时消息带参数（名单上限条数），
     * 「键 + 参数」只有 Component 能表达 —— 见字段 {@link #addStatus} 的注释。</p>
     */
    private Component hint() {
        if (addStatus != null) {
            return addStatus;
        }
        return Component.translatable(LANG + (whitelist ? "mode.hint.whitelist" : "mode.hint.blacklist"));
    }

    /** 列表：外框 + 底色 + 行（勾选框 / 图标 / 名字 / 灰后缀）+ 空态提示。 */
    private void drawList(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        final int left = px + LIST_X;
        final int top = py + LIST_Y;
        guiGraphics.fill(left - 1, top - 1, left + LIST_W + 1, top + LIST_H + 1, COLOR_BORDER);
        guiGraphics.fill(left, top, left + LIST_W, top + LIST_H, COLOR_LIST_BG);
        if (rows.isEmpty()) {
            guiGraphics.drawString(font, emptyListText(), left + 4, top + 4, COLOR_DIM, false);
            return;
        }
        final int hovered = rowAt(mouseX, mouseY);
        for (int row = 0; row < VISIBLE_ROWS; row++) {
            final int index = firstVisibleRow + row;
            if (index >= rows.size()) {
                break;
            }
            final int y = top + row * ROW_H;
            final Row data = rows.get(index);
            if (data.group() != null) {
                // 第 125 轮：组头行是另一种行型（三态勾选框 / 代表图标 / ▶▼ + 家族名 / 已选计数），
                // 单独一个绘制方法；它自己处理 hover 与「只命中成员」的高亮底色。
                drawGroupRow(guiGraphics, left, y, data.group(), index == hovered);
                continue;
            }
            if (index == hovered) {
                // 悬停高亮只画到滚动条左缘：滚动条压在列表最右 10px，不能被行底色盖住
                guiGraphics.fill(left, y, left + LIST_W - SCROLL_W, y + ROW_H, COLOR_ROW_HOVER);
            }
            final int textY = y + (ROW_H - LINE_H) / 2;
            // 第 125 轮：折叠组成员整行右移 GROUP_INDENT（勾选框 / 图标 / 名字一起，层级一眼可见）
            final int rowIndent = data.member() ? GROUP_INDENT : 0;
            drawCheckbox(guiGraphics, left + ROW_CHECK_X + rowIndent,
                y + (ROW_H - ROW_CHECK_SIZE) / 2, isChecked(data));
            if (data.player()) {
                drawPlayerHead(guiGraphics, left + ROW_ICON_X + rowIndent, y + 1,
                    headTextureOf(data.playerId()));
            } else if (data.blockId() != null) {
                // 第 110 轮：方块行用**方块物品**的图标（用户的「世界中的方块」在界面上总得有个
                // 认得出的图标；方块本身没有贴图可画 —— 玻璃 / 台阶这类要画成方块模型才像样，
                // 那属于 3D 渲染，成本远高于收益）。图标来自行重建时的缓存，渲染路径零查询。
                McGui.slotFrame(guiGraphics, left + ROW_ICON_X - 1 + rowIndent, y);
                guiGraphics.renderItem(blockIconOf(data.blockId()),
                    left + ROW_ICON_X + rowIndent, y + 1);
            } else if (data.item() != null) {
                McGui.slotFrame(guiGraphics, left + ROW_ICON_X - 1 + rowIndent, y);
                guiGraphics.renderItem(ChargerFilterCandidates.icon(data.item()),
                    left + ROW_ICON_X + rowIndent, y + 1);
            }
            // 后缀（灰）先量宽再决定名字能用多少像素：任何语言下都不重叠、不压滚动条
            final String suffix = suffixOf(data);
            final int suffixW = font.width(suffix);
            final int suffixX = left + ROW_SUFFIX_RIGHT - suffixW;
            final int nameMax = Math.max(8, suffixX - (left + ROW_NAME_X) - NAME_SUFFIX_GAP);
            // 成员行的可用宽度扣掉缩进（组头与普通行仍是完整预算）：名字仍然「只缩字号、不截断」
            final int rowNameMax = Math.max(8, nameMax - rowIndent);
            // 名字（玩家名 / 物品名 = 自由文本）：放不下整体缩字号完整显示，不截断、不补省略号；
            // 完整信息（物品 ID / UUID / 在线状态）另在行 tooltip 里逐字给出。
            RsccTextFit.drawFitLine(guiGraphics, font, Component.literal(displayNameOf(data)),
                left + ROW_NAME_X + rowIndent, textY, rowNameMax, COLOR_TEXT);
            // 在线状态：第 100 轮起不再画在头像上（会盖住真脸），改为「在线」文字上绿色 ——
            // 信息与原来完全等价（tooltip 里也有同样的在线/离线文字），只是不再压皮肤像素。
            // 第 107 轮：物品行的后缀是空串（id 不进列表，只进 tooltip），空串不画 ——
            // 省掉一次无意义的绘制，也避免以后有人误把「宽度 0」当成「后缀不存在」的证据。
            if (!suffix.isEmpty()) {
                guiGraphics.drawString(font, suffix, suffixX, textY,
                    data.player() && data.online() ? COLOR_ONLINE : COLOR_DIM, false);
            }
        }
    }

    /**
     * 画一行的<b>组头</b>（第 125 轮）：{@code [三态勾选框] [代表图标] ▶/▼ 名字（N 色）  已选/总数}。
     *
     * <h2>几何与普通行逐列对齐</h2>
     * <p>勾选框 / 图标 / 名字起点全部复用 {@link #ROW_CHECK_X} / {@link #ROW_ICON_X} /
     * {@link #ROW_NAME_X}，因此组头和普通行看起来是同一张表；成员行再整体右移
     * {@link #GROUP_INDENT} ⇒ 一眼看出「这几行属于上面这一组」。</p>
     *
     * <p><b>代表图标为什么用家族基础条目</b>：玩家要认的是「这是控制器」，不是「这是白色控制器」，
     * 基础条目的图标正是这个意思；图标栈在构建家族时缓存好（{@code Family#icon()}），
     * 渲染路径<b>不查注册表、不 new ItemStack</b>。</p>
     *
     * <p>名字走 {@link RsccTextFit#drawFitLine}（放不下缩字号、绝不截断补省略号）；
     * 右侧「已选 / 总数」是纯数字，照旧用 {@code drawString}（与其它行尾小字同一画法）。</p>
     */
    private void drawGroupRow(final GuiGraphics guiGraphics, final int left, final int y,
                              final GroupRow group, final boolean hovered) {
        final int right = left + LIST_W - SCROLL_W;
        if (group.highlighted()) {
            // 只命中成员、组名本身没命中：淡绿底表示「这组是被搜索带出来的」（见 COLOR_GROUP_HIT）
            guiGraphics.fill(left, y, right, y + ROW_H, COLOR_GROUP_HIT);
        }
        if (hovered) {
            guiGraphics.fill(left, y, right, y + ROW_H, COLOR_ROW_HOVER);
        }
        final int textY = y + (ROW_H - LINE_H) / 2;
        drawTriStateCheckbox(guiGraphics, left + ROW_CHECK_X, y + (ROW_H - ROW_CHECK_SIZE) / 2,
            group.checkState());
        McGui.slotFrame(guiGraphics, left + ROW_ICON_X - 1, y);
        guiGraphics.renderItem(group.family().icon(), left + ROW_ICON_X, y + 1);
        // 与 suffixOf(组头行) 同一口径；这里直接拼是为了渲染路径不 new Row（每帧一次也不该有分配）
        final String suffix = group.selected() + "/" + group.total();
        final int suffixW = font.width(suffix);
        final int suffixX = left + ROW_SUFFIX_RIGHT - suffixW;
        final int nameMax = Math.max(8, suffixX - (left + ROW_NAME_X) - NAME_SUFFIX_GAP);
        RsccTextFit.drawFitLine(guiGraphics, font, Component.literal(group.label()),
            left + ROW_NAME_X, textY, nameMax, COLOR_TEXT);
        if (!suffix.isEmpty()) {
            guiGraphics.drawString(font, suffix, suffixX, textY, COLOR_DIM, false);
        }
    }

    /**
     * 三态勾选框（第 125 轮）：关 = 空框，开 = 绿底，<b>部分</b> = 绿底上再画一条绿横杠。
     *
     * <p>为什么不复用 {@link #drawCheckbox}：那个是二态画法（别的行用），
     * 部分选中如果画成「开」会骗人（勾选集合里其实还缺成员）。横杠是「部分」的通用写法，
     * 且只多两次 {@code fill}，不新增任何贴图。</p>
     */
    private static void drawTriStateCheckbox(final GuiGraphics guiGraphics, final int x, final int y,
                                             final int state) {
        guiGraphics.fill(x, y, x + ROW_CHECK_SIZE, y + ROW_CHECK_SIZE, COLOR_BORDER);
        guiGraphics.fill(x + 1, y + 1, x + ROW_CHECK_SIZE - 1, y + ROW_CHECK_SIZE - 1,
            state == CHECK_ON ? COLOR_CHECK_ON : COLOR_CHECK_OFF);
        if (state == CHECK_PARTIAL) {
            final int barWidth = ROW_CHECK_SIZE - 4;
            final int barY = y + ROW_CHECK_SIZE / 2 - 1;
            guiGraphics.fill(x + 2, barY, x + 2 + barWidth, barY + 2, COLOR_CHECK_ON);
        }
    }

    /** 空列表要说的话（候选池为空 / 搜索无命中 / 还没玩家记录，几种是不同原因）。 */
    private String emptyListText() {
        if (tab == Tab.PLAYERS) {
            return Component.translatable(LANG + "list.empty.players").getString();
        }
        if (tab == Tab.BLOCKS) {
            // 第 110 轮：方块的候选池来自服务端（范围内检测到的可充方块）。
            // 「一个候选都没有」与「搜索没命中」是两件不同的事，文案必须分开 ——
            // 前者多半是方块充电总闸关着 / 机器没电 / 刚放下还没扫，后者只是搜错了。
            if (seenBlocks.isEmpty() && blocks.isEmpty()) {
                return Component.translatable(LANG + "list.empty.blocks").getString();
            }
            return Component.translatable(LANG + "list.empty.blocks.search").getString();
        }
        if (ChargerFilterCandidates.isEmpty()) {
            return Component.translatable(LANG + "list.empty").getString();
        }
        return Component.translatable(LANG + "list.empty.search").getString();
    }

    /** 行显示名：物品名 / 方块名 / 玩家名 / UUID 条目显示「?」+ 短 UUID。 */
    private String displayNameOf(final Row row) {
        if (row.group() != null) {
            // 第 125 轮：组头的「纯名字」（不含 ▶/▼ 与「N 色」后缀 —— 那个后缀在族名里，
            // 这里只给 tooltip 首行 / 列表名字用；列表上的组头文案是 GroupRow#label）
            return row.group().family().displayName().getString();
        }
        if (!row.player()) {
            // 第 110 轮：方块行显示的是「方块物品」的本地化名字（与物品两页同一观感），
            // 完整方块注册名只在行 tooltip 里（第 107 轮口径：列表只认名字，要找 id 就打字搜）。
            if (row.blockId() != null) {
                return blockNameOf(row.blockId());
            }
            return row.item() == null ? "" : ChargerFilterCandidates.nameOf(row.item());
        }
        if (row.playerName() != null && !row.playerName().isEmpty()) {
            return row.playerName();
        }
        return row.playerId() == null ? "?" : "?" + shortUuid(row.playerId());
    }

    /**
     * 行尾小字：物品 = <b>空</b>（第 107 轮起）；玩家 = 在线状态 / 「手动」。
     *
     * <h2>第 107 轮：物品行不再显示注册 id（用户口径）</h2>
     * <p>用户原话：「<i>你就直接显示它这个物品名字就可以了，它这个 id 的话呢你 tooltip 中加入就可以了，
     * 或者说你根本就不需要加入他的物品 id，你显示他的中文名就可以了，但是要支持搜索 id。</i>」</p>
     * <p>因此物品行的<b>名字右侧不再画注册 id 的 path</b>（原来这里是灰色的 {@code energy_tablet}
     * 这类英文字串，占了名字能用的像素、又不是玩家要认的东西）。完整注册 id 仍然逐字出现在
     * 行 tooltip 里（{@link #renderRowTooltip} 的 {@code row.id} 行），
     * 搜索也照旧支持「物品名 或 注册 id 子串」（{@link ChargerFilterCandidates#search}）——
     * 「界面上只认名字、要找 id 就打字搜」这两件事互不冲突。</p>
     *
     * <p>第 100 轮起玩家行的「在线」这两个字用 {@link #COLOR_ONLINE} 上色（离线 / 手动仍是
     * {@link #COLOR_DIM}）—— 原来画在头像左上角的绿角标会盖住真脸，这个文字上色就是它的替代
     * （信息等价）。</p>
     */
    private String suffixOf(final Row row) {
        if (row.group() != null) {
            // 第 125 轮：组头行尾显示「已选 / 家族总数」（纯数字，不需要语言键；
            // 两个数都按整个家族算，与搜索词无关 —— 见 GroupRow 的注释）
            return row.group().selected() + "/" + row.group().total();
        }
        if (!row.player()) {
            // 见方法注释：物品行没有行尾小字（id 只在 tooltip 里）
            return "";
        }
        if (row.manual()) {
            return Component.translatable(LANG + "row.player.manual").getString();
        }
        return Component.translatable(LANG + (row.online() ? "row.player.online"
            : "row.player.offline")).getString();
    }

    /** UUID 前 8 位（够区分、又不至于把名字挤没）。 */
    private static String shortUuid(final UUID id) {
        final String text = id.toString();
        return text.length() > 8 ? text.substring(0, 8) : text;
    }

    /** 9×9 勾选框（与工程里其它勾选框同一画法：1px 描边 + 内部填充）。 */
    private static void drawCheckbox(final GuiGraphics guiGraphics, final int x, final int y,
                                     final boolean on) {
        guiGraphics.fill(x, y, x + ROW_CHECK_SIZE, y + ROW_CHECK_SIZE, COLOR_BORDER);
        guiGraphics.fill(x + 1, y + 1, x + ROW_CHECK_SIZE - 1, y + ROW_CHECK_SIZE - 1,
            on ? COLOR_CHECK_ON : COLOR_CHECK_OFF);
    }

    /**
     * 玩家头像：<b>从真实皮肤图裁出来的头</b>（16×16），拿不到纹理时只画中性灰底。
     *
     * <h2>第 100 轮：用户否决了第 98 轮的自绘占位</h2>
     * <p>第 98 轮这里画的是「肤色底 + 头发 + 两只眼睛」（理由见当时注释：离线玩家取不到皮肤、
     * 怕在渲染循环里触发异步加载）。用户实机看到后明确否决：「<i>他这个头像什么鬼啊？……
     * 应该就是皮肤裁剪的那个头部不就行了吗？……你这个自己画的太诡异了。</i>」—— 于是这里
     * <b>不再有任何自绘五官</b>，改为直接调原版 {@link PlayerFaceRenderer#draw(GuiGraphics,
     * ResourceLocation, int, int, int)}（已 {@code javap} 核过 1.21.1 字节码）：</p>
     * <pre>
     *   第 1 层：blit(皮肤, x, y, 16, 16, u=8,  v=8,  8, 8, 64, 64)  ← 头部正面
     *   第 2 层：blit(皮肤, x, y, 16, 16, u=40, v=8,  8, 8, 64, 64)  ← 帽子 / 第二层皮肤（开混合）
     * </pre>
     * <p>即「与物品栏头像同一套像素来源」，只是绘制尺寸是 16×16 ⇒ 皮肤图上 8×8 的像素被放大 2 倍，
     * 16×16 的行内图标仍然逐像素清晰（不模糊、不插值拉伸成块）。</p>
     *
     * <p><b>为什么「异步加载」不再是问题</b>：皮肤是否已下载由原版 {@code SkinManager} 负责，
     * 它会把加载好的 {@link PlayerSkin} 挂到 {@link PlayerInfo} 上；本界面只在行重建 / 20 tick 兜底时
     * 解析一次纹理（见 {@link #resolveHeadTexture}），渲染路径只做一次 map 查询。因此
     * <b>既不会每帧触发异步加载，也不会永远停在默认皮肤</b>。</p>
     *
     * <p><b>在线角标已移走</b>：原来左上角那块 3×3 绿块会盖住真脸的眼睛，现在在线状态只由行尾
     * 「在线 / 离线」文字（在线时用 {@link #COLOR_ONLINE} 上色）表达。</p>
     *
     * @param texture 已缓存的皮肤纹理；{@code null} ⇒ 中性灰底（<b>不画任何五官</b>）
     */
    private static void drawPlayerHead(final GuiGraphics guiGraphics, final int x, final int y,
                                       @Nullable final ResourceLocation texture) {
        McGui.slotFrame(guiGraphics, x - 1, y - 1);
        if (texture == null) {
            guiGraphics.fill(x, y, x + ROW_ICON_SIZE, y + ROW_ICON_SIZE, COLOR_HEAD_NEUTRAL);
            return;
        }
        PlayerFaceRenderer.draw(guiGraphics, texture, x, y, ROW_ICON_SIZE);
        // PlayerFaceRenderer 内部为帽子层 enableBlend / 之后 disableBlend（javap 已核）；
        // 本界面紧接着还要画半透明的行悬停底色与勾选框，因此这里把混合状态还原成 GUI 常态。
        RenderSystem.enableBlend();
    }

    // ==================== tooltip（本模组 GUI 不会自动渲染，必须手动） ====================

    /** 鼠标是否在某个面板内矩形里（判定与绘制共用同一批几何常量）。 */
    private boolean inBox(final double mouseX, final double mouseY,
                          final int x, final int y, final int w, final int h) {
        return mouseX >= px + x && mouseX < px + x + w
            && mouseY >= py + y && mouseY < py + y + h;
    }

    /** 每帧只画<b>一个</b> tooltip（先判到的先画，立即 return，绝不叠两个框）。 */
    private void renderTooltips(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        // ① 列表行（内容最丰富，优先级最高）
        final int row = rowAt(mouseX, mouseY);
        if (row >= 0) {
            renderRowTooltip(guiGraphics, rows.get(row), mouseX, mouseY);
            return;
        }
        // ② 页签
        for (int i = 0; i < TAB_COUNT; i++) {
            if (inBox(mouseX, mouseY, TAB_X + i * (TAB_W + TAB_GAP), TAB_Y, TAB_W, TAB_H)) {
                renderTip(guiGraphics, LANG + Tab.values()[i].tipKey, mouseX, mouseY);
                return;
            }
        }
        // ③ 模式按钮
        if (inBox(mouseX, mouseY, MODE_X, MODE_Y, MODE_W, MODE_H)) {
            renderTip(guiGraphics, LANG + "mode.tip", mouseX, mouseY);
            return;
        }
        // ④ 搜索框（玩家页隐藏时不出提示）
        if (tab != Tab.PLAYERS && inBox(mouseX, mouseY, SEARCH_X, SEARCH_Y, SEARCH_W, SEARCH_H)) {
            renderTip(guiGraphics, LANG + "search.tip", mouseX, mouseY);
            return;
        }
        // ⑤ 滚动条
        if (inBox(mouseX, mouseY, SCROLL_X, LIST_Y, SCROLL_W, LIST_H)) {
            renderTip(guiGraphics, LANG + "list.scroll.tip", mouseX, mouseY);
            return;
        }
        // ⑥ 玩家页的添加框 / 添加按钮
        if (tab == Tab.PLAYERS) {
            if (inBox(mouseX, mouseY, ADD_X, ADD_Y, ADD_W, ADD_H)) {
                renderTip(guiGraphics, LANG + "add.tip", mouseX, mouseY);
                return;
            }
            if (inBox(mouseX, mouseY, ADD_BTN_X, ADD_Y, ADD_BTN_W, ADD_H)) {
                renderTip(guiGraphics, LANG + "add.tip", mouseX, mouseY);
                return;
            }
        }
        // ⑦ 底部按钮
        if (inBox(mouseX, mouseY, DONE_X, DONE_Y, DONE_W, BTN_H)) {
            renderTip(guiGraphics, LANG + "done.tip", mouseX, mouseY);
            return;
        }
        if (inBox(mouseX, mouseY, BACK_X, DONE_Y, BACK_W, BTN_H)) {
            renderTip(guiGraphics, LANG + "back.tip", mouseX, mouseY);
            return;
        }
        // ⑧ 提示行：被截断时给出完整文本（工程硬规则：放不下的完整文本必须能用 tooltip 看到）
        if (inBox(mouseX, mouseY, HINT_X, HINT_Y, HINT_MAX_W, LINE_H)) {
            renderTip(guiGraphics, hint(), mouseX, mouseY);
        }
    }

    /** 行的 tooltip：完整名字 / id / UUID / 在线状态 / 手动标记 / 操作说明。 */
    private void renderRowTooltip(final GuiGraphics guiGraphics, final Row row,
                                  final int mouseX, final int mouseY) {
        if (row.group() != null) {
            renderGroupTooltip(guiGraphics, row.group(), mouseX, mouseY);
            return;
        }
        final List<Component> lines = new ArrayList<>(5);
        lines.add(Component.literal(displayNameOf(row)));
        if (row.player()) {
            if (row.playerId() != null) {
                lines.add(Component.translatable(LANG + "row.player.uuid", row.playerId().toString()));
            }
            if (row.manual()) {
                lines.add(Component.translatable(LANG + "row.player.manual"));
            } else {
                lines.add(Component.translatable(LANG + (row.online() ? "row.player.online"
                    : "row.player.offline")));
            }
        } else if (row.blockId() != null) {
            // 第 110 轮：方块行给完整**方块注册名**（不是方块物品的 id —— 两者在多数方块上同名，
            // 但有相当一批方块没有物品，而这一页拦的正是「世界里的方块」）。
            lines.add(Component.translatable(LANG + "row.block.id", row.blockId().toString()));
        } else {
            final ResourceLocation id = row.item() == null ? null : ChargerFilterCandidates.idOf(row.item());
            if (id != null) {
                lines.add(Component.translatable(LANG + "row.id", id.toString()));
            }
        }
        lines.add(Component.translatable(LANG + "row.tip"));
        guiGraphics.renderTooltip(font, lines, java.util.Optional.empty(), mouseX, mouseY);
    }

    /**
     * 组头行的 tooltip（第 125 轮）：家族名 / 家族数量与已选数 / 「为什么这组会出现」/ 操作说明。
     *
     * <p>「只命中成员」时多一行说明 —— 玩家看到一行淡绿底却不知道为什么，那一行高亮就等于没解释。
     * 与其它行 tooltip 同一画法（同一个 {@code renderTooltip}、每帧最多一个框）。</p>
     */
    private void renderGroupTooltip(final GuiGraphics guiGraphics, final GroupRow group,
                                    final int mouseX, final int mouseY) {
        final List<Component> lines = new ArrayList<>(4);
        lines.add(group.family().displayName());
        lines.add(Component.translatable(LANG + "group.detail", group.total(), group.selected()));
        if (group.highlighted()) {
            lines.add(Component.translatable(LANG + "group.hit"));
        }
        lines.add(Component.translatable(LANG + "group.tip"));
        guiGraphics.renderTooltip(font, lines, java.util.Optional.empty(), mouseX, mouseY);
    }

    /** 单行 tooltip（走工程唯一实现 GhostMarkerRenderer#renderLines，保证与其它界面同一套定位）。 */
    /** 按语言键画单行 tooltip。 */
    private void renderTip(final GuiGraphics guiGraphics, final String key,
                           final int mouseX, final int mouseY) {
        renderTip(guiGraphics, Component.translatable(key), mouseX, mouseY);
    }

    /**
     * 画单行 tooltip（已翻译好的 Component 版本）。
     * <p>提示行既可能是「语言键 + 参数」（名单已满会带条数），也可能是纯键，
     * 因此 tooltip 与行内文字共用<b>同一个</b> {@link #hint()} 结果，绝不各拼一份。</p>
     */
    private void renderTip(final GuiGraphics guiGraphics, final Component line,
                           final int mouseX, final int mouseY) {
        GhostMarkerRenderer.renderLines(guiGraphics, font, List.of(line), mouseX, mouseY);
    }

    // ==================== 命中判定 ====================

    /** 鼠标落在列表的哪一行（返回<b>行模型下标</b>；不在行上 / 在滚动条上 ⇒ -1）。 */
    private int rowAt(final double mouseX, final double mouseY) {
        if (mouseX < px + LIST_X || mouseX >= px + SCROLL_X
            || mouseY < py + LIST_Y || mouseY >= py + LIST_Y + LIST_H) {
            return -1;
        }
        final int row = (int) ((mouseY - (py + LIST_Y)) / ROW_H);
        final int index = firstVisibleRow + row;
        return row >= 0 && row < VISIBLE_ROWS && index >= 0 && index < rows.size() ? index : -1;
    }

    // ==================== 事件 ====================

    @Override
    public boolean mouseClicked(final double mouseX, final double mouseY, final int button) {
        if (button == 0) {
            final int index = rowAt(mouseX, mouseY);
            if (index >= 0) {
                final Row row = rows.get(index);
                if (row.group() != null) {
                    // 第 125 轮：组头行两种点击 —— 勾选框 = 整组全选 / 全不选，其它位置 = 展开 / 折叠。
                    // 判定与绘制共用 ROW_CHECK_X / ROW_CHECK_SIZE（不收「大概点到了」的模糊区域）
                    if (onGroupCheckbox(mouseX)) {
                        toggleGroupSelection(row.group());
                    } else {
                        toggleGroupExpanded(row.group().family());
                    }
                    return true;
                }
                toggleRow(row);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(final double mouseX, final double mouseY,
                                 final double scrollX, final double scrollY) {
        if (inBox(mouseX, mouseY, LIST_X, LIST_Y, LIST_W, LIST_H)) {
            final int max = Math.max(0, rows.size() - VISIBLE_ROWS);
            if (scrollY != 0.0 && max > 0) {
                firstVisibleRow = Math.max(0, Math.min(max,
                    firstVisibleRow - (int) Math.signum(scrollY)));
                if (scrollbar != null) {
                    scrollbar.setOffset(firstVisibleRow);
                }
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(final int keyCode, final int scanCode, final int modifiers) {
        if ((keyCode == 257 || keyCode == 335) && addBox != null && addBox.isFocused()
            && tab == Tab.PLAYERS) {
            onAdd(); // 回车 = 点「添加」（与本工程其它输入框同一习惯）
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
