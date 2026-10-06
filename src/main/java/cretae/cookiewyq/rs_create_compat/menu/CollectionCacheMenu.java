package cretae.cookiewyq.rs_create_compat.menu;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.Config;
import cretae.cookiewyq.rs_create_compat.block.entity.CollectionCacheBlockEntity;
import cretae.cookiewyq.rs_create_compat.network.SyncCollectionMarkersPacket;
import cretae.cookiewyq.rs_create_compat.support.AbsorbType;
import cretae.cookiewyq.rs_create_compat.support.MarkerEntry;
import cretae.cookiewyq.rs_create_compat.support.XpForm;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerSynchronizer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 归流缓存仓菜单（v4 重排布局，见 {@code tmp_textures/COLLECTION_CACHE_GUI_DOC_V4.md}）：
 * 上方「匹配区」12 列 × 4 行 ghost 槽，下方「缓存区」12 列 × 4 行 real 槽，各自独占一条通栏面板、
 * 滚动条轨道贴在各自面板内右缘；下方一行矮面板放 4 个吸取开关，
 * 再下面是 6 格横排升级槽（物品栏正上方），底部玩家背包 / 快捷栏。
 * <p>两个区域<b>各自独立</b>的滚动窗口：可见窗口各 48 格（12 列 × 4 行），
 * 窗口槽位按各自偏移映射到全局下标，偏移由服务端权威并经 ContainerData 回传
 * （沿用本模组“可见窗口槽 + 偏移同步”的既有方案）。</p>
 * <p>槽位下标：0..47 = 匹配区窗口，48..95 = 缓存区窗口，96..101 = 升级槽，102..137 = 玩家背包 / 快捷栏。</p>
 */
