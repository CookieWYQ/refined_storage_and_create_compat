package com.refinedmods.refinedstorage.common.support.packet.s2c;

import com.refinedmods.refinedstorage.common.api.support.resource.PlatformResourceKey;
import com.refinedmods.refinedstorage.common.support.resource.ResourceCodecs;
import com.refinedmods.refinedstorage.common.util.ClientPlatformUtil;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import static com.refinedmods.refinedstorage.common.util.IdentifierUtil.createIdentifier;

public record AutocraftingTaskCompletedPacket(PlatformResourceKey resource, long amount)
    implements CustomPacketPayload {
    public static final Type<AutocraftingTaskCompletedPacket> PACKET_TYPE = new Type<>(
        createIdentifier("autocrafting_task_completed")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, AutocraftingTaskCompletedPacket> STREAM_CODEC =
        StreamCodec.composite(
            ResourceCodecs.STREAM_CODEC, AutocraftingTaskCompletedPacket::resource,
            ByteBufCodecs.VAR_LONG, AutocraftingTaskCompletedPacket::amount,
            AutocraftingTaskCompletedPacket::new
        );

    public static void handle(final AutocraftingTaskCompletedPacket packet) {
        ClientPlatformUtil.autocraftingTaskCompleted(packet.resource, packet.amount);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return PACKET_TYPE;
    }
}

