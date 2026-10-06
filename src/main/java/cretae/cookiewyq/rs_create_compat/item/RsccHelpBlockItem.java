package cretae.cookiewyq.rs_create_compat.item;

import com.refinedmods.refinedstorage.common.support.BaseBlockItem;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

/**
 * 本模组机器方块物品的共用基类：直接复用 RS 原生的「常显帮助」机制。
 *
 * <h2>为什么不自己处理 tooltip</h2>
 * <p>RS 的 {@link BaseBlockItem} 会把构造时传入的帮助文本包成它自己的
 * {@code HelpTooltipComponent}，只要把鼠标放上去就<b>一直显示</b>。
 * 上一轮曾把它改成「按住 Shift 才显示」，但用户明确要求
 * 「RS 常显的提示不用删」—— 因此这里把 {@code helpText} 原样交给父类，
 * 由 RS 那套机制负责常显，本类<b>不再</b>覆写 {@code appendHoverText}。</p>
 */
public class RsccHelpBlockItem extends BaseBlockItem {
    public RsccHelpBlockItem(final Block block, @Nullable final Component helpText) {
        super(block, helpText);
    }
}
