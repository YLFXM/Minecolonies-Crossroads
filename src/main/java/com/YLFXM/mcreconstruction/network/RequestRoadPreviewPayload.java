/*
 * Copyright (C) 2026 ZhuanZ (YLFXM)
 *
 * MineColonies: Reconstruction Plan - a feature expansion addon for MineColonies.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.YLFXM.mcreconstruction.network;

import com.YLFXM.mcreconstruction.MCReconstruction;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client asks the server for the blueprint the current A/B selection would
 * produce, so the planning preview can show real blocks instead of a box.
 *
 * <p>Carries the anchor and controller separately: the preview also runs for an
 * unbound wand, where the client synthesises both from endpoint A.</p>
 */
public record RequestRoadPreviewPayload(BlockPos controllerPos,
                                        BlockPos anchorPos,
                                        BlockPos a,
                                        BlockPos b,
                                        boolean mirror,
                                        int width,
                                        int lampSpacing,
                                        long sequence) implements CustomPacketPayload {
    public static final Type<RequestRoadPreviewPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MCReconstruction.MODID, "request_road_preview"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RequestRoadPreviewPayload> CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeBlockPos(payload.controllerPos());
                buf.writeBlockPos(payload.anchorPos());
                buf.writeBlockPos(payload.a());
                buf.writeBlockPos(payload.b());
                buf.writeBoolean(payload.mirror());
                buf.writeVarInt(Math.max(1, payload.width()));
                buf.writeVarInt(Math.max(0, payload.lampSpacing()));
                buf.writeLong(payload.sequence());
            },
            buf -> new RequestRoadPreviewPayload(
                    buf.readBlockPos(),
                    buf.readBlockPos(),
                    buf.readBlockPos(),
                    buf.readBlockPos(),
                    buf.readBoolean(),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readLong()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
