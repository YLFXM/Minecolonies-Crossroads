/*
 * Copyright (C) 2026 YLFXM
 *
 * MineColonies: Crossroads - a feature expansion addon for MineColonies.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.YLFXM.rp.crossroads.network;

import com.YLFXM.rp.crossroads.Crossroads;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Asks the server to rebuild a road from another unit style, series or tier.
 *
 * <p>Both ids travel as strings rather than ordinals: the set of styles grows
 * with the pack, and an ordinal that shifted between builds would silently pick
 * the wrong one on a client that is one version off.</p>
 *
 * <p>The tier rides along instead of having its own packet: a series switch can
 * lower the highest tier (streets has 3, main has 4), so the two have to be
 * applied and clamped together or a switch would leave the road on a tier the
 * new series does not ship.</p>
 */
public record SetRoadStylePayload(BlockPos controllerPos, String style, String series, int level)
        implements CustomPacketPayload {
    public static final Type<SetRoadStylePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Crossroads.MODID, "set_road_style"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetRoadStylePayload> CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeBlockPos(payload.controllerPos());
                buf.writeUtf(payload.style(), 64);
                buf.writeUtf(payload.series(), 32);
                buf.writeVarInt(payload.level());
            },
            buf -> new SetRoadStylePayload(buf.readBlockPos(), buf.readUtf(64), buf.readUtf(32),
                    buf.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
