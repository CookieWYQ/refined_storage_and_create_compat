package cretae.cookiewyq.rs_create_compat.item;

import com.refinedmods.refinedstorage.common.api.support.HelpTooltipComponent;

import java.util.Optional;
import javax.annotation.Nullable;

import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

/**
 * 兼容模组方块物品：仿 RS 原版 BaseBlockItem，
 * 描述文本通过 {@link #getTooltipImage} 返回帮助组件（帮助图标 + 蓝色小字 + Shift 展开）。
 */
public class CompatBlockItem extends BlockItem {
    private final Block block;
    @Nullable
    private final Component helpText;

    public CompatBlockItem(final Block block, @Nullable final Component helpText) {
        super(block, new Properties());
        this.block = block;
        this.helpText = helpText;
    }

    @Override
    public Component getDescription() {
        return block.getName();
    }

    @Override
    public Component getName(final ItemStack stack) {
        return block.getName();
    }

    @Override
    public Optional<TooltipComponent> getTooltipImage(final ItemStack stack) {
        if (helpText == null) {
            return Optional.empty();
        }
        return Optional.of(new HelpTooltipComponent(helpText));
    }
}