public class CollectionCacheMenu extends AbstractContainerMenu
    implements cretae.cookiewyq.rs_create_compat.support.RsccRedstoneModeHolder {
    /** 匹配区 / 缓存区槽位列数（V4 文档 §3.2 / §3.3）。 */
    public static final int GRID_COLS = 12;
    /** 每个区域可见行数（12×4 = 48 格）。 */
    public static final int VISIBLE_ROWS = 4;
    /** 玩家背包 / 快捷栏列数。 */
    public static final int INV_COLS = 9;
    /** 匹配区可见窗口（12 列 × 4 行 = 48 格）。 */
    public static final int MATCH_WINDOW = GRID_COLS * VISIBLE_ROWS;
    /**
     * 匹配区总容量（格）= 每页 {@link #MATCH_WINDOW} 格 × 方块实体的 {@code MATCH_PAGES} 页。
     * <p>匹配区的可用页数由<b>容量</b>决定：若改由「已用到的最高下标」决定，第一页（48 格）填满前
     * 最大偏移恒为 0，玩家既翻不到第二页、也就永远放不下第 49 个标记（现象就是「滚动条滚不动」）。</p>
     */
    public static final int MATCH_CAPACITY = MATCH_WINDOW * CollectionCacheBlockEntity.MATCH_PAGES;
    /** 缓存区可见窗口（12 列 × 4 行 = 48 格）。 */
    public static final int CACHE_WINDOW = GRID_COLS * VISIBLE_ROWS;
    /** 窗口槽位总数（匹配 48 + 缓存 48）。 */
    public static final int WINDOW_SLOTS = MATCH_WINDOW + CACHE_WINDOW;

    public static final int UPGRADE_START = WINDOW_SLOTS;
    public static final int UPGRADE_COUNT = CollectionCacheBlockEntity.UPGRADE_SLOTS;
    public static final int PLAYER_START = UPGRADE_START + UPGRADE_COUNT;

    /** 允许放入的升级种类（与插件槽 tooltip / RS 升级目的地注册一致）：范围升级每级上限 +25，创造范围升级无限。 */
    private static final List<String> ALLOWED_UPGRADES =
        List.of("speed_upgrade", "stack_upgrade", "range_upgrade", "creative_range_upgrade");

    /** 本机允许放入的升级种类（供 Screen 层渲染空槽 tooltip，避免名单写两份）。 */
    public static List<String> allowedUpgrades() {
        return ALLOWED_UPGRADES;
    }

    // ===== 布局坐标（全部为 Menu 坐标 = 文档“精灵坐标 + 1”，见 COLLECTION_CACHE_GUI_DOC_V4.md） =====
    /** 匹配区窗口：Menu (9,33) 起，12×4（精灵 (8,32)）。 */
    private static final int MATCH_X0 = 9;
    private static final int MATCH_Y0 = 33;
    /** 缓存区窗口：Menu (9,119) 起，12×4（精灵 (8,118)）。 */
    private static final int CACHE_X0 = 9;
    private static final int CACHE_Y0 = 119;
    /** 升级槽：Menu (76,219) 起，6 格横排（精灵 (75,218)，物品栏正上方居中）。 */
    private static final int UPGRADE_X0 = 76;
    private static final int UPGRADE_Y0 = 219;
    /** 玩家主背包：Menu (43,242) 起，3 行 9 列（精灵 (42,241)）。 */
    private static final int INV_X0 = 43;
    private static final int INV_Y0 = 242;
    /** 快捷栏 1×9 的 y（精灵 (42,301) → Menu (43,302)）。 */
    private static final int HOTBAR_Y = 302;

    /**
     * 数据槽索引表（前端按这些常量取值；服务端权威值经数据槽回传）：
     * <table>
     *     <tr><td>0</td><td>速度升级数</td></tr>
     *     <tr><td>1</td><td>堆叠升级数</td></tr>
     *     <tr><td>2</td><td>已收集物品数（截断，界面已不显示，仅保留数据位）</td></tr>
     *     <tr><td>3</td><td>已入网物品数（截断，界面已不显示，仅保留数据位）</td></tr>
     *     <tr><td>4</td><td>缓存每格上限</td></tr>
     *     <tr><td>5</td><td>吸取开关：物品（1=开 / 0=关）</td></tr>
     *     <tr><td>6</td><td>吸取开关：流体</td></tr>
     *     <tr><td>7</td><td>吸取开关：气体</td></tr>
     *     <tr><td>8</td><td>吸取开关：经验</td></tr>
     *     <tr><td>9</td><td>流体缓存已用量（mB，截断）</td></tr>
     *     <tr><td>10</td><td>收集范围 X（格）</td></tr>
     *     <tr><td>11</td><td>收集范围 Y（格）</td></tr>
     *     <tr><td>12</td><td>收集范围 Z（格）</td></tr>
     *     <tr><td>13</td><td>范围升级数（每级上限 +25）</td></tr>
     *     <tr><td>14</td><td>是否无限范围（创造范围升级，1=是）</td></tr>
     *     <tr><td>15</td><td>匹配区偏移（页）</td></tr>
     *     <tr><td>16</td><td>匹配区最大偏移（页）</td></tr>
     *     <tr><td>17</td><td>缓存区偏移（页）</td></tr>
     *     <tr><td>18</td><td>缓存区最大偏移（页）</td></tr>
     *     <tr><td>19</td><td>「多个输入面」开关位（位 = Direction.ordinal()，默认 63 = 六面全开）</td></tr>
     *     <tr><td>20</td><td>红石模式（0=忽略 / 1=高电平工作 / 2=低电平工作，与 RS 原版机器同一套编码）</td></tr>
     *     <tr><td>21</td><td>经验形态（{@link XpForm#ordinal()}：0=经验球实体 / 1=液态经验；「自动」已删除）</td></tr>
     *     <tr><td>22</td><td>「吸取所有物品」开关（1=开 / 0=关；开启后匹配区失效）</td></tr>
     *     <tr><td>23</td><td>「反转匹配」开关（1=开 / 0=关；匹配区白名单 ↔ 黑名单）</td></tr>
     *     <tr><td>24</td><td>缓存区物品总件数（销毁确认界面展示；截断）</td></tr>
     *     <tr><td>25</td><td>缓存区非空堆数（≈ 资源种类数）</td></tr>
     *     <tr><td>26</td><td>流体缓存种类数</td></tr>
     * </table>
     * 索引 0..14 由方块实体的 {@code getContainerData()} 提供；15..18 为本菜单的滚动偏移；19 为输入面开关位；
     * 20（红石模式）与 21（经验形态）是<b>菜单自己的槽位下标</b>，读值时会显式映射到方块实体对应的槽
     * （红石模式 → 方块实体槽 16，见 {@link #BLOCK_DATA_REDSTONE_MODE}）；
     * 22 / 23（两个新开关）同理映射到方块实体槽 17 / 18；24 / 25 / 26（销毁确认用快照）映射到方块实体槽 19 / 20 / 21。
     */
    public static final int DATA_SLOT_COUNT = 29;
    /** 4 个吸取开关的起始索引：+ {@link AbsorbType#ordinal()}。 */
    public static final int DATA_ABSORB_ITEM = 5;
    public static final int DATA_ABSORB_FLUID = 6;
    public static final int DATA_ABSORB_GAS = 7;
    public static final int DATA_ABSORB_EXP = 8;
    /** 流体缓存已用量（mB，截断）。 */
    public static final int DATA_FLUID_STORED = 9;
    /** 三轴收集范围 / 范围升级数 / 是否无限范围（与方块实体数据槽索引一一对应）。 */
    private static final int DATA_RADIUS_X = 10;
    private static final int DATA_RADIUS_Y = 11;
    private static final int DATA_RADIUS_Z = 12;
    private static final int DATA_RADIUS_UPGRADES = 13;
    private static final int DATA_RADIUS_INFINITE = 14;
    private static final int DATA_MATCH_OFFSET = 15;
    private static final int DATA_MATCH_MAX = 16;
    private static final int DATA_CACHE_OFFSET = 17;
    private static final int DATA_CACHE_MAX = 18;
    /** 「多个输入面」开关位（位 = Direction.ordinal()）。 */
    private static final int DATA_INPUT_FACES = 19;
    /** 红石模式（0=忽略 / 1=高电平工作 / 2=低电平工作）：与方块实体的数据槽索引一一对应。 */
    private static final int DATA_REDSTONE_MODE = 20;
    /**
     * 方块实体<b>自己</b>暴露红石模式的下标（见 {@code CollectionCacheBlockEntity#getContainerData} 的 {@code case 16}）。
     * <p>菜单与方块实体的数据槽编号各自独立：菜单把红石模式放在 20（避免改动既有同步槽位顺序），
     * 因此读值时必须显式映射到方块实体的 16 —— 直接用默认分支会读到不存在的槽而恒为 0。</p>
     */
    private static final int BLOCK_DATA_REDSTONE_MODE = 16;
    /** 经验形态（{@link XpForm#ordinal()}）：服务端写入方块实体、客户端据此刷新按钮文案。 */
    private static final int DATA_XP_FORM = 21;
    /**
     * 第 7 轮新增的两个开关（1=开 / 0=关）：
     * 22 = 吸取所有物品（开启后匹配区整体失效），23 = 反转匹配（匹配区白名单 ↔ 黑名单）。
     */
    private static final int DATA_COLLECT_ALL = 22;
    private static final int DATA_INVERT_MATCH = 23;
    /** 方块实体侧两个开关的下标（见 {@code CollectionCacheBlockEntity#getContainerData} 的 case 17/18）。 */
    private static final int BLOCK_DATA_COLLECT_ALL = 17;
    private static final int BLOCK_DATA_INVERT_MATCH = 18;
    /**
     * 销毁确认界面用的缓存快照（本轮新增）：24 = 物品总件数、25 = 非空堆数、26 = 流体种类数。
     * <p>它们映射到方块实体自己暴露的槽 19 / 20 / 21（理由同红石模式：菜单与方块实体的槽号各自独立）。
     * 客户端只<b>读</b>这些值来把「将销毁多少」写进确认文案，真正的销毁判定与执行全在服务端。</p>
     */
    private static final int DATA_CACHE_ITEM_TOTAL = 24;
    private static final int DATA_CACHE_ITEM_STACKS = 25;
    private static final int DATA_FLUID_KINDS = 26;
    private static final int BLOCK_DATA_CACHE_ITEM_TOTAL = 19;
    private static final int BLOCK_DATA_CACHE_ITEM_STACKS = 20;
    private static final int BLOCK_DATA_FLUID_KINDS = 21;
    /**
     * <b>删除模式总闸</b>（2026-10-05）：27 = 是否开启、28 = 当前删除吞吐 / tick。
     * <p>映射到方块实体的槽 22 / 23（见 {@code CollectionCacheBlockEntity#getContainerData}）。
     * 客户端只读这两个值来渲染复选框与「每秒能删多少」的提示；真正的销毁判定与执行全在服务端。
     * 老存档 / 未同步时读到 0 ⇒ 显示为「关闭」，与方块实体的默认值（关闭）一致。</p>
     */
    private static final int DATA_DELETE_MODE = 27;
    private static final int DATA_DELETE_RATE = 28;
    private static final int BLOCK_DATA_DELETE_MODE = 22;
    private static final int BLOCK_DATA_DELETE_RATE = 23;

    @Nullable
    private final CollectionCacheBlockEntity block;
    /** 服务端：方块实体数据源；客户端为 null。 */
    @Nullable
    private final ContainerData base;
    /** 客户端：服务端同步过来的数据槽数值。 */
    private final int[] clientData = new int[DATA_SLOT_COUNT];
    private final ContainerData data;
    @Nullable
    private net.minecraft.server.level.ServerPlayer serverPlayer;
    /** 匹配区滚动窗口偏移（页，0..max）。 */
    private int matchOffset;
    /** 缓存区滚动窗口偏移（页，0..max）。 */
    private int cacheOffset;
    /** 流体缓存快照的变化检测间隔（tick）。 */
    private static final int FLUID_SYNC_INTERVAL_TICKS = 20;
    /** 距离下次流体缓存变化检测的 tick 数。 */
    private int fluidSyncCooldown;
    /** 上次已同步的流体缓存已用量（-1 = 尚未同步过）。 */
    private long lastSyncedFluidStored = -1L;
    /** 上次已同步的流体缓存种类数（-1 = 尚未同步过）。 */
    private int lastSyncedFluidKinds = -1;

    public CollectionCacheMenu(final int id, final Inventory inventory) {
        this(id, inventory, null);
    }

    public CollectionCacheMenu(final int id,
                               final Inventory inventory,
                               @Nullable final CollectionCacheBlockEntity block) {
        super(RS_Create_Compat.COLLECTION_CACHE_MENU.get(), id);
        this.block = block;
        // 数据槽 0..9 来自方块实体；10..13 为两个区域各自的滚动偏移与最大偏移（服务端权威，同步给客户端）
        this.base = block != null ? block.getContainerData() : null;
        this.data = new ContainerData() {
            @Override
            public int get(final int index) {
                if (base == null) {
                    // 客户端：数值全部来自服务端同步包
                    return index >= 0 && index < DATA_SLOT_COUNT ? clientData[index] : 0;
                }
                return switch (index) {
                    case DATA_MATCH_OFFSET -> getMatchOffset();
                    // 匹配区最大偏移由「容量」决定（固定 MATCH_PAGES 页）：不再由「已用最高下标」推出，
                    // 否则第一页填满前最大偏移恒为 0 → 玩家翻不到第二页也放不下第 49 个标记。
                    case DATA_MATCH_MAX -> maxPageOffset(MATCH_CAPACITY - 1, MATCH_WINDOW);
                    case DATA_CACHE_OFFSET -> getCacheOffset();
                    // 缓存区格数取「当前实际格数」（参与机器集群后 = 台数 × CACHE_SLOTS）：
                    // 页数按总容量算，而不是「已用最高下标」，否则缓存半空时 maxPage = 0，
                    // 滚动条会被判定为「无内容可滚」——玩家看到的就是「滚动条根本拖不动」。
                    case DATA_CACHE_MAX -> maxPageOffset(
                        (block == null ? CollectionCacheBlockEntity.CACHE_SLOTS
                            : block.getCache().getContainerSize()) - 1, CACHE_WINDOW);
                    case DATA_INPUT_FACES -> block == null
                        ? CollectionCacheBlockEntity.ALL_FACES : block.getInputFacesMask();
                    // 红石模式：方块实体把它放在<b>它自己的槽 16</b>（见 CollectionCacheBlockEntity#getContainerData），
                    // 这里必须显式映射过去 —— 否则默认分支会去读 base.get(20)（方块实体根本没这一槽，恒 0），
                    // 界面上的红石按钮就永远显示「忽略」（用户实测问题）。
                    // 注：base == null（客户端）在上面的分支已提前返回，这里 base 必然非空。
                    case DATA_REDSTONE_MODE -> base.get(BLOCK_DATA_REDSTONE_MODE);
                    case DATA_XP_FORM -> block == null ? XpForm.ORB.ordinal() : block.getXpForm().ordinal();
                    // 两个新开关走同一条「菜单槽号 → 方块实体槽号」显式映射（理由同红石模式）
                    case DATA_COLLECT_ALL -> base.get(BLOCK_DATA_COLLECT_ALL);
                    case DATA_INVERT_MATCH -> base.get(BLOCK_DATA_INVERT_MATCH);
                    // 销毁确认用快照：同样显式映射到方块实体的 19 / 20 / 21
                    case DATA_CACHE_ITEM_TOTAL -> base.get(BLOCK_DATA_CACHE_ITEM_TOTAL);
                    case DATA_CACHE_ITEM_STACKS -> base.get(BLOCK_DATA_CACHE_ITEM_STACKS);
                    case DATA_FLUID_KINDS -> base.get(BLOCK_DATA_FLUID_KINDS);
                    // 删除模式总闸 + 当前删除吞吐（映射到方块实体的 22 / 23）
                    case DATA_DELETE_MODE -> base.get(BLOCK_DATA_DELETE_MODE);
                    case DATA_DELETE_RATE -> base.get(BLOCK_DATA_DELETE_RATE);

                    default -> index >= 0 && index < DATA_SLOT_COUNT ? base.get(index) : 0;
                };
            }

            @Override
            public void set(final int index, final int value) {
                // 仅客户端接收同步值；服务端由数据包逻辑直接修改
                if (base == null && index >= 0 && index < DATA_SLOT_COUNT) {
                    clientData[index] = value;
                }
            }

            @Override
            public int getCount() {
                return DATA_SLOT_COUNT;
            }
        };
        addDataSlots(data);

        // 匹配区窗口（ghost 槽，Menu (9,33) 起 12 列 × 4 行）
        for (int i = 0; i < MATCH_WINDOW; i++) {
            addSlot(new WindowSlot(this, i,
                MATCH_X0 + (i % GRID_COLS) * 18, MATCH_Y0 + (i / GRID_COLS) * 18));
        }
        // 缓存区窗口（real 槽，Menu (9,119) 起 12 列 × 4 行）
        for (int i = 0; i < CACHE_WINDOW; i++) {
            addSlot(new WindowSlot(this, MATCH_WINDOW + i,
                CACHE_X0 + (i % GRID_COLS) * 18, CACHE_Y0 + (i / GRID_COLS) * 18));
        }
        // 升级槽（6 格横排，Menu (76,219) 起）：速度提高入网速率、堆叠提高缓存每格上限
        final Container upgradeContainer = block != null ? block.getUpgradeContainer()
            : new SimpleContainer(UPGRADE_COUNT);
        for (int i = 0; i < UPGRADE_COUNT; i++) {
            addSlot(UpgradeSlot.forContainer(upgradeContainer, i,
                UPGRADE_X0 + i * 18, UPGRADE_Y0, ALLOWED_UPGRADES));
        }
        // 玩家主物品栏（3 行 9 列，Menu (43,242) 起）
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < INV_COLS; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9, INV_X0 + col * 18, INV_Y0 + row * 18));
            }
        }
        // 快捷栏（Menu (43,302) 起）
        for (int col = 0; col < INV_COLS; col++) {
            addSlot(new Slot(inventory, col, INV_X0 + col * 18, HOTBAR_Y));
        }
    }

    public static CollectionCacheMenu create(final int id,
                                             final Inventory inventory,
                                             final CollectionCacheBlockEntity block) {
        return new CollectionCacheMenu(id, inventory, block);
    }

    // ==================== 滚动窗口 ====================

    /** 最后一个已用下标所在的页号（未超出第一页时为 0）。 */
    private static int maxPageOffset(final int highestUsedIndex, final int window) {
        return Math.max(0, highestUsedIndex / window);
    }

    public int getMatchOffset() {
        return Math.max(0, Math.min(matchOffset, getMaxMatchOffset()));
    }

    /** 设置匹配区偏移（越界自动收敛）。服务端权威值经数据槽回传客户端。 */
    public void setMatchOffset(final int offset) {
        this.matchOffset = Math.max(0, Math.min(offset, getMaxMatchOffset()));
    }

    /** 匹配区最大偏移（页）。 */
    public int getMaxMatchOffset() {
        return Math.max(0, data.get(DATA_MATCH_MAX));
    }

    /** 服务端权威匹配区偏移（客户端据此收敛本地偏移）。 */
    public int getSyncedMatchOffset() {
        return Math.max(0, data.get(DATA_MATCH_OFFSET));
    }

    public int getCacheOffset() {
        return Math.max(0, Math.min(cacheOffset, getMaxCacheOffset()));
    }

    /** 设置缓存区偏移（越界自动收敛）。 */
    public void setCacheOffset(final int offset) {
        this.cacheOffset = Math.max(0, Math.min(offset, getMaxCacheOffset()));
    }

    /** 缓存区最大偏移（页）。 */
    public int getMaxCacheOffset() {
        return Math.max(0, data.get(DATA_CACHE_MAX));
    }

    /** 服务端权威缓存区偏移。 */
    public int getSyncedCacheOffset() {
        return Math.max(0, data.get(DATA_CACHE_OFFSET));
    }

    /** 窗口内某槽位是否位于匹配区（否则为缓存区）。 */
    public boolean isMarkerWindow(final int indexInWindow) {
        return indexInWindow >= 0 && indexInWindow < MATCH_WINDOW;
    }

    /** 窗口内某槽位映射到的全局下标（匹配区 / 缓存区各自的全局格，含各自偏移）。 */
    public int globalIndex(final int indexInWindow) {
        if (isMarkerWindow(indexInWindow)) {
            return getMatchOffset() * MATCH_WINDOW + indexInWindow;
        }
        return getCacheOffset() * CACHE_WINDOW + (indexInWindow - MATCH_WINDOW);
    }

    /** 客户端：该物品是否已在匹配区被标记（用于「同一物品只能标记一次」的前置拦截）。 */
    public boolean isMarkerItemPresent(final ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        for (int i = 0; i < MATCH_WINDOW; i++) {
            final ItemStack marker = slots.get(i).getItem();
            if (!marker.isEmpty() && marker.is(stack.getItem())) {
                return true;
            }
        }
        return false;
    }

    // ==================== 多个输入面（可配置） ====================

    /** 六个输入面开关的快照（客户端读服务端权威值）。 */
    public int getInputFacesMask() {
        return data.get(DATA_INPUT_FACES);
    }

    /** 该方向是否允许物流输入（客户端判定 / 子界面展示共用）。 */
    public boolean isInputFace(final net.minecraft.core.Direction direction) {
        return direction != null && (getInputFacesMask() & (1 << direction.ordinal())) != 0;
    }

    /** 服务端：设置某方向的输入开关（来自 {@code SetCollectionInputFacePacket}）。 */
    public void setInputFace(@Nullable final net.minecraft.core.Direction direction, final boolean enabled) {
        if (block == null || block.getLevel() == null || block.getLevel().isClientSide()) {
            return;
        }
        block.setInputFace(direction, enabled);
    }

    // ==================== 交互 ====================

    /** 红石模式：与 RS 原版机器一致地循环 忽略 → 高电平 → 低电平（服务端权威）。 */
    @Override
    public boolean clickMenuButton(final Player player, final int id) {
        if (id == cretae.cookiewyq.rs_create_compat.support.RsccRedstoneMode.BUTTON_ID) {
            if (block != null && !block.getLevel().isClientSide()) {
                block.setRedstoneMode(block.getRedstoneMode().toggle());
            }
            return true;
        }
        return super.clickMenuButton(player, id);
    }

    /** 当前红石模式（客户端读服务端同步值）。 */
    @Override
    public com.refinedmods.refinedstorage.common.support.RedstoneMode rscc$getRedstoneMode() {
        return com.refinedmods.refinedstorage.common.support.RedstoneModeSettings.getRedstoneMode(
            data.get(DATA_REDSTONE_MODE));
    }

    /** 匹配区标记：手持物品点击复制为 ghost 标记（不消耗）；空手持点击清除标记；禁止放入真实物品。 */
    @Override
    public void clicked(final int slotId, final int button, final ClickType clickType, final Player player) {
        if (slotId >= 0 && slotId < MATCH_WINDOW
            && button == 0 && (clickType == ClickType.PICKUP || clickType == ClickType.QUICK_MOVE)) {
            if (block != null && !block.getLevel().isClientSide()) {
                final int markerIndex = globalIndex(slotId);
                final ItemStack carried = getCarried();
                if (carried.isEmpty()) {
                    block.removeMarker(markerIndex);
                } else {
                    block.tryAddMarker(markerIndex, carried);
                }
                syncMarkers();
            }
            return;
        }
        super.clicked(slotId, button, clickType, player);
    }

    /**
     * 服务端：写入匹配区条目（资源 / 数量 / 匹配 NBT / 匹配标签集合），并回传同步。
     * <p>{@code id == null} 表示清除该下标标记；{@code fluid=true} 表示流体/气体条目。
     * {@code tags} 非空表示按标签匹配一整类（命中物须同时带上集合里的每个标签）；标签与 NBT 规则可同时生效。</p>
     */
    public void setMarkerConfig(final int markerIndex,
                                final boolean fluid,
                                @Nullable final ResourceLocation id,
                                @Nullable final CompoundTag nbt,
                                final long amount,
                                final boolean matchNbt,
                                final List<ResourceLocation> tags) {
        if (block == null || block.getLevel().isClientSide()) {
            return;
        }
        // 网络边界防护：只接受落在匹配区容量内的下标（页数上限就是由该容量决定的），
        // 既避免恶意包用一个超大下标撑爆标记表，也保证「翻到后面的页点空格」能真正写进去。
        if (markerIndex < 0 || markerIndex >= MATCH_CAPACITY) {
            return;
        }
        block.setMarkerConfig(markerIndex, fluid, id, nbt, amount, matchNbt, tags);
        syncMarkers();
    }

    /** 服务端：设置 / 清除某格的「匹配标签集合」（空集合 = 取消标签匹配，回到只看这一个具体物品）。 */
    public void setMarkerTag(final int markerIndex, final List<ResourceLocation> tags) {
        if (block == null || block.getLevel().isClientSide()) {
            return;
        }
        if (markerIndex < 0 || markerIndex >= block.getMarkerCount()) {
            return;
        }
        if (block.setMarkerTag(markerIndex, tags)) {
            syncMarkers();
        }
    }

    /** 服务端：只更新某条已存在标记的数量与匹配规则（保留其匹配标签集合）。 */
    public void setMarkerConfig(final int markerIndex, final long amount,
                                final boolean matchNbt) {
        if (block == null || block.getLevel().isClientSide()) {
            return;
        }
        if (markerIndex < 0 || markerIndex >= block.getMarkerCount()) {
            return;
        }
        block.setMarkerConfig(markerIndex, amount, matchNbt, block.getMarkerTags(markerIndex));
        syncMarkers();
    }

    // ==================== 经验形态（自动 / 颗粒 / 液态） ====================

    /** 当前经验形态（服务端读方块实体，客户端读服务端经数据槽同步的值）。 */
    public XpForm getXpForm() {
        return XpForm.byOrdinal(data.get(DATA_XP_FORM));
    }

    /** 服务端：设置经验形态（由 {@code SetCollectionXpFormPacket} 调用；落方块实体 NBT）。 */
    public void setXpForm(final XpForm form) {
        if (block == null || block.getLevel() == null || block.getLevel().isClientSide()) {
            return;
        }
        block.setXpForm(form);
    }

    // ==================== 吸取开关 / 流体缓存 ====================

    /** 该来源的吸取开关是否开启（服务端读方块实体，客户端读同步值）。 */
    public boolean isAbsorbEnabled(final AbsorbType type) {
        return type != null && data.get(DATA_ABSORB_ITEM + type.ordinal()) != 0;
    }

    /** 服务端：设置吸取开关（由 {@code SetCollectionAbsorbTogglePacket} 调用）。 */
    public void setAbsorbEnabled(final AbsorbType type, final boolean enabled) {
        if (block == null || block.getLevel().isClientSide()) {
            return;
        }
        block.setAbsorbEnabled(type, enabled);
    }

    /**
     * 「吸取所有物品」是否开启（服务端读方块实体，客户端读服务端经数据槽同步的值）。
     * <p>开启 = 匹配区整体失效（不过滤，全收）；关闭 = 恢复按匹配区过滤。</p>
     */
    public boolean isCollectAll() {
        return data.get(DATA_COLLECT_ALL) != 0;
    }

    /** 服务端：设置「吸取所有物品」（由 {@code SetCollectionCollectAllPacket} 调用；落方块实体 NBT）。 */
    public void setCollectAll(final boolean enabled) {
        if (block == null || block.getLevel() == null || block.getLevel().isClientSide()) {
            return;
        }
        block.setCollectAll(enabled);
    }

    /**
     * 「反转匹配」是否开启（服务端读方块实体，客户端读同步值）。
     * <p>开启 = 匹配区从白名单变黑名单（只收没被标记的资源）。</p>
     */
    public boolean isInvertMatch() {
        return data.get(DATA_INVERT_MATCH) != 0;
    }

    /** 服务端：设置「反转匹配」（由 {@code SetCollectionInvertMatchPacket} 调用；落方块实体 NBT）。 */
    public void setInvertMatch(final boolean enabled) {
        if (block == null || block.getLevel() == null || block.getLevel().isClientSide()) {
            return;
        }
        block.setInvertMatch(enabled);
    }

    /**
     * <b>删除模式总闸</b>是否开启（服务端读方块实体，客户端读同步值）—— 界面那个复选框的状态。
     * <p>关闭（默认）时，匹配槽上的「标记为删除」只是记录意图，绝不销毁资源。</p>
     */
    public boolean isDeleteMode() {
        return data.get(DATA_DELETE_MODE) != 0;
    }

    /**
     * 服务端：设置删除模式（由 {@code SetCollectionDeleteModePacket} 调用；落方块实体 NBT）。
     * <p><b>开启</b>必须经服务端确认位校验（见该包），这里只负责落地。</p>
     */
    public void setDeleteMode(final boolean enabled) {
        if (block == null || block.getLevel() == null || block.getLevel().isClientSide()) {
            return;
        }
        block.setDeleteMode(enabled);
    }

    /** 当前删除吞吐 / tick（= 处理组数 × 单次吞吐，随速度 / 堆叠升级变化）；供界面写清「多久能删完」。 */
    public int getDeleteRatePerTick() {
        return Math.max(0, data.get(DATA_DELETE_RATE));
    }

    /**
     * 「反转匹配」是否<b>被自动模式禁用</b>：{@code 吸取所有物品} 开启时匹配区整体失效，
     * 反转匹配即使开着也不参与判定 —— 界面据此显示第三态（禁用）。
     * <p>判定只在客户端用于显示，真正的优先级判定在方块实体的收集路径里（服务端权威）。</p>
     */
    public boolean isInvertMatchDisabled() {
        return isCollectAll();
    }

    // ==================== 销毁（本轮新增：缓存区清空 + 匹配槽「直接销毁」） ====================

    /** 缓存区物品总件数（客户端读服务端同步值，用于销毁确认文案）。 */
    public int getCacheItemTotal() {
        return Math.max(0, data.get(DATA_CACHE_ITEM_TOTAL));
    }

    /** 缓存区非空堆数（≈ 资源种类数；销毁确认文案用）。 */
    public int getCacheItemStacks() {
        return Math.max(0, data.get(DATA_CACHE_ITEM_STACKS));
    }

    /** 流体缓存种类数（销毁确认文案用）。 */
    public int getFluidKinds() {
        return Math.max(0, data.get(DATA_FLUID_KINDS));
    }

    /**
     * 服务端：销毁缓存区全部内容（物品 + 流体）。
     * <p><b>调用者必须已经过玩家二次确认</b>：本方法只应由带确认位的
     * {@code SetCollectionDestroyPacket} 触发；换言之「未确认 ⇒ 服务端零销毁」。
     * 销毁是有意行为，由方块实体逐资源写入账本（{@code RsccFlowLedger#destroyed}），可审计。</p>
     */
    public void destroyCacheContent() {
        if (block == null || block.getLevel() == null || block.getLevel().isClientSide()) {
            return;
        }
        block.destroyAllCacheContent();
        syncMarkers(); // 立即回传新快照，界面数量即时刷新
    }

    /** 服务端：设置某匹配槽的「直接销毁」模式（由 {@code SetCollectionMarkerDestroyPacket} 调用；服务端权威）。 */
    public void setMarkerDestroy(final int markerIndex, final boolean destroy) {
        if (block == null || block.getLevel() == null || block.getLevel().isClientSide()) {
            return;
        }
        if (markerIndex < 0 || markerIndex >= MATCH_CAPACITY) {
            return; // 网络边界防护：只接受落在匹配区容量内的下标
        }
        block.setMarkerDestroy(markerIndex, destroy);
        syncMarkers();
    }

    /** 流体缓存已用量（mB，截断到 int）。 */
    public int getFluidStored() {
        return Math.max(0, data.get(DATA_FLUID_STORED));
    }

    /** 流体缓存总容量（mB）。 */
    public long getFluidCapacity() {
        return CollectionCacheBlockEntity.FLUID_CACHE_CAPACITY;
    }

    /** 一格流体格对应的一桶容量（mB）。 */
    public static final long BUCKET_MB = 1000L;

    /**
     * 服务端：玩家点击缓存区的流体格 → <b>用玩家自己的空容器换出一份装满的容器</b>
     * （水桶 / 岩浆桶 / 瓶子 / 任意模组容器皆可，走容器的 {@code IFluidHandlerItem}）。
     * <p><b>为什么必须「换」容器（旧实现的复制 bug）</b>：旧实现直接
     * {@code FluidUtil.getFilledBucket(...)} 造一个装满的桶塞给玩家，再把缓存扣一桶 ——
     * 全程既没有要求、也没有消耗任何空容器，于是「身上一个空桶都没有，点一下也能拿到岩浆桶」
     * = <b>凭空复制容器</b>。现在改为把缓存里的流体灌进玩家已有的容器：
     * 1 个空容器 → 1 个装满的容器，数量守恒。</p>
     * <p><b>原子性</b>：先<b>模拟</b>（容器用副本、流体源只应答 SIMULATE），确认
     * 「缓存 ≥ 1 桶 + 这个容器装得下这种流体」后才真正执行，执行序列 =
     * 从缓存抽取（量恰好等于容器吸收量）→ 消耗 1 个空容器 → 交回装满的容器；
     * 中间没有任何可能失败的分支，因此不存在「扣了没给」。
     * 执行阶段若被容器实现拒绝（返回失败），抽取也不会发生（NeoForge 在同一调用内先模拟后执行）。</p>
     * <p><b>静默</b>：没有可用容器、缓存不足 1 桶、流体无效、该流体没有容器形态、容器装不下 ——
     * 一律什么都不做，也<b>不提示</b>（与 RS 一致：RS 的网格取流体同样是「做得到就做、做不到就无声」）。</p>
     */
    public void extractFluidToPlayer(final net.minecraft.server.level.ServerPlayer player,
                                     final ResourceLocation id, final CompoundTag nbt) {
        if (block == null || block.getLevel() == null || block.getLevel().isClientSide() || player == null) {
            return;
        }
        if (id == null || block.getFluidCache().getAmount(id, nbt) < BUCKET_MB) {
            return; // 不足 1 桶：不可取出（静默）
        }
        final net.minecraft.world.level.material.Fluid fluid =
            net.minecraft.core.registries.BuiltInRegistries.FLUID.get(id);
        if (fluid == null || fluid == net.minecraft.world.level.material.Fluids.EMPTY) {
            return;
        }
        // 流体源只认「被点击的这一个条目」：整表视图会拿第一条条目应答 drain，可能灌出别的流体
        final net.neoforged.neoforge.fluids.capability.IFluidHandler source =
            new cretae.cookiewyq.rs_create_compat.support.FluidCacheEntryHandler(
                block.getFluidCache(), id, nbt, block.getLevel().registryAccess(), (int) BUCKET_MB);
        // 1) 光标上的容器优先（RS 行为：光标拿着流体容器时只动光标，不去翻背包 —— 避免误耗背包里的桶）
        final ItemStack carried = getCarried();
        if (!carried.isEmpty() && heldFluidContainer(carried)) {
            if (fillCarriedContainer(player, carried, source)) {
                block.setChanged();
                syncMarkers(); // 立即回传新快照，界面数量即时刷新
            }
            return;
        }
        // 2) 背包（含快捷栏）逐个尝试：第一个「装得下」的容器被换成装满的容器
        final net.minecraft.world.entity.player.Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (fillInventoryContainer(player, slot, source)) {
                block.setChanged();
                syncMarkers();
                return;
            }
        }
        // 3) 找不到可用容器 / 该流体没有容器形态：静默返回，不提示（与 RS 一致）
    }

    /** 该物品是否是「流体容器」（即带有 {@code IFluidHandlerItem} 能力；空桶、满桶、瓶子、模组桶都算）。 */
    private static boolean heldFluidContainer(final ItemStack stack) {
        return stack.getCapability(net.neoforged.neoforge.capabilities.Capabilities.FluidHandler.ITEM) != null;
    }

    /**
     * 光标上的容器：装得下缓存里的流体就「1 个空容器 → 1 个装满的容器」。
     * <p>整栈只有 1 个时原地换掉；多于 1 个时只消耗 1 个，结果容器并入背包（放不下就掉脚下），
     * 其余空容器原样留在光标上 —— 数量恒为「−1 空 +1 满」，既不凭空产出也不吞物品。</p>
     *
     * @return 是否真的取出了流体（false = 什么都没发生）
     */
    private boolean fillCarriedContainer(final net.minecraft.server.level.ServerPlayer player,
                                         final ItemStack carried,
                                         final net.neoforged.neoforge.fluids.capability.IFluidHandler source) {
        final ItemStack filled = fillFromCache(carried, source);
        if (filled == null) {
            return false;
        }
        if (carried.getCount() <= 1) {
            setCarried(filled);
        } else {
            final ItemStack rest = carried.copy();
            rest.shrink(1);
            setCarried(rest);
            giveToPlayer(player, filled);
        }
        return true;
    }

    /**
     * 背包第 {@code slot} 格的容器：装得下缓存里的流体就「1 个空容器 → 1 个装满的容器」。
     * <p>整栈只有 1 个时原地替换；多于 1 个时只消耗 1 个、其余原样留在背包，结果容器并入背包
     * （放不下就掉脚下）—— 同样守恒。</p>
     *
     * @return 是否真的取出了流体（false = 什么都没发生）
     */
    private boolean fillInventoryContainer(final net.minecraft.server.level.ServerPlayer player,
                                           final int slot,
                                           final net.neoforged.neoforge.fluids.capability.IFluidHandler source) {
        final net.minecraft.world.entity.player.Inventory inventory = player.getInventory();
        final ItemStack stack = inventory.getItem(slot);
        if (stack.isEmpty()) {
            return false;
        }
        final ItemStack filled = fillFromCache(stack, source);
        if (filled == null) {
            return false;
        }
        if (stack.getCount() <= 1) {
            inventory.setItem(slot, filled);
        } else {
            stack.shrink(1);
            giveToPlayer(player, filled);
        }
        return true;
    }

    /**
     * 把缓存里的流体灌进这一件容器：<b>先模拟、后执行</b>。
     * <p>模拟只作用于容器副本与本模组流体源的 SIMULATE（见
     * {@link cretae.cookiewyq.rs_create_compat.support.FluidCacheEntryHandler}），
     * 因此「不是容器 / 装不下 / 流体不对」时不会留下任何痕迹；
     * 执行时 NeoForge 会在同一次调用内重新模拟、抽取缓存、灌装容器，抽取量恰好等于容器吸收量。</p>
     *
     * @return 装满的容器；任何一步不成立（含「容器原样不变」这种只会白扣流体的实现）都返回 null
     */
    @Nullable
    private static ItemStack fillFromCache(
        final ItemStack container,
        final net.neoforged.neoforge.fluids.capability.IFluidHandler source) {
        final net.neoforged.neoforge.fluids.FluidActionResult simulated =
            net.neoforged.neoforge.fluids.FluidUtil.tryFillContainer(
                container, source, (int) BUCKET_MB, null, false);
        if (!simulated.isSuccess() || simulated.getResult().isEmpty()
            || ItemStack.isSameItemSameComponents(container, simulated.getResult())) {
            return null; // 灌完还是原来那件东西 = 只会白扣缓存里的流体，坚决不做
        }
        final net.neoforged.neoforge.fluids.FluidActionResult executed =
            net.neoforged.neoforge.fluids.FluidUtil.tryFillContainer(
                container, source, (int) BUCKET_MB, null, true);
        return executed.isSuccess() && !executed.getResult().isEmpty() ? executed.getResult() : null;
    }

    /** 把结果容器交给玩家：先并入背包，放不下就掉在脚下（绝不销毁物品）。 */
    private static void giveToPlayer(final net.minecraft.server.level.ServerPlayer player,
                                     final ItemStack stack) {
        final ItemStack rest = stack.copy();
        player.getInventory().add(rest);
        if (!rest.isEmpty()) {
            player.drop(rest, false);
        }
    }

    /** 匹配区某下标的条目（客户端为同步包内容，服务端为方块实体真值）。 */
    public MarkerEntry getMarkerEntry(final int markerIndex) {
        return block != null ? block.getMarkerEntry(markerIndex) : MarkerEntry.EMPTY;
    }

    /** 当前匹配区可见窗口第一条的全局下标（= 页偏移 × 每页 48 格）。 */
    public int getMatchWindowStart() {
        return getMatchOffset() * MATCH_WINDOW;
    }

    /**
     * 服务端：把匹配区<b>当前可见窗口</b>（48 条）+ 流体缓存快照推送给打开本界面的玩家。
     * <p>匹配区无上限后不再整表同步（条目多了会成包风暴）；翻页时由
     * {@code SetCollectionScrollPacket} 触发重发新窗口。</p>
     */
    public void syncMarkers() {
        if (block != null && serverPlayer != null) {
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(serverPlayer,
                SyncCollectionMarkersPacket.of(getMatchWindowStart(),
                    block.getMarkerWindowEntries(getMatchWindowStart(), MATCH_WINDOW),
                    block.getMarkerDestroyWindow(getMatchWindowStart(), MATCH_WINDOW),
                    block.getFluidCacheEntries(),
                    block.getBlockedItems(), block.getBlockedFluids(),
                    block.getBlockedItemTags(), block.getBlockedFluidTags()));
            lastSyncedFluidStored = block.getFluidCache().getStored();
            lastSyncedFluidKinds = block.getFluidCache().getKinds();
        }
    }

    /**
     * 服务端：切换某资源（物品 / 流体）的「阻塞」开关（由 {@code SetCollectionBlockedPacket} 调用）。
     * <p>阻塞后该资源不再写回 RS 网络，但仍可被本机收集 / 物流输入 / 玩家取出（绝不销毁）。</p>
     * <p><b>本轮修复（用户第 ③ 条）</b>：{@code tags} 非空时，除具体资源 id 外还把<b>标签集合</b>
     * 一并写进「按标签阻塞」名单 —— 于是「按标签注册的匹配条目」点阻塞后，
     * 属于该标签的<b>所有</b>资源（含金板）都被挡住，不再瞬间回流进网络。</p>
     */
    public void setBlocked(final boolean fluid, final ResourceLocation id, final boolean blocked,
                           @Nullable final List<ResourceLocation> tags) {
        if (block == null || block.getLevel() == null || block.getLevel().isClientSide() || id == null) {
            return;
        }
        block.setBlocked(fluid, id, blocked);
        block.setBlockedTags(fluid, tags, blocked);
        syncMarkers();
    }

    /** 兼容重载：只切换具体资源 id 的阻塞（不涉及标签）。 */
    public void setBlocked(final boolean fluid, final ResourceLocation id, final boolean blocked) {
        setBlocked(fluid, id, blocked, null);
    }

    /**
     * 服务端每 tick 的数据槽同步（由原版机制调用）+ 流体缓存快照的变化检测：
     * 每 20 tick 检查一次，只有流体缓存的「已用量 / 种类数」变了才补发一次同步包，
     * 保证缓存面板右侧的流体内容不会长期停留在旧值，同时不产生每 tick 的包风暴。
     */
    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        if (block == null || block.getLevel() == null || block.getLevel().isClientSide()) {
            return;
        }
        // 打开后解析玩家并补发一次匹配区快照：synchronizer 不是 ServerPlayer，setSynchronizer 路径不可靠
        if (serverPlayer == null) {
            serverPlayer = findPlayer();
            if (serverPlayer != null) {
                syncMarkers();
            }
        }
        if (serverPlayer == null) {
            return;
        }
        if (--fluidSyncCooldown > 0) {
            return;
        }
        fluidSyncCooldown = FLUID_SYNC_INTERVAL_TICKS;
        final long stored = block.getFluidCache().getStored();
        if (stored != lastSyncedFluidStored || block.getFluidCache().getKinds() != lastSyncedFluidKinds) {
            syncMarkers();
        }
    }

    /** 按「containerMenu == this」反查当前打开本菜单的服务端玩家（无则 null）。 */
    @Nullable
    private net.minecraft.server.level.ServerPlayer findPlayer() {
        if (block == null || block.getLevel() == null) {
            return null;
        }
        for (final Player p : block.getLevel().players()) {
            if (p.containerMenu == this && p instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                return serverPlayer;
            }
        }
        return null;
    }

    @Override
    public void setSynchronizer(final ContainerSynchronizer synchronizer) {
        super.setSynchronizer(synchronizer);
        if (synchronizer instanceof net.minecraft.server.level.ServerPlayer player) {
            this.serverPlayer = player;
            syncMarkers();
        }
    }

    @Override
    public ItemStack quickMoveStack(final Player player, final int index) {
        if (index < 0 || index >= slots.size()) {
            return ItemStack.EMPTY;
        }
        final Slot slot = slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        final ItemStack stackInSlot = slot.getItem();
        final ItemStack copy = stackInSlot.copy();
        if (index >= PLAYER_START) {
            // 玩家背包 → 升级槽（若为允许的升级）否则直接塞入缓存（缓存区不必全部可见）
            if (block == null || block.getLevel().isClientSide()) {
                return ItemStack.EMPTY;
            }
            boolean moved = false;
            if (isAllowedUpgrade(stackInSlot)) {
                moved = moveItemStackTo(stackInSlot, UPGRADE_START, UPGRADE_START + UPGRADE_COUNT, false);
            }
            if (!moved) {
                final ItemStack remainder = block.insertIntoCache(stackInSlot);
                if (remainder.getCount() == stackInSlot.getCount()) {
                    return ItemStack.EMPTY;
                }
                slot.set(remainder);
            }
        } else {
            // 方块槽（窗口 / 升级） → 玩家背包
            if (!moveItemStackTo(stackInSlot, PLAYER_START, PLAYER_START + 36, true)) {
                return ItemStack.EMPTY;
            }
        }
        if (slot.getItem().isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return copy;
    }

    private static boolean isAllowedUpgrade(final ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        final ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id != null && "refinedstorage".equals(id.getNamespace())
            && ALLOWED_UPGRADES.contains(id.getPath());
    }

    @Override
    public boolean stillValid(final Player player) {
        if (block == null) {
            return true; // 客户端重建菜单：保持打开（有效性由服务端校验）
        }
        if (block.getLevel() == null) {
            return true;
        }
        return block.getLevel().getBlockEntity(block.getBlockPos()) == block
            && player.distanceToSqr(block.getBlockPos().getX() + 0.5,
            block.getBlockPos().getY() + 0.5,
            block.getBlockPos().getZ() + 0.5) <= 64.0;
    }

    // ==================== 数据槽 ====================

    public int getSpeedUpgradeCount() {
        return data.get(0);
    }

    public int getStackUpgradeCount() {
        return data.get(1);
    }

    public int getCacheSlotCapacity() {
        return Math.max(1, data.get(4));
    }

    // ==================== 收集范围（三轴） ====================

    /** 某一轴当前收集范围（格，axis：0=X / 1=Y / 2=Z）：客户端读服务端同步值，服务端读方块实体真值。 */
    public int getCollectRadius(final int axis) {
        final int raw;
        if (block != null) {
            raw = block.getCollectRadius(axis);
        } else {
            raw = data.get(switch (axis) {
                case 1 -> DATA_RADIUS_Y;
                case 2 -> DATA_RADIUS_Z;
                default -> DATA_RADIUS_X;
            });
        }
        return Math.max(CollectionCacheBlockEntity.MIN_COLLECT_RADIUS,
            Math.min(getMaxCollectRadius(), raw));
    }

    /** 收集范围（三轴中的最大值）：用于 tooltip / 耗电展示。 */
    public int getCollectRadius() {
        return Math.max(getCollectRadius(0), Math.max(getCollectRadius(1), getCollectRadius(2)));
    }

    /** 收集范围上限（格）：基础上限 + 每个范围升级 25；无限范围升级时为极大值。 */
    public int getMaxCollectRadius() {
        if (block != null) {
            return Math.max(CollectionCacheBlockEntity.MIN_COLLECT_RADIUS, block.getMaxCollectRadius());
        }
        if (hasInfiniteRange()) {
            return Integer.MAX_VALUE - 1;
        }
        final long bonus = (long) getRangeUpgradeCount() * Config.rangeChargerRangePerUpgrade;
        return (int) Math.min(Integer.MAX_VALUE - 1,
            CollectionCacheBlockEntity.MAX_COLLECT_RADIUS + bonus);
    }

    /** 是否已放入创造范围升级（无限范围：不受上限约束）。 */
    public boolean hasInfiniteRange() {
        return block != null ? block.hasInfiniteRange() : data.get(DATA_RADIUS_INFINITE) == 1;
    }

    /** 范围升级数量（每级上限 +25）。 */
    public int getRangeUpgradeCount() {
        return block != null ? block.getRangeUpgradeCount() : data.get(DATA_RADIUS_UPGRADES);
    }

    /** 服务端：设置某一轴的收集范围（由 {@code SetCollectionRadiusPacket} 调用，范围越大耗电越高、吸取越快）。 */
    public void setCollectRadius(final int axis, final int radius) {
        if (block == null || block.getLevel() == null || block.getLevel().isClientSide()) {
            return;
        }
        block.setCollectRadius(axis, radius);
    }

    /**
     * 滚动窗口槽：按菜单当前偏移映射到匹配区（ghost 标记）或缓存区（真实存储）。
     * <p>服务端（block != null）直接读写方块实体；客户端（block == null）为本地镜像槽，
     * 内容由服务端槽位同步包写入，避免偏移变更期间索引错位。</p>
     */
    private static final class WindowSlot extends Slot {
        private static final Container EMPTY = new SimpleContainer(0);
        private final CollectionCacheMenu menu;
        private final int indexInWindow;
        private ItemStack localStack = ItemStack.EMPTY;

        private WindowSlot(final CollectionCacheMenu menu, final int indexInWindow, final int x, final int y) {
            super(EMPTY, indexInWindow, x, y);
            this.menu = menu;
            this.indexInWindow = indexInWindow;
        }

        private boolean markerRegion() {
            return menu.isMarkerWindow(indexInWindow);
        }

        private int global() {
            return menu.globalIndex(indexInWindow);
        }

        @Override
        public ItemStack getItem() {
            final CollectionCacheBlockEntity block = menu.block;
            if (block == null) {
                return localStack;
            }
            return markerRegion() ? block.getMarkerStack(global()) : block.getCache().getItem(global());
        }

        @Override
        public void set(final ItemStack stack) {
            final CollectionCacheBlockEntity block = menu.block;
            if (block == null) {
                localStack = stack;
            } else if (!markerRegion()) {
                block.getCache().setItem(global(), stack);
            }
            // 匹配区写入由菜单 clicked() 统一处理（ghost 标记 + 去重）
            this.setChanged();
        }

        @Override
        public boolean mayPlace(final ItemStack stack) {
            return !markerRegion() && !stack.isEmpty();
        }

        @Override
        public boolean mayPickup(final Player player) {
            return !markerRegion();
        }

        @Override
        public ItemStack remove(final int amount) {
            final CollectionCacheBlockEntity block = menu.block;
            if (block == null || markerRegion()) {
                return ItemStack.EMPTY;
            }
            return block.getCache().removeItem(global(), amount);
        }

        @Override
        public int getMaxStackSize() {
            final CollectionCacheBlockEntity block = menu.block;
            return markerRegion() ? 1 : (block != null ? block.getCacheSlotCapacity() : 64);
        }

        @Override
        public int getMaxStackSize(final ItemStack stack) {
            return Math.min(getMaxStackSize(), stack.getMaxStackSize());
        }
    }
}
