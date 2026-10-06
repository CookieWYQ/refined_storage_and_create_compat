package com.refinedmods.refinedstorage.common.autocrafting;

import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.common.api.RefinedStorageClientApi;
import com.refinedmods.refinedstorage.common.api.support.resource.ResourceRendering;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;

import static com.refinedmods.refinedstorage.common.util.IdentifierUtil.createIdentifier;
import static com.refinedmods.refinedstorage.common.util.IdentifierUtil.createTranslation;

public class TaskCompletedToast implements Toast {
    private static final ResourceLocation SPRITE = createIdentifier("autocrafting_task_completed_toast");
    private static final MutableComponent TITLE = createTranslation(
        "misc",
        "autocrafting_task_completed"
    );

    private static final long TIME_VISIBLE = 5000;
    private static final int MARGIN = 4;

    private final ResourceKey resource;
    private final ResourceRendering rendering;
    private final MutableComponent resourceTitle;

    public TaskCompletedToast(final ResourceKey resource, final long amount) {
        this.resource = resource;
        this.rendering = RefinedStorageClientApi.INSTANCE.getResourceRendering(resource.getClass());
        this.resourceTitle = Component.literal(rendering.formatAmount(amount, true))
            .append(" ")
            .append(rendering.getDisplayName(resource));
    }

    @Override
    public Visibility render(final GuiGraphics graphics,
                             final ToastComponent toastComponent,
                             final long timeSinceLastVisible) {
        graphics.blitSprite(SPRITE, 0, 0, width(), height());
        rendering.render(resource, graphics, 8, 8);
        final Font font = Minecraft.getInstance().font;
        graphics.drawString(font, TITLE, 8 + 18 + MARGIN, 7, 0xFFFFA500);
        graphics.drawString(font, resourceTitle, 8 + 18 + MARGIN, 7 + 2 + 9, 0xFFFFFFFF);
        return timeSinceLastVisible >= TIME_VISIBLE ? Visibility.HIDE : Visibility.SHOW;
    }
}
