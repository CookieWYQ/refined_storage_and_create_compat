package cretae.cookiewyq.rs_create_compat.support;

import com.refinedmods.refinedstorage.common.api.RefinedStorageApi;
import com.refinedmods.refinedstorage.common.api.support.slotreference.SlotReference;
import com.refinedmods.refinedstorage.common.api.support.slotreference.SlotReferenceFactory;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.item.AdvancedRemoteTerminalItem;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 让放进<b>饰品槽</b>里的高级远程终端，也能被 RS 原版的终端快捷键（本模组默认 <b>G</b>）找到并打开。
 * <p>
 * <b>槽位由前置模组提供（本模组不再注册自己的槽）</b>：前置「Refined Storage - Curios Integration」
 * （modId {@code refinedstorage_curios_integration}）已经提供了 RS 饰品槽本体与槽位背景精灵，
 * 因此本模组只把三件终端登记进<b>前置槽位的物品标签</b>
 * （见 {@code data/curios/tags/item/refinedstorage_curios_integration.json}），不再自建槽位
 * （用户最终要求：「前置本身已经有了，我们没必要再搞一个」）。
 * <p>
 * <b>为什么接入 RS 的 {@code SlotReferenceProvider} 而不是自己发包</b>：
 * RS 原版无线终端快捷键走的是「客户端找引用 → 发 RS 原生 C2S 包 → 服务端解引用并调用物品的
 * {@code SlotReferenceHandlerItem#use}」这条链路（{@code RefinedStorageApi#useSlotReferencedItem}）。
 * 这里只按官方扩展点补一个「饰品槽」的引用来源，于是：
 * <ul>
 *     <li>打开界面用的仍是<b>既有</b>的 {@code AdvancedRemoteTerminalItem#use} → {@code openModeScreen}；</li>
 *     <li>「有没有终端」由服务端解引用时再判一次（{@code instanceof SlotReferenceHandlerItem}），
 *     客户端骗不过去；找不到 / 找到多个时由 RS 原版给出红字短提示（"There isn't any %s..."）；</li>
 *     <li>不用新增任何网络包，背包里的情形由 RS 自带的 {@code InventorySlotReferenceProvider} 覆盖。</li>
 * </ul>
 * <p>
 * <b>为什么不直接编译期依赖 Curios</b>：本工程离线编译（{@code tools/manual_compile.ps1}）只用本地
 * Gradle 缓存里的 jar，缓存中没有 Curios 1.21.1 制品，直接引用其类会导致编译失败。
 * 因此这里全程<b>反射</b>：CuriosApi → {@code getCuriosInventory(LivingEntity)} →
 * {@code getStacksHandler(槽位 id)} → {@code getStacks()}。Curios 缺失 / 版本方法名不符时
 * 整条链路静默降级为「只认背包」，绝不抛异常、绝不影响加载。
 */
public final class RsccCuriosTerminalSlot {
    /**
     * 前置「Refined Storage - Curios Integration」的饰品槽 id（= 它的 modId）。
     * <p>本模组把三件终端加进了这个槽位的物品标签
     * （{@code data/curios/tags/item/refinedstorage_curios_integration.json}），
     * 因此玩家会把终端插进<b>前置的槽</b>；本模组<b>不再</b>注册自己的槽位（用户要求删掉），
     * 所以这里只保留前置槽 id 作为「首选扫描目标」。</p>
     */
    public static final String RS_SLOT_ID = "refinedstorage_curios_integration";
    /** 首选的饰品槽 id（前置 RS 槽）；其余槽位由 {@link #slotIds} 从玩家实际拥有的槽位里兜底枚举。 */
    private static final String[] CANDIDATE_SLOT_IDS = {RS_SLOT_ID};

    private static final Logger LOGGER = LoggerFactory.getLogger(RsccCuriosTerminalSlot.class);
    /** Curios 的入口类：{@code CuriosApi.getCuriosInventory(LivingEntity)}。 */
    private static final String CURIOS_API_CLASS = "top.theillusivec4.curios.api.CuriosApi";

    private static final ResourceLocation FACTORY_ID =
        ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "curios_terminal");
    private static final SlotReferenceFactory FACTORY = new CuriosStackReferenceFactory();

    /** 反射方法缓存（按「实现的类型 → 方法名」缓存；Curios 各版本的接口类名改过名，故按实例自省）。 */
    private static final ConcurrentHashMap<Class<?>, Optional<Method>> STACKS_HANDLER_METHODS =
        new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, Optional<Method>> GET_STACKS_METHODS =
        new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, Optional<Method>> GET_SLOTS_METHODS =
        new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, Optional<Method>> GET_STACK_IN_SLOT_METHODS =
        new ConcurrentHashMap<>();
    /** 按「槽位 id → 处理器」映射取值的回退方法缓存（部分 Curios 版本没有 getStacksHandler）。 */
    private static final ConcurrentHashMap<Class<?>, Optional<Method>> GET_CURIOS_METHODS =
        new ConcurrentHashMap<>();

    /** 已提示过「该槽位不存在」的槽位 id（避免每次查询都刷日志）。 */
    private static final Set<String> MISSING_SLOT_LOGGED = ConcurrentHashMap.newKeySet();
    /** 已提示过「槽位处理器形状认不出来」的处理器类型名（按类型去重，避免每次查询都刷日志）。 */
    private static final Set<String> UNKNOWN_SHAPE_LOGGED = ConcurrentHashMap.newKeySet();
    /** 已提示过「在某槽位找到终端」的标记（槽位 # 下标 # 物品，避免重复刷）。 */
    private static final Set<String> FOUND_LOGGED = ConcurrentHashMap.newKeySet();

    private RsccCuriosTerminalSlot() {
    }

    /**
     * 注册到 RS（common 端在 {@code commonSetup} 调用一次）：
     * ① 引用工厂 —— RS 发 C2S 包时要用它把引用编码 / 解码（双端都要注册）；
     * ② 引用提供者 —— 快捷键查找终端时把「饰品槽里的终端」一并列出。
     */
    public static void register() {
        if (!OptionalDeps.isCuriosLoaded()) {
            LOGGER.info("Curios is not loaded: terminal shortcut falls back to the player inventory only");
            return;
        }
        RefinedStorageApi.INSTANCE.getSlotReferenceFactoryRegistry().register(FACTORY_ID, FACTORY);
        RefinedStorageApi.INSTANCE.addSlotReferenceProvider(RsccCuriosTerminalSlot::find);
        LOGGER.info("Registered terminal slot reference provider for Curios slots {}",
            String.join(", ", CANDIDATE_SLOT_IDS));
    }

    /** RS 的 {@code SlotReferenceProvider}：列出候选饰品槽里所有「属于 validItems」的格子。 */
    private static List<SlotReference> find(final Player player, final Set<Item> validItems) {
        final List<SlotReference> result = new ArrayList<>();
        for (final String slotId : slotIds(player)) {
            final List<ItemStack> stacks = slots(player, slotId);
            for (int i = 0; i < stacks.size(); i++) {
                if (validItems.contains(stacks.get(i).getItem())) {
                    logFound(slotId, i, stacks.get(i));
                    result.add(new CuriosStackReference(slotId, i));
                }
            }
        }
        return result;
    }

    /**
     * 找到饰品槽里第一格<b>高级远程多功能终端</b>的槽位引用。
     * <p>客户端 {@link cretae.cookiewyq.rs_create_compat.client.TerminalModeTabOverlay}
     * 在「主手 / 副手 / 背包都没有终端」时调用本方法回退查饰品槽，保证从饰品槽打开终端后
     * 右下角的模式切换 Tab 仍能正常渲染（Curios 缺失 / 没放终端 → {@link Optional#empty()}）。
     * 返回的 {@link SlotReference#resolve} 可直接取到该格的<b>存活栈</b>用于读写模式 NBT。</p>
     */
    public static Optional<SlotReference> findTerminalReference(final Player player) {
        return findAllTerminalReferences(player).stream().findFirst();
    }

    /**
     * 饰品槽里<b>全部</b>高级远程多功能终端的槽位引用（槽位 id 顺序 → 槽内下标顺序）。
     * <p>{@link RsccTerminalLocator} 用它统计「身上一共几台终端」以给出一次性日志说明；
     * 打开界面时只取列表第一个（多个也<b>不拒绝</b>，与 RS 的「唯一性」语义解耦）。</p>
     */
    public static List<SlotReference> findAllTerminalReferences(final Player player) {
        final List<SlotReference> found = new ArrayList<>(2);
        for (final String slotId : slotIds(player)) {
            final List<ItemStack> stacks = slots(player, slotId);
            for (int i = 0; i < stacks.size(); i++) {
                final ItemStack stack = stacks.get(i);
                if (!stack.isEmpty() && stack.getItem() instanceof AdvancedRemoteTerminalItem) {
                    logFound(slotId, i, stack);
                    found.add(new CuriosStackReference(slotId, i));
                }
            }
        }
        return found;
    }

    /**
     * 玩家身上<b>全部饰品槽</b>里的物品（Curios 缺失 / 没有任何饰品槽 / 反射链路失败 → 空表）。
     *
     * <p><b>为什么放在本类</b>：本工程对 Curios 的访问只有这一份反射实现（离线编译拿不到 Curios 制品，
     * 见类注释），范围充电器要扫饰品槽时不应该再抄一份；本方法只做「把反射读到的存活栈交出去」，
     * 不含任何充电策略。</p>
     *
     * <p><b>返回的是处理器内部的存活栈</b>（不是快照）：与 {@link #findAllTerminalReferences} 的
     * {@code resolve} 语义一致，调用方对电量组件的写入会直接落在玩家饰品槽里的那件物品上。</p>
     *
     * <p><b>绝不抛异常</b>：未装 Curios 时 {@code curiosInventory} 第一句就返回 null，
     * 反射失败也在 {@code slots} 内部被吞掉 —— 调用方（范围充电器）因此不需要任何 try/catch，
     * 「没装 Curios」与「装了但槽位是空的」得到的是同一个结果：什么都不用充。</p>
     */
    public static List<ItemStack> allStacks(final LivingEntity player) {
        final List<ItemStack> found = new ArrayList<>();
        for (final String slotId : slotIds(player)) {
            final List<ItemStack> stacks = slots(player, slotId);
            if (stacks == null) {
                continue; // 处理器形状认不出来（slots 已打过一条节流日志）：这一槽位跳过
            }
            for (final ItemStack stack : stacks) {
                if (!stack.isEmpty()) {
                    found.add(stack);
                }
            }
        }
        return found;
    }

    /**
     * 本次扫描要看的饰品槽 id 列表。
     * <p><b>优先枚举玩家实际拥有的全部饰品槽</b>（Curios 的 {@code getCurios()} 返回「槽位 id → 处理器」映射）：
     * 本模组不再自建槽位，终端由<b>前置槽位</b>的物品标签接纳；但终端也可能被塞进<b>别的模组</b>的
     * 饰品槽（例如任何接受「任意物品」的槽）。只扫写死的前置槽 id 会在这种情况下报「找不到终端」——
     * 枚举一次即可彻底消除这类「槽位 id 猜错」的故障。
     * 拿不到映射（版本差异 / 反射失败）时退回候选 id 列表。</p>
     */
    private static List<String> slotIds(final LivingEntity player) {
        final java.util.Map<?, ?> map = curiosSlotMap(player);
        if (map == null || map.isEmpty()) {
            return List.of(CANDIDATE_SLOT_IDS);
        }
        final List<String> ids = new ArrayList<>(map.size() + CANDIDATE_SLOT_IDS.length);
        // 前置 RS 槽优先（终端的主要去处），随后是映射里的其余槽位（含其它模组的槽）
        for (final String candidate : CANDIDATE_SLOT_IDS) {
            if (map.containsKey(candidate)) {
                ids.add(candidate);
            }
        }
        for (final Object key : map.keySet()) {
            if (key instanceof String id && !ids.contains(id)) {
                ids.add(id);
            }
        }
        return ids.isEmpty() ? List.of(CANDIDATE_SLOT_IDS) : ids;
    }

    /** Curios 的「槽位 id → 处理器」映射（拿不到 → {@code null}）。 */
    @Nullable
    private static java.util.Map<?, ?> curiosSlotMap(final LivingEntity player) {
        final Object inventory = curiosInventory(player);
        if (inventory == null) {
            return null;
        }
        try {
            final Method getCurios = cachedMethod(GET_CURIOS_METHODS, inventory.getClass(), "getCurios");
            if (getCurios == null) {
                return null;
            }
            return getCurios.invoke(inventory) instanceof java.util.Map<?, ?> map ? map : null;
        } catch (final ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** Curios 的饰品库存对象（Curios 缺失 / 反射失败 → {@code null}）。 */
    @Nullable
    private static Object curiosInventory(final LivingEntity player) {
        if (!OptionalDeps.isCuriosLoaded()) {
            return null;
        }
        try {
            final Method getCuriosInventory = findMethod(
                Class.forName(CURIOS_API_CLASS), "getCuriosInventory", LivingEntity.class);
            if (getCuriosInventory == null) {
                LOGGER.warn("CuriosApi.getCuriosInventory(LivingEntity) not found; terminal shortcut degrades to "
                    + "the player inventory only");
                return null;
            }
            return getCuriosInventory.invoke(null, player) instanceof Optional<?> inventory
                && inventory.isPresent() ? inventory.get() : null;
        } catch (final ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("Failed to read the Curios inventory; terminal shortcut degrades to inventory only", e);
            return null;
        }
    }

    /** 记录一次「在饰品槽里找到了终端」（同一槽位 / 下标 / 物品只记一次）：玩家反馈时据此定位。 */
    private static void logFound(final String slotId, final int index, final ItemStack stack) {
        if (FOUND_LOGGED.add(slotId + "#" + index + "#" + stack.getItem())) {
            LOGGER.info("Found terminal {} in Curios slot {} index {}",
                stack.getItem(), slotId, index);
        }
    }

    /** 指定饰品槽里的物品（Curios 缺失 / 槽位不存在 / 反射失败 → 空表）。 */
    private static List<ItemStack> slots(final LivingEntity player, final String slotId) {
        final Object inventory = curiosInventory(player);
        if (inventory == null) {
            return List.of();
        }
        try {
            final Method getStacksHandler = cachedMethod(
                STACKS_HANDLER_METHODS, inventory.getClass(), "getStacksHandler", String.class);
            if (getStacksHandler != null) {
                final Object raw = getStacksHandler.invoke(inventory, slotId);
                if (raw instanceof Optional<?> slot) {
                    if (slot.isEmpty()) {
                        logMissingSlot(slotId);
                        return List.of();
                    }
                    return stacksOf(slot.get(), slotId);
                }
                if (raw != null) {
                    // 部分版本直接返回处理器本体（不是 Optional）
                    return stacksOf(raw, slotId);
                }
                logMissingSlot(slotId);
                return List.of();
            }
            // 回退：有的版本用 getCurios() 返回「槽位 id → 处理器」的映射，没有 getStacksHandler
            final Method getCurios = cachedMethod(GET_CURIOS_METHODS, inventory.getClass(), "getCurios");
            if (getCurios != null) {
                if (getCurios.invoke(inventory) instanceof java.util.Map<?, ?> map) {
                    final Object handler = map.get(slotId);
                    if (handler != null) {
                        return stacksOf(handler, slotId);
                    }
                }
                logMissingSlot(slotId);
                return List.of();
            }
            return List.of();
        } catch (final ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("Failed to read Curios slot {}; terminal shortcut degrades to inventory only", slotId, e);
            return List.of();
        }
    }

    /** 记录一次「该饰品槽不存在」（每次进程只记一次，避免刷屏）。 */
    private static void logMissingSlot(final String slotId) {
        if (MISSING_SLOT_LOGGED.add(slotId)) {
            LOGGER.info("Curios slot {} is not present for the player; skipping it when locating the terminal",
                slotId);
        }
    }

    /** 记录一次「槽位处理器形状认不出来」（每次进程 / 每个形状只记一次）：反射链路失败时据此定位。 */
    private static void logUnknownShape(final String slotId, final Class<?> type) {
        if (UNKNOWN_SHAPE_LOGGED.add(type.getName())) {
            LOGGER.warn("Curios slot {} uses an unrecognized stack handler {} (no getStacks()/List, "
                + "no getSlots()+getStackInSlot(int)); terminal shortcut ignores this slot", slotId, type);
        }
    }

    /**
     * 取某个槽位处理器里的全部物品栈（返回的是处理器内部的<b>存活栈</b>：
     * 物品模式写回、电量消耗都作用在这份实体上，与 RS 原版 {@code InventorySlotReference} 行为一致）。
     *
     * <p><b>两种处理器形状都要认（本轮修正的真因）</b>：</p>
     * <ol>
     *     <li>处理器直接把内容给成 {@code List<ItemStack>}（少数版本 / 包装类）；</li>
     *     <li>处理器给的是<b>一个槽位容器</b>（Curios 9.x 的
     *     {@code ICurioStacksHandler#getStacks()} 返回 {@code IDynamicStackHandler}，
     *     它其实是个 {@code IItemHandler}，<b>不是 List</b>）—— 这时必须对<b>它</b>
     *     按 {@code getSlots() + getStackInSlot(i)} 读。
     *     <p>旧实现把「非 List 的返回值」直接丢掉，转而去问处理器自己要 {@code getSlots()}：
     *     {@code ICurioStacksHandler} 确实有 {@code getSlots()}（槽位数）却<b>没有</b>
     *     {@code getStackInSlot(int)}，于是每次都静默返回空表 —— 表现为「终端放进饰品槽后
     *     按快捷键永远提示找不到」（用户实测），而且一条日志都不打，极难定位。</li>
     * </ol>
     *
     * @return 认不出来的处理器形状返回 {@code null}（调用方据此打一条节流日志后按空表处理）
     */
    @Nullable
    private static List<ItemStack> stacksOf(final Object stacksHandler, final String slotId)
        throws ReflectiveOperationException {
        final Class<?> type = stacksHandler.getClass();
        final Method getStacks = cachedMethod(GET_STACKS_METHODS, type, "getStacks");
        if (getStacks != null) {
            final Object raw = getStacks.invoke(stacksHandler);
            if (raw instanceof List<?> list) {
                final List<ItemStack> out = new ArrayList<>(list.size());
                for (final Object element : list) {
                    out.add(element instanceof ItemStack stack ? stack : ItemStack.EMPTY);
                }
                return out;
            }
            if (raw != null) {
                final List<ItemStack> bySlots = itemsOf(raw);
                if (bySlots != null) {
                    return bySlots;
                }
            }
        }
        // 兜底：处理器自己就是「IItemHandler 风格」（getSlots + getStackInSlot）
        final List<ItemStack> direct = itemsOf(stacksHandler);
        if (direct == null) {
            logUnknownShape(slotId, type);
        }
        return direct;
    }

    /** 按 {@code IItemHandler} 风格读一个对象里的全部物品（没有这两个方法 → {@code null}）。 */
    @Nullable
    private static List<ItemStack> itemsOf(final Object handler) {
        final Class<?> type = handler.getClass();
        final Method getSlots = cachedMethod(GET_SLOTS_METHODS, type, "getSlots");
        final Method getStackInSlot = cachedMethod(GET_STACK_IN_SLOT_METHODS, type, "getStackInSlot", int.class);
        if (getSlots == null || getStackInSlot == null) {
            return null;
        }
        try {
            final int size = (Integer) getSlots.invoke(handler);
            final List<ItemStack> out = new ArrayList<>(Math.max(0, size));
            for (int i = 0; i < size; i++) {
                out.add((ItemStack) getStackInSlot.invoke(handler, i));
            }
            return out;
        } catch (final ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static Method cachedMethod(final ConcurrentHashMap<Class<?>, Optional<Method>> cache,
                                       final Class<?> type,
                                       final String name,
                                       final Class<?>... parameterTypes) {
        return cache.computeIfAbsent(type, key -> Optional.ofNullable(findMethod(key, name, parameterTypes)))
            .orElse(null);
    }

    /** 沿「父类 → 接口」层级查找 public 方法（只按方法名 / 参数类型，不写死 Curios 的接口类名）。 */
    private static Method findMethod(final Class<?> type, final String name, final Class<?>... parameterTypes) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getMethod(name, parameterTypes);
            } catch (final NoSuchMethodException ignored) {
                // 继续向上找
            }
        }
        for (final Class<?> iface : type.getInterfaces()) {
            final Method found = findMethod(iface, name, parameterTypes);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * 指向「某个饰品槽 id 的第 index 格」的引用。
     * <p>必须同时存<b>槽位 id</b>与下标：终端插进的是前置槽
     * 「Refined Storage - Curios Integration」（见 {@link #RS_SLOT_ID}），而兜底枚举还可能命中其它槽位，
     * 只存下标会在跨槽时解错位置。
     * 只存下标/字符串、不存物品快照：双端按同一槽位重新取值，避免把物品实例传来传去
     * （槽位不存在 / 该格已空 → {@link Optional#empty()}，服务端据此拒绝打开）。</p>
     */
    private record CuriosStackReference(String slotId, int index) implements SlotReference {
        @Override
        public boolean isDisabledSlot(final int playerSlotIndex) {
            // 饰品槽不在容器菜单里，没有「需要禁用的菜单槽位」
            return false;
        }

        @Override
        public Optional<ItemStack> resolve(final Player player) {
            final List<ItemStack> stacks = slots(player, slotId);
            if (index < 0 || index >= stacks.size()) {
                return Optional.empty();
            }
            final ItemStack stack = stacks.get(index);
            return stack.isEmpty() ? Optional.empty() : Optional.of(stack);
        }

        @Override
        public SlotReferenceFactory getFactory() {
            return FACTORY;
        }
    }

    /** 引用的网络序列化（RS 的 {@code UseSlotReferencedItemPacket} 靠它把引用发给服务端）。 */
    private static final class CuriosStackReferenceFactory implements SlotReferenceFactory {
        private static final StreamCodec<RegistryFriendlyByteBuf, CuriosStackReference> STREAM_CODEC =
            StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, CuriosStackReference::slotId,
                ByteBufCodecs.VAR_INT, CuriosStackReference::index,
                CuriosStackReference::new
            );

        @Override
        @SuppressWarnings({"rawtypes", "unchecked"})
        public StreamCodec<RegistryFriendlyByteBuf, SlotReference> getStreamCodec() {
            return (StreamCodec) STREAM_CODEC;
        }
    }
}
