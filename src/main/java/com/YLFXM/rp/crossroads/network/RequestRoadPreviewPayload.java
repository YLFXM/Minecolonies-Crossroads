/*
 * Copyright (C) 2026 YLFXM
 *
 * MineColonies: Crossroads - a feature expansion addon for MineColonies.
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
package com.YLFXM.rp.crossroads.network;

import com.YLFXM.rp.crossroads.Crossroads;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client asks the server for the blueprint the current A/B selection would
 * produce, so the planning preview can show real blocks instead of a box.
 *
 * <p>Carries the bound plan UUID; reusing a deleted controller coordinate cannot
 * make a late preview request mutate a different road.</p>
 */
public record RequestRoadPreviewPayload(BlockPos controllerPos,
                                        BlockPos anchorPos,
                                        BlockPos a,
                                        BlockPos b,
                                        boolean mirror,
                                        int width,
                                        int lampSpacing,
                                        long sequence, java.util.UUID roadId,java.util.UUID jointPeer) implements CustomPacketPayload {
    public RequestRoadPreviewPayload(BlockPos controller,BlockPos anchor,BlockPos a,BlockPos b,boolean mirror,int width,int lamps,long sequence,java.util.UUID road) {this(controller,anchor,a,b,mirror,width,lamps,sequence,road,new java.util.UUID(0,0));}
    public static final Type<RequestRoadPreviewPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Crossroads.MODID, "request_road_preview"));

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
                buf.writeUUID(payload.roadId());
                buf.writeUUID(payload.jointPeer());
            },
            buf -> new RequestRoadPreviewPayload(
                    buf.readBlockPos(),
                    buf.readBlockPos(),
                    buf.readBlockPos(),
                    buf.readBlockPos(),
                    buf.readBoolean(),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readLong(), buf.readUUID(),buf.readUUID()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
