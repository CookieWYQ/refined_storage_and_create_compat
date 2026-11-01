package cretae.cookiewyq.rs_create_compat.support;

import cretae.cookiewyq.rs_create_compat.block.entity.InventoryProxyBlockEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 容器代理器交给 Jade 的「内容物事实」载荷（<b>公共类型，不碰任何 Jade 类</b>）。
 *
 * <h2>这段文字解决的是什么问题（用户第 108 条）</h2>
 * <p>用户原话：「把装了储罐升级的那个放进去，用 Jade 查看的时候它只显示有流体，里面的物品却没有显示」。
 * Jade 的容器内容由它自己的 Universal 插件按方块能力采集 —— 它显示的东西<b>永远是真的</b>，
 * 问题在于「只显示流体」这件事<b>本身没有解释</b>：玩家看到的是一句无声的「没有物品」，
 * 无法区分下面三种情况：</p>
 * <ol>
 *     <li>物品面确实 0 格（该物品此刻没有可读的物品库存，例如精妙背包的 storage UUID 不在物品上）；</li>
 *     <li>物品面有格但全空（真的没东西）；</li>
 *     <li>代理解析那一路出错（第 108 轮以前会被永久粘住）。</li>
 * </ol>
 * <p>所以本模组在 Jade 里<b>补一行数字</b>：`内容物：物品 N 格 · 流体 M 罐`。数字来自
 * {@link InventoryProxyBlockEntity#innerItemSlots()} / {@link #lines(CompoundTag)}，
 * 与内部 handler 的 {@code getSlots()} / {@code getTanks()} 同一个来源 ——
 * 不自己再数一遍内容物（那会变成第二份口径，迟早和 RS 的读数分叉）。</p>
 *
 * <h2>为什么这一行不能替代服务端修复</h2>
 * <p>它只报事实，不改变能力：代理器两路能力各自独立、退化态自动重试是
 * {@link InventoryProxyBlockEntity} 的职责（第 108 轮修复）。这里绝不「把 0 格说成有」——
 * 那样才是真正的糊弄。</p>
 *
 * <h2>为什么放在 support 包而不是 compat/jade</h2>
 * <p>本类<b>不 import 任何 {@code snownee.jade.*}</b> 类型（见 {@code tools/selfcheck_round54_jade_bus.py}
 * 的 ③d 判据），所以即使 Jade 缺席、或者其它模块只想读这几个键，也可以安全加载。</p>
 */
public final class InventoryProxyJadePayload {
    /** 服务端数据 UID（与客户端 provider 的 UID 分开：Jade 按 UID 建配置项 / 去重）。 */
    public static final String SERVER_DATA_ID = "proxy_data";
    /** 客户端 provider UID。 */
    public static final String COMPONENT_ID = "proxy";

    private static final String KEY_OBJECT = "ProxyObject";
    private static final String KEY_STATE = "ProxyInnerState";
    private static final String KEY_ITEM_SLOTS = "ProxyItemSlots";
    private static final String KEY_FLUID_TANKS = "ProxyFluidTanks";

    private static final String LANG = "gui.rs_create_compat.inventory_proxy.jade.";

    private InventoryProxyJadePayload() {
    }

    /**
     * 服务端写：把「有没有代理对象 / 四态 / 两路格数」写进 Jade 的服务端数据标签。
     *
     * <p>只写 4 个 int/bool，没有内容物本身 —— Jade 自己的物品/流体组已经按能力采过了，
     * 这里补的是「代理器实际认到的是什么形态」。</p>
     */
    public static void write(final CompoundTag tag, final boolean hasObject,
                             final InventoryProxyBlockEntity.InnerState state,
                             final int itemSlots, final int fluidTanks) {
        tag.putBoolean(KEY_OBJECT, hasObject);
        tag.putInt(KEY_STATE, state.id());
        tag.putInt(KEY_ITEM_SLOTS, Math.max(0, itemSlots));
        tag.putInt(KEY_FLUID_TANKS, Math.max(0, fluidTanks));
    }

    /** 服务端是否写过数据（客户端据此决定「一行都不加」还是「按事实加行」）。 */
    public static boolean hasData(final CompoundTag tag) {
        return tag != null && tag.contains(KEY_OBJECT) && tag.contains(KEY_STATE);
    }

    /** 代理槽为空时不加行（Jade 自己会显示方块本体信息，多一行“没有代理对象”只会吵）。 */
    public static boolean hasObject(final CompoundTag tag) {
        return tag != null && tag.getBoolean(KEY_OBJECT);
    }

    /**
     * 把事实翻成要给玩家看的那 1~2 行。
     *
     * <ul>
     *     <li>两路都有：`内容物：物品 N 格 · 流体 M 罐`；</li>
     *     <li>只有物品：`内容物：物品 N 格`（潜影盒 / 收纳袋 / 磁盘都是这一态，属正常，不额外告警）；</li>
     *     <li><b>只有流体</b>：`内容物：流体 M 罐 · 物品 0 格` + 一行解释（这一态几乎总是
     *     「物品库存此刻不可读」——精妙背包的物品 provider 以 storage UUID 为门槛、流体 handler 是
     *     懒建缓存，正是这个指纹）；</li>
     *     <li>都没有：`内容物：没有可读库存`。</li>
     * </ul>
     */
    public static List<Component> lines(final CompoundTag tag) {
        final InventoryProxyBlockEntity.InnerState state =
            InventoryProxyBlockEntity.InnerState.byId(tag.getInt(KEY_STATE));
        final int itemSlots = tag.getInt(KEY_ITEM_SLOTS);
        final int fluidTanks = tag.getInt(KEY_FLUID_TANKS);
        return switch (state) {
            case BOTH -> List.of(Component.translatable(LANG + "both", itemSlots, fluidTanks));
            case ITEM -> List.of(Component.translatable(LANG + "item_only", itemSlots));
            case FLUID -> List.of(
                Component.translatable(LANG + "fluid_only", fluidTanks),
                Component.translatable(LANG + "fluid_only_hint"));
            case NONE -> List.of(Component.translatable(LANG + "none"));
        };
    }
}
