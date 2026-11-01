package cretae.cookiewyq.rs_create_compat.client;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.support.DecompositionDisplayCache;
import cretae.cookiewyq.rs_create_compat.support.DecompositionDisplayEntry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * JEI「降解发电」分类页的<b>一条配方</b>：一组可放进去的物品 → 发多少电、耗多久、掉什么副产物。
 *
 * <h2>数据从哪来（服务端权威，客户端只是镜像）</h2>
 * <p>{@link #all()} 读 {@link DecompositionDisplayCache}（服务端在数据包同步时下发的整表快照）。
 * 单人游戏里那份镜像还没到时会退回本机表（见该类的 {@code entries()}）—— 两条路径都是
 * <b>同一份</b> {@link DecompositionDisplayEntry} 形状，本类不做任何数值推导。</p>
 *
 * <h2>这里<b>钉死</b>的口径</h2>
 * <ul>
 *     <li><b>数值就是数据包写的原值</b>：{@link #energy()} 是「这一件降解完一次性写进缓存」的 FE，
 *     {@link #time()} 是未装速度升级时的 tick 数。速度升级只缩短耗时、不改总发电量
 *     （见 {@link cretae.cookiewyq.rs_create_compat.support.DecompositionGeneratorSpec#effectiveTime}），
 *     所以 JEI 页面也<b>不</b>给「升级后」的假想值 —— 那会变成一处必然会过期的第二真相；</li>
 *     <li><b>物品 id 解析不出来就跳过</b>（客户端没有这个模组 / 注册表里没有这个物品）。
 *     一条配方的输入列表为空时<b>整条跳过</b>（JEI 不允许空槽的配方），副产物解析失败只跳过那一项；</li>
 *     <li><b>配方 id 由 key 推导</b>（{@link #idOf}）：JEI 的书签 / 复制功能靠它去重，
 *     所以同一个 key 必须永远得到同一个 id。</li>
 * </ul>
 *
 * @param id          配方 id（JEI 书签 / 复制用，见 {@link #idOf(String)}）
 * @param inputs      这条条目适用的物品（JEI 里会轮播显示；不可变、非空）
 * @param time        耗时（tick，数据包原值）
 * @param energy      单次发电量（FE，数据包原值）
 * @param byproducts  副产物（可为空 = 只发电）
 */
public record DecompositionJeiRecipe(ResourceLocation id, List<ItemStack> inputs, int time, int energy,
                                     List<Byproduct> byproducts) {
    /** 副产物：{@code stack} 已带上数量，{@code chance} 是独立掷骰概率（0..1）。 */
    public record Byproduct(ItemStack stack, float chance) {
        public Byproduct {
            stack = stack == null ? ItemStack.EMPTY : stack;
            chance = Float.isNaN(chance) ? 0.0F : Math.max(0.0F, Math.min(1.0F, chance));
        }

        /** 概率百分比（四舍五入到整数，用于 tooltip 文案；100% 时也照实显示，玩家能看出是必掉）。 */
        public int chancePercent() {
            return Math.round(chance * 100.0F);
        }
    }

    public DecompositionJeiRecipe {
        time = Math.max(1, time);
        energy = Math.max(1, energy);
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
        byproducts = byproducts == null ? List.of() : List.copyOf(byproducts);
    }

    /** 平均功率（FE/t）—— 只用于文案展示，不参与记账（与 {@code DecompositionProfile#energyPerTick()} 同口径）。 */
    public double energyPerTick() {
        return (double) energy / (double) time;
    }

    /** 耗时（秒，保留 1 位小数；例：20 tick → {@code 1.0}、25 tick → {@code 1.3}）。 */
    public String secondsText() {
        return RsccJeiText.oneDecimal(time / 20.0D);
    }

    /** 这一条是不是「适用于这个物品栈」（点击机器箭头时用它把页面直接定位到当前物品）。 */
    public boolean matches(final ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        for (final ItemStack input : inputs) {
            if (ItemStack.isSameItem(input, stack)) {
                return true;
            }
        }
        return false;
    }

    // ==================== 从镜像构建 ====================

    /**
     * 把当前镜像整表翻译成 JEI 配方列表（JEI 每次重载调一次）。
     *
     * <p>顺序 = 服务端下发的顺序（精确物品条目在前、tag 条目在后），JEI 内部还会自己排一次；
     * 这里不排序，免得两端顺序不一致时反而看不出问题。</p>
     */
    public static List<DecompositionJeiRecipe> all() {
        final List<DecompositionDisplayEntry> entries = DecompositionDisplayCache.entries();
        final List<DecompositionJeiRecipe> recipes = new ArrayList<>(entries.size());
        for (final DecompositionDisplayEntry entry : entries) {
            final List<ItemStack> inputs = new ArrayList<>(entry.items().size());
            for (final String itemId : entry.items()) {
                final ItemStack stack = stackOf(itemId);
                if (!stack.isEmpty()) {
                    inputs.add(stack);
                }
            }
            if (inputs.isEmpty()) {
                continue; // JEI 不允许空输入槽的配方：整条跳过（数据仍留在镜像里，方便排查）
            }
            final List<Byproduct> byproducts = new ArrayList<>(entry.results().size());
            for (final DecompositionDisplayEntry.Result result : entry.results()) {
                final ItemStack stack = stackOf(result.item());
                if (!stack.isEmpty()) {
                    byproducts.add(new Byproduct(stack.copyWithCount(result.count()), result.chance()));
                }
            }
            recipes.add(new DecompositionJeiRecipe(idOf(entry.key()), inputs,
                entry.time(), entry.energy(), byproducts));
        }
        return List.copyOf(recipes);
    }

    /**
     * 由条目 key 推出稳定的 JEI 配方 id。
     *
     * <ul>
     *     <li>精确物品条目 {@code minecraft:oak_sapling} → {@code rs_create_compat:decomposition/minecraft/oak_sapling}</li>
     *     <li>tag 条目 {@code #minecraft:leaves} → {@code rs_create_compat:decomposition/tag/minecraft/leaves}</li>
     * </ul>
     * <p>{@link ResourceLocation} 的 path 只允许 {@code [a-z0-9_./-]}，因此 {@code ':'} 换成 {@code '/'}，
     * 其余意外字符统一换成 {@code '_'}（数据包写错 id 时也不至于抛异常）。</p>
     */
    public static ResourceLocation idOf(final String key) {
        final String raw = key == null ? "" : key;
        final boolean tag = raw.startsWith("#");
        final String body = tag ? raw.substring(1) : raw;
        final StringBuilder path = new StringBuilder(tag ? "decomposition/tag/" : "decomposition/");
        for (int i = 0; i < body.length(); i++) {
            final char c = Character.toLowerCase(body.charAt(i));
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '/' || c == '.' || c == '-') {
                path.append(c);
            } else if (c == ':') {
                path.append('/');
            } else {
                path.append('_');
            }
        }
        return ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, path.toString());
    }

    /** 注册 id → 数量 1 的物品栈（解析不出来返回 {@link ItemStack#EMPTY}，绝不抛异常）。 */
    private static ItemStack stackOf(final String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return ItemStack.EMPTY;
        }
        final ResourceLocation id = ResourceLocation.tryParse(itemId);
        if (id == null) {
            return ItemStack.EMPTY;
        }
        final Item item = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        return item == null ? ItemStack.EMPTY : new ItemStack(item);
    }
}
