package cretae.cookiewyq.rs_create_compat.support;

import com.refinedmods.refinedstorage.common.api.RefinedStorageApi;
import com.refinedmods.refinedstorage.common.api.storage.SerializableStorage;
import com.refinedmods.refinedstorage.common.api.storage.StorageContainerItem;
import com.refinedmods.refinedstorage.common.api.storage.StorageRepository;
import cretae.cookiewyq.rs_create_compat.storage.RsccDiskItemHandler;
import cretae.cookiewyq.rs_create_compat.storage.RsccDiskSlotCache;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.Nullable;

/**
 * 给 RS 存储磁盘（{@link StorageContainerItem}）补注册 {@code Capabilities.ItemHandler.ITEM}。
 *
 * <h2>为什么必须补注册（而不是在代理器里写特例）</h2>
 * <p>RS 2.0.9 <b>没有</b>给磁盘注册物品能力：磁盘物品上只有一个 UUID 组件
 * {@code refinedstorage:storage_reference}，内容在 overworld 的 {@code StorageRepository}（SavedData）里
 * （{@code docs/RS_DISK_ADAPTER_DESIGN.md} §1）。不补注册的话，代理器「按能力判断能不能代理」会把
 * 磁盘判成「不是容器」，内容物面永远 0 格。补注册后，代理器<b>零特例</b>：
 * {@code stack.getCapability(Capabilities.ItemHandler.ITEM)} 一条路同时覆盖潜影盒 / 背包 / 磁盘。</p>
 *
 * <h2>为什么覆盖范围是「所有 {@link StorageContainerItem}」而不是写死 id</h2>
 * <p>RS 2.0.9 里根本没有 {@code refinedstorage:disk} 这个 id（实际是 {@code 1k_storage_disk} /
 * {@code 4k_storage_disk} / … / {@code 64b_fluid_storage_disk}）。本模组的
 * {@code UniversalStorageDiskItem extends AbstractStorageContainerItem} 与 RS 原生盘同构
 * （同一 UUID 组件、同一 {@code StorageRepository}，只是 {@code StorageType} 不同），
 * 所以按类遍历可以一次覆盖 RS 原盘 + 本模组通用盘 + 未来的第三方 addon 盘。</p>
 *
 * <h2>provider 没有 Level，怎么办</h2>
 * <p>{@code Capabilities.ItemHandler.ITEM} 的 context 是 {@code Void}（没有 level / 方向 / 玩家）。
 * 因此 provider 用 {@link ServerLifecycleHooks#getCurrentServer()} + overworld 取
 * {@link StorageRepository}，并用 {@link MinecraftServer#isSameThread()} 做线程门禁：</p>
 * <ul>
 *     <li>单人游戏的客户端线程也能拿到集成服务器对象 —— 不判线程就会在客户端线程读服务端的 HashMap，
 *     数据竞争，甚至踩到 {@code ClientStorageRepository#get} 的 {@code UnsupportedOperationException}；</li>
 *     <li>客户端本来就没有磁盘内容，返回 {@code null} 是正确语义（客户端只需要「存了多少」，
 *     那是 RS 自己的包同步的事）。</li>
 * </ul>
 *
 * <p><b>未格式化盘</b>：{@code resolve} 返回空 ⇒ 本 provider 返回 {@code null}（没有能力）。
 * 这是刻意的：磁盘只会在<b>玩家背包 tick</b> 时自动格式化，放在箱子 / 漏斗 / 代理器里的盘永远不会有 UUID。
 * 代理器的对象槽因此额外接受 {@link StorageContainerItem} 类物品，并在界面上提示「先拿在背包里 tick 一次」。</p>
 */
public final class RsccDiskItemCapabilities {
    private static boolean lifecycleHooked;

    private RsccDiskItemCapabilities() {
    }

    /** MOD 总线：给所有 {@link StorageContainerItem} 补注册物品物品能力（跳过已有 provider 的物品）。 */
    public static void register(final RegisterCapabilitiesEvent event) {
        hookLifecycle();
        for (final Item item : BuiltInRegistries.ITEM) {
            if (!(item instanceof StorageContainerItem)) {
                continue;
            }
            // 先注册者先返回（ItemCapability#getCapability 取第一个非 null），
            // 别的模组已经提供就不要再遮蔽它（设计文档 §3.1.3）。
            if (event.isItemRegistered(Capabilities.ItemHandler.ITEM, item)) {
                continue;
            }
            event.registerItem(Capabilities.ItemHandler.ITEM,
                (stack, context) -> provideDiskHandler(stack), item);
        }
    }

    private static void hookLifecycle() {
        if (lifecycleHooked) {
            return;
        }
        lifecycleHooked = true;
        // 换世界 / 停服时清掉粘性槽位表（按存储对象身份的弱引用表，跨存档不应复用）。
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent.class,
            event -> RsccDiskSlotCache.clear());
    }

    /**
     * 能力实现：把<b>这一张盘</b>（真实 {@code ItemStack} 上的 UUID）对应的那份存储包成
     * 固定 N 槽的资源种类窗口。
     *
     * @return 未格式化 / UUID 不在仓库 / 非服务端主线程 / 服务器未启动时返回 {@code null}
     */
    @Nullable
    public static IItemHandler provideDiskHandler(final ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof StorageContainerItem disk)) {
            return null;
        }
        final MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || !server.isSameThread()) {
            // 客户端线程 / 网络线程 / 异步线程一律不提供（见类注释）。
            return null;
        }
        final ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) {
            return null;
        }
        final StorageRepository repository = RefinedStorageApi.INSTANCE.getStorageRepository(overworld);
        final SerializableStorage storage = disk.resolve(repository, stack).orElse(null);
        if (storage == null) {
            // 未格式化盘 / 跨存档复制过来的盘：没有 UUID 或 UUID 不在仓库里。
            return null;
        }
        return new RsccDiskItemHandler(storage, repository);
    }
}
