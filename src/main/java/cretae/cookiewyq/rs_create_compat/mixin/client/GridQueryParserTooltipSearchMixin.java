package cretae.cookiewyq.rs_create_compat.mixin.client;

import com.refinedmods.refinedstorage.api.resource.repository.ResourceRepositoryFilter;
import com.refinedmods.refinedstorage.common.api.grid.view.GridResource;
import com.refinedmods.refinedstorage.common.grid.query.GridQueryParser;
import com.refinedmods.refinedstorage.query.parser.node.LiteralNode;
import cretae.cookiewyq.rs_create_compat.client.grid.RsccGridTooltipSearch;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 把「裸文本也要搜 tooltip」接进精致存储的网格搜索（用户需求：搜索框直接打「水下呼吸」要能搜到附魔书）。
 *
 * <h2>一、为什么挂在 {@code parseLiteral} 而不是 {@code parse}</h2>
 * <p>RS 的查询是<b>一棵树的求值</b>：{@code GridQueryParser#parse} 把整串解析成
 * {@code &&} / {@code ||} / {@code !} / 括号 / 计数过滤的表达式，叶子由 {@code parseLiteral} 变成一个
 * 「这个名字有没有命中」的过滤器（{@code ResourceRepositoryFilter}）。</p>
 * <p>两种挂法的差别是<b>语义对不对</b>，不是风格：</p>
 * <ul>
 *     <li>挂在 {@code parse} 上（把整串与 tooltip 比一次）：如果玩家写 {@code 铁 && #forge:ingots}，
 *     拿整串去比 tooltip 会把 {@code &&}、{@code #} 也当成搜索词 —— 表达式一复杂就错，
 *     而且 {@code !} 的语义会被整串 OR 破坏；</li>
 *     <li>挂在 {@code parseLiteral} 上（每个叶子各自 OR 一次）：<b>裸文本在哪一层都是「名字或 tooltip」</b>，
 *     {@code 铁 && #forge:ingots}、{@code (铁||金) && <64} 的布尔结构完全保留，
 *     因为注入的只是「叶子判定 = 名字命中 或 tooltip 命中」，运算符与求值顺序一个字没动。</li>
 * </ul>
 * <p><b>必须说清楚的一处语义变化</b>：因为否定是对<b>整个叶子</b>取反（RS 的 {@code !foo} 是
 * {@code not(parseLiteral(foo))}），叶子被换成「名字或 tooltip」之后，{@code !foo} 的含义变成
 * <b>「名字与 tooltip 都不含 foo」</b>。例如中文客户端 {@code !minecraft} 以前几乎筛不掉东西
 * （物品名是中文），现在会藏起所有原版物品（它们的进阶 tooltip 行里有 {@code minecraft:xxx}）。
 * 这是「裸文本 = 搜遍所有文本」的必然结果，<b>不是 bug</b>，CHANGELOG 里对玩家如实写了这一条；
 * 不选「让 {@code !} 里的字面量退回只搜名字」是因为那会让同一个词在 {@code !} 内外有两种含义 ——
 * 比「否定也一起搜 tooltip」更难解释。</p>
 *
 * <h2>二、为什么是 {@code @At("RETURN")} + {@code setReturnValue}（包裹而非替换）</h2>
 * <p>{@code parseLiteral} 返回的过滤器只有一句话：「遍历 {@code getSearchableNames()} 看是否有子串命中」。
 * 我们<b>不重写</b>它，而是把它的结果与 {@link RsccGridTooltipSearch#matches} 做<b>逻辑或</b>：</p>
 * <pre>{@code
 * (repository, resource) -> nameOnly.test(repository, resource)
 *                         || RsccGridTooltipSearch.matches(resource, literal)
 * }</pre>
 * <p>这个顺序是刻意的：名字已命中的物品<b>短路</b>，不会去生成 tooltip ——
 * 生成 tooltip 是整个功能里唯一有开销的一步（见 {@code RsccGridTooltipSearch} 的缓存说明）。</p>
 *
 * <h2>三、{@code $} / {@code #} / {@code @} 为什么完全不受影响</h2>
 * <p>那三个前缀在 {@code parseUnaryOp} 里走 {@code ATTRIBUTE_MAPPING} → {@code attributeMatch}，
 * <b>根本不经过</b> {@code parseLiteral}（它们的「字面量」由 {@code UnaryOpNode} 直接持有）。
 * 所以本注入在结构上不可能改动 {@code $}/{@code #}/{@code @} 的既有语义 ——
 * 这也是选 {@code parseLiteral} 的第 4 个理由：注入面最小。</p>
 * <p>顺带纠正一个容易想当然的说法：RS 的搜索框左边<b>有</b>问号帮助图标，里面写了
 * {@code Use '$' to filter for resources with a specific tooltip}（{@code gui.refinedstorage.grid.search_help.tooltip_search}）；
 * 只是 RS 自带的 {@code zh_cn.json} 没翻译这行、且它是「前缀语法」而不是「直接搜」——
 * 所以问题在于发现性与手感，不是「RS 完全没提示」。本注入不改那行提示。</p>
 *
 * <h2>四、为什么登记在 {@code rs_create_compat.mixins.json} 的 {@code client} 数组</h2>
 * <p>注入体引用了本模组 {@code client} 包里的类（{@link RsccGridTooltipSearch}，它读
 * {@code Minecraft.getInstance().getLanguageManager()}）。目标类 {@code GridQueryParser} 本身在
 * 专用服务端也会被加载（{@code AbstractGridContainerMenu} 的静态字段），但 <b>{@code parseLiteral}
 * 在服务端永远不会被调用</b>：搜索文本只有一个来源 —— 客户端界面的搜索框
 * （{@code AbstractGridScreen#setSearchBox}），服务端菜单从不设置搜索框。
 * 因此把注入登记进 {@code client} 数组既正确（服务端不加载客户端专属类）又安全（服务端逻辑零改动）。</p>
 *
 * <h2>五、如果 RS 以后改了方法名 / 签名</h2>
 * <p>本工程的 mixin 配置是 {@code defaultRequire: 1}，注入点找不到时 Mixin 会<b>显式报错</b>
 * （{@code CriticalInjectionException}），而不是静默失效；{@code tools/verify_mixin_shadows.py}
 * 也会拿真实字节码把 {@code parseLiteral} 的存在性与入参逐一核对。两处都保证
 * 「功能没了」这件事不会悄悄发生。</p>
 */
@Mixin(GridQueryParser.class)
public abstract class GridQueryParserTooltipSearchMixin {
    /**
     * 把「裸文本 = 名字命中 或 tooltip 命中」接到每个字面量叶子上。
     *
     * @param node 字面量节点（{@code node.token().content()} 就是玩家写的那段裸文本）
     * @param cir  {@code parseLiteral} 的返回值（RS 原本的「只比名字」过滤器）
     */
    @Inject(method = "parseLiteral", at = @At("RETURN"), cancellable = true)
    private static void rscc$matchTooltipForPlainLiteral(
        final LiteralNode node,
        final CallbackInfoReturnable<ResourceRepositoryFilter<GridResource>> cir
    ) {
        final String literal = node.token().content();
        // 空字面量保持 RS 原样：空查询由 parse() 自己短路成恒真过滤器，走到这里说明句中含空字面量。
        if (literal == null || literal.isBlank()) {
            return;
        }
        final ResourceRepositoryFilter<GridResource> nameOnly = cir.getReturnValue();
        cir.setReturnValue((repository, resource) ->
            nameOnly.test(repository, resource) || RsccGridTooltipSearch.matches(resource, literal));
    }
}
