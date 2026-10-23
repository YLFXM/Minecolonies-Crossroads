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
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Asks the server to cycle the unit type of one road cell.
 *
 * <p>Only the world position travels; the server resolves it against the plan
 * and decides whether it is on the paved body, so a client cannot mark cells
 * outside the road or on somebody else's plan.</p>
 */
public record EditRoadCellPayload(BlockPos controllerPos, BlockPos cellPos)
        implements CustomPacketPayload {
    public static final Type<EditRoadCellPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MCReconstruction.MODID, "edit_road_cell"));
    public static final StreamCodec<RegistryFriendlyByteBuf, EditRoadCellPayload> CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeBlockPos(payload.controllerPos());
                buf.writeBlockPos(payload.cellPos());
            },
            buf -> new EditRoadCellPayload(buf.readBlockPos(), buf.readBlockPos()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
