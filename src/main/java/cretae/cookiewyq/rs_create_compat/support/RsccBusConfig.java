package cretae.cookiewyq.rs_create_compat.support;

import com.refinedmods.refinedstorage.common.support.FilterWithFuzzyMode;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;

/**
 * 「一条总线的玩家配置」在<b>机械动力剪贴板</b>上的载荷格式（用户第 7 条：批量给总线安排相同配置）。
 *
 * <h2>为什么单独一个类（而不是写在剪贴板挂点里）</h2>
 * <p>复制与粘贴分别发生在两个方块实体 Mixin 上（输入总线 / 输出总线的字段是私有的），
 * 而「这段 NBT 长什么样」必须只有<b>一份</b>定义：否则复制按输入总线的写法、粘贴按输出总线的读法，
 * 一旦漂移就是「粘上去没反应」这种最难查的问题。因此键名 / 版本号 / 取值都收敛在本类，
 * 两个 Mixin 只负责把<b>自己那几个字段</b>填进来、读出去（见
 * {@code RsccImporterExecutorMode#rscc$writeBusConfig} / {@code RsccExporterExecutorMode#rscc$writeBusConfig}）。</p>
 *
 * <h2>载荷里有什么（= 玩家眼里「这条总线的配置」）</h2>
 * <ol>
 *     <li><b>RS 过滤槽</b>（{@link #KEY_FILTER}）—— 直接调用 RS 自己的
 *     {@code FilterWithFuzzyMode#save}，因此物品 / 流体过滤器<b>与模糊模式开关</b>一起被带走，
 *     格式与 RS 落盘逐字一致（{@code rf} = 过滤项、{@code fm} = 模糊模式）；
 *     本模组<b>不</b>自己序列化 ResourceKey，避免与 RS 的版本差异。</li>
 *     <li><b>本模组的类别勾选</b>（{@link #KEY_CATEGORIES}）—— 一行一个稳定类别 id（见 {@link RsccBusCategory}）；
 *     「是否显式勾选过」（{@link #KEY_EXPLICIT}）必须一起带走：不带走的话
 *     「从未勾过（= 默认全选输入性产物）」会被粘成「一个都不勾」，语义完全反了。</li>
 *     <li><b>输入总线的全自动收回开关</b>（{@link #KEY_AUTO}）—— 只在输入总线一侧有意义
 *     （输出总线没有这个开关，见 {@code RsccImporterExecutorMode#rscc$isAutoCollect()}）。</li>
 *     <li><b>「强制普通总线」开关</b>（{@link #KEY_FORCE_NORMAL}）—— 与用户第 9 条那条
 *     「过滤槽有东西 ⇒ 按普通总线」的自动规则并列，两条都是「本总线算不算延长型」的玩家意图，
 *     因此复制配置时必须一起带走，否则粘贴出来的总线与源总线<b>不是同一种工作状态</b>。</li>
 * </ol>
 *
 * <h2>校验（粘贴前的第一道闸门）</h2>
 * <p>{@link #writeHeader} / {@link #acceptsKind}：版本号只认「不高于本模组认识的版本」，
 * 种类（输入 / 输出总线）必须一致。种类不一致时调用方给一句明确反馈并<b>不写入任何字段</b> ——
 * 这正是「不要误改到不相关的总线」那条硬要求在数据层的落点。</p>
 */
public final class RsccBusConfig {
    /** 载荷版本：将来格式变化时靠它把旧剪贴板数据安全地拒掉（而不是读出一堆默认值）。 */
    public static final int VERSION = 1;

    /** 版本号。 */
    public static final String KEY_VERSION = "v";
    /** 种类（{@link #KIND_IMPORTER} / {@link #KIND_EXPORTER}）。 */
    public static final String KEY_KIND = "kind";
    /** RS 过滤槽（{@code FilterWithFuzzyMode} 自己那份子标签）。 */
    public static final String KEY_FILTER = "filter";
    /** 本模组类别勾选（字符串列表）。 */
    public static final String KEY_CATEGORIES = "categories";
    /** 「是否显式勾选过类别」。 */
    public static final String KEY_EXPLICIT = "categories_explicit";
    /** 输入总线的「全自动收回」开关。 */
    public static final String KEY_AUTO = "auto";
    /** 「强制普通总线」开关。 */
    public static final String KEY_FORCE_NORMAL = "force_normal";

    /** 种类值：输入总线。 */
    public static final String KIND_IMPORTER = "importer";
    /** 种类值：输出总线。 */
    public static final String KIND_EXPORTER = "exporter";

    private RsccBusConfig() {
    }

    /** 写载荷头（版本 + 种类）。两种总线的复制路径都从这里开头，格式因此只有一份定义。 */
    public static void writeHeader(final CompoundTag tag, final String kind) {
        tag.putInt(KEY_VERSION, VERSION);
        tag.putString(KEY_KIND, kind);
    }

    /**
     * 这份载荷能不能贴到 {@code kind} 这种总线上。
     * <p>判据两条，缺一不可：① 版本不高于本模组认识的上限（更新的格式一律拒，绝不做「尽力而为」的猜测）；
     * ② 种类一致（输入总线的配置贴到输出总线上没有任何一项是对得上的）。</p>
     */
    public static boolean acceptsKind(final CompoundTag tag, final String kind) {
        return tag.contains(KEY_KIND, Tag.TAG_STRING)
            && tag.getInt(KEY_VERSION) <= VERSION
            && kind.equals(tag.getString(KEY_KIND));
    }

    /**
     * RS 过滤槽 → 子标签。
     * <p>用 RS 自己的 {@code save}（它写 {@code rf} + {@code fm} 两个键），因此「复制了什么」
     * 与「RS 存档里存了什么」永远是同一份格式；本模组不碰里面的任何键。</p>
     */
    public static void writeFilter(final CompoundTag tag, final FilterWithFuzzyMode filter,
                                   final HolderLookup.Provider provider) {
        final CompoundTag filterTag = new CompoundTag();
        filter.save(filterTag, provider);
        tag.put(KEY_FILTER, filterTag);
    }

    /**
     * 子标签 → RS 过滤槽（含模糊模式）。{@code false} = 载荷里没有这一节，调用方应视为无效配置。
     * <p>写入走 RS 自己的 {@code load}：它会同步把过滤项推给网络节点（{@code notifyListeners}），
     * 因此粘贴之后<b>过滤真的生效</b>，不是只改了一份界面数据。</p>
     */
    public static boolean readFilter(final CompoundTag tag, final FilterWithFuzzyMode filter,
                                     final HolderLookup.Provider provider) {
        if (!tag.contains(KEY_FILTER, Tag.TAG_COMPOUND)) {
            return false;
        }
        filter.load(tag.getCompound(KEY_FILTER), provider);
        return true;
    }

    /** 类别 id 列表 → 字符串列表标签（顺序保留：顺序本身是界面展示顺序）。 */
    public static void writeCategories(final CompoundTag tag, final List<String> categoryIds) {
        final ListTag list = new ListTag();
        for (final String id : categoryIds) {
            if (id != null && !id.isEmpty()) {
                list.add(StringTag.valueOf(id));
            }
        }
        tag.put(KEY_CATEGORIES, list);
    }

    /** 字符串列表标签 → 类别 id 列表（缺失 / 类型不符 = 空表；清洗由各方块实体自己的 setter 负责）。 */
    public static List<String> readCategories(final CompoundTag tag) {
        final ListTag list = tag.getList(KEY_CATEGORIES, Tag.TAG_STRING);
        final List<String> ids = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            ids.add(list.getString(i));
        }
        return ids;
    }
}
