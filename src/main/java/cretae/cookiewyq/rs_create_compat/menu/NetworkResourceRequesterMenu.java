package cretae.cookiewyq.rs_create_compat.menu;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.NetworkResourceRequesterBlockEntity;
import cretae.cookiewyq.rs_create_compat.support.RequesterSenderBackend;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * 网络资源请求器菜单 —— 第 131 轮起<b>只是一层兼容外壳</b>：设置界面（命名目标 / 插件槽 /
 * 投递进度）已被用户要求整页删除，右键请求器<b>直接进 RS 网格</b>（{@code ResourcePickerMenu}），
 * 因此本菜单<b>永远不会被打开</b>。
 *
 * <h2>为什么还留着这个类</h2>
 * <p>① 它的 {@code MenuType} 注册在 {@code RS_Create_Compat}（Lead 独占文件），删类会让那份注册
 * 编译失败 —— 而「只改自己写作用域里的文件」是本轮纪律；② {@code block/NetworkResourceRequesterBlock}
 * 仍通过本类的静态入口问「为什么没打开 / 请打开网格」，那几条是右键交互的唯一出处，
 * 不能跟着界面一起消失。</p>
 *
 * <h2>本轮真正删掉的东西（自检脚本会钉住）</h2>
 * <ul>
 *     <li><b>6 个升级槽</b>：请求器不再持有任何升级容器
 *     （用户原话：「发送器跟插件槽没关系，红石激活的速度决定发送速度」）；
 *     同步数据槽位（每 tick 批数 / 单批量）一并删除 —— 节拍现在是常量，
 *     由 {@code SenderDelivery.FIXED_BATCHES_PER_TICK / FIXED_BATCH_SIZE} 给出；</li>
 *     <li><b>「设置」入口</b>：从网格切回设置面板的那两个静态入口全部删除，
 *     网格与设置之间的来回切换在用户看来本来就是多此一举；</li>
 *     <li><b>所有订单 / 目标编辑逻辑</b>：迁到 {@link RequesterSenderBackend}（服务端唯一校验链），
 *     这里只剩「转发给网格菜单」的几个静态壳。</li>
 * </ul>
 *
 * <p>菜单正文只保留玩家背包槽：万一将来有人重新打开它（调试 / 兼容旧存档），
 * 它至少是一个合法、可点击的容器菜单，而不是一个没有槽位的空壳。</p>
 */
public class NetworkResourceRequesterMenu extends AbstractContainerMenu {
    /**
     * 菜单标题语言键（方块名，中英都已存在）。
     * <p>菜单里<b>只有</b>这一处 {@code Component.translatable}：服务端从不拼自然语言，
     * 其余提示一律是 {@code MSG_*} 语言键常量，随 S2C 快照下发由客户端翻译。</p>
     */
    public static final String MENU_TITLE_KEY = "block.rs_create_compat.network_resource_requester";

    /** 玩家背包 / 快捷栏起始下标（没有升级槽了，玩家槽从 0 开始）。 */
    public static final int PLAYER_START = 0;

    /** 玩家背包 3 行 9 列的左上角（原版标准位置）。 */
    private static final int INV_X0 = 8;
    private static final int INV_Y0 = 84;
    private static final int HOTBAR_Y = 142;

    @Nullable
    private final NetworkResourceRequesterBlockEntity requester;

    /** 客户端重建菜单用（无方块实体）。 */
    public NetworkResourceRequesterMenu(final int id, final Inventory inventory) {
        this(id, inventory, null);
    }

    public NetworkResourceRequesterMenu(final int id, final Inventory inventory,
                                        @Nullable final NetworkResourceRequesterBlockEntity requester) {
        super(RS_Create_Compat.NETWORK_RESOURCE_REQUESTER_MENU.get(), id);
        this.requester = requester;
        // 玩家主物品栏（3 行 9 列）+ 快捷栏
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9, INV_X0 + col * 18, INV_Y0 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, INV_X0 + col * 18, HOTBAR_Y));
        }
    }

    public static NetworkResourceRequesterMenu create(final int id, final Inventory inventory,
                                                      final NetworkResourceRequesterBlockEntity requester) {
        return new NetworkResourceRequesterMenu(id, inventory, requester);
    }

    /** 纯 getter：暴露本菜单持有的方块实体（客户端重建菜单时为 null）。 */
    @Nullable
    public NetworkResourceRequesterBlockEntity getRequester() {
        return requester;
    }

    // ==================== 方块右键入口（唯一的静态门面） ====================

    /**
     * 服务端：这台请求器此刻是否接在可用网络上（「为什么没打开」的口径来源）。
     * <p>判据只有一处：{@link RequesterSenderBackend#isConnectedToNetwork}；本方法只转发，
     * 因此 {@code block/**}、{@code menu/**} 不会各写一套。</p>
     */
    public static boolean isPickerConnected(
        @Nullable final NetworkResourceRequesterBlockEntity requester) {
        return RequesterSenderBackend.isConnectedToNetwork(requester);
    }

    /**
     * 服务端：给这台请求器打开<b>网格主界面</b>（右键方块 = 唯一入口）。
     *
     * <p>判据全在 {@code ResourcePickerMenu#openFor}（接在可用网络上才开），本方法只做空值前置，
     * 因此「右键方块」与「终端发送模式」两条路径不可能各有一套判据。</p>
     *
     * @return {@code false} = 没打开（没接在可用网络上）。调用方必须给一句提示，
     *     绝不留一个点了没反应的交互。
     */
    public static boolean openPickerFor(@Nullable final net.minecraft.server.level.ServerPlayer player,
                                        @Nullable final NetworkResourceRequesterBlockEntity requester) {
        if (player == null || requester == null) {
            return false;
        }
        return cretae.cookiewyq.rs_create_compat.client.grid.ResourcePickerMenu
            .openFor(player, new RequesterSenderBackend(requester),
                Component.translatable(cretae.cookiewyq.rs_create_compat.client.grid
                    .ResourcePickerMenu.TITLE_KEY));
    }

    /**
     * 本菜单的 {@code MenuProvider}（{@code block/**} 的右键路径仍在用）。
     * <p>第 131 轮起菜单<b>永远不会被打开</b>（设置界面已删除）；保留它是因为注册与
     * 方块交互的接口形状仍需要一份合法实现，删掉会让 Lead 独占的注册文件与 block 层一起改。
     * 真正的界面入口是 {@link #openPickerFor}。</p>
     */
    public static net.minecraft.world.MenuProvider providerFor(
        final NetworkResourceRequesterBlockEntity requester) {
        return new net.minecraft.world.SimpleMenuProvider(
            (id, inventory, ignored) -> create(id, inventory, requester),
            Component.translatable(MENU_TITLE_KEY));
    }

    // ==================== 兼容常量（block 层仍引用；值来自共享层） ====================

    /** 「网格打不开：没接网络」的语言键（转发 {@link RequesterSenderBackend}，不另写一份文案）。 */
    public static final String MSG_PICKER_NO_NETWORK = RequesterSenderBackend.MSG_PICKER_NO_NETWORK;

    /** 「网格界面没能打开」的语言键（与上面配成一对：前者=没接网络，后者=这次没开成）。 */
    public static final String MSG_PICKER_OPEN_FAILED = RequesterSenderBackend.MSG_PICKER_OPEN_FAILED;

    // ==================== 容器基本行为 ====================

    /** 本菜单不搬运任何物品（它只是历史留下的容器壳）。 */
    @Override
    public ItemStack quickMoveStack(final Player player, final int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(final Player player) {
        return RequesterSenderBackend.stillValidFor(player, requester);
    }
}
