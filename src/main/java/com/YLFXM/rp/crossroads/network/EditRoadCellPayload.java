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

/** Requests a row edit on an identified bound plan, with draft endpoints for server validation. */
public record EditRoadCellPayload(BlockPos controllerPos, BlockPos cellPos, BlockPos a, BlockPos b, boolean mirror, java.util.UUID roadId)
        implements CustomPacketPayload {
    public static final Type<EditRoadCellPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Crossroads.MODID, "edit_road_cell"));
    public static final StreamCodec<RegistryFriendlyByteBuf, EditRoadCellPayload> CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeBlockPos(payload.controllerPos());
                buf.writeBlockPos(payload.cellPos());
                buf.writeBlockPos(payload.a()); buf.writeBlockPos(payload.b()); buf.writeBoolean(payload.mirror()); buf.writeUUID(payload.roadId());
            },
            buf -> new EditRoadCellPayload(buf.readBlockPos(), buf.readBlockPos(), buf.readBlockPos(), buf.readBlockPos(), buf.readBoolean(), buf.readUUID()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
